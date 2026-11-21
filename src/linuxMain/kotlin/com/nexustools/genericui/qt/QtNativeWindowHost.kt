@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.linux.LinuxX11WindowHintsBridge
import com.nexustools.genericui.qt.render.QtControlRenderUtil
import com.nexustools.genericui.qt.render.QtGraphicsDrawUtil
import com.nexustools.genericui.qt.render.QtHitRegion
import com.nexustools.genericui.qt.render.QtRenderContext
import com.nexustools.genericui.qt.render.QtThemePalette
import com.nexustools.genericui.qt.render.QtWorkspaceLayoutRenderUtil
import com.nexustools.genericui.style.color.BootstrapColorPalette
import kotlinx.cinterop.COpaquePointer
import platform.posix.usleep
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * Native Qt 6 (`QApplication` + `QWidget` + `QImage(Format_ARGB32)` + `QPainter` + `QPixmap` + `QLabel`)
 * window host operating strictly via Qt 6's non-vtable, non-`sret` C++ ABI.
 */
object QtNativeWindowHost {
    private val sessions = LinkedHashMap<Long, QtWindowSession>(8)
    private val lastAppliedUndecoratedByKey = HashMap<Long, Boolean>()
    private val lastObservedMinimizedByKey = HashMap<Long, Boolean>()
    private var appInitialized = false
    private var eventLoopRunning = false
    private var stopRequested = false

    var visualTestMode: Boolean = false
    internal var onLoopTickHook: (() -> Unit)? = null

    fun sessionCount(): Int = sessions.size

    fun activeQWidgetPtr(): COpaquePointer? = sessions.values.firstOrNull { !it.closed }?.qWidget

    internal fun sessionForWindow(windowImpl: QtWindowNativeImpl): QtWindowSession? =
        sessions[windowImpl.nativeWidget.nativePointer]

    fun prepareEventLoop() {
        ensureAppInitialized()
    }

