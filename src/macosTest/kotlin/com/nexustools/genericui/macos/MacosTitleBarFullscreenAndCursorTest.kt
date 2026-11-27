@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.apple.AppleDataBridge
import com.nexustools.genericui.apple.AppleSvgCoreGraphicsRasterizer
import com.nexustools.genericui.apple.style.AppleBackdropFilterController
import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.action.styled.StyledButton
import com.nexustools.genericui.component.form.NumberField
import com.nexustools.genericui.component.form.Slider
import com.nexustools.genericui.component.form.styled.StyledSlider
import com.nexustools.genericui.component.form.Switch
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.HStack
import com.nexustools.genericui.component.layout.LayerConstraint
import com.nexustools.genericui.component.layout.LayeredContainer
import com.nexustools.genericui.component.layout.StackConstraint
import com.nexustools.genericui.component.layout.styled.StyledContainer
import com.nexustools.genericui.component.layout.VStack
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.surface.styled.StyledComponent
import com.nexustools.genericui.component.typography.Label
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.desktop.system.DesktopSystemTraySilhouetteTransformer
import com.nexustools.genericui.enums.bootstrap.AlignSelf
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.enums.window.DisplayCutoutMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.event.PointerDragManager
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.macos.impl.MacosInteractiveLabelField
import com.nexustools.genericui.macos.impl.MacosSwitchLabeledView
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssEdgeLengths
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.css.CssStyleSheet
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.invoke
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.AppKit.NSAppearanceNameAqua
import platform.AppKit.NSAppearanceNameDarkAqua
import platform.AppKit.NSBitmapImageRep
import platform.AppKit.NSCursor
import platform.AppKit.NSEvent
import platform.AppKit.NSEventTypeLeftMouseDown
import platform.AppKit.NSEventTypeLeftMouseDragged
import platform.AppKit.NSEventTypeMouseExited
import platform.AppKit.NSEventTypeMouseMoved
import platform.AppKit.NSFloatingWindowLevel
import platform.AppKit.NSImageRep
import platform.AppKit.NSNormalWindowLevel
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSPanel
import platform.AppKit.NSPopUpMenuWindowLevel
import platform.AppKit.NSScreen
import platform.AppKit.NSWindow
import platform.AppKit.NSSlider
import platform.AppKit.NSSwitch
import platform.AppKit.NSTrackingActiveAlways
import platform.AppKit.NSTrackingActiveInKeyWindow
import platform.AppKit.NSTrackingArea
import platform.AppKit.NSView
import platform.AppKit.NSVisualEffectView
import platform.AppKit.NSWindowCloseButton
import platform.AppKit.NSWindowCollectionBehaviorFullScreenAuxiliary
import platform.AppKit.NSWindowCollectionBehaviorFullScreenNone
import platform.AppKit.NSWindowCollectionBehaviorFullScreenPrimary
import platform.AppKit.NSWindowMiniaturizeButton
import platform.AppKit.NSWindowStyleMaskFullScreen
import platform.AppKit.NSWindowStyleMaskFullSizeContentView
import platform.AppKit.NSWindowStyleMaskTitled
import platform.AppKit.NSWindowZoomButton
import platform.AppKit.fittingSize
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGRectNull
import platform.Foundation.NSFileManager
import platform.Foundation.NSMakePoint
import platform.Foundation.NSNotification
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSThread
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDefaults
import platform.Foundation.writeToFile
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync
import platform.objc.class_getMethodImplementation
import platform.objc.object_getClass
import platform.objc.sel_registerName
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class MacosTitleBarFullscreenAndCursorTest {

    private inline fun runOnMainThreadSync(crossinline block: () -> Unit) {
        if (NSThread.isMainThread) {
            block()
            return
        }
        var failure: Throwable? = null
        dispatch_sync(dispatch_get_main_queue()) {
            try {
                block()
            } catch (t: Throwable) {
                failure = t
            }
        }
        failure?.let { throw it }
    }

    private inline fun withInteractiveMacosHost(crossinline block: (MacosNativeFactory) -> Unit) {
        val factory = MacosNativeFactory()
        val prevFactory = GenericUI.activeFactory
        val prevApp = Application.current
        val prevInteractive = MacosApplicationNativeImpl.interactiveMacosGuiEnabled
        val prevVisual = MacosNativeWindowHost.visualTestMode
        Application.current = null
        MacosApplicationNativeImpl.interactiveMacosGuiEnabled = true
        MacosNativeWindowHost.visualTestMode = true
        GenericUI.initialize(factory)
        try {
            runOnMainThreadSync {
                block(factory)
            }
        } finally {
            runOnMainThreadSync {
                MacosNativeWindowHost.closeAllVisualWindows()
                GenericUiThreading.flushAllQueues()
            }
            MacosNativeWindowHost.visualTestMode = prevVisual
            MacosApplicationNativeImpl.interactiveMacosGuiEnabled = prevInteractive
            Application.current = prevApp
            GenericUI.activeFactory = prevFactory
        }
    }

    private fun pumpUntil(timeoutMs: Long = 2500L, predicate: () -> Boolean): Boolean {
        val mark = TimeSource.Monotonic.markNow()
        while (!predicate() && mark.elapsedNow().inWholeMilliseconds < timeoutMs) {
            MacosNativeWindowHost.pumpVisualWindowEvents(24L)
        }
        return predicate()
    }

    @Test
    fun undecoratedAlwaysOnTopAspectRatioToggleFullScreenEntersAndExitsWithoutCrash() = withInteractiveMacosHost { factory ->
        val window = Window(title = "Fullscreen Safety", initialSize = Size(960, 540)).apply {
            setUndecorated(true)
            setAlwaysOnTop(true)
            setAspectRatio(16, 9)
            setContent(Container())
        }
        window.show(factory)

        val winImpl = window.ensureNativeImpl() as MacosWindowNativeImpl
        val hostWin = assertNotNull(MacosNativeWindowHost.activeNsWindow as? MacosHostWindow)
        assertTrue(hostWin.isUndecoratedHostWindow)
        assertEquals(NSFloatingWindowLevel, hostWin.level)
        assertEquals(UNDECORATED_WINDOWED_STYLE_MASK, hostWin.styleMask)
        val initialRatio = hostWin.contentAspectRatio.useContents {
            if (height > 0.0) width / height else 0.0
        }
        assertTrue(abs(initialRatio - (16.0 / 9.0)) < 0.02, "Expected 16:9 initial contentAspectRatio, got $initialRatio")

        val initialSize = hostWin.frame.useContents { size.width to size.height }

        // Invoke real AppKit toggleFullScreen: on undecorated + alwaysOnTop + 16:9 window.
        hostWin.toggleFullScreen(null)

        assertTrue(hostWin.isTransitioningFullscreen, "toggleFullScreen: must mark transition active immediately")
        assertEquals(NSNormalWindowLevel, hostWin.level, "Floating level must lower to NSNormalWindowLevel before fullscreen")
        val clearedRatio = hostWin.contentAspectRatio.useContents { width to height }
        assertEquals(0.0, clearedRatio.first, 1e-6)
        assertEquals(0.0, clearedRatio.second, 1e-6)
        assertEquals(UNDECORATED_TRANSITION_STYLE_MASK, hostWin.styleMask)
        assertTrue((hostWin.collectionBehavior and NSWindowCollectionBehaviorFullScreenPrimary) != 0UL)
        assertEquals(0UL, hostWin.collectionBehavior and NSWindowCollectionBehaviorFullScreenAuxiliary)
        assertEquals(0UL, hostWin.collectionBehavior and NSWindowCollectionBehaviorFullScreenNone)

        // Re-entrant call during active transition must safely no-op without crashing.
        hostWin.toggleFullScreen(null)
        assertTrue(hostWin.isTransitioningFullscreen)

        assertTrue(
            pumpUntil(timeoutMs = 2500L) { !hostWin.isTransitioningFullscreen },
            "Fullscreen enter or fail delegate must settle within timeout"
        )

        if ((hostWin.styleMask and NSWindowStyleMaskFullScreen) != 0UL) {
            assertTrue(winImpl.isFullscreen(), "WindowNativeImpl must reflect native fullscreen entry")
            val rootView = assertNotNull(hostWin.contentView as? MacosWindowRootView)
            assertFalse(rootView.isUndecoratedChrome, "Undecorated resize chrome must be disabled in fullscreen")
            assertTrue(rootView.isWindowInFullscreen())
            val closeBtn = hostWin.standardWindowButton(platform.AppKit.NSWindowCloseButton)
            val minBtn = hostWin.standardWindowButton(platform.AppKit.NSWindowMiniaturizeButton)
            val zoomBtn = hostWin.standardWindowButton(platform.AppKit.NSWindowZoomButton)
            if (closeBtn != null && minBtn != null && zoomBtn != null) {
                assertFalse(closeBtn.isHidden())
                assertFalse(minBtn.isHidden(), "Minimize button must remain visible in fullscreen")
                assertFalse(zoomBtn.isHidden())
                assertTrue(closeBtn.isEnabled())
                assertFalse(minBtn.isEnabled(), "Minimize button must be disabled in fullscreen")
                assertTrue(zoomBtn.isEnabled())
            }
            hostWin.toggleFullScreen(null)
            assertTrue(
                pumpUntil(timeoutMs = 2500L) {
                    !hostWin.isTransitioningFullscreen && (hostWin.styleMask and NSWindowStyleMaskFullScreen) == 0UL
                },
                "Fullscreen exit must complete cleanly within timeout"
            )
            assertFalse(winImpl.isFullscreen(), "WindowNativeImpl must clear fullscreen after exit")
        }

        // After exit (or clean transition rejection), windowed chrome, level, aspect ratio, and frame are restored.
        assertEquals(UNDECORATED_WINDOWED_STYLE_MASK, hostWin.styleMask)
        assertEquals(NSFloatingWindowLevel, hostWin.level)
        val restoredRatio = hostWin.contentAspectRatio.useContents {
            if (height > 0.0) width / height else 0.0
        }
        assertTrue(abs(restoredRatio - (16.0 / 9.0)) < 0.02, "Expected 16:9 ratio restored, got $restoredRatio")
        val restoredSize = hostWin.frame.useContents { size.width to size.height }
        assertTrue(
            abs(restoredSize.first - initialSize.first) <= 2.0 && abs(restoredSize.second - initialSize.second) <= 2.0,
            "Expected windowed frame size $initialSize restored, got $restoredSize"
        )

        // Closing the window must mark isClosingWindow and guard any subsequent toggleFullScreen: call.
        window.close()
        assertTrue(hostWin.isClosingWindow)
        hostWin.toggleFullScreen(null)
        assertFalse(hostWin.isTransitioningFullscreen)
    }

    @Test
    fun undecoratedMaximizeRestoreTitleBarDoubleClickAndFocusNotificationsWork() = withInteractiveMacosHost { factory ->
        val defaults = NSUserDefaults.standardUserDefaults
        val hadPrevAction = defaults.objectForKey("AppleActionOnDoubleClick")
        try {
            val titleBar = StyledContainer().apply {
                setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
                setAttribute("data-titlebar-active", "true")
                style.setWidth(CssLength.percent(100f))
                style.setHeight(CssLength.px(32f))
            }
            val centerSlot = HStack().apply {
                style.setWidth(CssLength.percent(100f))
                style.setHeight(CssLength.px(32f))
                addChild(Text("Double Click Bar"))
            }
            titleBar.addChild(centerSlot)
            val root = Container().apply { addChild(titleBar) }

            val focusEvents = mutableListOf<Boolean>()
            val window = Window(title = "Undecorated Max", initialSize = Size(800, 450)).apply {
                setUndecorated(true)
                setAspectRatio(16, 9)
                setOnWindowFocusChanged { focused -> focusEvents.add(focused) }
                setContent(root)
            }
            window.show(factory)
            MacosNativeWindowHost.pumpVisualWindowEvents(48L)

            val hostWin = assertNotNull(MacosNativeWindowHost.activeNsWindow as? MacosHostWindow)
            val winImpl = window.ensureNativeImpl() as MacosWindowNativeImpl
            val delegate = assertNotNull(hostWin.delegate as? MacosWindowDelegate)

            val initFrameSize = hostWin.frame.useContents { size.width to size.height }
            assertEquals(800.0, initFrameSize.first, 2.0)
            assertEquals(450.0, initFrameSize.second, 2.0)

            // Real maximize on undecorated window fits screen.visibleFrame and suspends 16:9 clamp.
            hostWin.toggleMaximizeOrRestore()
            assertTrue(
                pumpUntil(timeoutMs = 1500L) { !hostWin.isAnimatingMaximize && window.getWindowState() == WindowState.MAXIMIZED },
                "Maximize animation must complete cleanly"
            )
            assertTrue(hostWin.isZoomed())
            assertTrue(hostWin.isUndecoratedMaximized)
            assertEquals(WindowState.MAXIMIZED, window.getWindowState())
            val aspectWhileMax = hostWin.contentAspectRatio.useContents { width to height }
            assertEquals(0.0, aspectWhileMax.first, 1e-6)
            assertEquals(0.0, aspectWhileMax.second, 1e-6)

            val targetScreen = hostWin.screen ?: NSScreen.mainScreen ?: (NSScreen.screens.firstOrNull() as? NSScreen)
            if (targetScreen != null) {
                val vis = targetScreen.visibleFrame.useContents { size.width to size.height }
                val maxSize = hostWin.frame.useContents { size.width to size.height }
                assertEquals(vis.first, maxSize.first, 2.0)
                assertEquals(vis.second, maxSize.second, 2.0)
            }

            // Real restore returns to preZoomFrame and restores 16:9 aspect ratio.
            hostWin.toggleMaximizeOrRestore()
            assertTrue(
                pumpUntil(timeoutMs = 1500L) { !hostWin.isAnimatingMaximize && window.getWindowState() == WindowState.NORMAL },
                "Restore animation must complete cleanly"
            )
            assertFalse(hostWin.isZoomed())
            assertFalse(hostWin.isUndecoratedMaximized)
            assertEquals(WindowState.NORMAL, window.getWindowState())
            val restoredSize = hostWin.frame.useContents { size.width to size.height }
            assertEquals(800.0, restoredSize.first, 2.0)
            assertEquals(450.0, restoredSize.second, 2.0)
            val restoredAspect = hostWin.contentAspectRatio.useContents {
                if (height > 0.0) width / height else 0.0
            }
            assertTrue(abs(restoredAspect - (16.0 / 9.0)) < 0.02)

            // Simulate double-click on centerSlot inside titlebar with AppleActionOnDoubleClick = "Maximize".
            defaults.setObject("Maximize", forKey = "AppleActionOnDoubleClick")
            val centerBounds = centerSlot.getBounds()
            val clickX = (centerBounds.x + centerBounds.width * 0.5f)
            val clickY = (centerBounds.y + centerBounds.height * 0.5f)
            MacosNativeWindowHost.simulateMouseClick(clickX, clickY, button = 0, clickCount = 2)
            assertTrue(
                pumpUntil(timeoutMs = 1500L) { !hostWin.isAnimatingMaximize && window.getWindowState() == WindowState.MAXIMIZED },
                "Single double-click must maximize once without double-toggling"
            )
            assertEquals(WindowState.MAXIMIZED, window.getWindowState(), "Single double-click must maximize once without double-toggling")

            MacosNativeWindowHost.simulateMouseClick(clickX, clickY, button = 0, clickCount = 2)
            assertTrue(
                pumpUntil(timeoutMs = 1500L) { !hostWin.isAnimatingMaximize && window.getWindowState() == WindowState.NORMAL },
                "Second double-click must restore cleanly"
            )
            assertEquals(WindowState.NORMAL, window.getWindowState(), "Second double-click must restore cleanly")

            // AppleActionOnDoubleClick = "None" must no-op.
            defaults.setObject("None", forKey = "AppleActionOnDoubleClick")
            MacosNativeWindowHost.simulateMouseClick(clickX, clickY, button = 0, clickCount = 2)
            assertEquals(WindowState.NORMAL, window.getWindowState())

            // AppleActionOnDoubleClick = "Minimize" must miniaturize.
            defaults.setObject("Minimize", forKey = "AppleActionOnDoubleClick")
            hostWin.handleTitleBarDoubleClick()
            assertTrue(
                pumpUntil(timeoutMs = 1500L) { hostWin.miniaturized },
                "AppleActionOnDoubleClick=Minimize must miniaturize window"
            )
            hostWin.deminiaturize(null)
            pumpUntil(timeoutMs = 1500L) { !hostWin.miniaturized }
            MacosNativeWindowHost.pumpVisualWindowEvents(32L)

            // Focus notifications from windowDidBecomeKey/Main & windowDidResignKey/Main deduplicate cleanly.
            focusEvents.clear()
            val notif = NSNotification(name = "TestFocus", `object` = hostWin, userInfo = null)
            delegate.windowDidResignKey(notif)
            delegate.windowDidResignMain(notif)
            delegate.windowDidBecomeMain(notif)
            delegate.windowDidBecomeKey(notif)
            assertEquals(listOf(false, true), focusEvents, "Key+Main notifications must forward once per focus transition")
            assertEquals(WindowState.NORMAL, winImpl.getWindowState())

            window.close()
        } finally {
            if (hadPrevAction != null) {
                defaults.setObject(hadPrevAction, forKey = "AppleActionOnDoubleClick")
            } else {
                defaults.removeObjectForKey("AppleActionOnDoubleClick")
            }
        }
    }

    @Test
    fun isWithinOverlayTitleBarNonControlAreaDistinguishesBarSlotsLabelsAndControls() = withInteractiveMacosHost { factory ->
        val closeBtn = StyledButton("×").apply {
            setAttribute("data-window-control", "close")
            style.setWidth(CssLength.px(12f))
            style.setHeight(CssLength.px(12f))
        }
        val minBtn = StyledButton("–").apply {
            setAttribute("data-window-control", "minimize")
            style.setWidth(CssLength.px(12f))
            style.setHeight(CssLength.px(12f))
        }
        val maxBtn = StyledButton("+").apply {
            setAttribute("data-window-control", "maximize")
            style.setWidth(CssLength.px(12f))
            style.setHeight(CssLength.px(12f))
        }
        val controlsContainer = HStack(gapPx = 8).apply {
            addChild(closeBtn)
            addChild(minBtn)
            addChild(maxBtn)
        }
        val accessoryBtn = Button("Action")
        val interactiveAccessory = StyledContainer().apply {
            style.setWidth(CssLength.px(20f))
            style.setHeight(CssLength.px(20f))
            onClick = {}
        }
        val leadingSlot = HStack(gapPx = 8).apply {
            addChild(controlsContainer)
            addChild(interactiveAccessory)
        }
        val titleLabel = Text("Sample Video Title")
        val centerSlot = HStack().apply {
            addChild(titleLabel)
        }
        val trailingSlot = HStack().apply {
            addChild(accessoryBtn)
        }
        val titleBar = StyledContainer().apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            setAttribute("data-titlebar-active", "true")
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.px(32f))
            addChild(leadingSlot)
            addChild(centerSlot)
            addChild(trailingSlot)
        }
        val window = Window(title = "Titlebar HitTest", initialSize = Size(640, 240)).apply {
            setUndecorated(true)
            setContent(Container().apply { addChild(titleBar) })
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val barView = assertNotNull((titleBar.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val centerView = assertNotNull((centerSlot.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val leadingView = assertNotNull((leadingSlot.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val trailingView = assertNotNull((trailingSlot.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val labelView = assertNotNull((titleLabel.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosInteractiveLabelField)
        val closeView = assertNotNull((closeBtn.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val minView = assertNotNull((minBtn.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val maxView = assertNotNull((maxBtn.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val nativeBtnView = assertNotNull((accessoryBtn.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val interactiveAccView = assertNotNull((interactiveAccessory.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)

        assertTrue(isWithinOverlayTitleBarNonControlArea(barView))
        assertTrue(isWithinOverlayTitleBarNonControlArea(centerView))
        assertTrue(isWithinOverlayTitleBarNonControlArea(leadingView))
        assertTrue(isWithinOverlayTitleBarNonControlArea(trailingView))
        assertTrue(isWithinOverlayTitleBarNonControlArea(labelView))

        assertTrue(barView.mouseDownCanMoveWindow())
        assertTrue(centerView.mouseDownCanMoveWindow())
        assertTrue(labelView.mouseDownCanMoveWindow())

        assertFalse(isWithinOverlayTitleBarNonControlArea(closeView), "Close traffic-light must not trigger window drag")
        assertFalse(isWithinOverlayTitleBarNonControlArea(minView), "Minimize traffic-light must not trigger window drag")
        assertFalse(isWithinOverlayTitleBarNonControlArea(maxView), "Maximize traffic-light must not trigger window drag")
        assertFalse(isWithinOverlayTitleBarNonControlArea(nativeBtnView), "NSButton inside titlebar must not trigger window drag")
        assertFalse(isWithinOverlayTitleBarNonControlArea(interactiveAccView), "Interactive onClick accessory must not trigger window drag")

        // Deactivating titlebar disables dragging across bar, slots, and titleLabel.
        titleBar.setAttribute("data-titlebar-active", "false")
        assertFalse(isWithinOverlayTitleBarNonControlArea(barView))
        assertFalse(isWithinOverlayTitleBarNonControlArea(centerView))
        assertFalse(isWithinOverlayTitleBarNonControlArea(labelView))
        assertFalse(centerView.mouseDownCanMoveWindow())

        window.close()
    }

    @Test
    fun compactStyledButtonHonorsExplicitDimensionsAndDrawsTrafficLightGlyphsWithoutClipping() = withInteractiveMacosHost { factory ->
        val closeBtn = StyledButton("×").apply {
            style.setWidth(CssLength.px(12f))
            style.setHeight(CssLength.px(12f))
            style.setPadding(CssEdgeLengths.px(0f))
            style.setBorderWidthPx(1f)
            style.setBorderRadiusPx(6f)
            style.setFontSize(CssLength.px(9f))
            backgroundColor = RgbaColor(255, 95, 87, 255)
            borderColor = RgbaColor(224, 68, 62, 255)
            textColor = RgbaColor(77, 0, 0, 230)
        }
        val window = Window(title = "Compact Button", initialSize = Size(200, 120)).apply {
            setContent(Container().apply { addChild(closeBtn) })
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val btnImpl = closeBtn.ensureNativeImpl() as MacosStyledButtonNativeImpl
        assertEquals(Size(12, 12), btnImpl.measureIntrinsicSize(null, null), "Compact 12x12 button must not inflate intrinsic size")
        val bounds = closeBtn.getBounds()
        assertEquals(12, bounds.width)
        assertEquals(12, bounds.height)

        val screenshot = MacosNativeWindowHost.captureWindowScreenshot(
            window.ensureNativeImpl() as MacosWindowNativeImpl,
            200,
            120
        )
        assertNotNull(screenshot)
        // Verify dark glyph pixels were drawn centered inside the 12x12 coral circle (not clipped to zero).
        var darkGlyphPixels = 0
        for (py in 3..8) {
            for (px in 3..8) {
                val off = (py * screenshot.width + px) * 4
                val r = screenshot.bytes[off].toInt() and 0xFF
                val g = screenshot.bytes[off + 1].toInt() and 0xFF
                if (r < 190 && g < 60) {
                    darkGlyphPixels++
                }
            }
        }
        assertTrue(darkGlyphPixels > 0, "12x12 compact button must visibly draw centered glyph pixels")

        window.close()
    }

    @Test
    fun liveVisualEffectViewUsesLightweightMaterialAndRespectsThemeAppearance() = withInteractiveMacosHost { factory ->
        val blurFilter = CssFilter(blur = CssLength.px(18f))
        assertFalse(AppleBackdropFilterController.requiresFullFilterPipeline(blurFilter))

        val regularBlurred = StyledContainer().apply {
            style.setWidth(CssLength.px(240f))
            style.setHeight(CssLength.px(44f))
            backdropFilter = blurFilter
        }
        val titleBarBlurred = StyledContainer().apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            style.setWidth(CssLength.px(240f))
            style.setHeight(CssLength.px(32f))
            backgroundColor = RgbaColor(20, 20, 24, 94)
            backdropFilter = blurFilter
        }
        val root = Container().apply {
            addChild(titleBarBlurred)
            addChild(regularBlurred)
        }
        val window = Window(title = "Vibrancy Blur", initialSize = Size(320, 180)).apply {
            setContent(root)
        }
        window.show(factory)
        MacosNativeWindowHost.syncThemeAppearance(ThemeMode.DARK)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val regView = assertNotNull((regularBlurred.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosStyledContainerView)
        val barView = assertNotNull((titleBarBlurred.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosStyledContainerView)

        assertTrue(regView.subviews.none { it is NSVisualEffectView }, "Pure blur(18px) must not mount NSVisualEffectView")
        assertTrue(barView.subviews.none { it is NSVisualEffectView }, "Titlebar blur(18px) must not mount NSVisualEffectView")
        assertNotNull(regView.surfaceSupport.backdropUnderlayView, "Pure blur(18px) must use CoreImage backdropUnderlayView")
        assertNotNull(barView.surfaceSupport.backdropUnderlayView, "Titlebar blur(18px) must use CoreImage backdropUnderlayView")
        assertNotNull(regView.surfaceSupport.tintOverlayView)
        assertNotNull(barView.surfaceSupport.tintOverlayView)

        MacosNativeWindowHost.syncThemeAppearance(ThemeMode.LIGHT)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)
        assertNotNull(regView.surfaceSupport.backdropUnderlayView)
        assertNotNull(barView.surfaceSupport.backdropUnderlayView)

        window.close()
    }

    @Test
    fun hoveringPointerButtonInUndecoratedWindowHasZeroCursorFlicker() = withInteractiveMacosHost { factory ->
        val ptrBtn = StyledButton("Play").apply {
            style.setWidth(CssLength.px(100f))
            style.setHeight(CssLength.px(36f))
            style.setCursor(CssCursor.POINTER)
            onClick = {}
        }
        val innerContainer = StyledContainer().apply {
            style.setPadding(CssEdgeLengths.px(40f))
            addChild(ptrBtn)
        }
        val outerContainer = Container().apply {
            style.setPadding(CssEdgeLengths.px(20f))
            addChild(innerContainer)
        }
        val window = Window(title = "Zero Cursor Flicker", initialSize = Size(480, 320)).apply {
            setUndecorated(true)
            setContent(outerContainer)
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val winImpl = window.ensureNativeImpl() as MacosWindowNativeImpl
        val rootView = assertNotNull((winImpl.nativeWidget as MacosViewHandle).nsView as? MacosWindowRootView)
        assertTrue(rootView.isUndecoratedChrome)

        val outerView = assertNotNull((outerContainer.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosContainerView)
        val innerView = assertNotNull((innerContainer.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosStyledContainerView)
        val btnView = assertNotNull((ptrBtn.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosStyledButtonControl)

        val btnCenter = btnView.bounds.useContents {
            rootView.convertPoint(
                NSMakePoint(origin.x + size.width * 0.5, origin.y + size.height * 0.5),
                fromView = btnView
            )
        }
        val (bx, by) = btnCenter.useContents { x to y }
        assertEquals(btnView, rootView.hitTestClientPoint(bx, by))

        // 1. Background area resolves arrowCursor.
        val bgCursor = rootView.updateEffectiveCursorForClientPoint(200.0, 220.0)
        assertEquals(NSCursor.arrowCursor, bgCursor)
        assertEquals(NSCursor.arrowCursor, rootView.lastAppliedCursor)

        // 2. Moving onto pointer button resolves pointingHandCursor.
        MacosNativeWindowHost.simulateMouseMove(bx.toFloat(), by.toFloat())
        assertEquals(NSCursor.pointingHandCursor, rootView.lastAppliedCursor)

        // 3. Firing mouseMoved: on rootView and cursorUpdate: on ALL ancestor containers must NOT clobber pointingHandCursor!
        val winLoc = rootView.convertClientPointToWindow(bx, by)
        val moveEv = assertNotNull(
            NSEvent.mouseEventWithType(
                type = NSEventTypeMouseMoved,
                location = winLoc,
                modifierFlags = 0uL,
                timestamp = 0.0,
                windowNumber = rootView.window?.windowNumber ?: 0L,
                context = null,
                eventNumber = 0L,
                clickCount = 0L,
                pressure = 0f
            )
        )
        repeat(5) {
            rootView.mouseMoved(moveEv)
            assertEquals(NSCursor.pointingHandCursor, rootView.lastAppliedCursor)
            outerView.cursorUpdate(moveEv)
            assertEquals(NSCursor.pointingHandCursor, rootView.lastAppliedCursor)
            innerView.cursorUpdate(moveEv)
            assertEquals(NSCursor.pointingHandCursor, rootView.lastAppliedCursor)
            outerView.mouseEntered(moveEv)
            assertEquals(NSCursor.pointingHandCursor, rootView.lastAppliedCursor)
            innerView.mouseExited(moveEv)
            assertEquals(NSCursor.pointingHandCursor, rootView.lastAppliedCursor)
        }

        // 4. Left resize border resolves resize cursor, and moving back to plain background restores arrowCursor.
        val leftBorderCursor = rootView.updateEffectiveCursorForClientPoint(2.0, 160.0)
        assertNotEquals(NSCursor.arrowCursor, leftBorderCursor)
        assertEquals(MacosNativeWindowHost.resolveCursorForResizeEdge(MacosNativeWindowHost.UndecoratedResizeEdge.W).second, leftBorderCursor)

        MacosNativeWindowHost.simulateMouseMove(220f, 240f)
        assertEquals(NSCursor.arrowCursor, rootView.lastAppliedCursor)

        window.close()
    }

    @Test
    fun resolveCursorNoneReturnsCachedTransparentHiddenCursor() = runOnMainThreadSync {
        val first = MacosCursorTranslator.resolveCursor("none")
        val second = MacosCursorTranslator.resolveCursor("  NONE ")
        assertEquals("none", first.first)
        assertEquals("none", second.first)
        assertTrue(
            !MacosCursorTranslator.isSameCursor(first.second, NSCursor.arrowCursor),
            "cursor: none must not resolve to NSCursor.arrowCursor"
        )
        assertTrue(MacosCursorTranslator.isHiddenCursor(first.second))
        assertTrue(
            MacosCursorTranslator.isSameCursor(first.second, second.second),
            "Repeated cursor: none calls must return the same cached instance"
        )
        assertFalse(MacosCursorTranslator.isHiddenCursor(NSCursor.arrowCursor))
        assertFalse(MacosCursorTranslator.isHiddenCursor(null))
    }

    @Test
    fun stationaryPointerUpdatesToHiddenAndRestoresImmediatelyWithoutMouseMove() = withInteractiveMacosHost { factory ->
        val css = CssStyleSheet.parse(
            """
            .stage-target {
                width: 100%;
                height: 100%;
            }
            .stage-target.cursor-hidden {
                cursor: none;
            }
            .hud-overlay {
                width: 100%;
                height: 100%;
                cursor: default;
            }
            .hud-overlay.overlay-hidden {
                pointer-events: none;
            }
            .pointer-child {
                width: 120px;
                height: 40px;
                cursor: pointer;
            }
            """.trimIndent()
        )
        val stageTarget = StyledContainer().apply {
            addClass("stage-target")
        }
        val overlay = StyledContainer().apply {
            addClass("hud-overlay")
        }
        val pointerBtn = StyledButton("Play").apply {
            addClass("pointer-child")
            onClick = {}
        }
        val rootLayer = LayeredContainer().apply {
            addFillLayer(stageTarget, zIndex = 0)
            addFillLayer(overlay, zIndex = 10)
            addLayer(pointerBtn, LayerConstraint.float(offsetXPx = 20, offsetYPx = 20, zIndex = 5))
        }
        val window = Window(title = "Stationary Cursor Hide", initialSize = Size(480, 320)).apply {
            setContent(rootLayer)
        }
        window.show(factory)
        rootLayer.applyStyleSheet(css)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val winImpl = window.ensureNativeImpl() as MacosWindowNativeImpl
        val hostWin = assertNotNull((winImpl.nativeWidget as MacosViewHandle).nsWindow as? MacosHostWindow)
        hostWin.forceActiveKeyAppearanceForCapture = true
        val rootView = assertNotNull((winImpl.nativeWidget as MacosViewHandle).nsView as? MacosWindowRootView)

        // 1. Move mouse once to the center of the window (over visible overlay -> arrowCursor).
        MacosNativeWindowHost.simulateMouseMove(240f, 160f)
        assertEquals(NSCursor.arrowCursor, rootView.lastAppliedCursor)
        assertEquals(NSCursor.arrowCursor, NSCursor.currentCursor)

        // 2. Hide overlay (pointer-events: none) and apply cursor: none class to stage WITHOUT moving the mouse.
        overlay.addClass("overlay-hidden")
        stageTarget.addClass("cursor-hidden")
        GenericUiThreading.flushAllQueues()
        MacosNativeWindowHost.pumpVisualWindowEvents(32L)

        assertTrue(
            MacosCursorTranslator.isHiddenCursor(rootView.lastAppliedCursor),
            "Stationary pointer over stage must switch lastAppliedCursor to hiddenBlankCursor"
        )
        assertTrue(
            MacosCursorTranslator.isHiddenCursor(NSCursor.currentCursor),
            "Stationary pointer over stage must switch NSCursor.currentCursor to hiddenBlankCursor"
        )

        // 3. Show overlay / remove cursor-hidden class WITHOUT another mouse move -> cursor restores immediately.
        overlay.removeClass("overlay-hidden")
        stageTarget.removeClass("cursor-hidden")
        GenericUiThreading.flushAllQueues()
        MacosNativeWindowHost.pumpVisualWindowEvents(32L)

        assertEquals(NSCursor.arrowCursor, rootView.lastAppliedCursor)
        assertEquals(NSCursor.arrowCursor, NSCursor.currentCursor)

        // 4. Stationary pointer over a pointer child when overlay toggles pointer-events: none vs cursor: none.
        val btnView = assertNotNull((pointerBtn.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val btnCenter = btnView.bounds.useContents {
            rootView.convertPoint(
                NSMakePoint(origin.x + size.width * 0.5, origin.y + size.height * 0.5),
                fromView = btnView
            )
        }
        val (bx, by) = btnCenter.useContents { x to y }
        overlay.addClass("overlay-hidden")
        GenericUiThreading.flushAllQueues()
        MacosNativeWindowHost.pumpVisualWindowEvents(16L)

        MacosNativeWindowHost.simulateMouseMove(bx.toFloat(), by.toFloat())
        assertEquals(NSCursor.pointingHandCursor, rootView.lastAppliedCursor)
        assertEquals(NSCursor.pointingHandCursor, NSCursor.currentCursor)

        // Cover pointerBtn with overlay set to cursor: none WITHOUT moving the mouse.
        overlay.removeClass("overlay-hidden")
        overlay.style.setCursor(CssCursor.NONE)
        GenericUiThreading.flushAllQueues()
        MacosNativeWindowHost.pumpVisualWindowEvents(32L)

        assertTrue(MacosCursorTranslator.isHiddenCursor(rootView.lastAppliedCursor))
        assertTrue(MacosCursorTranslator.isHiddenCursor(NSCursor.currentCursor))

        // Make overlay pass-through again WITHOUT moving the mouse -> pointingHandCursor restores immediately.
        overlay.style.setCursor(null)
        overlay.addClass("overlay-hidden")
        GenericUiThreading.flushAllQueues()
        MacosNativeWindowHost.pumpVisualWindowEvents(32L)

        assertEquals(NSCursor.pointingHandCursor, rootView.lastAppliedCursor)
        assertEquals(NSCursor.pointingHandCursor, NSCursor.currentCursor)

        // 5. Window resigning key restores arrowCursor if cursor was hidden.
        overlay.removeClass("overlay-hidden")
        overlay.style.setCursor(CssCursor.NONE)
        GenericUiThreading.flushAllQueues()
        MacosNativeWindowHost.pumpVisualWindowEvents(32L)
        assertTrue(MacosCursorTranslator.isHiddenCursor(NSCursor.currentCursor))

        hostWin.forceActiveKeyAppearanceForCapture = false
        val delegate = assertNotNull(hostWin.delegate as? MacosWindowDelegate)
        delegate.windowDidResignKey(NSNotification(name = "TestResignKey", `object` = hostWin, userInfo = null))
        assertNull(rootView.lastAppliedCursor)
        assertEquals(NSCursor.arrowCursor, NSCursor.currentCursor)

        window.close()
    }

    @Test
    fun seekBarInitialZOrderSubviewsLayersAndRenderedPixelsMatchProgressColorWithoutHover() = withInteractiveMacosHost { factory ->
        val css = CssStyleSheet.parse(
            """
            .seek-bar {
                position: relative;
                width: 300px;
                height: 24px;
            }
            .styled-slider-track {
                position: absolute;
                left: 0px;
                top: 8px;
                width: 100%;
                height: 8px;
                background-color: rgb(45, 45, 55);
                z-index: 1;
            }
            .seek-bar-preload {
                position: absolute;
                left: 0px;
                top: 8px;
                height: 8px;
                background-color: rgb(110, 110, 130);
                pointer-events: none;
                z-index: 2;
            }
            .styled-slider-progress {
                position: absolute;
                left: 0px;
                top: 8px;
                height: 8px;
                background-color: rgb(235, 30, 30);
                pointer-events: none;
                z-index: 3;
            }
            .styled-slider-knob {
                position: absolute;
                top: 5px;
                width: 14px;
                height: 14px;
                background-color: rgb(255, 255, 255);
                z-index: 4;
            }
            """.trimIndent()
        )
        class TestSeekBarComposite : StyledSlider(min = 0.0, max = 100.0, value = 0.0, step = 1.0) {
            val preloadSeg: StyledComponent = StyledComponent(classPrefix = "seek-bar-preload").apply {
                addClass("seek-bar-preload")
            }
            init {
                addClass("seek-bar")
                addCompositeChild(preloadSeg)
            }
            fun trackPart(): StyledButton = trackButton
            fun progressPart(): StyledComponent = progressSegment
            fun knobPart(): StyledButton = knobButton
        }

        val seekBar = TestSeekBarComposite().apply {
            preloadSeg.style.setWidth(CssLength.percent(80f))
        }
        val overlayContainer = StyledContainer().apply {
            isVisible = false
            style.setWidth(CssLength.px(360f))
            style.setHeight(CssLength.px(80f))
            addChild(seekBar)
        }
        val window = Window(title = "SeekBar Z-Order", initialSize = Size(360, 80)).apply {
            setContent(overlayContainer)
        }
        window.show(factory)
        overlayContainer.applyStyleSheet(css)
        seekBar.value = 50.0
        GenericUiThreading.flushAllQueues()
        MacosNativeWindowHost.pumpVisualWindowEvents(24L)

        // Show WITHOUT hovering.
        overlayContainer.isVisible = true
        GenericUiThreading.flushAllQueues()
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val sliderView = assertNotNull((seekBar.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosStyledContainerView)
        val trackView = assertNotNull((seekBar.trackPart().ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val preloadView = assertNotNull((seekBar.preloadSeg.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val progressView = assertNotNull((seekBar.progressPart().ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val knobView = assertNotNull((seekBar.knobPart().ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)

        val subs = sliderView.subviews.filterIsInstance<NSView>()
        val idxTrack = subs.indexOfFirst { it === trackView }
        val idxPreload = subs.indexOfFirst { it === preloadView }
        val idxProgress = subs.indexOfFirst { it === progressView }
        val idxKnob = subs.indexOfFirst { it === knobView }
        assertTrue(idxTrack >= 0 && idxPreload >= 0 && idxProgress >= 0 && idxKnob >= 0)
        assertTrue(
            idxTrack < idxPreload && idxPreload < idxProgress && idxProgress < idxKnob,
            "AppKit subviews must be ordered track < preload < progress < knob, got $idxTrack, $idxPreload, $idxProgress, $idxKnob"
        )

        val zTrack = trackView.layer?.zPosition ?: -999.0
        val zPreload = preloadView.layer?.zPosition ?: -999.0
        val zProgress = progressView.layer?.zPosition ?: -999.0
        val zKnob = knobView.layer?.zPosition ?: -999.0
        assertTrue(
            zTrack < zPreload && zPreload < zProgress && zProgress < zKnob,
            "CALayer.zPosition must ascend track < preload < progress < knob, got $zTrack, $zPreload, $zProgress, $zKnob"
        )

        val shot = assertNotNull(
            MacosNativeWindowHost.captureWindowScreenshot(
                window.ensureNativeImpl() as MacosWindowNativeImpl,
                360,
                80
            )
        )
        val playedX = 75
        val playedY = 12
        val off = (playedY * shot.width + playedX) * 4
        val r = shot.bytes[off].toInt() and 0xFF
        val g = shot.bytes[off + 1].toInt() and 0xFF
        val b = shot.bytes[off + 2].toInt() and 0xFF
        assertTrue(
            r >= 200 && g <= 55 && b <= 55,
            "Played region pixel at ($playedX, $playedY) must render vivid progress red above track & preload, got rgb($r, $g, $b)"
        )

        window.close()
    }

    @Test
    fun pointerDragManagerLocksCursorAcrossUndecoratedResizeEdgesAndCorners() = withInteractiveMacosHost { factory ->
        val dragTarget = StyledContainer().apply {
            style.setWidth(CssLength.px(200f))
            style.setHeight(CssLength.px(80f))
        }
        val window = Window(title = "Unified Cursor Lock", initialSize = Size(480, 320)).apply {
            setUndecorated(true)
            setContent(Container().apply { addChild(dragTarget) })
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val hostWin = assertNotNull(MacosNativeWindowHost.activeNsWindow as? MacosHostWindow)
        val rootView = assertNotNull(hostWin.contentView as? MacosWindowRootView)
        assertTrue(rootView.isUndecoratedChrome)

        fun sendMoveAndCursorUpdate(clientX: Double, clientY: Double) {
            val ev = assertNotNull(
                NSEvent.mouseEventWithType(
                    type = NSEventTypeMouseMoved,
                    location = rootView.convertClientPointToWindow(clientX, clientY),
                    modifierFlags = 0uL,
                    timestamp = 1.0,
                    windowNumber = hostWin.windowNumber,
                    context = null,
                    eventNumber = 0L,
                    clickCount = 0L,
                    pressure = 0f
                )
            )
            rootView.mouseMoved(ev)
            rootView.cursorUpdate(ev)
        }

        // 1. Window move drag locks cursor to DEFAULT across undecorated 8px edge and corner.
        val moveDrag = PointerDragManager.startWindowMoveDrag(lockedCursor = CssCursor.DEFAULT)
        try {
            assertEquals(NSCursor.arrowCursor, NSCursor.currentCursor)
            sendMoveAndCursorUpdate(2.0, 160.0)
            assertEquals(NSCursor.arrowCursor, NSCursor.currentCursor, "Window move lock must hold arrowCursor over W edge")
            sendMoveAndCursorUpdate(2.0, 2.0)
            assertEquals(NSCursor.arrowCursor, NSCursor.currentCursor, "Window move lock must hold arrowCursor over NW corner")
        } finally {
            moveDrag.dispose()
        }
        sendMoveAndCursorUpdate(2.0, 160.0)
        assertEquals(
            MacosNativeWindowHost.resolveCursorForResizeEdge(MacosNativeWindowHost.UndecoratedResizeEdge.W).second,
            NSCursor.currentCursor,
            "Releasing window move lock must immediately restore W resize cursor on edge"
        )

        // 2. Component drag locks cursor to EW_RESIZE across edge, corner, and interior until pointerUp.
        PointerDragManager.startDrag(
            target = dragTarget,
            lockedCursor = CssCursor.EW_RESIZE,
            onDragMove = {},
            onDragEnd = { _, _ -> }
        )
        try {
            assertEquals(NSCursor.resizeLeftRightCursor, NSCursor.currentCursor)
            sendMoveAndCursorUpdate(2.0, 160.0)
            assertEquals(NSCursor.resizeLeftRightCursor, NSCursor.currentCursor, "Component drag lock must hold EW_RESIZE over W edge")
            sendMoveAndCursorUpdate(2.0, 2.0)
            assertEquals(NSCursor.resizeLeftRightCursor, NSCursor.currentCursor, "Component drag lock must hold EW_RESIZE over NW corner")
            sendMoveAndCursorUpdate(240.0, 160.0)
            assertEquals(NSCursor.resizeLeftRightCursor, NSCursor.currentCursor)
        } finally {
            PointerDragManager.notifyGlobalPointerUp()
        }
        sendMoveAndCursorUpdate(2.0, 160.0)
        assertEquals(
            MacosNativeWindowHost.resolveCursorForResizeEdge(MacosNativeWindowHost.UndecoratedResizeEdge.W).second,
            NSCursor.currentCursor,
            "Ending component drag must immediately unlock and resolve edge resize cursor"
        )

        window.close()
    }

    @Test
    fun nativeOverlayTitleBarUsesTitledFullSizeContentViewSyncsContainerAndZoomsWithoutAspectClamp() = withInteractiveMacosHost { factory ->
        val titleBar = StyledContainer().apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            setAttribute("data-native-titlebar-controls", "true")
            setAttribute("data-titlebar-active", "true")
            setAttribute("data-titlebar-show-close", "true")
            setAttribute("data-titlebar-show-minimize", "true")
            setAttribute("data-titlebar-show-maximize", "true")
            setAttribute("data-titlebar-show-title", "true")
            setAttribute("data-titlebar-title", "Native Overlay Bar")
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.px(32f))
        }
        val overlayRoot = StyledContainer().apply {
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.percent(100f))
            addChild(titleBar)
        }
        val window = Window(title = "Native Overlay Bar", initialSize = Size(800, 450)).apply {
            setUndecorated(true)
            setAspectRatio(16, 9)
            setContent(overlayRoot)
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val winImpl = window.ensureNativeImpl() as MacosWindowNativeImpl
        assertTrue(winImpl.getTitleCapabilities().usesNativeOverlayTitleBarControls)

        val hostWin = assertNotNull(MacosNativeWindowHost.activeNsWindow as? MacosHostWindow)
        val rootView = assertNotNull(hostWin.contentView as? MacosWindowRootView)
        assertTrue(hostWin.isNativeOverlayTitleBarHostWindow)
        assertFalse(hostWin.isUndecoratedHostWindow)
        assertFalse(rootView.isUndecoratedChrome)
        assertTrue((hostWin.styleMask and NSWindowStyleMaskTitled) != 0UL)
        assertTrue((hostWin.styleMask and NSWindowStyleMaskFullSizeContentView) != 0UL)
        assertTrue((hostWin.collectionBehavior and NSWindowCollectionBehaviorFullScreenPrimary) != 0UL)

        val closeBtn = assertNotNull(hostWin.standardWindowButton(NSWindowCloseButton))
        val minBtn = assertNotNull(hostWin.standardWindowButton(NSWindowMiniaturizeButton))
        val zoomBtn = assertNotNull(hostWin.standardWindowButton(NSWindowZoomButton))
        assertFalse(closeBtn.isHidden())
        assertFalse(minBtn.isHidden())
        assertFalse(zoomBtn.isHidden())

        val tbContainer = assertNotNull(hostWin.titlebarContainerView())
        assertFalse(tbContainer.isHidden())
        assertEquals(1.0, tbContainer.alphaValue, 1e-4)

        // Hiding via opacity = 0f hides NSTitlebarContainerView so pointer moves hit contentView.
        overlayRoot.opacity = 0f
        MacosNativeWindowHost.pumpVisualWindowEvents(32L)
        assertTrue(tbContainer.isHidden())
        assertEquals(0.0, tbContainer.alphaValue, 1e-4)

        // Restoring opacity restores NSTitlebarContainerView.
        overlayRoot.opacity = 1f
        MacosNativeWindowHost.pumpVisualWindowEvents(32L)
        assertFalse(tbContainer.isHidden())
        assertEquals(1.0, tbContainer.alphaValue, 1e-4)

        // Hiding via isVisible = false also hides NSTitlebarContainerView while keeping native titlebar styleMask.
        overlayRoot.isVisible = false
        MacosNativeWindowHost.pumpVisualWindowEvents(32L)
        assertTrue(hostWin.isNativeOverlayTitleBarHostWindow)
        assertTrue((hostWin.styleMask and NSWindowStyleMaskTitled) != 0UL)
        assertTrue(tbContainer.isHidden())
        assertEquals(0.0, tbContainer.alphaValue, 1e-4)

        overlayRoot.isVisible = true
        MacosNativeWindowHost.pumpVisualWindowEvents(32L)
        assertFalse(tbContainer.isHidden())
        assertEquals(1.0, tbContainer.alphaValue, 1e-4)

        // Native performZoom clears 16:9 aspect ratio to maximize across visibleFrame and restores on unzoom.
        hostWin.performZoom(null)
        assertTrue(
            pumpUntil(timeoutMs = 1500L) { hostWin.isZoomed() && window.getWindowState() == WindowState.MAXIMIZED },
            "Native performZoom must maximize native overlay titlebar window"
        )
        val zoomedAspect = hostWin.contentAspectRatio.useContents { width to height }
        assertEquals(0.0, zoomedAspect.first, 1e-6)
        assertEquals(0.0, zoomedAspect.second, 1e-6)
        val targetScreen = hostWin.screen ?: NSScreen.mainScreen ?: (NSScreen.screens.firstOrNull() as? NSScreen)
        if (targetScreen != null) {
            val vis = targetScreen.visibleFrame.useContents { size.width to size.height }
            val maxSize = hostWin.frame.useContents { size.width to size.height }
            assertEquals(vis.first, maxSize.first, 4.0)
            assertEquals(vis.second, maxSize.second, 4.0)
        }

        hostWin.performZoom(null)
        assertTrue(
            pumpUntil(timeoutMs = 1500L) { !hostWin.isZoomed() && window.getWindowState() == WindowState.NORMAL },
            "Second performZoom must restore native overlay titlebar window"
        )
        val restoredAspect = hostWin.contentAspectRatio.useContents {
            if (height > 0.0) width / height else 0.0
        }
        assertTrue(abs(restoredAspect - (16.0 / 9.0)) < 0.02, "Expected 16:9 aspect ratio restored, got $restoredAspect")

        window.close()
    }

    @Test
    fun schemaSettingsFormSliderExpandsInHStackAndSwitchAnchorsLeftInVStack() = withInteractiveMacosHost { factory ->
        val slider = Slider(value = 50.0, min = 0.0, max = 100.0, step = 1.0)
        val numField = NumberField(numericValue = 250.0).apply {
            style.setWidth(CssLength.px(110f))
        }
        val unitLabel = Label("ms")
        val row = HStack(gapPx = 8).apply {
            addChild(slider, weight = 1f)
            addChild(numField)
            addChild(unitLabel)
        }
        val toggle = Switch(label = "", checked = true).apply {
            style.setAlignSelf(AlignSelf.START)
        }
        val form = VStack(gapPx = 12).apply {
            style.setWidth(CssLength.percent(100f))
            addChild(row)
            addChild(toggle, StackConstraint(alignSelf = AlignSelf.START, fillCrossAxis = false))
        }
        val window = Window(title = "Schema Settings Form", initialSize = Size(480, 240)).apply {
            setContent(form)
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val sliderBounds = slider.getBounds()
        assertTrue(sliderBounds.width >= 200, "Slider with weight=1f in HStack must expand >= 200px, got ${sliderBounds.width}")
        val nsSlider = assertNotNull((slider.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? NSSlider)
        val nsSliderWidth = nsSlider.frame.useContents { size.width }
        assertTrue(nsSliderWidth >= 200.0, "NSSlider frame width must be >= 200, got $nsSliderWidth")

        val toggleView = assertNotNull((toggle.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
        val nsSwitch = (toggleView as? NSSwitch) ?: (toggleView as? MacosSwitchLabeledView)?.switchControl
        assertNotNull(nsSwitch, "Switch must back onto NSSwitch")
        val naturalFitW = nsSwitch.fittingSize().useContents { width }
        nsSwitch.frame.useContents {
            assertTrue(origin.x <= 4.0, "NSSwitch must anchor left (x <= 4), got ${origin.x}")
            assertTrue(
                size.width <= naturalFitW + 12.0 && size.width < 80.0,
                "NSSwitch must keep natural compact width (<= ${naturalFitW + 12.0} and < 80), got ${size.width}"
            )
        }

        window.close()
    }

    @Test
    fun systemTrayIconSvgSilhouetteProducesAntialiased1xAnd2xRetinaReps() = runOnMainThreadSync {
        val prevInteractive = MacosApplicationNativeImpl.interactiveMacosGuiEnabled
        MacosApplicationNativeImpl.interactiveMacosGuiEnabled = true
        val svgMarkup = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">
              <circle cx="12" cy="12" r="9" fill="#2288ff"/>
            </svg>
        """.trimIndent()
        val tempSvgPath = "${NSTemporaryDirectory().trimEnd('/')}/tray_retina_${NSUUID().UUIDString}.svg"
        val svgBytes = svgMarkup.encodeToByteArray()
        assertTrue(AppleDataBridge.byteArrayToNSData(svgBytes).writeToFile(tempSvgPath, atomically = true))

        val supersampled = assertNotNull(
            AppleSvgCoreGraphicsRasterizer.rasterize(
                svgBytes,
                ImageDecodeOptions(targetWidth = 144, targetHeight = 144, antialiasing = true)
            )
        )
        val silhouette = DesktopSystemTraySilhouetteTransformer.toMacosTemplateSilhouette(supersampled)
        val buf1x = DesktopSystemTraySilhouetteTransformer.downsampleAreaBox(silhouette, 18, 18)
        val buf2x = DesktopSystemTraySilhouetteTransformer.downsampleAreaBox(silhouette, 36, 36)

        fun countIntermediateAlphaBytes(bytes: ByteArray): Int {
            var count = 0
            var i = 3
            while (i < bytes.size) {
                val a = bytes[i].toInt() and 0xFF
                if (a in 1..254) count++
                i += 4
            }
            return count
        }
        assertTrue(countIntermediateAlphaBytes(buf1x.bytes) > 0, "18x18 downsampled silhouette must have antialiased alpha pixels")
        assertTrue(countIntermediateAlphaBytes(buf2x.bytes) > 0, "36x36 downsampled silhouette must have antialiased alpha pixels")

        val tray = MacosSystemTrayNativeImpl()
        try {
            tray.setIcon(tempSvgPath)
            tray.syncNativeStatusItem()
            val statusItem = assertNotNull(tray.currentStatusItemForTesting)
            val trayImg = assertNotNull(statusItem.button?.image)
            assertTrue(trayImg.isTemplate(), "Auto-transformed tray icon must be marked template")
            trayImg.size.useContents {
                assertEquals(18.0, width, 0.1)
                assertEquals(18.0, height, 0.1)
            }

            val bitmapReps = trayImg.representations.mapNotNull { it as? NSBitmapImageRep }
            val rep1x = assertNotNull(bitmapReps.firstOrNull { it.pixelsWide == 18L && it.pixelsHigh == 18L }, "Expected 18x18 @1x rep")
            val rep2x = assertNotNull(bitmapReps.firstOrNull { it.pixelsWide == 36L && it.pixelsHigh == 36L }, "Expected 36x36 @2x rep")

            fun hasIntermediateAlpha(rep: NSBitmapImageRep): Boolean {
                for (y in 0 until rep.pixelsHigh.toInt()) {
                    for (x in 0 until rep.pixelsWide.toInt()) {
                        val a = rep.colorAtX(x.toLong(), y.toLong())?.alphaComponent ?: 0.0
                        if (a > 0.01 && a < 0.99) return true
                    }
                }
                return false
            }
            assertTrue(hasIntermediateAlpha(rep1x), "@1x NSBitmapImageRep must contain intermediate antialiased alpha pixels")
            assertTrue(hasIntermediateAlpha(rep2x), "@2x NSBitmapImageRep must contain intermediate antialiased alpha pixels")
        } finally {
            tray.dispose()
            NSFileManager.defaultManager.removeItemAtPath(tempSvgPath, error = null)
            MacosApplicationNativeImpl.interactiveMacosGuiEnabled = prevInteractive
        }
    }

    @Test
    fun unfocusedWindowTrackingUsesActiveAlwaysAcceptsFirstMouseAndRetainsLeftMouseDownForSystemMove() = withInteractiveMacosHost { factory ->
        val interactiveChild = StyledContainer().apply {
            style.setWidth(CssLength.px(120f))
            style.setHeight(CssLength.px(60f))
        }
        val plainChild = Container().apply {
            style.setWidth(CssLength.px(120f))
            style.setHeight(CssLength.px(40f))
        }
        val leafComp = StyledComponent().apply {
            style.setWidth(CssLength.px(80f))
            style.setHeight(CssLength.px(32f))
        }
        val btn = StyledButton("PiP").apply {
            style.setWidth(CssLength.px(64f))
            style.setHeight(CssLength.px(28f))
            onClick = {}
        }
        val rootContainer = Container().apply {
            addChild(interactiveChild)
            addChild(plainChild)
            addChild(leafComp)
            addChild(btn)
        }
        val window = Window(title = "Unfocused Tracking & Drag", initialSize = Size(420, 260)).apply {
            setUndecorated(true)
            setContent(rootContainer)
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val winImpl = window.ensureNativeImpl() as MacosWindowNativeImpl
        val winHandle = winImpl.nativeWidget as MacosViewHandle
        val nsWin = assertNotNull(winHandle.nsWindow)
        val rootView = assertNotNull(winHandle.nsView as? MacosWindowRootView)
        val containerView = assertNotNull((plainChild.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosContainerView)
        val styledContainerView = assertNotNull((interactiveChild.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosStyledContainerView)
        val styledCompView = assertNotNull((leafComp.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosStyledComponentView)
        val buttonView = assertNotNull((btn.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosStyledButtonControl)
        val surfaceView = MacosInteractiveSurfaceView(winHandle)
        val titleBarView = MacosTitleBarView(null)

        // 1. All views accept first mouse so unfocused clicks/drags pass through immediately.
        assertTrue(rootView.acceptsFirstMouse(null))
        assertTrue(containerView.acceptsFirstMouse(null))
        assertTrue(styledContainerView.acceptsFirstMouse(null))
        assertTrue(styledCompView.acceptsFirstMouse(null))
        assertTrue(buttonView.acceptsFirstMouse(null))
        assertTrue(surfaceView.acceptsFirstMouse(null))
        assertTrue(titleBarView.acceptsFirstMouse(null))

        // 2. Tracking areas use NSTrackingActiveAlways (not NSTrackingActiveInKeyWindow) so unfocused hover wakes overlays.
        for (trackedView in listOf(containerView, styledContainerView, styledCompView, buttonView)) {
            trackedView.updateTrackingAreas()
            val areas = trackedView.trackingAreas.mapNotNull { it as? NSTrackingArea }
            assertTrue(areas.isNotEmpty(), "Expected at least one NSTrackingArea on $trackedView")
            assertTrue(
                areas.any { (it.options and NSTrackingActiveAlways) != 0uL },
                "Tracking area must include NSTrackingActiveAlways on $trackedView"
            )
            assertFalse(
                areas.any { (it.options and NSTrackingActiveInKeyWindow) != 0uL },
                "Tracking area must not restrict hover/move to key window on $trackedView"
            )
        }

        // 3. startSystemMove event resolver falls back to recorded leftMouseDown when currentEvent is drag or null.
        val downEv = assertNotNull(
            NSEvent.mouseEventWithType(
                type = NSEventTypeLeftMouseDown,
                location = NSMakePoint(40.0, 40.0),
                modifierFlags = 0uL,
                timestamp = 1.0,
                windowNumber = nsWin.windowNumber,
                context = null,
                eventNumber = 10L,
                clickCount = 1L,
                pressure = 1f
            )
        )
        val dragEv = assertNotNull(
            NSEvent.mouseEventWithType(
                type = NSEventTypeLeftMouseDragged,
                location = NSMakePoint(56.0, 44.0),
                modifierFlags = 0uL,
                timestamp = 1.1,
                windowNumber = nsWin.windowNumber,
                context = null,
                eventNumber = 11L,
                clickCount = 1L,
                pressure = 1f
            )
        )
        styledContainerView.mouseDown(downEv)
        assertTrue(MacosNativeWindowHost.hasRecordedLeftMouseDownEvent(nsWin))
        assertEquals(downEv, MacosNativeWindowHost.resolveSystemMoveDragEventForWindow(nsWin, currentEvent = null))
        assertEquals(downEv, MacosNativeWindowHost.resolveSystemMoveDragEventForWindow(nsWin, currentEvent = dragEv))

        styledContainerView.mouseUp(downEv)
        assertFalse(MacosNativeWindowHost.hasRecordedLeftMouseDownEvent(nsWin))
        assertNull(MacosNativeWindowHost.resolveSystemMoveDragEventForWindow(nsWin, currentEvent = null))
        assertEquals(dragEv, MacosNativeWindowHost.resolveSystemMoveDragEventForWindow(nsWin, currentEvent = dragEv))

        window.close()
    }

    private fun supportsCompositedCornerCapture(hostWin: MacosHostWindow): Boolean {
        val sel = sel_registerName("_cgImageScreenShotIncludingShadow:clipRect:visualEffectViewWithDesktopBleedOnly:spaceID:")
            ?: return false
        return hostWin.respondsToSelector(sel)
    }

    private fun captureWindowCompositedAlphaCorners(hostWin: MacosHostWindow): List<Int>? {
        val sel = sel_registerName("_cgImageScreenShotIncludingShadow:clipRect:visualEffectViewWithDesktopBleedOnly:spaceID:")
            ?: return null
        if (!hostWin.respondsToSelector(sel)) return null
        val cls = object_getClass(hostWin) ?: return null
        val imp = class_getMethodImplementation(cls, sel) ?: return null
        val fn = imp.reinterpret<
            CFunction<(COpaquePointer?, COpaquePointer?, Boolean, kotlinx.cinterop.CValue<CGRect>, Boolean, ULong) -> CGImageRef?>
        >()
        val winPtr = kotlinx.cinterop.interpretCPointer<kotlinx.cinterop.CPointed>(hostWin.objcPtr()) ?: return null
        val cgImg = fn(winPtr, sel.reinterpret(), false, CGRectNull.readValue(), false, 0uL) ?: return null
        val iw = CGImageGetWidth(cgImg).toInt()
        val ih = CGImageGetHeight(cgImg).toInt()
        if (iw <= 2 || ih <= 2) return null
        val colorSpace = CGColorSpaceCreateDeviceRGB()
        val bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
        val ctx = CGBitmapContextCreate(
            data = null,
            width = iw.toULong(),
            height = ih.toULong(),
            bitsPerComponent = 8uL,
            bytesPerRow = (iw * 4).toULong(),
            space = colorSpace,
            bitmapInfo = bitmapInfo
        )
        CGColorSpaceRelease(colorSpace)
        if (ctx == null) return null
        return try {
            CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, iw.toDouble(), ih.toDouble()), cgImg)
            val dataPtr = CGBitmapContextGetData(ctx)?.reinterpret<ByteVar>() ?: return null
            val total = iw * ih * 4
            val bytes = ByteArray(total)
            bytes.usePinned { pinned ->
                platform.posix.memcpy(pinned.addressOf(0), dataPtr, total.toULong())
            }
            fun alphaAt(x: Int, y: Int): Int = bytes[(y * iw + x) * 4 + 3].toInt() and 0xFF
            listOf(
                alphaAt(0, 0),
                alphaAt(iw - 1, 0),
                alphaAt(0, ih - 1),
                alphaAt(iw - 1, ih - 1)
            )
        } finally {
            CGContextRelease(ctx)
        }
    }

    @Test
    fun nativeOverlayTitleBarRendersSquareCornersWhenUndecoratedAndRoundedWhenDecorated() = withInteractiveMacosHost { factory ->
        val titleBar = StyledContainer().apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            setAttribute("data-native-titlebar-controls", "true")
            setAttribute("data-titlebar-enabled", "true")
            setAttribute("data-titlebar-active", "true")
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.px(28f))
        }
        val content = StyledContainer().apply {
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.percent(100f))
            style.setBackgroundColor(RgbaColor(20, 24, 30, 255))
            addChild(titleBar)
        }
        val undecoratedWin = Window(title = "Square Native Overlay", initialSize = Size(480, 300)).apply {
            setUndecorated(true)
            setContent(content)
        }
        undecoratedWin.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(60L)

        val undecHost = assertNotNull(MacosNativeWindowHost.activeNsWindow as? MacosHostWindow)
        assertTrue(undecHost.isNativeOverlayTitleBarHostWindow)
        assertTrue(undecHost.usesSquareWindowCorners, "Undecorated native overlay titlebar window must enable square corners")
        assertTrue((undecHost.styleMask and NSWindowStyleMaskTitled) != 0UL)
        assertTrue((undecHost.styleMask and NSWindowStyleMaskFullSizeContentView) != 0UL)
        assertFalse(assertNotNull(undecHost.standardWindowButton(NSWindowCloseButton)).isHidden())
        assertFalse(assertNotNull(undecHost.standardWindowButton(NSWindowMiniaturizeButton)).isHidden())
        assertFalse(assertNotNull(undecHost.standardWindowButton(NSWindowZoomButton)).isHidden())

        if (supportsCompositedCornerCapture(undecHost)) {
            var lastUndecAlphas: List<Int>? = null
            val undecOpaque = pumpUntil(timeoutMs = 1500L) {
                val alphas = captureWindowCompositedAlphaCorners(undecHost)
                lastUndecAlphas = alphas
                alphas == listOf(255, 255, 255, 255)
            }
            assertTrue(
                undecOpaque,
                "Square corners must become fully opaque at all 4 extremes within timeout, last=$lastUndecAlphas"
            )
        }

        val decoratedWin = Window(title = "Rounded Decorated", initialSize = Size(420, 260)).apply {
            setContent(StyledContainer().apply {
                style.setWidth(CssLength.percent(100f))
                style.setHeight(CssLength.percent(100f))
                style.setBackgroundColor(RgbaColor(20, 24, 30, 255))
            })
        }
        decoratedWin.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(60L)

        val decImpl = decoratedWin.ensureNativeImpl() as MacosWindowNativeImpl
        val decHost = assertNotNull((decImpl.nativeWidget as MacosViewHandle).nsWindow as? MacosHostWindow)
        assertFalse(decHost.usesSquareWindowCorners, "Decorated windows must preserve standard rounded corners")
        if (supportsCompositedCornerCapture(decHost)) {
            var lastDecAlphas: List<Int>? = null
            val decRounded = pumpUntil(timeoutMs = 1500L) {
                val alphas = captureWindowCompositedAlphaCorners(decHost)
                lastDecAlphas = alphas
                alphas != null && alphas.firstOrNull() == 0
            }
            assertTrue(
                decRounded,
                "Decorated rounded window corner (0,0) must remain transparent, last=$lastDecAlphas"
            )
        }

        decoratedWin.close()
        undecoratedWin.close()
    }

    @Test
    fun nativeOverlayTitleBarRetainsControlsSquareCornersAndUnpinsHoverAcrossFullscreenExitAndMouseExit() = withInteractiveMacosHost { factory ->
        var pointerLeaveCount = 0
        val titleBar = StyledContainer().apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            setAttribute("data-native-titlebar-controls", "true")
            setAttribute("data-titlebar-enabled", "true")
            setAttribute("data-titlebar-active", "true")
            setAttribute("data-titlebar-show-close", "true")
            setAttribute("data-titlebar-show-minimize", "true")
            setAttribute("data-titlebar-show-maximize", "true")
            setAttribute("data-titlebar-show-title", "true")
            setAttribute("data-titlebar-title", "Receiver HUD")
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.px(28f))
            onPointerLeave = { pointerLeaveCount++ }
        }
        val overlayRoot = StyledContainer().apply {
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.percent(100f))
            style.setBackgroundColor(RgbaColor(18, 20, 24, 255))
            addChild(titleBar)
        }
        val window = Window(title = "Receiver HUD", initialSize = Size(800, 450)).apply {
            setUndecorated(true)
            setAspectRatio(16, 9)
            setContent(overlayRoot)
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(60L)

        val winImpl = window.ensureNativeImpl() as MacosWindowNativeImpl
        val key = winImpl.nativeWidget.nativePointer
        val hostWin = assertNotNull((winImpl.nativeWidget as MacosViewHandle).nsWindow as? MacosHostWindow)
        val rootView = assertNotNull(hostWin.contentView as? MacosWindowRootView)

        // 1. Hovering inside top 28px bar pins hover; exiting window clears pin immediately.
        rootView.onWindowMouseMoved?.invoke(100.0, 10.0)
        assertTrue(MacosNativeWindowHost.isNativeTitleBarHoverPinned(hostWin), "Moving into top 28px bar must pin native titlebar hover")

        val leaveCountBeforeExit = pointerLeaveCount
        val exitEv = assertNotNull(
            NSEvent.enterExitEventWithType(
                type = NSEventTypeMouseExited,
                location = rootView.convertClientPointToWindow(100.0, -4.0),
                modifierFlags = 0uL,
                timestamp = 2.0,
                windowNumber = hostWin.windowNumber,
                context = null,
                eventNumber = 20L,
                trackingNumber = 0L,
                userData = null
            )
        )
        rootView.mouseExited(exitEv)
        GenericUiThreading.flushAllQueues()
        assertFalse(MacosNativeWindowHost.isNativeTitleBarHoverPinned(hostWin), "mouseExited must immediately unpin native titlebar hover")
        assertTrue(pointerLeaveCount > leaveCountBeforeExit, "mouseExited unpin must dispatchPointerLeave")

        // 2. Pin hover again, then enter fullscreen via requestElementFullscreen + AppKit delegate callbacks.
        rootView.onWindowMouseMoved?.invoke(100.0, 10.0)
        assertTrue(MacosNativeWindowHost.isNativeTitleBarHoverPinned(hostWin))
        val leaveBeforeFs = pointerLeaveCount

        winImpl.setFullscreenElement(overlayRoot.ensureNativeImpl(), DisplayCutoutMode.SHORT_EDGES)
        assertNotNull(winImpl.getFullscreenElement(), "setFullscreenElement sets fullscreenElementImpl")
        titleBar.setAttribute("data-titlebar-active", "false")
        MacosNativeWindowHost.onNativeWindowFullscreenWillChange(key, isDialog = false, enteringFullscreen = true)
        MacosNativeWindowHost.onNativeWindowFullscreenChanged(key, isDialog = false, fullscreen = true)
        GenericUiThreading.flushAllQueues()
        assertFalse(MacosNativeWindowHost.isNativeTitleBarHoverPinned(hostWin), "Entering fullscreen must unpin native titlebar hover even with data-titlebar-active=false")
        assertTrue(pointerLeaveCount > leaveBeforeFs)

        // 3. Exit fullscreen while data-titlebar-active is still 'false' initially, then flip back to 'true'.
        MacosNativeWindowHost.onNativeWindowFullscreenWillChange(key, isDialog = false, enteringFullscreen = false)
        MacosNativeWindowHost.onNativeWindowFullscreenChanged(key, isDialog = false, fullscreen = false)

        assertNull(winImpl.getFullscreenElement(), "Fullscreen exit must clear fullscreenElementImpl")
        assertTrue(hostWin.isNativeOverlayTitleBarHostWindow, "Must remain native overlay titlebar host after fullscreen exit")
        assertTrue((hostWin.styleMask and NSWindowStyleMaskTitled) != 0UL, "Titled styleMask must not demote to borderless on fullscreen exit")
        assertTrue(hostWin.usesSquareWindowCorners, "Square window corners must re-enable after fullscreen exit")

        titleBar.setAttribute("data-titlebar-active", "true")
        MacosNativeWindowHost.pumpVisualWindowEvents(60L)

        val closeBtn = assertNotNull(hostWin.standardWindowButton(NSWindowCloseButton))
        val minBtn = assertNotNull(hostWin.standardWindowButton(NSWindowMiniaturizeButton))
        val zoomBtn = assertNotNull(hostWin.standardWindowButton(NSWindowZoomButton))
        val tbContainer = assertNotNull(hostWin.titlebarContainerView())
        assertFalse(closeBtn.isHidden(), "Close traffic light must remain visible after fullscreen exit")
        assertFalse(minBtn.isHidden(), "Minimize traffic light must remain visible after fullscreen exit")
        assertFalse(zoomBtn.isHidden(), "Zoom traffic light must remain visible after fullscreen exit")
        assertFalse(tbContainer.isHidden(), "NSTitlebarContainerView must remain visible after fullscreen exit")
        assertEquals(1.0, tbContainer.alphaValue, 1e-4)
        assertFalse(MacosNativeWindowHost.isNativeTitleBarHoverPinned(hostWin), "Hover pin must stay false after fullscreen exit when cursor is not hovering bar")

        window.close()
    }

    @Test
    fun hiddenFadedOverlayChildrenDoNotInstallCursorRectsAndChromeSyncDetectsHideTransition() = withInteractiveMacosHost { factory ->
        val actionBtn = StyledButton("Play").apply {
            id = "play-btn"
            style.setWidth(CssLength.px(120f))
            style.setHeight(CssLength.px(44f))
            style.setCursor(CssCursor.POINTER)
        }
        val overlayHud = StyledContainer().apply {
            id = "hud-layer"
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.percent(100f))
            style.setCursor(CssCursor.NONE)
            addChild(actionBtn)
        }
        val stageBackdrop = StyledContainer().apply {
            id = "stage"
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.percent(100f))
            style.setCursor(CssCursor.NONE)
            style.setBackgroundColor(RgbaColor(0, 0, 0, 255))
            addChild(overlayHud)
        }
        val titleBar = StyledContainer().apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            setAttribute("data-native-titlebar-controls", "true")
            setAttribute("data-titlebar-enabled", "true")
            setAttribute("data-titlebar-active", "true")
            setAttribute("data-titlebar-show-close", "true")
            setAttribute("data-titlebar-show-minimize", "true")
            setAttribute("data-titlebar-show-maximize", "true")
            setAttribute("data-titlebar-show-title", "true")
            setAttribute("data-titlebar-title", "Cursor Hide Test")
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.px(28f))
            stageBackdrop.addChild(this)
        }

        val window = Window(title = "Cursor Hide Test", initialSize = Size(480, 300)).apply {
            setUndecorated(true)
            setContent(stageBackdrop)
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val winImpl = window.ensureNativeImpl() as MacosWindowNativeImpl
        val hostWin = assertNotNull((winImpl.nativeWidget as MacosViewHandle).nsWindow as? MacosHostWindow)
        val rootView = assertNotNull(hostWin.contentView as? MacosWindowRootView)

        // While overlay HUD is visible, moving over button resolves pointing hand cursor.
        val btnBounds = actionBtn.getBounds()
        val bx = btnBounds.x + btnBounds.width * 0.5f
        val by = btnBounds.y + btnBounds.height * 0.5f
        MacosNativeWindowHost.simulateMouseMove(bx, by)
        assertTrue(MacosCursorTranslator.isSameCursor(NSCursor.pointingHandCursor, NSCursor.currentCursor))

        // Fade overlay HUD out via opacity = 0f (without setHidden(true)) and hide titlebar.
        overlayHud.style.setOpacity(0f)
        (overlayHud.ensureNativeImpl() as MacosStyledContainerNativeImpl).setOpacity(0f)
        titleBar.style.setOpacity(0f)
        (titleBar.ensureNativeImpl() as MacosStyledContainerNativeImpl).setOpacity(0f)
        MacosNativeWindowHost.pumpVisualWindowEvents(32L)

        rootView.resetCursorRects()
        MacosNativeWindowHost.simulateMouseMove(bx, by)
        assertTrue(
            MacosCursorTranslator.isHiddenCursor(NSCursor.currentCursor),
            "Faded-out overlay button (alpha <= 0.004) must not override hidden cursor"
        )

        // Verify syncNativeOverlayTitleBarChrome detects visible->hidden transition and no-ops when unchanged.
        assertFalse(
            hostWin.syncNativeOverlayTitleBarChrome(
                visible = true,
                alpha = 1.0,
                showClose = true,
                showMinimize = true,
                showMaximize = true,
                showTitle = true,
                titleText = "Cursor Hide Test"
            ),
            "Show transition does not report hidNow"
        )
        assertTrue(
            hostWin.syncNativeOverlayTitleBarChrome(
                visible = false,
                alpha = 0.0,
                showClose = true,
                showMinimize = true,
                showMaximize = true,
                showTitle = true,
                titleText = "Cursor Hide Test"
            ),
            "Visible -> hidden transition must return true so cursor is refreshed"
        )
        assertFalse(
            hostWin.syncNativeOverlayTitleBarChrome(
                visible = false,
                alpha = 0.0,
                showClose = true,
                showMinimize = true,
                showMaximize = true,
                showTitle = true,
                titleText = "Cursor Hide Test"
            ),
            "Repeated unchanged chrome sync must be a no-op returning false"
        )

        window.close()
    }

    @Test
    fun outOfBandTitleBarHoverPinReconcilesOnMouseExitAndNormalChildWindowDoesNotPin() = withInteractiveMacosHost { factory ->
        var pointerLeaveCount = 0
        val titleBar = StyledContainer().apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            setAttribute("data-native-titlebar-controls", "true")
            setAttribute("data-titlebar-enabled", "true")
            setAttribute("data-titlebar-active", "true")
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.px(28f))
            onPointerLeave = {
                setAttribute("data-native-titlebar-hover-pinned", "false")
                pointerLeaveCount++
            }
        }
        val root = StyledContainer().apply {
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.percent(100f))
            addChild(titleBar)
        }
        val window = Window(title = "Reconcile Hover Pin", initialSize = Size(640, 360)).apply {
            setUndecorated(true)
            setContent(root)
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val winImpl = window.ensureNativeImpl() as MacosWindowNativeImpl
        val hostWin = assertNotNull((winImpl.nativeWidget as MacosViewHandle).nsWindow as? MacosHostWindow)
        val rootView = assertNotNull(hostWin.contentView as? MacosWindowRootView)

        // Simulate out-of-band tracking-area hover pin on titleBar while session.nativeTitleBarHoverPinned is false.
        titleBar.setAttribute("data-native-titlebar-hover-pinned", "true")
        assertFalse(MacosNativeWindowHost.isNativeTitleBarHoverPinned(hostWin))

        val exitEv = assertNotNull(
            NSEvent.enterExitEventWithType(
                type = NSEventTypeMouseExited,
                location = rootView.convertClientPointToWindow(100.0, -4.0),
                modifierFlags = 0uL,
                timestamp = 2.0,
                windowNumber = hostWin.windowNumber,
                context = null,
                eventNumber = 21L,
                trackingNumber = 0L,
                userData = null
            )
        )
        rootView.mouseExited(exitEv)
        GenericUiThreading.flushAllQueues()
        assertTrue(pointerLeaveCount >= 1, "Out-of-band titlebar hover pin must receive dispatchPointerLeave on mouse exit")
        assertEquals("false", titleBar.getAttribute("data-native-titlebar-hover-pinned"))

        // Verify non-popup child window only blocks when pointer is inside its frame (or level >= NSPopUpMenuWindowLevel).
        val normalChild = NSWindow(
            contentRect = CGRectMake(1000.0, 1000.0, 120.0, 80.0),
            styleMask = 0uL,
            backing = platform.AppKit.NSBackingStoreBuffered,
            defer = false
        )
        normalChild.level = NSNormalWindowLevel
        normalChild.orderFront(null)
        assertFalse(
            MacosNativeWindowHost.isBlockingPopupChildWindow(normalChild, NSMakePoint(10.0, 10.0)),
            "Normal-level child window must not pin when pointer is outside its frame"
        )
        assertTrue(
            MacosNativeWindowHost.isBlockingPopupChildWindow(normalChild, NSMakePoint(1050.0, 1040.0)),
            "Normal-level child window should block only while pointer is inside its frame"
        )
        normalChild.level = NSPopUpMenuWindowLevel
        assertTrue(
            MacosNativeWindowHost.isBlockingPopupChildWindow(normalChild, NSMakePoint(10.0, 10.0)),
            "Menu/popup-level child window blocks regardless of pointer coordinates"
        )
        normalChild.orderOut(null)

        window.close()
    }

    @Test
    fun nativeZoomAndLiveResizeDeferOsResizeCallbackWithoutFightingAspectRatio() = withInteractiveMacosHost { factory ->
        val titleBar = StyledContainer().apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            setAttribute("data-native-titlebar-controls", "true")
            setAttribute("data-titlebar-enabled", "true")
            setAttribute("data-titlebar-active", "true")
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.px(28f))
        }
        val root = StyledContainer().apply {
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.percent(100f))
            addChild(titleBar)
        }
        var resizeCallbackCount = 0
        val window = Window(title = "Resize Smoothness", initialSize = Size(800, 450)).apply {
            setUndecorated(true)
            setAspectRatio(16, 9)
            setOnWindowResize { resizeCallbackCount++ }
            setContent(root)
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(48L)

        val winImpl = window.ensureNativeImpl() as MacosWindowNativeImpl
        val key = winImpl.nativeWidget.nativePointer
        val hostWin = assertNotNull((winImpl.nativeWidget as MacosViewHandle).nsWindow as? MacosHostWindow)
        val rootView = assertNotNull(hostWin.contentView as? MacosWindowRootView)

        resizeCallbackCount = 0
        hostWin.isPerformingNativeZoom = true
        hostWin.setFrame(CGRectMake(0.0, 0.0, 1024.0, 620.0), display = false)
        MacosNativeWindowHost.onNativeWindowDidResize(key, isDialog = false)
        assertEquals(0, resizeCallbackCount, "Intermediate native zoom frames must defer OS resize callbacks")
        hostWin.isPerformingNativeZoom = false

        resizeCallbackCount = 0
        MacosNativeWindowHost.onNativeWindowLiveResizeChanged(key, active = true)
        assertTrue(rootView.isInNativeLiveResize)
        hostWin.setFrame(CGRectMake(0.0, 0.0, 960.0, 540.0), display = false)
        MacosNativeWindowHost.onNativeWindowDidResize(key, isDialog = false)
        assertEquals(0, resizeCallbackCount, "Live drag resize frames must defer OS resize callbacks until live resize ends")

        MacosNativeWindowHost.onNativeWindowLiveResizeChanged(key, active = false)
        assertFalse(rootView.isInNativeLiveResize)
        assertEquals(1, resizeCallbackCount, "Ending live resize must dispatch exactly one settled OS resize callback")

        window.close()
    }

    @Test
    fun nativeOverlayTitleBarHoverPinIsNotRepinnedDuringFullscreenTransition() = withInteractiveMacosHost { factory ->
        val titleBar = StyledContainer().apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            setAttribute("data-native-titlebar-controls", "true")
            setAttribute("data-titlebar-enabled", "true")
            setAttribute("data-titlebar-active", "true")
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.px(28f))
        }
        val overlayRoot = StyledContainer().apply {
            style.setWidth(CssLength.percent(100f))
            style.setHeight(CssLength.percent(100f))
            style.setBackgroundColor(RgbaColor(18, 20, 24, 255))
            addChild(titleBar)
        }
        val window = Window(title = "Fullscreen Transition Hover Guard", initialSize = Size(800, 450)).apply {
            setUndecorated(true)
            setAspectRatio(16, 9)
            setContent(overlayRoot)
        }
        window.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(60L)

        val winImpl = window.ensureNativeImpl() as MacosWindowNativeImpl
        val key = winImpl.nativeWidget.nativePointer
        val hostWin = assertNotNull((winImpl.nativeWidget as MacosViewHandle).nsWindow as? MacosHostWindow)
        val rootView = assertNotNull(hostWin.contentView as? MacosWindowRootView)

        rootView.onWindowMouseMoved?.invoke(100.0, 10.0)
        assertTrue(MacosNativeWindowHost.isNativeTitleBarHoverPinned(hostWin), "Precondition: moving into titlebar pins hover")

        MacosNativeWindowHost.onNativeWindowFullscreenWillChange(key, isDialog = false, enteringFullscreen = true)
        assertTrue(hostWin.isTransitioningFullscreen, "Fullscreen transition must be active after willChange")
        assertFalse(MacosNativeWindowHost.isNativeTitleBarHoverPinned(hostWin), "Entering fullscreen transition must clear hover pin")

        rootView.onWindowMouseMoved?.invoke(100.0, 10.0)
        assertFalse(
            MacosNativeWindowHost.isNativeTitleBarHoverPinned(hostWin),
            "onWindowMouseMoved during fullscreen transition must not re-pin native titlebar hover"
        )

        MacosNativeWindowHost.syncAllNativeOverlayTitleBars()
        assertFalse(
            MacosNativeWindowHost.isNativeTitleBarHoverPinned(hostWin),
            "syncAllNativeOverlayTitleBars during fullscreen transition must not re-pin native titlebar hover"
        )

        MacosNativeWindowHost.onNativeWindowFullscreenChanged(key, isDialog = false, fullscreen = true)
        assertFalse(hostWin.isTransitioningFullscreen)
        assertFalse(MacosNativeWindowHost.isNativeTitleBarHoverPinned(hostWin))

        MacosNativeWindowHost.onNativeWindowFullscreenWillChange(key, isDialog = false, enteringFullscreen = false)
        MacosNativeWindowHost.onNativeWindowFullscreenChanged(key, isDialog = false, fullscreen = false)
        window.close()
    }
}
