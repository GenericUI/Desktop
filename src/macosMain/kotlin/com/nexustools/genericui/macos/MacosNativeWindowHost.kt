@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.apple.AppleImageIoCodecNativeImpl
import com.nexustools.genericui.apple.style.AppleBackdropFilterController
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockWindowNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.event.KeyEvent
import com.nexustools.genericui.event.PointerDragManager
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.event.PopupMouseCaptureManager
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.AppleIconTileComposer
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageColorSpace
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.nativeimpl.shell.MenuBarNativeImpl
import com.nexustools.genericui.nativeimpl.shell.MenuNativeImpl
import com.nexustools.genericui.res.DrawableResource
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CValue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.autoreleasepool
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.AppKit.NSAppearance
import platform.AppKit.NSAppearanceNameAqua
import platform.AppKit.NSAppearanceNameDarkAqua
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSButton
import platform.AppKit.NSCursor
import platform.AppKit.NSCursorFrameResizeDirectionsAll
import platform.AppKit.NSCursorFrameResizePositionBottom
import platform.AppKit.NSCursorFrameResizePositionBottomLeft
import platform.AppKit.NSCursorFrameResizePositionBottomRight
import platform.AppKit.NSCursorFrameResizePositionLeft
import platform.AppKit.NSCursorFrameResizePositionRight
import platform.AppKit.NSCursorFrameResizePositionTop
import platform.AppKit.NSCursorFrameResizePositionTopLeft
import platform.AppKit.NSCursorFrameResizePositionTopRight
import platform.AppKit.NSEvent
import platform.AppKit.NSEventMaskAny
import platform.AppKit.NSEventModifierFlagCommand
import platform.AppKit.NSEventModifierFlagControl
import platform.AppKit.NSEventModifierFlagOption
import platform.AppKit.NSEventModifierFlagShift
import platform.AppKit.NSEventTypeApplicationDefined
import platform.AppKit.NSEventTypeCursorUpdate
import platform.AppKit.NSEventTypeLeftMouseDown
import platform.AppKit.NSEventTypeLeftMouseDragged
import platform.AppKit.NSEventTypeLeftMouseUp
import platform.AppKit.NSEventTypeMouseEntered
import platform.AppKit.NSEventTypeMouseExited
import platform.AppKit.NSEventTypeMouseMoved
import platform.AppKit.NSEventTypeRightMouseDown
import platform.AppKit.NSEventTypeRightMouseUp
import platform.AppKit.NSFloatingWindowLevel
import platform.AppKit.NSGraphicsContext
import platform.AppKit.NSImage
import platform.AppKit.NSModalPanelWindowLevel
import platform.AppKit.NSNormalWindowLevel
import platform.AppKit.NSPanel
import platform.AppKit.NSPopUpMenuWindowLevel
import platform.AppKit.NSScreen
import platform.AppKit.NSScrollView
import platform.AppKit.NSScroller
import platform.AppKit.NSScrollerStyleLegacy
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowAbove
import platform.AppKit.NSWindowCollectionBehaviorFullScreenAuxiliary
import platform.AppKit.NSWindowCollectionBehaviorFullScreenPrimary
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskFullScreen
import platform.AppKit.NSWindowStyleMaskFullSizeContentView
import platform.AppKit.NSWindowStyleMaskMiniaturizable
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.NSWindowStyleMaskTitled
import platform.AppKit.NSWindowStyleMaskUtilityWindow
import platform.AppKit.NSWindowTitleHidden
import platform.AppKit.NSWindowTitleVisible
import platform.AppKit.appearance
import platform.AppKit.currentEvent
import platform.AppKit.nextEventMatchingMask
import platform.AppKit.orderFrontStandardAboutPanel
import platform.AppKit.orderFrontStandardAboutPanelWithOptions
import platform.AppKit.postEvent
import platform.AppKit.sendAction
import platform.AppKit.sendEvent
import platform.AppKit.setAppearance
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextClearRect
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextSetAlpha
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSMakePoint
import platform.Foundation.NSPoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSMakeSize
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRect
import platform.Foundation.NSRunLoop
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSThread
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runMode
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_after
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync
import platform.darwin.dispatch_time
import kotlin.concurrent.atomics.AtomicInt
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.TimeSource

/**
 * Singleton AppKit window, event-loop, layout, hit-testing, and offscreen capture coordinator
 * for `:desktop:macos`.
 */
object MacosNativeWindowHost {

    internal var stageTopologyVersion: Long = 0L

    internal enum class UndecoratedResizeEdge(
        val left: Boolean,
        val right: Boolean,
        val top: Boolean,
        val bottom: Boolean
    ) {
        NONE(false, false, false, false),
        N(false, false, true, false),
        S(false, false, false, true),
        E(false, true, false, false),
        W(true, false, false, false),
        NE(false, true, true, false),
        NW(true, false, true, false),
        SE(false, true, false, true),
        SW(true, false, false, true)
    }

    internal const val UNDECORATED_RESIZE_EDGE_PT: Double = 8.0
    internal const val UNDECORATED_RESIZE_CORNER_PT: Double = 14.0
    internal const val UNDECORATED_RESIZE_CONTROL_EDGE_PT: Double = 2.0
    private const val CUSTOM_TITLEBAR_INSET_PX: Int = 28
    private const val CHROME_MODE_STANDARD: Int = 0
    private const val CHROME_MODE_SEAMLESS_STACKED_NAVBAR: Int = 1
    private const val CHROME_MODE_EXPLICIT_CUSTOM_TITLEBAR: Int = 2
    private const val CHROME_MODE_NATIVE_OVERLAY_TITLEBAR: Int = 3

    private val sessions = LinkedHashMap<Long, MacosWindowSession>(8)
    private var syntheticKeySeq: Long = 1000L
    @kotlin.concurrent.Volatile
    private var appInitialized: Boolean = false
    private var appFinishedLaunching: Boolean = false
    private var eventLoopRunning: Boolean = false
    private val hooksInstalled = AtomicInt(0)
    @kotlin.concurrent.Volatile
    private var mainMenuInstalled: Boolean = false
    @kotlin.concurrent.Volatile
    private var mainMenuDirty: Boolean = true
    internal var mainMenuInstallCountForTest: Int = 0
    private var syncingChromeSessionKey: Long = 0L
    private var activeKeyWindowId: Long? = null
    private var activeAppearanceName: String? = null
    private var activeApplicationMenuImpl: MenuNativeImpl<*>? = null
    private var activeApplicationTitle: String? = null
    private var activeWindowMenuBarImpl: MenuBarNativeImpl<*>? = null
    private var hostVBlankWatcherId: Int = -1
    private val frameReadyPending = AtomicInt(0)

    @kotlin.concurrent.Volatile
    private var redrawRequested: Boolean = true
    @kotlin.concurrent.Volatile
    private var stageRedrawRequested: Boolean = false
    private val redrawDispatchScheduled = AtomicInt(0)
    private val stageRedrawDispatchScheduled = AtomicInt(0)

    private val pendingOverlayRelayoutRoots = linkedSetOf<com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl<*>>()
    private val overlayRelayoutLock = AtomicInt(0)
    private val overlayRelayoutDispatchScheduled = AtomicInt(0)

    private inline fun <T> withOverlayRelayoutLock(block: () -> T): T {
        while (!overlayRelayoutLock.compareAndSet(0, 1)) {
            // brief spin strictly around pendingOverlayRelayoutRoots
        }
        return try {
            block()
        } finally {
            overlayRelayoutLock.store(0)
        }
    }

    private var totalPaintsCount: Long = 0L
    private var stageOnlyPaintsCount: Long = 0L
    private var fullWindowPaintsCount: Long = 0L
    private var redisplayOnlyRequested: Boolean = false
    private val redisplayDispatchScheduled = AtomicInt(0)
    private val paintDurationsUs = ArrayList<Long>(256)

    var visualTestMode: Boolean = false
    var visualTestBannerText: String? = null

    init {
        DesktopNativeWidget.onVBlankWidgetsChanged = { syncHostVBlankWatcher() }
        MacosSurfaceLayerSupport.onActiveBackdropViewsChanged = { syncHostVBlankWatcher() }
        DesktopNativeWidget.onSurfaceFrameReadyGlobal = { _ ->
            if (frameReadyPending.compareAndSet(0, 1)) {
                dispatch_async(dispatch_get_main_queue()) {
                    autoreleasepool {
                        frameReadyPending.store(0)
                        onSurfaceFrameReadyOnMain()
                    }
                }
            }
        }
    }

    private fun onSurfaceFrameReadyOnMain() {
        flushPendingOverlayRelayout()
        var anyStage = false
        for (session in sessions.values) {
            if (session.presentStageSurfaces(forceRefresh = false)) {
                recordStageOnlyPaint(250L)
                anyStage = true
            }
        }
        if (redrawRequested) {
            invalidateAllWindows(stageOnly = false)
        } else if (stageRedrawRequested && !anyStage) {
            invalidateAllWindows(stageOnly = true)
        }
    }

    val activeNsWindow: NSWindow?
        get() = activeSession()?.nsWindow

    private inline fun <T> runOnMainSync(crossinline block: () -> T): T {
        if (NSThread.isMainThread) return block()
        var result: Result<T>? = null
        dispatch_sync(dispatch_get_main_queue()) {
            result = runCatching { block() }
        }
        return result!!.getOrThrow()
    }

    private fun centerPanelOnActiveScreen(panel: NSWindow) {
        val screen = NSScreen.mainScreen ?: panel.screen ?: (NSScreen.screens.firstOrNull() as? NSScreen)
        if (screen != null) {
            val (fw, fh) = panel.frame.useContents { size.width to size.height }
            screen.visibleFrame.useContents {
                val cx = origin.x + (size.width - fw) * 0.5
                val cy = origin.y + (size.height - fh) * 0.5
                panel.setFrame(NSMakeRect(cx, cy, fw, fh), display = false)
            }
        } else {
            panel.center()
        }
    }

    // -------------------------------------------------------------------------
    // App Lifecycle & Event Loop
    // -------------------------------------------------------------------------

    fun prepareEventLoop() {
        installHooks()
        if (NSThread.isMainThread) {
            ensureAppInitialized()
            ensureMainMenuInstalled()
        } else if (!appInitialized || !mainMenuInstalled || mainMenuDirty) {
            MacosAppKitDispatch.onMainOrDefer {
                ensureAppInitialized()
                ensureMainMenuInstalled()
            }
        }
    }

    private fun ensureAppInitialized() {
        if (appInitialized) return
        val app = NSApplication.sharedApplication
        syncActivationPolicyForCurrentState()
        installApplicationIcon(app)
        appInitialized = true
    }

    private fun installHooks() {
        if (!hooksInstalled.compareAndSet(0, 1)) return

        DesktopNativeWidget.onNativeTreeInvalidatedWidget = { widget ->
            requestRedrawForWidget(widget)
        }
        DesktopNativeWidget.overlayInvalidationListener = { widget ->
            if (widget.isInsideIsolatedStageSubtree()) {
                requestStageRedraw()
            } else {
                requestRedrawForWidget(widget)
            }
        }
        DesktopNativeWidget.overlayLayoutInvalidationListener = { widget ->
            if (widget.isInsideIsolatedStageSubtree()) {
                requestOverlayRelayout(widget)
            } else {
                requestRedrawForWidget(widget)
            }
        }
        DesktopNativeWidget.onCursorUpdated = {
            MacosAppKitDispatch.onMainOrDefer {
                refreshActiveCursorsNow()
            }
        }
        PointerDragManager.addCursorLockListener { locked, cursor, _ ->
            MacosAppKitDispatch.onMainOrDefer {
                for (session in sessions.values.toList()) {
                    session.rootView.applyDragCursorLock(locked, cursor)
                }
                if (!locked) {
                    refreshActiveCursorsNow()
                }
            }
        }
        DesktopDialogWindowNativeImpl.onShowDialogHook = { dialogImpl, ownerWindow ->
            presentDialogWindow(dialogImpl, ownerWindow)
        }
        DesktopDialogWindowNativeImpl.onCloseDialogHook = { dialogImpl ->
            closeDialogWindow(dialogImpl)
        }
        DesktopDialogWindowNativeImpl.onTitleChangedHook = { dialogImpl, title ->
            MacosAppKitDispatch.onMainOrDefer {
                val key = dialogImpl.nativeWidget.nativePointer
                sessions[key]?.nsWindow?.setTitle(title)
            }
        }
        MacosDialogWindowNativeImpl.onBoundsChangedHook = { dialogImpl ->
            MacosAppKitDispatch.onMainOrDefer {
                val session = sessions[dialogImpl.nativeWidget.nativePointer] ?: return@onMainOrDefer
                if (!session.isClosing && !session.isSyncingSize) {
                    syncDialogProperties(session)
                    session.needsRelayout = true
                    session.needsRedisplay = true
                    scheduleSessionFlush()
                }
            }
        }
    }

    private fun orderedVisibleSessions(): List<MacosWindowSession> {
        val visible = sessions.values.filter { !it.isClosing && it.isShown && it.nsWindow.isVisible() }
        if (visible.size <= 1) return visible
        val keyWin = NSApplication.sharedApplication.keyWindow
        val active = activeSession()
        val ordered = ArrayList<MacosWindowSession>(visible.size)
        visible.firstOrNull { isSameNsWindow(it.nsWindow, keyWin) }?.let { ordered.add(it) }
        if (active != null && active in visible && active !in ordered) {
            ordered.add(active)
        }
        for (i in visible.indices.reversed()) {
            val s = visible[i]
            if (s !in ordered) {
                ordered.add(s)
            }
        }
        return ordered
    }

    private fun isClientPointInsideRootBounds(x: Double, y: Double, rootView: MacosWindowRootView): Boolean =
        x.isFinite() && y.isFinite() && rootView.bounds.useContents {
            size.width > 0.0 && size.height > 0.0 &&
                x >= origin.x && x <= origin.x + size.width &&
                y >= origin.y && y <= origin.y + size.height
        }

    internal fun refreshActiveCursorsNow() {
        val orderedSessions = orderedVisibleSessions()
        if (orderedSessions.isEmpty()) return
        if (!visualTestMode) {
            for (s in orderedSessions) {
                s.nsWindow.invalidateCursorRectsForView(s.rootView)
            }
        }
        if (visualTestMode) {
            for (s in orderedSessions) {
                val lx = s.rootView.lastKnownClientX
                val ly = s.rootView.lastKnownClientY
                if (isClientPointInsideRootBounds(lx, ly, s.rootView)) {
                    s.rootView.updateEffectiveCursorForClientPoint(lx, ly, forceCheckCurrent = true)
                    return
                }
            }
        }
        val mouseScreenPt = NSEvent.mouseLocation
        val topWinNumber = NSWindow.windowNumberAtPoint(mouseScreenPt, belowWindowWithWindowNumber = 0L)
        if (topWinNumber > 0L) {
            val topSession = orderedSessions.firstOrNull { it.nsWindow.windowNumber == topWinNumber } ?: return
            val winPt = topSession.nsWindow.convertPointFromScreen(mouseScreenPt)
            val (cx, cy) = topSession.rootView.convertPoint(winPt, fromView = null).useContents { x to y }
            if (isClientPointInsideRootBounds(cx, cy, topSession.rootView)) {
                topSession.rootView.updateEffectiveCursorForClientPoint(cx, cy, forceCheckCurrent = true)
            }
            return
        }
        for (s in orderedSessions) {
            val winPt = s.nsWindow.convertPointFromScreen(mouseScreenPt)
            val (cx, cy) = s.rootView.convertPoint(winPt, fromView = null).useContents { x to y }
            if (isClientPointInsideRootBounds(cx, cy, s.rootView)) {
                s.rootView.updateEffectiveCursorForClientPoint(cx, cy, forceCheckCurrent = true)
                return
            }
        }
        for (s in orderedSessions) {
            val lx = s.rootView.lastKnownClientX
            val ly = s.rootView.lastKnownClientY
            if (isClientPointInsideRootBounds(lx, ly, s.rootView)) {
                s.rootView.updateEffectiveCursorForClientPoint(lx, ly, forceCheckCurrent = true)
                return
            }
        }
    }

    fun runEventLoop() {
        prepareEventLoop()
        val app = NSApplication.sharedApplication
        eventLoopRunning = true
        if (!appFinishedLaunching) {
            appFinishedLaunching = true
            app.finishLaunching()
        }
        for (session in sessions.values.toList()) {
            flushSessionDisplayNow(session, stageOnly = false)
        }
        if (visualTestMode) {
            pumpVisualWindowEvents(durationMs = 32L)
            eventLoopRunning = false
            return
        }
        app.run()
        eventLoopRunning = false
    }

    fun terminateEventLoop() = MacosAppKitDispatch.onMainOrDefer {
        eventLoopRunning = false
        (com.nexustools.genericui.animation.Animator.activeScheduler as? MacosAnimationNativeImpl)
            ?.setHostSurfaceDemand(active = false, preferredRateHz = null)
        if (hostVBlankWatcherId > 0) {
            com.nexustools.genericui.animation.Animator.unwatchVBlank(hostVBlankWatcherId)
            hostVBlankWatcherId = -1
        }
        val app = NSApplication.sharedApplication
        app.stop(null)
        val dummyEvent = NSEvent.otherEventWithType(
            type = NSEventTypeApplicationDefined,
            location = NSMakePoint(0.0, 0.0),
            modifierFlags = 0uL,
            timestamp = 0.0,
            windowNumber = 0L,
            context = null,
            subtype = 0,
            data1 = 0L,
            data2 = 0L
        )
        if (dummyEvent != null) {
            app.postEvent(dummyEvent, atStart = true)
        }
    }

    fun requestApplicationQuit() = MacosAppKitDispatch.onMainOrDefer {
        val mainWin = sessions.values.firstOrNull { !it.isDialog && it.windowImpl?.isToolModalWindow != true }?.windowImpl
        if (mainWin != null) {
            mainWin.closeWindow()
        } else {
            terminateEventLoop()
        }
    }

    // -------------------------------------------------------------------------
    // Dock Icon & Screen Helpers
    // -------------------------------------------------------------------------

    internal fun decodeDrawableToBuffer(drawable: DrawableResource): ImageBuffer? =
        MacosAppKitAppearance.decodeDrawableToBuffer(drawable)

    @OptIn(ExperimentalEncodingApi::class)
    internal fun isAlreadyWrappedAppleSquircleIcon(buffer: ImageBuffer): Boolean {
        val rgba = buffer.asRgba8888()
        val w = rgba.width
        val h = rgba.height
        if (w < 32 || h < 32 || rgba.bytes.size < w * h * 4) return false

        val cornerX = (w * 0.02).toInt().coerceIn(0, w - 1)
        val cornerY = (h * 0.02).toInt().coerceIn(0, h - 1)
        val cornerAlpha = rgba.bytes[(cornerY * w + cornerX) * 4 + 3].toInt() and 0xFF
        if (cornerAlpha > 16) return false

        val diagX = (w * 0.11).toInt().coerceIn(0, w - 1)
        val diagY = (h * 0.11).toInt().coerceIn(0, h - 1)
        val diagAlpha = rgba.bytes[(diagY * w + diagX) * 4 + 3].toInt() and 0xFF
        if (diagAlpha > 32) return false

        val tileBorderX = (w * 0.12).toInt().coerceIn(0, w - 1)
        val tileBorderY = (h / 2).coerceIn(0, h - 1)
        val borderAlpha = rgba.bytes[(tileBorderY * w + tileBorderX) * 4 + 3].toInt() and 0xFF
        return borderAlpha >= 180
    }

    internal fun composeRuntimeDockIconBuffer(source: ImageBuffer): ImageBuffer {
        val rgbaBuffer = source.asRgba8888()
        if (GenericUI.appleIconExplicitlyProvided || isAlreadyWrappedAppleSquircleIcon(rgbaBuffer)) {
            return rgbaBuffer
        }
        val targetSize = maxOf(rgbaBuffer.width, rgbaBuffer.height, 512)
        val composedBytes = AppleIconTileComposer.composeRgba(
            sourceRgba = rgbaBuffer.bytes,
            sourceWidth = rgbaBuffer.width,
            sourceHeight = rgbaBuffer.height,
            targetSize = targetSize
        )
        return ImageBuffer(
            width = targetSize,
            height = targetSize,
            pixelFormat = PixelFormat.RGBA_8888,
            colorSpace = ImageColorSpace.SRGB,
            bytes = composedBytes
        )
    }

    private fun installApplicationIcon(app: NSApplication) {
        val icnsPath = NSBundle.mainBundle.pathForResource("icon", ofType = "icns")
        if (icnsPath != null) {
            val isAppBundle = NSBundle.mainBundle.bundlePath.endsWith(".app", ignoreCase = true)
            if (isAppBundle) {
                NSImage(contentsOfFile = icnsPath)?.let { app.setApplicationIconImage(it) }
                return
            }
            val data = NSData.dataWithContentsOfFile(icnsPath)
            val ptr = data?.bytes
            if (data != null && ptr != null && data.length.toInt() > 0) {
                val raw = ByteArray(data.length.toInt())
                raw.usePinned { pinned ->
                    platform.posix.memcpy(pinned.addressOf(0), ptr, data.length)
                }
                val codec = ImageLoader.activeNativeCodec ?: AppleImageIoCodecNativeImpl().also {
                    ImageLoader.activeNativeCodec = it
                }
                val decoded = codec.decode(ImageSource.fromBytes(raw), ImageDecodeOptions())
                if (decoded != null) {
                    val wrapped = composeRuntimeDockIconBuffer(decoded)
                    createNsImageFromBuffer(wrapped)?.let { app.setApplicationIconImage(it) }
                    return
                }
            }
        }
        val drawable = Resources.getDrawable("icon") ?: Resources.getDrawable("app_icon") ?: return
        val buf = decodeDrawableToBuffer(drawable) ?: return
        val finalBuffer = composeRuntimeDockIconBuffer(buf)
        createNsImageFromBuffer(finalBuffer)?.let { app.setApplicationIconImage(it) }
    }