    internal fun ensureAppInitialized(): Boolean {
        if (!Qt6DynamicBindings.ensureApplication()) {
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
        QtGraphicsDrawUtil.onAsyncAssetLoaded = {
            requestRedraw()
        }
        QtControlRenderUtil.onRequestTextInputFocus = { inputImpl ->
            val widget = inputImpl.nativeWidget as? DesktopNativeWidget
            if (widget != null) {
                for (session in sessions.values) {
                    session.focusedWidget = widget
                    session.needsRepaint = true
                }
                requestRedraw()
            }
        }

        DesktopDialogWindowNativeImpl.onShowDialogHook = { dialogImpl, ownerWindow ->
            if (QtApplicationNativeImpl.interactiveQtGuiEnabled) {
                presentDialog(dialogImpl, ownerWindow)
            }
        }
        DesktopDialogWindowNativeImpl.onCloseDialogHook = { dialogImpl ->
            if (QtApplicationNativeImpl.interactiveQtGuiEnabled) {
                closeDialogSession(dialogImpl)
            }
        }
        DesktopDialogWindowNativeImpl.onTitleChangedHook = { dialogImpl, title ->
            sessions[dialogImpl.nativeWidget.nativePointer]?.let { s ->
                Qt6DynamicBindings.setWindowTitle(s.qWidget, title)
            }
        }
    }

    fun requestRedraw() {
        for (s in sessions.values) {
            if (!s.closed) {
                s.needsRepaint = true
            }
        }
    }

    fun syncThemeAppearance(mode: ThemeMode) {
        QtApplicationNativeImpl.detectQtSystemTheme()
        requestRedraw()
    }

    fun presentWindow(windowImpl: QtWindowNativeImpl) {
        if (!ensureAppInitialized()) return
        val key = windowImpl.nativeWidget.nativePointer
        val existing = sessions[key]
        if (existing != null && !existing.closed) {
            restoreWindowModeAndPresent(existing, windowImpl)
            return
        }

        val undecorated = windowImpl.isUndecorated()
        val size = windowImpl.getWindowSize()
        val initW = size.width.coerceAtLeast(if (undecorated) 64 else 320)
        val initH = size.height.coerceAtLeast(if (undecorated) 64 else 240)
        val effectiveTop = windowImpl.isAlwaysOnTop() || (windowImpl.isToolModalWindow && windowImpl.alwaysOnTopOfOwner)
        val baseFlags = windowImpl.nativeWidget.qtWindowFlags or QtWidgetHandle.Qt_Window
        val flagsWithTop = if (effectiveTop) {
            baseFlags or QtWidgetHandle.Qt_WindowStaysOnTopHint
        } else {
            baseFlags and QtWidgetHandle.Qt_WindowStaysOnTopHint.inv()
        }
        val flags = if (undecorated) {
            flagsWithTop or QtWidgetHandle.Qt_FramelessWindowHint
        } else {
            flagsWithTop and QtWidgetHandle.Qt_FramelessWindowHint.inv()
        }
        val qWidget = Qt6DynamicBindings.createWidget(parent = null, flags = flags) ?: return
        val qLabel = Qt6DynamicBindings.createLabel(parent = qWidget, flags = 0) ?: run {
            Qt6DynamicBindings.destroyWidget(qWidget)
            return
        }

        Qt6DynamicBindings.setWindowTitle(qWidget, windowImpl.getTitle().ifBlank { "GenericUI" })
        Qt6DynamicBindings.resizeWidget(qWidget, initW, initH)
        Qt6DynamicBindings.setWidgetGeometry(qLabel, 0, 0, initW, initH)

        val session = QtWindowSession(
            windowKey = key,
            qWidget = qWidget,
            qLabel = qLabel,
            windowImpl = windowImpl
        ).apply {
            lastAppliedAlwaysOnTop = effectiveTop
        }
        sessions[key] = session
        lastAppliedUndecoratedByKey[key] = undecorated
        lastObservedMinimizedByKey[key] = false
        syncWindowProperties(windowImpl)
        if (!windowImpl.isFullscreen() && windowImpl.getWindowState() == WindowState.NORMAL && windowImpl.hasExplicitWindowPosition()) {
            val pos = windowImpl.getWindowPosition()
            val tx = pos.x.toInt()
            val ty = pos.y.toInt()
            session.lastReportedX = tx
            session.lastReportedY = ty
            session.suppressMoveUntilMark = TimeSource.Monotonic.markNow()
            Qt6DynamicBindings.moveWidget(qWidget, tx, ty)
        }
        if (windowImpl.isFullscreen()) {
            session.pendingTargetFullscreen = true
            session.fullscreenTransitionMark = TimeSource.Monotonic.markNow()
        }
        renderSessionFrame(session)
        when {
            windowImpl.isFullscreen() -> Qt6DynamicBindings.showFullScreen(qWidget)
            windowImpl.getWindowState() == WindowState.MAXIMIZED -> Qt6DynamicBindings.showMaximized(qWidget)
            else -> Qt6DynamicBindings.showWidget(qWidget)
        }
        syncSessionX11Hints(session)
        Qt6DynamicBindings.raiseWidget(qWidget)
        Qt6DynamicBindings.activateWindow(qWidget)
    }

    private fun restoreWindowModeAndPresent(session: QtWindowSession, windowImpl: QtWindowNativeImpl) {
        syncWindowProperties(windowImpl)
        if (windowImpl.isFullscreen()) {
            session.pendingTargetFullscreen = true
            session.fullscreenTransitionMark = TimeSource.Monotonic.markNow()
            val xid = Qt6DynamicBindings.widgetX11Xid(session.qWidget)
            if (xid != 0uL) {
                LinuxX11WindowHintsBridge.setAspectRatioHint(xid, null)
            }
            Qt6DynamicBindings.setWidgetVisible(session.qWidget, true)
            Qt6DynamicBindings.showFullScreen(session.qWidget)
        } else {
            val currentlyFs = Qt6DynamicBindings.isWidgetFullScreen(session.qWidget)
            if (currentlyFs) {
                session.pendingTargetFullscreen = false
                session.fullscreenTransitionMark = TimeSource.Monotonic.markNow()
                Qt6DynamicBindings.showNormal(session.qWidget)
            } else {
                session.pendingTargetFullscreen = null
            }
            val size = windowImpl.getWindowSize()
            val w = size.width.coerceAtLeast(1)
            val h = size.height.coerceAtLeast(1)
            Qt6DynamicBindings.resizeWidget(session.qWidget, w, h)
            Qt6DynamicBindings.setWidgetGeometry(session.qLabel, 0, 0, w, h)
            if (windowImpl.getWindowState() == WindowState.NORMAL && windowImpl.hasExplicitWindowPosition()) {
                val pos = windowImpl.getWindowPosition()
                val tx = pos.x.toInt()
                val ty = pos.y.toInt()
                session.lastReportedX = tx
                session.lastReportedY = ty
                session.suppressMoveUntilMark = TimeSource.Monotonic.markNow()
                Qt6DynamicBindings.moveWidget(session.qWidget, tx, ty)
            }
            Qt6DynamicBindings.setWidgetVisible(session.qWidget, true)
            if (windowImpl.getWindowState() == WindowState.MAXIMIZED) {
                Qt6DynamicBindings.showMaximized(session.qWidget)
            } else {
                Qt6DynamicBindings.showWidget(session.qWidget)
            }
            syncWindowAspectRatio(windowImpl)
        }
        syncSessionX11Hints(session)
        Qt6DynamicBindings.raiseWidget(session.qWidget)
        Qt6DynamicBindings.activateWindow(session.qWidget)
        session.needsRepaint = true
        renderSessionFrame(session)
    }

    fun syncWindowPosition(windowImpl: QtWindowNativeImpl) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        if (windowImpl.isFullscreen() || windowImpl.getWindowState() != WindowState.NORMAL) return
        if (windowImpl.hasExplicitWindowPosition()) {
            val pos = windowImpl.getWindowPosition()
            val tx = pos.x.toInt()
            val ty = pos.y.toInt()
            session.lastReportedX = tx
            session.lastReportedY = ty
            session.suppressMoveUntilMark = TimeSource.Monotonic.markNow()
            Qt6DynamicBindings.moveWidget(session.qWidget, tx, ty)
        }
    }

