@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.event.KeyEvent
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.render.GtkCairoRenderContext
import com.nexustools.genericui.gtk.render.GtkControlRenderUtil
import com.nexustools.genericui.gtk.render.GtkGraphicsDrawUtil
import com.nexustools.genericui.gtk.render.GtkHitRegion
import com.nexustools.genericui.gtk.render.GtkThemePalette
import com.nexustools.genericui.gtk.render.GtkWorkspaceLayoutRenderUtil
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.linux.LinuxX11WindowHintsBridge
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import platform.posix.usleep
import kotlin.time.TimeSource

object GtkNativeWindowHost {
    private val sessions = LinkedHashMap<Long, GtkWindowSession>(8)
    private var appInitialized = false
    private var eventLoopRunning = false
    private var stopRequested = false

    var visualTestMode: Boolean = false

    fun sessionCount(): Int = sessions.size

    fun activeGtkWindowPtr(): COpaquePointer? = sessions.values.firstOrNull { !it.closed }?.gtkWindow

    internal fun sessionForWindow(windowImpl: GtkWindowNativeImpl): GtkWindowSession? =
        sessions[windowImpl.nativeWidget.nativePointer]

    fun prepareEventLoop() {
        ensureAppInitialized()
    }

    internal fun ensureAppInitialized(): Boolean {
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) {
            return false
        }
        installGlobalHooks()
        if (appInitialized) return true
        appInitialized = true
        return true
    }

    private fun installGlobalHooks() {
        DesktopNativeWidget.onNativeTreeInvalidated = {
            requestRedraw()
        }
        DesktopNativeWidget.overlayInvalidationListener = { _ ->
            requestRedraw()
        }
        DesktopNativeWidget.onCursorUpdated = {
            for (s in sessions.values) {
                updateSessionCursor(s, s.hoveredRegion)
            }
        }
        GtkGraphicsDrawUtil.onAsyncAssetLoaded = {
            requestRedraw()
        }
        GtkControlRenderUtil.onRequestTextInputFocus = { inputImpl ->
            val widget = inputImpl.nativeWidget as? DesktopNativeWidget
            if (widget != null) {
                for (session in sessions.values) {
                    session.focusedWidget = widget
                    Gtk4DynamicBindings.gtkWidgetGrabFocus(session.drawingArea)
                    Gtk4DynamicBindings.gtkWidgetQueueDraw(session.drawingArea)
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
        val active = sessions.values.filter { !it.closed }
        if (active.isEmpty()) return
        GenericUiThreading.runOnUi {
            for (session in sessions.values) {
                if (!session.closed) {
                    Gtk4DynamicBindings.gtkWidgetQueueDraw(session.drawingArea)
                }
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

    fun presentWindow(windowImpl: GtkWindowNativeImpl) {
        if (!ensureAppInitialized()) return
        val key = windowImpl.nativeWidget.nativePointer
        val existing = sessions[key]
        if (existing != null && !existing.closed) {
            restoreWindowModeAndPresent(existing, windowImpl)
            return
        }

        val winSize = windowImpl.getWindowSize()
        val initW = winSize.width.coerceAtLeast(320)
        val initH = winSize.height.coerceAtLeast(240)

        val gtkWin = Gtk4DynamicBindings.gtkWindowNew() ?: return
        val drawingArea = Gtk4DynamicBindings.gtkDrawingAreaNew() ?: run {
            Gtk4DynamicBindings.gtkWindowDestroy(gtkWin)
            return
        }

        val headerBar = if (windowImpl.getTitleBarInstructions()?.customTitleBar != false) {
            Gtk4DynamicBindings.gtkHeaderBarNew()?.also { hb ->
                Gtk4DynamicBindings.gtkWindowSetTitlebar(gtkWin, hb)
            }
        } else {
            null
        }

        Gtk4DynamicBindings.gtkWindowSetDefaultSize(gtkWin, initW, initH)
        Gtk4DynamicBindings.gtkWindowSetTitle(gtkWin, windowImpl.getTitle().ifBlank { "GenericUI" })
        Gtk4DynamicBindings.gtkWidgetSetFocusable(drawingArea, true)
        Gtk4DynamicBindings.gtkWindowSetChild(gtkWin, drawingArea)

        val session = GtkWindowSession(
            windowKey = key,
            gtkWindow = gtkWin,
            drawingArea = drawingArea,
            headerBar = headerBar,
            windowImpl = windowImpl
        )
        sessions[key] = session
        attachSessionControllers(session)
        syncWindowProperties(windowImpl)

        val ownerImpl = windowImpl.ownerWindow as? GtkWindowNativeImpl
        if (ownerImpl != null) {
            sessions[ownerImpl.nativeWidget.nativePointer]?.let { ownerSession ->
                Gtk4DynamicBindings.gtkWindowSetTransientFor(gtkWin, ownerSession.gtkWindow)
            }
        }

        applyRequestedModeForShow(session, windowImpl)
        Gtk4DynamicBindings.gtkWindowPresent(gtkWin)
        syncSessionX11Hints(session)
        Gtk4DynamicBindings.gtkWidgetGrabFocus(drawingArea)
        Gtk4DynamicBindings.gtkWidgetQueueDraw(drawingArea)
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
        if (!windowImpl.isFullscreen() && windowImpl.getWindowState() == WindowState.NORMAL) {
            val size = windowImpl.getWindowSize()
            Gtk4DynamicBindings.gtkWindowSetDefaultSize(session.gtkWindow, size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
        }
        applyRequestedModeForShow(session, windowImpl)
        Gtk4DynamicBindings.gtkWidgetSetVisible(session.gtkWindow, true)
        Gtk4DynamicBindings.gtkWindowPresent(session.gtkWindow)
        syncSessionX11Hints(session)
        Gtk4DynamicBindings.gtkWidgetQueueDraw(session.drawingArea)
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
            Gtk4DynamicBindings.gtkWidgetQueueDraw(existing.drawingArea)
            return
        }

        val ownerSession = (ownerWindow as? GtkWindowNativeImpl)?.let { sessions[it.nativeWidget.nativePointer] }
            ?: sessions.values.firstOrNull { !it.closed && it.windowImpl != null }
        val maxW = ownerSession?.lastPaintedWidth?.takeIf { it > 100 } ?: 860
        val maxH = ownerSession?.lastPaintedHeight?.takeIf { it > 100 } ?: 680
        val intrinsic = GtkWorkspaceLayoutRenderUtil.measureDialogIntrinsicSize(dialogImpl.nativeWidget, maxW, maxH)

        val gtkWin = Gtk4DynamicBindings.gtkWindowNew() ?: return
        val drawingArea = Gtk4DynamicBindings.gtkDrawingAreaNew() ?: run {
            Gtk4DynamicBindings.gtkWindowDestroy(gtkWin)
            return
        }

        Gtk4DynamicBindings.gtkWindowSetDefaultSize(gtkWin, intrinsic.width, intrinsic.height)
        Gtk4DynamicBindings.gtkWindowSetTitle(gtkWin, dialogImpl.getTitle().ifBlank { "Dialog" })
        Gtk4DynamicBindings.gtkWindowSetModal(gtkWin, true)
        ownerSession?.gtkWindow?.let { Gtk4DynamicBindings.gtkWindowSetTransientFor(gtkWin, it) }
        Gtk4DynamicBindings.gtkWidgetSetFocusable(drawingArea, true)
        Gtk4DynamicBindings.gtkWindowSetChild(gtkWin, drawingArea)

        val session = GtkWindowSession(
            windowKey = key,
            gtkWindow = gtkWin,
            drawingArea = drawingArea,
            dialogImpl = dialogImpl,
            ownerWindowKey = ownerSession?.windowKey
        )
        sessions[key] = session
        attachSessionControllers(session)
        Gtk4DynamicBindings.gtkWindowPresent(gtkWin)
        Gtk4DynamicBindings.gtkWidgetQueueDraw(drawingArea)
    }

    private fun attachSessionControllers(session: GtkWindowSession) {
        val ref = StableRef.create(session)
        session.sessionRef = ref
        val refPtr = ref.asCPointer()

        val drawCb = staticCFunction { _: COpaquePointer?, cr: COpaquePointer?, width: Int, height: Int, userData: COpaquePointer? ->
            if (userData != null && cr != null && width > 0 && height > 0) {
                val s = userData.asStableRef<GtkWindowSession>().get()
                renderSessionCairoFrame(s, cr, width, height)
            }
        }
        Gtk4DynamicBindings.gtkDrawingAreaSetDrawFunc(session.drawingArea, drawCb, refPtr, null)

        val clickGesture = Gtk4DynamicBindings.gtkGestureClickNew()
        if (clickGesture != null) {
            Gtk4DynamicBindings.gtkGestureSingleSetButton(clickGesture, 0u)
            val pressCb = staticCFunction { _: COpaquePointer?, nPress: Int, x: Double, y: Double, userData: COpaquePointer? ->
                if (userData != null) {
                    val s = userData.asStableRef<GtkWindowSession>().get()
                    handlePointerPress(s, x.toFloat(), y.toFloat(), nPress, button = 0)
                }
            }
            val releaseCb = staticCFunction { _: COpaquePointer?, _: Int, x: Double, y: Double, userData: COpaquePointer? ->
                if (userData != null) {
                    val s = userData.asStableRef<GtkWindowSession>().get()
                    handlePointerRelease(s, x.toFloat(), y.toFloat())
                }
            }
            LinuxGlibGioBindings.signalConnect(clickGesture, "pressed", pressCb, refPtr)
            LinuxGlibGioBindings.signalConnect(clickGesture, "released", releaseCb, refPtr)
            Gtk4DynamicBindings.gtkWidgetAddController(session.drawingArea, clickGesture)
        }

        val motionCtrl = Gtk4DynamicBindings.gtkEventControllerMotionNew()
        if (motionCtrl != null) {
            val moveCb = staticCFunction { _: COpaquePointer?, x: Double, y: Double, userData: COpaquePointer? ->
                if (userData != null) {
                    val s = userData.asStableRef<GtkWindowSession>().get()
                    handlePointerMotion(s, x.toFloat(), y.toFloat())
                }
            }
            val leaveCb = staticCFunction { _: COpaquePointer?, userData: COpaquePointer? ->
                if (userData != null) {
                    val s = userData.asStableRef<GtkWindowSession>().get()
                    handlePointerLeave(s)
                }
            }
            LinuxGlibGioBindings.signalConnect(motionCtrl, "motion", moveCb, refPtr)
            LinuxGlibGioBindings.signalConnect(motionCtrl, "leave", leaveCb, refPtr)
            Gtk4DynamicBindings.gtkWidgetAddController(session.drawingArea, motionCtrl)
        }

        val scrollCtrl = Gtk4DynamicBindings.gtkEventControllerScrollNew(Gtk4DynamicBindings.GTK_EVENT_CONTROLLER_SCROLL_BOTH_AXES.toUInt())
        if (scrollCtrl != null) {
            val scrollCb = staticCFunction { _: COpaquePointer?, dx: Double, dy: Double, userData: COpaquePointer? ->
                if (userData != null) {
                    val s = userData.asStableRef<GtkWindowSession>().get()
                    if (handleScroll(s, dx.toFloat() * 36f, dy.toFloat() * 36f)) 1 else 0
                } else 0
            }
            LinuxGlibGioBindings.signalConnect(scrollCtrl, "scroll", scrollCb, refPtr)
            Gtk4DynamicBindings.gtkWidgetAddController(session.drawingArea, scrollCtrl)
        }

        val keyCtrl = Gtk4DynamicBindings.gtkEventControllerKeyNew()
        if (keyCtrl != null) {
            val keyPressCb = staticCFunction { _: COpaquePointer?, keyval: UInt, keycode: UInt, state: UInt, userData: COpaquePointer? ->
                if (userData != null) {
                    val s = userData.asStableRef<GtkWindowSession>().get()
                    if (handleKeyPress(s, keyval, keycode, state)) 1 else 0
                } else 0
            }
            val keyReleaseCb = staticCFunction { _: COpaquePointer?, keyval: UInt, keycode: UInt, state: UInt, userData: COpaquePointer? ->
                if (userData != null) {
                    val s = userData.asStableRef<GtkWindowSession>().get()
                    handleKeyRelease(s, keyval, keycode, state)
                }
            }
            LinuxGlibGioBindings.signalConnect(keyCtrl, "key-pressed", keyPressCb, refPtr)
            LinuxGlibGioBindings.signalConnect(keyCtrl, "key-released", keyReleaseCb, refPtr)
            Gtk4DynamicBindings.gtkWidgetAddController(session.drawingArea, keyCtrl)
        }

        val closeCb = staticCFunction { _: COpaquePointer?, userData: COpaquePointer? ->
            if (userData != null) {
                val s = userData.asStableRef<GtkWindowSession>().get()
                handleWindowCloseRequest(s)
            }
            1
        }
        LinuxGlibGioBindings.signalConnect(session.gtkWindow, "close-request", closeCb, refPtr)

        val mapCb = staticCFunction { _: COpaquePointer?, userData: COpaquePointer? ->
            if (userData != null) {
                val s = userData.asStableRef<GtkWindowSession>().get()
                syncSessionX11Hints(s)
            }
        }
        LinuxGlibGioBindings.signalConnect(session.gtkWindow, "map", mapCb, refPtr)
    }

    @Suppress("UNCHECKED_CAST")
    private fun renderSessionCairoFrame(session: GtkWindowSession, cr: COpaquePointer, width: Int, height: Int) {
        if (session.closed) return
        val darkMode = GtkControlRenderUtil.isDarkThemeActive()
        val themeConfig = BootstrapColorPalette.activeThemeConfig
        val palette = GtkThemePalette.resolve(darkMode, themeConfig)
        val viewport = Rect(0, 0, width, height)

        val ctx = GtkCairoRenderContext(
            cairoContext = cr,
            windowKey = session.windowKey,
            viewport = viewport,
            scaleFactor = 1.0,
            darkMode = darkMode,
            themeConfig = themeConfig,
            palette = palette,
            focusedWidget = session.focusedWidget,
            hoveredWidget = session.hoveredWidget,
            pressedWidget = session.pressedWidget
        )

        val winImpl = session.windowImpl
        val dlgImpl = session.dialogImpl
        if (winImpl != null) {
            val rawFs = Gtk4DynamicBindings.gtkWindowIsFullscreen(session.gtkWindow)
            val pendingFs = session.pendingTargetFullscreen
            val isFs = if (pendingFs != null) {
                val elapsedMs = session.fullscreenTransitionMark?.elapsedNow()?.inWholeMilliseconds ?: 0L
                if (rawFs == pendingFs || elapsedMs >= 1200L) {
                    session.pendingTargetFullscreen = null
                    rawFs
                } else {
                    pendingFs
                }
            } else {
                rawFs
            }
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
            GtkWorkspaceLayoutRenderUtil.renderMainWindow(
                ctx = ctx,
                windowImpl = winImpl as DesktopWindowNativeImpl<DesktopNativeWidget>
            )
        } else if (dlgImpl != null) {
            GtkWorkspaceLayoutRenderUtil.renderDialogWindow(ctx, dlgImpl)
        }

        session.hitRegions = ctx.hitRegions
        session.scrollRegions = ctx.scrollRegions
        session.paintedFrameCount++
        session.lastPaintedWidth = width
        session.lastPaintedHeight = height
    }

    fun runEventLoop() {
        if (!ensureAppInitialized()) return
        if (eventLoopRunning) return
        eventLoopRunning = true
        stopRequested = false
        val ctx = LinuxGlibGioBindings.mainContextDefault()

        try {
            if (visualTestMode) {
                pumpEvents(maxIterations = 40, sleepMsPerStep = 10)
                return
            }

            while (
                eventLoopRunning &&
                !stopRequested &&
                (sessions.values.any { !it.closed } || GtkWindowNativeImpl.activeWindows.any { !it.isDisposed })
            ) {
                var didWork = false
                if (ctx != null) {
                    var spins = 0
                    while (spins < 32 && LinuxGlibGioBindings.mainContextIteration(ctx, false)) {
                        didWork = true
                        spins++
                    }
                }
                if (!didWork) {
                    usleep(8000u)
                }
            }
        } finally {
            eventLoopRunning = false
        }
    }

    fun pumpEvents(maxIterations: Int = 30, sleepMsPerStep: Int = 10): Int {
        if (!ensureAppInitialized()) return 0
        val ctx = LinuxGlibGioBindings.mainContextDefault()
        var steps = 0
        for (i in 0 until maxIterations) {
            var dispatched = false
            if (ctx != null) {
                var spins = 0
                while (spins < 32 && LinuxGlibGioBindings.mainContextIteration(ctx, false)) {
                    dispatched = true
                    spins++
                }
            }
            steps++
            if (sleepMsPerStep > 0) {
                usleep((sleepMsPerStep * 1000).toUInt())
            }
        }
        return steps
    }

    fun terminateEventLoop() {
        stopRequested = true
        eventLoopRunning = false
        val toClose = sessions.values.toList()
        sessions.clear()
        for (session in toClose) {
            destroySession(session)
        }
        LinuxGlibGioBindings.mainContextWakeup(LinuxGlibGioBindings.mainContextDefault())
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
        LinuxX11WindowHintsBridge.syncWindowHints(
            windowXid = xid,
            alwaysOnTop = winImpl.isAlwaysOnTop(),
            aspectRatio = effectiveWindowedAspectRatio(session, winImpl)
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
        val xid = Gtk4DynamicBindings.gtkWindowX11Xid(session.gtkWindow)
        if (xid != 0uL) {
            LinuxX11WindowHintsBridge.setAspectRatioHint(
                windowXid = xid,
                aspectRatio = effectiveWindowedAspectRatio(session, windowImpl)
            )
        }
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
        Gtk4DynamicBindings.gtkWidgetQueueDraw(session.drawingArea)
    }

    fun syncWindowProperties(windowImpl: GtkWindowNativeImpl) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        Gtk4DynamicBindings.gtkWindowSetTitle(session.gtkWindow, windowImpl.getTitle().ifBlank { "GenericUI" })
        session.headerBar?.let { hb ->
            val layout = (windowImpl.nativeWidget.attributes["gtkButtonsLayout"] as? String) ?: ":minimize,maximize,close"
            Gtk4DynamicBindings.gtkHeaderBarSetDecorationLayout(hb, layout)
            Gtk4DynamicBindings.gtkHeaderBarSetShowTitleButtons(hb, true)
        }
        val minSize = windowImpl.getMinWindowSize()
        if (minSize != null) {
            Gtk4DynamicBindings.gtkWidgetSetSizeRequest(session.drawingArea, minSize.width.coerceAtLeast(160), minSize.height.coerceAtLeast(120))
        }
        syncSessionX11Hints(session)
    }

    fun syncWindowTitle(windowImpl: GtkWindowNativeImpl, title: String) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        Gtk4DynamicBindings.gtkWindowSetTitle(session.gtkWindow, title)
    }

    fun syncWindowSize(windowImpl: GtkWindowNativeImpl) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        val size = windowImpl.getWindowSize()
        session.lastClampUnclampedInput = null
        session.lastClampTargetSize = size.width to size.height
        Gtk4DynamicBindings.gtkWindowSetDefaultSize(session.gtkWindow, size.width.coerceAtLeast(1), size.height.coerceAtLeast(1))
        Gtk4DynamicBindings.gtkWidgetQueueDraw(session.drawingArea)
    }

    fun syncWindowState(windowImpl: GtkWindowNativeImpl) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        val xid = Gtk4DynamicBindings.gtkWindowX11Xid(session.gtkWindow)
        when (windowImpl.getWindowState()) {
            WindowState.MINIMIZED -> Gtk4DynamicBindings.gtkWindowMinimize(session.gtkWindow)
            WindowState.MAXIMIZED -> {
                if (xid != 0uL) {
                    LinuxX11WindowHintsBridge.setAspectRatioHint(xid, null)
                }
                Gtk4DynamicBindings.gtkWindowMaximize(session.gtkWindow)
            }
            WindowState.FULLSCREEN -> {
                if (xid != 0uL) {
                    LinuxX11WindowHintsBridge.setAspectRatioHint(xid, null)
                }
                session.pendingTargetFullscreen = true
                session.fullscreenTransitionMark = TimeSource.Monotonic.markNow()
                Gtk4DynamicBindings.gtkWindowFullscreen(session.gtkWindow)
            }
            WindowState.NORMAL, WindowState.PICTURE_IN_PICTURE -> {
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
        if (fullscreen) {
            if (xid != 0uL) {
                LinuxX11WindowHintsBridge.setAspectRatioHint(xid, null)
            }
            session.pendingTargetFullscreen = true
            session.fullscreenTransitionMark = TimeSource.Monotonic.markNow()
            Gtk4DynamicBindings.gtkWindowFullscreen(session.gtkWindow)
        } else {
            session.pendingTargetFullscreen = false
            session.fullscreenTransitionMark = TimeSource.Monotonic.markNow()
            Gtk4DynamicBindings.gtkWindowUnfullscreen(session.gtkWindow)
            syncWindowAspectRatio(windowImpl)
        }
    }

    fun syncWindowVisibility(windowImpl: GtkWindowNativeImpl, visible: Boolean) {
        val session = sessions[windowImpl.nativeWidget.nativePointer]
        if (visible) {
            if (session == null || session.closed) presentWindow(windowImpl)
            else {
                restoreWindowModeAndPresent(session, windowImpl)
            }
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

    fun onWindowClosed(windowImpl: GtkWindowNativeImpl) {
        val session = sessions.remove(windowImpl.nativeWidget.nativePointer) ?: return
        destroySession(session)
    }

    private fun closeDialogSession(dialogImpl: DesktopDialogWindowNativeImpl<*>) {
        val session = sessions.remove(dialogImpl.nativeWidget.nativePointer) ?: return
        destroySession(session)
    }

    private fun destroySession(session: GtkWindowSession) {
        if (session.closed) return
        session.closed = true
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

    private fun handlePointerPress(session: GtkWindowSession, x: Float, y: Float, clickCount: Int, button: Int) {
        session.lastMouseX = x
        session.lastMouseY = y
        val region = session.hitRegions.lastOrNull { it.contains(x.toInt(), y.toInt()) }
        session.pressedRegion = region
        session.pressedWidget = region?.widget
        if (region != null) {
            if (region.isTextInput) {
                session.focusedWidget = region.widget
                Gtk4DynamicBindings.gtkWidgetGrabFocus(session.drawingArea)
            } else if (session.focusedWidget !== region.widget) {
                session.focusedWidget = null
            }
            val lx = x - region.left
            val ly = y - region.top
            region.onPress?.invoke(lx, ly, x, y, button, clickCount)
            val impl = region.widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
            impl?.dispatchPointerDown(PointerEvent(x = lx, y = ly, screenX = x, screenY = y, button = button, clickCount = clickCount))
        } else {
            session.focusedWidget = null
        }
        Gtk4DynamicBindings.gtkWidgetQueueDraw(session.drawingArea)
    }

    private fun handlePointerRelease(session: GtkWindowSession, x: Float, y: Float) {
        val pressed = session.pressedRegion
        session.pressedRegion = null
        session.pressedWidget = null
        if (pressed != null) {
            val inside = pressed.contains(x.toInt(), y.toInt())
            val lx = x - pressed.left
            val ly = y - pressed.top
            pressed.onRelease?.invoke(lx, ly, x, y, inside)
            val impl = pressed.widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
            impl?.dispatchPointerUp(PointerEvent(x = lx, y = ly, screenX = x, screenY = y, button = 0))
            if (inside) {
                pressed.onClick?.invoke()
            }
        }
        Gtk4DynamicBindings.gtkWidgetQueueDraw(session.drawingArea)
    }

    private fun handlePointerMotion(session: GtkWindowSession, x: Float, y: Float) {
        session.lastMouseX = x
        session.lastMouseY = y
        val pressed = session.pressedRegion
        if (pressed != null) {
            val lx = x - pressed.left
            val ly = y - pressed.top
            pressed.onDrag?.invoke(lx, ly, x, y)
            val impl = pressed.widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
            impl?.dispatchPointerMove(PointerEvent(x = lx, y = ly, screenX = x, screenY = y))
            Gtk4DynamicBindings.gtkWidgetQueueDraw(session.drawingArea)
            return
        }

        val region = session.hitRegions.lastOrNull { it.contains(x.toInt(), y.toInt()) }
        val prevWidget = session.hoveredWidget
        if (prevWidget !== region?.widget) {
            (prevWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.dispatchPointerLeave()
            session.hoveredRegion = region
            session.hoveredWidget = region?.widget
            (region?.widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.dispatchPointerEnter()
            updateSessionCursor(session, region)
            Gtk4DynamicBindings.gtkWidgetQueueDraw(session.drawingArea)
        }
    }

    private fun handlePointerLeave(session: GtkWindowSession) {
        val prev = session.hoveredWidget
        session.hoveredRegion = null
        session.hoveredWidget = null
        (prev?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.dispatchPointerLeave()
        updateSessionCursor(session, null)
        Gtk4DynamicBindings.gtkWidgetQueueDraw(session.drawingArea)
    }

    private fun handleScroll(session: GtkWindowSession, deltaX: Float, deltaY: Float): Boolean {
        val px = session.lastMouseX.toInt()
        val py = session.lastMouseY.toInt()
        val target = session.scrollRegions.lastOrNull { it.contains(px, py) } ?: session.scrollRegions.lastOrNull() ?: return false
        val changed = target.onScrollBy(deltaY)
        if (changed) {
            Gtk4DynamicBindings.gtkWidgetQueueDraw(session.drawingArea)
        }
        return changed
    }

    private fun handleKeyPress(session: GtkWindowSession, keyval: UInt, keycode: UInt, state: UInt): Boolean {
        val keyEvent = translateKeyEvent(keyval, keycode, state, isTargetTextInput = session.focusedWidget != null)
        val focusedImpl = session.focusedWidget?.attributes?.get("nativeImpl") as? DesktopTextInputNativeImpl<*>
        if (focusedImpl != null && focusedImpl.isEnabled() && !focusedImpl.isReadOnly() && !keyEvent.ctrlKey && !keyEvent.metaKey) {
            when (keyEvent.key) {
                "Backspace" -> {
                    val cur = focusedImpl.getValue()
                    if (cur.isNotEmpty()) {
                        GenericUiThreading.runOnLogic { focusedImpl.setValue(cur.dropLast(1)) }
                        Gtk4DynamicBindings.gtkWidgetQueueDraw(session.drawingArea)
                    }
                    return true
                }
                "Enter" -> {
                    GenericUiThreading.runOnLogic {
                        if (focusedImpl.isMultiline()) focusedImpl.setValue(focusedImpl.getValue() + "\n")
                        else focusedImpl.submitValue()
                    }
                    Gtk4DynamicBindings.gtkWidgetQueueDraw(session.drawingArea)
                    return true
                }
                else -> {
                    val chCode = Gtk4DynamicBindings.gdkKeyvalToUnicode(keyval).toInt()
                    if (chCode >= 0x20 && chCode != 0x7F) {
                        val ch = chCode.toChar()
                        GenericUiThreading.runOnLogic { focusedImpl.setValue(focusedImpl.getValue() + ch) }
                        Gtk4DynamicBindings.gtkWidgetQueueDraw(session.drawingArea)
                        return true
                    }
                }
            }
        }
        GenericUiThreading.runOnLogic {
            session.windowImpl?.dispatchKeyDown(keyEvent)
            (session.dialogImpl as? DesktopBaseNativeImpl<*>)?.dispatchKeyDown(keyEvent)
        }
        return false
    }

    private fun handleKeyRelease(session: GtkWindowSession, keyval: UInt, keycode: UInt, state: UInt) {
        val keyEvent = translateKeyEvent(keyval, keycode, state, isTargetTextInput = session.focusedWidget != null)
        GenericUiThreading.runOnLogic {
            session.windowImpl?.dispatchKeyUp(keyEvent)
            (session.dialogImpl as? DesktopBaseNativeImpl<*>)?.dispatchKeyUp(keyEvent)
        }
    }

    private fun translateKeyEvent(keyval: UInt, keycode: UInt, state: UInt, isTargetTextInput: Boolean): KeyEvent {
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
            isTargetTextInput = isTargetTextInput
        )
    }

    private fun updateSessionCursor(session: GtkWindowSession, region: GtkHitRegion?) {
        val cssName = region?.cursorCss ?: if (region?.isTextInput == true) "text" else "default"
        if (session.lastAppliedCursorKey == cssName) return
        session.lastAppliedCursorKey = cssName
        Gtk4DynamicBindings.gtkWidgetSetCursorFromName(session.drawingArea, cssName)
    }
}
