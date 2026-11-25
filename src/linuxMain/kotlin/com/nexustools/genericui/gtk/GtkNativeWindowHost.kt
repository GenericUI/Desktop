@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.animation.Animator
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.event.KeyEvent
import com.nexustools.genericui.event.PointerDragManager
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.impl.GtkMenuBarNativeImpl
import com.nexustools.genericui.gtk.titlebar.GtkCustomTitleBarWidget
import com.nexustools.genericui.gtk.titlebar.GtkWindowTitleBarController
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkRetainedContainerSupport
import com.nexustools.genericui.gtk.widget.GtkSurfaceRenderHelper
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.linux.LinuxX11WindowHintsBridge
import com.nexustools.genericui.linux.titlebar.LinuxOverlayTitleBarResolver
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarProfileManager
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import platform.posix.usleep
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * Pure retained GTK4 `GtkWindow` / `GtkDialog` lifecycle host, GLib main loop coordinator,
 * custom titlebar & chrome bar orchestrator, undecorated edge resize bridge, and `GtkWidgetPaintable`
 * screenshot capturer with headless Cairo/GSK fallback.
 */
object GtkNativeWindowHost {
    private val sessions = LinkedHashMap<Long, GtkWindowSession>(8)
    private val lastObservedMinimizedByKey = HashMap<Long, Boolean>()
    private val vblankClockOrigin = TimeSource.Monotonic.markNow()
    private var appInitialized = false
    private var cursorLockListenerInstalled = false
    private var eventLoopRunning = false
    private var stopRequested = false
    private val hostFallbackWatcherId = kotlin.concurrent.AtomicInt(-1)
    private val surfaceFrameWakeScheduled = kotlin.concurrent.AtomicInt(0)

    var visualTestMode: Boolean = false

    fun sessionCount(): Int = sessions.size

    fun activeGtkWindowPtr(): COpaquePointer? = sessions.values.firstOrNull { !it.closed }?.gtkWindow

    internal fun activeTickWidgetPtr(): COpaquePointer? {
        for (session in sessions.values) {
            if (!session.closed && Gtk4DynamicBindings.gtkWidgetGetRealized(session.rootVBox)) {
                return session.rootVBox
            }
        }
        for (session in sessions.values) {
            if (!session.closed && Gtk4DynamicBindings.gtkWidgetGetRealized(session.gtkWindow)) {
                return session.gtkWindow
            }
        }
        return null
    }

    internal fun isLiveTickWidget(widget: COpaquePointer): Boolean =
        sessions.values.any {
            !it.closed && (it.rootVBox === widget || it.contentLayoutBox === widget || it.gtkWindow === widget)
        }

    internal fun sessionForWindow(windowImpl: GtkWindowNativeImpl): GtkWindowSession? =
        sessions[windowImpl.nativeWidget.nativePointer]

    internal fun windowX11Xid(windowKey: Long): ULong {
        val session = sessions[windowKey] ?: return 0uL
        if (session.closed) return 0uL
        return Gtk4DynamicBindings.gtkWindowX11Xid(session.gtkWindow)
    }

    internal fun shouldUseCustomLinuxTitleBar(windowImpl: GtkWindowNativeImpl): Boolean =
        !windowImpl.isUndecorated() &&
            !windowImpl.isFullscreen() &&
            LinuxTitleBarProfileManager.currentProfile().isReplicable &&
            (windowImpl.getTitleBarInstructions()?.customTitleBar == true)

    fun prepareEventLoop() {
        ensureAppInitialized()
    }