    private fun presentDialog(
        dialogImpl: DesktopDialogWindowNativeImpl<*>,
        ownerWindow: DesktopWindowNativeImpl<*>?
    ) {
        if (!ensureAppInitialized()) return
        val key = dialogImpl.nativeWidget.nativePointer
        val existing = sessions[key]
        if (existing != null && !existing.closed) {
            Qt6DynamicBindings.setWindowTitle(existing.qWidget, dialogImpl.getTitle())
            Qt6DynamicBindings.showWidget(existing.qWidget)
            Qt6DynamicBindings.raiseWidget(existing.qWidget)
            existing.needsRepaint = true
            renderSessionFrame(existing)
            return
        }

        val ownerSession = (ownerWindow as? QtWindowNativeImpl)?.let { sessions[it.nativeWidget.nativePointer] }
            ?: sessions.values.firstOrNull { !it.closed && it.windowImpl != null }
        val maxW = ownerSession?.lastPaintedWidth?.takeIf { it > 100 } ?: 860
        val maxH = ownerSession?.lastPaintedHeight?.takeIf { it > 100 } ?: 680
        val intrinsic = QtWorkspaceLayoutRenderUtil.measureDialogIntrinsicSize(dialogImpl.nativeWidget, maxW, maxH)
        val w = intrinsic.width.coerceAtLeast(280)
        val h = intrinsic.height.coerceAtLeast(180)

        val qWidget = Qt6DynamicBindings.createWidget(parent = null, flags = QtWidgetHandle.Qt_Dialog) ?: return
        val qLabel = Qt6DynamicBindings.createLabel(parent = qWidget, flags = 0) ?: run {
            Qt6DynamicBindings.destroyWidget(qWidget)
            return
        }
        Qt6DynamicBindings.setWindowTitle(qWidget, dialogImpl.getTitle().ifBlank { "Dialog" })
        Qt6DynamicBindings.setWindowModality(qWidget, 2) // Qt::ApplicationModal
        Qt6DynamicBindings.resizeWidget(qWidget, w, h)
        Qt6DynamicBindings.setWidgetGeometry(qLabel, 0, 0, w, h)

        val session = QtWindowSession(
            windowKey = key,
            qWidget = qWidget,
            qLabel = qLabel,
            dialogImpl = dialogImpl,
            ownerWindowKey = ownerSession?.windowKey
        )
        sessions[key] = session
        renderSessionFrame(session)
        Qt6DynamicBindings.showWidget(qWidget)
        Qt6DynamicBindings.raiseWidget(qWidget)
        Qt6DynamicBindings.activateWindow(qWidget)
    }