    private fun createNsImageFromBuffer(buffer: ImageBuffer): NSImage? {
        val cg = MacosAppKitAppearance.createCgImageFromBuffer(buffer) ?: return null
        return try {
            NSImage(cGImage = cg, size = NSMakeSize(buffer.width.toDouble(), buffer.height.toDouble()))
        } finally {
            platform.CoreGraphics.CGImageRelease(cg)
        }
    }

    private fun primaryScreenMaxY(): Double {
        val primary = (NSScreen.screens.firstOrNull() as? NSScreen) ?: NSScreen.mainScreen
        return primary?.frame?.useContents { origin.y + size.height } ?: 1080.0
    }

    internal fun cocoaFrameToDesktopTopLeft(frame: CValue<NSRect>): Pair<Int, Int> {
        val maxY = primaryScreenMaxY()
        return frame.useContents {
            origin.x.roundToInt() to (maxY - (origin.y + size.height)).roundToInt()
        }
    }

    internal fun cocoaFrameToDesktopTopLeftRect(frame: CValue<NSRect>): Rect {
        val maxY = primaryScreenMaxY()
        return frame.useContents {
            Rect(
                x = origin.x.roundToInt(),
                y = (maxY - (origin.y + size.height)).roundToInt(),
                width = size.width.roundToInt(),
                height = size.height.roundToInt()
            )
        }
    }

    internal fun desktopTopLeftToCocoaTopLeft(x: Number, y: Number): CValue<platform.Foundation.NSPoint> =
        NSMakePoint(x.toDouble(), primaryScreenMaxY() - y.toDouble())

    internal fun desktopTopLeftRectToCocoaRect(x: Number, y: Number, width: Double, height: Double): CValue<NSRect> =
        NSMakeRect(x.toDouble(), primaryScreenMaxY() - y.toDouble() - height, width, height)

    fun getPrimaryMonitorBounds(): Rect? = runOnMainSync {
        val screen = (NSScreen.screens.firstOrNull() as? NSScreen) ?: NSScreen.mainScreen ?: return@runOnMainSync null
        cocoaFrameToDesktopTopLeftRect(screen.visibleFrame)
    }

    fun isWindowRectOnScreen(rect: Rect): Boolean = runOnMainSync {
        val screens = NSScreen.screens
        for (obj in screens) {
            val screen = obj as? NSScreen ?: continue
            val visible = cocoaFrameToDesktopTopLeftRect(screen.visibleFrame)
            val overlapX = rect.x < visible.right && rect.right > visible.x
            val overlapY = rect.y < visible.bottom && rect.bottom > visible.y
            if (overlapX && overlapY) return@runOnMainSync true
        }
        false
    }

    // -------------------------------------------------------------------------
    // Theme & Appearance
    // -------------------------------------------------------------------------

    fun syncThemeAppearance(mode: ThemeMode) = MacosAppKitDispatch.onMainOrDefer {
        val effective = if (mode == ThemeMode.AUTO) {
            MacosApplicationNativeImpl.detectMacosSystemTheme()
        } else {
            mode
        }
        val name = if (effective == ThemeMode.DARK) NSAppearanceNameDarkAqua else NSAppearanceNameAqua
        activeAppearanceName = name
        val appearance = NSAppearance.appearanceNamed(name)
        val winBg = MacosAppKitAppearance.toNsColor(
            if (effective == ThemeMode.DARK) com.nexustools.genericui.style.color.RgbaColor(24, 26, 31, 255)
            else com.nexustools.genericui.style.color.RgbaColor(244, 245, 248, 255)
        )
        NSApplication.sharedApplication.setAppearance(appearance)
        fun notifyTreeTheme(impl: com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl<*>) {
            impl.notifyHostThemeChanged()
            if (impl is com.nexustools.genericui.desktop.lazy.DesktopLazyListNativeImpl<*>) {
                for (itemImpl in impl.materializedItemsSnapshot().values) {
                    if (itemImpl is com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl<*>) {
                        notifyTreeTheme(itemImpl)
                    }
                }
            } else if (impl is com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl<*>) {
                for (child in impl.getLogicalChildren()) {
                    if (child is com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl<*>) {
                        notifyTreeTheme(child)
                    }
                }
            }
        }
        for (session in sessions.values) {
            session.nsWindow.setAppearance(appearance)
            session.nsWindow.setBackgroundColor(winBg)
            session.rootView.setWantsLayer(true)
            session.rootView.layer?.backgroundColor = winBg.CGColor
            val host = session.hostContainer()
            host?.nativeWidget?.attributes?.put("darkTheme", effective == ThemeMode.DARK)
            if (host != null) {
                notifyTreeTheme(host)
            }
            session.needsRelayout = true
            session.needsRedisplay = true
        }
        requestRedraw()
    }

    // -------------------------------------------------------------------------
    // Window Presentation & Sync
    // -------------------------------------------------------------------------

    fun presentWindow(windowImpl: MacosWindowNativeImpl) = runOnMainSync {
        prepareEventLoop()
        val key = windowImpl.nativeWidget.nativePointer
        val existing = sessions[key]
        if (existing != null) {
            existing.windowImpl = windowImpl
            syncWindowProperties(windowImpl)
            existing.layoutWindowTree(force = true)
            existing.nsWindow.makeKeyAndOrderFront(null)
            existing.isShown = true
            (existing.nsWindow as? MacosHostWindow)?.syncSquareWindowCorners(
                enabled = windowImpl.isUndecorated() && !existing.activeFullscreen && !windowImpl.isToolModalWindow,
                forceRefresh = true
            )
            scheduleDeferredSquareCornerRefresh(existing)
            activeKeyWindowId = key
            syncActivationPolicyForCurrentState()
            return@runOnMainSync
        }

        val size = windowImpl.getWindowSize()
        val pos = windowImpl.getWindowPosition()
        val initW = size.width.toDouble().coerceAtLeast(160.0)
        val initH = size.height.toDouble().coerceAtLeast(120.0)
        val contentRect = desktopTopLeftRectToCocoaRect(pos.x, pos.y, initW, initH)
        val chromeMode = resolveChromeMode(windowImpl)
        val isNativeOverlayBar = chromeMode == CHROME_MODE_NATIVE_OVERLAY_TITLEBAR
        val isUndecorated = windowImpl.isUndecorated()
        val styleMask = computeStyleMask(windowImpl, chromeMode, isUndecorated)

        val nsWindow = MacosHostWindow(
            contentRect = contentRect,
            styleMask = styleMask,
            backing = NSBackingStoreBuffered,
            defer = false,
            isToolModal = windowImpl.isToolModalWindow,
            isUndecoratedHostWindow = isUndecorated && !isNativeOverlayBar
        )
        nsWindow.isNativeOverlayTitleBarHostWindow = isNativeOverlayBar
        nsWindow.styleMask = styleMask
        nsWindow.collectionBehavior = NSWindowCollectionBehaviorFullScreenPrimary
        nsWindow.setAcceptsMouseMovedEvents(true)
        nsWindow.setReleasedWhenClosed(false)

        val rootView = MacosWindowRootView(
            windowKey = key,
            frameRect = NSMakeRect(0.0, 0.0, initW, initH)
        )
        nsWindow.setContentView(rootView)

        val delegate = MacosWindowDelegate(windowKey = key, isDialog = false)
        nsWindow.setDelegate(delegate)

        val session = MacosWindowSession(
            windowKey = key,
            nsWindow = nsWindow,
            rootView = rootView,
            delegate = delegate,
            isDialog = false,
            windowImpl = windowImpl
        )
        nsWindow.onMaximizedStateChanged = { maximizedNow ->
            val activeSession = sessions[key]
            val activeWin = activeSession?.windowImpl
            if (activeSession != null && activeWin != null && !activeSession.isClosing) {
                val (cw, ch) = activeSession.currentClientSize()
                activeWin.notifyHostResizedFromOs(
                    widthPx = cw,
                    heightPx = ch,
                    fullscreenNow = activeSession.activeFullscreen,
                    maximizedNow = maximizedNow
                )
                refreshDeferredBackdropUnderlays(activeSession.rootView)
                activeSession.needsRelayout = true
                activeSession.layoutWindowTree(force = true)
            }
        }
        wireRootViewCallbacks(session)
        sessions[key] = session
        (windowImpl.nativeWidget as? MacosViewHandle)?.let { handle ->
            handle.nsWindow = nsWindow
            handle.nsView = rootView
            handle.hostInvalidateHook = { stageOnly ->
                if (stageOnly) {
                    session.needsStageOnlyRedisplay = true
                    requestStageRedraw()
                } else {
                    session.needsRelayout = true
                    session.needsRedisplay = true
                    scheduleSessionFlush()
                }
            }
            handle.hostRelayoutHook = {
                session.needsRelayout = true
                scheduleSessionFlush()
            }
        }

        syncWindowProperties(windowImpl)
        if (windowImpl.hasExplicitWindowPosition()) {
            nsWindow.setFrameTopLeftPoint(desktopTopLeftToCocoaTopLeft(pos.x, pos.y))
        } else {
            nsWindow.center()
        }
        session.layoutWindowTree(force = true)
        session.presentStageSurfaces(forceRefresh = true)

        if (windowImpl.nativeWidget.visible) {
            if (windowImpl.isToolModalWindow) {
                nsWindow.setLevel(NSModalPanelWindowLevel)
            }
            nsWindow.makeKeyAndOrderFront(null)
            session.isShown = true
            nsWindow.syncSquareWindowCorners(
                enabled = windowImpl.isUndecorated() && !session.activeFullscreen && !windowImpl.isToolModalWindow,
                forceRefresh = true
            )
            scheduleDeferredSquareCornerRefresh(session)
            val (initX, initY) = cocoaFrameToDesktopTopLeft(nsWindow.frame)
            windowImpl.notifyHostMovedFromOs(initX, initY)
            if (windowImpl.isFullscreen()) {
                syncFullscreen(windowImpl, true)
            }
        }
        activeKeyWindowId = key
        syncMenuBar(windowImpl, windowImpl.getAttachedMenuBarImpl())
        syncActivationPolicyForCurrentState()
        syncHostVBlankWatcher()
    }

    private fun presentDialogWindow(
        dialogImpl: DesktopDialogWindowNativeImpl<*>,
        ownerWindowImpl: DesktopWindowNativeImpl<*>?
    ) = runOnMainSync {
        if (!dialogImpl.isVisible() || dialogImpl.isDisposed) return@runOnMainSync
        prepareEventLoop()
        val key = dialogImpl.nativeWidget.nativePointer
        val ownerKey = (ownerWindowImpl as? DesktopWindowNativeImpl<*>)?.nativeWidget?.nativePointer
        val existing = sessions[key]
        if (existing != null) {
            existing.dialogImpl = dialogImpl
            existing.ownerWindowKey = ownerKey
            existing.titleBarInsetTopPx = 0
            existing.nsWindow.collectionBehavior = NSWindowCollectionBehaviorFullScreenAuxiliary
            syncDialogProperties(existing)
            val ownerWin = ownerKey?.let { sessions[it]?.nsWindow }
            (existing.nsWindow as? MacosDialogPanel)?.attachToOwner(ownerWin)
            if (ownerKey == null) {
                centerPanelOnActiveScreen(existing.nsWindow)
            }
            existing.layoutWindowTree(force = true)
            existing.nsWindow.makeKeyAndOrderFront(null)
            if (ownerKey == null) {
                centerPanelOnActiveScreen(existing.nsWindow)
            }
            existing.isShown = true
            activeKeyWindowId = key
            syncActivationPolicyForCurrentState()
            return@runOnMainSync
        }

        val dlgW = dialogImpl.boundsWidth.toDouble().coerceAtLeast(240.0)
        val dlgH = dialogImpl.boundsHeight.toDouble().coerceAtLeast(160.0)
        val mask = NSWindowStyleMaskTitled or NSWindowStyleMaskClosable or NSWindowStyleMaskResizable
        val panel = MacosDialogPanel(
            contentRect = NSMakeRect(0.0, 0.0, dlgW, dlgH),
            styleMask = mask,
            backing = NSBackingStoreBuffered,
            defer = false
        )
        panel.setLevel(NSModalPanelWindowLevel)
        panel.collectionBehavior = NSWindowCollectionBehaviorFullScreenAuxiliary
        panel.setHidesOnDeactivate(false)
        panel.setCanHide(false)
        panel.setAcceptsMouseMovedEvents(true)
        panel.setReleasedWhenClosed(false)
        panel.setTitle(dialogImpl.getTitle())

        val rootView = MacosWindowRootView(
            windowKey = key,
            frameRect = NSMakeRect(0.0, 0.0, dlgW, dlgH)
        )
        panel.setContentView(rootView)

        val delegate = MacosWindowDelegate(windowKey = key, isDialog = true)
        panel.setDelegate(delegate)

        val session = MacosWindowSession(
            windowKey = key,
            nsWindow = panel,
            rootView = rootView,
            delegate = delegate,
            isDialog = true,
            dialogImpl = dialogImpl,
            ownerWindowKey = ownerKey
        )
        session.titleBarInsetTopPx = 0
        wireRootViewCallbacks(session)
        sessions[key] = session
        (dialogImpl.nativeWidget as? MacosViewHandle)?.let { handle ->
            handle.nsWindow = panel
            handle.nsView = rootView
            handle.hostInvalidateHook = { _ ->
                session.needsRelayout = true
                session.needsRedisplay = true
                scheduleSessionFlush()
            }
            handle.hostRelayoutHook = {
                session.needsRelayout = true
                scheduleSessionFlush()
            }
        }

        syncDialogProperties(session)
        val ownerNsWin = ownerKey?.let { sessions[it]?.nsWindow }
        if (ownerNsWin != null) {
            val (pw, ph) = session.nsWindow.frame.useContents { size.width to size.height }
            val visibleFrame = (ownerNsWin.screen ?: NSScreen.mainScreen)?.visibleFrame
            var cx = 0.0
            var cy = 0.0
            ownerNsWin.frame.useContents {
                cx = origin.x + (size.width - pw) * 0.5
                cy = origin.y + (size.height - ph) * 0.5
            }
            if (visibleFrame != null) {
                visibleFrame.useContents {
                    val maxX = (origin.x + size.width - pw).coerceAtLeast(origin.x)
                    val maxY = (origin.y + size.height - ph).coerceAtLeast(origin.y)
                    cx = cx.coerceIn(origin.x, maxX)
                    cy = cy.coerceIn(origin.y, maxY)
                }
            }
            panel.setFrame(NSMakeRect(cx, cy, pw, ph), display = false)
            panel.attachToOwner(ownerNsWin)
        } else {
            centerPanelOnActiveScreen(panel)
        }

        session.layoutWindowTree(force = true)
        panel.makeKeyAndOrderFront(null)
        if (ownerNsWin == null) {
            centerPanelOnActiveScreen(panel)
        }
        session.isShown = true
        activeKeyWindowId = key
        syncActivationPolicyForCurrentState()
    }

