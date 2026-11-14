@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopDropdownNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSpinnerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.event.KeyEvent
import com.nexustools.genericui.event.PointerDragManager
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.event.PopupMouseCaptureManager
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.macos.impl.MacosDropdownNativeImpl
import com.nexustools.genericui.macos.render.MacosControlRenderUtil
import com.nexustools.genericui.macos.render.MacosGraphicsDrawUtil
import com.nexustools.genericui.macos.render.MacosHitRegion
import com.nexustools.genericui.macos.render.MacosNavigationRenderUtil
import com.nexustools.genericui.macos.render.MacosRenderContext
import com.nexustools.genericui.macos.render.MacosScrollRegion
import com.nexustools.genericui.macos.render.MacosStageOverlayRenderUtil
import com.nexustools.genericui.macos.render.MacosThemePalette
import com.nexustools.genericui.macos.render.MacosWorkspaceLayoutRenderUtil
import com.nexustools.genericui.nativeimpl.shell.MenuBarNativeImpl
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.style.theme.ThemeConfig
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.CValue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.AppKit.NSAppearance
import platform.AppKit.NSAppearanceNameAqua
import platform.AppKit.NSAppearanceNameDarkAqua
import platform.AppKit.NSApplication
import platform.AppKit.NSApplicationActivationPolicy
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSCursor
import platform.AppKit.NSEvent
import platform.AppKit.NSEventMaskAny
import platform.AppKit.NSEventMaskSystemDefined
import platform.AppKit.NSEventModifierFlagCommand
import platform.AppKit.NSEventModifierFlagControl
import platform.AppKit.NSEventModifierFlagOption
import platform.AppKit.NSEventModifierFlagShift
import platform.AppKit.NSEventTypeLeftMouseDown
import platform.AppKit.NSEventTypeLeftMouseDragged
import platform.AppKit.NSEventTypeLeftMouseUp
import platform.AppKit.NSEventTypeOtherMouseDown
import platform.AppKit.NSEventTypeOtherMouseDragged
import platform.AppKit.NSEventTypeOtherMouseUp
import platform.AppKit.NSEventTypeRightMouseDown
import platform.AppKit.NSEventTypeRightMouseDragged
import platform.AppKit.NSEventTypeRightMouseUp
import platform.AppKit.NSFloatingWindowLevel
import platform.AppKit.NSGraphicsContext
import platform.AppKit.NSImage
import platform.AppKit.NSNormalWindowLevel
import platform.AppKit.NSPanel
import platform.AppKit.NSPasteboard
import platform.AppKit.NSPasteboardTypeString
import platform.AppKit.NSScreen
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowAbove
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
import platform.CoreFoundation.CFRunLoopGetMain
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.CFRunLoopWakeUp
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.kCGBitmapByteOrderDefault
import platform.Foundation.NSBundle
import platform.Foundation.NSDate
import platform.Foundation.NSDefaultRunLoopMode
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSMakeSize
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSRect
import platform.Foundation.NSRunLoop
import platform.Foundation.NSRunLoopCommonModes
import platform.Foundation.NSThread
import platform.Foundation.NSTimer
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.usleep
import kotlin.concurrent.AtomicInt
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Singleton AppKit window, event-loop, CoreGraphics display, hit-testing, and input coordinator
 * for `:desktop:macos`.
 */
object MacosNativeWindowHost {

    private const val CUSTOM_TITLEBAR_INSET_PX: Int = 28
    private const val CHROME_MODE_STANDARD: Int = 0
    private const val CHROME_MODE_SEAMLESS_STACKED_NAVBAR: Int = 1
    private const val CHROME_MODE_EXPLICIT_CUSTOM_TITLEBAR: Int = 2
    private const val STAGE_HUD_IDLE_HIDE_MS: Double = 5000.0

    private val sessions = LinkedHashMap<Long, MacosWindowSession>(8)
    private var appInitialized: Boolean = false
    private var eventLoopRunning: Boolean = false
    private var frameTimer: NSTimer? = null
    private var localSystemEventMonitor: Any? = null
    private var lastFrameTickMs: Double = 0.0
    private var lastSpinnerRedrawMs: Double = 0.0
    private var lastThemePollMs: Double = 0.0
    private var lastObservedSystemTheme: ThemeMode? = null
    @kotlin.concurrent.Volatile
    private var redrawRequested: Boolean = true
    @kotlin.concurrent.Volatile
    private var stageRedrawRequested: Boolean = false
    private val redrawDispatchScheduled = AtomicInt(0)
    private val stageRedrawDispatchScheduled = AtomicInt(0)
    private var activeKeyWindowId: Long? = null

    var visualTestMode: Boolean = false
    var visualTestBannerText: String? = null
        set(value) {
            field = value
            if (appInitialized && MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
                runOnMainAppKitThread {
                    val mainSess = sessions.values.firstOrNull { it.windowImpl != null && !it.windowImpl.isToolModalWindow }
                    if (mainSess != null && mainSess.windowImpl != null) {
                        val baseTitle = mainSess.windowImpl.getTitle()
                        mainSess.nsWindow.title = if (!value.isNullOrBlank()) "$baseTitle  —  $value" else baseTitle
                    }
                }
            }
        }

    val activeNsWindow: NSWindow?
        get() = activeSession()?.nsWindow

    private val paintDurationSamplesUs = LongArray(512)
    private var paintSampleWriteIdx: Int = 0
    private var paintSampleCount: Int = 0
    private var totalPaintsCounter: Long = 0L
    private var stageOnlyPaintsCounter: Long = 0L
    private var fullWindowPaintsCounter: Long = 0L