    fun runEventLoop() {
        if (!ensureAppInitialized()) return
        if (eventLoopRunning) return
        eventLoopRunning = true
        stopRequested = false
        QtSystemTrayNativeImpl.ensureAllActiveIfInteractive()
        Qt6DynamicBindings.setQuitOnLastWindowClosed(!QtSystemTrayNativeImpl.hasActiveTray())

        try {
            if (visualTestMode) {
                pumpEvents(maxIterations = 24, sleepMsPerStep = 8)
                return
            }

            while (
                eventLoopRunning &&
                !stopRequested &&
                (sessions.values.any { !it.closed } ||
                    QtWindowNativeImpl.activeWindows.any { !it.isDisposed } ||
                    QtSystemTrayNativeImpl.hasActiveTray())
            ) {
                Qt6DynamicBindings.processEvents(16)
                QtSystemTrayNativeImpl.pollActiveTrays()
                onLoopTickHook?.invoke()
                pollWindowStateTransitions()
                syncAndRenderSessions()
                pollPointerEvents()
                usleep(8000u)
            }
        } finally {
            eventLoopRunning = false
        }
    }

    fun pumpEvents(maxIterations: Int = 20, sleepMsPerStep: Int = 8): Int {
        if (!ensureAppInitialized()) return 0
        var steps = 0
        for (i in 0 until maxIterations) {
            Qt6DynamicBindings.processEvents(12)
            onLoopTickHook?.invoke()
            pollWindowStateTransitions()
            syncAndRenderSessions()
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
            val minNow = Qt6DynamicBindings.isWidgetMinimized(session.qWidget)
            val prevMin = lastObservedMinimizedByKey[key]
            if (prevMin != null && prevMin != minNow) {
                val maxNow = Qt6DynamicBindings.isWidgetMaximized(session.qWidget)
                val fsNow = Qt6DynamicBindings.isWidgetFullScreen(session.qWidget)
                winImpl.notifyHostMinimizedFromOs(minNow, maxNow, fsNow)
            }
            lastObservedMinimizedByKey[key] = minNow
        }
    }

    fun terminateEventLoop() {
        stopRequested = true
        eventLoopRunning = false
        val toClose = sessions.values.toList()
        sessions.clear()
        lastAppliedUndecoratedByKey.clear()
        lastObservedMinimizedByKey.clear()
        for (session in toClose) {
            destroySession(session)
        }
        Qt6DynamicBindings.exitApplication(0)
    }

    private fun effectiveWindowedAspectRatio(
        session: QtWindowSession,
        windowImpl: QtWindowNativeImpl
    ): Double? {
        val isNormal = windowImpl.getWindowState() == WindowState.NORMAL &&
            !windowImpl.isFullscreen() &&
            !Qt6DynamicBindings.isWidgetFullScreen(session.qWidget) &&
            !Qt6DynamicBindings.isWidgetMaximized(session.qWidget)
        return if (isNormal) windowImpl.getAspectRatio() else null
    }

    private fun syncSessionX11Hints(session: QtWindowSession) {
        val winImpl = session.windowImpl ?: return
        val xid = Qt6DynamicBindings.widgetX11Xid(session.qWidget)
        if (xid == 0uL) return
        val effectiveTop = winImpl.isAlwaysOnTop() || (winImpl.isToolModalWindow && winImpl.alwaysOnTopOfOwner)
        LinuxX11WindowHintsBridge.syncWindowHints(
            windowXid = xid,
            alwaysOnTop = effectiveTop,
            aspectRatio = effectiveWindowedAspectRatio(session, winImpl)
        )
    }

