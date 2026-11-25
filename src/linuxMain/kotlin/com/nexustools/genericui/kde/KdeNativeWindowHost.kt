@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.animation.Animator
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.event.PointerDragManager
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageColorSpace
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.linux.LinuxX11WindowHintsBridge
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import platform.posix.usleep
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * Retained KDE / Qt 6 top-level `QWidget` window host managing the Qt event loop, X11/Wayland
 * window flags & `KWindowEffects` blur-behind, Breeze palette synchronization, and offscreen/live
 * window screenshot capture (`captureWindowScreenshot`).
 */
object KdeNativeWindowHost {

    private val sessions = LinkedHashMap<Long, KdeWindowSession>(8)
    private val vblankClockOrigin = TimeSource.Monotonic.markNow()
    private var eventLoopPrepared = false
    private var cursorLockListenerInstalled = false
    private var eventLoopRunning = false
    private var stopRequested = false
    private val hostFallbackWatcherId = kotlin.concurrent.AtomicInt(-1)
    private val surfaceFrameWakeScheduled = kotlin.concurrent.AtomicInt(0)

    var visualTestMode: Boolean = false

    fun prepareEventLoop() {
        val isDark = SystemThemeDetector.resolveTheme(ThemeMode.AUTO) == ThemeMode.DARK
        KdeDynamicBindings.ensureQApplication(isDark)
        KdeVTableBridge.ensureInitialized()
        KdeUiThreadDispatcherNativeImpl.activeInstance?.bindCurrentThreadAsUiThread()
        DesktopNativeWidget.onVBlankWidgetsChanged = {
            syncHostVBlankDemand()
        }
        DesktopNativeWidget.onSurfaceFrameReadyGlobal = { _ ->
            scheduleSurfaceFrameWakeup()
        }
        DesktopNativeWidget.onCursorUpdated = {
            if (eventLoopPrepared) {
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
                if (eventLoopPrepared) {
                    if (GenericUiThreading.isOnUiThread()) {
                        syncCursorsOnUiThread()
                    } else {
                        GenericUiThreading.runOnUi { syncCursorsOnUiThread() }
                    }
                }
            }
        }
        eventLoopPrepared = true
    }

    private fun syncCursorsOnUiThread() {
        for (session in sessions.values) {
            if (session.closed || !KdeVTableBridge.isPeerAlive(session.qWidget)) continue
            val root = session.rootHandle
                ?: session.windowImpl?.nativeWidget
                ?: session.dialogImpl?.nativeWidget
                ?: session.dockWindowImpl?.nativeWidget
            if (root != null) {
                KdePainterSupport.refreshWidgetTreeCursors(root)
            }
        }
        for (win in KdeWindowNativeImpl.activeWindows) {
            if (!win.isDisposed && sessions[win.nativeWidget.nativePointer] == null) {
                KdePainterSupport.refreshWidgetTreeCursors(win.nativeWidget)
            }
        }
    }

    private fun currentVBlankTimestampMs(): Double =
        vblankClockOrigin.elapsedNow().inWholeNanoseconds / 1_000_000.0

    internal fun onAnimatorVBlankTick(timestampMs: Double? = currentVBlankTimestampMs()) {
        dispatchActiveVBlankWidgets(timestampMs)
    }