    private inline fun runOnMainAppKitThread(crossinline block: () -> Unit) {
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled || NSThread.isMainThread) {
            block()
        } else {
            dispatch_async(dispatch_get_main_queue()) {
                block()
            }
        }
    }

    fun prepareEventLoop() {
        ensureAppInitialized()
    }

    private fun ensureAppInitialized() {
        if (appInitialized) return
        appInitialized = true

        val app = NSApplication.sharedApplication
        app.setActivationPolicy(NSApplicationActivationPolicy.NSApplicationActivationPolicyRegular)

        installApplicationIcon(app)

        val appName = resolveApplicationName()
        MacosMenuBarBridge.installMainMenu(appName, null)

        DesktopNativeWidget.registerStageSubtreeClassifier { widget ->
            MacosStageOverlayRenderUtil.isStageContainer(widget)
        }
        DesktopNativeWidget.onNativeTreeInvalidated = {
            requestRedraw()
        }
        DesktopNativeWidget.overlayInvalidationListener = { _ ->
            requestStageRedraw()
        }
        DesktopNativeWidget.onCursorUpdated = {
            updateActiveCursor()
        }
        MacosGraphicsDrawUtil.onAsyncAssetLoaded = {
            requestRedraw()
        }
        PopupMouseCaptureManager.onActivePopupCountChanged = { _ ->
            requestRedraw()
        }

        DesktopDialogWindowNativeImpl.onShowDialogHook = { dialogImpl, ownerWindow ->
            if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
                presentDialog(dialogImpl, ownerWindow)
            }
        }
        DesktopDialogWindowNativeImpl.onCloseDialogHook = { dialogImpl ->
            if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
                closeDialogSession(dialogImpl)
            }
        }
        DesktopDialogWindowNativeImpl.onTitleChangedHook = { dialogImpl, title ->
            sessions[dialogImpl.nativeWidget.nativePointer]?.nsWindow?.title = title
        }

        MacosControlRenderUtil.onRequestTextInputFocus = { inputImpl ->
            focusTextInput(inputImpl)
        }
        MacosControlRenderUtil.onRequestSelectPopup = { selectImpl, bounds ->
            openSelectPopup(selectImpl, bounds)
        }
        MacosControlRenderUtil.onRequestDropdownPopup = { dropdownImpl, bounds ->
            openDropdownPopup(dropdownImpl, bounds)
        }

        if (localSystemEventMonitor == null) {
            localSystemEventMonitor = NSEvent.addLocalMonitorForEventsMatchingMask(NSEventMaskSystemDefined) { event ->
                if (event != null) {
                    val handled = MacosApplicationNativeImpl.systemDefinedEventHook?.invoke(
                        event.subtype.toInt(),
                        event.data1.toInt()
                    ) ?: false
                    if (handled) null else event
                } else {
                    null
                }
            }
        }
    }

    private fun resolveApplicationName(): String {
        val fromApp = Application.current?.mainWindow?.getTitle()?.takeIf { it.isNotBlank() }
        if (fromApp != null) return fromApp
        val display = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleDisplayName") as? String
        if (!display.isNullOrBlank()) return display
        val bundleName = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleName") as? String
        if (!bundleName.isNullOrBlank()) return bundleName
        return NSProcessInfo.processInfo.processName
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun installApplicationIcon(app: NSApplication) {
        val icnsPath = NSBundle.mainBundle.pathForResource("icon", ofType = "icns")
            ?: NSBundle.mainBundle.pathForResource("AppIcon", ofType = "icns")
        if (icnsPath != null) {
            val bundleImg = NSImage(contentsOfFile = icnsPath)
            if (bundleImg != null) {
                app.applicationIconImage = bundleImg
                return
            }
        }
        val drawable = Resources.getDrawable("icon") ?: Resources.getDrawable("app_icon") ?: return
        val buf: ImageBuffer? = when {
            !drawable.svgSource.isNullOrBlank() -> {
                MacosSvgCoreGraphicsRasterizer.rasterizeSvgText(drawable.svgSource!!, 256, 256)
            }
            !drawable.base64Data.isNullOrBlank() -> {
                val rawBytes = runCatching { Base64.decode(drawable.base64Data!!.trim()) }.getOrNull()
                if (rawBytes != null && rawBytes.isNotEmpty()) {
                    ImageLoader.activeNativeCodec?.decode(
                        ImageSource.fromBytes(rawBytes),
                        ImageDecodeOptions(targetWidth = 256, targetHeight = 256, antialiasing = true)
                    )
                } else {
                    null
                }
            }
            else -> null
        }
        if (buf != null && buf.width > 0 && buf.height > 0) {
            createNsImageFromRgba(buf)?.let { nsImg ->
                app.applicationIconImage = nsImg
            }
        }
    }

    private fun createNsImageFromRgba(buffer: ImageBuffer): NSImage? {
        val w = buffer.width
        val h = buffer.height
        val rgba = buffer.bytes
        if (w <= 0 || h <= 0 || rgba.size < w * h * 4) return null
        val premul = ByteArray(w * h * 4)
        var i = 0
        val limit = w * h * 4
        while (i < limit) {
            val r = rgba[i].toInt() and 0xFF
            val g = rgba[i + 1].toInt() and 0xFF
            val b = rgba[i + 2].toInt() and 0xFF
            val a = rgba[i + 3].toInt() and 0xFF
            if (a == 255) {
                premul[i] = r.toByte()
                premul[i + 1] = g.toByte()
                premul[i + 2] = b.toByte()
                premul[i + 3] = 255.toByte()
            } else if (a > 0) {
                premul[i] = ((r * a + 127) / 255).toByte()
                premul[i + 1] = ((g * a + 127) / 255).toByte()
                premul[i + 2] = ((b * a + 127) / 255).toByte()
                premul[i + 3] = a.toByte()
            }
            i += 4
        }
        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
        return try {
            val cgImg = premul.usePinned { pinned ->
                val ctx = CGBitmapContextCreate(
                    data = pinned.addressOf(0),
                    width = w.convert(),
                    height = h.convert(),
                    bitsPerComponent = 8.convert(),
                    bytesPerRow = (w * 4).convert(),
                    space = colorSpace,
                    bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrderDefault
                ) ?: return null
                try {
                    CGBitmapContextCreateImage(ctx)
                } finally {
                    CGContextRelease(ctx)
                }
            } ?: return null
            try {
                NSImage(cGImage = cgImg, size = NSMakeSize(w.toDouble(), h.toDouble()))
            } finally {
                CGImageRelease(cgImg)
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }
    }

    fun runEventLoop() {
        ensureAppInitialized()
        if (eventLoopRunning) return
        eventLoopRunning = true
        lastFrameTickMs = monotonicTimeMs()

        val timer = NSTimer.scheduledTimerWithTimeInterval(
            interval = 1.0 / 60.0,
            repeats = true
        ) { _ ->
            onFrameTimerTick()
        }
        NSRunLoop.currentRunLoop.addTimer(timer, forMode = NSRunLoopCommonModes)
        frameTimer = timer

        val app = NSApplication.sharedApplication
        app.finishLaunching()
        if (sessions.isNotEmpty()) {
            app.activateIgnoringOtherApps(true)
        }
        requestRedraw()
        onFrameTimerTick()

        if (visualTestMode) {
            pumpVisualWindowEvents(48L)
            return
        }

        try {
            app.run()
        } finally {
            eventLoopRunning = false
            frameTimer?.invalidate()
            frameTimer = null
            restoreHiddenCursors()
        }
    }

    fun terminateEventLoop() {
        frameTimer?.invalidate()
        frameTimer = null
        restoreHiddenCursors()
        if (!eventLoopRunning) return
        eventLoopRunning = false
        NSApplication.sharedApplication.stop(null)
        CFRunLoopGetMain()?.let { CFRunLoopWakeUp(it) }
    }

    fun requestApplicationQuit() {
        val appInstance = Application.current
        if (appInstance != null) {
            GenericUiThreading.runOnLogic {
                appInstance.exit(0)
            }
            return
        }
        val mainWin = sessions.values.firstOrNull { it.windowImpl != null && !it.windowImpl.isToolModalWindow }?.windowImpl
        if (mainWin != null) {
            GenericUiThreading.runOnLogic {
                mainWin.closeWindow()
            }
        } else {
            terminateEventLoop()
        }
    }

    private fun invalidateSessionWindow(session: MacosWindowSession) {
        session.pendingFullPaint = true
        session.canvasView.setNeedsDisplay(true)
    }

    private fun invalidateSessionStageOnly(session: MacosWindowSession) {
        val stage = session.renderedStageBounds
        if (stage == null || stage.width <= 0 || stage.height <= 0 || session.pendingFullPaint) {
            invalidateSessionWindow(session)
            return
        }
        session.canvasView.setNeedsDisplayInRect(
            NSMakeRect(
                stage.x.toDouble(),
                stage.y.toDouble(),
                stage.width.toDouble(),
                stage.height.toDouble()
            )
        )
    }

    private fun isWidgetInsideStage(session: MacosWindowSession, widget: DesktopNativeWidget?): Boolean {
        if (widget == null) return false
        val stageWidget = session.renderedStageWidget ?: return false
        return stageWidget.containsNativeWidget(widget)
    }

    fun requestRedraw() {
        redrawRequested = true
        if (!appInitialized || !MacosApplicationNativeImpl.interactiveMacosGuiEnabled) return
        if (!NSThread.isMainThread) {
            if (redrawDispatchScheduled.compareAndSet(0, 1)) {
                dispatch_async(dispatch_get_main_queue()) {
                    redrawDispatchScheduled.value = 0
                    for (session in sessions.values) {
                        invalidateSessionWindow(session)
                    }
                }
            }
            return
        }
        for (session in sessions.values) {
            invalidateSessionWindow(session)
        }
    }

    fun requestStageRedraw() {
        stageRedrawRequested = true
        if (!appInitialized || !MacosApplicationNativeImpl.interactiveMacosGuiEnabled) return
        if (!NSThread.isMainThread) {
            if (stageRedrawDispatchScheduled.compareAndSet(0, 1)) {
                dispatch_async(dispatch_get_main_queue()) {
                    stageRedrawDispatchScheduled.value = 0
                    for (session in sessions.values) {
                        invalidateSessionStageOnly(session)
                    }
                }
            }
            return
        }
        for (session in sessions.values) {
            invalidateSessionStageOnly(session)
        }
    }

    fun syncThemeAppearance(mode: ThemeMode) {
        if (!appInitialized) return
        runOnMainAppKitThread {
            val effective = SystemThemeDetector.resolveTheme(mode)
            val appearanceName = if (effective == ThemeMode.DARK) NSAppearanceNameDarkAqua else NSAppearanceNameAqua
            val appearance = NSAppearance.appearanceNamed(appearanceName)
            for (session in sessions.values) {
                session.nsWindow.appearance = appearance
                session.canvasView.setNeedsDisplay(true)
            }
            redrawRequested = true
        }
    }

    fun presentWindow(windowImpl: MacosWindowNativeImpl) {
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) { presentWindow(windowImpl) }
            return
        }
        ensureAppInitialized()
        val key = windowImpl.nativeWidget.nativePointer
        val existing = sessions[key]
        if (existing != null) {
            existing.lastStageActivityTimeMs = monotonicTimeMs()
            existing.overlayHudAlpha = 1f
            syncWindowProperties(windowImpl)
            existing.nsWindow.makeKeyAndOrderFront(null)
            existing.nsWindow.makeFirstResponder(existing.canvasView)
            NSApplication.sharedApplication.activateIgnoringOtherApps(true)
            activeKeyWindowId = key
            requestWindowFullscreen(existing, windowImpl.isFullscreen())
            existing.canvasView.setNeedsDisplay(true)
            return
        }

        val winSize = windowImpl.getWindowSize()
        val initW = winSize.width.coerceAtLeast(320).toDouble()
        val initH = winSize.height.coerceAtLeast(240).toDouble()

        val initialChromeMode = resolveTitleBarChromeMode(windowImpl)
        val styleMask = computeWindowStyleMask(windowImpl, initialChromeMode)
        val nsWindow = NSWindow(
            contentRect = NSMakeRect(0.0, 0.0, initW, initH),
            styleMask = styleMask,
            backing = NSBackingStoreBuffered,
            defer = false
        )
        nsWindow.releasedWhenClosed = false
        if (!windowImpl.isToolModalWindow) {
            nsWindow.collectionBehavior = NSWindowCollectionBehaviorFullScreenPrimary
        }

        val canvasView = MacosCanvasView(windowKey = key, frameRect = NSMakeRect(0.0, 0.0, initW, initH))
        val delegate = MacosWindowDelegate(windowKey = key, isDialog = false)
        val session = MacosWindowSession(
            windowKey = key,
            nsWindow = nsWindow,
            canvasView = canvasView,
            delegate = delegate,
            windowImpl = windowImpl
        )
        session.appliedTitleBarChromeMode = initialChromeMode
        session.lastStageActivityTimeMs = monotonicTimeMs()
        sessions[key] = session

        nsWindow.contentView = canvasView
        nsWindow.delegate = delegate

        applyAppearanceToWindow(nsWindow)
        syncWindowProperties(windowImpl)
        nsWindow.center()

        val ownerImpl = windowImpl.ownerWindow as? MacosWindowNativeImpl
        if (ownerImpl != null) {
            sessions[ownerImpl.nativeWidget.nativePointer]?.nsWindow?.addChildWindow(nsWindow, ordered = NSWindowAbove)
        }

        val menuBar = windowImpl.getAttachedMenuBarImpl()
        if (menuBar != null || !windowImpl.isToolModalWindow) {
            val appTitle = windowImpl.getTitle().ifBlank { resolveApplicationName() }
            MacosMenuBarBridge.installMainMenu(appTitle, menuBar)
        }

        nsWindow.makeKeyAndOrderFront(null)
        nsWindow.makeFirstResponder(canvasView)
        NSApplication.sharedApplication.activateIgnoringOtherApps(true)
        activeKeyWindowId = key

        if (windowImpl.isFullscreen()) {
            requestWindowFullscreen(session, targetFullscreen = true, deferToggleToNextTurn = true)
        }

        onNativeWindowDidResize(key, isDialog = false)
        canvasView.setNeedsDisplay(true)
    }

    private fun presentDialog(
        dialogImpl: DesktopDialogWindowNativeImpl<*>,
        ownerWindow: DesktopWindowNativeImpl<*>?
    ) {
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) { presentDialog(dialogImpl, ownerWindow) }
            return
        }
        ensureAppInitialized()
        val key = dialogImpl.nativeWidget.nativePointer
        val existing = sessions[key]
        if (existing != null) {
            existing.nsWindow.title = dialogImpl.getTitle()
            existing.nsWindow.makeKeyAndOrderFront(null)
            existing.nsWindow.makeFirstResponder(existing.canvasView)
            existing.canvasView.setNeedsDisplay(true)
            return
        }

        val ownerSession = (ownerWindow as? MacosWindowNativeImpl)?.let { sessions[it.nativeWidget.nativePointer] }
            ?: sessions.values.firstOrNull { it.windowImpl != null }
        val maxW = ownerSession?.canvasView?.bounds?.useContents { size.width.toInt() - 64 }?.coerceAtLeast(360) ?: 860
        val maxH = ownerSession?.canvasView?.bounds?.useContents { size.height.toInt() - 64 }?.coerceAtLeast(260) ?: 680

        val hasExplicit = dialogImpl.nativeWidget.attributes["hasExplicitBounds"] == true
        val intrinsic = if (hasExplicit) {
            com.nexustools.genericui.geometry.Size(dialogImpl.boundsWidth, dialogImpl.boundsHeight)
        } else {
            MacosWorkspaceLayoutRenderUtil.measureDialogIntrinsicSize(dialogImpl.nativeWidget, maxW, maxH)
        }
        val dlgW = intrinsic.width.coerceAtLeast(320).toDouble()
        val dlgH = intrinsic.height.coerceAtLeast(180).toDouble()

        val mask = NSWindowStyleMaskTitled or NSWindowStyleMaskClosable or NSWindowStyleMaskResizable
        val panel = NSPanel(
            contentRect = NSMakeRect(0.0, 0.0, dlgW, dlgH),
            styleMask = mask,
            backing = NSBackingStoreBuffered,
            defer = false
        )
        panel.releasedWhenClosed = false
        panel.title = dialogImpl.getTitle()
        panel.floatingPanel = false
        panel.hidesOnDeactivate = false
        panel.contentMinSize = NSMakeSize(300.0, 160.0)

        val canvasView = MacosCanvasView(windowKey = key, frameRect = NSMakeRect(0.0, 0.0, dlgW, dlgH))
        val delegate = MacosWindowDelegate(windowKey = key, isDialog = true)
        val session = MacosWindowSession(
            windowKey = key,
            nsWindow = panel,
            canvasView = canvasView,
            delegate = delegate,
            dialogImpl = dialogImpl,
            ownerWindowKey = ownerSession?.windowKey
        )
        session.lastStageActivityTimeMs = monotonicTimeMs()
        sessions[key] = session

        panel.contentView = canvasView
        panel.delegate = delegate
        applyAppearanceToWindow(panel)

        if (ownerSession != null) {
            val ownerFrame = ownerSession.nsWindow.frame
            val panelFrame = panel.frame
            val originX = ownerFrame.useContents { origin.x + (size.width - panelFrame.useContents { size.width }) * 0.5 }
            val originY = ownerFrame.useContents { origin.y + (size.height - panelFrame.useContents { size.height }) * 0.5 }
            panel.setFrameOrigin(NSMakePoint(originX, originY))
            ownerSession.nsWindow.addChildWindow(panel, ordered = NSWindowAbove)
        } else {
            panel.center()
        }

        panel.makeKeyAndOrderFront(null)
        panel.makeFirstResponder(canvasView)
        activeKeyWindowId = key
        canvasView.setNeedsDisplay(true)
        requestRedraw()
    }

    private fun closeDialogSession(dialogImpl: DesktopDialogWindowNativeImpl<*>) {
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) { closeDialogSession(dialogImpl) }
            return
        }
        val key = dialogImpl.nativeWidget.nativePointer
        val session = sessions.remove(key) ?: return
        clearFocusedWidgetInSession(session)
        session.nsWindow.delegate = null
        val ownerSession = session.ownerWindowKey?.let { sessions[it] }
        ownerSession?.nsWindow?.removeChildWindow(session.nsWindow)
        session.nsWindow.orderOut(null)
        if (ownerSession != null) {
            ownerSession.nsWindow.makeKeyAndOrderFront(null)
            ownerSession.nsWindow.makeFirstResponder(ownerSession.canvasView)
            activeKeyWindowId = ownerSession.windowKey
        }
        requestRedraw()
    }

    fun syncWindowProperties(windowImpl: MacosWindowNativeImpl) {
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) { syncWindowProperties(windowImpl) }
            return
        }
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        val nsWindow = session.nsWindow
        syncUnifiedTitleBarChrome(session, force = true)
        nsWindow.title = windowImpl.getTitle()
        if (windowImpl.isToolModalWindow && windowImpl.alwaysOnTopOfOwner) {
            nsWindow.level = NSFloatingWindowLevel
        } else {
            nsWindow.level = NSNormalWindowLevel
        }
        syncWindowSize(windowImpl)
        session.canvasView.setNeedsDisplay(true)
    }

    private fun applyTitleBarAppearanceForMode(nsWindow: NSWindow, mode: Int) {
        when (mode) {
            CHROME_MODE_SEAMLESS_STACKED_NAVBAR -> {
                nsWindow.titlebarAppearsTransparent = true
                nsWindow.titleVisibility = NSWindowTitleVisible
            }
            CHROME_MODE_EXPLICIT_CUSTOM_TITLEBAR -> {
                nsWindow.titlebarAppearsTransparent = true
                nsWindow.titleVisibility = NSWindowTitleHidden
            }
            else -> {
                nsWindow.titlebarAppearsTransparent = false
                nsWindow.titleVisibility = NSWindowTitleVisible
            }
        }
    }

    private fun applyMinContentSizeForMode(session: MacosWindowSession, mode: Int) {
        val windowImpl = session.windowImpl ?: return
        val minSz = windowImpl.getMinWindowSize() ?: return
        val minInsetH = if (mode != CHROME_MODE_STANDARD) {
            session.titleBarInsetTopPx.takeIf { it in 20..56 } ?: CUSTOM_TITLEBAR_INSET_PX
        } else {
            0
        }
        session.nsWindow.contentMinSize = NSMakeSize(
            minSz.width.coerceAtLeast(200).toDouble(),
            (minSz.height.coerceAtLeast(140) + minInsetH).toDouble()
        )
    }

    private fun syncUnifiedTitleBarChrome(session: MacosWindowSession, force: Boolean = false) {
        val windowImpl = session.windowImpl ?: return
        val nsWindow = session.nsWindow
        val isFs = (nsWindow.styleMask and NSWindowStyleMaskFullScreen) != 0UL
        if (isFs || session.isFullscreenTransitionInFlight) {
            if (isFs) {
                session.appliedTitleBarChromeMode = -1
            }
            return
        }
        val mode = resolveTitleBarChromeMode(windowImpl)
        val previousMode = session.appliedTitleBarChromeMode
        val previousMask = nsWindow.styleMask
        val nextMask = computeWindowStyleMask(windowImpl, mode)
        val maskChanged = previousMask != nextMask
        val modeChanged = previousMode != mode

        if (force || modeChanged || maskChanged) {
            var savedX = 0.0
            var savedY = 0.0
            var savedW = 0.0
            var savedH = 0.0
            nsWindow.frame.useContents {
                savedX = origin.x
                savedY = origin.y
                savedW = size.width
                savedH = size.height
            }

            session.appliedTitleBarChromeMode = mode
            session.isApplyingChromeGeometryTransition = true
            try {
                if (maskChanged) {
                    nsWindow.styleMask = nextMask
                }
                applyTitleBarAppearanceForMode(nsWindow, mode)
                applyMinContentSizeForMode(session, mode)
                if ((maskChanged || (previousMode >= 0 && modeChanged)) && savedW > 0.0 && savedH > 0.0) {
                    nsWindow.setFrame(NSMakeRect(savedX, savedY, savedW, savedH), display = false)
                }
                var expectedW = 0.0
                var expectedH = 0.0
                nsWindow.contentRectForFrameRect(nsWindow.frame).useContents {
                    expectedW = size.width
                    expectedH = size.height
                }
                var curCanvasW = 0.0
                var curCanvasH = 0.0
                session.canvasView.bounds.useContents {
                    curCanvasW = size.width
                    curCanvasH = size.height
                }
                if (expectedW > 0.0 && expectedH > 0.0 &&
                    (abs(curCanvasW - expectedW) > 0.5 || abs(curCanvasH - expectedH) > 0.5)
                ) {
                    session.canvasView.setFrameSize(NSMakeSize(expectedW, expectedH))
                }
            } finally {
                session.isApplyingChromeGeometryTransition = false
            }
        }

        if (mode != CHROME_MODE_STANDARD) {
            val frameH = nsWindow.frame.useContents { size.height }
            val layoutH = nsWindow.contentLayoutRect.useContents { size.height }
            val measuredInset = (frameH - layoutH).roundToInt()
            session.titleBarInsetTopPx = if (measuredInset in 20..56) measuredInset else CUSTOM_TITLEBAR_INSET_PX
        } else {
            session.titleBarInsetTopPx = 0
        }

        if (maskChanged || (previousMode >= 0 && modeChanged)) {
            val newViewW = session.canvasView.bounds.useContents { size.width.roundToInt().coerceAtLeast(1) }
            val newViewH = session.canvasView.bounds.useContents { size.height.roundToInt().coerceAtLeast(1) }
            windowImpl.syncHostClientSizeFromChromeTransition(newViewW, newViewH)
            invalidateSessionWindow(session)
        }
    }

    fun syncWindowTitle(windowImpl: MacosWindowNativeImpl, title: String) {
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) { syncWindowTitle(windowImpl, title) }
            return
        }
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        session.nsWindow.title = title
        session.canvasView.setNeedsDisplay(true)
    }

    fun syncWindowSize(windowImpl: MacosWindowNativeImpl) {
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) { syncWindowSize(windowImpl) }
            return
        }
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        val nsWindow = session.nsWindow
        val activeMode = if (session.appliedTitleBarChromeMode >= 0) {
            session.appliedTitleBarChromeMode
        } else {
            resolveTitleBarChromeMode(windowImpl)
        }
        applyMinContentSizeForMode(session, activeMode)
        if (session.isFullscreenTransitionInFlight || (nsWindow.styleMask and NSWindowStyleMaskFullScreen) != 0UL) return
        val target = windowImpl.getWindowSize()
        val curW = session.canvasView.bounds.useContents { size.width.roundToInt() }
        val curH = session.canvasView.bounds.useContents { size.height.roundToInt() }
        if (abs(curW - target.width) > 1 || abs(curH - target.height) > 1) {
            nsWindow.setContentSize(NSMakeSize(target.width.toDouble(), target.height.toDouble()))
        }
    }

    fun syncWindowState(windowImpl: MacosWindowNativeImpl) {
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) { syncWindowState(windowImpl) }
            return
        }
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        val nsWindow = session.nsWindow
        when (windowImpl.getWindowState()) {
            WindowState.MINIMIZED -> if (!nsWindow.miniaturized) nsWindow.miniaturize(null)
            WindowState.MAXIMIZED -> {
                if (nsWindow.miniaturized) nsWindow.deminiaturize(null)
                NSScreen.mainScreen?.visibleFrame?.let { visibleRect ->
                    nsWindow.setFrame(visibleRect, display = true)
                }
            }
            WindowState.FULLSCREEN -> syncFullscreen(windowImpl, true)
            WindowState.NORMAL, WindowState.PICTURE_IN_PICTURE -> {
                if (nsWindow.miniaturized) nsWindow.deminiaturize(null)
                syncFullscreen(windowImpl, false)
            }
        }
    }

    private fun requestWindowFullscreen(
        session: MacosWindowSession,
        targetFullscreen: Boolean,
        deferToggleToNextTurn: Boolean = false
    ) {
        val nsWindow = session.nsWindow
        val nowMs = monotonicTimeMs()
        if (session.isFullscreenTransitionInFlight && (nowMs - session.fullscreenTransitionStartedMs) > 2500.0) {
            session.isFullscreenTransitionInFlight = false
        }
        val currentlyFullscreen = (nsWindow.styleMask and NSWindowStyleMaskFullScreen) != 0UL
        if (session.isFullscreenTransitionInFlight) {
            session.pendingTargetFullscreen = if (session.targetFullscreen != targetFullscreen) targetFullscreen else null
            return
        }
        if (targetFullscreen == currentlyFullscreen) {
            session.targetFullscreen = currentlyFullscreen
            session.pendingTargetFullscreen = null
            return
        }
        session.targetFullscreen = targetFullscreen
        session.isFullscreenTransitionInFlight = true
        session.fullscreenTransitionStartedMs = nowMs
        session.pendingTargetFullscreen = null
        if (deferToggleToNextTurn) {
            dispatch_async(dispatch_get_main_queue()) {
                val liveSession = sessions[session.windowKey] ?: return@dispatch_async
                val liveNowFullscreen = (liveSession.nsWindow.styleMask and NSWindowStyleMaskFullScreen) != 0UL
                if (liveSession.targetFullscreen != liveNowFullscreen) {
                    liveSession.fullscreenTransitionStartedMs = monotonicTimeMs()
                    liveSession.nsWindow.toggleFullScreen(null)
                } else {
                    liveSession.isFullscreenTransitionInFlight = false
                }
            }
        } else {
            nsWindow.toggleFullScreen(null)
        }
    }

    fun syncFullscreen(windowImpl: MacosWindowNativeImpl, fullscreen: Boolean) {
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) { syncFullscreen(windowImpl, fullscreen) }
            return
        }
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        requestWindowFullscreen(session, fullscreen)
        session.canvasView.setNeedsDisplay(true)
    }

    fun syncMenuBar(windowImpl: MacosWindowNativeImpl, menuBarImpl: MenuBarNativeImpl<*>?) {
        if (!appInitialized) return
        runOnMainAppKitThread {
            val appTitle = windowImpl.getTitle().ifBlank { resolveApplicationName() }
            MacosMenuBarBridge.installMainMenu(appTitle, menuBarImpl)
        }
    }

    fun syncWindowVisibility(windowImpl: MacosWindowNativeImpl, visible: Boolean) {
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) { syncWindowVisibility(windowImpl, visible) }
            return
        }
        val session = sessions[windowImpl.nativeWidget.nativePointer]
        if (visible) {
            if (session == null) {
                presentWindow(windowImpl)
            } else {
                session.lastStageActivityTimeMs = monotonicTimeMs()
                session.overlayHudAlpha = 1f
                session.nsWindow.makeKeyAndOrderFront(null)
                session.nsWindow.makeFirstResponder(session.canvasView)
                NSApplication.sharedApplication.activateIgnoringOtherApps(true)
                activeKeyWindowId = session.windowKey
                requestWindowFullscreen(session, windowImpl.isFullscreen())
                session.canvasView.setNeedsDisplay(true)
            }
        } else if (session != null) {
            val currentlyFullscreen = (session.nsWindow.styleMask and NSWindowStyleMaskFullScreen) != 0UL
            if (currentlyFullscreen || session.isFullscreenTransitionInFlight) {
                requestWindowFullscreen(session, false)
            } else {
                session.nsWindow.orderOut(null)
            }
        }
    }

    fun syncWindowOpacity(windowImpl: MacosWindowNativeImpl, opacity: Float) {
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) { syncWindowOpacity(windowImpl, opacity) }
            return
        }
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        session.nsWindow.alphaValue = opacity.coerceIn(0f, 1f).toDouble()
    }

    fun bringWindowToFront(windowImpl: MacosWindowNativeImpl) {
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) { bringWindowToFront(windowImpl) }
            return
        }
        val session = sessions[windowImpl.nativeWidget.nativePointer] ?: return
        session.nsWindow.makeKeyAndOrderFront(null)
        activeKeyWindowId = session.windowKey
    }

    fun onWindowClosed(windowImpl: MacosWindowNativeImpl) {
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) { onWindowClosed(windowImpl) }
            return
        }
        val key = windowImpl.nativeWidget.nativePointer
        val session = sessions.remove(key) ?: return
        clearFocusedWidgetInSession(session)
        session.nsWindow.delegate = null
        val ownerSession = (windowImpl.ownerWindow as? MacosWindowNativeImpl)?.let { sessions[it.nativeWidget.nativePointer] }
        ownerSession?.nsWindow?.removeChildWindow(session.nsWindow)
        session.nsWindow.orderOut(null)

        val hasRemainingMainWindow = sessions.values.any { it.windowImpl != null && !it.windowImpl.isToolModalWindow }
        if (!hasRemainingMainWindow && !windowImpl.isToolModalWindow && !visualTestMode) {
            val app = Application.current
            if (app != null) {
                app.exit(0)
            } else {
                terminateEventLoop()
            }
        }
    }

    internal fun onNativeWindowShouldClose(windowKey: Long, isDialog: Boolean): Boolean {
        val session = sessions[windowKey] ?: return true
        if (isDialog) {
            val dlg = session.dialogImpl ?: return true
            GenericUiThreading.runOnLogic {
                dlg.closeDialog()
            }
            return false
        }
        val win = session.windowImpl ?: return true
        GenericUiThreading.runOnLogic {
            win.closeWindow()
        }
        return false
    }

    internal fun onNativeWindowDidResize(windowKey: Long, isDialog: Boolean) {
        val session = sessions[windowKey] ?: return
        if (session.isApplyingChromeGeometryTransition) return
        val w = session.canvasView.bounds.useContents { size.width.roundToInt().coerceAtLeast(1) }
        val h = session.canvasView.bounds.useContents { size.height.roundToInt().coerceAtLeast(1) }
        if (isDialog) {
            session.dialogImpl?.nativeWidget?.bounds = Rect(0, 0, w, h)
        } else {
            val win = session.windowImpl
            if (win != null) {
                val nativeIsFs = (session.nsWindow.styleMask and NSWindowStyleMaskFullScreen) != 0UL
                val effectiveIsFs = when {
                    !win.isVisible() -> win.isFullscreen()
                    session.isFullscreenTransitionInFlight -> session.targetFullscreen
                    else -> nativeIsFs
                }
                if (!nativeIsFs && !session.isFullscreenTransitionInFlight) {
                    syncUnifiedTitleBarChrome(session)
                }
                GenericUiThreading.runOnLogic {
                    win.notifyHostResizedFromOs(w, h, effectiveIsFs)
                }
            }
        }
        invalidateSessionWindow(session)
    }

    internal fun onNativeWindowFocusChanged(windowKey: Long, isDialog: Boolean, focused: Boolean) {
        val session = sessions[windowKey] ?: return
        if (focused) {
            activeKeyWindowId = windowKey
        }
        if (!isDialog) {
            val win = session.windowImpl ?: return
            GenericUiThreading.runOnLogic {
                win.dispatchFocusChanged(focused)
            }
        }
        invalidateSessionWindow(session)
    }

    internal fun onNativeWindowFullscreenWillChange(
        windowKey: Long,
        isDialog: Boolean,
        enteringFullscreen: Boolean
    ) {
        if (isDialog) return
        val session = sessions[windowKey] ?: return
        session.targetFullscreen = enteringFullscreen
        session.isFullscreenTransitionInFlight = true
        session.fullscreenTransitionStartedMs = monotonicTimeMs()
    }

    internal fun onNativeWindowFullscreenFailed(windowKey: Long, isDialog: Boolean) {
        if (isDialog) return
        val session = sessions[windowKey] ?: return
        session.isFullscreenTransitionInFlight = false
        session.targetFullscreen = (session.nsWindow.styleMask and NSWindowStyleMaskFullScreen) != 0UL
        session.pendingTargetFullscreen = null
        invalidateSessionWindow(session)
    }

    internal fun onNativeWindowFullscreenChanged(windowKey: Long, isDialog: Boolean, fullscreen: Boolean) {
        if (isDialog) return
        val session = sessions[windowKey] ?: return
        val win = session.windowImpl ?: return
        session.isFullscreenTransitionInFlight = false
        session.targetFullscreen = fullscreen
        if (!fullscreen) {
            syncUnifiedTitleBarChrome(session, force = true)
            if (!win.isVisible()) {
                session.pendingTargetFullscreen = null
                session.nsWindow.orderOut(null)
                return
            }
        } else {
            var expectedW = 0.0
            var expectedH = 0.0
            session.nsWindow.contentRectForFrameRect(session.nsWindow.frame).useContents {
                expectedW = size.width
                expectedH = size.height
            }
            var curW = 0.0
            var curH = 0.0
            session.canvasView.bounds.useContents {
                curW = size.width
                curH = size.height
            }
            if (expectedW > 0.0 && expectedH > 0.0 &&
                (abs(curW - expectedW) > 0.5 || abs(curH - expectedH) > 0.5)
            ) {
                session.canvasView.setFrameSize(NSMakeSize(expectedW, expectedH))
            }
        }
        val w = session.canvasView.bounds.useContents { size.width.roundToInt().coerceAtLeast(1) }
        val h = session.canvasView.bounds.useContents { size.height.roundToInt().coerceAtLeast(1) }
        val pendingTarget = session.pendingTargetFullscreen
        session.pendingTargetFullscreen = null
        if (pendingTarget != null && pendingTarget != fullscreen) {
            requestWindowFullscreen(session, pendingTarget)
        } else {
            GenericUiThreading.runOnLogic {
                win.notifyHostResizedFromOs(w, h, fullscreen)
            }
        }
        invalidateSessionWindow(session)
    }

    private fun pollSystemThemeIfChanged(nowMs: Double, force: Boolean = false) {
        if (!force && lastObservedSystemTheme != null && (nowMs - lastThemePollMs) < 400.0) {
            return
        }
        lastThemePollMs = nowMs
        val detected = SystemThemeDetector.detectSystemTheme()
        val prev = lastObservedSystemTheme
        lastObservedSystemTheme = detected
        if (prev != null && prev != detected) {
            val appMode = Application.current?.getThemeMode() ?: ThemeMode.AUTO
            if (appMode.isAuto) {
                val appearanceName = if (detected == ThemeMode.DARK) NSAppearanceNameDarkAqua else NSAppearanceNameAqua
                val appearance = NSAppearance.appearanceNamed(appearanceName)
                for (session in sessions.values) {
                    session.nsWindow.appearance = appearance
                    invalidateSessionWindow(session)
                }
                redrawRequested = true
            }
            GenericUiThreading.runOnLogic {
                SystemThemeDetector.notifySystemThemeChanged(detected)
            }
            GenericUiThreading.flushAllQueues()
        }
    }

    private fun onFrameTimerTick() {
        val nowMs = monotonicTimeMs()
        val elapsedMs = (nowMs - lastFrameTickMs).coerceIn(1.0, 250.0)
        lastFrameTickMs = nowMs

        pollSystemThemeIfChanged(nowMs)

        GenericUiThreading.runOnLogic {
            MacosUiTimerNativeImpl.advanceActiveTimers(elapsedMs.roundToInt().coerceAtLeast(1))
        }
        GenericUiThreading.flushAllQueues()

        val vblankWidgets = DesktopNativeWidget.activeVBlankWidgetsSnapshot()
        if (vblankWidgets.isNotEmpty()) {
            for (widget in vblankWidgets) {
                if (!widget.disposed && widget.visible) {
                    widget.vblankDispatch?.invoke(nowMs)
                }
            }
            GenericUiThreading.flushAllQueues()
        }

        val checkSpinnerTick = (nowMs - lastSpinnerRedrawMs) >= 66.0
        if (checkSpinnerTick) {
            lastSpinnerRedrawMs = nowMs
        }

        val nonOverlayVer = DesktopNativeWidget.globalNonOverlayMutationVersion
        val reqFull = redrawRequested
        val reqStage = stageRedrawRequested
        redrawRequested = false
        stageRedrawRequested = false

        for (session in sessions.values) {
            var fullDirty = reqFull || session.pendingFullPaint || session.lastRenderedNonOverlayVersion != nonOverlayVer
            var stageDirty = reqStage

            if (session.windowImpl != null) {
                val prevMode = session.appliedTitleBarChromeMode
                val prevInset = session.titleBarInsetTopPx
                syncUnifiedTitleBarChrome(session)
                if (session.appliedTitleBarChromeMode != prevMode || session.titleBarInsetTopPx != prevInset) {
                    fullDirty = true
                }
            }

            val rootWidget = session.dialogImpl?.nativeWidget ?: session.windowImpl?.nativeWidget
            if (rootWidget != null) {
                val swapSum = sumInteractiveSurfaceSwaps(rootWidget)
                if (swapSum != session.lastRenderedSurfaceSwapSum) {
                    session.lastRenderedSurfaceSwapSum = swapSum
                    stageDirty = true
                }
                if (!fullDirty && checkSpinnerTick && containsVisibleSpinner(rootWidget)) {
                    val stageWidget = session.renderedStageWidget
                    if (stageWidget != null && containsVisibleSpinner(stageWidget)) {
                        stageDirty = true
                    } else {
                        fullDirty = true
                    }
                }
            }

            if (updateStageHudFade(session, nowMs, elapsedMs)) {
                stageDirty = true
            }

            if (fullDirty) {
                invalidateSessionWindow(session)
            } else if (stageDirty) {
                invalidateSessionStageOnly(session)
            }
        }
    }

    private fun updateStageHudFade(session: MacosWindowSession, nowMs: Double, dtMs: Double): Boolean {
        val stageBounds = session.renderedStageBounds
        if (stageBounds == null) {
            var changed = false
            if (session.overlayHudAlpha < 1f) {
                session.overlayHudAlpha = 1f
                changed = true
            }
            if (session.isStageCursorHidden) {
                NSCursor.unhide()
                session.isStageCursorHidden = false
            }
            return changed
        }

        val stageWidget = session.renderedStageWidget
        val overlayWidget = if (stageWidget != null) {
            MacosStageOverlayRenderUtil.findStageOverlayInSubtree(stageWidget, 4)
        } else {
            null
        }
        val activitySeq = overlayWidget?.attributes?.get("stageActivitySeq")?.toString().orEmpty()
        if (activitySeq.isNotEmpty() && activitySeq != session.lastStageActivitySeq) {
            session.lastStageActivitySeq = activitySeq
            session.lastStageActivityTimeMs = nowMs
        }
        val stagePlayingAttr = overlayWidget?.attributes?.get("stagePlaying")
        val isPausedStage = stagePlayingAttr == false || stagePlayingAttr == "false"
        val pointerLeftHidden = overlayWidget?.attributes?.get("hudPointerLeftHidden") == "true" && !session.isPointerInside

        val keepVisible = PopupMouseCaptureManager.hasActivePopup ||
            PointerDragManager.isDragging ||
            session.focusedWidget != null ||
            isPausedStage ||
            (!pointerLeftHidden && (nowMs - session.lastStageActivityTimeMs) < STAGE_HUD_IDLE_HIDE_MS)

        val targetAlpha = if (keepVisible) 1f else 0f
        val step = (dtMs / 220.0).toFloat().coerceIn(0.01f, 1f)
        val prevAlpha = session.overlayHudAlpha
        session.overlayHudAlpha = when {
            abs(prevAlpha - targetAlpha) <= step -> targetAlpha
            prevAlpha < targetAlpha -> (prevAlpha + step).coerceAtMost(1f)
            else -> (prevAlpha - step).coerceAtLeast(0f)
        }

        val pointerOverStage = session.isPointerInside &&
            stageBounds.contains(session.lastMouseX.roundToInt(), session.lastMouseY.roundToInt())
        val shouldHideCursor = !keepVisible && session.overlayHudAlpha <= 0.02f && pointerOverStage
        if (shouldHideCursor && !session.isStageCursorHidden) {
            NSCursor.hide()
            session.isStageCursorHidden = true
        } else if (!shouldHideCursor && session.isStageCursorHidden) {
            NSCursor.unhide()
            session.isStageCursorHidden = false
        }

        return abs(session.overlayHudAlpha - prevAlpha) > 0.001f
    }

    private fun restoreHiddenCursors() {
        for (session in sessions.values) {
            if (session.isStageCursorHidden) {
                NSCursor.unhide()
                session.isStageCursorHidden = false
            }
        }
    }

    private fun sumInteractiveSurfaceSwaps(node: DesktopNativeWidget): Long {
        if (!node.visible || node.disposed) return 0L
        var sum = node.interactiveSurfaceSpec?.swapCount?.invoke() ?: 0L
        for (child in node.children) {
            sum += sumInteractiveSurfaceSwaps(child)
        }
        return sum
    }

    private fun containsVisibleSpinner(node: DesktopNativeWidget): Boolean {
        if (!node.visible || node.disposed) return false
        if (node.attributes["nativeImpl"] is DesktopSpinnerNativeImpl<*>) return true
        for (child in node.children) {
            if (containsVisibleSpinner(child)) return true
        }
        return false
    }

    internal fun drawWindow(windowKey: Long, dirtyRect: CValue<NSRect>) {
        val session = sessions[windowKey] ?: return
        val cgContext = NSGraphicsContext.currentContext?.CGContext ?: return
        var dx = 0
        var dy = 0
        var dw = 0
        var dh = 0
        dirtyRect.useContents {
            dx = origin.x.toInt()
            dy = origin.y.toInt()
            dw = size.width.roundToInt()
            dh = size.height.roundToInt()
        }
        renderSessionIntoContext(
            session = session,
            cgContext = cgContext,
            dirtyX = dx,
            dirtyY = dy,
            dirtyW = dw,
            dirtyH = dh,
            allowStageFastPath = true,
            recordMetrics = true
        )
    }

    private fun renderSessionIntoContext(
        session: MacosWindowSession,
        cgContext: CGContextRef,
        dirtyX: Int,
        dirtyY: Int,
        dirtyW: Int,
        dirtyH: Int,
        allowStageFastPath: Boolean,
        recordMetrics: Boolean
    ) {
        val startUs = if (recordMetrics) monotonicTimeUs() else 0L

        val viewW = session.canvasView.bounds.useContents { size.width.roundToInt().coerceAtLeast(1) }
        val viewH = session.canvasView.bounds.useContents { size.height.roundToInt().coerceAtLeast(1) }
        val scale = session.nsWindow.backingScaleFactor.coerceAtLeast(1.0)

        val appInstance = Application.current
        val themeConfig = appInstance?.getThemeConfig() ?: ThemeConfig.DEFAULT
        val darkMode = isEffectiveDarkMode(themeConfig)
        val palette = MacosThemePalette.resolve(darkMode)

        val isFullscreen = (session.nsWindow.styleMask and NSWindowStyleMaskFullScreen) != 0UL
        val winImpl = session.windowImpl
        val dialogImpl = session.dialogImpl
        val chromeMode = if (winImpl != null && !isFullscreen && !winImpl.isFullscreen()) {
            resolveTitleBarChromeMode(winImpl)
        } else {
            CHROME_MODE_STANDARD
        }
        val customInset = if (chromeMode != CHROME_MODE_STANDARD) {
            session.titleBarInsetTopPx.takeIf { it > 0 } ?: CUSTOM_TITLEBAR_INSET_PX
        } else {
            0
        }
        val explicitCustomTb = chromeMode == CHROME_MODE_EXPLICIT_CUSTOM_TITLEBAR

        val titleBarWidgets = if (winImpl != null) collectTitleBarWidgets(winImpl) else emptySet()
        val renderCtx = MacosRenderContext(
            cgContext = cgContext,
            windowKey = session.windowKey,
            viewport = Rect(0, 0, viewW, viewH),
            backingScale = scale,
            darkMode = darkMode,
            themeConfig = themeConfig,
            palette = palette,
            customTitleBarInsetTop = customInset,
            explicitCustomTitleBar = explicitCustomTb,
            titleBarWidgets = titleBarWidgets,
            focusedWidget = session.focusedWidget,
            hoveredWidget = session.hoveredWidget,
            pressedWidget = session.pressedWidget,
            overlayHudAlpha = session.overlayHudAlpha
        )

        val nonOverlayVer = DesktopNativeWidget.globalNonOverlayMutationVersion
        val prevStageWidget = session.renderedStageWidget
        val prevStageBounds = session.renderedStageBounds
        val canUseStageFastPath = allowStageFastPath &&
            !session.pendingFullPaint &&
            dialogImpl == null &&
            winImpl != null &&
            !PopupMouseCaptureManager.hasActivePopup &&
            session.lastRenderedNonOverlayVersion == nonOverlayVer &&
            prevStageWidget != null &&
            prevStageWidget.visible &&
            !prevStageWidget.disposed &&
            prevStageBounds != null &&
            prevStageBounds.width > 0 &&
            prevStageBounds.height > 0 &&
            session.stageHitRegionStartIdx >= 0 &&
            session.stageHitRegionEndIdx >= session.stageHitRegionStartIdx &&
            session.stageHitRegionEndIdx <= session.hitRegions.size &&
            dirtyW > 0 &&
            dirtyH > 0 &&
            dirtyX >= prevStageBounds.x - 2 &&
            dirtyY >= prevStageBounds.y - 2 &&
            (dirtyX + dirtyW) <= prevStageBounds.right + 2 &&
            (dirtyY + dirtyH) <= prevStageBounds.bottom + 2

        if (canUseStageFastPath && prevStageWidget != null && prevStageBounds != null) {
            MacosWorkspaceLayoutRenderUtil.renderStageSubtreeOnly(renderCtx, prevStageWidget, prevStageBounds)

            val oldHits = session.hitRegions
            val newStageHits = renderCtx.hitRegions
            val hStart = session.stageHitRegionStartIdx
            val hEnd = session.stageHitRegionEndIdx
            val splicedHits = ArrayList<MacosHitRegion>(oldHits.size - (hEnd - hStart) + newStageHits.size)
            for (i in 0 until hStart) splicedHits.add(oldHits[i])
            splicedHits.addAll(newStageHits)
            for (i in hEnd until oldHits.size) splicedHits.add(oldHits[i])
            session.hitRegions = splicedHits
            session.stageHitRegionEndIdx = hStart + newStageHits.size

            val sStart = session.stageScrollRegionStartIdx
            val sEnd = session.stageScrollRegionEndIdx
            if (sStart >= 0 && sEnd >= sStart && sEnd <= session.scrollRegions.size) {
                val oldScrolls = session.scrollRegions
                val newStageScrolls = renderCtx.scrollRegions
                val splicedScrolls = ArrayList<MacosScrollRegion>(oldScrolls.size - (sEnd - sStart) + newStageScrolls.size)
                for (i in 0 until sStart) splicedScrolls.add(oldScrolls[i])
                splicedScrolls.addAll(newStageScrolls)
                for (i in sEnd until oldScrolls.size) splicedScrolls.add(oldScrolls[i])
                session.scrollRegions = splicedScrolls
                session.stageScrollRegionEndIdx = sStart + newStageScrolls.size
            }

            session.lastRenderedTreeVersion = DesktopNativeWidget.globalTreeMutationVersion
            if (sessions.size <= 1) {
                stageRedrawRequested = false
            }
            if (recordMetrics) {
                recordPaintMetricUs((monotonicTimeUs() - startUs).coerceAtLeast(1L), stageOnly = true)
            }
            return
        }

        if (dialogImpl != null) {
            MacosWorkspaceLayoutRenderUtil.renderDialogWindow(renderCtx, dialogImpl)
        } else if (winImpl != null) {
            val fsElem = (winImpl.getFullscreenElement()?.nativeWidget as? DesktopNativeWidget)
                ?.takeIf { winImpl.isFullscreen() }
            MacosWorkspaceLayoutRenderUtil.renderMainWindow(renderCtx, winImpl, fsElem)
        }

        if (allowStageFastPath) {
            session.hitRegions = renderCtx.hitRegions
            session.scrollRegions = renderCtx.scrollRegions
            session.renderedStageWidget = renderCtx.renderedStageWidget
            session.renderedStageBounds = renderCtx.renderedStageBounds
            session.stageHitRegionStartIdx = renderCtx.stageHitRegionStartIdx
            session.stageHitRegionEndIdx = renderCtx.stageHitRegionEndIdx
            session.stageScrollRegionStartIdx = renderCtx.stageScrollRegionStartIdx
            session.stageScrollRegionEndIdx = renderCtx.stageScrollRegionEndIdx
            session.lastRenderedTreeVersion = DesktopNativeWidget.globalTreeMutationVersion
            session.lastRenderedNonOverlayVersion = nonOverlayVer
            session.pendingFullPaint = false
            if (!sessions.values.any { it.pendingFullPaint }) {
                redrawRequested = false
                stageRedrawRequested = false
            }
        }

        if (recordMetrics) {
            recordPaintMetricUs((monotonicTimeUs() - startUs).coerceAtLeast(1L), stageOnly = false)
        }
    }

    private fun recordPaintMetricUs(durationUs: Long, stageOnly: Boolean) {
        totalPaintsCounter++
        if (stageOnly) {
            stageOnlyPaintsCounter++
        } else {
            fullWindowPaintsCounter++
        }
        paintDurationSamplesUs[paintSampleWriteIdx] = durationUs
        paintSampleWriteIdx = (paintSampleWriteIdx + 1) % paintDurationSamplesUs.size
        if (paintSampleCount < paintDurationSamplesUs.size) {
            paintSampleCount++
        }
    }

    private fun collectTitleBarWidgets(windowImpl: MacosWindowNativeImpl): Set<DesktopNativeWidget> {
        val tb = windowImpl.getTitleBarInstructions() ?: return emptySet()
        val all = tb.leadingComponents + tb.centerComponents + tb.trailingComponents
        if (all.isEmpty()) return emptySet()
        val set = LinkedHashSet<DesktopNativeWidget>(all.size)
        for (c in all) {
            val w = c.nativeImpl?.nativeWidget as? DesktopNativeWidget ?: continue
            set.add(w)
        }
        return set
    }

    private fun isEffectiveDarkMode(themeConfig: ThemeConfig): Boolean {
        val appTheme = Application.current?.getThemeMode() ?: themeConfig.mode
        return SystemThemeDetector.resolveTheme(appTheme) == ThemeMode.DARK
    }

    private fun applyAppearanceToWindow(nsWindow: NSWindow) {
        val themeConfig = Application.current?.getThemeConfig() ?: ThemeConfig.DEFAULT
        val dark = isEffectiveDarkMode(themeConfig)
        nsWindow.appearance = NSAppearance.appearanceNamed(
            if (dark) NSAppearanceNameDarkAqua else NSAppearanceNameAqua
        )
    }

    private fun resolveTitleBarChromeMode(windowImpl: MacosWindowNativeImpl): Int {
        if (windowImpl.isToolModalWindow) return CHROME_MODE_STANDARD
        if (windowImpl.isFullscreen() && windowImpl.getFullscreenElement() != null) return CHROME_MODE_STANDARD
        if (windowImpl.getTitleBarInstructions()?.customTitleBar == true) return CHROME_MODE_EXPLICIT_CUSTOM_TITLEBAR
        if (hasTopNavbar(windowImpl)) return CHROME_MODE_SEAMLESS_STACKED_NAVBAR
        return CHROME_MODE_STANDARD
    }

    private fun computeWindowStyleMask(
        windowImpl: MacosWindowNativeImpl,
        chromeMode: Int = resolveTitleBarChromeMode(windowImpl)
    ): ULong {
        var mask = NSWindowStyleMaskTitled or NSWindowStyleMaskClosable or NSWindowStyleMaskResizable
        val tb = windowImpl.getTitleBarInstructions()
        val allowMin = if (windowImpl.isToolModalWindow) windowImpl.minimizable else (tb?.showMinimizeButton ?: true)
        if (allowMin) {
            mask = mask or NSWindowStyleMaskMiniaturizable
        }
        if (chromeMode != CHROME_MODE_STANDARD) {
            mask = mask or NSWindowStyleMaskFullSizeContentView
        }
        if (windowImpl.isToolModalWindow) {
            mask = mask or NSWindowStyleMaskUtilityWindow
        }
        return mask
    }

    // =========================================================================
    // Mouse, Drag, Scroll, & Popup Handling
    // =========================================================================

    internal fun onMouseDown(windowKey: Long, event: NSEvent, button: Int) {
        val session = sessions[windowKey] ?: return
        val (x, y) = toViewCoords(session, event)
        handlePointerDownAt(
            session = session,
            x = x,
            y = y,
            button = button,
            clickCount = safeClickCount(event, 1),
            modifierFlags = event.modifierFlags,
            rawEvent = event
        )
    }

    private fun handlePointerDownAt(
        session: MacosWindowSession,
        x: Float,
        y: Float,
        button: Int,
        clickCount: Int,
        modifierFlags: ULong = 0UL,
        rawEvent: NSEvent? = null
    ) {
        activeKeyWindowId = session.windowKey
        session.lastMouseX = x
        session.lastMouseY = y
        session.isPointerInside = true
        notifyStageActivityIfInside(session, x, y)

        val hit = findTopHitRegion(session, x, y)
        val hitWidget = hit?.widget

        if (!hit?.isTextInput.orFalse() && session.focusedWidget != null && session.focusedWidget !== hitWidget) {
            clearFocusedWidgetInSession(session)
        }

        val interceptedByPopup = DesktopNativeWidget.interceptPopupPointerDown(x, y, hitWidget)
        if (interceptedByPopup) {
            session.pressedRegion = null
            session.pressedWidget = null
            invalidateSessionWindow(session)
            return
        }

        // Native custom-titlebar window drag / double-click zoom when clicking empty titlebar/navbar area
        if (button == 0 && hit == null && session.windowImpl != null) {
            val isFs = (session.nsWindow.styleMask and NSWindowStyleMaskFullScreen) != 0UL
            val chromeMode = resolveTitleBarChromeMode(session.windowImpl)
            val inset = session.titleBarInsetTopPx.takeIf { it > 0 } ?: CUSTOM_TITLEBAR_INSET_PX
            val dragHeight = when (chromeMode) {
                CHROME_MODE_SEAMLESS_STACKED_NAVBAR -> inset + MacosNavigationRenderUtil.NAVBAR_HEIGHT_PX
                CHROME_MODE_EXPLICIT_CUSTOM_TITLEBAR -> if (hasTopNavbar(session.windowImpl)) {
                    maxOf(inset, MacosNavigationRenderUtil.NAVBAR_HEIGHT_PX)
                } else {
                    inset + 8
                }
                else -> 0
            }
            if (!isFs && dragHeight > 0 && y >= 0f && y <= dragHeight.toFloat()) {
                if (clickCount >= 2) {
                    session.nsWindow.zoom(null)
                } else if (rawEvent != null) {
                    session.nsWindow.performWindowDragWithEvent(rawEvent)
                }
                return
            }
        }

        if (button == 2) {
            if (hit?.onContextMenu != null) {
                GenericUiThreading.runOnLogic {
                    hit.onContextMenu.invoke(x, y)
                }
                invalidateSessionWindow(session)
            }
            return
        }

        session.pressedRegion = hit
        session.pressedWidget = hitWidget
        val ptrEvent = buildPointerEventFromFlags(modifierFlags, x, y, button = button, buttonsMask = 1 shl button, clickCount = clickCount)

        GenericUiThreading.runOnLogic {
            if (hit != null) {
                hit.onPress?.invoke(x - hit.left, y - hit.top, x, y, button, clickCount)
            }
            dispatchWidgetPointerDown(hitWidget, ptrEvent)
        }
        if (hitWidget != null && isWidgetInsideStage(session, hitWidget)) {
            invalidateSessionStageOnly(session)
        } else {
            invalidateSessionWindow(session)
        }
    }

    internal fun onMouseDragged(windowKey: Long, event: NSEvent, button: Int) {
        val session = sessions[windowKey] ?: return
        val (x, y) = toViewCoords(session, event)
        handlePointerDraggedAt(
            session = session,
            x = x,
            y = y,
            button = button,
            clickCount = safeClickCount(event, 1),
            modifierFlags = event.modifierFlags
        )
    }

    private fun handlePointerDraggedAt(
        session: MacosWindowSession,
        x: Float,
        y: Float,
        button: Int,
        clickCount: Int = 1,
        modifierFlags: ULong = 0UL
    ) {
        session.lastMouseX = x
        session.lastMouseY = y
        notifyStageActivityIfInside(session, x, y)

        val ptrEvent = buildPointerEventFromFlags(modifierFlags, x, y, button = button, buttonsMask = 1 shl button, clickCount = clickCount)
        val pressed = session.pressedRegion
        GenericUiThreading.runOnLogic {
            if (pressed != null) {
                pressed.onDrag?.invoke(x - pressed.left, y - pressed.top, x, y)
            }
            PointerDragManager.notifyGlobalPointerMove(ptrEvent)
            dispatchWidgetPointerMove(pressed?.widget, ptrEvent)
        }
        if (pressed?.widget != null && isWidgetInsideStage(session, pressed.widget)) {
            invalidateSessionStageOnly(session)
        } else {
            invalidateSessionWindow(session)
        }
    }

    internal fun onMouseUp(windowKey: Long, event: NSEvent, button: Int) {
        val session = sessions[windowKey] ?: return
        val (x, y) = toViewCoords(session, event)
        handlePointerUpAt(
            session = session,
            x = x,
            y = y,
            button = button,
            clickCount = safeClickCount(event, 1),
            modifierFlags = event.modifierFlags
        )
    }

    private fun handlePointerUpAt(
        session: MacosWindowSession,
        x: Float,
        y: Float,
        button: Int,
        clickCount: Int = 1,
        modifierFlags: ULong = 0UL
    ) {
        session.lastMouseX = x
        session.lastMouseY = y
        notifyStageActivityIfInside(session, x, y)

        val pressed = session.pressedRegion
        val prevPressedWidget = session.pressedWidget
        session.pressedRegion = null
        session.pressedWidget = null

        val hitNow = findTopHitRegion(session, x, y)
        val ptrEvent = buildPointerEventFromFlags(modifierFlags, x, y, button = button, buttonsMask = 0, clickCount = clickCount)

        val swallowedUp = DesktopNativeWidget.interceptPopupPointerUp(x, y, hitNow?.widget)
        GenericUiThreading.runOnLogic {
            if (pressed != null) {
                val inside = pressed.contains(x.roundToInt(), y.roundToInt())
                pressed.onRelease?.invoke(x - pressed.left, y - pressed.top, x, y, inside)
            }
            val wasDragging = PointerDragManager.notifyGlobalPointerUp(ptrEvent)
            dispatchWidgetPointerUp(pressed?.widget ?: hitNow?.widget, ptrEvent)

            if (!swallowedUp && button == 0) {
                val swallowedClick = DesktopNativeWidget.interceptPopupClick(x, y, hitNow?.widget)
                if (!swallowedClick && pressed != null && hitNow?.widget === pressed.widget) {
                    if (clickCount >= 2 && pressed.onDoubleClick != null) {
                        pressed.onDoubleClick.invoke()
                    } else if (!wasDragging || pressed.onDrag == null) {
                        pressed.onClick?.invoke()
                    }
                }
            }
        }
        updateSessionHoverWithFlags(session, x, y, modifierFlags)
        val stageLocalRelease = !swallowedUp &&
            (prevPressedWidget == null || isWidgetInsideStage(session, prevPressedWidget)) &&
            (hitNow?.widget == null || isWidgetInsideStage(session, hitNow.widget)) &&
            (prevPressedWidget != null || hitNow?.widget != null)
        if (stageLocalRelease) {
            invalidateSessionStageOnly(session)
        } else {
            invalidateSessionWindow(session)
        }
    }

    internal fun onMouseMoved(windowKey: Long, event: NSEvent) {
        val session = sessions[windowKey] ?: return
        val (x, y) = toViewCoords(session, event)
        handlePointerMovedAt(session, x, y, event.modifierFlags)
    }

    private fun handlePointerMovedAt(
        session: MacosWindowSession,
        x: Float,
        y: Float,
        modifierFlags: ULong = 0UL
    ) {
        val moved = abs(x - session.lastMouseX) > 0.5f || abs(y - session.lastMouseY) > 0.5f
        session.lastMouseX = x
        session.lastMouseY = y
        session.isPointerInside = true
        if (moved) {
            notifyStageActivityIfInside(session, x, y)
        }
        updateSessionHoverWithFlags(session, x, y, modifierFlags)
    }

    internal fun onMouseEntered(windowKey: Long, event: NSEvent) {
        val session = sessions[windowKey] ?: return
        session.isPointerInside = true
        val (x, y) = toViewCoords(session, event)
        session.lastMouseX = x
        session.lastMouseY = y
        notifyStageActivityIfInside(session, x, y)
        updateSessionHoverWithFlags(session, x, y, event.modifierFlags)
    }

    internal fun onMouseExited(windowKey: Long) {
        val session = sessions[windowKey] ?: return
        session.isPointerInside = false
        if (session.isStageCursorHidden) {
            NSCursor.unhide()
            session.isStageCursorHidden = false
        }
        val prevHovered = session.hoveredWidget
        if (prevHovered != null) {
            session.hoveredRegion = null
            session.hoveredWidget = null
            val exitEvt = PointerEvent(x = session.lastMouseX, y = session.lastMouseY)
            GenericUiThreading.runOnLogic {
                dispatchAncestorHoverTransition(prevHovered, null, exitEvt)
            }
            if (isWidgetInsideStage(session, prevHovered)) {
                invalidateSessionStageOnly(session)
            } else {
                invalidateSessionWindow(session)
            }
        }
        if (session.lastAppliedCursorKey != "arrow") {
            session.lastAppliedCursorKey = "arrow"
            NSCursor.arrowCursor.set()
        }
    }

    internal fun onScrollWheel(windowKey: Long, event: NSEvent) {
        val session = sessions[windowKey] ?: return
        val (x, y) = toViewCoords(session, event)
        val rawDy = event.scrollingDeltaY
        val deltaPx = if (event.hasPreciseScrollingDeltas) {
            (-rawDy).toFloat()
        } else {
            (-rawDy * 28.0).toFloat()
        }
        handleScrollWheelAt(session, x, y, deltaPx)
    }

    private fun handleScrollWheelAt(
        session: MacosWindowSession,
        x: Float,
        y: Float,
        deltaPx: Float
    ) {
        notifyStageActivityIfInside(session, x, y)
        if (abs(deltaPx) < 0.05f) return

        val xi = x.roundToInt()
        val yi = y.roundToInt()
        val regions = session.scrollRegions
        for (i in regions.indices.reversed()) {
            val r = regions[i]
            if (r.contains(xi, yi)) {
                var consumed = false
                GenericUiThreading.runOnLogic {
                    consumed = r.onScrollBy(deltaPx)
                }
                if (consumed) {
                    if (isWidgetInsideStage(session, r.widget)) {
                        invalidateSessionStageOnly(session)
                    } else {
                        invalidateSessionWindow(session)
                    }
                    return
                }
            }
        }
    }

    private fun updateSessionHoverWithFlags(
        session: MacosWindowSession,
        x: Float,
        y: Float,
        modifierFlags: ULong = 0UL
    ) {
        val hit = findTopHitRegion(session, x, y)
        val nextWidget = hit?.widget
        val prevWidget = session.hoveredWidget
        session.hoveredRegion = hit

        if (nextWidget !== prevWidget) {
            session.hoveredWidget = nextWidget
            val ptrEvt = buildPointerEventFromFlags(modifierFlags, x, y, button = 0, buttonsMask = 0, clickCount = 0)
            GenericUiThreading.runOnLogic {
                dispatchAncestorHoverTransition(prevWidget, nextWidget, ptrEvt)
            }
            val stageOnlyHover = (prevWidget == null || isWidgetInsideStage(session, prevWidget)) &&
                (nextWidget == null || isWidgetInsideStage(session, nextWidget))
            if (stageOnlyHover) {
                invalidateSessionStageOnly(session)
            } else {
                invalidateSessionWindow(session)
            }
        } else if (nextWidget != null) {
            val ptrEvt = buildPointerEventFromFlags(modifierFlags, x, y, button = 0, buttonsMask = 0, clickCount = 0)
            GenericUiThreading.runOnLogic {
                dispatchWidgetPointerMove(nextWidget, ptrEvt)
            }
        }

        // Enforce Zero Default Tooltips (AGENTS.md §12): only set toolTip when explicitly assigned
        val explicitTip = hit?.tooltip?.takeIf { it.isNotEmpty() }
        if (session.canvasView.toolTip != explicitTip) {
            session.canvasView.toolTip = explicitTip
        }

        applyCursorForRegion(session, hit)
    }

    private fun dispatchAncestorHoverTransition(
        prevWidget: DesktopNativeWidget?,
        nextWidget: DesktopNativeWidget?,
        event: PointerEvent
    ) {
        val nextAncestors = HashSet<DesktopNativeWidget>(12)
        var curNext = nextWidget
        while (curNext != null) {
            nextAncestors.add(curNext)
            curNext = curNext.parentWidget
        }

        val prevAncestors = HashSet<DesktopNativeWidget>(12)
        var curPrev = prevWidget
        while (curPrev != null) {
            prevAncestors.add(curPrev)
            if (curPrev !in nextAncestors) {
                (curPrev.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.dispatchPointerLeave(event)
            }
            curPrev = curPrev.parentWidget
        }

        val entered = ArrayList<DesktopNativeWidget>(8)
        curNext = nextWidget
        while (curNext != null) {
            if (curNext !in prevAncestors) {
                entered.add(curNext)
            }
            curNext = curNext.parentWidget
        }
        for (i in entered.indices.reversed()) {
            (entered[i].attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.dispatchPointerEnter(event)
        }
    }

    private fun updateActiveCursor() {
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !NSThread.isMainThread) {
            dispatch_async(dispatch_get_main_queue()) { updateActiveCursor() }
            return
        }
        val key = activeKeyWindowId ?: return
        val session = sessions[key] ?: return
        applyCursorForRegion(session, session.hoveredRegion)
    }

    private fun applyCursorForRegion(session: MacosWindowSession, region: MacosHitRegion?) {
        if (session.isStageCursorHidden) return
        val rawCss = (region?.cursorCss ?: (region?.widget?.attributes?.get("cursor") as? String))
            ?.lowercase()
            ?.trim()
        val cursorKey: String
        val cursor: NSCursor = when (rawCss) {
            "pointer", "hand" -> {
                cursorKey = "pointer"
                NSCursor.pointingHandCursor
            }
            "text", "ibeam" -> {
                cursorKey = "ibeam"
                NSCursor.IBeamCursor
            }
            "crosshair" -> {
                cursorKey = "crosshair"
                NSCursor.crosshairCursor
            }
            "col-resize", "ew-resize" -> {
                cursorKey = "ew-resize"
                NSCursor.resizeLeftRightCursor
            }
            "row-resize", "ns-resize" -> {
                cursorKey = "ns-resize"
                NSCursor.resizeUpDownCursor
            }
            "not-allowed", "no-drop" -> {
                cursorKey = "not-allowed"
                NSCursor.operationNotAllowedCursor
            }
            else -> if (region?.isTextInput == true) {
                cursorKey = "ibeam"
                NSCursor.IBeamCursor
            } else {
                cursorKey = "arrow"
                NSCursor.arrowCursor
            }
        }
        if (session.lastAppliedCursorKey != cursorKey) {
            session.lastAppliedCursorKey = cursorKey
            cursor.set()
        }
    }

    private fun notifyStageActivityIfInside(session: MacosWindowSession, x: Float, y: Float) {
        val stage = session.renderedStageBounds ?: return
        if (stage.contains(x.roundToInt(), y.roundToInt())) {
            session.lastStageActivityTimeMs = monotonicTimeMs()
            if (session.isStageCursorHidden) {
                NSCursor.unhide()
                session.isStageCursorHidden = false
            }
            if (session.overlayHudAlpha < 1f) {
                invalidateSessionStageOnly(session)
            }
        }
    }

    private fun findTopHitRegion(session: MacosWindowSession, x: Float, y: Float): MacosHitRegion? {
        val xi = x.roundToInt()
        val yi = y.roundToInt()
        val list = session.hitRegions
        for (i in list.indices.reversed()) {
            val r = list[i]
            if (r.contains(xi, yi)) return r
        }
        return null
    }

    private fun hasTopNavbar(windowImpl: MacosWindowNativeImpl): Boolean {
        val explicit = (windowImpl.nativeWidget.attributes["navBar"] as? DesktopBaseNativeImpl<*>)?.nativeWidget
        if (explicit != null && explicit.visible && !explicit.disposed) return true
        return MacosWorkspaceLayoutRenderUtil.hasTopNavbarInSubtree(windowImpl.nativeWidget)
    }

    private fun toViewCoords(session: MacosWindowSession, event: NSEvent): Pair<Float, Float> {
        val pt = session.canvasView.convertPoint(event.locationInWindow, fromView = null)
        return pt.useContents { x.toFloat() to y.toFloat() }
    }

    private fun safeClickCount(event: NSEvent, defaultCount: Int = 0): Int {
        return when (event.type) {
            NSEventTypeLeftMouseDown,
            NSEventTypeLeftMouseUp,
            NSEventTypeRightMouseDown,
            NSEventTypeRightMouseUp,
            NSEventTypeOtherMouseDown,
            NSEventTypeOtherMouseUp,
            NSEventTypeLeftMouseDragged,
            NSEventTypeRightMouseDragged,
            NSEventTypeOtherMouseDragged -> event.clickCount.toInt().coerceAtLeast(1)
            else -> defaultCount
        }
    }

    private fun buildPointerEventFromFlags(
        flags: ULong,
        x: Float,
        y: Float,
        button: Int,
        buttonsMask: Int,
        clickCount: Int
    ): PointerEvent {
        return PointerEvent(
            x = x,
            y = y,
            screenX = x,
            screenY = y,
            button = button,
            buttonsMask = buttonsMask,
            shiftKey = (flags and NSEventModifierFlagShift) != 0UL,
            ctrlKey = (flags and NSEventModifierFlagControl) != 0UL,
            altKey = (flags and NSEventModifierFlagOption) != 0UL,
            metaKey = (flags and NSEventModifierFlagCommand) != 0UL,
            clickCount = clickCount
        )
    }

    private fun dispatchWidgetPointerDown(widget: DesktopNativeWidget?, event: PointerEvent) {
        var cur = widget
        while (cur != null) {
            val impl = cur.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
            if (impl != null) {
                val b = cur.bounds
                val local = event.copy(x = event.screenX - b.x, y = event.screenY - b.y)
                impl.dispatchPointerDown(local)
                break
            }
            cur = cur.parentWidget
        }
    }

    private fun dispatchWidgetPointerMove(widget: DesktopNativeWidget?, event: PointerEvent) {
        var cur = widget
        while (cur != null) {
            val impl = cur.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
            if (impl != null) {
                val b = cur.bounds
                val local = event.copy(x = event.screenX - b.x, y = event.screenY - b.y)
                impl.dispatchPointerMove(local)
                break
            }
            cur = cur.parentWidget
        }
    }

    private fun dispatchWidgetPointerUp(widget: DesktopNativeWidget?, event: PointerEvent) {
        var cur = widget
        while (cur != null) {
            val impl = cur.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
            if (impl != null) {
                val b = cur.bounds
                val local = event.copy(x = event.screenX - b.x, y = event.screenY - b.y)
                impl.dispatchPointerUp(local)
                break
            }
            cur = cur.parentWidget
        }
    }

    // =========================================================================
    // Focus, Keyboard, Clipboard, & Native Select/Dropdown Popups
    // =========================================================================

    private fun focusTextInput(inputImpl: DesktopTextInputNativeImpl<DesktopNativeWidget>) {
        if (!inputImpl.isEnabled()) return
        val session = findSessionContainingWidget(inputImpl.nativeWidget) ?: activeSession() ?: return
        if (session.focusedWidget !== inputImpl.nativeWidget) {
            clearFocusedWidgetInSession(session)
            session.focusedWidget = inputImpl.nativeWidget
            session.selectAllPendingReplace = false
            GenericUiThreading.runOnLogic {
                inputImpl.setFocusedState(true)
            }
        }
        session.nsWindow.makeFirstResponder(session.canvasView)
        session.canvasView.setNeedsDisplay(true)
    }

    private fun clearFocusedWidgetInSession(session: MacosWindowSession) {
        val prev = session.focusedWidget ?: return
        session.focusedWidget = null
        session.selectAllPendingReplace = false
        val prevImpl = prev.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        if (prevImpl != null) {
            GenericUiThreading.runOnLogic {
                prevImpl.setFocusedState(false)
            }
        }
    }

    private fun openSelectPopup(selectImpl: DesktopSelectNativeImpl<DesktopNativeWidget>, bounds: Rect) {
        if (!selectImpl.isEnabled()) return
        val session = findSessionContainingWidget(selectImpl.nativeWidget) ?: activeSession() ?: return
        dispatch_async(dispatch_get_main_queue()) {
            MacosMenuBarBridge.popUpSelectMenu(
                options = selectImpl.getOptions(),
                selectedValue = selectImpl.getSelectedValue(),
                anchorBounds = bounds,
                inView = session.canvasView
            ) { selectedValue ->
                selectImpl.notifyUserSelectionChanged(selectedValue)
                requestRedraw()
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun openDropdownPopup(dropdownImpl: DesktopDropdownNativeImpl<DesktopNativeWidget>, bounds: Rect) {
        if (!dropdownImpl.isEnabled()) return
        val session = findSessionContainingWidget(dropdownImpl.nativeWidget) ?: activeSession() ?: return
        val items: List<Pair<String, String>> = when (dropdownImpl) {
            is MacosDropdownNativeImpl -> dropdownImpl.getDropdownItems()
            else -> (dropdownImpl.nativeWidget.attributes["items"] as? List<Pair<String, String>>) ?: emptyList()
        }
        dispatch_async(dispatch_get_main_queue()) {
            MacosMenuBarBridge.popUpDropdownMenu(
                items = items,
                anchorBounds = bounds,
                inView = session.canvasView
            ) { itemId ->
                if (dropdownImpl is MacosDropdownNativeImpl) {
                    dropdownImpl.notifyUserItemSelected(itemId)
                }
                requestRedraw()
            }
        }
    }

    internal fun onPerformKeyEquivalent(windowKey: Long, event: NSEvent): Boolean {
        val flags = event.modifierFlags
        val cmd = (flags and NSEventModifierFlagCommand) != 0UL
        if (!cmd) return false
        val session = sessions[windowKey] ?: return false
        val inputImpl = focusedTextInput(session)
        val ch = event.charactersIgnoringModifiers?.lowercase() ?: return false
        if (inputImpl != null) {
            when (ch) {
                "v" -> {
                    performTextPaste()
                    return true
                }
                "c" -> {
                    performTextCopy()
                    return true
                }
                "x" -> {
                    performTextCut()
                    return true
                }
                "a" -> {
                    performTextSelectAll()
                    return true
                }
            }
        }
        return false
    }

    internal fun onKeyDown(windowKey: Long, event: NSEvent) {
        val session = sessions[windowKey] ?: return
        session.lastStageActivityTimeMs = monotonicTimeMs()
        val inputImpl = focusedTextInput(session)
        val keyEvent = mapNsKeyEvent(event, isTargetTextInput = inputImpl != null)

        if (keyEvent.key == "Escape") {
            if (inputImpl != null) {
                clearFocusedWidgetInSession(session)
                session.canvasView.setNeedsDisplay(true)
                return
            }
            if (session.dialogImpl != null) {
                val dlg = session.dialogImpl
                GenericUiThreading.runOnLogic {
                    dlg.closeDialog()
                }
                return
            }
        }

        if (inputImpl != null && !keyEvent.metaKey && !keyEvent.ctrlKey) {
            if (handleTextInputKeyDown(session, inputImpl, event, keyEvent)) {
                session.canvasView.setNeedsDisplay(true)
                return
            }
        }

        GenericUiThreading.runOnLogic {
            session.windowImpl?.dispatchKeyDown(keyEvent)
            (session.dialogImpl as? DesktopBaseNativeImpl<*>)?.dispatchKeyDown(keyEvent)
        }
        session.canvasView.setNeedsDisplay(true)
    }

    internal fun onKeyUp(windowKey: Long, event: NSEvent) {
        val session = sessions[windowKey] ?: return
        val inputImpl = focusedTextInput(session)
        val keyEvent = mapNsKeyEvent(event, isTargetTextInput = inputImpl != null)
        GenericUiThreading.runOnLogic {
            session.windowImpl?.dispatchKeyUp(keyEvent)
            (session.dialogImpl as? DesktopBaseNativeImpl<*>)?.dispatchKeyUp(keyEvent)
        }
    }

    private fun handleTextInputKeyDown(
        session: MacosWindowSession,
        inputImpl: DesktopTextInputNativeImpl<*>,
        nsEvent: NSEvent,
        keyEvent: KeyEvent
    ): Boolean {
        if (!inputImpl.isEnabled()) return false
        if (inputImpl.isReadOnly()) return keyEvent.key != "Tab"

        when (keyEvent.key) {
            "Backspace", "Delete" -> {
                val cur = inputImpl.getValue()
                if (cur.isNotEmpty()) {
                    val next = if (session.selectAllPendingReplace || nsEvent.modifierFlags and NSEventModifierFlagCommand != 0UL) {
                        ""
                    } else {
                        dropLastCodePoint(cur)
                    }
                    session.selectAllPendingReplace = false
                    GenericUiThreading.runOnLogic {
                        inputImpl.setValue(next)
                    }
                }
                return true
            }
            "Enter" -> {
                if (inputImpl.isMultiline() && !keyEvent.metaKey && !keyEvent.ctrlKey) {
                    val base = if (session.selectAllPendingReplace) "" else inputImpl.getValue()
                    session.selectAllPendingReplace = false
                    GenericUiThreading.runOnLogic {
                        inputImpl.setValue("$base\n")
                    }
                } else {
                    session.selectAllPendingReplace = false
                    GenericUiThreading.runOnLogic {
                        inputImpl.submitValue()
                    }
                }
                return true
            }
            "Tab" -> {
                cycleTextInputFocus(session, reverse = keyEvent.shiftKey)
                return true
            }
            "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown", "Home", "End" -> {
                session.selectAllPendingReplace = false
                return true
            }
        }

        val chars = nsEvent.characters ?: return false
        val printable = buildString {
            for (ch in chars) {
                val code = ch.code
                // Skip ASCII control chars and macOS Private Use Area function-key codes (0xF700..0xF8FF)
                if (code >= 0x20 && code != 0x7F && code !in 0xF700..0xF8FF) {
                    append(ch)
                }
            }
        }
        if (printable.isNotEmpty()) {
            val base = if (session.selectAllPendingReplace) "" else inputImpl.getValue()
            session.selectAllPendingReplace = false
            GenericUiThreading.runOnLogic {
                inputImpl.setValue(base + printable)
            }
            return true
        }
        return false
    }

    private fun cycleTextInputFocus(session: MacosWindowSession, reverse: Boolean) {
        val inputs = session.hitRegions
            .mapNotNull { it.widget?.attributes?.get("nativeImpl") as? DesktopTextInputNativeImpl<*> }
            .distinct()
            .filter { it.isEnabled() && it.isVisible() }
        if (inputs.isEmpty()) return
        val curIdx = inputs.indexOfFirst { it.nativeWidget === session.focusedWidget }
        val nextIdx = if (curIdx < 0) {
            0
        } else if (reverse) {
            (curIdx - 1 + inputs.size) % inputs.size
        } else {
            (curIdx + 1) % inputs.size
        }
        val target = inputs[nextIdx]
        focusTextInput(target)
    }

    fun performTextPaste() {
        val session = activeSession() ?: return
        val inputImpl = focusedTextInput(session) ?: return
        if (!inputImpl.isEnabled() || inputImpl.isReadOnly()) return
        val rawClip = NSPasteboard.generalPasteboard.stringForType(NSPasteboardTypeString) ?: return
        val sanitized = if (inputImpl.isMultiline()) {
            rawClip.replace("\r\n", "\n").replace('\r', '\n')
        } else {
            rawClip.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        }
        if (sanitized.isEmpty()) return
        val base = if (session.selectAllPendingReplace) "" else inputImpl.getValue()
        session.selectAllPendingReplace = false
        GenericUiThreading.runOnLogic {
            inputImpl.setValue(base + sanitized)
        }
        session.canvasView.setNeedsDisplay(true)
    }

    fun performTextCopy() {
        val session = activeSession() ?: return
        val inputImpl = focusedTextInput(session) ?: return
        if (inputImpl.isPasswordMode()) return
        val text = inputImpl.getValue()
        if (text.isEmpty()) return
        val pb = NSPasteboard.generalPasteboard
        pb.clearContents()
        pb.setString(text, forType = NSPasteboardTypeString)
    }

    fun performTextCut() {
        val session = activeSession() ?: return
        val inputImpl = focusedTextInput(session) ?: return
        if (!inputImpl.isEnabled() || inputImpl.isReadOnly() || inputImpl.isPasswordMode()) return
        val text = inputImpl.getValue()
        if (text.isEmpty()) return
        val pb = NSPasteboard.generalPasteboard
        pb.clearContents()
        pb.setString(text, forType = NSPasteboardTypeString)
        session.selectAllPendingReplace = false
        GenericUiThreading.runOnLogic {
            inputImpl.setValue("")
        }
        session.canvasView.setNeedsDisplay(true)
    }

    fun performTextSelectAll() {
        val session = activeSession() ?: return
        val inputImpl = focusedTextInput(session) ?: return
        if (inputImpl.getValue().isNotEmpty()) {
            session.selectAllPendingReplace = true
            session.canvasView.setNeedsDisplay(true)
        }
    }

    private fun focusedTextInput(session: MacosWindowSession): DesktopTextInputNativeImpl<*>? =
        session.focusedWidget?.attributes?.get("nativeImpl") as? DesktopTextInputNativeImpl<*>

    private fun activeSession(): MacosWindowSession? {
        activeKeyWindowId?.let { sessions[it]?.let { s -> return s } }
        return sessions.values.lastOrNull()
    }

    private fun findSessionContainingWidget(widget: DesktopNativeWidget): MacosWindowSession? {
        for (session in sessions.values) {
            val root = session.dialogImpl?.nativeWidget ?: session.windowImpl?.nativeWidget ?: continue
            if (root.containsNativeWidget(widget)) return session
        }
        return null
    }

    private fun dropLastCodePoint(text: String): String {
        if (text.isEmpty()) return ""
        if (text.length >= 2 && text[text.length - 1].isLowSurrogate() && text[text.length - 2].isHighSurrogate()) {
            return text.substring(0, text.length - 2)
        }
        return text.substring(0, text.length - 1)
    }

    private fun mapNsKeyEvent(event: NSEvent, isTargetTextInput: Boolean): KeyEvent {
        val code = event.keyCode.toInt()
        val flags = event.modifierFlags
        val keyName = when (code) {
            36, 76 -> "Enter"
            51 -> "Backspace"
            117 -> "Delete"
            53 -> "Escape"
            48 -> "Tab"
            49 -> " "
            123 -> "ArrowLeft"
            124 -> "ArrowRight"
            126 -> "ArrowUp"
            125 -> "ArrowDown"
            115 -> "Home"
            119 -> "End"
            116 -> "PageUp"
            121 -> "PageDown"
            122 -> "F1"
            120 -> "F2"
            99 -> "F3"
            118 -> "F4"
            96 -> "F5"
            97 -> "F6"
            98 -> "F7"
            100 -> "F8"
            101 -> "F9"
            109 -> "F10"
            103 -> "F11"
            111 -> "F12"
            else -> event.charactersIgnoringModifiers?.takeIf { it.isNotEmpty() } ?: "Unidentified"
        }
        return KeyEvent(
            key = keyName,
            code = keyName,
            keyCode = code,
            shiftKey = (flags and NSEventModifierFlagShift) != 0UL,
            ctrlKey = (flags and NSEventModifierFlagControl) != 0UL,
            altKey = (flags and NSEventModifierFlagOption) != 0UL,
            metaKey = (flags and NSEventModifierFlagCommand) != 0UL,
            isRepeat = event.isARepeat(),
            isTargetTextInput = isTargetTextInput
        )
    }

    // =========================================================================
    // Visual Test Driver, Screenshot Capture, & Paint Performance Telemetry
    // =========================================================================

    fun queryClientRect(): Rect {
        val session = activeSession() ?: return Rect(0, 0, 0, 0)
        var w = 0
        var h = 0
        session.canvasView.bounds.useContents {
            w = size.width.roundToInt().coerceAtLeast(0)
            h = size.height.roundToInt().coerceAtLeast(0)
        }
        return Rect(0, 0, w, h)
    }

    fun invalidateAllWindows(stageOnly: Boolean = false) {
        if (!appInitialized || !MacosApplicationNativeImpl.interactiveMacosGuiEnabled) return
        runOnMainAppKitThread {
            for (session in sessions.values) {
                if (stageOnly) {
                    invalidateSessionStageOnly(session)
                } else {
                    invalidateSessionWindow(session)
                }
            }
        }
    }

    fun forceSynchronousPaint(stageOnly: Boolean = false) {
        if (!appInitialized || !MacosApplicationNativeImpl.interactiveMacosGuiEnabled) return
        runOnMainAppKitThread {
            for (session in sessions.values) {
                if (stageOnly) {
                    invalidateSessionStageOnly(session)
                } else {
                    invalidateSessionWindow(session)
                }
                session.canvasView.displayIfNeeded()
            }
        }
    }

    fun pumpVisualWindowEvents(durationMs: Long = 32L) {
        if (!appInitialized || !MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            GenericUiThreading.flushAllQueues()
            return
        }
        val app = NSApplication.sharedApplication
        val startMs = monotonicTimeMs()
        val targetMs = durationMs.coerceAtLeast(0L).toDouble()
        do {
            CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.002, false)
            app.updateWindows()
            onFrameTimerTick()
            for (session in sessions.values.toList()) {
                session.canvasView.displayIfNeeded()
            }
            val nowMs = monotonicTimeMs()
            if (nowMs - startMs < targetMs && sessions.isNotEmpty()) {
                usleep(3_000u)
            } else {
                break
            }
        } while (sessions.isNotEmpty())
    }

    fun closeAllVisualWindows() {
        for (session in sessions.values.toList()) {
            clearFocusedWidgetInSession(session)
            session.nsWindow.delegate = null
            session.nsWindow.orderOut(null)
        }
        sessions.clear()
        activeKeyWindowId = null
        frameTimer?.invalidate()
        frameTimer = null
        eventLoopRunning = false
        restoreHiddenCursors()
    }

    fun captureWindowScreenshot(width: Int = 0, height: Int = 0): ImageBuffer? {
        val session = activeSession() ?: return null
        session.canvasView.displayIfNeeded()

        val viewW = session.canvasView.bounds.useContents { size.width.roundToInt().coerceAtLeast(1) }
        val viewH = session.canvasView.bounds.useContents { size.height.roundToInt().coerceAtLeast(1) }
        val targetW = if (width > 0) width else viewW
        val targetH = if (height > 0) height else viewH
        if (targetW <= 0 || targetH <= 0) return null

        val premul = ByteArray(targetW * targetH * 4)
        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
        try {
            premul.usePinned { pinned ->
                val cgCtx = CGBitmapContextCreate(
                    data = pinned.addressOf(0),
                    width = targetW.convert(),
                    height = targetH.convert(),
                    bitsPerComponent = 8.convert(),
                    bytesPerRow = (targetW * 4).convert(),
                    space = colorSpace,
                    bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrderDefault
                ) ?: return null
                val prevNsCtx = NSGraphicsContext.currentContext
                try {
                    // Match flipped NSView coordinates: (0, 0) at top-left, +Y downward
                    CGContextTranslateCTM(cgCtx, 0.0, targetH.toDouble())
                    CGContextScaleCTM(cgCtx, 1.0, -1.0)
                    if (targetW != viewW || targetH != viewH) {
                        CGContextScaleCTM(
                            cgCtx,
                            targetW.toDouble() / viewW.toDouble(),
                            targetH.toDouble() / viewH.toDouble()
                        )
                    }
                    val nsCtx = NSGraphicsContext.graphicsContextWithCGContext(cgCtx, flipped = true)
                    NSGraphicsContext.setCurrentContext(nsCtx)
                    renderSessionIntoContext(
                        session = session,
                        cgContext = cgCtx,
                        dirtyX = 0,
                        dirtyY = 0,
                        dirtyW = viewW,
                        dirtyH = viewH,
                        allowStageFastPath = false,
                        recordMetrics = false
                    )
                } finally {
                    NSGraphicsContext.setCurrentContext(prevNsCtx)
                    CGContextRelease(cgCtx)
                }
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }

        val out = ImageBuffer(targetW, targetH)
        val dst = out.bytes
        var i = 0
        val limit = targetW * targetH * 4
        while (i < limit) {
            val r = premul[i].toInt() and 0xFF
            val g = premul[i + 1].toInt() and 0xFF
            val b = premul[i + 2].toInt() and 0xFF
            val a = premul[i + 3].toInt() and 0xFF
            if (a >= 254 || a == 0) {
                dst[i] = r.toByte()
                dst[i + 1] = g.toByte()
                dst[i + 2] = b.toByte()
                dst[i + 3] = (if (a == 0) 0 else 255).toByte()
            } else {
                dst[i] = ((r * 255) / a).coerceAtMost(255).toByte()
                dst[i + 1] = ((g * 255) / a).coerceAtMost(255).toByte()
                dst[i + 2] = ((b * 255) / a).coerceAtMost(255).toByte()
                dst[i + 3] = a.toByte()
            }
            i += 4
        }
        return out
    }

    fun simulateMouseMove(x: Float, y: Float, flushDisplay: Boolean = true) {
        val session = activeSession() ?: return
        handlePointerMovedAt(session, x, y, 0UL)
        GenericUiThreading.flushAllQueues()
        if (flushDisplay) {
            session.canvasView.displayIfNeeded()
        }
    }

    fun simulateMouseClick(x: Float, y: Float, button: Int = 0, clickCount: Int = 1) {
        val session = activeSession() ?: return
        handlePointerMovedAt(session, x, y, 0UL)
        handlePointerDownAt(session, x, y, button = button, clickCount = clickCount)
        GenericUiThreading.flushAllQueues()
        handlePointerUpAt(session, x, y, button = button, clickCount = clickCount)
        GenericUiThreading.flushAllQueues()
        session.canvasView.displayIfNeeded()
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
        handlePointerMovedAt(session, fromX, fromY, 0UL)
        handlePointerDownAt(session, fromX, fromY, button = button, clickCount = 1)
        GenericUiThreading.flushAllQueues()
        session.canvasView.displayIfNeeded()
        val count = steps.coerceAtLeast(1)
        for (i in 1..count) {
            val t = i.toFloat() / count.toFloat()
            val cx = fromX + (toX - fromX) * t
            val cy = fromY + (toY - fromY) * t
            handlePointerDraggedAt(session, cx, cy, button = button, clickCount = 1)
            GenericUiThreading.flushAllQueues()
            session.canvasView.displayIfNeeded()
        }
        handlePointerUpAt(session, toX, toY, button = button, clickCount = 1)
        GenericUiThreading.flushAllQueues()
        session.canvasView.displayIfNeeded()
    }

    fun simulateScrollWheel(x: Float, y: Float, deltaPx: Float, flushDisplay: Boolean = true) {
        val session = activeSession() ?: return
        handleScrollWheelAt(session, x, y, deltaPx)
        GenericUiThreading.flushAllQueues()
        if (flushDisplay) {
            session.canvasView.displayIfNeeded()
        }
    }

    fun flushPendingDisplay() {
        val session = activeSession() ?: return
        session.canvasView.displayIfNeeded()
    }

    fun resetFrameMetrics() {
        paintSampleWriteIdx = 0
        paintSampleCount = 0
        totalPaintsCounter = 0L
        stageOnlyPaintsCounter = 0L
        fullWindowPaintsCounter = 0L
    }

    fun getFrameMetricsSnapshot(): MacosFrameMetricsSnapshot {
        val n = paintSampleCount
        if (n <= 0) {
            return MacosFrameMetricsSnapshot(
                totalPaints = totalPaintsCounter,
                stageOnlyPaints = stageOnlyPaintsCounter,
                fullWindowPaints = fullWindowPaintsCounter,
                sampleCount = 0,
                p50PaintUs = 0L,
                p95PaintUs = 0L,
                maxPaintUs = 0L,
                avgPaintUs = 0L
            )
        }
        val copy = LongArray(n) { idx -> paintDurationSamplesUs[idx] }
        copy.sort()
        var sum = 0L
        for (v in copy) sum += v
        val p50Idx = ((n - 1) * 50) / 100
        val p95Idx = ((n - 1) * 95) / 100
        return MacosFrameMetricsSnapshot(
            totalPaints = totalPaintsCounter,
            stageOnlyPaints = stageOnlyPaintsCounter,
            fullWindowPaints = fullWindowPaintsCounter,
            sampleCount = n,
            p50PaintUs = copy[p50Idx],
            p95PaintUs = copy[p95Idx],
            maxPaintUs = copy[n - 1],
            avgPaintUs = sum / n.toLong()
        )
    }

    private fun monotonicTimeMs(): Double =
        NSProcessInfo.processInfo.systemUptime * 1000.0

    private fun monotonicTimeUs(): Long =
        (NSProcessInfo.processInfo.systemUptime * 1_000_000.0).toLong()

    private fun Boolean?.orFalse(): Boolean = this == true
}
