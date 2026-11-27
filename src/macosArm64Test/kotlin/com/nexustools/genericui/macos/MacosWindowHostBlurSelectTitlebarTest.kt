@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.animation.AnimationSpec
import com.nexustools.genericui.apple.style.AppleBackdropFilterController
import com.nexustools.genericui.component.action.SplitDropdownButton
import com.nexustools.genericui.component.action.styled.StyledButton
import com.nexustools.genericui.component.form.RangeSlider
import com.nexustools.genericui.component.form.styled.StyledSlider
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.LayerConstraint
import com.nexustools.genericui.component.layout.LayeredContainer
import com.nexustools.genericui.component.layout.styled.StyledContainer
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.surface.styled.StyledComponent
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.desktop.widget.DesktopStyledComponentNativeImpl
import com.nexustools.genericui.enums.bootstrap.AlignItems
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.enums.css.CssPointerEvents
import com.nexustools.genericui.enums.css.CssPosition
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.impl.MacosSplitDropdownView
import com.nexustools.genericui.macos.impl.MacosTabNativeImpl
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCornerRadii
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.transition.CssTimingFunction
import com.nexustools.genericui.threading.GenericUiThreading
import com.nexustools.genericui.window.TitleBar
import kotlinx.cinterop.useContents
import platform.AppKit.NSApplication
import platform.AppKit.NSBackingStoreBuffered
import platform.AppKit.NSFloatingWindowLevel
import platform.AppKit.NSPanel
import platform.AppKit.NSScrollView
import platform.AppKit.NSSlider
import platform.AppKit.NSTitlebarSeparatorStyle
import platform.AppKit.NSView
import platform.AppKit.NSWindowCloseButton
import platform.AppKit.NSWindowMiniaturizeButton
import platform.AppKit.NSWindowStyleMaskClosable
import platform.AppKit.NSWindowStyleMaskMiniaturizable
import platform.AppKit.NSWindowStyleMaskResizable
import platform.AppKit.NSWindowStyleMaskTitled
import platform.AppKit.NSWindowTitleHidden
import platform.AppKit.NSWindowTitleVisible
import platform.AppKit.NSWindowZoomButton
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSThread
import platform.QuartzCore.CACurrentMediaTime
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MacosWindowHostBlurSelectTitlebarTest {

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

    private inline fun withCleanMacosTestEnvironment(
        interactiveGui: Boolean = true,
        crossinline block: (MacosNativeFactory) -> Unit
    ) {
        val prevFactory = GenericUI.activeFactory
        val prevApp = Application.current
        val prevInteractive = MacosApplicationNativeImpl.interactiveMacosGuiEnabled
        val prevVisual = MacosNativeWindowHost.visualTestMode

        Application.current = null
        MacosApplicationNativeImpl.interactiveMacosGuiEnabled = interactiveGui
        MacosNativeWindowHost.visualTestMode = true
        val factory = MacosNativeFactory()
        GenericUI.initialize(factory)
        try {
            block(factory)
        } finally {
            MacosNativeWindowHost.closeAllVisualWindows()
            GenericUiThreading.flushAllQueues()
            MacosNativeWindowHost.visualTestMode = prevVisual
            MacosApplicationNativeImpl.interactiveMacosGuiEnabled = prevInteractive
            Application.current = prevApp
            GenericUI.activeFactory = prevFactory
        }
    }

    private fun centerInRoot(root: NSView, target: NSView): Pair<Double, Double> {
        val pt = target.bounds.useContents {
            root.convertPoint(
                NSMakePoint(origin.x + size.width * 0.5, origin.y + size.height * 0.5),
                fromView = target
            )
        }
        return pt.useContents { x to y }
    }

    @Test
    fun testDialogPopupAnchorButtonInScrolled640x560DialogWindowSplitDropdownAndBoundsHook() {
        val anchorNearTop = Rect(x = 40, y = 24, width = 180, height = 32)
        val topPt = MacosMenuBarBridge.computeSelectPopupScreenPoint(
            anchorBounds = anchorNearTop,
            contentOriginInScreenX = 300.0,
            contentOriginInScreenY = 200.0,
            canvasHeight = 560.0
        )
        assertEquals(340.0, topPt.first, 0.01)
        assertEquals(704.0, topPt.second, 0.01)

        runOnMainThreadSync {
            NSApplication.sharedApplication
            val panel = NSPanel(
                contentRect = NSMakeRect(240.0, 180.0, 640.0, 560.0),
                styleMask = NSWindowStyleMaskTitled or NSWindowStyleMaskClosable or NSWindowStyleMaskResizable,
                backing = NSBackingStoreBuffered,
                defer = false
            )
            panel.releasedWhenClosed = false
            val rootContent = MacosWindowRootView(windowKey = 9101L, frameRect = NSMakeRect(0.0, 0.0, 640.0, 560.0))
            panel.contentView = rootContent
            try {
                val scrolledAnchor = Rect(x = 56, y = 342, width = 224, height = 34)
                val selectBtn = MacosMenuBarBridge.createSelectPopupAnchorButton(
                    options = listOf("opt1" to "First Option", "opt2" to "Second Option", "opt3" to "Third Option"),
                    selectedValue = "opt2",
                    anchorBounds = scrolledAnchor,
                    onSelected = {}
                )
                assertFalse(selectBtn.pullsDown, "Select/ComboBox popup anchor button must have pullsDown = false")
                assertEquals(1L, selectBtn.indexOfSelectedItem.toLong())
                assertEquals(224.0, selectBtn.menu?.minimumWidth ?: 0.0, 0.1)
                selectBtn.frame.useContents {
                    assertEquals(56.0, origin.x, 0.1)
                    assertEquals(342.0, origin.y, 0.1)
                    assertEquals(224.0, size.width, 0.1)
                    assertEquals(34.0, size.height, 0.1)
                }
                rootContent.addSubview(selectBtn)
                assertEquals(panel, selectBtn.window, "Mounting anchor button inside rootContent must attach parentWindow (NSPanel)")
                assertEquals(rootContent, selectBtn.superview)
                selectBtn.removeFromSuperview()

                val dropdownBtn = MacosMenuBarBridge.createDropdownPopupAnchorButton(
                    items = listOf("a" to "Action A", "b" to "Action B"),
                    anchorBounds = scrolledAnchor,
                    onItemSelected = {}
                )
                assertTrue(dropdownBtn.pullsDown, "DropdownButton/SplitDropdownButton anchor must have pullsDown = true")
                assertEquals(224.0, dropdownBtn.menu?.minimumWidth ?: 0.0, 0.1)
                val ddMenu = dropdownBtn.menu
                assertNotNull(ddMenu)
                assertEquals(3L, ddMenu.numberOfItems.toLong(), "Pull-down menu requires hidden item[0] + 2 real items")
                assertEquals("", ddMenu.itemAtIndex(0)?.title)
                assertEquals("Action A", ddMenu.itemAtIndex(1)?.title)
                assertEquals("Action B", ddMenu.itemAtIndex(2)?.title)
                rootContent.addSubview(dropdownBtn)
                assertEquals(panel, dropdownBtn.window)
                dropdownBtn.removeFromSuperview()

                val prevBoundsHook = MacosDialogWindowNativeImpl.onBoundsChangedHook
                val dlg = MacosDialogWindowNativeImpl()
                var hookCalls = 0
                try {
                    MacosDialogWindowNativeImpl.onBoundsChangedHook = { changedDlg ->
                        if (changedDlg === dlg) hookCalls++
                    }
                    dlg.setBounds(10, 20, 640, 560)
                    dlg.setPreferredSize(Size(680, 580))
                    assertEquals(2, hookCalls)
                } finally {
                    MacosDialogWindowNativeImpl.onBoundsChangedHook = prevBoundsHook
                    dlg.dispose()
                }
            } finally {
                panel.orderOut(null)
            }
        }

        // SplitDropdownButton mounts distinct retained NSButton primary + NSPopUpButton menu subviews
        withCleanMacosTestEnvironment(interactiveGui = true) { factory ->
            var primaryClicks = 0
            val splitBtn = SplitDropdownButton("Quick Build") {
                primaryClicks++
            }.apply {
                setItems(listOf("dev" to "Development", "prod" to "Production"))
                getStyle().apply {
                    setWidth(CssLength.px(160))
                    setHeight(CssLength.px(32))
                }
            }
            val win = Window(title = "Split Dropdown", initialSize = Size(300, 80))
            win.addChild(splitBtn)
            win.show(factory)
            try {
                MacosNativeWindowHost.flushPendingDisplay()
                val winHandle = win.ensureNativeImpl()!!.nativeWidget as MacosViewHandle
                val rootView = assertNotNull(winHandle.nsView as? MacosWindowRootView)
                val splitView = assertNotNull((splitBtn.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosSplitDropdownView)
                assertEquals(splitView, splitView.primaryButton.superview)
                assertEquals(splitView, splitView.menuButton.superview)
                assertFalse(splitView.primaryButton == splitView.menuButton)
                assertTrue(splitView.menuButton.pullsDown)
                assertEquals(3L, splitView.menuButton.numberOfItems.toLong())

                val (px, py) = centerInRoot(rootView, splitView.primaryButton)
                val (mx, my) = centerInRoot(rootView, splitView.menuButton)
                assertEquals<platform.AppKit.NSView?>(splitView.primaryButton, rootView.hitTestClientPoint(px, py))
                assertEquals<platform.AppKit.NSView?>(splitView.menuButton, rootView.hitTestClientPoint(mx, my))

                MacosNativeWindowHost.simulateMouseClick(px.toFloat(), py.toFloat())
                GenericUiThreading.flushAllQueues()
                assertEquals(1, primaryClicks)
            } finally {
                win.dispose()
            }
        }
    }

    @Test
    fun testRetainedInteractiveSurface3SlotRingBufferReusesBuffersAcrossFrames() = withCleanMacosTestEnvironment(interactiveGui = false) { factory ->
        val stageContainer = Container().apply {
            getStyle().apply {
                setWidth(CssLength.px(120))
                setHeight(CssLength.px(90))
            }
        }
        val win = Window(title = "Ring Surface Test", initialSize = Size(120, 90))
        win.addChild(stageContainer)
        win.show(factory)
        try {
            val winImpl = win.nativeImpl as MacosWindowNativeImpl
            val containerImpl = stageContainer.ensureNativeImpl() as MacosContainerNativeImpl
            val stageNative = containerImpl.nativeWidget

            var currentSwap = 7L
            var captureCalls = 0
            val frameBytes = ByteArray(16 * 12 * 4) { 0x7F }
            DesktopInteractiveSurfaceSpec(
                surfaceWidth = { 16 },
                surfaceHeight = { 12 },
                swapCount = { currentSwap },
                captureBgra = {
                    captureCalls++
                    frameBytes
                }
            ).attachTo(stageNative)

            // Trigger retained mount + initial frame presentation
            val shot1 = MacosNativeWindowHost.captureWindowScreenshot(winImpl, 120, 90)
            assertNotNull(shot1)
            val surfaceView = (stageNative as MacosViewHandle).nsView
                ?.subviews
                ?.filterIsInstance<MacosInteractiveSurfaceView>()
                ?.firstOrNull()
            assertNotNull(surfaceView, "Expected retained MacosInteractiveSurfaceView mounted on stage container")
            assertEquals(3, surfaceView.ringSlotCount)
            assertEquals(1, captureCalls)
            assertEquals(7L, surfaceView.activePresentedSwapCount)

            // Repeated draws at identical swapCount must skip duplicate captures
            for (pass in 0 until 5) {
                surfaceView.presentLatestFrame(forceRefresh = false)
            }
            assertEquals(1, captureCalls, "Repeated draws at identical swapCount must reuse active CGImage slot")

            // Advancing swapCount captures new frames cleanly across the 3-slot native-heap ring
            val seenSlots = mutableListOf<Int>()
            for (nextSwap in 8L..14L) {
                currentSwap = nextSwap
                assertTrue(surfaceView.presentLatestFrame(forceRefresh = false))
                seenSlots.add(surfaceView.activeRingSlotIndex)
            }
            assertEquals(8, captureCalls)
            assertEquals(listOf(2, 0, 1, 2, 0, 1, 2), seenSlots, "Frames must cycle cleanly across 3 ring slots")

            val slotPtrs = (0 until surfaceView.ringSlotCount).map { surfaceView.ringSlotPointerAddress(it) }
            assertTrue(slotPtrs.all { it != 0L }, "All 3 ring slots must retain native heap pointers")
            assertEquals(3, slotPtrs.toSet().size, "Each of the 3 ring slots must have a distinct native heap pointer")
            assertEquals(3, surfaceView.ringAllocationCount, "Ring buffer must allocate each of the 3 slots at most once for same dimensions")
            assertTrue(surfaceView.ringSlotReuseCount >= 5, "Ring buffer must reuse existing slot memory across subsequent frames")

            // Exercise retained NSScrollView clip scrolling smoothly across 45 steps
            val scroller = NSScrollView(frame = NSMakeRect(0.0, 0.0, 200.0, 180.0)).apply {
                setHasVerticalScroller(true)
                setDocumentView(NSView(frame = NSMakeRect(0.0, 0.0, 200.0, 1200.0)))
            }
            val clip = scroller.contentView()
            for (step in 0 until 45) {
                val targetY = step * (1020.0 / 44.0)
                clip.scrollToPoint(NSMakePoint(0.0, targetY))
                scroller.reflectScrolledClipView(clip)
            }
            assertTrue(clip.bounds.useContents { origin.y } > 900.0)
        } finally {
            win.dispose()
        }
    }

    @Test
    fun testFindStageOverlayInSubtreeDescendsPastOverlayHostRoot() = withCleanMacosTestEnvironment(interactiveGui = true) { factory ->
        val stageContainer = LayeredContainer().apply {
            setAttribute("data-overlay-host", "true")
        }
        val videoSurface = Container().apply {
            getStyle().apply {
                setWidth(CssLength.PERCENT_100)
                setHeight(CssLength.PERCENT_100)
            }
        }
        val overlayHostWrapper = LayeredContainer().apply {
            setAttribute("data-overlay-host-root", "true")
            addClass("gui-overlay-host-root")
            addClass("gui-overlay")
            pointerEventsEnabled = false
        }
        val activeOverlay = Container().apply {
            setAttribute("data-overlay-root", "true")
            addClass("gui-overlay")
            pointerEventsEnabled = false
            getStyle().apply {
                setWidth(CssLength.PERCENT_100)
                setHeight(CssLength.PERCENT_100)
            }
        }
        var btnClicks = 0
        val overlayActionBtn = StyledButton("Overlay Action").apply {
            getStyle().apply {
                setWidth(CssLength.px(140))
                setHeight(CssLength.px(36))
            }
            onClick = { btnClicks++ }
        }
        activeOverlay.addChild(overlayActionBtn)
        overlayHostWrapper.addFillLayer(activeOverlay, zIndex = 5, passThroughPointerEvents = true)
        stageContainer.addFillLayer(videoSurface, zIndex = 0)
        stageContainer.addFillLayer(overlayHostWrapper, zIndex = 10, passThroughPointerEvents = true)

        val win = Window(title = "Overlay Subtree Hit Test", initialSize = Size(400, 240))
        win.setContent(stageContainer)
        win.show(factory)
        try {
            MacosNativeWindowHost.flushPendingDisplay()
            val rootView = assertNotNull((win.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosWindowRootView)
            val wrapperView = (overlayHostWrapper.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView!!
            val actionView = (overlayActionBtn.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView!!

            // Wrapper with data-overlay-host-root must NOT swallow hits on empty regions
            val emptyHit = rootView.hitTestClientPoint(350.0, 200.0)
            assertFalse(emptyHit == wrapperView, "data-overlay-host-root wrapper must pass empty background hits through")

            // Interactive child inside active data-overlay-root must resolve through NSView tree
            val (bx, by) = centerInRoot(rootView, actionView)
            val buttonHit = rootView.hitTestClientPoint(bx, by)
            assertEquals(actionView, buttonHit, "NSView hitTest must descend past wrapper into active overlay control")

            MacosNativeWindowHost.simulateMouseClick(bx.toFloat(), by.toFloat())
            GenericUiThreading.flushAllQueues()
            assertEquals(1, btnClicks)

            // Removing active overlay leaves wrapper pass-through without hitting removed child
            overlayHostWrapper.removeLayer(activeOverlay)
            MacosNativeWindowHost.flushPendingDisplay()
            val afterRemovalHit = rootView.hitTestClientPoint(bx, by)
            assertFalse(afterRemovalHit == actionView)
        } finally {
            win.dispose()
        }
    }

    @Test
    fun testOverlayWindowTitleBarControlsRoutingAndDarkThemeContrast() = withCleanMacosTestEnvironment(interactiveGui = true) { factory ->
        var closeClicked = 0
        var minimizeClicked = 0
        var maximizeClicked = 0

        val windowTitleBar = StyledContainer().apply {
            setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR)
            setAttribute("data-titlebar-theme", "dark")
            setAttribute("windowTitle", "Player Stage")
            backgroundColor = RgbaColor(20, 22, 28, 255)
            getStyle().apply {
                setDisplay(CssDisplay.FLEX)
                setFlexDirection(FlexDirection.ROW)
                setAlignItems(AlignItems.CENTER)
                setGap(CssLength.px(8))
                setPaddingLeft(CssLength.px(10))
                setWidth(CssLength.px(480))
                setHeight(CssLength.px(34))
            }
        }
        val closeBtn = StyledButton("x").apply {
            setAttribute("data-window-control", "close")
            id = "overlay-window-close-btn"
            backgroundColor = RgbaColor(220, 60, 60, 255)
            getStyle().apply { setWidth(CssLength.px(20)); setHeight(CssLength.px(20)) }
            onClick = { closeClicked++ }
        }
        val minBtn = StyledButton("-").apply {
            setAttribute("data-window-control", "minimize")
            id = "overlay-window-minimize-btn"
            backgroundColor = RgbaColor(220, 170, 50, 255)
            getStyle().apply { setWidth(CssLength.px(20)); setHeight(CssLength.px(20)) }
            onClick = { minimizeClicked++ }
        }
        val maxBtn = StyledButton("+").apply {
            setAttribute("data-window-control", "maximize")
            id = "overlay-window-maximize-btn"
            backgroundColor = RgbaColor(60, 185, 85, 255)
            getStyle().apply { setWidth(CssLength.px(20)); setHeight(CssLength.px(20)) }
            onClick = { maximizeClicked++ }
        }
        val titleText = Text("Player Stage").apply {
            getStyle().setColor(RgbaColor(245, 247, 250, 255))
        }
        windowTitleBar.addChild(closeBtn)
        windowTitleBar.addChild(minBtn)
        windowTitleBar.addChild(maxBtn)
        windowTitleBar.addChild(titleText)

        val win = Window(title = "Player Stage", initialSize = Size(480, 64))
        win.addChild(windowTitleBar)
        win.show(factory)
        try {
            MacosNativeWindowHost.flushPendingDisplay()
            val winImpl = win.nativeImpl as MacosWindowNativeImpl
            val rootView = assertNotNull((winImpl.nativeWidget as MacosViewHandle).nsView as? MacosWindowRootView)
            val barView = (windowTitleBar.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView!!
            val closeView = (closeBtn.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView!!
            val minView = (minBtn.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView!!
            val maxView = (maxBtn.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView!!

            val (cx, cy) = centerInRoot(rootView, closeView)
            val (mnX, mnY) = centerInRoot(rootView, minView)
            val (mxX, mxY) = centerInRoot(rootView, maxView)

            assertEquals(closeView, rootView.hitTestClientPoint(cx, cy))
            assertEquals(minView, rootView.hitTestClientPoint(mnX, mnY))
            assertEquals(maxView, rootView.hitTestClientPoint(mxX, mxY))

            // Empty right area of bar hits barView and does not trigger control buttons
            val emptyBarHit = rootView.hitTestClientPoint(420.0, 17.0)
            assertEquals(barView, emptyBarHit)

            MacosNativeWindowHost.simulateMouseClick(cx.toFloat(), cy.toFloat())
            MacosNativeWindowHost.simulateMouseClick(mnX.toFloat(), mnY.toFloat())
            MacosNativeWindowHost.simulateMouseClick(mxX.toFloat(), mxY.toFloat())
            MacosNativeWindowHost.simulateMouseClick(420f, 17f)
            GenericUiThreading.flushAllQueues()

            assertEquals(1, closeClicked)
            assertEquals(1, minimizeClicked)
            assertEquals(1, maximizeClicked)

            // Verify dark titlebar renders dark background at empty spot and legible bright text pixels over title
            val shot = assertNotNull(MacosNativeWindowHost.captureWindowScreenshot(winImpl, 480, 64))
            val bgIdx = (17 * shot.width + 420) * 4
            val bgR = shot.bytes[bgIdx].toInt() and 0xFF
            val bgG = shot.bytes[bgIdx + 1].toInt() and 0xFF
            val bgB = shot.bytes[bgIdx + 2].toInt() and 0xFF
            assertTrue(bgR < 50 && bgG < 50 && bgB < 50, "Dark overlay titlebar background must render dark ($bgR,$bgG,$bgB)")

            val titleView = (titleText.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView!!
            val titleRect = titleView.convertRect(titleView.bounds, toView = rootView)
            val tx0 = titleRect.useContents { origin.x.roundToInt() }.coerceIn(0, shot.width - 1)
            val ty0 = titleRect.useContents { origin.y.roundToInt() }.coerceIn(0, shot.height - 1)
            val tx1 = titleRect.useContents { (origin.x + size.width).roundToInt() }.coerceIn(tx0 + 1, shot.width)
            val ty1 = titleRect.useContents { (origin.y + size.height).roundToInt() }.coerceIn(ty0 + 1, shot.height)
            var brightTextPixels = 0
            for (y in ty0 until ty1) {
                for (x in tx0 until tx1) {
                    val o = (y * shot.width + x) * 4
                    val r = shot.bytes[o].toInt() and 0xFF
                    val g = shot.bytes[o + 1].toInt() and 0xFF
                    val b = shot.bytes[o + 2].toInt() and 0xFF
                    if (r > 180 && g > 180 && b > 180) brightTextPixels++
                }
            }
            assertTrue(brightTextPixels > 20, "Title text on dark titlebar must render high-contrast light glyphs")
        } finally {
            win.dispose()
        }
    }

    @Test
    fun testUndecoratedHostWindowSharpCornersMiniaturizeAndDeminiaturizeRestoration() = withCleanMacosTestEnvironment(interactiveGui = true) { factory ->
        runOnMainThreadSync {
            val win = Window(title = "Undecorated Sharp Host", initialSize = Size(640, 400)).apply {
                setUndecorated(true)
                setAlwaysOnTop(true)
                setAspectRatio(16.0 / 9.0)
            }
            win.addChild(Container())
            win.show(factory)
            try {
                val winImpl = win.nativeImpl as MacosWindowNativeImpl
                val handle = winImpl.nativeWidget as MacosViewHandle
                val hostWin = assertNotNull(handle.nsWindow as? MacosHostWindow)
                val rootView = assertNotNull(handle.nsView as? MacosWindowRootView)
                val windowKey = handle.nativePointer

                // Sharp 0px rectangular corners: NO NSWindowStyleMaskTitled
                assertEquals(0UL, hostWin.styleMask and NSWindowStyleMaskTitled)
                assertTrue(hostWin.hasShadow)
                assertEquals(
                    NSWindowStyleMaskClosable or NSWindowStyleMaskMiniaturizable or NSWindowStyleMaskResizable,
                    hostWin.styleMask and (NSWindowStyleMaskClosable or NSWindowStyleMaskMiniaturizable or NSWindowStyleMaskResizable)
                )
                assertEquals(NSWindowTitleHidden, hostWin.titleVisibility)
                assertFalse(hostWin.movableByWindowBackground)
                assertFalse(rootView.mouseDownCanMoveWindow())

                // Transient promotion + hidden button enabled=true + alwaysOnTop lowering on miniaturize()
                hostWin.level = NSFloatingWindowLevel
                hostWin.orderFront(null)
                hostWin.miniaturize(null)
                assertEquals(
                    UNDECORATED_TRANSITION_STYLE_MASK,
                    hostWin.styleMask and UNDECORATED_TRANSITION_STYLE_MASK
                )
                val minBtn = hostWin.standardWindowButton(NSWindowMiniaturizeButton)
                assertNotNull(minBtn, "Promoted MacosHostWindow must expose _miniaturizeButton")
                assertTrue(minBtn.enabled, "_miniaturizeButton.enabled must stay true")
                assertEquals(1.0, minBtn.alphaValue, 0.001)
                assertTrue(minBtn.hidden)

                val deadline = CACurrentMediaTime() + 0.5
                while (!hostWin.miniaturized && CACurrentMediaTime() < deadline) {
                    CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.02, false)
                }
                if (hostWin.miniaturized) {
                    hostWin.deminiaturize(null)
                    val restoreDeadline = CACurrentMediaTime() + 0.5
                    while (hostWin.miniaturized && CACurrentMediaTime() < restoreDeadline) {
                        CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.02, false)
                    }
                }

                // Invoke delegate deminiaturize path cleanly and verify full state restoration
                MacosNativeWindowHost.onNativeWindowMiniaturized(windowKey, isDialog = false, miniaturized = false)
                assertEquals(0UL, hostWin.styleMask and NSWindowStyleMaskTitled, "Deminiaturize must strip NSWindowStyleMaskTitled back to 0px sharp UNDECORATED_WINDOWED_STYLE_MASK")
                assertEquals(UNDECORATED_WINDOWED_STYLE_MASK, hostWin.styleMask)
                assertEquals(NSFloatingWindowLevel, hostWin.level)
                assertEquals(WindowState.NORMAL, winImpl.getWindowState())
                hostWin.contentAspectRatio.useContents {
                    assertEquals(16.0 / 9.0, width / height, 0.01)
                }
            } finally {
                win.close()
                win.dispose()
            }
        }
    }

    @Test
    fun testFullscreenHoverTrafficLightsNoExitFlashAndSpacesExitRestore() = withCleanMacosTestEnvironment(interactiveGui = true) { factory ->
        runOnMainThreadSync {
            val win = Window(title = "Spaces Fullscreen Stage", initialSize = Size(800, 500)).apply {
                setUndecorated(true)
            }
            win.addChild(Container())
            win.show(factory)
            try {
                val winImpl = win.nativeImpl as MacosWindowNativeImpl
                val handle = winImpl.nativeWidget as MacosViewHandle
                val hostWin = assertNotNull(handle.nsWindow as? MacosHostWindow)
                val windowKey = handle.nativePointer

                // 1. Preparing fullscreen promotes titlebar hover chrome BEFORE AppKit builds NSToolbarFullScreenWindow
                // so the centered title lays out to the right of all 3 visible traffic lights (Close, disabled Minimize, Zoom).
                MacosNativeWindowHost.onNativeWindowFullscreenWillChange(windowKey, isDialog = false, enteringFullscreen = true)
                assertNotEquals(0UL, hostWin.styleMask and NSWindowStyleMaskTitled)
                assertFalse(hostWin.titlebarAppearsTransparent)
                assertEquals(NSWindowTitleVisible, hostWin.titleVisibility)
                assertTrue(hostWin.standardWindowButton(NSWindowCloseButton)?.hidden == false)
                assertTrue(hostWin.standardWindowButton(NSWindowMiniaturizeButton)?.hidden == false)
                assertFalse(hostWin.standardWindowButton(NSWindowMiniaturizeButton)?.enabled ?: true)
                assertTrue(hostWin.standardWindowButton(NSWindowZoomButton)?.hidden == false)

                // 2. Did enter fullscreen keeps red Close + disabled Minimize + green Zoom in slide-down menu bar
                MacosNativeWindowHost.onNativeWindowFullscreenChanged(windowKey, isDialog = false, fullscreen = true)
                assertEquals("Spaces Fullscreen Stage", hostWin.title)
                assertFalse(hostWin.titlebarAppearsTransparent)
                assertEquals(NSWindowTitleVisible, hostWin.titleVisibility)
                assertEquals(NSTitlebarSeparatorStyle.NSTitlebarSeparatorStyleAutomatic, hostWin.titlebarSeparatorStyle)
                val closeBtn = hostWin.standardWindowButton(NSWindowCloseButton)
                val minBtn = hostWin.standardWindowButton(NSWindowMiniaturizeButton)
                val zoomBtn = hostWin.standardWindowButton(NSWindowZoomButton)
                assertNotNull(closeBtn)
                assertNotNull(minBtn)
                assertNotNull(zoomBtn)
                assertFalse(closeBtn.hidden)
                assertTrue(closeBtn.enabled)
                assertFalse(minBtn.hidden)
                assertFalse(minBtn.enabled)
                assertFalse(zoomBtn.hidden)
                assertTrue(zoomBtn.enabled)

                // 3. Will exit fullscreen immediately hides traffic lights & makes titlebar transparent BEFORE exit snapshot
                MacosNativeWindowHost.onNativeWindowFullscreenWillChange(windowKey, isDialog = false, enteringFullscreen = false)
                assertTrue(hostWin.titlebarAppearsTransparent)
                assertEquals(NSWindowTitleHidden, hostWin.titleVisibility)
                assertEquals(NSTitlebarSeparatorStyle.NSTitlebarSeparatorStyleNone, hostWin.titlebarSeparatorStyle)
                assertTrue(hostWin.standardWindowButton(NSWindowCloseButton)?.hidden == true)
                assertTrue(hostWin.standardWindowButton(NSWindowMiniaturizeButton)?.hidden == true)
                assertTrue(hostWin.standardWindowButton(NSWindowZoomButton)?.hidden == true)

                // 4. Did exit (and failed-to-enter) strips NSWindowStyleMaskTitled back to UNDECORATED_WINDOWED_STYLE_MASK
                MacosNativeWindowHost.onNativeWindowFullscreenFailed(windowKey, isDialog = false)
                assertEquals(0UL, hostWin.styleMask and NSWindowStyleMaskTitled)
                hostWin.styleMask = UNDECORATED_TRANSITION_STYLE_MASK
                MacosNativeWindowHost.onNativeWindowFullscreenChanged(windowKey, isDialog = false, fullscreen = false)
                assertEquals(0UL, hostWin.styleMask and NSWindowStyleMaskTitled)
                assertEquals(UNDECORATED_WINDOWED_STYLE_MASK, hostWin.styleMask)
            } finally {
                win.close()
                win.dispose()
            }
        }
    }

    @Test
    fun testUndecorated8EdgeResizeDetectionCursorsControlClearanceAndAspectGeometry() {
        val w = 800.0
        val h = 500.0
        assertEquals(MacosNativeWindowHost.UndecoratedResizeEdge.NW, MacosNativeWindowHost.detectUndecoratedResizeEdge(2.0, 2.0, w, h, isWindowControlHit = false))
        assertEquals(MacosNativeWindowHost.UndecoratedResizeEdge.N, MacosNativeWindowHost.detectUndecoratedResizeEdge(400.0, 3.0, w, h, isWindowControlHit = false))
        assertEquals(MacosNativeWindowHost.UndecoratedResizeEdge.NE, MacosNativeWindowHost.detectUndecoratedResizeEdge(797.0, 2.0, w, h, isWindowControlHit = false))
        assertEquals(MacosNativeWindowHost.UndecoratedResizeEdge.W, MacosNativeWindowHost.detectUndecoratedResizeEdge(2.0, 250.0, w, h, isWindowControlHit = false))
        assertEquals(MacosNativeWindowHost.UndecoratedResizeEdge.E, MacosNativeWindowHost.detectUndecoratedResizeEdge(796.0, 250.0, w, h, isWindowControlHit = false))
        assertEquals(MacosNativeWindowHost.UndecoratedResizeEdge.SW, MacosNativeWindowHost.detectUndecoratedResizeEdge(3.0, 497.0, w, h, isWindowControlHit = false))
        assertEquals(MacosNativeWindowHost.UndecoratedResizeEdge.S, MacosNativeWindowHost.detectUndecoratedResizeEdge(400.0, 497.0, w, h, isWindowControlHit = false))
        assertEquals(MacosNativeWindowHost.UndecoratedResizeEdge.SE, MacosNativeWindowHost.detectUndecoratedResizeEdge(797.0, 497.0, w, h, isWindowControlHit = false))
        assertEquals(MacosNativeWindowHost.UndecoratedResizeEdge.NONE, MacosNativeWindowHost.detectUndecoratedResizeEdge(400.0, 250.0, w, h, isWindowControlHit = false))

        assertEquals(
            MacosNativeWindowHost.UndecoratedResizeEdge.NONE,
            MacosNativeWindowHost.detectUndecoratedResizeEdge(780.0, 4.0, w, h, isWindowControlHit = true)
        )
        assertEquals(
            MacosNativeWindowHost.UndecoratedResizeEdge.N,
            MacosNativeWindowHost.detectUndecoratedResizeEdge(780.0, 1.0, w, h, isWindowControlHit = true)
        )

        for (edge in MacosNativeWindowHost.UndecoratedResizeEdge.entries) {
            val (key, cursor) = MacosNativeWindowHost.resolveCursorForResizeEdge(edge)
            assertTrue(key.startsWith("resize:"))
            assertNotNull(cursor)
        }

        val start = NSMakeRect(200.0, 200.0, 800.0, 450.0)
        MacosNativeWindowHost.computeResizedWindowFrame(
            startFrame = start,
            edge = MacosNativeWindowHost.UndecoratedResizeEdge.N,
            deltaScreenX = 0.0,
            deltaScreenY = 50.0,
            minSize = Size(320, 180),
            maxSize = Size(1600, 900)
        ).useContents {
            assertEquals(200.0, origin.y, 0.1)
            assertEquals(500.0, size.height, 0.1)
        }
        MacosNativeWindowHost.computeResizedWindowFrame(
            startFrame = start,
            edge = MacosNativeWindowHost.UndecoratedResizeEdge.S,
            deltaScreenX = 0.0,
            deltaScreenY = -50.0,
            minSize = Size(320, 180)
        ).useContents {
            assertEquals(150.0, origin.y, 0.1)
            assertEquals(500.0, size.height, 0.1)
            assertEquals(650.0, origin.y + size.height, 0.1)
        }
        MacosNativeWindowHost.computeResizedWindowFrame(
            startFrame = start,
            edge = MacosNativeWindowHost.UndecoratedResizeEdge.W,
            deltaScreenX = -160.0,
            deltaScreenY = 0.0,
            minSize = Size(320, 180),
            maxSize = Size(1280, 720),
            aspectRatio = 16.0 / 9.0
        ).useContents {
            assertEquals(960.0, size.width, 1.0)
            assertEquals(540.0, size.height, 1.0)
            assertEquals(1000.0, origin.x + size.width, 1.0)
        }
        MacosNativeWindowHost.computeResizedWindowFrame(
            startFrame = start,
            edge = MacosNativeWindowHost.UndecoratedResizeEdge.NW,
            deltaScreenX = 700.0,
            deltaScreenY = -400.0,
            minSize = Size(320, 200)
        ).useContents {
            assertEquals(320.0, size.width, 0.1)
            assertEquals(200.0, size.height, 0.1)
            assertEquals(1000.0, origin.x + size.width, 0.1)
            assertEquals(200.0, origin.y, 0.1)
        }
    }

    @Test
    fun testStyledSliderProximalRelativeZIndexPointerEventsAndBaseSlider4KCap() = withCleanMacosTestEnvironment(interactiveGui = true) { factory ->
        // 1. StyledSlider must mount strictly via general StyledContainer + StyledComponent + StyledButton
        val styledWindow = Window(title = "StyledSlider Test", initialSize = Size(800, 120))
        val styledSlider = StyledSlider(min = 0.0, max = 100.0, value = 50.0, step = 1.0).apply {
            style.setWidthPx(400)
            style.setHeightPx(28)
        }
        val sliderContainer = Container().apply {
            style.setWidthPx(800)
            style.setHeightPx(120)
            addChild(styledSlider)
        }
        styledWindow.addChild(sliderContainer)
        styledWindow.show(factory)
        try {
            MacosNativeWindowHost.flushPendingDisplay()
            val sliderImpl = styledSlider.ensureNativeImpl()
            assertTrue(sliderImpl is MacosStyledContainerNativeImpl, "StyledSlider Composite must use MacosStyledContainerNativeImpl")
            val sliderWidget = (sliderImpl as DesktopBaseNativeImpl<*>).nativeWidget
            val leafTypes = sliderWidget.children.mapNotNull { it.attributes["nativeImpl"] }
            assertTrue(
                leafTypes.any { it is DesktopStyledComponentNativeImpl<*> },
                "StyledSlider must use DesktopStyledComponentNativeImpl for visual bar segments"
            )
            val sliderView = assertNotNull((sliderWidget as MacosViewHandle).nsView as? MacosStyledContainerView)
            assertTrue(
                sliderView.subviews.any { it is MacosStyledComponentView },
                "StyledSlider must mount retained MacosStyledComponentView track/fill subviews"
            )
        } finally {
            styledWindow.close()
            styledWindow.dispose()
        }

        // 2. General proximal position: relative ancestor, static wrapper bypass, 4px height,
        //    signed % CssTransform, 50% circular border-radius, z-index paint/hit ordering, pointer-events: none, & cursor: none
        val layoutWindow = Window(title = "Proximal Relative Layout", initialSize = Size(800, 120))
        val viewportRoot = Container().apply {
            style.setPosition(CssPosition.STATIC)
            style.setWidthPx(800)
            style.setHeightPx(120)
        }
        val relAncestor = Container().apply {
            style.setPosition(CssPosition.RELATIVE)
            style.setMarginLeft(CssLength.px(100))
            style.setMarginTop(CssLength.px(20))
            style.setWidthPx(400)
            style.setHeightPx(60)
            style.setCursor(CssCursor.POINTER)
        }
        val staticMiddle = Container().apply {
            style.setPosition(CssPosition.STATIC)
            style.setWidthPx(400)
            style.setHeightPx(60)
        }
        var trackClicks = 0
        var knobClicks = 0
        val trackBtn = StyledButton(text = "").apply {
            onClick = { trackClicks++ }
            style.apply {
                setPosition(CssPosition.ABSOLUTE)
                setLeft(CssLength.px(0))
                setTop(CssLength.percent(50f))
                setWidthPx(400)
                setHeightPx(4)
                setTransform(CssTransform.parse("translate(0px, -50%)")!!)
                setZIndex(1)
                setBackgroundColor(RgbaColor(40, 40, 40, 255))
            }
        }
        val progressLeaf = StyledComponent().apply {
            style.apply {
                setPosition(CssPosition.ABSOLUTE)
                setLeft(CssLength.px(0))
                setTop(CssLength.percent(50f))
                setWidthPx(200)
                setHeightPx(4)
                setTransform(CssTransform.parse("translate(0px, -50%)")!!)
                setZIndex(3)
                setPointerEvents(CssPointerEvents.NONE)
                setCursor(CssCursor.NONE)
                setBackgroundColor(RgbaColor(240, 30, 30, 255))
            }
        }
        val knobBtn = StyledButton(text = "").apply {
            onClick = { knobClicks++ }
            style.apply {
                setPosition(CssPosition.ABSOLUTE)
                setLeft(CssLength.percent(50f))
                setTop(CssLength.percent(50f))
                setWidthPx(14)
                setHeightPx(14)
                setBorderRadius(CssCornerRadii.percent(50))
                setTransform(CssTransform.parse("translate(-50%, -50%)")!!)
                setZIndex(4)
                setBackgroundColor(RgbaColor(255, 255, 255, 255))
            }
        }
        staticMiddle.addChild(knobBtn)
        staticMiddle.addChild(progressLeaf)
        staticMiddle.addChild(trackBtn)
        relAncestor.addChild(staticMiddle)
        viewportRoot.addChild(relAncestor)
        layoutWindow.addChild(viewportRoot)

        layoutWindow.show(factory)
        try {
            val winImpl = layoutWindow.nativeImpl as MacosWindowNativeImpl
            MacosNativeWindowHost.flushPendingDisplay()
            val rootView = assertNotNull((winImpl.nativeWidget as MacosViewHandle).nsView as? MacosWindowRootView)

            val trackWidget = (trackBtn.ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget as MacosViewHandle
            val progressWidget = (progressLeaf.ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget as MacosViewHandle
            val knobWidget = (knobBtn.ensureNativeImpl() as DesktopBaseNativeImpl<*>).nativeWidget as MacosViewHandle

            val trackView = assertNotNull(trackWidget.nsView)
            val progressView = assertNotNull(progressWidget.nsView)
            val knobView = assertNotNull(knobWidget.nsView)

            val trackInRoot = trackView.convertRect(trackView.bounds, toView = rootView)
            trackInRoot.useContents {
                assertEquals(100.0, origin.x, 1.0, "position: absolute must resolve against proximal position: relative ancestor at x=100")
                assertEquals(500.0, origin.x + size.width, 1.0)
                assertEquals(4.0, size.height, 0.5, "Track must keep exact 4px CSS height without minimum clamping")
                assertEquals(48.0, origin.y, 1.0, "top: 50% + translateY(-50%) inside 60px relative ancestor at y=20 -> y=48..52")
            }

            val ptInStatic = rootView.convertPoint(NSMakePoint(150.0, 50.0), toView = progressView.superview!!)
            assertNull(progressView.hitTest(ptInStatic), "StyledComponent with pointer-events: none must return null in hitTest")
            assertEquals("none", progressWidget.attributes["cursor"], "StyledComponent must preserve cursor: none")

            // Click through pointer-events: none progressLeaf (z-index: 3) -> must hit trackView (z-index: 1)
            val hitThroughProgress = rootView.hitTestClientPoint(150.0, 50.0)
            assertEquals(trackView, hitThroughProgress, "pointer-events: none overlay must click through to track")
            MacosNativeWindowHost.simulateMouseClick(150f, 50f)
            GenericUiThreading.flushAllQueues()
            assertEquals(1, trackClicks)

            // Verify progressLeaf painted red pixels visually at (150, 50) over trackBtn
            val shot = assertNotNull(MacosNativeWindowHost.captureWindowScreenshot(winImpl, 800, 120))
            val off = (50 * shot.width + 150) * 4
            val pxR = shot.bytes[off].toInt() and 0xFF
            val pxG = shot.bytes[off + 1].toInt() and 0xFF
            assertTrue(pxR > 200 && pxG < 80, "StyledComponent progress fill at z-index: 3 must paint over z-index: 1 track (r=$pxR, g=$pxG)")

            // Circular knob (z-index: 4) at centered (293..307, 43..57) must win hit-test over DOM order
            val knobInRoot = knobView.convertRect(knobView.bounds, toView = rootView)
            assertEquals(4, knobWidget.effectiveZIndex())
            knobInRoot.useContents {
                assertTrue(size.width in 13.0..15.0 && size.height in 13.0..15.0, "Knob must keep 14x14 centered bounds")
                assertEquals(293.0, origin.x, 1.0, "left: 50% + translateX(-50%) in 400px relative ancestor at x=100 -> 300 - 7 = 293")
                assertEquals(43.0, origin.y, 1.0, "top: 50% + translateY(-50%) in 60px relative ancestor at y=20 -> 50 - 7 = 43")
            }
            assertEquals(knobView, rootView.hitTestClientPoint(300.0, 50.0), "Higher z-index knob must win hit-test even if inserted earlier in DOM")
            MacosNativeWindowHost.simulateMouseClick(300f, 50f)
            GenericUiThreading.flushAllQueues()
            assertEquals(1, knobClicks)
        } finally {
            layoutWindow.close()
            layoutWindow.dispose()
        }

        // 3. Base RangeSlider supports up to 4K width & natural AppKit NSSlider height
        val rangeWin = Window(title = "4K Slider", initialSize = Size(3840, 48))
        val baseRange = RangeSlider(minValue = 25.0, maxValue = 75.0, min = 0.0, max = 100.0).apply {
            getStyle().apply {
                setWidth(CssLength.px(3840))
                setHeight(CssLength.px(22))
            }
        }
        rangeWin.addChild(baseRange)
        rangeWin.show(factory)
        try {
            val rangeImpl = baseRange.ensureNativeImpl()!!
            val winImpl = rangeWin.nativeImpl as MacosWindowNativeImpl
            MacosNativeWindowHost.captureWindowScreenshot(winImpl, 3840, 48)
            val sliderView = (rangeImpl.nativeWidget as MacosViewHandle).nsView as? NSSlider
            assertNotNull(sliderView, "Base RangeSlider must mount genuine NSSlider")
            val rangeBaseImpl = rangeImpl as DesktopBaseNativeImpl<*>
            assertEquals(3840, rangeBaseImpl.nativeWidget.bounds.width)
            val intrinsic = rangeBaseImpl.measureIntrinsicSize(null, null)
            assertTrue(intrinsic.height in 16..24, "Base RangeSlider natural height should be in 16..24, got ${intrinsic.height}")
        } finally {
            rangeWin.close()
            rangeWin.dispose()
        }

        assertFalse(MacosTabContainerNativeImpl(MacosViewHandle("NSTabView")).supportsNativeFadeTransition)
    }

    @Test
    fun testRetina2560x96BackdropBlurPerformanceOpacityAndLiveCapture() = withCleanMacosTestEnvironment(interactiveGui = false) { factory ->
        // 1. Live retained StyledContainer backdrop-filter screenshot capture over sharp red/blue split
        val backdropWindow = Window(title = "Backdrop Blur Capture", initialSize = Size(640, 60))
        val layerRoot = LayeredContainer()
        val splitBackdrop = Container().apply {
            getStyle().apply {
                setDisplay(CssDisplay.FLEX)
                setFlexDirection(FlexDirection.ROW)
                setWidth(CssLength.px(640))
                setHeight(CssLength.px(60))
            }
            addChild(StyledContainer().apply {
                backgroundColor = RgbaColor(230, 30, 30, 255)
                getStyle().apply {
                    setWidth(CssLength.px(320))
                    setHeight(CssLength.px(60))
                }
            })
            addChild(StyledContainer().apply {
                backgroundColor = RgbaColor(30, 30, 230, 255)
                getStyle().apply {
                    setWidth(CssLength.px(320))
                    setHeight(CssLength.px(60))
                }
            })
        }
        val blurGlass = StyledContainer().apply {
            borderRadiusPx = 10f
            backdropFilter = CssFilter(blur = CssLength.px(14f))
            getStyle().apply {
                setWidth(CssLength.px(640))
                setHeight(CssLength.px(48))
            }
        }
        layerRoot.addFillLayer(splitBackdrop, zIndex = 0)
        layerRoot.addLayer(blurGlass, LayerConstraint.float(offsetXPx = 0, offsetYPx = 0, zIndex = 5))
        backdropWindow.setContent(layerRoot)
        backdropWindow.show(factory)
        try {
            val winImpl = backdropWindow.nativeImpl as MacosWindowNativeImpl
            val shot = assertNotNull(MacosNativeWindowHost.captureWindowScreenshot(winImpl, 640, 60))
            val seamOff = (24 * shot.width + 320) * 4
            val r = shot.bytes[seamOff].toInt() and 0xFF
            val b = shot.bytes[seamOff + 2].toInt() and 0xFF
            val a = shot.bytes[seamOff + 3].toInt() and 0xFF
            assertTrue(a == 255 && r in 60..200 && b in 60..200, "Retained backdrop-filter screenshot seam must blend red & blue (r=$r, b=$b, a=$a)")
        } finally {
            backdropWindow.dispose()
        }

        // 2. Retina 2560x96 (1280x48 @2x) AppleBackdropFilterController benchmark & opacity blending accuracy
        val retinaW = 2560
        val retinaH = 120
        val pixels = IntArray(retinaW * retinaH)
        val filter = CssFilter(blur = CssLength.px(14f))

        fun fillSharpRedBlue() {
            val half = retinaW / 2
            val redPremul = (0xFF shl 24) or (230 shl 16) or (30 shl 8) or 30
            val bluePremul = (0xFF shl 24) or (30 shl 16) or (30 shl 8) or 230
            for (y in 0 until retinaH) {
                val row = y * retinaW
                for (x in 0 until half) pixels[row + x] = redPremul
                for (x in half until retinaW) pixels[row + x] = bluePremul
            }
        }

        fillSharpRedBlue()
        assertTrue(
            AppleBackdropFilterController.applyBackdropToPremulBgraRegion(
                filter = filter,
                pixels = pixels,
                strideWords = retinaW,
                bufferWidth = retinaW,
                bufferHeight = retinaH,
                regionX = 0,
                regionY = 0,
                regionWidth = retinaW,
                regionHeight = 96,
                cornerRadiusPx = 10f,
                componentOpacity = 1.0f
            )
        )
        val seamPx = pixels[48 * retinaW + 1280]
        val seamA = (seamPx ushr 24) and 0xFF
        val seamR = (seamPx ushr 16) and 0xFF
        val seamB = seamPx and 0xFF
        assertTrue(seamA == 255 && seamR in 70..190 && seamB in 70..190, "Seam pixel must blend red & blue (r=$seamR, b=$seamB, a=$seamA)")

        fillSharpRedBlue()
        assertTrue(
            AppleBackdropFilterController.applyBackdropToPremulBgraRegion(
                filter = filter,
                pixels = pixels,
                strideWords = retinaW,
                bufferWidth = retinaW,
                bufferHeight = retinaH,
                regionX = 0,
                regionY = 0,
                regionWidth = retinaW,
                regionHeight = 96,
                cornerRadiusPx = 10f,
                componentOpacity = 0.5f
            )
        )
        val halfLeft = pixels[48 * retinaW + 1272]
        val halfR = (halfLeft ushr 16) and 0xFF
        val halfB = halfLeft and 0xFF
        assertTrue(halfR > halfB, "At 0.5f layerOpacity left of seam stays red-dominant blended ($halfR vs $halfB)")

        // Benchmark 10 steady-state Retina 2560x96 backdrop blur passes
        val startSec = CACurrentMediaTime()
        for (i in 0 until 10) {
            AppleBackdropFilterController.applyBackdropToPremulBgraRegion(
                filter = filter,
                pixels = pixels,
                strideWords = retinaW,
                bufferWidth = retinaW,
                bufferHeight = retinaH,
                regionX = 0,
                regionY = 0,
                regionWidth = retinaW,
                regionHeight = 96,
                cornerRadiusPx = 10f,
                componentOpacity = if (i % 2 == 0) 1.0f else 0.5f
            )
        }
        val perCallMs = ((CACurrentMediaTime() - startSec) * 1000.0) / 10.0
        assertTrue(perCallMs < 2.0, "Retina 2560x96 backdrop blur must take < 2.0ms per call, took ${perCallMs}ms")
    }

    @Test
    fun testMacosAnimationNativeImplNsAnimationDisplayLinkAndNonSnappingHudOpacity() = withCleanMacosTestEnvironment(interactiveGui = true) { factory ->
        runOnMainThreadSync {
            val scheduler = factory.createAnimationScheduler() as MacosAnimationNativeImpl
            try {
                val hz = scheduler.getRefreshRateHz()
                assertTrue(hz in 24.0..240.0)

                val progressSamples = mutableListOf<Float>()
                var completed = false
                var completionCancelled: Boolean? = null
                val animHandle = scheduler.startAnimation(
                    spec = AnimationSpec(durationMs = 80, delayMs = 0, timingFunction = CssTimingFunction.EASE_OUT),
                    target = null,
                    onUpdate = { p -> progressSamples.add(p) },
                    onComplete = { c ->
                        completed = true
                        completionCancelled = c
                    }
                )
                val endWait = CACurrentMediaTime() + 1.0
                while (!completed && CACurrentMediaTime() < endWait) {
                    CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.01, false)
                }
                assertTrue(completed, "NSAnimation must invoke completion callback cleanly")
                assertEquals(false, completionCancelled)
                assertFalse(animHandle.isRunning)
                assertEquals(0, scheduler.activeNsAnimationCount)
                assertTrue(progressSamples.size >= 2, "Expected multiple NSAnimation progress ticks")
                assertEquals(1f, progressSamples.last(), 0.02f)

                var cancelledCallback: Boolean? = null
                val cancelHandle = scheduler.startAnimation(
                    spec = AnimationSpec(durationMs = 400, delayMs = 0, timingFunction = CssTimingFunction.LINEAR),
                    target = null,
                    onUpdate = {},
                    onComplete = { c -> cancelledCallback = c }
                )
                assertTrue(cancelHandle.isRunning)
                cancelHandle.cancel()
                assertEquals(true, cancelledCallback)
                assertFalse(cancelHandle.isRunning)
                assertEquals(0, scheduler.activeNsAnimationCount)

                assertFalse(scheduler.isDisplayLinkActive)
                var vblankOnMainThread = false
                var vblankTicks = 0
                val watchId = scheduler.watchVBlank { _, _, _ ->
                    vblankTicks++
                    if (NSThread.isMainThread) {
                        vblankOnMainThread = true
                    }
                }
                assertTrue(scheduler.isDisplayLinkActive, "watchVBlank must activate CADisplayLink / VBlank source")
                val vblankDeadline = CACurrentMediaTime() + 0.5
                while (vblankTicks == 0 && CACurrentMediaTime() < vblankDeadline) {
                    CFRunLoopRunInMode(kCFRunLoopDefaultMode, 0.01, false)
                }
                assertTrue(vblankTicks >= 1, "Main-thread CADisplayLink VBlank watcher must deliver frame ticks")
                assertTrue(vblankOnMainThread, "CADisplayLink VBlank callback must execute on the AppKit main thread")
                scheduler.unwatchVBlank(watchId)
                assertFalse(scheduler.isDisplayLinkActive, "unwatchVBlank must park CADisplayLink at 0% idle CPU")
                MacosNativeWindowHost.syncHostVBlankWatcher()
            } finally {
                scheduler.dispose()
            }
        }

        // 4. Retained AppKit HUD controls-layer opacity stays interactive mid-fade even when isOverlayVisible == false
        runOnMainThreadSync {
            val stageContainer = LayeredContainer().apply {
                setAttribute("data-overlay-host", "true")
            }
            val overlayRoot = LayeredContainer().apply {
                setAttribute("data-overlay-root", "true")
            }
            val controlsLayer = Container().apply {
                setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_CONTROLS_LAYER)
                opacity = 0.45f
                getStyle().apply { setWidth(CssLength.px(320)); setHeight(CssLength.px(180)) }
            }
            val bottomBar = Container().apply {
                setAttribute(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE, DesktopStageOverlaySpec.ROLE_BOTTOM_BAR)
                getStyle().apply { setWidth(CssLength.px(320)); setHeight(CssLength.px(44)) }
            }
            var playClicks = 0
            val playBtn = StyledButton("Play").apply {
                getStyle().apply { setWidth(CssLength.px(80)); setHeight(CssLength.px(32)) }
                onClick = { playClicks++ }
            }
            bottomBar.addChild(playBtn)
            controlsLayer.addChild(bottomBar)
            overlayRoot.addFillLayer(controlsLayer, zIndex = 1)
            stageContainer.addFillLayer(overlayRoot, zIndex = 5)

            val hudWin = Window(title = "HUD Fade Opacity", initialSize = Size(320, 180))
            hudWin.setContent(stageContainer)
            hudWin.show(factory)
            try {
                val overlayNative = overlayRoot.ensureNativeImpl()!!.nativeWidget as DesktopNativeWidget
                DesktopStageOverlaySpec(isOverlayVisible = { false }).attachTo(overlayNative)
                MacosNativeWindowHost.flushPendingDisplay()
                val rootView = assertNotNull((hudWin.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosWindowRootView)
                val controlsView = (controlsLayer.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView!!
                val playView = (playBtn.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView!!

                assertEquals(0.45, controlsView.alphaValue, 0.01)
                val (px, py) = centerInRoot(rootView, playView)
                assertEquals(
                    playView,
                    rootView.hitTestClientPoint(px, py),
                    "HUD must stay interactive mid-fade while controlsLayer.opacity=0.45f even after specVisible becomes false"
                )
                MacosNativeWindowHost.simulateMouseClick(px.toFloat(), py.toFloat())
                GenericUiThreading.flushAllQueues()
                assertEquals(1, playClicks)

                // Once fade completes to 0f, AppKit alphaValue <= 0.004 drops hit interception
                controlsLayer.opacity = 0f
                GenericUiThreading.flushAllQueues()
                assertEquals(0.0, controlsView.alphaValue, 0.01, "controlsView.alphaValue must drop to 0.0 (nativeWidget.opacity=${(controlsLayer.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).opacity})")
                val ptInOverlayRoot = rootView.convertPoint(NSMakePoint(px, py), toView = controlsView.superview!!)
                assertNull(controlsView.hitTest(ptInOverlayRoot))
                assertTrue(rootView.hitTestClientPoint(px, py) != playView)
            } finally {
                hudWin.close()
                hudWin.dispose()
            }
        }
    }

    @Test
    fun macosTitleBarShowWindowIconAndNonTintedTabIconPreserved() {
        val factory = MacosNativeFactory()
        GenericUI.initialize(factory)

        runOnMainThreadSync {
            val win = Window(title = "No Titlebar Icon", initialSize = Size(480, 300))
            win.setTitleBar(TitleBar(customTitleBar = true, showWindowIcon = false))
            win.show(factory)
            try {
                val winImpl = win.nativeImpl as MacosWindowNativeImpl
                assertEquals(false, winImpl.nativeWidget.attributes["showWindowIcon"])
                val nsWin = MacosNativeWindowHost.windowSessionForTest(winImpl)?.nsWindow
                nsWin?.standardWindowButton(platform.AppKit.NSWindowDocumentIconButton)?.let { docBtn ->
                    assertTrue(docBtn.isHidden())
                }
            } finally {
                win.close()
                win.dispose()
            }
        }

        val tabImpl = MacosTabNativeImpl(MacosViewHandle("Tab"), initialClosable = false)
        tabImpl.setTintImageToThemeNative(false)
        tabImpl.setClosableNative(true)
        assertFalse(tabImpl.tintImageToThemeVal)
        assertTrue(tabImpl.closableVal)
        tabImpl.dispose()
    }
}
