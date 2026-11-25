@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.action.DropdownButton
import com.nexustools.genericui.component.action.SplitDropdownButton
import com.nexustools.genericui.component.action.StyledButton
import com.nexustools.genericui.component.content.Table
import com.nexustools.genericui.component.content.TableCell
import com.nexustools.genericui.component.content.TableColumn
import com.nexustools.genericui.component.content.TableRow
import com.nexustools.genericui.component.dock.DockPanel
import com.nexustools.genericui.component.form.ColorPicker
import com.nexustools.genericui.component.form.ComboBox
import com.nexustools.genericui.component.form.DatePicker
import com.nexustools.genericui.component.form.NumberField
import com.nexustools.genericui.component.form.SearchField
import com.nexustools.genericui.component.form.Select
import com.nexustools.genericui.component.form.SelectOption
import com.nexustools.genericui.component.form.StyledSlider
import com.nexustools.genericui.component.form.TextField
import com.nexustools.genericui.event.PointerDragManager
import com.nexustools.genericui.component.form.TimePicker
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.HStack
import com.nexustools.genericui.component.layout.LayerConstraint
import com.nexustools.genericui.component.layout.LayeredContainer
import com.nexustools.genericui.component.layout.StyledContainer
import com.nexustools.genericui.component.lazy.LazyList
import com.nexustools.genericui.component.lazy.TreeNode
import com.nexustools.genericui.component.lazy.TreeView
import com.nexustools.genericui.component.overlay.Popover
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.surface.Card
import com.nexustools.genericui.component.tabs.Tab
import com.nexustools.genericui.component.tabs.TabContainer
import com.nexustools.genericui.component.typography.Badge
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.AbstractComponent
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.impl.MacosDockPanelView
import com.nexustools.genericui.macos.impl.MacosSplitDropdownView
import com.nexustools.genericui.macos.impl.MacosTextNativeImpl
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.css.CssStyleSheet
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.useContents
import platform.AppKit.NSButton
import platform.AppKit.NSColorWell
import platform.AppKit.NSComboBox
import platform.AppKit.NSDatePicker
import platform.AppKit.NSOutlineView
import platform.AppKit.NSPopUpButton
import platform.AppKit.NSScrollView
import platform.AppKit.NSSearchField
import platform.AppKit.NSSecureTextField
import platform.AppKit.NSTableView
import platform.AppKit.NSTextField
import platform.Foundation.NSMakePoint
import platform.Foundation.NSThread
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_sync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacosRetainedAppKitViewsTest {

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

    private inline fun withMacosFactory(crossinline block: (MacosNativeFactory) -> Unit) {
        val factory = MacosNativeFactory()
        val prevFactory = GenericUI.activeFactory
        val prevApp = Application.current
        val prevInteractive = MacosApplicationNativeImpl.interactiveMacosGuiEnabled
        val prevVisual = MacosNativeWindowHost.visualTestMode
        Application.current = null
        MacosApplicationNativeImpl.interactiveMacosGuiEnabled = false
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

    private inline fun withAttachedWindow(
        factory: MacosNativeFactory,
        vararg components: AbstractComponent,
        block: (Window) -> Unit
    ) {
        val win = Window(title = "Retained AppKit View Host", initialSize = Size(480, 320))
        val root = Container()
        for (c in components) {
            root.addChild(c)
        }
        win.setContent(root)
        win.show(factory)
        try {
            block(win)
        } finally {
            win.close()
            win.dispose()
        }
    }

    @Test
    fun buttonSwapsBetweenGenuineNsButtonAndBootstrapControl() = withMacosFactory { factory ->
        val root = Container()
        val button = Button("Save")
        root.addChild(button)
        val win = Window(title = "Button Swap", initialSize = Size(320, 120))
        win.setContent(root)
        win.show(factory)
        try {
            val btnImpl = button.ensureNativeImpl() as MacosButtonNativeImpl
            val btnHandle = btnImpl.nativeWidget
            assertTrue(btnImpl.isNativeDefaultButtonMode())
            assertTrue(btnHandle.nsView is NSButton, "Default Button must mount unmodified AppKit NSButton")
            val rootView = (root.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView!!
            assertEquals(rootView, btnHandle.nsView!!.superview)

            button.type = BootstrapType.PRIMARY
            assertFalse(btnImpl.isNativeDefaultButtonMode())
            val primaryBtn = btnHandle.nsView as? NSButton
            assertNotNull(primaryBtn, "Typed Button must keep genuine NSButton")
            assertNotNull(primaryBtn.bezelColor, "Filled PRIMARY Button must apply native NSButton.bezelColor")
            assertEquals(rootView, primaryBtn.superview)

            button.type = BootstrapType.NONE
            button.outline = false
            assertTrue(btnImpl.isNativeDefaultButtonMode())
            val resetBtn = btnHandle.nsView as? NSButton
            assertNotNull(resetBtn, "Resetting Button type must keep genuine NSButton")
            assertNull(resetBtn.bezelColor, "Default Button(NONE) must clear bezelColor")
            assertNull(resetBtn.contentTintColor, "Default Button(NONE) must clear contentTintColor")
            assertEquals(rootView, resetBtn.superview)
        } finally {
            win.close()
            win.dispose()
        }
    }

    @Test
    fun dropdownMountsNsPopUpButtonAndSplitDropdownFiresPrimaryAction() = withMacosFactory { factory ->
        val dropdown = DropdownButton("Actions")
        dropdown.setItems(listOf("open" to "Open", "save" to "Save"))

        var primaryClicks = 0
        val split = SplitDropdownButton("Build") { primaryClicks++ }
        split.setItems(listOf("clean" to "Clean Build"))

        withAttachedWindow(factory, dropdown, split) {
            val dropHandle = dropdown.ensureNativeImpl()!!.nativeWidget as MacosViewHandle
            val pop = assertNotNull(dropHandle.nsView as? NSPopUpButton, "DropdownButton must mount NSPopUpButton")
            assertTrue(pop.pullsDown)

            val splitHandle = split.ensureNativeImpl()!!.nativeWidget as MacosViewHandle
            val splitView = assertNotNull(splitHandle.nsView as? MacosSplitDropdownView, "SplitDropdownButton must mount split NSButton + NSPopUpButton")
            splitView.primaryButton.performClick(null)
            assertEquals(1, primaryClicks)
        }
    }

    @Test
    fun selectSwapsBetweenNsPopUpButtonAndNsComboBox() = withMacosFactory { factory ->
        val select = Select()
        select.setOptions(listOf(SelectOption("a", "Alpha"), SelectOption("b", "Beta")))
        val combo = ComboBox()
        combo.setOptions(listOf(SelectOption("1", "One"), SelectOption("2", "Two")))

        withAttachedWindow(factory, select, combo) {
            val handle = select.ensureNativeImpl()!!.nativeWidget as MacosViewHandle
            assertTrue(handle.nsView is NSPopUpButton, "Select must mount NSPopUpButton")

            select.setAttribute("data-control-variant", "combobox")
            assertTrue(handle.nsView is NSComboBox, "Combobox variant must swap cleanly to NSComboBox")

            val comboHandle = combo.ensureNativeImpl()!!.nativeWidget as MacosViewHandle
            assertTrue(comboHandle.nsView is NSComboBox, "ComboBox component must mount NSComboBox")
        }
    }

    @Test
    fun formInputVariantsMountAppropriateAppKitControls() = withMacosFactory { factory ->
        val datePicker = DatePicker()
        val timePicker = TimePicker()
        val colorPicker = ColorPicker()
        val searchField = SearchField()
        val numberField = NumberField()
        val passwordField = TextField(passwordMode = true)
        val multilineField = TextField(multilineMode = true)
        val textField = TextField()

        withAttachedWindow(
            factory,
            datePicker,
            timePicker,
            colorPicker,
            searchField,
            numberField,
            passwordField,
            multilineField,
            textField
        ) {
            assertTrue((datePicker.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView is NSDatePicker)
            assertTrue((timePicker.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView is NSDatePicker)
            assertTrue((colorPicker.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView is NSColorWell)
            assertTrue((searchField.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView is NSSearchField)
            assertNotNull((numberField.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView)
            assertTrue((passwordField.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView is NSSecureTextField)
            assertTrue((multilineField.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView is NSScrollView)
            assertTrue((textField.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView is NSTextField)
        }
    }

    @Test
    fun semanticContainersResolveSurfacesWhilePlainContainersStayTransparent() = withMacosFactory { factory ->
        val plain = HStack()
        val card = Card().apply { type = BootstrapType.INFO }
        val badgeText = Text("NEW", paragraph = false, classPrefix = "badge bg-primary rounded-pill")
        val badgeComponent = Badge("BETA", type = BootstrapType.INFO, pill = true)

        withAttachedWindow(factory, plain, card, badgeText, badgeComponent) {
            val plainImpl = plain.ensureNativeImpl() as MacosContainerNativeImpl
            assertNull(plainImpl.resolveSemanticSurfaceSpec(darkTheme = true))
            val plainView = (plainImpl.nativeWidget as MacosViewHandle).nsView as MacosContainerView
            assertNull(plainView.surfaceSupport.activeSurfaceSpec.backgroundColor)

            val cardImpl = card.ensureNativeImpl() as MacosContainerNativeImpl
            assertNotNull(cardImpl.resolveSemanticSurfaceSpec(darkTheme = true))

            val badgeTextImpl = badgeText.ensureNativeImpl() as MacosTextNativeImpl
            assertTrue(badgeTextImpl.isBadgeRole())
            assertTrue((badgeTextImpl.nativeWidget as MacosViewHandle).nsView is com.nexustools.genericui.macos.impl.MacosBadgeView)

            val badgeCompImpl = badgeComponent.ensureNativeImpl() as MacosTextNativeImpl
            assertTrue(badgeCompImpl.isBadgeRole())
            assertTrue((badgeCompImpl.nativeWidget as MacosViewHandle).nsView is com.nexustools.genericui.macos.impl.MacosBadgeView)
        }
    }

    @Test
    fun pointerEventsNoneOverlayPassesClicksToChildAndRendersScreenshot() = withMacosFactory { factory ->
        MacosApplicationNativeImpl.interactiveMacosGuiEnabled = true
        val window = Window(title = "Retained AppKit", initialSize = Size(400, 260))

        var childClicks = 0
        val rootLayer = LayeredContainer()
        val backdropBg = StyledContainer().apply {
            backgroundColor = RgbaColor(45, 95, 210, 255)
        }
        val glassOverlay = StyledContainer().apply {
            pointerEventsEnabled = false
            backgroundColor = RgbaColor(20, 24, 34, 140)
            borderColor = RgbaColor(255, 255, 255, 90)
            borderWidthPx = 1f
            borderRadiusPx = 12f
            backdropFilter = CssFilter(
                blur = CssLength.px(6f),
                saturate = 1.4f,
                brightness = 1.1f
            )
        }
        val innerButton = StyledButton("Tap").apply {
            getStyle().setWidth(CssLength.px(120f))
            getStyle().setHeight(CssLength.px(44f))
            onClick = { childClicks++ }
        }
        glassOverlay.addChild(innerButton)
        rootLayer.addFillLayer(backdropBg, zIndex = 0)
        rootLayer.addLayer(
            glassOverlay,
            LayerConstraint.fill(zIndex = 10, passThroughPointerEvents = true)
        )

        window.setContent(rootLayer)
        window.show(factory)

        val winImpl = window.ensureNativeImpl() as MacosWindowNativeImpl
        MacosNativeWindowHost.flushPendingDisplay()

        val rootView = assertNotNull((winImpl.nativeWidget as MacosViewHandle).nsView as? MacosWindowRootView)
        // Click on the glassOverlay empty area -> must NOT match glassOverlay directly because pointerEventsEnabled = false.
        val glassView = (glassOverlay.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView!!
        val hitOnEmptyGlass = rootView.hitTestClientPoint(340.0, 210.0)
        assertNotEquals(glassView, hitOnEmptyGlass, "pointer-events: none container must not intercept background hits")

        // Child StyledButton inside pointer-events: none container MUST still be clickable!
        val btnView = (innerButton.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView!!
        val btnCenterInRoot = btnView.bounds.useContents {
            rootView.convertPoint(
                NSMakePoint(origin.x + size.width * 0.5, origin.y + size.height * 0.5),
                fromView = btnView
            )
        }
        val (hitX, hitY) = btnCenterInRoot.useContents { x to y }
        val hitOnChild = rootView.hitTestClientPoint(hitX, hitY)
        assertEquals(btnView, hitOnChild, "Interactive child inside pointer-events: none container must hit cleanly at ($hitX, $hitY)")
        MacosNativeWindowHost.simulateMouseClick(hitX.toFloat(), hitY.toFloat())
        GenericUiThreading.flushAllQueues()
        assertEquals(1, childClicks)

        val screenshot = MacosNativeWindowHost.captureWindowScreenshot(winImpl, 400, 260)
        assertNotNull(screenshot, "Offscreen AppKit screenshot capture must succeed")
        assertEquals(400, screenshot.width)
        assertEquals(260, screenshot.height)
    }

    @Test
    fun lazyListTableTreeViewTabContainerDockPanelAndPopoverMountGenuineAppKitViews() = withMacosFactory { factory ->
        val lazy = LazyList()
        val table = Table().apply {
            setColumns(listOf(TableColumn("name", "Name"), TableColumn("role", "Role")))
            setRows(
                listOf(
                    TableRow("r1", listOf(TableCell("Ada"), TableCell("Engineer"))),
                    TableRow("r2", listOf(TableCell("Grace"), TableCell("Admiral")))
                )
            )
        }
        val tree = TreeView<String>().apply {
            setRootNodes(listOf(TreeNode("r1", "Root", children = mutableListOf(TreeNode("c1", "Child")))))
        }
        val tabs = TabContainer().apply {
            addTab(Tab("Overview"), Text("Overview body"))
            addTab(Tab("Settings"), Text("Settings body"))
        }
        val dockPanel = DockPanel("inspector", "Inspector")
        val anchor = Button("Anchor")
        val popover = Popover(title = "Details").apply {
            addChild(Text("Hello Popover"))
        }

        withAttachedWindow(factory, lazy, table, tree, tabs, dockPanel, anchor) {
            val lazyScroll = (lazy.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? NSScrollView
            assertNotNull(lazyScroll)

            val tableScroll = (table.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? NSScrollView
            assertNotNull(tableScroll)
            assertTrue(tableScroll.documentView is NSTableView)

            val treeScroll = (tree.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? NSScrollView
            assertNotNull(treeScroll)
            assertTrue(treeScroll.documentView is NSOutlineView)

            val tabsView = (tabs.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView
            assertNotNull(tabsView)
            assertTrue(tabsView.subviews.size >= 2)

            val dockView = (dockPanel.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView as? MacosDockPanelView
            assertNotNull(dockView)
            assertEquals("Inspector", dockView.titleField.stringValue)

            anchor.ensureNativeImpl()
            popover.showAnchored(anchor)
            assertTrue(popover.isVisible)
            popover.hide()
            assertFalse(popover.isVisible)
        }
    }

    @Test
    fun windowShouldCloseRespectsVetoAndNeverDoubleClosesOrCrashesOffMainThread() = withMacosFactory { factory ->
        MacosApplicationNativeImpl.interactiveMacosGuiEnabled = true
        val rootVeto = Container().apply { addChild(Text("Veto Test")) }
        val winVeto = Window(title = "Veto Window", initialSize = Size(320, 200))
        var allowClose = false
        winVeto.setOnWindowCloseRequested { allowClose }
        winVeto.setContent(rootVeto)
        winVeto.show(factory)

        val implVeto = winVeto.ensureNativeImpl() as MacosWindowNativeImpl
        val keyVeto = implVeto.nativeWidget.nativePointer
        assertFalse(MacosNativeWindowHost.onNativeWindowShouldClose(keyVeto, isDialog = false))
        assertFalse(implVeto.isDisposed, "Vetoed window close must keep window alive")
        assertNotNull(MacosNativeWindowHost.activeNsWindow, "Vetoed window session must remain registered")

        allowClose = true
        assertFalse(
            MacosNativeWindowHost.onNativeWindowShouldClose(keyVeto, isDialog = false),
            "onNativeWindowShouldClose must close session cleanly and return false to prevent AppKit double-close"
        )
        assertTrue(implVeto.isDisposed, "Allowed window close must dispose window cleanly")

        // Repeated close or off-main-thread syncWindowSize / view dispose must safely defer without throwing or trapping
        val root2 = Container().apply {
            val childBtn = Button("Child")
            addChild(childBtn)
        }
        val win2 = Window(title = "OffMain Dispose", initialSize = Size(360, 220))
        win2.setContent(root2)
        win2.show(factory)
        val impl2 = win2.ensureNativeImpl() as MacosWindowNativeImpl

        val group = platform.darwin.dispatch_group_create()
        val bgQueue = platform.darwin.dispatch_get_global_queue(0L, 0uL)
        platform.darwin.dispatch_group_async(group, bgQueue) {
            impl2.setWindowSize(Size(420, 260))
            root2.dispose()
            win2.dispose()
        }
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        while (platform.darwin.dispatch_group_wait(group, platform.darwin.DISPATCH_TIME_NOW) != 0L &&
            mark.elapsedNow().inWholeMilliseconds < 2000L
        ) {
            MacosNativeWindowHost.pumpVisualWindowEvents(16L)
        }
        MacosNativeWindowHost.pumpVisualWindowEvents(32L)
        assertTrue(impl2.isDisposed, "Off-main-thread window/subtree dispose must complete cleanly")
    }

    @Test
    fun autoSizedPaddedStyledContainerMeasuresWithoutChromeDoubleCounting() = withMacosFactory { factory ->
        MacosApplicationNativeImpl.interactiveMacosGuiEnabled = true
        val innerBox = StyledContainer().apply {
            style.setWidth(CssLength.px(32f))
            style.setHeight(CssLength.px(32f))
        }
        val pill = StyledContainer().apply {
            style.setDisplay(com.nexustools.genericui.enums.css.CssDisplay.INLINE_FLEX)
            style.setFlexDirection(com.nexustools.genericui.enums.bootstrap.FlexDirection.ROW)
            style.setHeight(CssLength.px(40f))
            style.setPadding(
                com.nexustools.genericui.style.css.CssEdgeLengths(
                    top = CssLength.px(3f),
                    right = CssLength.px(4f),
                    bottom = CssLength.px(3f),
                    left = CssLength.px(4f)
                )
            )
            style.setBorderWidthPx(1f)
            addChild(innerBox)
        }
        val sibling = StyledContainer().apply {
            style.setWidth(CssLength.px(60f))
            style.setHeight(CssLength.px(40f))
        }
        val row = HStack(gapPx = 8).apply {
            addChild(pill)
            addChild(sibling)
        }
        val root = Container().apply { addChild(row) }
        val win = Window(title = "Chrome Measure", initialSize = Size(320, 120))
        win.setContent(root)
        win.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(64L)

        val pillBounds = pill.getBounds()
        val siblingBounds = sibling.getBounds()
        assertEquals(42, pillBounds.width, "Padded 1px-border 32px child pill must measure 42px (not 52px double-chrome)")
        assertEquals(40, pillBounds.height)
        assertEquals(
            pillBounds.right + 8,
            siblingBounds.x,
            "HStack(gap=8) must place sibling 8px right of pill ($pillBounds -> $siblingBounds)"
        )
        win.dispose()
    }

    @Test
    fun childZIndexOrdersSubviewsAndCoreAnimationLayers() = withMacosFactory { factory ->
        MacosApplicationNativeImpl.interactiveMacosGuiEnabled = true
        val track = StyledContainer().apply {
            style.setPosition(com.nexustools.genericui.enums.css.CssPosition.ABSOLUTE)
            style.setLeft(CssLength.px(0f))
            style.setTop(CssLength.px(0f))
            style.setWidth(CssLength.px(100f))
            style.setHeight(CssLength.px(10f))
            style.setZIndex(1)
        }
        val progress = StyledContainer().apply {
            style.setPosition(com.nexustools.genericui.enums.css.CssPosition.ABSOLUTE)
            style.setLeft(CssLength.px(0f))
            style.setTop(CssLength.px(0f))
            style.setWidth(CssLength.px(40f))
            style.setHeight(CssLength.px(10f))
            style.setZIndex(3)
        }
        val knob = StyledContainer().apply {
            style.setPosition(com.nexustools.genericui.enums.css.CssPosition.ABSOLUTE)
            style.setLeft(CssLength.px(35f))
            style.setTop(CssLength.px(0f))
            style.setWidth(CssLength.px(10f))
            style.setHeight(CssLength.px(10f))
            style.setZIndex(4)
        }
        // Appended last intentionally (mirroring SeekBar.preloadSegment) with z-index=2
        val preload = StyledContainer().apply {
            style.setPosition(com.nexustools.genericui.enums.css.CssPosition.ABSOLUTE)
            style.setLeft(CssLength.px(0f))
            style.setTop(CssLength.px(0f))
            style.setWidth(CssLength.px(80f))
            style.setHeight(CssLength.px(10f))
            style.setZIndex(2)
        }
        val sliderHost = StyledContainer().apply {
            style.setPosition(com.nexustools.genericui.enums.css.CssPosition.RELATIVE)
            style.setWidth(CssLength.px(100f))
            style.setHeight(CssLength.px(10f))
            addChild(track)
            addChild(progress)
            addChild(knob)
            addChild(preload)
        }
        val root = Container().apply { addChild(sliderHost) }
        val win = Window(title = "ZIndex Order", initialSize = Size(180, 80))
        win.setContent(root)
        win.show(factory)
        MacosNativeWindowHost.pumpVisualWindowEvents(64L)

        val hostView = (sliderHost.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView!!
        val trackView = (track.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView!!
        val preloadView = (preload.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView!!
        val progressView = (progress.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView!!
        val knobView = (knob.ensureNativeImpl()!!.nativeWidget as MacosViewHandle).nsView!!

        val subviews = hostView.subviews
        val idxTrack = subviews.indexOf(trackView)
        val idxPreload = subviews.indexOf(preloadView)
        val idxProgress = subviews.indexOf(progressView)
        val idxKnob = subviews.indexOf(knobView)
        assertTrue(
            idxTrack in 0 until idxPreload && idxPreload < idxProgress && idxProgress < idxKnob,
            "Subviews must be ordered track($idxTrack) < preload($idxPreload) < progress($idxProgress) < knob($idxKnob)"
        )
        assertTrue(
            (trackView.layer?.zPosition ?: -999.0) < (preloadView.layer?.zPosition ?: -999.0) &&
                (preloadView.layer?.zPosition ?: -999.0) < (progressView.layer?.zPosition ?: -999.0) &&
                (progressView.layer?.zPosition ?: -999.0) < (knobView.layer?.zPosition ?: -999.0),
            "CALayer.zPosition must strictly increase track < preload < progress < knob"
        )
        win.dispose()
    }

    @Test
    fun styledSliderKnobDragsViaPointerDragManagerAndUsesPointerCursor() = withMacosFactory { factory ->
        MacosApplicationNativeImpl.interactiveMacosGuiEnabled = true
        val slider = StyledSlider(min = 0.0, max = 100.0, value = 100.0, step = 1.0)
        val css = CssStyleSheet.parse(
            """
            .styled-slider {
                position: relative;
                width: 200px;
                height: 24px;
                cursor: pointer;
            }
            .styled-slider-track {
                position: absolute;
                top: 50%;
                left: 0;
                right: 0;
                height: 4px;
                transform: translate(0, -50%);
                z-index: 1;
                cursor: pointer;
            }
            .styled-slider-progress {
                position: absolute;
                top: 50%;
                left: 0;
                height: 4px;
                transform: translate(0, -50%);
                pointer-events: none;
                z-index: 3;
            }
            .styled-slider-knob {
                position: absolute;
                top: 50%;
                width: 14px;
                height: 14px;
                transform: translate(-50%, -50%);
                z-index: 4;
                cursor: pointer;
            }
            """.trimIndent()
        )
        val root = Container().apply {
            style.setPadding(com.nexustools.genericui.style.css.CssEdgeLengths.px(24f))
            addChild(slider)
        }
        val win = Window(title = "StyledSlider Knob Drag", initialSize = Size(300, 100))
        win.setContent(root)
        win.show(factory)
        root.applyStyleSheet(css)
        MacosNativeWindowHost.pumpVisualWindowEvents(64L)
        GenericUiThreading.flushAllQueues()

        val sliderImpl = slider.ensureNativeImpl() as MacosStyledContainerNativeImpl
        val sliderBounds = slider.getBounds()
        assertEquals(200, sliderBounds.width)
        assertEquals("pointer", sliderImpl.nativeWidget.attributes["cursor"])

        val knobHandle = sliderImpl.nativeWidget.children[2] as MacosViewHandle
        val knobView = knobHandle.nsView as? MacosStyledButtonControl
        assertNotNull(knobView, "Knob must render with MacosStyledButtonControl")
        assertEquals("pointer", knobHandle.attributes["cursor"])
        assertEquals(
            "pointer" to platform.AppKit.NSCursor.pointingHandCursor,
            MacosCursorTranslator.resolveCursor(knobHandle.attributes["cursor"] as? String)
        )

        val rootView = MacosNativeWindowHost.activeNsWindow!!.contentView as MacosWindowRootView
        // At 100%, the right half of knobButton overflows sliderBounds.right — hitTest MUST still hit knobView!
        val knobRightEdgeX = sliderBounds.right + 4.0
        val knobMidY = sliderBounds.y + sliderBounds.height / 2.0
        val hitRight = rootView.hitTestClientPoint(knobRightEdgeX, knobMidY)
        assertTrue(
            hitRight === knobView || hitRight === knobView.foregroundView || hitRight?.superview === knobView,
            "Overflowing right edge of 100% knob ($knobRightEdgeX, $knobMidY) must hit knobView ($hitRight vs $knobView)"
        )

        // Drag knob from 100% left by 100px -> ~50% via PointerDragManager
        MacosNativeWindowHost.simulateMouseDrag(
            fromX = (sliderBounds.right + 3).toFloat(),
            fromY = knobMidY.toFloat(),
            toX = (sliderBounds.right + 3 - 100).toFloat(),
            toY = knobMidY.toFloat(),
            steps = 5
        )
        GenericUiThreading.flushAllQueues()
        assertTrue(
            slider.value in 44.0..56.0,
            "Dragging 100% knob left by 100px across 200px slider must update value to ~50.0 (actual=${slider.value})"
        )
        assertFalse(slider.isScrubbing, "StyledSlider must end scrubbing on mouseUp")
        assertFalse(PointerDragManager.isDragging, "PointerDragManager must clear active drag on mouseUp")

        // Move to 0%, verify overflowing left edge hits knobView and drags smoothly back to ~50%
        slider.value = 0.0
        GenericUiThreading.flushAllQueues()
        MacosNativeWindowHost.pumpVisualWindowEvents(32L)
        val knobLeftEdgeX = sliderBounds.x - 4.0
        val hitLeft = rootView.hitTestClientPoint(knobLeftEdgeX, knobMidY)
        assertTrue(
            hitLeft === knobView || hitLeft === knobView.foregroundView || hitLeft?.superview === knobView,
            "Overflowing left edge of 0% knob ($knobLeftEdgeX, $knobMidY) must hit knobView ($hitLeft vs $knobView)"
        )

        MacosNativeWindowHost.simulateMouseDrag(
            fromX = (sliderBounds.x - 3).toFloat(),
            fromY = knobMidY.toFloat(),
            toX = (sliderBounds.x - 3 + 100).toFloat(),
            toY = knobMidY.toFloat(),
            steps = 5
        )
        GenericUiThreading.flushAllQueues()
        assertTrue(
            slider.value in 44.0..56.0,
            "Dragging 0% knob right by 100px across 200px slider must update value to ~50.0 (actual=${slider.value})"
        )
        assertFalse(slider.isScrubbing)
        assertFalse(PointerDragManager.isDragging)
        win.dispose()
    }
}