    fun syncWindowAlwaysOnTop(windowImpl: QtWindowNativeImpl) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        val effectiveTop = windowImpl.isAlwaysOnTop() || (windowImpl.isToolModalWindow && windowImpl.alwaysOnTopOfOwner)
        val xid = Qt6DynamicBindings.widgetX11Xid(session.qWidget)
        if (xid != 0uL) {
            LinuxX11WindowHintsBridge.setAlwaysOnTop(xid, effectiveTop)
            session.lastAppliedAlwaysOnTop = effectiveTop
        } else if (session.lastAppliedAlwaysOnTop != effectiveTop) {
            session.lastAppliedAlwaysOnTop = effectiveTop
            val wasVisible = Qt6DynamicBindings.readPropertyBool(session.qWidget, "visible") == true
            Qt6DynamicBindings.setWidgetWindowFlag(session.qWidget, QtWidgetHandle.Qt_WindowStaysOnTopHint, effectiveTop)
            if (wasVisible) {
                Qt6DynamicBindings.showWidget(session.qWidget)
            }
        }
    }

    fun syncWindowAspectRatio(windowImpl: QtWindowNativeImpl) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        val xid = Qt6DynamicBindings.widgetX11Xid(session.qWidget)
        if (xid != 0uL) {
            LinuxX11WindowHintsBridge.setAspectRatioHint(
                windowXid = xid,
                aspectRatio = effectiveWindowedAspectRatio(session, windowImpl)
            )
        }
    }

    internal fun requestAspectClampResize(
        windowImpl: QtWindowNativeImpl,
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
        val w = targetSize.width.coerceAtLeast(1)
        val h = targetSize.height.coerceAtLeast(1)
        Qt6DynamicBindings.resizeWidget(session.qWidget, w, h)
        Qt6DynamicBindings.setWidgetGeometry(session.qLabel, 0, 0, w, h)
        session.needsRepaint = true
    }

    fun syncWindowProperties(windowImpl: QtWindowNativeImpl) {
        val key = windowImpl.nativeWidget.nativePointer
        val session = sessions[key] ?: return
        val undecorated = windowImpl.isUndecorated()
        Qt6DynamicBindings.setWindowTitle(session.qWidget, windowImpl.getTitle().ifBlank { "GenericUI" })
        if (lastAppliedUndecoratedByKey[key] != undecorated) {
            lastAppliedUndecoratedByKey[key] = undecorated
            val wasVisible = Qt6DynamicBindings.readPropertyBool(session.qWidget, "visible") == true
            Qt6DynamicBindings.setWidgetWindowFlag(session.qWidget, QtWidgetHandle.Qt_FramelessWindowHint, undecorated)
            if (wasVisible) {
                Qt6DynamicBindings.showWidget(session.qWidget)
            }
        }
        val minSize = windowImpl.getMinWindowSize()
        if (minSize != null) {
            val minW = minSize.width.coerceAtLeast(if (undecorated) 64 else 160)
            val minH = minSize.height.coerceAtLeast(if (undecorated) 64 else 120)
            Qt6DynamicBindings.setMinimumSize(session.qWidget, minW, minH)
        }
        val isFixedSize = (windowImpl.nativeWidget.attributes["resizable"] as? Boolean) == false
        if (isFixedSize) {
            val s = windowImpl.getWindowSize()
            Qt6DynamicBindings.setMinimumSize(session.qWidget, s.width, s.height)
            Qt6DynamicBindings.setMaximumSize(session.qWidget, s.width, s.height)
        }
        syncSessionX11Hints(session)
    }

    fun startSystemMove(windowImpl: QtWindowNativeImpl) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        if (session.closed) return
        if (Qt6DynamicBindings.startSystemMove(session.qWidget)) {
            session.pressedRegion = null
            session.pressedWidget = null
        }
    }

    fun syncWindowTitle(windowImpl: QtWindowNativeImpl, title: String) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        Qt6DynamicBindings.setWindowTitle(session.qWidget, title)
    }

    fun syncWindowSize(windowImpl: QtWindowNativeImpl) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        val size = windowImpl.getWindowSize()
        val w = size.width.coerceAtLeast(1)
        val h = size.height.coerceAtLeast(1)
        session.lastClampUnclampedInput = null
        session.lastClampTargetSize = w to h
        Qt6DynamicBindings.resizeWidget(session.qWidget, w, h)
        Qt6DynamicBindings.setWidgetGeometry(session.qLabel, 0, 0, w, h)
        session.needsRepaint = true
        renderSessionFrame(session)
    }

    fun syncWindowState(windowImpl: QtWindowNativeImpl) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        val xid = Qt6DynamicBindings.widgetX11Xid(session.qWidget)
        when (windowImpl.getWindowState()) {
            WindowState.MINIMIZED -> Qt6DynamicBindings.showMinimized(session.qWidget)
            WindowState.MAXIMIZED -> {
                if (xid != 0uL) {
                    LinuxX11WindowHintsBridge.setAspectRatioHint(xid, null)
                }
                Qt6DynamicBindings.showMaximized(session.qWidget)
            }
            WindowState.FULLSCREEN -> {
                if (xid != 0uL) {
                    LinuxX11WindowHintsBridge.setAspectRatioHint(xid, null)
                }
                session.pendingTargetFullscreen = true
                session.fullscreenTransitionMark = TimeSource.Monotonic.markNow()
                Qt6DynamicBindings.showFullScreen(session.qWidget)
            }
            WindowState.NORMAL -> {
                if (Qt6DynamicBindings.isWidgetFullScreen(session.qWidget) || session.pendingTargetFullscreen == true) {
                    session.pendingTargetFullscreen = false
                    session.fullscreenTransitionMark = TimeSource.Monotonic.markNow()
                }
                Qt6DynamicBindings.showNormal(session.qWidget)
                syncWindowAspectRatio(windowImpl)
            }
        }
        session.needsRepaint = true
    }

    fun syncFullscreen(windowImpl: QtWindowNativeImpl, fullscreen: Boolean) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        val xid = Qt6DynamicBindings.widgetX11Xid(session.qWidget)
        if (fullscreen) {
            if (xid != 0uL) {
                LinuxX11WindowHintsBridge.setAspectRatioHint(xid, null)
            }
            session.pendingTargetFullscreen = true
            session.fullscreenTransitionMark = TimeSource.Monotonic.markNow()
            Qt6DynamicBindings.showFullScreen(session.qWidget)
        } else {
            session.pendingTargetFullscreen = false
            session.fullscreenTransitionMark = TimeSource.Monotonic.markNow()
            Qt6DynamicBindings.showNormal(session.qWidget)
            syncWindowAspectRatio(windowImpl)
        }
        session.needsRepaint = true
    }

    fun syncWindowVisibility(windowImpl: QtWindowNativeImpl, visible: Boolean) {
        val session = sessions[windowImpl.nativeWidget.nativePointer]
        if (visible) {
            if (session == null || session.closed) presentWindow(windowImpl)
            else {
                restoreWindowModeAndPresent(session, windowImpl)
            }
        } else if (session != null) {
            if (Qt6DynamicBindings.isWidgetFullScreen(session.qWidget) || session.pendingTargetFullscreen == true) {
                session.pendingTargetFullscreen = false
                session.fullscreenTransitionMark = TimeSource.Monotonic.markNow()
                Qt6DynamicBindings.showNormal(session.qWidget)
            }
            Qt6DynamicBindings.setWidgetVisible(session.qWidget, false)
        }
    }

    fun bringWindowToFront(windowImpl: QtWindowNativeImpl) {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        Qt6DynamicBindings.raiseWidget(session.qWidget)
        Qt6DynamicBindings.activateWindow(session.qWidget)
    }

    fun onWindowClosed(windowImpl: QtWindowNativeImpl) {
        val key = windowImpl.nativeWidget.nativePointer
        val session = sessions.remove(key) ?: return
        destroySession(session)
    }

    private fun closeDialogSession(dialogImpl: DesktopDialogWindowNativeImpl<*>) {
        val key = dialogImpl.nativeWidget.nativePointer
        val session = sessions.remove(key) ?: return
        destroySession(session)
    }

    private fun destroySession(session: QtWindowSession) {
        lastAppliedUndecoratedByKey.remove(session.windowKey)
        lastObservedMinimizedByKey.remove(session.windowKey)
        if (session.closed) return
        session.closed = true
        Qt6DynamicBindings.closeWidget(session.qWidget)
        Qt6DynamicBindings.destroyLabel(session.qLabel)
        Qt6DynamicBindings.destroyWidget(session.qWidget)
    }

    private fun syncAndRenderSessions() {
        val closedKeys = ArrayList<Long>()
        for ((key, session) in sessions) {
            if (session.closed) {
                closedKeys.add(key)
                continue
            }
            val qSize = Qt6DynamicBindings.readPropertyQSize(session.qWidget, "size")
            val w = qSize?.first?.coerceAtLeast(1) ?: session.lastPaintedWidth
            val h = qSize?.second?.coerceAtLeast(1) ?: session.lastPaintedHeight
            if (w > 0 && h > 0 && (w != session.lastPaintedWidth || h != session.lastPaintedHeight || session.needsRepaint)) {
                renderSessionFrame(session)
            }
        }
        for (key in closedKeys) {
            sessions.remove(key)
        }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun renderSessionFrame(session: QtWindowSession) {
        if (session.closed) return
        val qSize = Qt6DynamicBindings.readPropertyQSize(session.qWidget, "size")
        val fallbackW = session.windowImpl?.getWindowSize()?.width ?: session.lastPaintedWidth.coerceAtLeast(320)
        val fallbackH = session.windowImpl?.getWindowSize()?.height ?: session.lastPaintedHeight.coerceAtLeast(240)
        val width = (qSize?.first?.takeIf { it > 0 } ?: fallbackW).coerceAtLeast(1)
        val height = (qSize?.second?.takeIf { it > 0 } ?: fallbackH).coerceAtLeast(1)

        if (session.argbBuffer.size != width * height || session.bufferWidth != width || session.bufferHeight != height) {
            session.argbBuffer = IntArray(width * height)
            session.bufferWidth = width
            session.bufferHeight = height
            Qt6DynamicBindings.setWidgetGeometry(session.qLabel, 0, 0, width, height)
        }

        val darkMode = QtControlRenderUtil.isDarkThemeActive()
        val themeConfig = BootstrapColorPalette.activeThemeConfig
        val palette = QtThemePalette.resolve(darkMode, themeConfig)
        val viewport = Rect(0, 0, width, height)

        val ctx = QtRenderContext(
            windowKey = session.windowKey,
            viewport = viewport,
            argbPixels = session.argbBuffer,
            bufferWidth = width,
            bufferHeight = height,
            darkMode = darkMode,
            themeConfig = themeConfig,
            palette = palette,
            focusedWidget = session.focusedWidget,
            hoveredWidget = session.hoveredWidget,
            pressedWidget = session.pressedWidget
        )

        val presented = Qt6DynamicBindings.presentArgbBufferOnLabel(
            label = session.qLabel,
            argbPixels = session.argbBuffer,
            width = width,
            height = height
        ) { qPainter ->
            ctx.qPainter = qPainter
            try {
                val winImpl = session.windowImpl
                val dlgImpl = session.dialogImpl
                if (winImpl != null) {
                    val rawFs = Qt6DynamicBindings.isWidgetFullScreen(session.qWidget)
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
                    val isMax = Qt6DynamicBindings.isWidgetMaximized(session.qWidget)
                    winImpl.syncHostClientSize(width, height, fullscreenNow = isFs, maximizedNow = isMax)
                    if (session.paintedFrameCount > 1 && winImpl.isVisible() && !isFs && !isMax &&
                        winImpl.getWindowState() == WindowState.NORMAL
                    ) {
                        val moveSuppressElapsed = session.suppressMoveUntilMark?.elapsedNow()?.inWholeMilliseconds ?: 999L
                        if (moveSuppressElapsed >= 200L) {
                            val frameGeom = Qt6DynamicBindings.readPropertyQRect(session.qWidget, "frameGeometry")
                                ?: Qt6DynamicBindings.widgetX11Xid(session.qWidget).takeIf { it != 0uL }?.let { xid ->
                                    LinuxX11WindowHintsBridge.queryWindowPosition(xid)?.let { intArrayOf(it.first, it.second, width, height) }
                                }
                            if (frameGeom != null) {
                                val fx = frameGeom[0]
                                val fy = frameGeom[1]
                                if (fx != session.lastReportedX || fy != session.lastReportedY) {
                                    session.lastReportedX = fx
                                    session.lastReportedY = fy
                                    winImpl.notifyHostPositionChanged(fx, fy)
                                }
                            }
                        }
                    }
                    QtWorkspaceLayoutRenderUtil.renderMainWindow(
                        ctx = ctx,
                        windowImpl = winImpl as DesktopWindowNativeImpl<DesktopNativeWidget>
                    )
                } else if (dlgImpl != null) {
                    QtWorkspaceLayoutRenderUtil.renderDialogWindow(ctx, dlgImpl)
                }
            } finally {
                ctx.qPainter = null
            }
        }

        if (presented) {
            session.hitRegions = ctx.hitRegions
            session.scrollRegions = ctx.scrollRegions
            session.paintedFrameCount++
            session.lastPaintedWidth = width
            session.lastPaintedHeight = height
            session.needsRepaint = false
            Qt6DynamicBindings.updateWidget(session.qLabel)
        }
    }

    private fun pollPointerEvents() {
        val globalPos = Qt6DynamicBindings.cursorPos() ?: return
        val mouseButtons = Qt6DynamicBindings.mouseButtons()
        val leftDown = (mouseButtons and 1u) != 0u

        for (session in sessions.values) {
            if (session.closed) continue
            val visible = Qt6DynamicBindings.readPropertyBool(session.qWidget, "visible") ?: false
            if (!visible) continue
            val geom = Qt6DynamicBindings.readPropertyQRect(session.qWidget, "geometry") ?: continue
            val localX = (globalPos.first - geom[0]).toFloat()
            val localY = (globalPos.second - geom[1]).toFloat()
            val insideWindow = localX >= 0f && localY >= 0f && localX < geom[2] && localY < geom[3]

            if (!insideWindow && session.hoveredWidget != null && !session.wasMouseDown) {
                (session.hoveredWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.dispatchPointerLeave()
                session.hoveredWidget = null
                session.hoveredRegion = null
                updateSessionCursor(session, null)
            }

            if (insideWindow) {
                val region = session.hitRegions.lastOrNull { it.contains(localX.toInt(), localY.toInt()) }
                if (region?.widget !== session.hoveredWidget && !session.wasMouseDown) {
                    (session.hoveredWidget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.dispatchPointerLeave()
                    session.hoveredWidget = region?.widget
                    session.hoveredRegion = region
                    (region?.widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)?.dispatchPointerEnter()
                    updateSessionCursor(session, region)
                    session.needsRepaint = true
                }

                if (leftDown && !session.wasMouseDown) {
                    session.pressedRegion = region
                    session.pressedWidget = region?.widget
                    session.focusedWidget = if (region?.isTextInput == true) region.widget else null
                    val impl = region?.widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>
                    if (region != null && impl != null) {
                        impl.dispatchPointerDown(PointerEvent(x = localX - region.left, y = localY - region.top, screenX = localX, screenY = localY, button = 0))
                    } else if (region == null && session.windowImpl?.isUndecorated() == true) {
                        startSystemMove(session.windowImpl)
                    }
                    session.needsRepaint = true
                } else if (leftDown && session.wasMouseDown) {
                    val pressed = session.pressedRegion
                    if (pressed != null && (localX != session.lastMouseX || localY != session.lastMouseY)) {
                        val lx = localX - pressed.left
                        val ly = localY - pressed.top
                        pressed.onDrag?.invoke(lx, ly, localX, localY)
                        (pressed.widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)
                            ?.dispatchPointerMove(PointerEvent(x = lx, y = ly, screenX = localX, screenY = localY))
                        session.needsRepaint = true
                    }
                } else if (!leftDown && session.wasMouseDown) {
                    val pressed = session.pressedRegion
                    session.pressedRegion = null
                    session.pressedWidget = null
                    if (pressed != null) {
                        val insideRegion = pressed.contains(localX.toInt(), localY.toInt())
                        val lx = localX - pressed.left
                        val ly = localY - pressed.top
                        pressed.onRelease?.invoke(lx, ly, localX, localY, insideRegion)
                        (pressed.widget?.attributes?.get("nativeImpl") as? DesktopBaseNativeImpl<*>)
                            ?.dispatchPointerUp(PointerEvent(x = lx, y = ly, screenX = localX, screenY = localY, button = 0))
                        if (insideRegion) {
                            pressed.onClick?.invoke()
                        }
                    }
                    session.needsRepaint = true
                }
                session.lastMouseX = localX
                session.lastMouseY = localY
            }
            session.wasMouseDown = leftDown
        }
    }

    private fun updateSessionCursor(session: QtWindowSession, region: QtHitRegion?) {
        val shape = when {
            region?.isTextInput == true -> Qt6DynamicBindings.QT_CURSOR_IBEAM
            region?.cursorCss == "pointer" -> Qt6DynamicBindings.QT_CURSOR_POINTING_HAND
            region?.cursorCss == "text" -> Qt6DynamicBindings.QT_CURSOR_IBEAM
            region?.cursorCss == "crosshair" -> Qt6DynamicBindings.QT_CURSOR_CROSS
            region?.cursorCss == "wait" -> Qt6DynamicBindings.QT_CURSOR_WAIT
            else -> Qt6DynamicBindings.QT_CURSOR_ARROW
        }
        if (session.lastAppliedCursorShape == shape) return
        session.lastAppliedCursorShape = shape
        Qt6DynamicBindings.setWidgetCursorShape(session.qWidget, shape)
    }
}