    internal fun ensureAppInitialized(): Boolean {
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) {
            return false
        }
        installGlobalHooks()
        if (!appInitialized) {
            appInitialized = true
        }
        return true
    }

    private fun installGlobalHooks() {
        DesktopNativeWidget.onNativeTreeInvalidated = {
            requestRedraw()
        }
        DesktopNativeWidget.overlayInvalidationListener = { _ ->
            requestRedraw()
        }
        DesktopNativeWidget.onVBlankWidgetsChanged = {
            syncHostVBlankDemand()
        }
        DesktopNativeWidget.onSurfaceFrameReadyGlobal = { _ ->
            scheduleSurfaceFrameWakeup()
        }
        DesktopNativeWidget.onCursorUpdated = {
            if (appInitialized && GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
                if (GenericUiThreading.isOnUiThread()) {
                    syncCursorsOnUiThread()
                } else {
                    GenericUiThreading.runOnUi { syncCursorsOnUiThread() }
                }
            }
        }
        if (!cursorLockListenerInstalled) {
            cursorLockListenerInstalled = true
            PointerDragManager.addCursorLockListener { _, _, _ ->
                if (appInitialized && GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
                    if (GenericUiThreading.isOnUiThread()) {
                        syncCursorsOnUiThread()
                    } else {
                        GenericUiThreading.runOnUi { syncCursorsOnUiThread() }
                    }
                }
            }
        }

        DesktopDialogWindowNativeImpl.onShowDialogHook = { dialogImpl, ownerWindow ->
            if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
                presentDialog(dialogImpl, ownerWindow)
            }
        }
        DesktopDialogWindowNativeImpl.onCloseDialogHook = { dialogImpl ->
            if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled) {
                closeDialogSession(dialogImpl)
            }
        }
        DesktopDialogWindowNativeImpl.onTitleChangedHook = { dialogImpl, title ->
            sessions[dialogImpl.nativeWidget.nativePointer]?.let { s ->
                Gtk4DynamicBindings.gtkWindowSetTitle(s.gtkWindow, title)
            }
        }
    }

    fun requestRedraw() {
        if (!appInitialized || !GtkApplicationNativeImpl.interactiveGtkGuiEnabled) return
        if (sessions.values.none { !it.closed }) return
        if (GenericUiThreading.isOnUiThread()) {
            requestRedrawOnUiThread()
        } else {
            GenericUiThreading.runOnUi { requestRedrawOnUiThread() }
        }
    }

    internal fun requestRedrawOnUiThread() {
        if (!appInitialized || !GtkApplicationNativeImpl.interactiveGtkGuiEnabled) return
        for (session in sessions.values) {
            if (session.closed) continue
            session.surfaceBackgroundDa?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
            session.titleBarWidget?.queueDraw()
            Gtk4DynamicBindings.gtkWidgetQueueDraw(session.rootVBox)
            GtkCustomLayoutManager.requestRelayout(session.contentLayoutBox)
        }
    }

    private fun applyLockedSessionCursor(session: GtkWindowSession) {
        val locked = PointerDragManager.lockedCursor ?: return
        val lockedName = GtkWidgetLifecycleBridge.resolveGtkCursorName(locked) ?: "default"
        if (session.lastAppliedCursorKey != lockedName) {
            session.lastAppliedCursorKey = lockedName
            Gtk4DynamicBindings.gtkWidgetSetCursorFromName(session.rootVBox, lockedName)
        }
    }

    private fun syncCursorsOnUiThread() {
        for (session in sessions.values) {
            if (session.closed) continue
            val root = session.windowImpl?.nativeWidget ?: session.dialogImpl?.nativeWidget ?: continue
            GtkWidgetLifecycleBridge.syncSubtreeCursors(root)
            if (PointerDragManager.isCursorLocked) {
                applyLockedSessionCursor(session)
            } else if (session.lastMouseX >= 0f && session.lastMouseY >= 0f) {
                updateSessionEdgeCursor(session, session.lastMouseX, session.lastMouseY)
            }
        }
    }

    fun syncThemeAppearance(mode: ThemeMode) {
        if (!ensureAppInitialized()) return
        val scheme = when (mode) {
            ThemeMode.DARK -> Gtk4DynamicBindings.ADW_COLOR_SCHEME_FORCE_DARK
            ThemeMode.LIGHT -> Gtk4DynamicBindings.ADW_COLOR_SCHEME_FORCE_LIGHT
            ThemeMode.AUTO -> Gtk4DynamicBindings.ADW_COLOR_SCHEME_DEFAULT
        }
        Gtk4DynamicBindings.adwStyleManagerSetColorScheme(scheme)
        requestRedraw()
    }

    fun presentWindow(winImpl: GtkWindowNativeImpl) {
        if (!ensureAppInitialized()) return
        val key = winImpl.nativeWidget.nativePointer
        val existing = sessions[key]
        if (existing != null && !existing.closed) {
            restoreWindowModeAndPresent(existing, winImpl)
            return
        }

        val isSecondaryWindow = sessions.values.any { !it.closed && it.windowImpl != null }
        val undecorated = winImpl.isUndecorated()
        val useCustomLinuxTitleBar = shouldUseCustomLinuxTitleBar(winImpl)
        val winSize = winImpl.getWindowSize()
        val initW = winSize.width.coerceAtLeast(if (undecorated) 64 else 320)
        val initH = winSize.height.coerceAtLeast(if (undecorated) 64 else 240)

        val gtkWin = Gtk4DynamicBindings.gtkWindowNew() ?: return
        val rootVBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 0)
        val rootContentHost = Gtk4DynamicBindings.gtkOverlayNew()
        val contentLayoutBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 0)
        if (rootVBox == null || rootContentHost == null || contentLayoutBox == null) {
            Gtk4DynamicBindings.gtkWindowDestroy(gtkWin)
            return
        }

        val surfaceBgDa = GtkWidgetLifecycleBridge.createDrawingSurfaceForPainter(winImpl.nativeWidget) { cr, w, h ->
            paintWindowBackdrop(cr, winImpl, w, h)
        }
        if (surfaceBgDa != null) {
            Gtk4DynamicBindings.gtkWidgetSetCanTarget(surfaceBgDa, false)
            Gtk4DynamicBindings.gtkOverlaySetChild(rootContentHost, surfaceBgDa)
        }
        Gtk4DynamicBindings.gtkOverlayAddOverlay(rootContentHost, contentLayoutBox)
        Gtk4DynamicBindings.gtkWidgetSetExpand(rootContentHost, hexpand = true, vexpand = true)
        Gtk4DynamicBindings.gtkWidgetSetExpand(contentLayoutBox, hexpand = true, vexpand = true)

        val titleBarWidget = GtkCustomTitleBarWidget(winImpl)
        titleBarWidget.ensureWidget()?.let { tbPtr ->
            Gtk4DynamicBindings.gtkWidgetSetExpand(tbPtr, hexpand = true, vexpand = false)
            Gtk4DynamicBindings.gtkBoxAppend(rootVBox, tbPtr)
        }
        Gtk4DynamicBindings.gtkBoxAppend(rootVBox, rootContentHost)

        Gtk4DynamicBindings.gtkWindowSetTitlebar(gtkWin, null)
        Gtk4DynamicBindings.gtkWindowSetDecorated(gtkWin, !undecorated && !useCustomLinuxTitleBar)
        Gtk4DynamicBindings.gtkWindowSetUndecoratedRectClass(gtkWin, undecorated)
        if (isSecondaryWindow || undecorated) {
            Gtk4DynamicBindings.gtkWindowSetHideOnClose(gtkWin, true)
        }
        Gtk4DynamicBindings.gtkWindowSetDefaultSize(gtkWin, initW, initH)
        Gtk4DynamicBindings.gtkWindowSetTitle(gtkWin, winImpl.getTitle().ifBlank { "GenericUI" })
        Gtk4DynamicBindings.gtkWindowSetChild(gtkWin, rootVBox)

        val session = GtkWindowSession(
            windowKey = key,
            gtkWindow = gtkWin,
            rootVBox = rootVBox,
            rootContentHost = rootContentHost,
            contentLayoutBox = contentLayoutBox,
            titleBarWidget = titleBarWidget,
            surfaceBackgroundDa = surfaceBgDa,
            windowImpl = winImpl
        ).apply {
            usesCustomLinuxTitleBar = useCustomLinuxTitleBar
        }
        sessions[key] = session
        lastObservedMinimizedByKey[key] = false

        val controller = winImpl.nativeWidget.customLayoutController
            ?: DesktopContainerLayoutController(
                host = winImpl,
                defaultDisplay = CssDisplay.FLEX,
                defaultFlexDirection = FlexDirection.COLUMN,
                applyScaleToBounds = false
            ).also { winImpl.nativeWidget.customLayoutController = it }
        winImpl.nativeWidget.realGtkWidgetPtr = gtkWin
        winImpl.nativeWidget.containerLayoutBoxPtr = contentLayoutBox
        winImpl.nativeWidget.backgroundSurfacePtr = surfaceBgDa
        winImpl.nativeWidget.onBeforeLayoutAllocate = { w, h ->
            onContentLayoutAllocate(session, winImpl, w, h)
        }
        GtkCustomLayoutManager.attachToContainer(winImpl, contentLayoutBox, controller)
        for (child in winImpl.getLogicalChildren()) {
            GtkRetainedContainerSupport.attachChildWidgetIfRealized(winImpl, child)
        }

        attachSessionControllers(session)
        syncWindowChromeBars(winImpl)
        syncWindowProperties(winImpl)

        val ownerImpl = winImpl.ownerWindow as? GtkWindowNativeImpl
        if (ownerImpl != null && !undecorated) {
            sessions[ownerImpl.nativeWidget.nativePointer]?.let { ownerSession ->
                Gtk4DynamicBindings.gtkWindowSetTransientFor(gtkWin, ownerSession.gtkWindow)
            }
        }

        applyRequestedModeForShow(session, winImpl)
        activateWindowSessionFocus(session)
        Gtk4DynamicBindings.gtkWindowPresent(gtkWin)
        syncSessionX11Hints(session)
        titleBarWidget.syncVisibilityAndHeight(initW)
        GtkCustomLayoutManager.requestRelayout(contentLayoutBox)
    }

    private fun onContentLayoutAllocate(
        session: GtkWindowSession,
        winImpl: GtkWindowNativeImpl,
        width: Int,
        height: Int
    ) {
        if (session.closed || width <= 0 || height <= 0) return
        val rawFs = Gtk4DynamicBindings.gtkWindowIsFullscreen(session.gtkWindow)
        val isFs = resolveEffectiveFullscreenState(session, rawFs)
        val isMax = Gtk4DynamicBindings.gtkWindowIsMaximized(session.gtkWindow)
        winImpl.syncHostClientSize(width, height, fullscreenNow = isFs, maximizedNow = isMax)

        if (session.paintedFrameCount > 1 && winImpl.isVisible() && !isFs && !isMax &&
            winImpl.getWindowState() == WindowState.NORMAL
        ) {
            val moveSuppressElapsed = session.suppressMoveUntilMark?.elapsedNow()?.inWholeMilliseconds ?: 999L
            if (moveSuppressElapsed >= 200L) {
                val xid = Gtk4DynamicBindings.gtkWindowX11Xid(session.gtkWindow)
                if (xid != 0uL) {
                    val pos = LinuxX11WindowHintsBridge.queryWindowPosition(xid)
                    if (pos != null && (pos.first != session.lastReportedX || pos.second != session.lastReportedY)) {
                        session.lastReportedX = pos.first
                        session.lastReportedY = pos.second
                        winImpl.notifyHostPositionChanged(pos.first, pos.second)
                    }
                }
            }
        }

        val totalW = Gtk4DynamicBindings.gtkWidgetGetWidth(session.gtkWindow).takeIf { it > 0 } ?: width
        session.lastPaintedWidth = width
        session.lastPaintedHeight = height
        session.paintedFrameCount++
        session.titleBarWidget?.syncVisibilityAndHeight(totalW)
    }

    private fun resolveEffectiveFullscreenState(session: GtkWindowSession, rawFs: Boolean): Boolean {
        val pendingFs = session.pendingTargetFullscreen ?: return rawFs
        val elapsedMs = session.fullscreenTransitionMark?.elapsedNow()?.inWholeMilliseconds ?: 0L
        return if (rawFs == pendingFs || elapsedMs >= 1200L) {
            session.pendingTargetFullscreen = null
            rawFs
        } else {
            pendingFs
        }
    }

    private fun activateWindowSessionFocus(activeSession: GtkWindowSession) {
        for (other in sessions.values) {
            if (other.closed) continue
            val otherWin = other.windowImpl ?: continue
            val shouldFocus = other.windowKey == activeSession.windowKey
            val wasFocused = otherWin.isFocusedState()
            val hadFocusedAttr = otherWin.nativeWidget.attributes.containsKey("focused")
            if (wasFocused != shouldFocus || !hadFocusedAttr) {
                otherWin.notifyHostFocusChanged(shouldFocus)
                if (other.windowKey != activeSession.windowKey) {
                    other.titleBarWidget?.queueDraw()
                }
            }
        }
    }

    private fun applyRequestedModeForShow(session: GtkWindowSession, windowImpl: GtkWindowNativeImpl) {
        if (windowImpl.isFullscreen()) {
            session.pendingTargetFullscreen = true
            session.fullscreenTransitionMark = TimeSource.Monotonic.markNow()
            Gtk4DynamicBindings.gtkWindowFullscreen(session.gtkWindow)
        } else {
            val currentlyFs = Gtk4DynamicBindings.gtkWindowIsFullscreen(session.gtkWindow)
            if (currentlyFs) {
                session.pendingTargetFullscreen = false
                session.fullscreenTransitionMark = TimeSource.Monotonic.markNow()
                Gtk4DynamicBindings.gtkWindowUnfullscreen(session.gtkWindow)
            } else {
                session.pendingTargetFullscreen = null
            }
            if (windowImpl.getWindowState() == WindowState.MAXIMIZED) {
                Gtk4DynamicBindings.gtkWindowMaximize(session.gtkWindow)
            } else if (Gtk4DynamicBindings.gtkWindowIsMaximized(session.gtkWindow)) {
                Gtk4DynamicBindings.gtkWindowUnmaximize(session.gtkWindow)
            }
        }
    }

    private fun restoreWindowModeAndPresent(session: GtkWindowSession, windowImpl: GtkWindowNativeImpl) {
        syncWindowProperties(windowImpl)
        syncWindowChromeBars(windowImpl)
        if (!windowImpl.isFullscreen() && windowImpl.getWindowState() == WindowState.NORMAL) {
            val size = windowImpl.getWindowSize()
            Gtk4DynamicBindings.gtkWindowSetDefaultSize(session.gtkWindow, size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
        }
        applyRequestedModeForShow(session, windowImpl)
        activateWindowSessionFocus(session)
        Gtk4DynamicBindings.gtkWidgetSetVisible(session.gtkWindow, true)
        Gtk4DynamicBindings.gtkWindowPresent(session.gtkWindow)
        syncSessionX11Hints(session)
        GtkCustomLayoutManager.requestRelayout(session.contentLayoutBox)
        requestRedrawOnUiThread()
    }

    private fun presentDialog(
        dialogImpl: DesktopDialogWindowNativeImpl<*>,
        ownerWindow: DesktopWindowNativeImpl<*>?
    ) {
        if (!ensureAppInitialized()) return
        val key = dialogImpl.nativeWidget.nativePointer
        val existing = sessions[key]
        if (existing != null && !existing.closed) {
            Gtk4DynamicBindings.gtkWindowSetTitle(existing.gtkWindow, dialogImpl.getTitle())
            Gtk4DynamicBindings.gtkWindowPresent(existing.gtkWindow)
            GtkCustomLayoutManager.requestRelayout(existing.contentLayoutBox)
            return
        }

        val ownerSession = (ownerWindow as? GtkWindowNativeImpl)
            ?.let { sessions[it.nativeWidget.nativePointer] }
            ?.takeIf {
                val w = it.windowImpl
                !it.closed && w != null && !w.isDisposed && w.isVisible() &&
                    Gtk4DynamicBindings.gtkWidgetGetVisible(it.gtkWindow)
            }
        val maxW = ownerSession?.lastPaintedWidth?.takeIf { it > 100 } ?: 860
        val maxH = ownerSession?.lastPaintedHeight?.takeIf { it > 100 } ?: 680
        val intrinsic = dialogImpl.measureIntrinsicSize(maxW.toFloat(), maxH.toFloat())
        val initW = intrinsic.width.coerceIn(280, maxW)
        val initH = intrinsic.height.coerceIn(140, maxH)

        val gtkWin = Gtk4DynamicBindings.gtkWindowNew() ?: return
        val rootVBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 0)
        val rootContentHost = Gtk4DynamicBindings.gtkOverlayNew()
        val contentLayoutBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 0)
        if (rootVBox == null || rootContentHost == null || contentLayoutBox == null) {
            Gtk4DynamicBindings.gtkWindowDestroy(gtkWin)
            return
        }

        val dlgHandle = dialogImpl.nativeWidget as? GtkWidgetHandle
        val surfaceBgDa = dlgHandle?.let { handle ->
            GtkWidgetLifecycleBridge.createDrawingSurfaceForPainter(handle) { cr, w, h ->
                paintSurfaceColors(cr, w, h, null)
            }
        }
        if (surfaceBgDa != null) {
            Gtk4DynamicBindings.gtkWidgetSetCanTarget(surfaceBgDa, false)
            Gtk4DynamicBindings.gtkOverlaySetChild(rootContentHost, surfaceBgDa)
        }
        Gtk4DynamicBindings.gtkOverlayAddOverlay(rootContentHost, contentLayoutBox)
        Gtk4DynamicBindings.gtkWidgetSetExpand(rootContentHost, hexpand = true, vexpand = true)
        Gtk4DynamicBindings.gtkWidgetSetExpand(contentLayoutBox, hexpand = true, vexpand = true)
        Gtk4DynamicBindings.gtkBoxAppend(rootVBox, rootContentHost)

        Gtk4DynamicBindings.gtkWindowSetDefaultSize(gtkWin, initW, initH)
        Gtk4DynamicBindings.gtkWindowSetTitle(gtkWin, dialogImpl.getTitle().ifBlank { "Dialog" })
        Gtk4DynamicBindings.gtkWindowSetResizable(
            gtkWin,
            (dialogImpl.nativeWidget.attributes["resizable"] as? Boolean) != false
        )
        if (ownerSession != null) {
            Gtk4DynamicBindings.gtkWindowSetModal(gtkWin, dialogImpl.isModal)
            Gtk4DynamicBindings.gtkWindowSetTransientFor(gtkWin, ownerSession.gtkWindow)
        } else {
            Gtk4DynamicBindings.gtkWindowSetModal(gtkWin, false)
        }
        Gtk4DynamicBindings.gtkWindowSetChild(gtkWin, rootVBox)

        val session = GtkWindowSession(
            windowKey = key,
            gtkWindow = gtkWin,
            rootVBox = rootVBox,
            rootContentHost = rootContentHost,
            contentLayoutBox = contentLayoutBox,
            surfaceBackgroundDa = surfaceBgDa,
            dialogImpl = dialogImpl,
            ownerWindowKey = ownerSession?.windowKey
        )
        sessions[key] = session

        @Suppress("UNCHECKED_CAST")
        val typedDlg = dialogImpl as DesktopBaseContainerNativeImpl<GtkWidgetHandle>
        val dlgController = dlgHandle?.customLayoutController
            ?: DesktopContainerLayoutController(
                host = typedDlg,
                defaultDisplay = CssDisplay.FLEX,
                defaultFlexDirection = FlexDirection.COLUMN,
                applyScaleToBounds = false
            )
        dlgHandle?.let { h ->
            h.customLayoutController = dlgController
            h.realGtkWidgetPtr = gtkWin
            h.containerLayoutBoxPtr = contentLayoutBox
            h.backgroundSurfacePtr = surfaceBgDa
            h.onBeforeLayoutAllocate = { w, hPx ->
                session.lastPaintedWidth = w
                session.lastPaintedHeight = hPx
                session.paintedFrameCount++
            }
        }
        GtkCustomLayoutManager.attachToContainer(typedDlg, contentLayoutBox, dlgController)
        for (child in typedDlg.getLogicalChildren()) {
            GtkRetainedContainerSupport.attachChildWidgetIfRealized(typedDlg, child)
        }

        attachSessionControllers(session)
        Gtk4DynamicBindings.gtkWindowPresent(gtkWin)
        GtkCustomLayoutManager.requestRelayout(contentLayoutBox)
    }

    private fun attachSessionControllers(session: GtkWindowSession) {
        val ref = StableRef.create(session)
        session.sessionRef = ref
        val refPtr = ref.asCPointer()

        val closeCb = staticCFunction { _: COpaquePointer?, userData: COpaquePointer? ->
            userData?.asStableRef<GtkWindowSession>()?.get()?.let { s ->
                handleWindowCloseRequest(s)
            }
            1
        }
        LinuxGlibGioBindings.signalConnect(session.gtkWindow, "close-request", closeCb, refPtr)

        val mapCb = staticCFunction { _: COpaquePointer?, userData: COpaquePointer? ->
            userData?.asStableRef<GtkWindowSession>()?.get()?.let { s ->
                syncSessionX11Hints(s)
                GtkAnimationNativeImpl.activeInstance?.onHostSessionMappedChanged()
            }
            Unit
        }
        LinuxGlibGioBindings.signalConnect(session.gtkWindow, "map", mapCb, refPtr)

        val activeNotifyCb = staticCFunction { _: COpaquePointer?, _: COpaquePointer?, userData: COpaquePointer? ->
            userData?.asStableRef<GtkWindowSession>()?.get()?.let { s ->
                val isActive = Gtk4DynamicBindings.gtkWindowIsActive(s.gtkWindow)
                s.windowImpl?.let { winImpl ->
                    GenericUiThreading.runOnLogic {
                        winImpl.notifyHostFocusChanged(isActive)
                    }
                    s.titleBarWidget?.queueDraw()
                }
            }
            Unit
        }
        LinuxGlibGioBindings.signalConnect(session.gtkWindow, "notify::is-active", activeNotifyCb, refPtr)

        val motionCtrl = Gtk4DynamicBindings.gtkEventControllerMotionNew()
        if (motionCtrl != null) {
            val moveCb = staticCFunction { _: COpaquePointer?, x: Double, y: Double, userData: COpaquePointer? ->
                userData?.asStableRef<GtkWindowSession>()?.get()?.let { s ->
                    s.lastMouseX = x.toFloat()
                    s.lastMouseY = y.toFloat()
                    if (PointerDragManager.isDragging) {
                        val ev = PointerEvent(
                            x = x.toFloat(),
                            y = y.toFloat(),
                            screenX = x.toFloat(),
                            screenY = y.toFloat()
                        )
                        PointerDragManager.notifyGlobalPointerMove(ev, coordinatesAreTargetLocal = false)
                    }
                    if (PointerDragManager.isCursorLocked) {
                        applyLockedSessionCursor(s)
                    } else if (!PointerDragManager.isDragging) {
                        updateSessionEdgeCursor(s, s.lastMouseX, s.lastMouseY)
                    }
                }
                Unit
            }
            val leaveCb = staticCFunction { _: COpaquePointer?, userData: COpaquePointer? ->
                userData?.asStableRef<GtkWindowSession>()?.get()?.let { s ->
                    if (PointerDragManager.isCursorLocked) return@let
                    if (s.lastAppliedCursorKey.isNotEmpty()) {
                        s.lastAppliedCursorKey = ""
                        Gtk4DynamicBindings.gtkWidgetSetCursorFromName(s.rootVBox, null)
                    }
                }
                Unit
            }
            LinuxGlibGioBindings.signalConnect(motionCtrl, "motion", moveCb, refPtr)
            LinuxGlibGioBindings.signalConnect(motionCtrl, "leave", leaveCb, refPtr)
            Gtk4DynamicBindings.gtkWidgetAddController(session.rootVBox, motionCtrl)
        }

        val edgeResizeClick = Gtk4DynamicBindings.gtkGestureClickNew()
        if (edgeResizeClick != null) {
            Gtk4DynamicBindings.gtkGestureSingleSetButton(edgeResizeClick, Gtk4DynamicBindings.GDK_BUTTON_PRIMARY)
            val pressCb = staticCFunction { _: COpaquePointer?, _: Int, x: Double, y: Double, userData: COpaquePointer? ->
                userData?.asStableRef<GtkWindowSession>()?.get()?.let { s ->
                    s.lastMouseX = x.toFloat()
                    s.lastMouseY = y.toFloat()
                    val edge = detectSessionResizeEdge(s, s.lastMouseX, s.lastMouseY)
                    if (edge >= 0) {
                        Gtk4DynamicBindings.gtkWindowBeginResize(s.gtkWindow, edge, x, y)
                    }
                }
                Unit
            }
            val releaseCb = staticCFunction { _: COpaquePointer?, _: Int, x: Double, y: Double, _: COpaquePointer? ->
                if (PointerDragManager.isDragging) {
                    val ev = PointerEvent(
                        x = x.toFloat(),
                        y = y.toFloat(),
                        screenX = x.toFloat(),
                        screenY = y.toFloat()
                    )
                    PointerDragManager.notifyGlobalPointerUp(ev, coordinatesAreTargetLocal = false)
                }
                Unit
            }
            LinuxGlibGioBindings.signalConnect(edgeResizeClick, "pressed", pressCb, refPtr)
            LinuxGlibGioBindings.signalConnect(edgeResizeClick, "released", releaseCb, refPtr)
            Gtk4DynamicBindings.gtkWidgetAddController(session.rootVBox, edgeResizeClick)
        }

        val keyCtrl = Gtk4DynamicBindings.gtkEventControllerKeyNew()
        if (keyCtrl != null) {
            val keyPressCb = staticCFunction { _: COpaquePointer?, keyval: UInt, keycode: UInt, state: UInt, userData: COpaquePointer? ->
                val s = userData?.asStableRef<GtkWindowSession>()?.get() ?: return@staticCFunction 0
                val ev = translateKeyEvent(keyval, keycode, state)
                GenericUiThreading.runOnLogic {
                    s.windowImpl?.dispatchKeyDown(ev)
                    (s.dialogImpl as? DesktopBaseNativeImpl<*>)?.dispatchKeyDown(ev)
                }
                0
            }
            val keyReleaseCb = staticCFunction { _: COpaquePointer?, keyval: UInt, keycode: UInt, state: UInt, userData: COpaquePointer? ->
                val s = userData?.asStableRef<GtkWindowSession>()?.get() ?: return@staticCFunction
                val ev = translateKeyEvent(keyval, keycode, state)
                GenericUiThreading.runOnLogic {
                    s.windowImpl?.dispatchKeyUp(ev)
                    (s.dialogImpl as? DesktopBaseNativeImpl<*>)?.dispatchKeyUp(ev)
                }
            }
            LinuxGlibGioBindings.signalConnect(keyCtrl, "key-pressed", keyPressCb, refPtr)
            LinuxGlibGioBindings.signalConnect(keyCtrl, "key-released", keyReleaseCb, refPtr)
            Gtk4DynamicBindings.gtkWidgetAddController(session.gtkWindow, keyCtrl)
        }
    }

    private fun updateSessionEdgeCursor(session: GtkWindowSession, x: Float, y: Float) {
        if (PointerDragManager.isCursorLocked) {
            applyLockedSessionCursor(session)
            return
        }
        val edge = detectSessionResizeEdge(session, x, y)
        if (edge >= 0) {
            val cursorName = resizeEdgeCursorCss(edge)
            if (session.lastAppliedCursorKey != cursorName) {
                session.lastAppliedCursorKey = cursorName
                Gtk4DynamicBindings.gtkWidgetSetCursorFromName(session.rootVBox, cursorName)
            }
            return
        }
        val rootWidget = session.windowImpl?.nativeWidget ?: session.dialogImpl?.nativeWidget
        val resolvedName = if (rootWidget != null) {
            val topChromeH = if (session.windowImpl != null) {
                GtkWindowTitleBarController.getEffectiveTitleBarHeight(session.windowKey) +
                    (session.mountedMenuBarPtr?.let { Gtk4DynamicBindings.gtkWidgetGetHeight(it).coerceAtLeast(0) } ?: 0) +
                    (session.mountedNavBarPtr?.let { Gtk4DynamicBindings.gtkWidgetGetHeight(it).coerceAtLeast(0) } ?: 0)
            } else {
                0
            }
            val ly = y - topChromeH.toFloat()
            val hit = if (ly >= 0f) hitTestWidgetAt(rootWidget, x, ly) else null
            GtkWidgetLifecycleBridge.resolveEffectiveGtkCursorName(hit ?: rootWidget)
        } else {
            null
        }
        // Clear rootVBox override when default/unspecified so child cursors ("none", "pointer", etc.) are not wiped out.
        val targetName = resolvedName?.takeIf { it != "default" }
        val nextKey = targetName ?: ""
        if (session.lastAppliedCursorKey != nextKey) {
            session.lastAppliedCursorKey = nextKey
            Gtk4DynamicBindings.gtkWidgetSetCursorFromName(session.rootVBox, targetName)
        }
    }

    private fun detectSessionResizeEdge(session: GtkWindowSession, x: Float, y: Float): Int {
        val winImpl = session.windowImpl ?: return -1
        if (!winImpl.isUndecorated() && !session.usesCustomLinuxTitleBar) return -1
        if ((winImpl.nativeWidget.attributes["resizable"] as? Boolean) == false) return -1
        if (winImpl.isFullscreen() ||
            Gtk4DynamicBindings.gtkWindowIsFullscreen(session.gtkWindow) ||
            Gtk4DynamicBindings.gtkWindowIsMaximized(session.gtkWindow)
        ) {
            return -1
        }
        val w = Gtk4DynamicBindings.gtkWidgetGetWidth(session.rootVBox).takeIf { it > 0 }
            ?: session.lastPaintedWidth
        val h = Gtk4DynamicBindings.gtkWidgetGetHeight(session.rootVBox).takeIf { it > 0 }
            ?: session.lastPaintedHeight
        if (w <= 0 || h <= 0) return -1
        val titleH = GtkWindowTitleBarController.getEffectiveTitleBarHeight(session.windowKey)
        val hit = hitTestWidgetAt(winImpl.nativeWidget, x, y - titleH.toFloat())
        return detectUndecoratedResizeEdgeRect(w, h, x, y, hit, winImpl.nativeWidget)
    }

    internal fun detectUndecoratedResizeEdgeRect(
        width: Int,
        height: Int,
        x: Float,
        y: Float,
        hitWidget: DesktopNativeWidget?,
        ownerWindowWidget: DesktopNativeWidget? = null
    ): Int {
        val impl = hitWidget?.attributes?.get("nativeImpl")
        val cursorCss = (hitWidget?.attributes?.get("cursor") as? String)?.trim()?.lowercase()
        val hitOverInteractive = hitWidget != null &&
            hitWidget !== ownerWindowWidget &&
            (cursorCss == "pointer" ||
                impl is DesktopButtonNativeImpl<*> ||
                impl is DesktopTextInputNativeImpl<*> ||
                LinuxOverlayTitleBarResolver.isWindowControlWidget(hitWidget))
        val mask = LinuxOverlayTitleBarResolver.computeResizeEdgeBitmask(
            width = width,
            height = height,
            x = x,
            y = y,
            borderPx = 6f,
            hitOverInteractiveControl = hitOverInteractive,
            perimeterPx = 2f
        )
        val onLeft = (mask and LinuxOverlayTitleBarResolver.EDGE_LEFT) != 0
        val onRight = (mask and LinuxOverlayTitleBarResolver.EDGE_RIGHT) != 0
        val onTop = (mask and LinuxOverlayTitleBarResolver.EDGE_TOP) != 0
        val onBottom = (mask and LinuxOverlayTitleBarResolver.EDGE_BOTTOM) != 0
        return when {
            onTop && onLeft -> Gtk4DynamicBindings.GDK_SURFACE_EDGE_NORTH_WEST
            onTop && onRight -> Gtk4DynamicBindings.GDK_SURFACE_EDGE_NORTH_EAST
            onBottom && onLeft -> Gtk4DynamicBindings.GDK_SURFACE_EDGE_SOUTH_WEST
            onBottom && onRight -> Gtk4DynamicBindings.GDK_SURFACE_EDGE_SOUTH_EAST
            onLeft -> Gtk4DynamicBindings.GDK_SURFACE_EDGE_WEST
            onRight -> Gtk4DynamicBindings.GDK_SURFACE_EDGE_EAST
            onTop -> Gtk4DynamicBindings.GDK_SURFACE_EDGE_NORTH
            onBottom -> Gtk4DynamicBindings.GDK_SURFACE_EDGE_SOUTH
            else -> -1
        }
    }

    internal fun resizeEdgeCursorCss(edge: Int): String = when (edge) {
        Gtk4DynamicBindings.GDK_SURFACE_EDGE_NORTH_WEST -> "nw-resize"
        Gtk4DynamicBindings.GDK_SURFACE_EDGE_NORTH -> "n-resize"
        Gtk4DynamicBindings.GDK_SURFACE_EDGE_NORTH_EAST -> "ne-resize"
        Gtk4DynamicBindings.GDK_SURFACE_EDGE_WEST -> "w-resize"
        Gtk4DynamicBindings.GDK_SURFACE_EDGE_EAST -> "e-resize"
        Gtk4DynamicBindings.GDK_SURFACE_EDGE_SOUTH_WEST -> "sw-resize"
        Gtk4DynamicBindings.GDK_SURFACE_EDGE_SOUTH -> "s-resize"
        Gtk4DynamicBindings.GDK_SURFACE_EDGE_SOUTH_EAST -> "se-resize"
        else -> "default"
    }

    private fun hitTestWidgetAt(root: DesktopNativeWidget, x: Float, y: Float): DesktopNativeWidget? {
        for (child in root.children.asReversed()) {
            if (!child.visible || child.disposed || child.isSelfPointerEventsNone()) continue
            val b = child.bounds
            val lx = x - b.x
            val ly = y - b.y
            if (lx >= 0f && ly >= 0f && lx < b.width && ly < b.height) {
                return hitTestWidgetAt(child, lx, ly) ?: child
            }
        }
        return null
    }

    fun syncWindowChromeBars(winImpl: GtkWindowNativeImpl) {
        val session = sessions[winImpl.nativeWidget.nativePointer] ?: return
        if (session.closed) return

        // Detach previously mounted root-vbox chrome bars safely before re-inserting in order.
        session.mountedMenuBarPtr?.let { ptr ->
            if (Gtk4DynamicBindings.gtkWidgetGetParent(ptr) == session.rootVBox) {
                Gtk4DynamicBindings.gtkWidgetUnparent(ptr)
            }
            session.mountedMenuBarPtr = null
        }
        session.mountedNavBarPtr?.let { ptr ->
            if (Gtk4DynamicBindings.gtkWidgetGetParent(ptr) == session.rootVBox) {
                Gtk4DynamicBindings.gtkWidgetUnparent(ptr)
            }
            session.mountedNavBarPtr = null
        }
        session.mountedBottomNavPtr?.let { ptr ->
            if (Gtk4DynamicBindings.gtkWidgetGetParent(ptr) == session.rootVBox) {
                Gtk4DynamicBindings.gtkWidgetUnparent(ptr)
            }
            session.mountedBottomNavPtr = null
        }

        val embeddedMenu = winImpl.nativeWidget.attributes["embedMenuBarInTitleBar"] as? Boolean == true
        val menuBarImpl = winImpl.nativeWidget.attributes["menuBar"] as? GtkMenuBarNativeImpl
        if (menuBarImpl != null && !embeddedMenu && !winImpl.isFullscreen()) {
            val mbPtr = menuBarImpl.nativeWidget.ensureLiveWidgetPtr()
            if (mbPtr != null) {
                if (Gtk4DynamicBindings.gtkWidgetGetParent(mbPtr) != null) {
                    Gtk4DynamicBindings.gtkWidgetUnparent(mbPtr)
                }
                Gtk4DynamicBindings.gtkWidgetSetExpand(mbPtr, hexpand = true, vexpand = false)
                Gtk4DynamicBindings.gtkWidgetInsertBefore(mbPtr, session.rootVBox, session.rootContentHost)
                session.mountedMenuBarPtr = mbPtr
            }
        }

        val navBarHandle = (winImpl.nativeWidget.attributes["navBar"] as? DesktopBaseNativeImpl<*>)?.nativeWidget as? GtkWidgetHandle
        navBarHandle?.ensureLiveWidgetPtr()?.let { nbPtr ->
            if (Gtk4DynamicBindings.gtkWidgetGetParent(nbPtr) != null) {
                Gtk4DynamicBindings.gtkWidgetUnparent(nbPtr)
            }
            Gtk4DynamicBindings.gtkWidgetSetExpand(nbPtr, hexpand = true, vexpand = false)
            Gtk4DynamicBindings.gtkWidgetInsertBefore(nbPtr, session.rootVBox, session.rootContentHost)
            session.mountedNavBarPtr = nbPtr
        }

        val bottomNavHandle = (winImpl.nativeWidget.attributes["bottomNav"] as? DesktopBaseNativeImpl<*>)?.nativeWidget as? GtkWidgetHandle
        bottomNavHandle?.ensureLiveWidgetPtr()?.let { bnPtr ->
            if (Gtk4DynamicBindings.gtkWidgetGetParent(bnPtr) != null) {
                Gtk4DynamicBindings.gtkWidgetUnparent(bnPtr)
            }
            Gtk4DynamicBindings.gtkWidgetSetExpand(bnPtr, hexpand = true, vexpand = false)
            Gtk4DynamicBindings.gtkWidgetInsertAfter(bnPtr, session.rootVBox, session.rootContentHost)
            session.mountedBottomNavPtr = bnPtr
        }
    }

    fun syncWindowProperties(windowImpl: GtkWindowNativeImpl) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        val undecorated = windowImpl.isUndecorated()
        val useCustomLinuxTitleBar = shouldUseCustomLinuxTitleBar(windowImpl)
        session.usesCustomLinuxTitleBar = useCustomLinuxTitleBar
        Gtk4DynamicBindings.gtkWindowSetTitle(session.gtkWindow, windowImpl.getTitle().ifBlank { "GenericUI" })
        Gtk4DynamicBindings.gtkWindowSetTitlebar(session.gtkWindow, null)
        Gtk4DynamicBindings.gtkWindowSetDecorated(session.gtkWindow, !undecorated && !useCustomLinuxTitleBar)
        Gtk4DynamicBindings.gtkWindowSetUndecoratedRectClass(session.gtkWindow, undecorated)

        val effMin = resolveEffectiveMinSize(windowImpl)
        if (effMin != null) {
            Gtk4DynamicBindings.gtkWidgetSetSizeRequest(session.rootVBox, effMin.width, effMin.height)
        } else {
            Gtk4DynamicBindings.gtkWidgetSetSizeRequest(session.rootVBox, -1, -1)
        }

        val curW = Gtk4DynamicBindings.gtkWidgetGetWidth(session.gtkWindow).takeIf { it > 0 }
            ?: windowImpl.getWindowSize().width
        session.titleBarWidget?.syncVisibilityAndHeight(curW)
        syncWindowChromeBars(windowImpl)
        syncSessionX11Hints(session)
        requestRedrawOnUiThread()
    }

    fun syncWindowTitle(windowImpl: GtkWindowNativeImpl, title: String) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        Gtk4DynamicBindings.gtkWindowSetTitle(session.gtkWindow, title)
        session.titleBarWidget?.queueDraw()
    }

    fun syncWindowSize(windowImpl: GtkWindowNativeImpl) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        val size = windowImpl.getWindowSize()
        session.lastClampUnclampedInput = null
        session.lastClampTargetSize = size.width to size.height
        Gtk4DynamicBindings.gtkWindowSetDefaultSize(session.gtkWindow, size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
        GtkCustomLayoutManager.requestRelayout(session.contentLayoutBox)
        requestRedrawOnUiThread()
    }

    fun syncWindowPosition(windowImpl: GtkWindowNativeImpl) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        if (windowImpl.isFullscreen() || windowImpl.getWindowState() != WindowState.NORMAL) return
        val xid = Gtk4DynamicBindings.gtkWindowX11Xid(session.gtkWindow)
        if (xid != 0uL && windowImpl.hasExplicitWindowPosition()) {
            val pos = windowImpl.getWindowPosition()
            val targetX = pos.x.toInt()
            val targetY = pos.y.toInt()
            session.lastReportedX = targetX
            session.lastReportedY = targetY
            session.suppressMoveUntilMark = TimeSource.Monotonic.markNow()
            LinuxX11WindowHintsBridge.moveWindow(xid, targetX, targetY)
        }
    }

    fun syncWindowAlwaysOnTop(windowImpl: GtkWindowNativeImpl) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        val xid = Gtk4DynamicBindings.gtkWindowX11Xid(session.gtkWindow)
        if (xid != 0uL) {
            LinuxX11WindowHintsBridge.setAlwaysOnTop(xid, windowImpl.isAlwaysOnTop())
        }
    }

    fun syncWindowAspectRatio(windowImpl: GtkWindowNativeImpl) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        syncSessionX11Hints(session)
    }

    internal fun requestAspectClampResize(
        windowImpl: GtkWindowNativeImpl,
        unclampedSize: Size,
        targetSize: Size
    ) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        val unclampedPair = unclampedSize.width to unclampedSize.height
        val targetPair = targetSize.width to targetSize.height
        if (session.lastClampUnclampedInput == unclampedPair && session.lastClampTargetSize == targetPair) {
            return
        }
        session.lastClampUnclampedInput = unclampedPair
        session.lastClampTargetSize = targetPair
        Gtk4DynamicBindings.gtkWindowSetDefaultSize(
            session.gtkWindow,
            targetSize.width.coerceAtLeast(1),
            targetSize.height.coerceAtLeast(1)
        )
        GtkCustomLayoutManager.requestRelayout(session.contentLayoutBox)
    }

    fun startSystemMove(windowImpl: GtkWindowNativeImpl) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        if (session.closed) return
        Gtk4DynamicBindings.gtkWindowBeginMove(
            win = session.gtkWindow,
            fallbackX = session.lastMouseX.toDouble(),
            fallbackY = session.lastMouseY.toDouble()
        )
    }

    fun showWindowMenu(
        windowImpl: GtkWindowNativeImpl,
        gdkEvent: COpaquePointer? = null,
        screenX: Int = 0,
        screenY: Int = 0
    ): Boolean {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return false
        if (session.closed) return false
        if (gdkEvent != null && Gtk4DynamicBindings.gtkWindowShowWindowMenu(session.gtkWindow, gdkEvent)) {
            return true
        }
        val xid = Gtk4DynamicBindings.gtkWindowX11Xid(session.gtkWindow)
        if (xid != 0uL) {
            return LinuxX11WindowHintsBridge.showWindowMenu(xid, screenX, screenY)
        }
        return false
    }

    fun syncWindowState(windowImpl: GtkWindowNativeImpl) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        val xid = Gtk4DynamicBindings.gtkWindowX11Xid(session.gtkWindow)
        when (windowImpl.getWindowState()) {
            WindowState.MINIMIZED -> Gtk4DynamicBindings.gtkWindowMinimize(session.gtkWindow)
            WindowState.MAXIMIZED -> {
                if (xid != 0uL) LinuxX11WindowHintsBridge.setAspectRatioHint(xid, null)
                Gtk4DynamicBindings.gtkWindowMaximize(session.gtkWindow)
            }
            WindowState.FULLSCREEN -> {
                if (xid != 0uL) LinuxX11WindowHintsBridge.setAspectRatioHint(xid, null)
                session.pendingTargetFullscreen = true
                session.fullscreenTransitionMark = TimeSource.Monotonic.markNow()
                Gtk4DynamicBindings.gtkWindowFullscreen(session.gtkWindow)
            }
            WindowState.NORMAL -> {
                if (Gtk4DynamicBindings.gtkWindowIsFullscreen(session.gtkWindow) || session.pendingTargetFullscreen == true) {
                    session.pendingTargetFullscreen = false
                    session.fullscreenTransitionMark = TimeSource.Monotonic.markNow()
                    Gtk4DynamicBindings.gtkWindowUnfullscreen(session.gtkWindow)
                }
                if (Gtk4DynamicBindings.gtkWindowIsMaximized(session.gtkWindow)) {
                    Gtk4DynamicBindings.gtkWindowUnmaximize(session.gtkWindow)
                }
                syncWindowAspectRatio(windowImpl)
            }
        }
    }

    fun syncFullscreen(windowImpl: GtkWindowNativeImpl, fullscreen: Boolean) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        val xid = Gtk4DynamicBindings.gtkWindowX11Xid(session.gtkWindow)
        val undecorated = windowImpl.isUndecorated()
        val useCustomLinuxTitleBar = !fullscreen && shouldUseCustomLinuxTitleBar(windowImpl)
        session.usesCustomLinuxTitleBar = useCustomLinuxTitleBar
        Gtk4DynamicBindings.gtkWindowSetTitlebar(session.gtkWindow, null)
        Gtk4DynamicBindings.gtkWindowSetDecorated(session.gtkWindow, !undecorated && !useCustomLinuxTitleBar)
        if (fullscreen) {
            if (xid != 0uL) LinuxX11WindowHintsBridge.setAspectRatioHint(xid, null)
            session.pendingTargetFullscreen = true
            session.fullscreenTransitionMark = TimeSource.Monotonic.markNow()
            Gtk4DynamicBindings.gtkWindowFullscreen(session.gtkWindow)
        } else {
            session.pendingTargetFullscreen = false
            session.fullscreenTransitionMark = TimeSource.Monotonic.markNow()
            Gtk4DynamicBindings.gtkWindowUnfullscreen(session.gtkWindow)
            syncWindowAspectRatio(windowImpl)
        }
        val curW = Gtk4DynamicBindings.gtkWidgetGetWidth(session.gtkWindow).takeIf { it > 0 }
            ?: windowImpl.getWindowSize().width
        session.titleBarWidget?.syncVisibilityAndHeight(curW)
        syncWindowChromeBars(windowImpl)
        requestRedrawOnUiThread()
    }

    fun syncWindowVisibility(windowImpl: GtkWindowNativeImpl, visible: Boolean) {
        val session = sessions[windowImpl.nativeWidget.nativePointer]
        if (visible) {
            if (session == null || session.closed) presentWindow(windowImpl)
            else restoreWindowModeAndPresent(session, windowImpl)
        } else if (session != null) {
            if (Gtk4DynamicBindings.gtkWindowIsFullscreen(session.gtkWindow) || session.pendingTargetFullscreen == true) {
                session.pendingTargetFullscreen = false
                session.fullscreenTransitionMark = TimeSource.Monotonic.markNow()
                Gtk4DynamicBindings.gtkWindowUnfullscreen(session.gtkWindow)
            }
            Gtk4DynamicBindings.gtkWidgetSetVisible(session.gtkWindow, false)
        }
    }

    fun bringWindowToFront(windowImpl: GtkWindowNativeImpl) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        Gtk4DynamicBindings.gtkWindowPresent(session.gtkWindow)
    }

    internal fun resolveEffectiveMinSize(windowImpl: GtkWindowNativeImpl): Size? {
        val rawMin = windowImpl.getMinWindowSize() ?: return null
        val undecorated = windowImpl.isUndecorated()
        return Size(
            width = rawMin.width.coerceAtLeast(if (undecorated) 64 else 160),
            height = rawMin.height.coerceAtLeast(if (undecorated) 64 else 120)
        )
    }

    private fun effectiveWindowedAspectRatio(
        session: GtkWindowSession,
        windowImpl: GtkWindowNativeImpl
    ): Double? {
        val isNormal = windowImpl.getWindowState() == WindowState.NORMAL &&
            !windowImpl.isFullscreen() &&
            !Gtk4DynamicBindings.gtkWindowIsFullscreen(session.gtkWindow) &&
            !Gtk4DynamicBindings.gtkWindowIsMaximized(session.gtkWindow)
        return if (isNormal) windowImpl.getAspectRatio() else null
    }

    private fun syncSessionX11Hints(session: GtkWindowSession) {
        val winImpl = session.windowImpl ?: return
        val xid = Gtk4DynamicBindings.gtkWindowX11Xid(session.gtkWindow)
        if (xid == 0uL) return
        val isNormal = winImpl.getWindowState() == WindowState.NORMAL &&
            !winImpl.isFullscreen() &&
            !Gtk4DynamicBindings.gtkWindowIsFullscreen(session.gtkWindow) &&
            !Gtk4DynamicBindings.gtkWindowIsMaximized(session.gtkWindow)
        val effMin = if (isNormal) resolveEffectiveMinSize(winImpl) else null
        LinuxX11WindowHintsBridge.syncWindowHints(
            windowXid = xid,
            alwaysOnTop = winImpl.isAlwaysOnTop(),
            aspectRatio = effectiveWindowedAspectRatio(session, winImpl),
            minWidthPx = effMin?.width,
            minHeightPx = effMin?.height
        )
        if (!winImpl.isFullscreen() && winImpl.getWindowState() == WindowState.NORMAL && winImpl.hasExplicitWindowPosition()) {
            val pos = winImpl.getWindowPosition()
            val targetX = pos.x.toInt()
            val targetY = pos.y.toInt()
            session.lastReportedX = targetX
            session.lastReportedY = targetY
            session.suppressMoveUntilMark = TimeSource.Monotonic.markNow()
            LinuxX11WindowHintsBridge.moveWindow(xid, targetX, targetY)
        }
    }

    private fun hasActiveEventLoopTargets(): Boolean =
        sessions.values.any { !it.closed } ||
            GtkWindowNativeImpl.activeWindows.any { !it.isDisposed && it.isVisible() } ||
            GtkSystemTrayNativeImpl.hasActiveTrayIconsInProcess()

    fun runEventLoop() {
        if (!ensureAppInitialized()) return
        if (eventLoopRunning) return
        eventLoopRunning = true
        stopRequested = false
        GtkSystemTrayNativeImpl.ensureAllRegisteredIfInteractive()
        val ctx = LinuxGlibGioBindings.mainContextDefault()
        var statePollTimerId = 0u

        try {
            if (visualTestMode) {
                pumpEvents(maxIterations = 40, sleepMsPerStep = 10)
                return
            }

            if (ctx != null) {
                val statePollCb = staticCFunction { _: COpaquePointer? ->
                    if (!eventLoopRunning || stopRequested) 0 else {
                        pollWindowStateTransitions()
                        1
                    }
                }
                statePollTimerId = LinuxGlibGioBindings.timeoutAdd(250u, statePollCb, null)
            }
            syncHostVBlankDemand()

            while (eventLoopRunning && !stopRequested && hasActiveEventLoopTargets()) {
                if (ctx != null) {
                    var spins = 0
                    while (spins < 32 && LinuxGlibGioBindings.mainContextIteration(ctx, false)) {
                        spins++
                    }
                    if (!eventLoopRunning || stopRequested || !hasActiveEventLoopTargets()) break
                    LinuxGlibGioBindings.mainContextIteration(ctx, true)
                } else {
                    pollWindowStateTransitions()
                    if (DesktopNativeWidget.activeVBlankWidgetsSnapshot().isNotEmpty()) {
                        dispatchActiveVBlankWidgets(currentVBlankTimestampMs())
                    }
                    val intervalUs = (1_000_000.0 / Animator.getRefreshRateHz().coerceAtLeast(1.0))
                        .toLong()
                        .coerceAtLeast(1_000L)
                        .toUInt()
                    usleep(intervalUs)
                }
            }
        } finally {
            if (statePollTimerId != 0u) {
                LinuxGlibGioBindings.sourceRemove(statePollTimerId)
            }
            eventLoopRunning = false
        }
    }

    private fun currentVBlankTimestampMs(): Double =
        vblankClockOrigin.elapsedNow().inWholeNanoseconds / 1_000_000.0

    internal fun onAnimatorVBlankTick(timestampMs: Double? = null) {
        dispatchActiveVBlankWidgets(timestampMs)
    }

    internal fun syncHostVBlankDemand() {
        val hasActive = DesktopNativeWidget.activeVBlankWidgetsSnapshot().isNotEmpty()
        val preferredRateHz = if (hasActive) DesktopNativeWidget.resolvePreferredVBlankRateHz() else null
        val gtkScheduler = Animator.activeScheduler as? GtkAnimationNativeImpl
        if (gtkScheduler != null) {
            val prevId = hostFallbackWatcherId.value
            if (prevId > 0 && hostFallbackWatcherId.compareAndSet(prevId, -1)) {
                Animator.unwatchVBlank(prevId)
            }
            gtkScheduler.setHostSurfaceDemand(hasActive, preferredRateHz)
        } else if (hasActive) {
            if (hostFallbackWatcherId.value <= 0) {
                val id = Animator.watchVBlank { _, ts, _ ->
                    dispatchActiveVBlankWidgets(ts)
                }
                if (!hostFallbackWatcherId.compareAndSet(-1, id) && id > 0) {
                    Animator.unwatchVBlank(id)
                }
            }
        } else {
            val prevId = hostFallbackWatcherId.value
            if (prevId > 0 && hostFallbackWatcherId.compareAndSet(prevId, -1)) {
                Animator.unwatchVBlank(prevId)
            }
        }
    }

    private fun scheduleSurfaceFrameWakeup() {
        if (!appInitialized || !GtkApplicationNativeImpl.interactiveGtkGuiEnabled) return
        if (!surfaceFrameWakeScheduled.compareAndSet(0, 1)) return
        val deliver = {
            surfaceFrameWakeScheduled.value = 0
            dispatchActiveVBlankWidgets(currentVBlankTimestampMs())
            requestRedrawOnUiThread()
        }
        if (GenericUiThreading.isOnUiThread()) {
            deliver()
        } else {
            GenericUiThreading.runOnUi(deliver)
            LinuxGlibGioBindings.mainContextWakeup(LinuxGlibGioBindings.mainContextDefault())
        }
    }

    private fun dispatchActiveVBlankWidgets(timestampMs: Double? = null) {
        val active = DesktopNativeWidget.activeVBlankWidgetsSnapshot()
        if (active.isEmpty()) return
        for (w in active) {
            if (!w.disposed) {
                w.vblankDispatch?.invoke(timestampMs)
                val gtkHandle = w as? GtkWidgetHandle
                gtkHandle?.realGtkWidgetPtr?.let { ptr -> Gtk4DynamicBindings.gtkWidgetQueueDraw(ptr) }
            }
        }
    }

    fun pumpEvents(maxIterations: Int = 8, sleepMsPerStep: Int = 4): Int {
        if (!ensureAppInitialized()) return 0
        val ctx = LinuxGlibGioBindings.mainContextDefault()
        var steps = 0
        val fallbackStepMs = (1000.0 / Animator.getRefreshRateHz().coerceAtLeast(1.0))
        for (i in 0 until maxIterations) {
            if (ctx != null) {
                var spins = 0
                while (spins < 32 && LinuxGlibGioBindings.mainContextIteration(ctx, false)) {
                    spins++
                }
            }
            GtkAnimationNativeImpl.activeInstance?.pumpIfFallbackNeeded(sleepMsPerStep.toDouble().coerceAtLeast(fallbackStepMs))
            dispatchActiveVBlankWidgets()
            pollWindowStateTransitions()
            steps++
            if (sleepMsPerStep > 0) {
                usleep((sleepMsPerStep * 1000).toUInt())
            }
        }
        return steps
    }

    private fun pollWindowStateTransitions() {
        for (session in sessions.values.toList()) {
            if (session.closed) continue
            val winImpl = session.windowImpl ?: continue
            val key = session.windowKey
            val minNow = Gtk4DynamicBindings.gtkWindowIsMinimized(session.gtkWindow)
            val prevMin = lastObservedMinimizedByKey[key]
            if (prevMin != null && prevMin != minNow) {
                val maxNow = Gtk4DynamicBindings.gtkWindowIsMaximized(session.gtkWindow)
                val fsNow = Gtk4DynamicBindings.gtkWindowIsFullscreen(session.gtkWindow)
                GenericUiThreading.runOnLogic {
                    winImpl.notifyHostMinimizedFromOs(minNow, maxNow, fsNow)
                }
            }
            lastObservedMinimizedByKey[key] = minNow
        }
    }

    fun terminateEventLoop() {
        stopRequested = true
        eventLoopRunning = false
        surfaceFrameWakeScheduled.value = 0
        val prevId = hostFallbackWatcherId.value
        if (prevId > 0 && hostFallbackWatcherId.compareAndSet(prevId, -1)) {
            Animator.unwatchVBlank(prevId)
        }
        (Animator.activeScheduler as? GtkAnimationNativeImpl)?.setHostSurfaceDemand(false, null)
        val cleanupUi = {
            val toClose = sessions.values.toList()
            sessions.clear()
            lastObservedMinimizedByKey.clear()
            for (session in toClose) {
                destroySession(session)
            }
            LinuxGlibGioBindings.mainContextWakeup(LinuxGlibGioBindings.mainContextDefault())
        }
        if (!GtkApplicationNativeImpl.interactiveGtkGuiEnabled || GenericUiThreading.isOnUiThread()) {
            cleanupUi()
        } else {
            GenericUiThreading.runOnUi { cleanupUi() }
            LinuxGlibGioBindings.mainContextWakeup(LinuxGlibGioBindings.mainContextDefault())
        }
    }

    private fun hasRemainingVisibleMainWindow(excludingWindow: GtkWindowNativeImpl? = null): Boolean {
        val visibleInSessions = sessions.values.any { session ->
            val w = session.windowImpl
            !session.closed && w != null && w !== excludingWindow &&
                !w.isDisposed && w.isVisible() && !w.isToolModalWindow
        }
        if (visibleInSessions) return true
        return GtkWindowNativeImpl.activeWindows.any { w ->
            w !== excludingWindow && !w.isDisposed && w.isVisible() && !w.isToolModalWindow
        }
    }

    private fun hasRemainingVisibleDialogSession(excludingDialog: DesktopDialogWindowNativeImpl<*>? = null): Boolean =
        sessions.values.any { session ->
            val dlg = session.dialogImpl
            !session.closed && dlg != null && dlg !== excludingDialog &&
                dlg.nativeWidget.visible && !dlg.nativeWidget.disposed
        }

    private fun hasAnyRemainingVisiblePresentationWindow(
        excludingWindow: GtkWindowNativeImpl? = null,
        excludingDialog: DesktopDialogWindowNativeImpl<*>? = null
    ): Boolean =
        hasRemainingVisibleMainWindow(excludingWindow) || hasRemainingVisibleDialogSession(excludingDialog)

    private fun evaluateRemainingWindowsAfterClose(
        excludingWindow: GtkWindowNativeImpl? = null,
        excludingDialog: DesktopDialogWindowNativeImpl<*>? = null,
        allowExitLoop: Boolean = true
    ) {
        val hasRemaining = hasAnyRemainingVisiblePresentationWindow(
            excludingWindow = excludingWindow,
            excludingDialog = excludingDialog
        )
        if (!hasRemaining && allowExitLoop && eventLoopRunning && !visualTestMode) {
            if (!GtkSystemTrayNativeImpl.hasActiveTrayIconsInProcess()) {
                val app = Application.current
                if (app != null) {
                    GenericUiThreading.runOnLogic {
                        app.exit(0)
                        terminateEventLoop()
                    }
                } else {
                    terminateEventLoop()
                }
            }
        }
    }

    internal fun onTrayDisposed(hasRemainingActiveTrays: Boolean) {
        if (!GtkApplicationNativeImpl.interactiveGtkGuiEnabled) return
        if (!hasRemainingActiveTrays && !hasAnyRemainingVisiblePresentationWindow() && eventLoopRunning && !visualTestMode) {
            val app = Application.current
            if (app != null) {
                GenericUiThreading.runOnLogic {
                    app.exit(0)
                    terminateEventLoop()
                }
            } else {
                terminateEventLoop()
            }
        }
    }

    fun onWindowClosed(windowImpl: GtkWindowNativeImpl) {
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled && !GenericUiThreading.isOnUiThread()) {
            GenericUiThreading.runOnUi { onWindowClosed(windowImpl) }
            return
        }
        val session = sessions.remove(windowImpl.nativeWidget.nativePointer) ?: return
        destroySession(session)
        evaluateRemainingWindowsAfterClose(
            excludingWindow = windowImpl,
            allowExitLoop = !windowImpl.isToolModalWindow
        )
    }

    private fun closeDialogSession(dialogImpl: DesktopDialogWindowNativeImpl<*>) {
        if (GtkApplicationNativeImpl.interactiveGtkGuiEnabled && !GenericUiThreading.isOnUiThread()) {
            GenericUiThreading.runOnUi { closeDialogSession(dialogImpl) }
            return
        }
        val session = sessions.remove(dialogImpl.nativeWidget.nativePointer) ?: return
        destroySession(session)
        evaluateRemainingWindowsAfterClose(excludingDialog = dialogImpl)
    }

    private fun destroySession(session: GtkWindowSession) {
        lastObservedMinimizedByKey.remove(session.windowKey)
        GtkWindowTitleBarController.clearWindowState(session.windowKey)
        if (session.closed) return
        session.closed = true
        session.titleBarWidget?.dispose()
        session.windowImpl?.nativeWidget?.apply {
            onBeforeLayoutAllocate = null
            realGtkWidgetPtr = null
            containerLayoutBoxPtr = null
            backgroundSurfacePtr = null
        }
        (session.dialogImpl?.nativeWidget as? GtkWidgetHandle)?.apply {
            onBeforeLayoutAllocate = null
            realGtkWidgetPtr = null
            containerLayoutBoxPtr = null
            backgroundSurfacePtr = null
        }
        GtkAnimationNativeImpl.activeInstance?.onHostWidgetDestroyed(session.contentLayoutBox)
        GtkAnimationNativeImpl.activeInstance?.onHostWidgetDestroyed(session.rootVBox)
        GtkAnimationNativeImpl.activeInstance?.onHostWidgetDestroyed(session.gtkWindow)
        Gtk4DynamicBindings.gtkWindowDestroy(session.gtkWindow)
        val ref = session.sessionRef
        session.sessionRef = null
        ref?.dispose()
    }

    private fun handleWindowCloseRequest(session: GtkWindowSession) {
        val winImpl = session.windowImpl
        val dlgImpl = session.dialogImpl
        if (winImpl != null) {
            GenericUiThreading.runOnLogic {
                winImpl.closeWindow()
            }
        } else if (dlgImpl != null) {
            GenericUiThreading.runOnLogic {
                dlgImpl.closeDialog()
            }
        } else {
            sessions.remove(session.windowKey)
            destroySession(session)
        }
    }

    /**
     * Captures a screenshot of [winImpl] at [width] x [height] by updating its layout pass and
     * rendering the live GTK4 widget tree via `GtkWidgetPaintable` + `GskRenderer`
     * (`gsk_renderer_render_texture` -> `gdk_texture_download`), falling back cleanly to a
     * headless Cairo composition pass when no display socket (`DISPLAY`/`WAYLAND_DISPLAY`) runs.
     */
    fun captureWindowScreenshot(winImpl: GtkWindowNativeImpl, width: Int, height: Int): ImageBuffer? {
        if (width <= 0 || height <= 0) return null
        if (!Gtk4DynamicBindings.ensureLibrariesLoaded()) return null

        val titleBarActive = GtkWindowTitleBarController.isCustomTitleBarActive(winImpl)
        val titleLayout = if (titleBarActive) {
            GtkWindowTitleBarController.computeAndApplySlotLayout(winImpl, width)
        } else {
            GtkWindowTitleBarController.clearWindowState(winImpl.nativeWidget.nativePointer)
            null
        }
        val titleBarH = titleLayout?.barBounds?.height ?: 0
        val embeddedMenu = winImpl.nativeWidget.attributes["embedMenuBarInTitleBar"] as? Boolean == true
        val menuBarImpl = winImpl.nativeWidget.attributes["menuBar"] as? GtkMenuBarNativeImpl
        val menuBarH = if (menuBarImpl != null && !embeddedMenu && !winImpl.isFullscreen()) {
            menuBarImpl.measureIntrinsicSize(width.toFloat(), null).height.coerceAtLeast(28)
        } else {
            0
        }
        val contentH = (height - titleBarH - menuBarH).coerceAtLeast(1)
        winImpl.layoutChildrenInBounds(width.toFloat(), contentH.toFloat())
        if (titleBarActive) {
            GtkWindowTitleBarController.computeAndApplySlotLayout(winImpl, width)
        }

        val liveSession = sessions[winImpl.nativeWidget.nativePointer]?.takeIf { !it.closed }
        if (liveSession != null && Gtk4DynamicBindings.ensureGtkInitialized()) {
            liveSession.titleBarWidget?.syncVisibilityAndHeight(width)
            Gtk4DynamicBindings.gtkWidgetSizeAllocate(liveSession.rootVBox, 0, 0, width, height, -1)
            pumpEvents(maxIterations = 4, sleepMsPerStep = 2)
            captureLiveWidgetViaPaintable(liveSession.gtkWindow, liveSession.rootVBox, width, height)?.let { rgba ->
                if (hasAnyVisiblePixel(rgba)) {
                    return ImageBuffer(
                        width = width,
                        height = height,
                        pixelFormat = PixelFormat.RGBA_8888,
                        bytes = rgba
                    )
                }
            }
        }

        val headlessRgba = renderHeadlessCairoScreenshot(
            winImpl = winImpl,
            width = width,
            height = height,
            titleBarActive = titleBarActive,
            titleBarH = titleBarH,
            menuBarH = menuBarH
        ) ?: return null
        return ImageBuffer(
            width = width,
            height = height,
            pixelFormat = PixelFormat.RGBA_8888,
            bytes = headlessRgba
        )
    }

    private fun captureLiveWidgetViaPaintable(
        gtkWin: COpaquePointer,
        rootWidgetPtr: COpaquePointer,
        width: Int,
        height: Int
    ): ByteArray? {
        val paintable = Gtk4DynamicBindings.gtkWidgetPaintableNew(rootWidgetPtr) ?: return null
        val snapshot = Gtk4DynamicBindings.gtkSnapshotNew()
        if (snapshot == null) {
            LinuxGlibGioBindings.objectUnref(paintable)
            return null
        }
        var node: COpaquePointer? = null
        var renderer: COpaquePointer? = null
        var texture: COpaquePointer? = null
        try {
            Gtk4DynamicBindings.gdkPaintableSnapshot(paintable, snapshot, width.toDouble(), height.toDouble())
            node = Gtk4DynamicBindings.gtkSnapshotFreeToNode(snapshot) ?: return null

            val surface = Gtk4DynamicBindings.gtkWindowSurface(gtkWin)
            renderer = if (surface != null) {
                Gtk4DynamicBindings.gskRendererNewForSurface(surface)
            } else {
                Gtk4DynamicBindings.gskCairoRendererNew()
            }
            if (renderer != null && Gtk4DynamicBindings.gskRendererRealize(renderer, surface)) {
                texture = Gtk4DynamicBindings.gskRendererRenderTexture(
                    renderer = renderer,
                    rootNode = node,
                    x = 0f,
                    y = 0f,
                    width = width.toFloat(),
                    height = height.toFloat()
                )
                if (texture != null) {
                    val premulBgra = Gtk4DynamicBindings.gdkTextureDownloadBgraPremul(texture, width, height)
                    val rgba = GtkSurfaceRenderHelper.premulBgraBytesToRgbaBytes(premulBgra, width, height)
                    if (hasAnyVisiblePixel(rgba)) {
                        return rgba
                    }
                }
            }

            // Secondary GSK render-node -> Cairo surface draw if texture renderer unavailable
            val cairoSurf = Gtk4DynamicBindings.cairoImageSurfaceCreate(width, height) ?: return null
            val cr = Gtk4DynamicBindings.cairoCreate(cairoSurf)
            try {
                if (cr != null) {
                    Gtk4DynamicBindings.gskRenderNodeDraw(node, cr)
                    Gtk4DynamicBindings.cairoDestroy(cr)
                }
                val words = GtkSurfaceRenderHelper.cairoSurfaceToPremulBgraWords(cairoSurf, width, height)
                return premulBgraWordsToRgbaBytes(words)
            } finally {
                Gtk4DynamicBindings.cairoSurfaceDestroy(cairoSurf)
            }
        } finally {
            texture?.let { LinuxGlibGioBindings.objectUnref(it) }
            renderer?.let {
                Gtk4DynamicBindings.gskRendererUnrealize(it)
                LinuxGlibGioBindings.objectUnref(it)
            }
            node?.let { Gtk4DynamicBindings.gskRenderNodeUnref(it) }
            LinuxGlibGioBindings.objectUnref(paintable)
        }
    }

    private fun renderHeadlessCairoScreenshot(
        winImpl: GtkWindowNativeImpl,
        width: Int,
        height: Int,
        titleBarActive: Boolean,
        titleBarH: Int,
        menuBarH: Int
    ): ByteArray? {
        val surface = Gtk4DynamicBindings.cairoImageSurfaceCreate(width, height) ?: return null
        val cr = Gtk4DynamicBindings.cairoCreate(surface)
        if (cr == null) {
            Gtk4DynamicBindings.cairoSurfaceDestroy(surface)
            return null
        }
        try {
            paintWindowBackdrop(cr, winImpl, width, height)

            if (titleBarActive && titleBarH > 0) {
                GtkWindowTitleBarController.renderTopLevelTitleBarToCairo(cr, winImpl, width, titleBarH)
                for ((slotWidget, _) in GtkWindowTitleBarController.getLatestSlotWidgets(winImpl.nativeWidget.nativePointer)) {
                    val slotHandle = slotWidget as? GtkWidgetHandle ?: continue
                    GtkWidgetLifecycleBridge.renderSubtreeToCairo(
                        handle = slotHandle,
                        cr = cr,
                        includeMountedTitleBar = true
                    )
                }
            }

            val topOffset = titleBarH + menuBarH
            val contentH = (height - topOffset).coerceAtLeast(1)
            Gtk4DynamicBindings.cairoSave(cr)
            Gtk4DynamicBindings.cairoTranslate(cr, 0.0, topOffset.toDouble())
            Gtk4DynamicBindings.cairoRectangle(cr, 0.0, 0.0, width.toDouble(), contentH.toDouble())
            Gtk4DynamicBindings.cairoClip(cr)
            for (child in winImpl.getLogicalChildren()) {
                val childHandle = child.nativeWidget as? GtkWidgetHandle ?: continue
                GtkWidgetLifecycleBridge.renderSubtreeToCairo(childHandle, cr)
            }
            Gtk4DynamicBindings.cairoRestore(cr)
            Gtk4DynamicBindings.cairoDestroy(cr)

            val words = GtkSurfaceRenderHelper.cairoSurfaceToPremulBgraWords(surface, width, height)
            return premulBgraWordsToRgbaBytes(words)
        } finally {
            Gtk4DynamicBindings.cairoSurfaceDestroy(surface)
        }
    }

    private fun paintWindowBackdrop(
        cr: COpaquePointer,
        winImpl: GtkWindowNativeImpl,
        width: Int,
        height: Int
    ) {
        val explicitBg = (winImpl.nativeWidget.attributes["backgroundColor"] as? RgbaColor)
            ?: winImpl.getComputedStyle()?.backgroundColor
        val dark = GtkRetainedContainerSupport.isDarkTheme()
        val effectiveColors = BootstrapColorPalette.activeThemeConfig.resolveEffectiveColors(dark)
        val fallbackBg = explicitBg
            ?: effectiveColors.background
            ?: effectiveColors.surface
            ?: if (dark) RgbaColor(30, 32, 37) else RgbaColor(250, 250, 251)
        val borderColor = if (winImpl.isUndecorated()) {
            if (dark) RgbaColor(72, 76, 86, 0.85f) else RgbaColor(180, 184, 194, 0.85f)
        } else {
            null
        }
        paintSurfaceColors(cr, width, height, fallbackBg, borderColor)
    }

    private fun paintSurfaceColors(
        cr: COpaquePointer,
        width: Int,
        height: Int,
        bg: RgbaColor?,
        border: RgbaColor? = null
    ) {
        val dark = GtkRetainedContainerSupport.isDarkTheme()
        val fill = bg ?: if (dark) RgbaColor(36, 38, 44) else RgbaColor(246, 246, 248)
        Gtk4DynamicBindings.cairoSave(cr)
        Gtk4DynamicBindings.cairoSetSourceRgba(cr, fill.r / 255.0, fill.g / 255.0, fill.b / 255.0, fill.a / 255.0)
        Gtk4DynamicBindings.cairoRectangle(cr, 0.0, 0.0, width.toDouble(), height.toDouble())
        Gtk4DynamicBindings.cairoFill(cr)
        if (border != null && width > 1 && height > 1) {
            Gtk4DynamicBindings.cairoSetSourceRgba(cr, border.r / 255.0, border.g / 255.0, border.b / 255.0, border.a / 255.0)
            Gtk4DynamicBindings.cairoSetLineWidth(cr, 1.0)
            Gtk4DynamicBindings.cairoRectangle(cr, 0.5, 0.5, width - 1.0, height - 1.0)
            Gtk4DynamicBindings.cairoStroke(cr)
        }
        Gtk4DynamicBindings.cairoRestore(cr)
    }

    private fun hasAnyVisiblePixel(rgba: ByteArray): Boolean {
        var idx = 3
        while (idx < rgba.size) {
            if ((rgba[idx].toInt() and 0xFF) != 0) return true
            idx += 4
        }
        return false
    }

    private fun premulBgraWordsToRgbaBytes(words: IntArray): ByteArray {
        val out = ByteArray(words.size * 4)
        for (i in words.indices) {
            val px = words[i]
            val a = (px ushr 24) and 0xFF
            val rPremul = (px ushr 16) and 0xFF
            val gPremul = (px ushr 8) and 0xFF
            val bPremul = px and 0xFF
            val s = i * 4
            if (a == 0) {
                out[s] = 0; out[s + 1] = 0; out[s + 2] = 0; out[s + 3] = 0
            } else if (a == 255) {
                out[s] = rPremul.toByte()
                out[s + 1] = gPremul.toByte()
                out[s + 2] = bPremul.toByte()
                out[s + 3] = 255.toByte()
            } else {
                out[s] = ((rPremul * 255 + (a shr 1)) / a).coerceIn(0, 255).toByte()
                out[s + 1] = ((gPremul * 255 + (a shr 1)) / a).coerceIn(0, 255).toByte()
                out[s + 2] = ((bPremul * 255 + (a shr 1)) / a).coerceIn(0, 255).toByte()
                out[s + 3] = a.toByte()
            }
        }
        return out
    }

    private fun translateKeyEvent(keyval: UInt, keycode: UInt, state: UInt): KeyEvent {
        val rawName = Gtk4DynamicBindings.gdkKeyvalName(keyval) ?: ""
        val keyName = when (rawName) {
            "Return", "KP_Enter" -> "Enter"
            "BackSpace" -> "Backspace"
            "Left" -> "ArrowLeft"
            "Right" -> "ArrowRight"
            "Up" -> "ArrowUp"
            "Down" -> "ArrowDown"
            "Escape" -> "Escape"
            "Tab", "ISO_Left_Tab" -> "Tab"
            "space" -> " "
            else -> {
                val uni = Gtk4DynamicBindings.gdkKeyvalToUnicode(keyval).toInt()
                if (uni in 0x20..0x7E) uni.toChar().toString() else rawName
            }
        }
        return KeyEvent(
            key = keyName,
            code = rawName,
            keyCode = keycode.toInt(),
            shiftKey = (state and Gtk4DynamicBindings.GDK_SHIFT_MASK) != 0u,
            ctrlKey = (state and Gtk4DynamicBindings.GDK_CONTROL_MASK) != 0u,
            altKey = (state and Gtk4DynamicBindings.GDK_ALT_MASK) != 0u,
            metaKey = (state and Gtk4DynamicBindings.GDK_SUPER_MASK) != 0u,
            isTargetTextInput = false
        )
    }
}