    private fun syncDialogProperties(session: MacosWindowSession) {
        val dlg = session.dialogImpl ?: return
        session.nsWindow.setTitle(dlg.getTitle())
        val minW = when (val raw = dlg.nativeWidget.attributes["minWidthPx"]) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }.coerceAtLeast(0.0)
        val minH = when (val raw = dlg.nativeWidget.attributes["minHeightPx"]) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull() ?: 0.0
            else -> 0.0
        }.coerceAtLeast(0.0)
        if (minW > 0.0 || minH > 0.0) {
            session.nsWindow.setContentMinSize(NSMakeSize(minW, minH))
        }
        val dlgW = dlg.boundsWidth.toDouble().coerceAtLeast(240.0)
        val dlgH = dlg.boundsHeight.toDouble().coerceAtLeast(160.0)
        val curSize = session.currentClientSize()
        if (curSize.first != dlgW.roundToInt() || curSize.second != dlgH.roundToInt()) {
            session.isSyncingSize = true
            try {
                session.nsWindow.setContentSize(NSMakeSize(dlgW, dlgH))
            } finally {
                session.isSyncingSize = false
            }
        }
        val isDark = MacosAppKitAppearance.isDarkTheme(dlg.nativeWidget)
        val dlgBg = MacosAppKitAppearance.toNsColor(
            if (isDark) {
                com.nexustools.genericui.style.color.RgbaColor(24, 26, 31, 255)
            } else {
                com.nexustools.genericui.style.color.RgbaColor(244, 245, 248, 255)
            }
        )
        session.nsWindow.setBackgroundColor(dlgBg)
        session.rootView.setWantsLayer(true)
        session.rootView.layer?.backgroundColor = dlgBg.CGColor
        val appearanceName = activeAppearanceName ?: if (isDark) NSAppearanceNameDarkAqua else NSAppearanceNameAqua
        session.nsWindow.setAppearance(NSAppearance.appearanceNamed(appearanceName))
    }

    internal fun closeDialogWindow(dialogImpl: DesktopDialogWindowNativeImpl<*>) = runOnMainSync {
        val key = dialogImpl.nativeWidget.nativePointer
        val session = sessions.remove(key)
        if (session != null) {
            session.isClosing = true
            session.isShown = false
            (session.nsWindow as? MacosDialogPanel)?.detachForClose()
            val ownerNsWin = session.nsWindow.parentWindow
                ?: session.ownerWindowKey?.let { sessions[it]?.nsWindow }
            ownerNsWin?.removeChildWindow(session.nsWindow)
            clearSessionRootViewCallbacks(session)
            session.nsWindow.setDelegate(null)
            session.nsWindow.orderOut(null)
            session.nsWindow.close()
        }
        (dialogImpl.nativeWidget as? MacosViewHandle)?.let { handle ->
            handle.nsWindow = null
            handle.nsView = null
            handle.hostInvalidateHook = null
            handle.hostRelayoutHook = null
        }
        if (activeKeyWindowId == key) {
            activeKeyWindowId = sessions.keys.firstOrNull()
        }
        syncActivationPolicyForCurrentState()
        if (!hasAnyRemainingVisiblePresentationWindow() && eventLoopRunning) {
            terminateEventLoop()
        }
    }

    // -------------------------------------------------------------------------
    // Floating Dock Window Panels (Multi-Window Mode)
    // -------------------------------------------------------------------------

    internal fun presentDockWindow(dockImpl: DesktopDockWindowNativeImpl<*>) = runOnMainSync {
        prepareEventLoop()
        val key = dockImpl.nativeWidget.nativePointer
        val existing = sessions[key]
        if (existing != null) {
            existing.dockWindowImpl = dockImpl
            syncDockWindowProperties(dockImpl)
            existing.layoutWindowTree(force = true)
            existing.nsWindow.orderFront(null)
            existing.isShown = true
            return@runOnMainSync
        }

        val b = dockImpl.nativeWidget.bounds
        val w = b.width.coerceAtLeast(220).toDouble()
        val h = b.height.coerceAtLeast(180).toDouble()
        val mask = NSWindowStyleMaskTitled or
            NSWindowStyleMaskClosable or
            NSWindowStyleMaskResizable or
            NSWindowStyleMaskUtilityWindow
        val panel = NSPanel(
            contentRect = NSMakeRect(b.x.toDouble(), b.y.toDouble(), w, h),
            styleMask = mask,
            backing = NSBackingStoreBuffered,
            defer = false
        )
        panel.setFloatingPanel(true)
        panel.setLevel(NSFloatingWindowLevel)
        panel.setReleasedWhenClosed(false)
        panel.setAcceptsMouseMovedEvents(true)
        panel.setTitle(dockImpl.nativeWidget.attributes["title"] as? String ?: "")

        val rootView = MacosWindowRootView(windowKey = key, frameRect = NSMakeRect(0.0, 0.0, w, h))
        panel.setContentView(rootView)
        val delegate = MacosWindowDelegate(windowKey = key, isDialog = false)
        panel.setDelegate(delegate)

        val ownerKey = (dockImpl.ownerWindow as? DesktopWindowNativeImpl<*>)?.nativeWidget?.nativePointer
        val session = MacosWindowSession(
            windowKey = key,
            nsWindow = panel,
            rootView = rootView,
            delegate = delegate,
            isDialog = false,
            dockWindowImpl = dockImpl,
            ownerWindowKey = ownerKey
        )
        wireRootViewCallbacks(session)
        sessions[key] = session
        (dockImpl.nativeWidget as? MacosViewHandle)?.let { handle ->
            handle.nsWindow = panel
            handle.nsView = rootView
        }
        ownerKey?.let { sessions[it]?.nsWindow?.addChildWindow(panel, ordered = NSWindowAbove) }
        session.layoutWindowTree(force = true)
        panel.orderFront(null)
        session.isShown = true
    }

    internal fun syncDockWindowProperties(dockImpl: DesktopDockWindowNativeImpl<*>) = MacosAppKitDispatch.onMainOrDefer {
        val session = sessions[dockImpl.nativeWidget.nativePointer] ?: return@onMainOrDefer
        val title = dockImpl.nativeWidget.attributes["title"] as? String
        if (title != null) {
            session.nsWindow.setTitle(title)
        }
        val b = dockImpl.nativeWidget.bounds
        if (b.width > 0 && b.height > 0) {
            session.nsWindow.setContentSize(NSMakeSize(b.width.toDouble(), b.height.toDouble()))
            session.needsRelayout = true
        }
    }

    internal fun closeDockWindow(dockImpl: DesktopDockWindowNativeImpl<*>) = runOnMainSync {
        val key = dockImpl.nativeWidget.nativePointer
        val session = sessions.remove(key) ?: return@runOnMainSync
        session.isClosing = true
        val ownerNsWin = session.ownerWindowKey?.let { sessions[it]?.nsWindow }
        ownerNsWin?.removeChildWindow(session.nsWindow)
        session.nsWindow.setDelegate(null)
        session.nsWindow.orderOut(null)
        session.nsWindow.close()
        session.isShown = false
    }

    private fun wireRootViewCallbacks(session: MacosWindowSession) {
        val rv = session.rootView
        rv.onNeedsRelayout = {
            if (!session.isClosing && !session.isSyncingSize) {
                val (cw, ch) = session.currentClientSize()
                if (cw != session.lastLaidOutWidth ||
                    ch != session.lastLaidOutHeight ||
                    DesktopNativeWidget.globalLayoutMutationVersion != session.lastMutationVersion
                ) {
                    session.needsRelayout = true
                    session.layoutWindowTree(force = false)
                }
            }
        }
        rv.onUndecoratedEdgeMouseDown = { event -> handleUndecoratedEdgeMouseDown(session, event) }
        rv.onUndecoratedEdgeMouseDragged = { event -> handleUndecoratedEdgeMouseDragged(session, event) }
        rv.onUndecoratedEdgeMouseUp = { _ -> handleUndecoratedEdgeMouseUp(session) }
        rv.onWindowMouseMoved = { _, clientY ->
            val winImpl = session.windowImpl
            if (winImpl != null &&
                !session.activeFullscreen &&
                !winImpl.isFullscreen() &&
                !winImpl.isToolModalWindow &&
                (session.nsWindow as? MacosHostWindow)?.isTransitioningFullscreen != true
            ) {
                val barHandle = findNativeOverlayTitleBarHandle(winImpl, requireActive = false)
                if (barHandle != null) {
                    syncNativeOverlayTitleBarHoverPin(
                        session = session,
                        barHandle = barHandle,
                        barVisible = session.lastSyncedNativeTitleBarVisible ?: true,
                        pointerClientY = clientY
                    )
                }
            }
        }
        rv.onWindowMouseExited = {
            val winImpl = session.windowImpl
            if (winImpl != null && !session.activeFullscreen && !winImpl.isFullscreen() && !winImpl.isToolModalWindow) {
                val barHandle = findNativeOverlayTitleBarHandle(winImpl, requireActive = false)
                if (barHandle != null) {
                    val movedIntoNativeTitleBar = if (!visualTestMode &&
                        session.lastSyncedNativeTitleBarVisible == true
                    ) {
                        val barH = (barHandle.bounds.height.takeIf { it > 0 } ?: CUSTOM_TITLEBAR_INSET_PX).toDouble()
                        val mousePt = NSEvent.mouseLocation
                        val topWinNumber = NSWindow.windowNumberAtPoint(mousePt, belowWindowWithWindowNumber = 0L)
                        if (topWinNumber == session.nsWindow.windowNumber) {
                            val (mx, my) = mousePt.useContents { x to y }
                            session.nsWindow.frame.useContents {
                                val topY = origin.y + size.height
                                mx in origin.x..(origin.x + size.width) && my in (topY - barH)..topY
                            }
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                    if (movedIntoNativeTitleBar) {
                        syncNativeOverlayTitleBarHoverPin(
                            session = session,
                            barHandle = barHandle,
                            barVisible = true,
                            pointerClientY = 0.0
                        )
                    } else {
                        syncNativeOverlayTitleBarHoverPin(
                            session = session,
                            barHandle = barHandle,
                            barVisible = false,
                            pointerClientY = -1.0
                        )
                    }
                }
            }
        }
        rv.onWindowKeyDown = { event -> dispatchWindowKeyEvent(session, event, isDown = true) }
        rv.onWindowKeyUp = { event -> dispatchWindowKeyEvent(session, event, isDown = false) }
    }

    private fun findTopStackedNavbarImpl(windowImpl: MacosWindowNativeImpl): com.nexustools.genericui.macos.impl.MacosNavbarNativeImpl? {
        if (windowImpl.getFullscreenElement() != null || windowImpl.isUndecorated()) return null
        if (windowImpl.getTitleBarInstructions() != null) return null
        val attached = windowImpl.nativeWidget.attributes["navBar"] as? com.nexustools.genericui.macos.impl.MacosNavbarNativeImpl
        if (attached != null && attached.nativeWidget.visible && !attached.isDisposed) {
            return attached
        }
        fun searchTop(
            container: com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl<*>,
            depth: Int
        ): com.nexustools.genericui.macos.impl.MacosNavbarNativeImpl? {
            if (depth > 8) return null
            for (child in container.getLogicalChildren()) {
                if (child !is com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl<*> || !child.nativeWidget.visible) continue
                if (child.nativeWidget.bounds.y > 4) continue
                if (child is com.nexustools.genericui.macos.impl.MacosNavbarNativeImpl) return child
                if (child is com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl<*>) {
                    val found = searchTop(child, depth + 1)
                    if (found != null) return found
                }
            }
            return null
        }
        return searchTop(windowImpl, 0)
    }

    internal fun findNativeOverlayTitleBarHandle(
        windowImpl: MacosWindowNativeImpl,
        requireActive: Boolean = false
    ): MacosViewHandle? {
        if (windowImpl.isToolModalWindow) return null
        val session = sessions[windowImpl.nativeWidget.nativePointer]
        val treeVer = windowImpl.nativeWidget.subtreeMutationVersion
        if (session != null &&
            session.cachedOverlayTitleBarTreeVer == treeVer &&
            session.cachedOverlayTitleBarHostImpl === windowImpl
        ) {
            val cached = session.cachedOverlayTitleBarHandle ?: return null
            if (!cached.disposed) {
                val enabledByAttr = cached.attributes["data-titlebar-enabled"]
                    ?.toString()
                    ?.equals("false", ignoreCase = true) != true
                if (enabledByAttr) {
                    val disabledByActiveAttr = cached.attributes["data-titlebar-active"]
                        ?.toString()
                        ?.equals("false", ignoreCase = true) == true
                    return if (!requireActive || !disabledByActiveAttr) cached else null
                }
            }
        }
        fun search(
            container: DesktopBaseContainerNativeImpl<*>,
            depth: Int
        ): MacosViewHandle? {
            if (depth > 10) return null
            for (child in container.getLogicalChildren()) {
                if (child !is DesktopBaseNativeImpl<*> || child.isDisposed) continue
                val w = child.nativeWidget
                val role = (w.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] as? String)?.trim()?.lowercase()
                val nativeControls = w.attributes["data-native-titlebar-controls"]
                    ?.toString()
                    ?.equals("true", ignoreCase = true) == true
                val enabledByAttr = w.attributes["data-titlebar-enabled"]
                    ?.toString()
                    ?.equals("false", ignoreCase = true) != true
                if (role == DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR &&
                    nativeControls &&
                    enabledByAttr
                ) {
                    return w as? MacosViewHandle
                }
                if (child is DesktopBaseContainerNativeImpl<*>) {
                    val found = search(child, depth + 1)
                    if (found != null) return found
                }
            }
            return null
        }
        val found = search(windowImpl, 0)
        if (session != null && windowImpl.nativeWidget.subtreeMutationVersion == treeVer) {
            session.cachedOverlayTitleBarHandle = found
            session.cachedOverlayTitleBarTreeVer = treeVer
            session.cachedOverlayTitleBarHostImpl = windowImpl
        }
        if (found == null) return null
        if (requireActive) {
            val disabledByActiveAttr = found.attributes["data-titlebar-active"]
                ?.toString()
                ?.equals("false", ignoreCase = true) == true
            if (disabledByActiveAttr) return null
        }
        return found
    }

    private fun clearNativeOverlayTitleBarHoverPin(session: MacosWindowSession) {
        val winImpl = session.windowImpl ?: return
        val wasSessionPinned = session.nativeTitleBarHoverPinned
        session.nativeTitleBarHoverPinned = false
        findNativeOverlayTitleBarHandle(winImpl, requireActive = false)?.let { barHandle ->
            val barReportsPinned = barHandle.attributes["data-native-titlebar-hover-pinned"]?.toString() == "true"
            if (wasSessionPinned || barReportsPinned) {
                val barImpl = barHandle.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
                if (barImpl != null && !barImpl.isDisposed) {
                    barImpl.dispatchPointerLeave(PointerEvent())
                }
            }
        }
    }

    private fun resolveChromeMode(windowImpl: MacosWindowNativeImpl): Int {
        if (!windowImpl.isFullscreen() && findNativeOverlayTitleBarHandle(windowImpl, requireActive = false) != null) {
            return CHROME_MODE_NATIVE_OVERLAY_TITLEBAR
        }
        val explicitTitleBar = windowImpl.getTitleBarInstructions()
        if (explicitTitleBar != null) return CHROME_MODE_EXPLICIT_CUSTOM_TITLEBAR
        val hasStackedNavbar = findTopStackedNavbarImpl(windowImpl) != null ||
            windowImpl.nativeWidget.attributes["hasSeamlessStackedNavbar"] == true
        return if (hasStackedNavbar) CHROME_MODE_SEAMLESS_STACKED_NAVBAR else CHROME_MODE_STANDARD
    }

    internal fun syncUnifiedTitleBarChrome(session: MacosWindowSession) {
        val win = session.windowImpl ?: return
        val nsWin = session.nsWindow
        val undecorated = win.isUndecorated()
        val fsElement = win.getFullscreenElement()
        val mutVer = DesktopNativeWidget.globalLayoutMutationVersion
        val existingNav = session.attachedUnifiedNavbar
        if (mutVer == session.lastChromeSyncMutationVersion &&
            fsElement === session.lastChromeFullscreenElement &&
            undecorated == session.lastChromeUndecorated &&
            session.activeFullscreen == session.lastChromeActiveFullscreen &&
            nsWin.styleMask == session.lastChromeWindowStyleMask &&
            (existingNav == null || (existingNav.nativeWidget.visible && !existingNav.isDisposed))
        ) {
            return
        }
        val hasNativeOverlayTitleBar = !session.activeFullscreen &&
            !win.isFullscreen() &&
            fsElement == null &&
            findNativeOverlayTitleBarHandle(win, requireActive = false) != null
        val stackedNav = if (!hasNativeOverlayTitleBar && !undecorated && !session.activeFullscreen && fsElement == null) {
            findTopStackedNavbarImpl(win)
        } else {
            null
        }
        val prevNav = session.attachedUnifiedNavbar
        if (prevNav != null && prevNav !== stackedNav) {
            prevNav.setUnifiedWithMacosTitlebar(false)
        }
        session.attachedUnifiedNavbar = stackedNav
        stackedNav?.setUnifiedWithMacosTitlebar(true)

        val hadSeamlessAttr = win.nativeWidget.attributes["hasSeamlessStackedNavbar"] == true
        val nowSeamless = stackedNav != null
        if (hadSeamlessAttr != nowSeamless) {
            win.nativeWidget.attributes["hasSeamlessStackedNavbar"] = nowSeamless
        }
        val chromeMode = resolveChromeMode(win)
        val desiredMask = computeStyleMask(win, chromeMode, undecorated)
        val needsTitleVisible = chromeMode != CHROME_MODE_EXPLICIT_CUSTOM_TITLEBAR &&
            chromeMode != CHROME_MODE_NATIVE_OVERLAY_TITLEBAR &&
            !undecorated
        val maskMismatch = !session.activeFullscreen &&
            (nsWin as? MacosHostWindow)?.isTransitioningFullscreen != true &&
            nsWin.styleMask != desiredMask
        val titleVisMismatch = !session.activeFullscreen &&
            chromeMode != CHROME_MODE_NATIVE_OVERLAY_TITLEBAR &&
            (nsWin as? MacosHostWindow)?.isTransitioningFullscreen != true &&
            ((needsTitleVisible && nsWin.titleVisibility != NSWindowTitleVisible) ||
                (!needsTitleVisible && nsWin.titleVisibility != NSWindowTitleHidden))
        if (hadSeamlessAttr != nowSeamless ||
            hasNativeOverlayTitleBar != session.lastChromeNativeOverlayTitleBar ||
            maskMismatch ||
            titleVisMismatch
        ) {
            syncWindowProperties(win)
        }
        val explicitTitleBar = win.getTitleBarInstructions()
        val hasEmbeddedExplicitBar = chromeMode == CHROME_MODE_EXPLICIT_CUSTOM_TITLEBAR &&
            explicitTitleBar?.customTitleBar == true &&
            !undecorated &&
            !session.activeFullscreen &&
            fsElement == null &&
            (explicitTitleBar.heightPx != null ||
                explicitTitleBar.leadingComponents.isNotEmpty() ||
                explicitTitleBar.centerComponents.isNotEmpty() ||
                explicitTitleBar.trailingComponents.isNotEmpty())
        val topInset = if (hasEmbeddedExplicitBar && explicitTitleBar != null) {
            (explicitTitleBar.heightPx ?: CUSTOM_TITLEBAR_INSET_PX).coerceAtLeast(CUSTOM_TITLEBAR_INSET_PX)
        } else if (stackedNav != null && !undecorated && !session.activeFullscreen && fsElement == null) {
            val contentH = nsWin.contentRectForFrameRect(nsWin.frame).useContents { size.height }
            val layoutH = nsWin.contentLayoutRect.useContents { size.height }
            val measured = (contentH - layoutH).toInt()
            if (measured in 18..64) measured else 28
        } else {
            0
        }
        session.titleBarInsetTopPx = topInset
        session.lastChromeSyncMutationVersion = DesktopNativeWidget.globalLayoutMutationVersion
        session.lastChromeFullscreenElement = fsElement
        session.lastChromeUndecorated = undecorated
        session.lastChromeActiveFullscreen = session.activeFullscreen
        session.lastChromeNativeOverlayTitleBar = hasNativeOverlayTitleBar
        session.lastChromeWindowStyleMask = nsWin.styleMask
    }

    private fun computeStyleMask(
        windowImpl: MacosWindowNativeImpl,
        chromeMode: Int,
        undecorated: Boolean
    ): ULong {
        if (chromeMode == CHROME_MODE_NATIVE_OVERLAY_TITLEBAR) {
            var mask = NSWindowStyleMaskTitled or
                NSWindowStyleMaskClosable or
                NSWindowStyleMaskResizable or
                NSWindowStyleMaskFullSizeContentView
            if (windowImpl.minimizable) {
                mask = mask or NSWindowStyleMaskMiniaturizable
            }
            return mask
        }
        if (undecorated) {
            return UNDECORATED_WINDOWED_STYLE_MASK
        }
        var mask = NSWindowStyleMaskTitled or NSWindowStyleMaskClosable or NSWindowStyleMaskResizable
        if (windowImpl.minimizable) {
            mask = mask or NSWindowStyleMaskMiniaturizable
        }
        if (chromeMode != CHROME_MODE_STANDARD) {
            mask = mask or NSWindowStyleMaskFullSizeContentView
        }
        return mask
    }

    fun syncWindowProperties(windowImpl: MacosWindowNativeImpl) = MacosAppKitDispatch.onMainOrDefer {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return@onMainOrDefer
        val nsWin = session.nsWindow
        syncingChromeSessionKey = session.windowKey
        try {
            val chromeMode = resolveChromeMode(windowImpl)
            val isNativeOverlayBar = chromeMode == CHROME_MODE_NATIVE_OVERLAY_TITLEBAR
            val undecorated = windowImpl.isUndecorated()
            val pureBorderless = undecorated && !isNativeOverlayBar
            val desiredMask = computeStyleMask(windowImpl, chromeMode, undecorated)
            val hostWin = nsWin as? MacosHostWindow
            val isTransitioningFs = hostWin?.isTransitioningFullscreen == true
            val preserveNativeOverlayDuringFs = (session.activeFullscreen || isTransitioningFs) &&
                hostWin?.isNativeOverlayTitleBarHostWindow == true
            val effectiveNativeOverlayFlag = isNativeOverlayBar || preserveNativeOverlayDuringFs
            if (hostWin != null) {
                hostWin.isNativeOverlayTitleBarHostWindow = effectiveNativeOverlayFlag
                hostWin.isUndecoratedHostWindow = pureBorderless && !preserveNativeOverlayDuringFs
                hostWin.isToolModal = windowImpl.isToolModalWindow
            }
            session.rootView.isUndecoratedChrome = pureBorderless && !session.activeFullscreen && !preserveNativeOverlayDuringFs
            val maskChanged = !session.activeFullscreen && !isTransitioningFs && nsWin.styleMask != desiredMask
            if (maskChanged) {
                val savedFrame = nsWin.frame
                nsWin.setStyleMask(desiredMask)
                nsWin.setFrame(savedFrame, display = false)
                hostWin?.invalidateNativeOverlayTitleBarChromeCache()
            }
            val wantsSquareCorners = undecorated &&
                !session.activeFullscreen &&
                !isTransitioningFs &&
                !windowImpl.isToolModalWindow
            hostWin?.syncSquareWindowCorners(enabled = wantsSquareCorners, forceRefresh = maskChanged)
            val showWindowIcon = windowImpl.getTitleBarInstructions()?.showWindowIcon != false
            if (isNativeOverlayBar && !session.activeFullscreen) {
                nsWin.setOpaque(true)
                syncNativeOverlayTitleBarForSession(session)
            } else if (pureBorderless && !session.activeFullscreen && !preserveNativeOverlayDuringFs) {
                hostWin?.applyUndecoratedChromeAttributes() ?: run {
                    nsWin.setTitlebarAppearsTransparent(true)
                    nsWin.setTitleVisibility(NSWindowTitleHidden)
                }
                hostWin?.hideStandardWindowButtons()
            } else if (session.activeFullscreen && (undecorated || preserveNativeOverlayDuringFs)) {
                hostWin?.applyFullscreenMenuBarHoverChrome(windowImpl.getTitle(), showWindowIcon = showWindowIcon)
            } else {
                nsWin.setOpaque(true)
                val showMinimize = windowImpl.minimizable
                val enableMinimize = !session.activeFullscreen && windowImpl.minimizable
                when (chromeMode) {
                    CHROME_MODE_STANDARD -> {
                        nsWin.setTitlebarAppearsTransparent(false)
                        nsWin.setTitleVisibility(NSWindowTitleVisible)
                        nsWin.setTitlebarSeparatorStyle(platform.AppKit.NSTitlebarSeparatorStyle.NSTitlebarSeparatorStyleAutomatic)
                        hostWin?.showStandardWindowButtons(showMinimize = showMinimize, enableMinimize = enableMinimize, showWindowIcon = showWindowIcon)
                    }
                    CHROME_MODE_SEAMLESS_STACKED_NAVBAR -> {
                        nsWin.setTitlebarAppearsTransparent(true)
                        nsWin.setTitleVisibility(NSWindowTitleVisible)
                        nsWin.setTitlebarSeparatorStyle(platform.AppKit.NSTitlebarSeparatorStyle.NSTitlebarSeparatorStyleNone)
                        hostWin?.showStandardWindowButtons(showMinimize = showMinimize, enableMinimize = enableMinimize, showWindowIcon = showWindowIcon)
                    }
                    else -> {
                        nsWin.setTitlebarAppearsTransparent(true)
                        nsWin.setTitleVisibility(NSWindowTitleHidden)
                        nsWin.setTitlebarSeparatorStyle(platform.AppKit.NSTitlebarSeparatorStyle.NSTitlebarSeparatorStyleNone)
                        hostWin?.showStandardWindowButtons(showMinimize = showMinimize, enableMinimize = enableMinimize, showWindowIcon = showWindowIcon)
                    }
                }
            }
            val winBg = MacosAppKitAppearance.toNsColor(
                if (MacosAppKitAppearance.isDarkTheme(windowImpl.nativeWidget)) {
                    com.nexustools.genericui.style.color.RgbaColor(24, 26, 31, 255)
                } else {
                    com.nexustools.genericui.style.color.RgbaColor(244, 245, 248, 255)
                }
            )
            nsWin.setBackgroundColor(winBg)
            session.rootView.setWantsLayer(true)
            session.rootView.layer?.backgroundColor = winBg.CGColor
            if (!isNativeOverlayBar) {
                syncWindowTitle(windowImpl, windowImpl.getTitle())
            }
            syncWindowAlwaysOnTop(windowImpl)
            syncWindowAspectRatio(windowImpl)
            syncCustomTitleBarOverlay(session, windowImpl, chromeMode, undecorated || isNativeOverlayBar)
            hostWin?.syncContentViewFillsWindowFrame()
            activeAppearanceName?.let { nsWin.setAppearance(NSAppearance.appearanceNamed(it)) }
        } finally {
            syncingChromeSessionKey = 0L
        }
    }

    internal fun syncAllNativeOverlayTitleBars() {
        for (session in sessions.values.toList()) {
            if (!session.isClosing && (session.nsWindow as? MacosHostWindow)?.isNativeOverlayTitleBarHostWindow == true) {
                syncNativeOverlayTitleBarForSession(session)
            }
        }
    }

    internal fun syncNativeOverlayTitleBarForSession(session: MacosWindowSession) {
        val winImpl = session.windowImpl ?: return
        val hostWin = session.nsWindow as? MacosHostWindow ?: return
        if (!hostWin.isNativeOverlayTitleBarHostWindow) return
        if (session.activeFullscreen || winImpl.isFullscreen() || (hostWin.styleMask and NSWindowStyleMaskFullScreen) != 0uL) {
            hostWin.syncNativeOverlayTitleBarChrome(
                visible = true,
                alpha = 1.0,
                showClose = true,
                showMinimize = winImpl.minimizable,
                showMaximize = true,
                showTitle = true,
                titleText = formatEffectiveWindowTitle(winImpl.getTitle())
            )
            session.lastSyncedNativeTitleBarVisible = true
            session.lastSyncedNativeTitleBarAlpha = 1.0
            clearNativeOverlayTitleBarHoverPin(session)
            return
        }
        val barHandle = findNativeOverlayTitleBarHandle(winImpl, requireActive = false) ?: return
        var effectiveVisible = barHandle.attributes["data-titlebar-active"]
            ?.toString()
            ?.equals("false", ignoreCase = true) != true
        var accumulatedAlpha = 1.0
        var cur: DesktopNativeWidget? = barHandle
        var steps = 0
        while (effectiveVisible && cur != null && cur !== winImpl.nativeWidget && steps < 32) {
            steps++
            if (!cur.visible || cur.attributes["visibilityHidden"] == true) {
                effectiveVisible = false
                break
            }
            val view = (cur as? MacosViewHandle)?.nsView
                ?: (cur.attributes["macosMountedSurfaceView"] as? NSView)
            if (view != null) {
                if (view.isHidden()) {
                    effectiveVisible = false
                    break
                }
                val viewAlpha = view.alphaValue.coerceIn(0.0, 1.0)
                val widgetAlpha = cur.opacity.toDouble().coerceIn(0.0, 1.0)
                accumulatedAlpha *= min(viewAlpha, widgetAlpha)
            } else {
                accumulatedAlpha *= cur.opacity.toDouble().coerceIn(0.0, 1.0)
            }
            cur = cur.parentWidget
        }
        val effectiveAlpha = if (effectiveVisible) accumulatedAlpha.coerceIn(0.0, 1.0) else 0.0
        val finalVisible = effectiveVisible && effectiveAlpha > 0.004

        val showClose = barHandle.attributes["data-titlebar-show-close"]?.toString()?.equals("false", ignoreCase = true) != true
        val showMinimize = (barHandle.attributes["data-titlebar-show-minimize"]?.toString()?.equals("false", ignoreCase = true) != true) &&
            winImpl.minimizable
        val showMaximize = barHandle.attributes["data-titlebar-show-maximize"]?.toString()?.equals("false", ignoreCase = true) != true
        val showTitle = barHandle.attributes["data-titlebar-show-title"]?.toString()?.equals("false", ignoreCase = true) != true
        val showWindowIcon = (barHandle.attributes["data-titlebar-show-window-icon"]?.toString()?.equals("false", ignoreCase = true) != true) &&
            (winImpl.getTitleBarInstructions()?.showWindowIcon != false)
        val rawTitle = (barHandle.attributes["data-titlebar-title"] as? String)?.takeIf { it.isNotEmpty() }
            ?: winImpl.getTitle()
        val effectiveTitle = formatEffectiveWindowTitle(rawTitle)

        val titleBarHidNow = hostWin.syncNativeOverlayTitleBarChrome(
            visible = finalVisible,
            alpha = effectiveAlpha,
            showClose = showClose,
            showMinimize = showMinimize,
            showMaximize = showMaximize,
            showTitle = showTitle,
            titleText = effectiveTitle,
            showWindowIcon = showWindowIcon
        )
        session.lastSyncedNativeTitleBarVisible = finalVisible
        session.lastSyncedNativeTitleBarAlpha = effectiveAlpha
        syncNativeOverlayTitleBarHoverPin(session, barHandle, barVisible = finalVisible)
        if (titleBarHidNow) {
            refreshActiveCursorsNow()
        }
    }

    internal fun isBlockingPopupChildWindow(w: NSWindow, mousePt: CValue<NSPoint>): Boolean {
        if (!w.isVisible() || w.alphaValue <= 0.01) return false
        if (w.level >= NSPopUpMenuWindowLevel) return true
        val (mx, my) = mousePt.useContents { x to y }
        return w.frame.useContents {
            mx >= origin.x && mx <= origin.x + size.width &&
                my >= origin.y && my <= origin.y + size.height
        }
    }

    private fun refreshDeferredBackdropUnderlays(view: NSView) {
        when (view) {
            is MacosContainerView -> view.surfaceSupport.refreshDeferredBackdropUnderlayIfNeeded()
            is MacosStyledContainerView -> view.surfaceSupport.refreshDeferredBackdropUnderlayIfNeeded()
            is MacosStyledComponentView -> view.surfaceSupport.refreshDeferredBackdropUnderlayIfNeeded()
            is MacosStyledButtonControl -> view.surfaceSupport.refreshDeferredBackdropUnderlayIfNeeded()
            is com.nexustools.genericui.macos.impl.MacosStyledTextInputHostView ->
                view.surfaceSupport.refreshDeferredBackdropUnderlayIfNeeded()
        }
        for (sub in view.subviews) {
            val child = sub as? NSView ?: continue
            refreshDeferredBackdropUnderlays(child)
        }
    }

    internal fun hasActiveDialogDragOrResize(): Boolean =
        sessions.values.any { (it.nsWindow as? MacosDialogPanel)?.isActivelyDraggingOrResizing == true }

    internal fun isWindowClosing(win: NSWindow?): Boolean =
        win == null || findSessionByWindow(win)?.isClosing == true

    private fun syncNativeOverlayTitleBarHoverPin(
        session: MacosWindowSession,
        barHandle: MacosViewHandle,
        barVisible: Boolean,
        pointerClientY: Double? = null
    ) {
        if (hasActiveDialogDragOrResize()) return
        val mousePt = NSEvent.mouseLocation
        val hoveredNow = if (session.isClosing ||
            !session.isShown ||
            !session.nsWindow.isVisible() ||
            session.activeFullscreen ||
            (session.nsWindow as? MacosHostWindow)?.isTransitioningFullscreen == true
        ) {
            false
        } else {
            val hasPopupChild = session.nsWindow.childWindows?.any { cw ->
                val w = cw as? NSWindow
                w != null &&
                    sessions.values.none { isSameNsWindow(it.nsWindow, w) } &&
                    isBlockingPopupChildWindow(w, mousePt)
            } == true
            if (hasPopupChild) {
                true
            } else if (!barVisible) {
                false
            } else {
                val barH = (barHandle.bounds.height.takeIf { it > 0 } ?: CUSTOM_TITLEBAR_INSET_PX).toDouble()
                if (pointerClientY != null) {
                    pointerClientY in 0.0..barH
                } else {
                    val (mx, my) = mousePt.useContents { x to y }
                    val inTitleBarBand = session.nsWindow.frame.useContents {
                        val topY = origin.y + size.height
                        mx in origin.x..(origin.x + size.width) && my in (topY - barH)..topY
                    }
                    if (!inTitleBarBand) {
                        false
                    } else {
                        val topWinNumber = NSWindow.windowNumberAtPoint(mousePt, belowWindowWithWindowNumber = 0L)
                        topWinNumber <= 0L || topWinNumber == session.nsWindow.windowNumber
                    }
                }
            }
        }
        val barReportsPinned = barHandle.attributes["data-native-titlebar-hover-pinned"]?.toString() == "true"
        if (hoveredNow != session.nativeTitleBarHoverPinned || (!hoveredNow && barReportsPinned)) {
            session.nativeTitleBarHoverPinned = hoveredNow
            val barImpl = barHandle.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
            if (barImpl != null && !barImpl.isDisposed) {
                if (hoveredNow) {
                    barImpl.dispatchPointerEnter(PointerEvent())
                } else {
                    barImpl.dispatchPointerLeave(PointerEvent())
                }
            }
        }
    }

    private fun formatEffectiveWindowTitle(title: String): String =
        if (visualTestMode && !visualTestBannerText.isNullOrBlank()) {
            "$title — $visualTestBannerText"
        } else {
            title
        }

    private fun syncCustomTitleBarOverlay(
        session: MacosWindowSession,
        windowImpl: MacosWindowNativeImpl,
        chromeMode: Int,
        undecorated: Boolean
    ) {
        if (undecorated || chromeMode != CHROME_MODE_EXPLICIT_CUSTOM_TITLEBAR) {
            session.titleBarView?.removeFromSuperview()
            session.titleBarView = null
            session.rootView.titleBarOverlayView = null
            return
        }
        val (w, _) = session.currentClientSize()
        val tbInstructions = windowImpl.getTitleBarInstructions()
        val barH = (tbInstructions?.heightPx ?: CUSTOM_TITLEBAR_INSET_PX).coerceAtLeast(CUSTOM_TITLEBAR_INSET_PX)
        val tb = session.titleBarView ?: MacosTitleBarView(
            owner = null,
            frameRect = NSMakeRect(0.0, 0.0, w.toDouble(), barH.toDouble())
        ).also { created ->
            session.titleBarView = created
            session.rootView.titleBarOverlayView = created
            session.rootView.addSubview(created)
        }
        tb.setFrame(NSMakeRect(0.0, 0.0, w.toDouble(), barH.toDouble()))
        tb.syncAndLayout(
            windowImpl = windowImpl,
            nsWindow = session.nsWindow,
            barWidthPx = w,
            barHeightPx = barH,
            inFullscreen = session.activeFullscreen || windowImpl.isFullscreen()
        )
    }

    fun syncWindowAlwaysOnTop(windowImpl: MacosWindowNativeImpl) = MacosAppKitDispatch.onMainOrDefer {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return@onMainOrDefer
        val level = when {
            windowImpl.isAlwaysOnTop() || windowImpl.isToolModalWindow -> NSFloatingWindowLevel
            else -> platform.AppKit.NSNormalWindowLevel
        }
        session.nsWindow.setLevel(level)
    }

    fun syncWindowAspectRatio(windowImpl: MacosWindowNativeImpl) = MacosAppKitDispatch.onMainOrDefer {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return@onMainOrDefer
        val hostWin = session.nsWindow as? MacosHostWindow
        val isNormalState = windowImpl.getWindowState() == WindowState.NORMAL &&
            !windowImpl.isFullscreen() &&
            !session.activeFullscreen &&
            !session.nsWindow.isZoomed() &&
            hostWin?.isTransitioningFullscreen != true &&
            hostWin?.isAnimatingMaximize != true &&
            hostWin?.isPerformingNativeZoom != true
        val ratio = if (isNormalState) windowImpl.getAspectRatio() else null
        if (ratio != null && ratio > 0.0) {
            session.nsWindow.setContentAspectRatio(NSMakeSize(ratio, 1.0))
        } else {
            clearWindowContentAspectRatio(session.nsWindow)
        }
    }

    internal fun clearWindowContentAspectRatio(nsWindow: NSWindow) {
        nsWindow.setContentAspectRatio(NSMakeSize(0.0, 0.0))
        nsWindow.setResizeIncrements(NSMakeSize(1.0, 1.0))
    }

    fun syncWindowTitle(windowImpl: MacosWindowNativeImpl, title: String) = MacosAppKitDispatch.onMainOrDefer {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return@onMainOrDefer
        val effectiveTitle = if (visualTestMode && !visualTestBannerText.isNullOrBlank()) {
            "$title — $visualTestBannerText"
        } else {
            title
        }
        session.nsWindow.setTitle(effectiveTitle)
    }

    fun syncWindowSize(windowImpl: MacosWindowNativeImpl) = MacosAppKitDispatch.onMainOrDefer {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return@onMainOrDefer
        val winSize = windowImpl.getWindowSize()
        val w = winSize.width.toDouble().coerceAtLeast(160.0)
        val h = winSize.height.toDouble().coerceAtLeast(120.0)
        val minSize = windowImpl.getMinWindowSize()
        if (minSize != null) {
            session.nsWindow.setContentMinSize(NSMakeSize(minSize.width.toDouble(), minSize.height.toDouble()))
        }
        val (beforeW, beforeH) = session.currentClientSize()
        if (beforeW != w.roundToInt() || beforeH != h.roundToInt()) {
            session.isSyncingSize = true
            try {
                val (oldX, oldTopY) = session.nsWindow.frame.useContents {
                    origin.x to (origin.y + size.height)
                }
                val (frameW, frameH) = session.nsWindow.frameRectForContentRect(NSMakeRect(0.0, 0.0, w, h)).useContents {
                    size.width to size.height
                }
                session.nsWindow.setFrame(NSMakeRect(oldX, oldTopY - frameH, frameW, frameH), display = false)
            } finally {
                session.isSyncingSize = false
            }
        }
        val (cw, ch) = session.currentClientSize()
        val mutVer = DesktopNativeWidget.globalLayoutMutationVersion
        if (session.needsRelayout ||
            session.lastLaidOutWidth != cw ||
            session.lastLaidOutHeight != ch ||
            session.lastMutationVersion != mutVer
        ) {
            session.needsRelayout = true
            session.needsRedisplay = true
            session.layoutWindowTree(force = true)
            if (redrawRequested && sessions.values.none { it.needsRelayout }) {
                redrawRequested = false
            }
        }
    }

    fun syncWindowPosition(windowImpl: MacosWindowNativeImpl) = MacosAppKitDispatch.onMainOrDefer {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return@onMainOrDefer
        val pos = windowImpl.getWindowPosition()
        session.nsWindow.setFrameTopLeftPoint(desktopTopLeftToCocoaTopLeft(pos.x, pos.y))
    }

    fun syncWindowState(windowImpl: MacosWindowNativeImpl) = MacosAppKitDispatch.onMainOrDefer {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return@onMainOrDefer
        val hostWin = session.nsWindow as? MacosHostWindow
        when (windowImpl.getWindowState()) {
            WindowState.MINIMIZED -> session.nsWindow.miniaturize(null)
            WindowState.MAXIMIZED -> {
                if (hostWin != null) {
                    hostWin.setMaximized(true)
                } else if (!session.nsWindow.isZoomed()) {
                    session.nsWindow.zoom(null)
                }
            }
            WindowState.NORMAL -> {
                if (session.nsWindow.isMiniaturized()) session.nsWindow.deminiaturize(null)
                if (hostWin != null) {
                    hostWin.setMaximized(false)
                } else if (session.nsWindow.isZoomed()) {
                    session.nsWindow.zoom(null)
                }
            }
            WindowState.FULLSCREEN -> syncFullscreen(windowImpl, true)
        }
    }

    private fun findSessionByWindow(win: NSWindow?): MacosWindowSession? {
        if (win == null) return null
        return sessions.values.firstOrNull { isSameNsWindow(it.nsWindow, win) }
    }

    internal fun recordLeftMouseDownEvent(win: NSWindow?, event: NSEvent) {
        findSessionByWindow(win)?.lastLeftMouseDownEvent = event
    }

    internal fun clearLeftMouseDownEvent(win: NSWindow?) {
        findSessionByWindow(win)?.lastLeftMouseDownEvent = null
    }

    internal fun hasRecordedLeftMouseDownEvent(win: NSWindow?): Boolean =
        findSessionByWindow(win)?.lastLeftMouseDownEvent != null

    internal fun isNativeTitleBarHoverPinned(win: NSWindow?): Boolean =
        findSessionByWindow(win)?.nativeTitleBarHoverPinned == true

    internal fun resolveSystemMoveDragEventForWindow(
        win: NSWindow?,
        currentEvent: NSEvent? = NSApplication.sharedApplication.currentEvent
    ): NSEvent? = findSessionByWindow(win)?.let { resolveSystemMoveDragEvent(it, currentEvent) }

    internal fun resolveSystemMoveDragEvent(
        session: MacosWindowSession,
        currentEvent: NSEvent? = NSApplication.sharedApplication.currentEvent
    ): NSEvent? {
        if (currentEvent != null &&
            currentEvent.type == NSEventTypeLeftMouseDown &&
            isSameNsWindow(currentEvent.window, session.nsWindow)
        ) {
            return currentEvent
        }
        session.lastLeftMouseDownEvent?.let { recorded ->
            if (recorded.type == NSEventTypeLeftMouseDown && isSameNsWindow(recorded.window, session.nsWindow)) {
                return recorded
            }
        }
        if (currentEvent != null &&
            currentEvent.type == NSEventTypeLeftMouseDragged &&
            isSameNsWindow(currentEvent.window, session.nsWindow)
        ) {
            return currentEvent
        }
        return null
    }

    internal fun performWindowDragWithCursorLock(win: NSWindow, event: NSEvent) {
        val dragSession = PointerDragManager.startWindowMoveDrag(lockedCursor = CssCursor.DEFAULT)
        try {
            win.performWindowDragWithEvent(event)
        } finally {
            clearLeftMouseDownEvent(win)
            dragSession.dispose()
            refreshActiveCursorsNow()
        }
    }

    fun startSystemMove(windowImpl: MacosWindowNativeImpl) = MacosAppKitDispatch.onMainOrDefer {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return@onMainOrDefer
        val dragEvent = resolveSystemMoveDragEvent(session) ?: return@onMainOrDefer
        performWindowDragWithCursorLock(session.nsWindow, dragEvent)
    }

    fun syncFullscreen(windowImpl: MacosWindowNativeImpl, fullscreen: Boolean) = MacosAppKitDispatch.onMainOrDefer {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return@onMainOrDefer
        if (session.isClosing) return@onMainOrDefer
        val currentlyFullscreen = (session.nsWindow.styleMask and NSWindowStyleMaskFullScreen) != 0uL
        if (fullscreen != currentlyFullscreen) {
            session.nsWindow.toggleFullScreen(null)
        }
        session.needsRelayout = true
        session.layoutWindowTree(force = true)
    }

    internal fun syncMacApplicationMenu(menuImpl: MenuNativeImpl<*>?, appTitle: String? = null) = MacosAppKitDispatch.onMainOrDefer {
        activeApplicationMenuImpl = menuImpl
        if (!appTitle.isNullOrBlank()) {
            activeApplicationTitle = appTitle
        }
        mainMenuDirty = true
        rebuildMainMenu()
    }

    fun syncMenuBar(windowImpl: MacosWindowNativeImpl, menuBarImpl: MenuBarNativeImpl<*>?) = MacosAppKitDispatch.onMainOrDefer {
        val winKey = windowImpl.nativeWidget.nativePointer
        val isControllingWindow = activeKeyWindowId == null ||
            activeKeyWindowId == winKey ||
            sessions[activeKeyWindowId]?.windowImpl == null
        if (activeApplicationTitle.isNullOrBlank()) {
            val winTitle = windowImpl.getTitle()
            if (winTitle.isNotBlank()) {
                activeApplicationTitle = winTitle
            }
        }
        if (!isControllingWindow) {
            return@onMainOrDefer
        }
        val nextBar = menuBarImpl as? com.nexustools.genericui.desktop.shell.DesktopMenuBarNativeImpl<*>
        if (nextBar == null && activeWindowMenuBarImpl == null && mainMenuInstalled && !mainMenuDirty) {
            return@onMainOrDefer
        }
        activeWindowMenuBarImpl = nextBar
        mainMenuDirty = true
        rebuildMainMenu()
    }

    private fun rebuildMainMenu() {
        if (!NSThread.isMainThread) {
            MacosAppKitDispatch.onMainOrDefer { rebuildMainMenu() }
            return
        }
        val resolvedTitle = activeApplicationTitle?.takeIf { it.isNotBlank() }
            ?: GenericUI.applicationName.takeIf { it.isNotBlank() && it != "GenericUI" }
            ?: activeSession()?.windowImpl?.getTitle()?.takeIf { it.isNotBlank() }
            ?: "GenericUI"
        MacosMenuBarBridge.installMainMenu(
            appName = resolvedTitle,
            menuBarImpl = activeWindowMenuBarImpl,
            applicationMenuImpl = activeApplicationMenuImpl
        )
        mainMenuInstalled = true
        mainMenuDirty = false
        mainMenuInstallCountForTest++
    }

    private fun ensureMainMenuInstalled() {
        if (!NSThread.isMainThread) {
            MacosAppKitDispatch.onMainOrDefer { ensureMainMenuInstalled() }
            return
        }
        if (NSApplication.sharedApplication.mainMenu == null || !mainMenuInstalled || mainMenuDirty) {
            rebuildMainMenu()
        }
    }

    private fun scheduleDeferredSquareCornerRefresh(session: MacosWindowSession) {
        val windowKey = session.windowKey
        val refresh: () -> Unit = {
            val activeSession = sessions[windowKey]
            val activeWin = activeSession?.windowImpl
            if (activeSession != null && activeWin != null &&
                !activeSession.isClosing && activeSession.isShown && !activeSession.activeFullscreen
            ) {
                (activeSession.nsWindow as? MacosHostWindow)?.syncSquareWindowCorners(
                    enabled = activeWin.isUndecorated() && !activeWin.isToolModalWindow,
                    forceRefresh = true
                )
            }
        }
        dispatch_async(dispatch_get_main_queue(), refresh)
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, 60_000_000L), dispatch_get_main_queue(), refresh)
    }

    fun syncWindowVisibility(windowImpl: MacosWindowNativeImpl, visible: Boolean) = runOnMainSync {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return@runOnMainSync
        if (visible) {
            session.nsWindow.makeKeyAndOrderFront(null)
            session.isShown = true
            (session.nsWindow as? MacosHostWindow)?.syncSquareWindowCorners(
                enabled = windowImpl.isUndecorated() && !session.activeFullscreen && !windowImpl.isToolModalWindow,
                forceRefresh = true
            )
            scheduleDeferredSquareCornerRefresh(session)
        } else {
            session.nsWindow.orderOut(null)
            session.isShown = false
        }
        syncActivationPolicyForCurrentState()
    }

    fun syncWindowOpacity(windowImpl: MacosWindowNativeImpl, opacity: Float) = MacosAppKitDispatch.onMainOrDefer {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return@onMainOrDefer
        session.nsWindow.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    fun bringWindowToFront(windowImpl: MacosWindowNativeImpl) = MacosAppKitDispatch.onMainOrDefer {
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return@onMainOrDefer
        session.nsWindow.makeKeyAndOrderFront(null)
        if (!shouldSuppressDockIconForTestProcess()) {
            NSApplication.sharedApplication.activateIgnoringOtherApps(true)
        }
        activeKeyWindowId = session.windowKey
        syncFocusedWindowMenuBarIfNeeded()
    }

    private fun clearSessionRootViewCallbacks(session: MacosWindowSession) {
        val rv = session.rootView
        rv.onNeedsRelayout = null
        rv.onUndecoratedEdgeMouseDown = null
        rv.onUndecoratedEdgeMouseDragged = null
        rv.onUndecoratedEdgeMouseUp = null
        rv.onWindowMouseMoved = null
        rv.onWindowMouseExited = null
        rv.onWindowKeyDown = null
        rv.onWindowKeyUp = null
    }

    fun onWindowClosed(windowImpl: MacosWindowNativeImpl) = MacosAppKitDispatch.onMainOrDefer {
        val key = windowImpl.nativeWidget.nativePointer
        val session = sessions.remove(key) ?: return@onMainOrDefer
        session.isClosing = true
        session.isShown = false
        session.lastLeftMouseDownEvent = null
        resetUndecoratedResizeState(session)
        (session.nsWindow as? MacosHostWindow)?.let { hostWin ->
            hostWin.isClosingWindow = true
            hostWin.onMaximizedStateChanged = null
        }
        (session.nsWindow as? MacosDialogPanel)?.detachForClose()
        val ownerNsWin = session.nsWindow.parentWindow
            ?: session.ownerWindowKey?.let { sessions[it]?.nsWindow }
        ownerNsWin?.removeChildWindow(session.nsWindow)
        clearSessionRootViewCallbacks(session)
        (windowImpl.nativeWidget as? MacosViewHandle)?.let { handle ->
            handle.nsWindow = null
            handle.nsView = null
            handle.hostInvalidateHook = null
            handle.hostRelayoutHook = null
        }
        session.nsWindow.setDelegate(null)
        session.nsWindow.orderOut(null)
        session.nsWindow.close()
        if (activeKeyWindowId == key) {
            activeKeyWindowId = sessions.keys.firstOrNull()
            syncFocusedWindowMenuBarIfNeeded()
        }
        syncActivationPolicyForCurrentState()
        if (!hasAnyRemainingVisiblePresentationWindow() && eventLoopRunning) {
            terminateEventLoop()
        }
    }

    internal fun shouldSuppressDockIconForTestProcess(): Boolean {
        if (visualTestMode) return true
        val info = NSProcessInfo.processInfo
        val name = info.processName
        if (name == "test.kexe" || name.endsWith("Test.kexe")) return true
        for (arg in info.arguments) {
            val s = arg as? String ?: continue
            if (s.startsWith("--ktest_")) return true
        }
        return false
    }

    internal fun computeProductionActivationPolicy(
        hasVisibleWindow: Boolean = hasAnyRemainingVisiblePresentationWindow()
    ): NSApplicationActivationPolicy = if (hasVisibleWindow) {
        NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular
    } else {
        NSApplicationActivationPolicy.NSApplicationActivationPolicyAccessory
    }

    internal fun syncActivationPolicyForCurrentState() = MacosAppKitDispatch.onMainOrDefer {
        val app = NSApplication.sharedApplication
        val desiredPolicy = if (shouldSuppressDockIconForTestProcess()) {
            NSApplicationActivationPolicy.NSApplicationActivationPolicyAccessory
        } else {
            computeProductionActivationPolicy()
        }
        if (app.activationPolicy() != desiredPolicy) {
            app.setActivationPolicy(desiredPolicy)
        }
    }

    internal fun onTrayDisposed(hasRemainingActiveTrays: Boolean) = MacosAppKitDispatch.onMainOrDefer {
        syncActivationPolicyForCurrentState()
        if (!hasRemainingActiveTrays && !hasAnyRemainingVisiblePresentationWindow()) {
            terminateEventLoop()
        }
    }

    private fun hasAnyRemainingVisiblePresentationWindow(): Boolean =
        sessions.values.any { it.isShown && !it.isClosing && it.nsWindow.isVisible() }

    internal fun hasAnyVisiblePresentationWindowForTest(): Boolean =
        hasAnyRemainingVisiblePresentationWindow()

    // -------------------------------------------------------------------------
    // Window Delegate Notifications
    // -------------------------------------------------------------------------

    internal fun onNativeWindowShouldClose(windowKey: Long, isDialog: Boolean): Boolean {
        val session = sessions[windowKey] ?: return true
        val dock = session.dockWindowImpl
        if (dock != null) {
            dock.hideDockWindow()
            return false
        }
        val dlg = session.dialogImpl
        if (dlg != null) {
            dlg.closeDialog()
            return false
        }
        val win = session.windowImpl ?: return true
        win.closeWindow()
        return false
    }

    internal fun onNativeWindowWillClose(windowKey: Long, isDialog: Boolean) {
        val session = sessions.remove(windowKey) ?: return
        session.isClosing = true
        session.isShown = false
        resetUndecoratedResizeState(session)
        (session.nsWindow as? MacosHostWindow)?.let { hostWin ->
            hostWin.isClosingWindow = true
            hostWin.onMaximizedStateChanged = null
        }
        (session.nsWindow as? MacosDialogPanel)?.detachForClose()
        val ownerNsWin = session.nsWindow.parentWindow
            ?: session.ownerWindowKey?.let { sessions[it]?.nsWindow }
        ownerNsWin?.removeChildWindow(session.nsWindow)
        clearSessionRootViewCallbacks(session)
        session.nsWindow.setDelegate(null)
        session.nsWindow.orderOut(null)
        if (activeKeyWindowId == windowKey) {
            activeKeyWindowId = sessions.keys.firstOrNull()
            syncFocusedWindowMenuBarIfNeeded()
        }
        val dlg = session.dialogImpl
        val win = session.windowImpl
        (dlg?.nativeWidget as? MacosViewHandle ?: win?.nativeWidget as? MacosViewHandle)?.let { handle ->
            handle.nsWindow = null
            handle.nsView = null
            handle.hostInvalidateHook = null
            handle.hostRelayoutHook = null
        }
        if (dlg != null && dlg.isVisible()) {
            dlg.closeDialog()
        } else if (win != null && !win.isDisposed) {
            win.closeWindow()
        }
        syncActivationPolicyForCurrentState()
        if (!hasAnyRemainingVisiblePresentationWindow() && eventLoopRunning) {
            terminateEventLoop()
        }
    }

    internal fun onNativeWindowDidResize(windowKey: Long, isDialog: Boolean) {
        val session = sessions[windowKey] ?: return
        if (syncingChromeSessionKey == windowKey || session.isClosing || session.isSyncingSize) return
        val hostWin = session.nsWindow as? MacosHostWindow
        val (w, h) = session.currentClientSize()
        // Do not overwrite Window.lastWindowedNormalSize or fight AppKit aspect-ratio enforcement
        // during fullscreen transitions, native zoom animations, maximize animations, or live drag resize;
        // completion callbacks dispatch the settled size once done.
        val deferOsResizeCallback = hostWin?.isTransitioningFullscreen == true ||
            hostWin?.isAnimatingMaximize == true ||
            hostWin?.isPerformingNativeZoom == true ||
            session.rootView.isInNativeLiveResize
        if (!deferOsResizeCallback) {
            session.windowImpl?.notifyHostResizedFromOs(
                widthPx = w,
                heightPx = h,
                fullscreenNow = session.activeFullscreen,
                maximizedNow = session.nsWindow.isZoomed()
            )
            if (session.dialogImpl != null) {
                session.isSyncingSize = true
                try {
                    (session.dialogImpl as? MacosDialogWindowNativeImpl)?.syncHostDialogBounds(w, h)
                } finally {
                    session.isSyncingSize = false
                }
            }
        }
        session.layoutWindowTree(force = false)
    }

    internal fun onNativeWindowDidMove(windowKey: Long, isDialog: Boolean) {
        val session = sessions[windowKey] ?: return
        if (session.windowImpl == null) return
        if (session.isClosing || session.isSyncingSize) return
        val hostWin = session.nsWindow as? MacosHostWindow
        if (hostWin?.isTransitioningFullscreen == true ||
            session.activeFullscreen ||
            hostWin?.isAnimatingMaximize == true ||
            hostWin?.isPerformingNativeZoom == true ||
            session.nsWindow.isZoomed()
        ) {
            return
        }
        val (topLeftX, topLeftY) = cocoaFrameToDesktopTopLeft(session.nsWindow.frame)
        session.windowImpl?.notifyHostMovedFromOs(topLeftX, topLeftY)
    }

    internal fun onNativeWindowShouldZoom(windowKey: Long, isDialog: Boolean, window: NSWindow) {
        val session = sessions[windowKey] ?: return
        session.needsRelayout = true
    }

    internal fun onNativeWindowMiniaturized(windowKey: Long, isDialog: Boolean, miniaturized: Boolean) {
        val session = sessions[windowKey] ?: return
        val win = session.windowImpl ?: return
        win.notifyHostMiniaturizedFromOs(miniaturized = miniaturized)
        if (!miniaturized) {
            (session.nsWindow as? MacosHostWindow)?.restoreUndecoratedStyleMaskIfNeeded()
            syncWindowProperties(win)
        }
    }

    internal fun onNativeWindowFocusChanged(windowKey: Long, isDialog: Boolean, focused: Boolean) {
        val session = sessions[windowKey] ?: return
        if (session.isClosing) return
        if (focused) {
            activeKeyWindowId = windowKey
            syncFocusedWindowMenuBarIfNeeded()
        } else {
            resetUndecoratedResizeState(session)
            val forcedKeyInTest = visualTestMode &&
                (session.nsWindow as? MacosHostWindow)?.forceActiveKeyAppearanceForCapture == true
            if (!forcedKeyInTest) {
                session.rootView.clearLastAppliedCursor()
                if (MacosCursorTranslator.isHiddenCursor(NSCursor.currentCursor)) {
                    NSCursor.arrowCursor.set()
                }
            }
        }
        if (session.lastDispatchedFocus == focused) return
        session.lastDispatchedFocus = focused
        session.windowImpl?.dispatchFocusChanged(focused)
    }

    internal fun onNativeWindowFullscreenWillChange(windowKey: Long, isDialog: Boolean, enteringFullscreen: Boolean) {
        val session = sessions[windowKey] ?: return
        val win = session.windowImpl ?: return
        syncingChromeSessionKey = windowKey
        try {
            clearNativeOverlayTitleBarHoverPin(session)
            val isNativeOverlay = (session.nsWindow as? MacosHostWindow)?.isNativeOverlayTitleBarHostWindow == true
            val isUndec = !isNativeOverlay && (session.preFullscreenWasUndecorated || win.isUndecorated())
            if (enteringFullscreen) {
                session.preFullscreenWasUndecorated = isUndec
                session.rootView.isUndecoratedChrome = false
                resetUndecoratedResizeState(session)
                session.nsWindow.invalidateCursorRectsForView(session.rootView)
            }
            (session.nsWindow as? MacosHostWindow)?.let { hostWin ->
                hostWin.invalidateNativeOverlayTitleBarChromeCache()
                if (enteringFullscreen) {
                    hostWin.syncSquareWindowCorners(enabled = false)
                }
                if (isUndec && enteringFullscreen &&
                    !hostWin.isTransitioningFullscreen &&
                    (hostWin.styleMask and NSWindowStyleMaskTitled) == 0uL
                ) {
                    val savedFrame = hostWin.frame
                    hostWin.styleMask = UNDECORATED_TRANSITION_STYLE_MASK
                    hostWin.setFrame(savedFrame, display = false)
                }
                hostWin.markFullscreenTransitionWillChange()
                if (enteringFullscreen) {
                    hostWin.applyFullscreenMenuBarHoverChrome(win.getTitle())
                    hostWin.syncContentViewFillsWindowFrame()
                } else if (isUndec) {
                    hostWin.applyUndecoratedChromeAttributes()
                    hostWin.hideStandardWindowButtons()
                    hostWin.syncContentViewFillsWindowFrame()
                }
            }
        } finally {
            syncingChromeSessionKey = 0L
        }
    }

    internal fun onNativeWindowFullscreenFailed(windowKey: Long, isDialog: Boolean) {
        val session = sessions[windowKey] ?: return
        val win = session.windowImpl ?: return
        val inFullscreenNow = (session.nsWindow.styleMask and NSWindowStyleMaskFullScreen) != 0uL
        session.activeFullscreen = inFullscreenNow
        clearNativeOverlayTitleBarHoverPin(session)
        val hostWin = session.nsWindow as? MacosHostWindow
        hostWin?.onFullscreenTransitionFinished(
            succeeded = false,
            inFullscreenNow = inFullscreenNow
        )
        val (w, h) = session.currentClientSize()
        win.notifyHostResizedFromOs(
            widthPx = w,
            heightPx = h,
            fullscreenNow = inFullscreenNow,
            maximizedNow = session.nsWindow.isZoomed()
        )
        if (!inFullscreenNow) {
            syncWindowProperties(win)
            hostWin?.syncSquareWindowCorners(
                enabled = win.isUndecorated() && !win.isToolModalWindow,
                forceRefresh = true
            )
        } else {
            session.rootView.isUndecoratedChrome = false
            resetUndecoratedResizeState(session)
            hostWin?.applyFullscreenMenuBarHoverChrome(win.getTitle())
        }
        session.nsWindow.invalidateCursorRectsForView(session.rootView)
        session.needsRelayout = true
        session.layoutWindowTree(force = true)
    }

    internal fun onNativeWindowFullscreenChanged(windowKey: Long, isDialog: Boolean, fullscreen: Boolean) {
        val session = sessions[windowKey] ?: return
        val win = session.windowImpl ?: return
        session.activeFullscreen = fullscreen
        syncingChromeSessionKey = windowKey
        try {
            clearNativeOverlayTitleBarHoverPin(session)
            val hostWin = session.nsWindow as? MacosHostWindow
            hostWin?.onFullscreenTransitionFinished(succeeded = true, inFullscreenNow = fullscreen)
            val (w, h) = session.currentClientSize()
            win.notifyHostResizedFromOs(
                widthPx = w,
                heightPx = h,
                fullscreenNow = fullscreen,
                maximizedNow = session.nsWindow.isZoomed()
            )
            if (fullscreen) {
                session.rootView.isUndecoratedChrome = false
                resetUndecoratedResizeState(session)
                hostWin?.applyFullscreenMenuBarHoverChrome(win.getTitle())
                hostWin?.standardWindowButton(platform.AppKit.NSWindowCloseButton)?.superview?.layoutSubtreeIfNeeded()
            } else {
                syncWindowProperties(win)
                hostWin?.syncSquareWindowCorners(
                    enabled = win.isUndecorated() && !win.isToolModalWindow,
                    forceRefresh = true
                )
                dispatch_async(dispatch_get_main_queue()) {
                    val activeSession = sessions[windowKey] ?: return@dispatch_async
                    val activeWin = activeSession.windowImpl ?: return@dispatch_async
                    if (activeSession.isClosing || activeSession.activeFullscreen) return@dispatch_async
                    syncWindowProperties(activeWin)
                    syncNativeOverlayTitleBarForSession(activeSession)
                    (activeSession.nsWindow as? MacosHostWindow)?.syncSquareWindowCorners(
                        enabled = activeWin.isUndecorated() && !activeWin.isToolModalWindow,
                        forceRefresh = true
                    )
                    activeSession.needsRelayout = true
                    activeSession.layoutWindowTree(force = true)
                }
            }
            session.nsWindow.invalidateCursorRectsForView(session.rootView)
            session.needsRelayout = true
            session.layoutWindowTree(force = true)
        } finally {
            syncingChromeSessionKey = 0L
        }
    }

    // -------------------------------------------------------------------------
    // VBlank, Display & Frame Metrics
    // -------------------------------------------------------------------------

    internal fun activeHostWindowForDisplayLink(): NSWindow? =
        sessions.values.firstOrNull { !it.isClosing && !it.isDialog && it.stageViews.isNotEmpty() }?.nsWindow
            ?: sessions.values.firstOrNull { !it.isClosing && !it.isDialog }?.nsWindow
            ?: activeSession()?.nsWindow

    private fun dispatchActiveVBlankWidgets(timestampMs: Double? = null) {
        val active = com.nexustools.genericui.desktop.core.DesktopNativeWidget.activeVBlankWidgetsSnapshot()
        if (active.isEmpty()) return
        val ts = timestampMs ?: (platform.QuartzCore.CACurrentMediaTime() * 1000.0)
        for (w in active) {
            w.vblankDispatch?.invoke(ts)
        }
    }

    internal fun syncHostVBlankWatcher() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { syncHostVBlankWatcher() }
            return
        }
        val active = com.nexustools.genericui.desktop.core.DesktopNativeWidget.activeVBlankWidgetsSnapshot()
        val hasActive = active.isNotEmpty() || sessions.values.any { it.stageViews.isNotEmpty() }
        val scheduler = com.nexustools.genericui.animation.Animator.activeScheduler
        if (scheduler is MacosAnimationNativeImpl) {
            if (hostVBlankWatcherId > 0) {
                com.nexustools.genericui.animation.Animator.unwatchVBlank(hostVBlankWatcherId)
                hostVBlankWatcherId = -1
            }
            val preferredHz = com.nexustools.genericui.desktop.core.DesktopNativeWidget.resolvePreferredVBlankRateHz()
            scheduler.setHostSurfaceDemand(active = hasActive, preferredRateHz = preferredHz)
        } else if (hasActive && hostVBlankWatcherId <= 0) {
            hostVBlankWatcherId = com.nexustools.genericui.animation.Animator.watchVBlank { _, tsMs, _ ->
                onAnimatorVBlankTick(tsMs)
            }
        } else if (!hasActive && hostVBlankWatcherId > 0) {
            com.nexustools.genericui.animation.Animator.unwatchVBlank(hostVBlankWatcherId)
            hostVBlankWatcherId = -1
        }
    }

    internal fun onAnimatorVBlankTick(timestampMs: Double? = null) {
        dispatchActiveVBlankWidgets(timestampMs)
        flushPendingOverlayRelayout()
        syncAllNativeOverlayTitleBars()
        var anyStage = false
        for (session in sessions.values) {
            if (session.presentStageSurfaces(forceRefresh = false)) {
                recordStageOnlyPaint(250L)
                anyStage = true
            }
        }
        if (redrawRequested) {
            invalidateAllWindows(stageOnly = false)
        } else if (stageRedrawRequested && !anyStage) {
            invalidateAllWindows(stageOnly = true)
        }
    }

    private fun resolveOwningSession(widget: DesktopNativeWidget): MacosWindowSession? {
        var cur: DesktopNativeWidget? = widget
        var steps = 0
        while (cur != null && steps < 128) {
            steps++
            sessions[cur.nativePointer]?.let { return it }
            cur = cur.parentWidget
        }
        return null
    }

    fun requestRedrawForWidget(widget: DesktopNativeWidget) {
        val owningSession = resolveOwningSession(widget)
        if (owningSession == null) {
            requestRedraw()
            return
        }
        owningSession.needsRelayout = true
        owningSession.needsRedisplay = true
        scheduleSessionFlush()
    }

    private fun scheduleSessionFlush() {
        if (redisplayDispatchScheduled.compareAndSet(0, 1)) {
            dispatch_async(dispatch_get_main_queue()) {
                redisplayDispatchScheduled.store(0)
                if (redrawRequested) {
                    redrawRequested = false
                    for (session in sessions.values) {
                        session.needsRelayout = true
                        session.needsRedisplay = true
                        flushSessionDisplayNow(session, stageOnly = false)
                    }
                    return@dispatch_async
                }
                redisplayOnlyRequested = false
                for (session in sessions.values) {
                    if (session.needsRelayout || session.needsRedisplay) {
                        flushSessionDisplayNow(session, stageOnly = false)
                    }
                }
            }
        }
    }

    fun requestRedraw() {
        redrawRequested = true
        redisplayOnlyRequested = false
        for (session in sessions.values) {
            session.needsRelayout = true
            session.needsRedisplay = true
        }
        if (redrawDispatchScheduled.compareAndSet(0, 1)) {
            dispatch_async(dispatch_get_main_queue()) {
                redrawDispatchScheduled.store(0)
                if (redrawRequested) {
                    redrawRequested = false
                    for (session in sessions.values) {
                        session.needsRelayout = true
                        session.needsRedisplay = true
                        flushSessionDisplayNow(session, stageOnly = false)
                    }
                }
            }
        }
    }

    fun requestViewportRedisplay() {
        redisplayOnlyRequested = true
        for (session in sessions.values) {
            session.needsRedisplay = true
        }
        scheduleSessionFlush()
    }

    fun onUiQueueDrained() {
        if (redrawRequested) {
            requestRedraw()
            return
        }
        if (sessions.values.any { it.needsRelayout }) {
            scheduleSessionFlush()
            return
        }
        if (withOverlayRelayoutLock { pendingOverlayRelayoutRoots.isNotEmpty() }) {
            MacosAppKitDispatch.onMainOrDefer { flushPendingOverlayRelayout() }
        }
        val hasRedisplay = redisplayOnlyRequested || sessions.values.any { it.needsRedisplay }
        if (hasRedisplay) {
            requestViewportRedisplay()
            return
        }
        val hasStage = stageRedrawRequested || sessions.values.any { it.needsStageOnlyRedisplay }
        if (hasStage) {
            requestStageRedraw()
        }
    }

    fun requestStageRedraw() {
        stageRedrawRequested = true
        if (stageRedrawDispatchScheduled.compareAndSet(0, 1)) {
            dispatch_async(dispatch_get_main_queue()) {
                autoreleasepool {
                    stageRedrawDispatchScheduled.store(0)
                    if (stageRedrawRequested) {
                        stageRedrawRequested = false
                        for (session in sessions.values) {
                            session.needsStageOnlyRedisplay = true
                            flushSessionDisplayNow(session, stageOnly = true)
                        }
                    }
                }
            }
        }
    }

    private fun resolveTopIsolatedOverlayContainer(
        widget: DesktopNativeWidget
    ): com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl<*>? {
        var highestContainer: com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl<*>? = null
        var cur: DesktopNativeWidget? = widget
        while (cur != null && cur.isInsideIsolatedStageSubtree()) {
            val impl = cur.attributes["nativeImpl"] as? com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl<*>
            if (impl != null && cur.interactiveSurfaceSpec == null) {
                highestContainer = impl
            }
            cur = cur.parentWidget
        }
        return highestContainer
    }

    fun requestOverlayRelayout(widget: DesktopNativeWidget) {
        val overlayRoot = resolveTopIsolatedOverlayContainer(widget)
        if (overlayRoot == null) {
            requestStageRedraw()
            return
        }
        withOverlayRelayoutLock {
            pendingOverlayRelayoutRoots.add(overlayRoot)
        }
        if (overlayRelayoutDispatchScheduled.compareAndSet(0, 1)) {
            dispatch_async(dispatch_get_main_queue()) {
                overlayRelayoutDispatchScheduled.store(0)
                flushPendingOverlayRelayout()
            }
        }
    }

    private fun flushPendingOverlayRelayout() {
        val roots = withOverlayRelayoutLock {
            if (pendingOverlayRelayoutRoots.isEmpty()) return
            pendingOverlayRelayoutRoots.toList().also {
                pendingOverlayRelayoutRoots.clear()
            }
        }
        for (overlayRoot in roots) {
            if (overlayRoot.isDisposed) continue
            val bounds = overlayRoot.nativeWidget.bounds
            if (bounds.width > 0 && bounds.height > 0) {
                overlayRoot.layoutChildrenInBounds(bounds.width.toFloat(), bounds.height.toFloat())
                val view = (overlayRoot.nativeWidget as? MacosViewHandle)?.nsView
                view?.layoutSubtreeIfNeeded()
                view?.setNeedsDisplay(true)
                view?.displayIfNeeded()
            }
        }
    }

    fun invalidateAllWindows(stageOnly: Boolean = false) {
        for (session in sessions.values) {
            if (stageOnly) {
                session.needsStageOnlyRedisplay = true
            } else {
                session.needsRelayout = true
                session.needsRedisplay = true
            }
        }
    }

    fun forceSynchronousPaint(stageOnly: Boolean = false) {
        flushPendingOverlayRelayout()
        for (session in sessions.values) {
            flushSessionDisplayNow(session, stageOnly = stageOnly)
        }
    }

    fun flushPendingDisplay() {
        flushPendingOverlayRelayout()
        for (session in sessions.values) {
            if (session.needsRelayout || session.needsRedisplay || session.needsStageOnlyRedisplay) {
                flushSessionDisplayNow(
                    session,
                    stageOnly = session.needsStageOnlyRedisplay && !session.needsRelayout && !session.needsRedisplay
                )
            }
        }
    }

    private fun flushSessionDisplayNow(session: MacosWindowSession, stageOnly: Boolean) {
        val start = TimeSource.Monotonic.markNow()
        if (stageOnly && !session.needsRelayout && !session.needsRedisplay) {
            session.presentStageSurfaces(forceRefresh = false)
            for (sv in session.stageViews) {
                sv.displayIfNeeded()
            }
            session.needsStageOnlyRedisplay = false
            val elapsedUs = start.elapsedNow().inWholeMicroseconds.coerceAtLeast(1L)
            recordStageOnlyPaint(elapsedUs)
            return
        }

        if (!session.needsRelayout) {
            session.presentStageSurfaces(forceRefresh = false)
            if (!session.nsWindow.inLiveResize && (session.nsWindow as? MacosHostWindow)?.isPerformingNativeZoom != true) {
                MacosSurfaceLayerSupport.refreshBackdropViewsForWindow(session.nsWindow, force = false)
            }
            session.rootView.displayIfNeeded()
            session.needsRedisplay = false
            session.needsStageOnlyRedisplay = false
            val elapsedUs = start.elapsedNow().inWholeMicroseconds.coerceAtLeast(1L)
            recordFullWindowPaint(elapsedUs)
            return
        }

        val (cw, ch) = session.currentClientSize()
        val mutVer = DesktopNativeWidget.globalLayoutMutationVersion
        val layoutUpToDate = session.lastLaidOutWidth == cw &&
            session.lastLaidOutHeight == ch &&
            session.lastMutationVersion == mutVer
        if (layoutUpToDate) {
            session.needsRelayout = false
        } else {
            session.layoutWindowTree(force = true)
        }
        session.presentStageSurfaces(forceRefresh = false)
        if (!session.nsWindow.inLiveResize && (session.nsWindow as? MacosHostWindow)?.isPerformingNativeZoom != true) {
            MacosSurfaceLayerSupport.refreshBackdropViewsForWindow(session.nsWindow, force = false)
        }
        session.rootView.setNeedsDisplay(true)
        session.rootView.displayIfNeeded()
        session.needsRelayout = false
        session.needsRedisplay = false
        session.needsStageOnlyRedisplay = false
        val elapsedUs = start.elapsedNow().inWholeMicroseconds.coerceAtLeast(1L)
        recordFullWindowPaint(elapsedUs)
    }

    private fun recordStageOnlyPaint(us: Long) {
        totalPaintsCount++
        stageOnlyPaintsCount++
        paintDurationsUs.add(us)
    }

    private fun recordFullWindowPaint(us: Long) {
        totalPaintsCount++
        fullWindowPaintsCount++
        paintDurationsUs.add(us)
    }

    fun resetFrameMetrics() {
        totalPaintsCount = 0L
        stageOnlyPaintsCount = 0L
        fullWindowPaintsCount = 0L
        paintDurationsUs.clear()
    }

    fun getFrameMetricsSnapshot(): MacosFrameMetricsSnapshot {
        if (paintDurationsUs.isEmpty()) {
            return MacosFrameMetricsSnapshot(
                totalPaints = totalPaintsCount,
                stageOnlyPaints = stageOnlyPaintsCount,
                fullWindowPaints = fullWindowPaintsCount,
                sampleCount = 0,
                p50PaintUs = 0L,
                p95PaintUs = 0L,
                maxPaintUs = 0L,
                avgPaintUs = 0L
            )
        }
        val sorted = paintDurationsUs.sorted()
        val p50 = sorted[((sorted.size - 1) * 0.50).roundToInt().coerceIn(0, sorted.lastIndex)]
        val p95 = sorted[((sorted.size - 1) * 0.95).roundToInt().coerceIn(0, sorted.lastIndex)]
        val maxV = sorted.last()
        val avgV = sorted.sum() / sorted.size
        return MacosFrameMetricsSnapshot(
            totalPaints = totalPaintsCount,
            stageOnlyPaints = stageOnlyPaintsCount,
            fullWindowPaints = fullWindowPaintsCount,
            sampleCount = sorted.size,
            p50PaintUs = p50,
            p95PaintUs = p95,
            maxPaintUs = maxV,
            avgPaintUs = avgV
        )
    }

    // -------------------------------------------------------------------------
    // Visual Runner Helpers, Input Simulation & Screen Capture
    // -------------------------------------------------------------------------

    fun queryClientRect(): Rect {
        val session = activeSession() ?: return Rect(0, 0, 0, 0)
        val (w, h) = session.currentClientSize()
        return Rect(0, 0, w, h)
    }

    fun pumpVisualWindowEvents(durationMs: Long = 32L) {
        val endMark = TimeSource.Monotonic.markNow()
        val targetMs = durationMs.coerceAtLeast(1L)
        GenericUiThreading.flushAllQueues()
        var lastVBlankElapsedMs = -16L
        do {
            autoreleasepool {
                val app = NSApplication.sharedApplication
                while (true) {
                    val ev = app.nextEventMatchingMask(
                        mask = NSEventMaskAny,
                        untilDate = null,
                        inMode = NSDefaultRunLoopMode,
                        dequeue = true
                    ) ?: break
                    if (visualTestMode) {
                        val evType = ev.type
                        if (evType == NSEventTypeMouseMoved ||
                            evType == NSEventTypeMouseEntered ||
                            evType == NSEventTypeMouseExited ||
                            evType == NSEventTypeCursorUpdate
                        ) {
                            continue
                        }
                    }
                    app.sendEvent(ev)
                }
                NSRunLoop.currentRunLoop.runMode(
                    NSDefaultRunLoopMode,
                    beforeDate = NSDate.dateWithTimeIntervalSinceNow(0.004)
                )
                GenericUiThreading.flushAllQueues()
                val elapsed = endMark.elapsedNow().inWholeMilliseconds
                if (elapsed - lastVBlankElapsedMs >= 16L) {
                    lastVBlankElapsedMs = elapsed
                    dispatchActiveVBlankWidgets()
                    flushPendingOverlayRelayout()
                    for (session in sessions.values.toList()) {
                        session.presentStageSurfaces(forceRefresh = false)
                    }
                }
            }
        } while (endMark.elapsedNow().inWholeMilliseconds < targetMs)
        dispatchActiveVBlankWidgets()
        GenericUiThreading.flushAllQueues()
        flushPendingOverlayRelayout()
        syncAllNativeOverlayTitleBars()
        for (session in sessions.values.toList()) {
            session.presentStageSurfaces(forceRefresh = false)
        }
    }

    fun closeAllVisualWindows() {
        for (session in sessions.values.toList()) {
            session.isClosing = true
            resetUndecoratedResizeState(session)
            (session.nsWindow as? MacosDialogPanel)?.detachForClose()
            (session.nsWindow as? MacosHostWindow)?.let { hostWin ->
                hostWin.isClosingWindow = true
                hostWin.onMaximizedStateChanged = null
            }
            session.nsWindow.setDelegate(null)
            session.nsWindow.orderOut(null)
            session.nsWindow.close()
            session.isShown = false
        }
        sessions.clear()
        activeKeyWindowId = null
        activeApplicationMenuImpl = null
        activeApplicationTitle = null
        activeWindowMenuBarImpl = null
        mainMenuInstalled = false
        mainMenuDirty = true
        mainMenuInstallCountForTest = 0
        syncHostVBlankWatcher()
        PopupMouseCaptureManager.clear()
        PointerDragManager.cancelActiveDrag()
        syncActivationPolicyForCurrentState()
    }

    fun simulateMouseMove(x: Float, y: Float, flushDisplay: Boolean = true) {
        val session = activeSession() ?: return
        session.layoutWindowTree(force = false)
        val dx = x.toDouble()
        val dy = y.toDouble()
        val hit = session.rootView.hitTestClientPoint(dx, dy)
        val winLoc = session.rootView.convertClientPointToWindow(dx, dy)
        val prev = session.lastHoverTargetView
        session.rootView.suppressCursorSync = true
        try {
            if (prev !== hit) {
                fun chainToRoot(leaf: NSView?): List<NSView> {
                    if (leaf == null) return emptyList()
                    val chain = ArrayList<NSView>(12)
                    var cur: NSView? = leaf
                    while (cur != null) {
                        chain.add(cur)
                        if (cur === session.rootView) break
                        cur = cur.superview
                    }
                    return chain
                }
                val prevChain = chainToRoot(prev)
                val hitChain = chainToRoot(hit)
                val hitSet = hitChain.toHashSet()
                var lca: NSView? = null
                for (v in prevChain) {
                    if (v in hitSet) {
                        lca = v
                        break
                    }
                }
                if (prevChain.isNotEmpty()) {
                    synthesizeMouseEvent(NSEventTypeMouseMoved, winLoc, session.nsWindow.windowNumber, clickCount = 0)?.let { ev ->
                        for (v in prevChain) {
                            if (v === lca) break
                            v.mouseExited(ev)
                        }
                    }
                }
                if (hitChain.isNotEmpty()) {
                    val enterList = ArrayList<NSView>(hitChain.size)
                    for (v in hitChain) {
                        if (v === lca) break
                        enterList.add(v)
                    }
                    if (enterList.isNotEmpty()) {
                        synthesizeMouseEvent(NSEventTypeMouseMoved, winLoc, session.nsWindow.windowNumber, clickCount = 0)?.let { ev ->
                            for (i in enterList.indices.reversed()) {
                                enterList[i].mouseEntered(ev)
                            }
                        }
                    }
                }
                session.lastHoverTargetView = hit
            }
            synthesizeMouseEvent(NSEventTypeMouseMoved, winLoc, session.nsWindow.windowNumber, clickCount = 0)?.let { ev ->
                (hit ?: session.rootView).mouseMoved(ev)
            }
        } finally {
            session.rootView.suppressCursorSync = false
        }
        session.rootView.updateEffectiveCursorForResolvedHit(hit, dx, dy, forceCheckCurrent = true)
        GenericUiThreading.flushAllQueues()
        if (flushDisplay) {
            flushPendingDisplay()
        }
    }

    fun simulateMouseClick(x: Float, y: Float, button: Int = 0, clickCount: Int = 1) {
        val session = activeSession() ?: return
        session.layoutWindowTree(force = false)
        val dx = x.toDouble()
        val dy = y.toDouble()
        val hit = session.rootView.hitTestClientPoint(dx, dy) ?: session.rootView
        val winLoc = session.rootView.convertClientPointToWindow(dx, dy)
        val downType = if (button == 2) NSEventTypeRightMouseDown else NSEventTypeLeftMouseDown
        val upType = if (button == 2) NSEventTypeRightMouseUp else NSEventTypeLeftMouseUp
        val downEv = synthesizeMouseEvent(downType, winLoc, session.nsWindow.windowNumber, clickCount = clickCount)
        val upEv = synthesizeMouseEvent(upType, winLoc, session.nsWindow.windowNumber, clickCount = clickCount)

        if (button == 2) {
            if (downEv != null) hit.rightMouseDown(downEv)
            if (upEv != null) hit.rightMouseUp(upEv)
        } else if (hit is NSButton && hit.isEnabled() && !hit.isHidden()) {
            hit.performClick(null)
        } else {
            if (downEv != null) hit.mouseDown(downEv)
            GenericUiThreading.flushAllQueues()
            if (upEv != null) hit.mouseUp(upEv)
        }
        GenericUiThreading.flushAllQueues()
    }

    fun simulateMouseDrag(
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        steps: Int = 5,
        button: Int = 0
    ) {
        val session = activeSession() ?: return
        session.layoutWindowTree(force = false)
        val startTarget = session.rootView.hitTestClientPoint(fromX.toDouble(), fromY.toDouble()) ?: session.rootView
        val downLoc = session.rootView.convertClientPointToWindow(fromX.toDouble(), fromY.toDouble())
        val downType = if (button == 2) NSEventTypeRightMouseDown else NSEventTypeLeftMouseDown
        val upType = if (button == 2) NSEventTypeRightMouseUp else NSEventTypeLeftMouseUp

        synthesizeMouseEvent(downType, downLoc, session.nsWindow.windowNumber, clickCount = 1)?.let { ev ->
            startTarget.mouseDown(ev)
        }

        val count = steps.coerceAtLeast(1)
        for (i in 1..count) {
            val t = i.toDouble() / count.toDouble()
            val curX = fromX.toDouble() + (toX.toDouble() - fromX.toDouble()) * t
            val curY = fromY.toDouble() + (toY.toDouble() - fromY.toDouble()) * t
            val loc = session.rootView.convertClientPointToWindow(curX, curY)
            synthesizeMouseEvent(NSEventTypeLeftMouseDragged, loc, session.nsWindow.windowNumber, clickCount = 1)?.let { ev ->
                startTarget.mouseDragged(ev)
            }
            GenericUiThreading.flushAllQueues()
        }

        val upLoc = session.rootView.convertClientPointToWindow(toX.toDouble(), toY.toDouble())
        synthesizeMouseEvent(upType, upLoc, session.nsWindow.windowNumber, clickCount = 1)?.let { ev ->
            startTarget.mouseUp(ev)
        }
        GenericUiThreading.flushAllQueues()
    }

    fun simulateScrollWheel(x: Float, y: Float, deltaPx: Float, flushDisplay: Boolean = true) {
        val session = activeSession() ?: return
        session.layoutWindowTree(force = false)
        var cur: NSView? = session.rootView.hitTestClientPoint(x.toDouble(), y.toDouble()) ?: session.rootView
        var scrollHandled = false
        while (cur != null) {
            if (cur is NSScrollView) {
                val clip = cur.contentView()
                val doc = cur.documentView
                if (doc != null) {
                    val clipBounds = clip.bounds
                    val docFrame = doc.frame
                    val (curX, curY, clipW, clipH) = clipBounds.useContents {
                        listOf(origin.x, origin.y, size.width, size.height)
                    }
                    val (docW, docH) = docFrame.useContents { size.width to size.height }
                    val maxScrollY = (docH - clipH).coerceAtLeast(0.0)
                    val maxScrollX = (docW - clipW).coerceAtLeast(0.0)
                    val nextY = (curY + deltaPx.toDouble()).coerceIn(0.0, maxScrollY)
                    val nextX = curX.coerceIn(0.0, maxScrollX)
                    clip.scrollToPoint(NSMakePoint(nextX, nextY))
                    cur.reflectScrolledClipView(clip)
                    scrollHandled = true
                }
                break
            }
            cur = cur.superview
        }
        if (!scrollHandled) {
            session.hostContainer()?.dispatchWheel(
                com.nexustools.genericui.event.WheelEvent(
                    x = x,
                    y = y,
                    deltaX = 0f,
                    deltaY = deltaPx
                )
            )
        }
        GenericUiThreading.flushAllQueues()
        if (flushDisplay) {
            flushPendingDisplay()
        }
    }

    private fun synthesizeMouseEvent(
        type: ULong,
        windowLocation: CValue<platform.CoreGraphics.CGPoint>,
        windowNumber: Long,
        clickCount: Int
    ): NSEvent? = NSEvent.mouseEventWithType(
        type = type,
        location = windowLocation,
        modifierFlags = 0uL,
        timestamp = 0.0,
        windowNumber = windowNumber,
        context = null,
        eventNumber = 0L,
        clickCount = clickCount.toLong(),
        pressure = if (type == NSEventTypeLeftMouseUp || type == NSEventTypeRightMouseUp) 0f else 1f
    )

    // -------------------------------------------------------------------------
    // Offscreen / Backing AppKit Window Screenshot Capture
    // -------------------------------------------------------------------------

    fun captureWindowScreenshot(width: Int = 0, height: Int = 0): ImageBuffer? {
        val session = activeSession() ?: return null
        val (cw, ch) = session.currentClientSize()
        val targetW = if (width > 0) width else cw
        val targetH = if (height > 0) height else ch
        val winImpl = session.windowImpl
        if (winImpl != null) {
            return captureWindowScreenshot(winImpl, targetW, targetH)
        }
        session.layoutWindowTree(force = true)
        session.presentStageSurfaces(forceRefresh = true)
        return captureRootViewWithBackdropFilters(session.rootView, targetW, targetH)
    }

    fun captureWindowScreenshot(winImpl: MacosWindowNativeImpl, width: Int = 0, height: Int = 0): ImageBuffer? {
        val existing = sessions.values.firstOrNull { it.windowImpl === winImpl }
        val targetW = if (width > 0) width else winImpl.getWindowSize().width.toInt().coerceAtLeast(320)
        val targetH = if (height > 0) height else winImpl.getWindowSize().height.toInt().coerceAtLeast(240)

        if (existing != null) {
            val origFrame = existing.nsWindow.frame
            val (origW, origH) = existing.currentClientSize()
            if (origW != targetW || origH != targetH) {
                existing.nsWindow.setContentSize(NSMakeSize(targetW.toDouble(), targetH.toDouble()))
            }
            val hostWin = existing.nsWindow as? MacosHostWindow
            val prevForceKey = hostWin?.forceActiveKeyAppearanceForCapture ?: false
            if (visualTestMode && hostWin != null) {
                hostWin.forceActiveKeyAppearanceForCapture = true
            }
            return try {
                existing.layoutWindowTree(force = true)
                existing.presentStageSurfaces(forceRefresh = true)
                captureRootViewWithBackdropFilters(existing.rootView, targetW, targetH)
            } finally {
                if (hostWin != null) {
                    hostWin.forceActiveKeyAppearanceForCapture = prevForceKey
                }
                if (origW != targetW || origH != targetH) {
                    existing.nsWindow.setFrame(origFrame, display = false)
                    existing.layoutWindowTree(force = true)
                }
            }
        }

        val tmpKey = --syntheticKeySeq
        val offscreenWin = MacosOffscreenCaptureWindow(
            contentRect = NSMakeRect(0.0, 0.0, targetW.toDouble(), targetH.toDouble()),
            styleMask = MacosViewHandle.NSWindowStyleMaskBorderless.toULong(),
            backing = NSBackingStoreBuffered,
            defer = false
        )
        offscreenWin.setReleasedWhenClosed(false)
        offscreenWin.appearance = NSApplication.sharedApplication.appearance()
            ?: NSAppearance.appearanceNamed(
                if (MacosAppKitAppearance.isDarkTheme(winImpl.nativeWidget)) {
                    NSAppearanceNameDarkAqua
                } else {
                    NSAppearanceNameAqua
                }
            )
        val rootView = MacosWindowRootView(
            windowKey = tmpKey,
            frameRect = NSMakeRect(0.0, 0.0, targetW.toDouble(), targetH.toDouble())
        )
        offscreenWin.setContentView(rootView)
        val delegate = MacosWindowDelegate(windowKey = tmpKey, isDialog = false)
        val tmpSession = MacosWindowSession(
            windowKey = tmpKey,
            nsWindow = offscreenWin,
            rootView = rootView,
            delegate = delegate,
            isDialog = false,
            windowImpl = winImpl
        )
        return try {
            tmpSession.layoutWindowTree(force = true)
            tmpSession.presentStageSurfaces(forceRefresh = true)
            captureRootViewWithBackdropFilters(rootView, targetW, targetH)
        } finally {
            for (sub in rootView.subviews.filterIsInstance<NSView>()) {
                sub.removeFromSuperview()
            }
            offscreenWin.close()
        }
    }

    private fun captureRootViewWithBackdropFilters(
        rootView: MacosWindowRootView,
        width: Int,
        height: Int
    ): ImageBuffer? {
        if (width <= 0 || height <= 0) return null
        if (!rootView.inLayoutCallback) {
            rootView.layoutSubtreeIfNeeded()
        }
        val basePremulRgba = renderViewToPremulRgba(rootView, width, height) ?: return null

        val backdropTargets = collectVisibleBackdropViews(rootView)
        if (backdropTargets.isNotEmpty()) {
            val premulBgra = convertPremulRgbaToPremulBgraInts(basePremulRgba, width, height)
            for ((view, support) in backdropTargets) {
                val filter = support.activeBackdropFilter ?: continue
                if (filter.isNone || !filter.hasActiveEffects) continue

                val rectInRoot = view.convertRect(view.bounds, toView = rootView)
                val rx = rectInRoot.useContents { origin.x.roundToInt() }.coerceIn(0, width)
                val ry = rectInRoot.useContents { origin.y.roundToInt() }.coerceIn(0, height)
                val rw = rectInRoot.useContents { size.width.roundToInt() }.coerceIn(0, width - rx)
                val rh = rectInRoot.useContents { size.height.roundToInt() }.coerceIn(0, height - ry)
                if (rw <= 0 || rh <= 0) continue

                val prevAlpha = view.alphaValue
                val hiddenViews = ArrayList<NSView>()
                fun tempHide(targetView: NSView) {
                    if (!targetView.isHidden()) {
                        targetView.setHidden(true)
                        hiddenViews.add(targetView)
                    }
                }
                tempHide(view)
                support.backdropUnderlayView?.let { tempHide(it) }
                // Preceding-paint-order isolation: hide siblings/branches painted AFTER `view`.
                var cur: NSView = view
                while (cur !== rootView) {
                    val parent = cur.superview ?: break
                    val subs = parent.subviews
                    val curIdx = subs.indexOf(cur)
                    val curZ = cur.layer?.zPosition ?: 0.0
                    for (i in subs.indices) {
                        val sib = subs[i] as? NSView ?: continue
                        if (sib === cur) continue
                        val sibZ = sib.layer?.zPosition ?: 0.0
                        if (sibZ > curZ || (sibZ == curZ && i > curIdx)) {
                            tempHide(sib)
                        }
                    }
                    cur = parent
                }
                val underlayBytes = try {
                    renderViewToPremulRgba(rootView, width, height)
                } finally {
                    for (i in hiddenViews.lastIndex downTo 0) {
                        hiddenViews[i].setHidden(false)
                    }
                    view.setAlphaValue(prevAlpha)
                }

                if (underlayBytes != null) {
                    val underlayBgra = convertPremulRgbaToPremulBgraInts(underlayBytes, width, height)
                    val radiusPx = support.activeCornerRadii?.topLeft?.resolvePx(rw.toFloat())
                        ?: support.activeFallbackRadiusPx
                    AppleBackdropFilterController.applyBackdropToPremulBgraRegion(
                        filter = filter,
                        pixels = underlayBgra,
                        strideWords = width,
                        bufferWidth = width,
                        bufferHeight = height,
                        regionX = rx,
                        regionY = ry,
                        regionWidth = rw,
                        regionHeight = rh,
                        cornerRadiusPx = radiusPx,
                        componentOpacity = prevAlpha.toFloat()
                    )
                    // Copy filtered backdrop region into destination premulBgra and blend view surface + children on top.
                    val underlay = support.backdropUnderlayView
                    val underlayWasHidden = underlay?.isHidden() ?: false
                    underlay?.setHidden(true)
                    val fgBytes = try {
                        captureIsolatedSubtreeBytes(view, rootView, width, height)
                    } finally {
                        underlay?.setHidden(underlayWasHidden)
                    }
                    compositeFilteredRegionAndForeground(
                        dstBgra = premulBgra,
                        filteredUnderlayBgra = underlayBgra,
                        foregroundPremulRgba = fgBytes,
                        width = width,
                        rx = rx,
                        ry = ry,
                        rw = rw,
                        rh = rh,
                        radiusPx = radiusPx
                    )
                }
            }
            val finalRgba = unpremultiplyBgraIntsToRgbaBytes(premulBgra, width, height)
            return ImageBuffer(width, height, PixelFormat.RGBA_8888, ImageColorSpace.SRGB, finalRgba)
        }

        val unpremul = unpremultiplyRgbaBytes(basePremulRgba, width * height)
        return ImageBuffer(width, height, PixelFormat.RGBA_8888, ImageColorSpace.SRGB, unpremul)
    }

    private class MacosOffscreenCaptureWindow(
        contentRect: CValue<NSRect>,
        styleMask: platform.AppKit.NSWindowStyleMask,
        backing: platform.AppKit.NSBackingStoreType,
        defer: Boolean
    ) : NSWindow(
        contentRect = contentRect,
        styleMask = styleMask,
        backing = backing,
        defer = defer
    ) {
        override fun isKeyWindow(): Boolean = true
        override fun isMainWindow(): Boolean = true
        override fun canBecomeKeyWindow(): Boolean = true
        override fun canBecomeMainWindow(): Boolean = true
    }

    private class MacosSnapshotStageBlitView(
        private val cgImage: platform.CoreGraphics.CGImageRef?,
        private val spec: com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec?,
        frameRect: CValue<NSRect>
    ) : NSView(frameRect) {

        override fun isFlipped(): Boolean = false

        override fun drawRect(dirtyRect: CValue<NSRect>) {
            val (w, h) = bounds.useContents { size.width to size.height }
            if (w <= 0.0 || h <= 0.0) return
            val ctx = NSGraphicsContext.currentContext?.CGContext ?: return
            platform.CoreGraphics.CGContextSetRGBFillColor(ctx, 0.0, 0.0, 0.0, 1.0)
            platform.CoreGraphics.CGContextFillRect(ctx, CGRectMake(0.0, 0.0, w, h))
            val img = cgImage ?: return
            val imgW = platform.CoreGraphics.CGImageGetWidth(img).toDouble()
            val imgH = platform.CoreGraphics.CGImageGetHeight(img).toDouble()
            if (imgW <= 0.0 || imgH <= 0.0) return
            val srcW = imgW.toInt().coerceAtLeast(1)
            val srcH = imgH.toInt().coerceAtLeast(1)
            val rect = if (spec != null) {
                com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec.computePresentationRect(
                    w, h, srcW, srcH, spec
                )
            } else {
                com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec.computePresentationRect(
                    w, h, srcW, srcH, (imgW / imgH).toFloat(), com.nexustools.genericui.enums.ui.ObjectFit.CONTAIN
                )
            }
            val cgDstY = h - rect.dstY - rect.dstH
            CGContextSaveGState(ctx)
            try {
                platform.CoreGraphics.CGContextClipToRect(ctx, CGRectMake(0.0, 0.0, w, h))
                CGContextDrawImage(ctx, CGRectMake(rect.dstX, cgDstY, rect.dstW, rect.dstH), img)
            } finally {
                CGContextRestoreGState(ctx)
            }
        }
    }

    private fun renderViewToPremulRgba(view: NSView, width: Int, height: Int): ByteArray? {
        val tempStageBlits = ArrayList<MacosSnapshotStageBlitView>()
        fun injectStageBlits(node: NSView) {
            if (node.isHidden() || node.alphaValue <= 0.004) return
            if (node is MacosInteractiveSurfaceView) {
                val blit = MacosSnapshotStageBlitView(
                    cgImage = node.currentCgImage(),
                    spec = node.currentSpec(),
                    frameRect = node.bounds
                )
                val firstSub = node.subviews.firstOrNull() as? NSView
                if (firstSub != null) {
                    node.addSubview(blit, positioned = platform.AppKit.NSWindowBelow, relativeTo = firstSub)
                } else {
                    node.addSubview(blit)
                }
                tempStageBlits.add(blit)
                return
            }
            for (sub in node.subviews) {
                if (sub is NSView) injectStageBlits(sub)
            }
        }
        injectStageBlits(view)
        val captureBounds = NSMakeRect(0.0, 0.0, width.toDouble(), height.toDouble())
        val rep = view.bitmapImageRepForCachingDisplayInRect(captureBounds)
        if (rep == null) {
            for (blit in tempStageBlits) blit.removeFromSuperview()
            return null
        }
        view.cacheDisplayInRect(captureBounds, toBitmapImageRep = rep)
        val cgImg = rep.CGImage
        if (cgImg == null) {
            for (blit in tempStageBlits) blit.removeFromSuperview()
            return null
        }

        val colorSpace = CGColorSpaceCreateDeviceRGB()
        if (colorSpace == null) {
            for (blit in tempStageBlits) blit.removeFromSuperview()
            return null
        }
        val bitmapInfo = kCGBitmapByteOrder32Big or CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
        val ctx = CGBitmapContextCreate(
            data = null,
            width = width.toULong(),
            height = height.toULong(),
            bitsPerComponent = 8uL,
            bytesPerRow = (width * 4).toULong(),
            space = colorSpace,
            bitmapInfo = bitmapInfo
        )
        CGColorSpaceRelease(colorSpace)
        if (ctx == null) {
            for (blit in tempStageBlits) blit.removeFromSuperview()
            return null
        }
        return try {
            CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), cgImg)
            compositeVisibleScrollers(view, ctx, height)
            val dataPtr = CGBitmapContextGetData(ctx)?.reinterpret<ByteVar>() ?: return null
            val totalBytes = width * height * 4
            val out = ByteArray(totalBytes)
            out.usePinned { pinned ->
                platform.posix.memcpy(pinned.addressOf(0), dataPtr, totalBytes.toULong())
            }
            out
        } finally {
            for (blit in tempStageBlits) blit.removeFromSuperview()
            CGContextRelease(ctx)
        }
    }

    private fun compositeVisibleScrollers(rootView: NSView, ctx: CGContextRef, height: Int) {
        val rootH = rootView.bounds.useContents { size.height }
        val rootFlipped = rootView.isFlipped()
        fun walk(v: NSView) {
            if (v.isHidden() || v.alphaValue <= 0.004) return
            val scroller = v as? NSScroller
            if (scroller != null && scroller.isEnabled() && scroller.knobProportion > 0.0 && scroller.knobProportion < 1.0) {
                val rInRoot = scroller.convertRect(scroller.bounds, toView = rootView)
                val (rx, ry, rh) = rInRoot.useContents { Triple(origin.x, origin.y, size.height) }
                val topDownY = if (rootFlipped) ry else (rootH - ry - rh)
                CGContextSaveGState(ctx)
                CGContextTranslateCTM(ctx, rx, height.toDouble() - topDownY)
                CGContextScaleCTM(ctx, 1.0, -1.0)
                NSGraphicsContext.saveGraphicsState()
                NSGraphicsContext.setCurrentContext(
                    NSGraphicsContext.graphicsContextWithCGContext(ctx, flipped = scroller.isFlipped())
                )
                scroller.effectiveAppearance.performAsCurrentDrawingAppearance {
                    if (scroller.scrollerStyle == NSScrollerStyleLegacy) {
                        scroller.drawKnobSlotInRect(scroller.bounds, highlight = false)
                    }
                    scroller.drawKnob()
                }
                NSGraphicsContext.restoreGraphicsState()
                CGContextRestoreGState(ctx)
                return
            }
            for (sub in v.subviews.filterIsInstance<NSView>()) {
                walk(sub)
            }
        }
        walk(rootView)
    }

    private fun captureIsolatedSubtreeBytes(
        target: NSView,
        rootView: MacosWindowRootView,
        width: Int,
        height: Int
    ): ByteArray? {
        if (width <= 0 || height <= 0 || target.isHidden() || target.alphaValue <= 0.004) return null
        val targetBounds = target.bounds
        val rectInRoot = target.convertRect(targetBounds, toView = rootView)
        val (rx, ry, rw, rh) = rectInRoot.useContents {
            doubleArrayOf(origin.x, origin.y, size.width, size.height)
        }
        if (rw <= 0.0 || rh <= 0.0) return null

        val rep = target.bitmapImageRepForCachingDisplayInRect(targetBounds) ?: return null
        target.cacheDisplayInRect(targetBounds, toBitmapImageRep = rep)
        val cgImg = rep.CGImage ?: return null

        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
        val bitmapInfo = kCGBitmapByteOrder32Big or CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
        val ctx = CGBitmapContextCreate(
            data = null,
            width = width.toULong(),
            height = height.toULong(),
            bitsPerComponent = 8uL,
            bytesPerRow = (width * 4).toULong(),
            space = colorSpace,
            bitmapInfo = bitmapInfo
        )
        CGColorSpaceRelease(colorSpace)
        if (ctx == null) return null
        return try {
            CGContextClearRect(ctx, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
            val alpha = target.alphaValue.coerceIn(0.0, 1.0)
            if (alpha < 0.999) {
                CGContextSetAlpha(ctx, alpha)
            }
            val topDownY = if (rootView.isFlipped()) ry else (rootView.bounds.useContents { size.height } - ry - rh)
            val cgDstY = height.toDouble() - topDownY - rh
            CGContextDrawImage(ctx, CGRectMake(rx, cgDstY, rw, rh), cgImg)
            val dataPtr = CGBitmapContextGetData(ctx)?.reinterpret<ByteVar>() ?: return null
            val totalBytes = width * height * 4
            val out = ByteArray(totalBytes)
            out.usePinned { pinned ->
                platform.posix.memcpy(pinned.addressOf(0), dataPtr, totalBytes.toULong())
            }
            out
        } finally {
            CGContextRelease(ctx)
        }
    }

    private fun isDescendant(candidateDescendant: NSView, ancestor: NSView): Boolean {
        var cur: NSView? = candidateDescendant
        while (cur != null) {
            if (cur == ancestor) return true
            cur = cur.superview
        }
        return false
    }

    private fun resolveSurfaceLayerSupport(view: NSView): MacosSurfaceLayerSupport? = when (view) {
        is MacosContainerView -> view.surfaceSupport
        is MacosStyledContainerView -> view.surfaceSupport
        is MacosStyledComponentView -> view.surfaceSupport
        is MacosStyledButtonControl -> view.surfaceSupport
        is com.nexustools.genericui.macos.impl.MacosStyledTextInputHostView -> view.surfaceSupport
        is com.nexustools.genericui.macos.impl.MacosNavbarView -> view.surfaceSupport
        else -> null
    }

    private fun collectVisibleBackdropViews(root: NSView): List<Pair<NSView, MacosSurfaceLayerSupport>> {
        val result = ArrayList<Pair<NSView, MacosSurfaceLayerSupport>>()
        fun walk(v: NSView) {
            if (v.isHidden() || v.alphaValue <= 0.004) return
            val support = resolveSurfaceLayerSupport(v)
            if (support != null && support.activeBackdropFilter != null) {
                result.add(v to support)
            }
            for (sub in v.subviews.filterIsInstance<NSView>()) {
                walk(sub)
            }
        }
        walk(root)
        return result
    }

    private fun convertPremulRgbaToPremulBgraInts(rgba: ByteArray, w: Int, h: Int): IntArray {
        val count = w * h
        val out = IntArray(count)
        for (i in 0 until count) {
            val o = i * 4
            val r = rgba[o].toInt() and 0xFF
            val g = rgba[o + 1].toInt() and 0xFF
            val b = rgba[o + 2].toInt() and 0xFF
            val a = rgba[o + 3].toInt() and 0xFF
            out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return out
    }

    private fun compositeFilteredRegionAndForeground(
        dstBgra: IntArray,
        filteredUnderlayBgra: IntArray,
        foregroundPremulRgba: ByteArray?,
        width: Int,
        rx: Int,
        ry: Int,
        rw: Int,
        rh: Int,
        radiusPx: Float
    ) {
        val rLim = radiusPx.coerceAtLeast(0f).toDouble()
        for (py in ry until (ry + rh)) {
            val rowOff = py * width
            for (px in rx until (rx + rw)) {
                if (rLim > 0.5 && !isPointInsideRoundedBox(px - rx, py - ry, rw, rh, rLim)) {
                    continue
                }
                val idx = rowOff + px
                var bg = filteredUnderlayBgra[idx]
                if (foregroundPremulRgba != null) {
                    val o = idx * 4
                    val sr = foregroundPremulRgba[o].toInt() and 0xFF
                    val sg = foregroundPremulRgba[o + 1].toInt() and 0xFF
                    val sb = foregroundPremulRgba[o + 2].toInt() and 0xFF
                    val sa = foregroundPremulRgba[o + 3].toInt() and 0xFF
                    if (sa > 0) {
                        val invA = 255 - sa
                        val dr = (bg ushr 16) and 0xFF
                        val dg = (bg ushr 8) and 0xFF
                        val db = bg and 0xFF
                        val da = (bg ushr 24) and 0xFF
                        val outR = (sr + (dr * invA + 127) / 255).coerceIn(0, 255)
                        val outG = (sg + (dg * invA + 127) / 255).coerceIn(0, 255)
                        val outB = (sb + (db * invA + 127) / 255).coerceIn(0, 255)
                        val outA = (sa + (da * invA + 127) / 255).coerceIn(0, 255)
                        bg = (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
                    }
                }
                dstBgra[idx] = bg
            }
        }
    }

    private fun isPointInsideRoundedBox(lx: Int, ly: Int, w: Int, h: Int, r: Double): Boolean {
        val effR = min(r, min(w, h) * 0.5)
        val cx = when {
            lx < effR -> effR - lx
            lx >= w - effR -> lx - (w - effR) + 1.0
            else -> return true
        }
        val cy = when {
            ly < effR -> effR - ly
            ly >= h - effR -> ly - (h - effR) + 1.0
            else -> return true
        }
        return cx * cx + cy * cy <= effR * effR + 0.5
    }

    private fun unpremultiplyBgraIntsToRgbaBytes(bgra: IntArray, w: Int, h: Int): ByteArray {
        val count = w * h
        val out = ByteArray(count * 4)
        for (i in 0 until count) {
            val px = bgra[i]
            val a = (px ushr 24) and 0xFF
            var r = (px ushr 16) and 0xFF
            var g = (px ushr 8) and 0xFF
            var b = px and 0xFF
            if (a in 1..254) {
                r = ((r * 255 + a / 2) / a).coerceIn(0, 255)
                g = ((g * 255 + a / 2) / a).coerceIn(0, 255)
                b = ((b * 255 + a / 2) / a).coerceIn(0, 255)
            } else if (a == 0) {
                r = 0; g = 0; b = 0
            }
            val o = i * 4
            out[o] = r.toByte()
            out[o + 1] = g.toByte()
            out[o + 2] = b.toByte()
            out[o + 3] = a.toByte()
        }
        return out
    }

    private fun unpremultiplyRgbaBytes(src: ByteArray, pxCount: Int): ByteArray {
        val out = ByteArray(pxCount * 4)
        for (i in 0 until pxCount) {
            val o = i * 4
            var r = src[o].toInt() and 0xFF
            var g = src[o + 1].toInt() and 0xFF
            var b = src[o + 2].toInt() and 0xFF
            val a = src[o + 3].toInt() and 0xFF
            if (a in 1..254) {
                r = ((r * 255 + a / 2) / a).coerceIn(0, 255)
                g = ((g * 255 + a / 2) / a).coerceIn(0, 255)
                b = ((b * 255 + a / 2) / a).coerceIn(0, 255)
            } else if (a == 0) {
                r = 0; g = 0; b = 0
            }
            out[o] = r.toByte()
            out[o + 1] = g.toByte()
            out[o + 2] = b.toByte()
            out[o + 3] = a.toByte()
        }
        return out
    }

    // -------------------------------------------------------------------------
    // Undecorated 8-Edge Resize, Text Menu Commands & Test Registry
    // -------------------------------------------------------------------------

    private fun edgeToCssCursor(edge: UndecoratedResizeEdge): CssCursor = when (edge) {
        UndecoratedResizeEdge.N -> CssCursor.N_RESIZE
        UndecoratedResizeEdge.S -> CssCursor.S_RESIZE
        UndecoratedResizeEdge.E -> CssCursor.E_RESIZE
        UndecoratedResizeEdge.W -> CssCursor.W_RESIZE
        UndecoratedResizeEdge.NE -> CssCursor.NE_RESIZE
        UndecoratedResizeEdge.NW -> CssCursor.NW_RESIZE
        UndecoratedResizeEdge.SE -> CssCursor.SE_RESIZE
        UndecoratedResizeEdge.SW -> CssCursor.SW_RESIZE
        UndecoratedResizeEdge.NONE -> CssCursor.DEFAULT
    }

    internal fun onNativeWindowLiveResizeChanged(windowKey: Long, active: Boolean) {
        val session = sessions[windowKey] ?: return
        val rv = session.rootView
        rv.isInNativeLiveResize = active
        if (active) {
            val edge = if (session.activeResizeEdge != UndecoratedResizeEdge.NONE) {
                session.activeResizeEdge
            } else {
                val mousePt = NSEvent.mouseLocation
                val winPt = session.nsWindow.convertPointFromScreen(mousePt)
                val (lx, ly) = rv.convertPoint(winPt, fromView = null).useContents { x to y }
                val (bw, bh) = rv.bounds.useContents { size.width to size.height }
                detectUndecoratedResizeEdge(lx, ly, bw, bh, isWindowControlHit = false)
            }
            if (session.activeWindowResizeDragSession?.isActive != true) {
                session.activeWindowResizeDragSession = PointerDragManager.startWindowResizeDrag(
                    lockedCursor = edgeToCssCursor(edge)
                )
            }
            if (rv.lockedResizeCursor == null) {
                rv.lockedResizeCursor = if (edge != UndecoratedResizeEdge.NONE) {
                    resolveCursorForResizeEdge(edge).second
                } else {
                    NSCursor.currentCursor
                }
            }
            rv.lockedResizeCursor?.set()
        } else {
            val hostWin = session.nsWindow as? MacosHostWindow
            if (rv.activeCustomResizeEdge == UndecoratedResizeEdge.NONE) {
                session.activeWindowResizeDragSession?.dispose()
                session.activeWindowResizeDragSession = null
                rv.lockedResizeCursor = null
            }
            if (!session.isClosing &&
                !session.isSyncingSize &&
                hostWin?.isTransitioningFullscreen != true &&
                hostWin?.isAnimatingMaximize != true &&
                hostWin?.isPerformingNativeZoom != true
            ) {
                val (w, h) = session.currentClientSize()
                session.windowImpl?.notifyHostResizedFromOs(
                    widthPx = w,
                    heightPx = h,
                    fullscreenNow = session.activeFullscreen,
                    maximizedNow = session.nsWindow.isZoomed()
                )
            }
            refreshDeferredBackdropUnderlays(rv)
            session.layoutWindowTree(force = false)
        }
        session.nsWindow.invalidateCursorRectsForView(rv)
    }

    private fun resetUndecoratedResizeState(session: MacosWindowSession) {
        val hadState = session.activeResizeEdge != UndecoratedResizeEdge.NONE ||
            session.rootView.activeCustomResizeEdge != UndecoratedResizeEdge.NONE ||
            session.rootView.lockedResizeCursor != null ||
            session.activeWindowResizeDragSession?.isActive == true
        session.activeResizeEdge = UndecoratedResizeEdge.NONE
        session.resizeStartFrame = null
        session.activeWindowResizeDragSession?.dispose()
        session.activeWindowResizeDragSession = null
        session.rootView.activeCustomResizeEdge = UndecoratedResizeEdge.NONE
        if (!session.rootView.isInNativeLiveResize) {
            session.rootView.lockedResizeCursor = null
        }
        if (hadState) {
            session.nsWindow.invalidateCursorRectsForView(session.rootView)
        }
    }

    internal fun detectUndecoratedResizeEdge(
        viewX: Double,
        viewY: Double,
        viewWidth: Double,
        viewHeight: Double,
        isWindowControlHit: Boolean
    ): UndecoratedResizeEdge {
        if (viewWidth <= 0.0 || viewHeight <= 0.0) {
            return UndecoratedResizeEdge.NONE
        }
        if (viewX < 0.0 || viewX > viewWidth || viewY < 0.0 || viewY > viewHeight) {
            return UndecoratedResizeEdge.NONE
        }
        val edgeBand = if (isWindowControlHit) UNDECORATED_RESIZE_CONTROL_EDGE_PT else UNDECORATED_RESIZE_EDGE_PT
        val cornerBand = if (isWindowControlHit) UNDECORATED_RESIZE_CONTROL_EDGE_PT else UNDECORATED_RESIZE_CORNER_PT
        val onLeftEdge = viewX <= edgeBand
        val onRightEdge = viewX >= viewWidth - edgeBand
        val onTopEdge = viewY <= edgeBand
        val onBottomEdge = viewY >= viewHeight - edgeBand
        val inLeftCorner = viewX <= cornerBand
        val inRightCorner = viewX >= viewWidth - cornerBand
        val inTopCorner = viewY <= cornerBand
        val inBottomCorner = viewY >= viewHeight - cornerBand
        return when {
            (onTopEdge && inLeftCorner) || (onLeftEdge && inTopCorner) -> UndecoratedResizeEdge.NW
            (onTopEdge && inRightCorner) || (onRightEdge && inTopCorner) -> UndecoratedResizeEdge.NE
            (onBottomEdge && inLeftCorner) || (onLeftEdge && inBottomCorner) -> UndecoratedResizeEdge.SW
            (onBottomEdge && inRightCorner) || (onRightEdge && inBottomCorner) -> UndecoratedResizeEdge.SE
            onTopEdge -> UndecoratedResizeEdge.N
            onBottomEdge -> UndecoratedResizeEdge.S
            onLeftEdge -> UndecoratedResizeEdge.W
            onRightEdge -> UndecoratedResizeEdge.E
            else -> UndecoratedResizeEdge.NONE
        }
    }

    internal fun resolveCursorForResizeEdge(edge: UndecoratedResizeEdge): Pair<String, NSCursor> {
        val hasMacos15FrameCursors = NSProcessInfo.processInfo.operatingSystemVersion.useContents {
            majorVersion >= 15L
        }
        val cursor = when (edge) {
            UndecoratedResizeEdge.N -> if (hasMacos15FrameCursors) {
                NSCursor.frameResizeCursorFromPosition(NSCursorFrameResizePositionTop, NSCursorFrameResizeDirectionsAll)
            } else NSCursor.resizeUpDownCursor
            UndecoratedResizeEdge.S -> if (hasMacos15FrameCursors) {
                NSCursor.frameResizeCursorFromPosition(NSCursorFrameResizePositionBottom, NSCursorFrameResizeDirectionsAll)
            } else NSCursor.resizeUpDownCursor
            UndecoratedResizeEdge.W -> if (hasMacos15FrameCursors) {
                NSCursor.frameResizeCursorFromPosition(NSCursorFrameResizePositionLeft, NSCursorFrameResizeDirectionsAll)
            } else NSCursor.resizeLeftRightCursor
            UndecoratedResizeEdge.E -> if (hasMacos15FrameCursors) {
                NSCursor.frameResizeCursorFromPosition(NSCursorFrameResizePositionRight, NSCursorFrameResizeDirectionsAll)
            } else NSCursor.resizeLeftRightCursor
            UndecoratedResizeEdge.NW -> if (hasMacos15FrameCursors) {
                NSCursor.frameResizeCursorFromPosition(NSCursorFrameResizePositionTopLeft, NSCursorFrameResizeDirectionsAll)
            } else NSCursor.crosshairCursor
            UndecoratedResizeEdge.NE -> if (hasMacos15FrameCursors) {
                NSCursor.frameResizeCursorFromPosition(NSCursorFrameResizePositionTopRight, NSCursorFrameResizeDirectionsAll)
            } else NSCursor.crosshairCursor
            UndecoratedResizeEdge.SW -> if (hasMacos15FrameCursors) {
                NSCursor.frameResizeCursorFromPosition(NSCursorFrameResizePositionBottomLeft, NSCursorFrameResizeDirectionsAll)
            } else NSCursor.crosshairCursor
            UndecoratedResizeEdge.SE -> if (hasMacos15FrameCursors) {
                NSCursor.frameResizeCursorFromPosition(NSCursorFrameResizePositionBottomRight, NSCursorFrameResizeDirectionsAll)
            } else NSCursor.crosshairCursor
            UndecoratedResizeEdge.NONE -> NSCursor.arrowCursor
        }
        return "resize:${edge.name.lowercase()}" to cursor
    }

    internal fun computeResizedWindowFrame(
        startFrame: CValue<NSRect>,
        edge: UndecoratedResizeEdge,
        deltaScreenX: Double,
        deltaScreenY: Double,
        minSize: Size? = null,
        maxSize: Size? = null,
        aspectRatio: Double? = null
    ): CValue<NSRect> {
        var sx = 0.0
        var sy = 0.0
        var sw = 0.0
        var sh = 0.0
        startFrame.useContents {
            sx = origin.x; sy = origin.y; sw = size.width; sh = size.height
        }
        val minW = (minSize?.width?.toDouble() ?: 160.0).coerceAtLeast(60.0)
        val minH = (minSize?.height?.toDouble() ?: 120.0).coerceAtLeast(60.0)
        val maxW = maxSize?.width?.toDouble() ?: 10000.0
        val maxH = maxSize?.height?.toDouble() ?: 10000.0

        if (aspectRatio != null && aspectRatio > 0.0 && edge != UndecoratedResizeEdge.NONE) {
            val effMinW = maxOf(minW, minH * aspectRatio).coerceAtMost(maxW)
            val effMaxW = minOf(maxW, maxH * aspectRatio).coerceAtLeast(effMinW)
            val hasHoriz = edge.left || edge.right
            val hasVert = edge.top || edge.bottom
            val rawW = if (edge.right) sw + deltaScreenX else if (edge.left) sw - deltaScreenX else sw
            val rawH = if (edge.top) sh + deltaScreenY else if (edge.bottom) sh - deltaScreenY else sh
            val targetW = when {
                hasHoriz && !hasVert -> rawW.coerceIn(effMinW, effMaxW)
                !hasHoriz && hasVert -> (rawH * aspectRatio).coerceIn(effMinW, effMaxW)
                else -> {
                    val useX = kotlin.math.abs(deltaScreenX) >= kotlin.math.abs(deltaScreenY * aspectRatio)
                    (if (useX) rawW else rawH * aspectRatio).coerceIn(effMinW, effMaxW)
                }
            }
            val targetH = targetW / aspectRatio
            val nx = when {
                edge.left -> sx + (sw - targetW)
                edge.right -> sx
                else -> sx + (sw - targetW) * 0.5
            }
            val ny = when {
                edge.top -> sy
                edge.bottom -> sy + (sh - targetH)
                else -> sy + (sh - targetH)
            }
            return NSMakeRect(nx, ny, targetW, targetH)
        }

        var nx = sx
        var ny = sy
        var nw = sw
        var nh = sh

        if (edge.right) {
            nw = (sw + deltaScreenX).coerceIn(minW, maxW)
        } else if (edge.left) {
            val proposedW = (sw - deltaScreenX).coerceIn(minW, maxW)
            nx = sx + (sw - proposedW)
            nw = proposedW
        }

        // AppKit window frame Y points upward: dragging top up increases deltaScreenY -> taller frame.
        if (edge.top) {
            nh = (sh + deltaScreenY).coerceIn(minH, maxH)
        } else if (edge.bottom) {
            val proposedH = (sh - deltaScreenY).coerceIn(minH, maxH)
            ny = sy + (sh - proposedH)
            nh = proposedH
        }

        return NSMakeRect(nx, ny, nw, nh)
    }

    private fun handleUndecoratedEdgeMouseDown(session: MacosWindowSession, event: NSEvent): Boolean {
        val rv = session.rootView
        if (!rv.isUndecoratedChrome || rv.isWindowInFullscreen()) return false
        val (lx, ly) = rv.convertPoint(event.locationInWindow, fromView = null).useContents { x to y }
        val (bw, bh) = rv.bounds.useContents { size.width to size.height }
        val edge = detectUndecoratedResizeEdge(lx, ly, bw, bh, isWindowControlHit = false)
        if (edge == UndecoratedResizeEdge.NONE) return false
        (session.nsWindow as? MacosHostWindow)?.clearMaximizedStateOnManualResize()
        session.activeResizeEdge = edge
        val cursor = resolveCursorForResizeEdge(edge).second
        session.activeWindowResizeDragSession?.dispose()
        session.activeWindowResizeDragSession = PointerDragManager.startWindowResizeDrag(
            lockedCursor = edgeToCssCursor(edge)
        )
        rv.activeCustomResizeEdge = edge
        rv.lockedResizeCursor = cursor
        cursor.set()
        session.nsWindow.invalidateCursorRectsForView(rv)
        val screenPt = session.nsWindow.convertPointToScreen(event.locationInWindow)
        screenPt.useContents {
            session.resizeStartScreenX = x
            session.resizeStartScreenY = y
        }
        session.resizeStartFrame = session.nsWindow.frame
        return true
    }

    private fun handleUndecoratedEdgeMouseDragged(session: MacosWindowSession, event: NSEvent): Boolean {
        val edge = session.activeResizeEdge
        val startFrame = session.resizeStartFrame ?: return false
        if (edge == UndecoratedResizeEdge.NONE) return false
        session.rootView.lockedResizeCursor?.set()
        val screenPt = session.nsWindow.convertPointToScreen(event.locationInWindow)
        var curSX = 0.0
        var curSY = 0.0
        screenPt.useContents { curSX = x; curSY = y }
        val win = session.windowImpl
        val nextFrame = computeResizedWindowFrame(
            startFrame = startFrame,
            edge = edge,
            deltaScreenX = curSX - session.resizeStartScreenX,
            deltaScreenY = curSY - session.resizeStartScreenY,
            minSize = win?.getMinWindowSize(),
            aspectRatio = win?.getAspectRatio()
        )
        session.nsWindow.setFrame(nextFrame, display = true)
        return true
    }

    private fun handleUndecoratedEdgeMouseUp(session: MacosWindowSession): Boolean {
        if (session.activeResizeEdge == UndecoratedResizeEdge.NONE &&
            session.rootView.activeCustomResizeEdge == UndecoratedResizeEdge.NONE
        ) {
            return false
        }
        resetUndecoratedResizeState(session)
        return true
    }

    private fun dispatchWindowKeyEvent(session: MacosWindowSession, event: NSEvent, isDown: Boolean): Boolean {
        val chars = event.charactersIgnoringModifiers ?: event.characters ?: ""
        val keyStr = when (event.keyCode.toInt()) {
            36, 76 -> "Enter"
            48 -> "Tab"
            49 -> " "
            51 -> "Backspace"
            53 -> "Escape"
            123 -> "ArrowLeft"
            124 -> "ArrowRight"
            125 -> "ArrowDown"
            126 -> "ArrowUp"
            else -> chars.ifEmpty { "Unidentified" }
        }
        val mod = event.modifierFlags
        val ke = KeyEvent(
            key = keyStr,
            code = keyStr,
            keyCode = event.keyCode.toInt(),
            shiftKey = (mod and NSEventModifierFlagShift) != 0uL,
            ctrlKey = (mod and NSEventModifierFlagControl) != 0uL,
            altKey = (mod and NSEventModifierFlagOption) != 0uL,
            metaKey = (mod and NSEventModifierFlagCommand) != 0uL,
            isRepeat = event.isARepeat()
        )
        val win = session.windowImpl ?: return false
        if (isDown) win.dispatchKeyDown(ke) else win.dispatchKeyUp(ke)
        return true
    }

    private fun syncFocusedWindowMenuBarIfNeeded() {
        val session = activeSession()
        val focusedBar = when {
            session == null -> null
            !session.isDialog ->
                session.windowImpl?.getAttachedMenuBarImpl() as? com.nexustools.genericui.desktop.shell.DesktopMenuBarNativeImpl<*>
            else -> session.ownerWindowKey?.let { ownerKey ->
                sessions[ownerKey]?.windowImpl?.getAttachedMenuBarImpl() as? com.nexustools.genericui.desktop.shell.DesktopMenuBarNativeImpl<*>
            } ?: activeWindowMenuBarImpl
        }
        if (focusedBar !== activeWindowMenuBarImpl) {
            activeWindowMenuBarImpl = focusedBar
            mainMenuDirty = true
            rebuildMainMenu()
        }
    }

    fun showStandardAboutPanel() = MacosAppKitDispatch.onMainOrDefer {
        val app = NSApplication.sharedApplication
        val bundle = NSBundle.mainBundle
        val isAppBundle = bundle.bundlePath.endsWith(".app", ignoreCase = true)
        val hasBundleShortVersion = bundle.infoDictionary?.get("CFBundleShortVersionString") != null
        if (isAppBundle && hasBundleShortVersion) {
            app.orderFrontStandardAboutPanel(null)
            return@onMainOrDefer
        }
        val appName = activeApplicationTitle?.takeIf { it.isNotBlank() }
            ?: GenericUI.applicationName.takeIf { it.isNotBlank() }
            ?: "GenericUI"
        val versionStr = GenericUI.appVersion.ifBlank { "1.0.0" }
        val buildStr = GenericUI.appGitHash.ifBlank { versionStr }
        val options = linkedMapOf<Any?, Any?>(
            "ApplicationName" to appName,
            "ApplicationVersion" to versionStr,
            "Version" to buildStr
        )
        if (GenericUI.appCopyright.isNotBlank()) {
            options["Copyright"] = GenericUI.appCopyright
        }
        app.orderFrontStandardAboutPanelWithOptions(options)
    }

    fun performTextCut() = MacosAppKitDispatch.onMainOrDefer {
        NSApplication.sharedApplication.sendAction(NSSelectorFromString("cut:"), to = null, from = null)
    }

    fun performTextCopy() = MacosAppKitDispatch.onMainOrDefer {
        NSApplication.sharedApplication.sendAction(NSSelectorFromString("copy:"), to = null, from = null)
    }

    fun performTextPaste() = MacosAppKitDispatch.onMainOrDefer {
        NSApplication.sharedApplication.sendAction(NSSelectorFromString("paste:"), to = null, from = null)
    }

    fun performTextSelectAll() = MacosAppKitDispatch.onMainOrDefer {
        NSApplication.sharedApplication.sendAction(NSSelectorFromString("selectAll:"), to = null, from = null)
    }

    internal fun dialogSessionNsWindow(dialogImpl: DesktopDialogWindowNativeImpl<*>): NSWindow? =
        sessions[dialogImpl.nativeWidget.nativePointer]?.nsWindow

    internal fun dialogSessionOwnerKey(dialogImpl: DesktopDialogWindowNativeImpl<*>): Long? =
        sessions[dialogImpl.nativeWidget.nativePointer]?.ownerWindowKey

    internal fun dialogSessionForTest(dialogImpl: DesktopDialogWindowNativeImpl<*>): MacosWindowSession? =
        sessions[dialogImpl.nativeWidget.nativePointer]

    internal fun windowSessionForTest(windowImpl: MacosWindowNativeImpl): MacosWindowSession? =
        sessions[windowImpl.nativeWidget.nativePointer]

    internal fun registerSessionForTest(session: MacosWindowSession) {
        sessions[session.windowKey] = session
        activeKeyWindowId = session.windowKey
        syncFocusedWindowMenuBarIfNeeded()
    }

    internal fun unregisterSessionForTest(windowKey: Long) {
        sessions.remove(windowKey)
        if (activeKeyWindowId == windowKey) {
            activeKeyWindowId = sessions.keys.firstOrNull()
            syncFocusedWindowMenuBarIfNeeded()
        }
    }

    private fun activeSession(): MacosWindowSession? {
        activeKeyWindowId?.let { id -> sessions[id]?.let { if (!it.isClosing) return it } }
        return sessions.values.firstOrNull { !it.isClosing }
    }
}