    internal fun syncHostVBlankDemand() {
        val hasActive = DesktopNativeWidget.activeVBlankWidgetsSnapshot().isNotEmpty()
        val preferredRateHz = if (hasActive) DesktopNativeWidget.resolvePreferredVBlankRateHz() else null
        val kdeScheduler = Animator.activeScheduler as? KdeAnimationNativeImpl
        if (kdeScheduler != null) {
            val prevId = hostFallbackWatcherId.value
            if (prevId > 0 && hostFallbackWatcherId.compareAndSet(prevId, -1)) {
                Animator.unwatchVBlank(prevId)
            }
            kdeScheduler.setHostSurfaceDemand(hasActive, preferredRateHz)
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
        if (!eventLoopPrepared) return
        if (!surfaceFrameWakeScheduled.compareAndSet(0, 1)) return
        val deliver = {
            surfaceFrameWakeScheduled.value = 0
            onAnimatorVBlankTick(currentVBlankTimestampMs())
            requestRedraw()
        }
        if (GenericUiThreading.isOnUiThread()) {
            deliver()
        } else {
            GenericUiThreading.runOnUi(deliver)
        }
    }

    private fun dispatchActiveVBlankWidgets(timestampMs: Double? = currentVBlankTimestampMs()) {
        val active = DesktopNativeWidget.activeVBlankWidgetsSnapshot()
        if (active.isEmpty()) return
        for (w in active) {
            if (!w.disposed) {
                w.vblankDispatch?.invoke(timestampMs)
                (w as? KdeWidgetHandle)?.qWidgetPtr?.let { ptr -> KdeDynamicBindings.updateWidget(ptr) }
            }
        }
    }

    fun runEventLoop() {
        if (!eventLoopPrepared) prepareEventLoop()
        if (visualTestMode) {
            pumpEvents(maxIterations = 8, sleepMsPerStep = 4)
            return
        }
        if (eventLoopRunning) return
        eventLoopRunning = true
        stopRequested = false
        syncHostVBlankDemand()
        try {
            KdeDynamicBindings.execEventLoop()
        } finally {
            eventLoopRunning = false
        }
    }

    fun terminateEventLoop() {
        stopRequested = true
        surfaceFrameWakeScheduled.value = 0
        val prevId = hostFallbackWatcherId.value
        if (prevId > 0 && hostFallbackWatcherId.compareAndSet(prevId, -1)) {
            Animator.unwatchVBlank(prevId)
        }
        (Animator.activeScheduler as? KdeAnimationNativeImpl)?.setHostSurfaceDemand(false, null)
        val toClose = sessions.values.toList()
        for (s in toClose) {
            if (!s.closed && KdeVTableBridge.isPeerAlive(s.qWidget)) {
                KdeDynamicBindings.setWidgetVisible(s.qWidget, false)
                s.closed = true
            }
        }
        sessions.clear()
        if (eventLoopRunning) {
            KdeDynamicBindings.quitEventLoop()
            eventLoopRunning = false
        }
    }

    fun presentWindow(winImpl: KdeWindowNativeImpl) {
        if (!eventLoopPrepared) prepareEventLoop()
        val qWin = winImpl.nativeWidget.qWidgetPtr ?: return
        val key = winImpl.nativeWidget.nativePointer
        val session = sessions.getOrPut(key) {
            KdeWindowSession(key = key, qWidget = qWin, windowImpl = winImpl).apply {
                rootHandle = winImpl.nativeWidget
            }
        }

        val sz = winImpl.getWindowSize()
        val w = sz.width.coerceAtLeast(1)
        val h = sz.height.coerceAtLeast(1)
        session.lastWidth = w
        session.lastHeight = h

        if (visualTestMode) {
            KdeDynamicBindings.setWidgetAttribute(qWin, KdeDynamicBindings.WA_DONT_SHOW_ON_SCREEN, true)
            KdeDynamicBindings.setWidgetAttribute(qWin, KdeDynamicBindings.WA_SHOW_WITHOUT_ACTIVATING, true)
        }

        syncWindowProperties(winImpl)
        KdeDynamicBindings.resizeWidget(qWin, w, h)
        if (winImpl.hasExplicitWindowPosition()) {
            val pos = winImpl.getWindowPosition()
            KdeDynamicBindings.moveWidget(qWin, pos.x.roundToInt(), pos.y.roundToInt())
        }
        winImpl.layoutWindowChromeAndChildren(w.toFloat(), h.toFloat())
        KdeDynamicBindings.setWidgetVisible(qWin, winImpl.isVisible())
        session.shown = winImpl.isVisible()
        applyPostShowX11AndKWinEffects(session, winImpl)
        if (winImpl.isVisible() && !visualTestMode) {
            KdeDynamicBindings.raiseWidget(qWin)
            KdeDynamicBindings.activateWindow(qWin)
        }
    }

    fun syncWindowTitle(winImpl: KdeWindowNativeImpl, title: String) {
        val qWin = winImpl.nativeWidget.qWidgetPtr ?: return
        if (!winImpl.nativeWidget.kdeDestroyed) {
            KdeDynamicBindings.setWindowTitle(qWin, title)
        }
    }

    fun syncWindowProperties(winImpl: KdeWindowNativeImpl) {
        val qWin = winImpl.nativeWidget.qWidgetPtr ?: return
        if (winImpl.nativeWidget.kdeDestroyed) return

        var flags = if (winImpl.isToolModalWindow) KdeDynamicBindings.WINDOW_TOOL else KdeDynamicBindings.WINDOW_WINDOW
        if (winImpl.isUndecorated()) {
            flags = flags or KdeDynamicBindings.WINDOW_FRAMELESS_HINT
        }
        if (winImpl.isAlwaysOnTop()) {
            flags = flags or KdeDynamicBindings.WINDOW_STAYS_ON_TOP_HINT
        }
        KdeDynamicBindings.setWindowFlags(qWin, flags)

        val translucent = winImpl.nativeWidget.attributes["windowTranslucent"] == true
        KdeDynamicBindings.setWidgetAttribute(qWin, KdeDynamicBindings.WA_TRANSLUCENT_BACKGROUND, translucent)

        val minSz = winImpl.getMinWindowSize()
        if (minSz != null) {
            KdeDynamicBindings.setWidgetMinSize(qWin, minSz.width, minSz.height)
        }
        KdeDynamicBindings.setWindowTitle(qWin, winImpl.getTitle())
        KdeDynamicBindings.setWindowOpacity(qWin, winImpl.getOpacity())
        syncWindowAspectRatio(winImpl)
        val session = sessions[winImpl.nativeWidget.nativePointer]
        if (session != null && session.shown) {
            KdeDynamicBindings.setWidgetVisible(qWin, winImpl.isVisible())
            applyPostShowX11AndKWinEffects(session, winImpl)
        }
    }

    fun syncWindowAlwaysOnTop(winImpl: KdeWindowNativeImpl) {
        val qWin = winImpl.nativeWidget.qWidgetPtr ?: return
        val session = sessions[winImpl.nativeWidget.nativePointer]
        if (session != null && session.x11WinId != 0uL) {
            LinuxX11WindowHintsBridge.setAlwaysOnTop(session.x11WinId, winImpl.isAlwaysOnTop())
        } else {
            syncWindowProperties(winImpl)
        }
        KdeDynamicBindings.updateWidget(qWin)
    }

    fun syncWindowAspectRatio(winImpl: KdeWindowNativeImpl) {
        val session = sessions[winImpl.nativeWidget.nativePointer] ?: return
        if (session.x11WinId == 0uL) return
        val ratio = if (!winImpl.isFullscreen() && winImpl.getWindowState() == WindowState.NORMAL) {
            winImpl.getAspectRatio()
        } else null
        val minSz = winImpl.getMinWindowSize()
        LinuxX11WindowHintsBridge.syncWindowHints(
            windowXid = session.x11WinId,
            alwaysOnTop = winImpl.isAlwaysOnTop(),
            aspectRatio = ratio,
            minWidthPx = minSz?.width,
            minHeightPx = minSz?.height
        )
    }

    fun syncWindowSize(winImpl: KdeWindowNativeImpl) {
        val qWin = winImpl.nativeWidget.qWidgetPtr ?: return
        val sz = winImpl.getWindowSize()
        val w = sz.width.coerceAtLeast(1)
        val h = sz.height.coerceAtLeast(1)
        KdeDynamicBindings.resizeWidget(qWin, w, h)
        sessions[winImpl.nativeWidget.nativePointer]?.apply {
            lastWidth = w
            lastHeight = h
        }
        winImpl.layoutWindowChromeAndChildren(w.toFloat(), h.toFloat())
    }

    fun syncWindowPosition(winImpl: KdeWindowNativeImpl) {
        val qWin = winImpl.nativeWidget.qWidgetPtr ?: return
        val pos = winImpl.getWindowPosition()
        KdeDynamicBindings.moveWidget(qWin, pos.x.roundToInt(), pos.y.roundToInt())
    }

    fun syncWindowState(winImpl: KdeWindowNativeImpl) {
        val qWin = winImpl.nativeWidget.qWidgetPtr ?: return
        when (winImpl.getWindowState()) {
            WindowState.MINIMIZED -> KdeDynamicBindings.showMinimized(qWin)
            WindowState.MAXIMIZED -> KdeDynamicBindings.showMaximized(qWin)
            WindowState.FULLSCREEN -> KdeDynamicBindings.showFullScreen(qWin)
            WindowState.NORMAL -> KdeDynamicBindings.showNormal(qWin)
        }
    }

    fun syncFullscreen(winImpl: KdeWindowNativeImpl, fullscreen: Boolean) {
        val qWin = winImpl.nativeWidget.qWidgetPtr ?: return
        if (fullscreen) KdeDynamicBindings.showFullScreen(qWin)
        else KdeDynamicBindings.showNormal(qWin)
    }

    fun syncWindowVisibility(winImpl: KdeWindowNativeImpl, visible: Boolean) {
        val qWin = winImpl.nativeWidget.qWidgetPtr ?: return
        KdeDynamicBindings.setWidgetVisible(qWin, visible)
    }

    fun bringWindowToFront(winImpl: KdeWindowNativeImpl) {
        val qWin = winImpl.nativeWidget.qWidgetPtr ?: return
        KdeDynamicBindings.raiseWidget(qWin)
        KdeDynamicBindings.activateWindow(qWin)
    }

    fun startSystemMove(winImpl: KdeWindowNativeImpl) {
        val qWin = winImpl.nativeWidget.qWidgetPtr ?: return
        if (!KdeVTableBridge.isPeerAlive(qWin)) return
        if (KdeDynamicBindings.startWindowSystemMove(qWin)) return
        val session = sessions[winImpl.nativeWidget.nativePointer] ?: return
        if (session.x11WinId != 0uL) {
            val gPos = KdeDynamicBindings.globalCursorPos()
            if (gPos != null) {
                LinuxX11WindowHintsBridge.initiateSystemMove(session.x11WinId, gPos.first, gPos.second)
            }
        }
    }

    fun showWindowMenu(winImpl: KdeWindowNativeImpl, screenX: Int, screenY: Int): Boolean {
        val qWin = winImpl.nativeWidget.qWidgetPtr ?: return false
        if (!KdeVTableBridge.isPeerAlive(qWin)) return false
        val session = sessions[winImpl.nativeWidget.nativePointer]
        val xid = (session?.x11WinId?.takeIf { it != 0uL })
            ?: if (LinuxX11WindowHintsBridge.isDisplayEnvironmentPresent()) KdeDynamicBindings.getWidgetWinId(qWin) else 0uL
        if (xid == 0uL) return false
        return LinuxX11WindowHintsBridge.showWindowMenu(xid, screenX, screenY)
    }

    fun requestAspectClampResize(winImpl: KdeWindowNativeImpl, requested: Size, resolved: Size) {
        val qWin = winImpl.nativeWidget.qWidgetPtr ?: return
        if (requested != resolved) {
            KdeDynamicBindings.resizeWidget(qWin, resolved.width, resolved.height)
        }
    }

    fun onWindowClosed(winImpl: KdeWindowNativeImpl) {
        val session = sessions.remove(winImpl.nativeWidget.nativePointer)
        if (session != null && !session.closed) {
            session.closed = true
            if (KdeVTableBridge.isPeerAlive(session.qWidget)) {
                KdeDynamicBindings.setWidgetVisible(session.qWidget, false)
            }
        }
    }

    fun presentDialog(dialogImpl: KdeDialogWindowNativeImpl, ownerWindow: WindowNativeImpl<*>?) {
        if (!eventLoopPrepared) prepareEventLoop()
        val dlgPtr = dialogImpl.nativeWidget.qWidgetPtr ?: return
        val ownerPtr = (ownerWindow as? KdeWindowNativeImpl)?.nativeWidget?.qWidgetPtr ?: activeQWidgetPtr()
        if (ownerPtr != null) {
            KdeDynamicBindings.setWidgetParent(dlgPtr, ownerPtr, KdeDynamicBindings.WINDOW_DIALOG)
        }
        if (visualTestMode) {
            KdeDynamicBindings.setWidgetAttribute(dlgPtr, KdeDynamicBindings.WA_DONT_SHOW_ON_SCREEN, true)
        }
        val bw = dialogImpl.nativeWidget.bounds.width.takeIf { it > 0 } ?: 480
        val bh = dialogImpl.nativeWidget.bounds.height.takeIf { it > 0 } ?: 360
        KdeDynamicBindings.resizeWidget(dlgPtr, bw, bh)
        dialogImpl.layoutChildrenInBounds(bw.toFloat(), bh.toFloat())
        KdeDynamicBindings.setWidgetVisible(dlgPtr, true)
        KdeDynamicBindings.raiseWidget(dlgPtr)
    }

    fun closeDialog(dialogImpl: KdeDialogWindowNativeImpl) {
        val dlgPtr = dialogImpl.nativeWidget.qWidgetPtr ?: return
        if (KdeVTableBridge.isPeerAlive(dlgPtr)) {
            KdeDynamicBindings.setWidgetVisible(dlgPtr, false)
        }
    }

    fun syncThemeAppearance(mode: ThemeMode) {
        val dark = SystemThemeDetector.resolveTheme(mode) == ThemeMode.DARK
        KdeDynamicBindings.applyBreezeStyleAndPalette(dark)
        for (s in sessions.values) {
            s.windowImpl?.syncTitleBarDerivedAttributes()
            if (KdeVTableBridge.isPeerAlive(s.qWidget)) {
                KdeDynamicBindings.updateWidget(s.qWidget)
            }
        }
    }

    fun requestRedraw() {
        for (s in sessions.values) {
            if (!s.closed && KdeVTableBridge.isPeerAlive(s.qWidget)) {
                KdeDynamicBindings.updateWidget(s.qWidget)
            }
        }
    }

    fun activeQWidgetPtr(): COpaquePointer? =
        sessions.values.lastOrNull { !it.closed && KdeVTableBridge.isPeerAlive(it.qWidget) }?.qWidget

    internal fun sessionForWindow(winImpl: KdeWindowNativeImpl): KdeWindowSession? =
        sessions[winImpl.nativeWidget.nativePointer]

    fun pumpEvents(maxIterations: Int = 8, sleepMsPerStep: Int = 4) {
        if (!eventLoopPrepared) prepareEventLoop()
        val iters = maxIterations.coerceAtLeast(1)
        for (i in 0 until iters) {
            KdeUiThreadDispatcherNativeImpl.activeInstance?.drainUiQueue()
            KdeDynamicBindings.processEvents(10)
            dispatchActiveVBlankWidgets(null)
            if (sleepMsPerStep > 0 && i + 1 < iters) {
                usleep((sleepMsPerStep * 1000).toUInt())
            }
        }
    }

    /**
     * Captures a live RGBA screenshot of [winImpl]'s retained Qt 6 widget hierarchy (`QWidget::render`)
     * into an [ImageBuffer] for visual verification tests.
     */
    fun captureWindowScreenshot(
        winImpl: KdeWindowNativeImpl,
        width: Int = winImpl.getWindowSize().width.coerceAtLeast(1),
        height: Int = winImpl.getWindowSize().height.coerceAtLeast(1)
    ): ImageBuffer? {
        if (!eventLoopPrepared) prepareEventLoop()
        val qWin = winImpl.nativeWidget.qWidgetPtr ?: return null
        if (!KdeVTableBridge.isPeerAlive(qWin)) return null

        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        KdeDynamicBindings.resizeWidget(qWin, w, h)
        winImpl.layoutWindowChromeAndChildren(w.toFloat(), h.toFloat())
        KdeDynamicBindings.processEvents(4)

        val img = KdeDynamicBindings.createQImage(w, h, KdeDynamicBindings.IMAGE_FORMAT_ARGB32_PREMULTIPLIED)
        try {
            val translucent = winImpl.nativeWidget.attributes["windowTranslucent"] == true
            KdeDynamicBindings.fillQImage(img, if (translucent) 0x00000000u else 0xFFEFF0F1u)
            KdeDynamicBindings.withQPainter(img) { painter ->
                KdeDynamicBindings.renderWidgetIntoPainter(
                    widget = qWin,
                    painter = painter,
                    targetOffsetX = 0,
                    targetOffsetY = 0,
                    srcX = 0,
                    srcY = 0,
                    srcW = w,
                    srcH = h,
                    renderFlags = KdeDynamicBindings.RENDER_DRAW_WINDOW_BACKGROUND or KdeDynamicBindings.RENDER_DRAW_CHILDREN
                )
            }

            val bits = KdeDynamicBindings.qImageBits(img)?.reinterpret<ByteVar>() ?: return null
            val stride = KdeDynamicBindings.qImageBytesPerLine(img).takeIf { it > 0 } ?: (w * 4)
            val rgbaOut = ByteArray(w * h * 4)
            for (y in 0 until h) {
                val rowOff = y * stride
                val dstRow = y * w * 4
                for (x in 0 until w) {
                    val s = rowOff + x * 4
                    val b = bits[s].toInt() and 0xFF
                    val g = bits[s + 1].toInt() and 0xFF
                    val r = bits[s + 2].toInt() and 0xFF
                    val a = bits[s + 3].toInt() and 0xFF
                    val d = dstRow + x * 4
                    if (a == 255) {
                        rgbaOut[d] = r.toByte()
                        rgbaOut[d + 1] = g.toByte()
                        rgbaOut[d + 2] = b.toByte()
                        rgbaOut[d + 3] = 255.toByte()
                    } else if (a == 0) {
                        rgbaOut[d] = 0
                        rgbaOut[d + 1] = 0
                        rgbaOut[d + 2] = 0
                        rgbaOut[d + 3] = 0
                    } else {
                        rgbaOut[d] = ((r * 255 + a / 2) / a).coerceIn(0, 255).toByte()
                        rgbaOut[d + 1] = ((g * 255 + a / 2) / a).coerceIn(0, 255).toByte()
                        rgbaOut[d + 2] = ((b * 255 + a / 2) / a).coerceIn(0, 255).toByte()
                        rgbaOut[d + 3] = a.toByte()
                    }
                }
            }
            return ImageBuffer(
                width = w,
                height = h,
                pixelFormat = PixelFormat.RGBA_8888,
                colorSpace = ImageColorSpace.SRGB,
                bytes = rgbaOut
            )
        } finally {
            KdeDynamicBindings.destroyQImage(img)
        }
    }

    private fun applyPostShowX11AndKWinEffects(session: KdeWindowSession, winImpl: KdeWindowNativeImpl) {
        val qWin = session.qWidget
        val translucent = winImpl.nativeWidget.attributes["windowTranslucent"] == true ||
            winImpl.isUndecorated()
        if (translucent && KdeDynamicBindings.kf6WindowSystemAvailable) {
            KdeDynamicBindings.enableKWindowBlurBehind(qWin, true, session.lastWidth, session.lastHeight)
        }
        if (LinuxX11WindowHintsBridge.isDisplayEnvironmentPresent()) {
            val xid = KdeDynamicBindings.getWidgetWinId(qWin)
            if (xid != 0uL) {
                session.x11WinId = xid
                if (winImpl.isUndecorated()) {
                    LinuxX11WindowHintsBridge.setMotifDecorations(xid, decorated = false)
                }
                syncWindowAspectRatio(winImpl)
            }
        }
    }

    fun detectUndecoratedResizeEdgeMaskRect(
        width: Int,
        height: Int,
        x: Float,
        y: Float,
        hitOverInteractiveControl: Boolean
    ): Int = com.nexustools.genericui.linux.style.LinuxStyledSurfaceHelper.computeResizeEdgeBitmask(
        width = width,
        height = height,
        x = x,
        y = y,
        borderPx = 6f,
        hitOverInteractiveControl = hitOverInteractiveControl,
        perimeterPx = 2f
    )

    fun resizeEdgeCursorShape(edgeMask: Int): Int {
        val left = (edgeMask and com.nexustools.genericui.linux.titlebar.LinuxOverlayTitleBarResolver.EDGE_LEFT) != 0
        val right = (edgeMask and com.nexustools.genericui.linux.titlebar.LinuxOverlayTitleBarResolver.EDGE_RIGHT) != 0
        val top = (edgeMask and com.nexustools.genericui.linux.titlebar.LinuxOverlayTitleBarResolver.EDGE_TOP) != 0
        val bottom = (edgeMask and com.nexustools.genericui.linux.titlebar.LinuxOverlayTitleBarResolver.EDGE_BOTTOM) != 0
        return when {
            (top && left) || (bottom && right) -> KdeDynamicBindings.CURSOR_SIZE_FDIAG
            (top && right) || (bottom && left) -> KdeDynamicBindings.CURSOR_SIZE_BDIAG
            top || bottom -> KdeDynamicBindings.CURSOR_SIZE_VER
            left || right -> KdeDynamicBindings.CURSOR_SIZE_HOR
            else -> KdeDynamicBindings.CURSOR_ARROW
        }
    }
}
