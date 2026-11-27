@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.action.styled.StyledButton
import com.nexustools.genericui.component.form.CheckBox
import com.nexustools.genericui.component.form.RangeSlider
import com.nexustools.genericui.component.form.Select
import com.nexustools.genericui.component.form.SelectOption
import com.nexustools.genericui.component.form.TextField
import com.nexustools.genericui.component.form.styled.StyledTextArea
import com.nexustools.genericui.component.form.styled.StyledTextField
import com.nexustools.genericui.component.layout.HStack
import com.nexustools.genericui.component.layout.Spacer
import com.nexustools.genericui.component.layout.VStack
import com.nexustools.genericui.component.layout.styled.StyledContainer
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.typography.Label
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledTextInputNativeImpl
import com.nexustools.genericui.enums.bootstrap.AlignItems
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCornerRadii
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.window.TitleBar
import com.nexustools.genericui.windows.impl.WindowsButtonNativeImpl
import com.nexustools.genericui.windows.impl.WindowsCheckboxNativeImpl
import com.nexustools.genericui.windows.impl.WindowsRangeSliderNativeImpl
import com.nexustools.genericui.windows.impl.WindowsSelectNativeImpl
import com.nexustools.genericui.windows.impl.WindowsStyledButtonNativeImpl
import com.nexustools.genericui.windows.impl.WindowsStyledContainerNativeImpl
import com.nexustools.genericui.windows.impl.WindowsStyledTextInputNativeImpl
import com.nexustools.genericui.windows.impl.WindowsTextInputNativeImpl
import com.nexustools.genericui.windows.impl.WindowsTextNativeImpl
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.windows.ClientToScreen
import platform.windows.ES_MULTILINE
import platform.windows.GetClassNameW
import platform.windows.GetFocus
import platform.windows.GetWindowLongW
import platform.windows.GWL_STYLE
import platform.windows.HTCAPTION
import platform.windows.HTCLIENT
import platform.windows.HTTRANSPARENT
import platform.windows.HWND
import platform.windows.MK_LBUTTON
import platform.windows.POINT
import platform.windows.SendMessageW
import platform.windows.VK_RETURN
import platform.windows.WM_KEYDOWN
import platform.windows.WM_LBUTTONDOWN
import platform.windows.WM_LBUTTONUP
import platform.windows.WM_NCHITTEST
import platform.windows.WS_BORDER
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsStyledTextInputAndLabelTargetTest {

    private inline fun withVisualHost(block: (WindowsNativeFactory) -> Unit) {
        val prevInteractive = WindowsApplicationNativeImpl.interactiveWin32GuiEnabled
        val prevVisualTest = Win32NativeWindowHost.visualTestMode
        WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = true
        Win32NativeWindowHost.visualTestMode = true
        Win32NativeWindowHost.closeAllVisualWindows()
        try {
            val factory = WindowsNativeFactory()
            GenericUI.initialize(factory)
            block(factory)
        } finally {
            Win32NativeWindowHost.closeAllVisualWindows()
            Win32NativeWindowHost.visualTestBannerText = null
            Win32NativeWindowHost.visualTestMode = prevVisualTest
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = prevInteractive
        }
    }

    private fun readWin32ClassName(hwnd: HWND): String = memScoped {
        val buf = allocArray<UShortVar>(128)
        GetClassNameW(hwnd, buf, 128)
        buf.toKString()
    }

    private fun screenHitLParamForChildCenter(hostHwnd: HWND, childHandle: Win32HwndHandle): Long {
        val bounds = childHandle.bounds
        val cx = bounds.x + bounds.width / 2
        val cy = bounds.y + bounds.height / 2
        return screenHitLParamForClientPoint(hostHwnd, cx, cy)
    }

    private fun screenHitLParamForClientPoint(hostHwnd: HWND, clientX: Int, clientY: Int): Long = memScoped {
        val pt = alloc<POINT>()
        pt.x = clientX
        pt.y = clientY
        ClientToScreen(hostHwnd, pt.ptr)
        (((pt.y and 0xFFFF).toLong()) shl 16) or (pt.x and 0xFFFF).toLong()
    }

    @Test
    fun testFactoryCreatesSinglelineAndMultilineStyledTextInputsWithMatchedEditHwnd() = withVisualHost { factory ->
        val singleImpl = factory.createStyledTextInput(multiline = false, classPrefix = "styled-textfield")
        val multiImpl = factory.createStyledTextInput(multiline = true, classPrefix = "styled-textarea")

        assertTrue(singleImpl is WindowsStyledTextInputNativeImpl)
        assertTrue(singleImpl is DesktopStyledTextInputNativeImpl<*>)
        assertTrue(multiImpl is WindowsStyledTextInputNativeImpl)

        val singleOuterHwnd = assertNotNull(singleImpl.nativeWidget.hwnd)
        val singleEditHwnd = assertNotNull(singleImpl.editHandle.hwnd)
        val multiEditHwnd = assertNotNull(multiImpl.editHandle.hwnd)

        assertEquals("GenericUIStyledTextInputW", readWin32ClassName(singleOuterHwnd))
        assertEquals("Edit", readWin32ClassName(singleEditHwnd))
        assertEquals("Edit", readWin32ClassName(multiEditHwnd))

        val singleEditStyle = GetWindowLongW(singleEditHwnd, GWL_STYLE)
        val multiEditStyle = GetWindowLongW(multiEditHwnd, GWL_STYLE)
        assertEquals(0, singleEditStyle and WS_BORDER)
        assertEquals(0, singleEditStyle and ES_MULTILINE)
        assertTrue((multiEditStyle and ES_MULTILINE) != 0)

        val pillBase = StyledVisualSpec(
            backgroundColor = RgbaColor(24, 30, 44, 255),
            textColor = RgbaColor(240, 244, 255, 255),
            placeholderColor = RgbaColor(148, 163, 184, 255),
            borderColor = RgbaColor(72, 108, 168, 255),
            borderWidthPx = 2f,
            borderStyle = CssBorderStyle.SOLID,
            borderRadiusPx = 18f,
            padding = EdgeInsets(6, 14, 6, 14),
            fontFamily = "Segoe UI",
            fontSizePx = 15f,
            fontWeightNumeric = 600
        )
        val hoverSpec = StyledVisualSpec(borderColor = RgbaColor(112, 162, 240, 255))
        val focusSpec = StyledVisualSpec(
            borderColor = RgbaColor(64, 156, 255, 255),
            focusRingColor = RgbaColor(64, 156, 255, 160),
            focusRingWidthPx = 3f
        )

        singleImpl.setBaseVisuals(pillBase)
        singleImpl.setHoverVisuals(hoverSpec)
        singleImpl.setFocusVisuals(focusSpec)
        singleImpl.setPlaceholder("Type URL or search...")
        singleImpl.setBounds(com.nexustools.genericui.geometry.Rect(0, 0, 280, 38))

        assertEquals(RgbaColor(24, 30, 44, 255), singleImpl.resolveCurrentVisuals().backgroundColor)
        assertEquals("Type URL or search...", singleImpl.getPlaceholder())

        val expectedBgRef = Win32DwmUxThemeBridge.colorRefRgb(24, 30, 44)
        val expectedFgRef = Win32DwmUxThemeBridge.colorRefRgb(240, 244, 255)
        assertEquals(expectedBgRef, singleImpl.editHandle.bgColorRef)
        assertEquals(expectedFgRef, singleImpl.editHandle.fgColorRef)

        val editBounds = singleImpl.editHandle.bounds
        assertTrue(editBounds.x >= 14, "Expected inner Edit HWND inset inside pill left padding, got $editBounds")
        assertTrue(editBounds.width in 100..(280 - 28), "Expected inner Edit HWND narrower than outer pill, got $editBounds")
        assertTrue(editBounds.y > 0 && editBounds.y + editBounds.height < 38, "Expected Edit vertically centered inside pill, got $editBounds")

        singleImpl.setHoveredState(true)
        assertEquals(RgbaColor(112, 162, 240, 255), singleImpl.resolveCurrentVisuals().borderColor)

        singleImpl.setFocusedState(true)
        assertEquals(RgbaColor(64, 156, 255, 255), singleImpl.resolveCurrentVisuals().borderColor)
        assertEquals(3f, singleImpl.resolveCurrentVisuals().focusRingWidthPx)

        var submittedValue: String? = null
        singleImpl.setOnSubmit { submittedValue = it }
        singleImpl.setValue("https://example.org")
        assertEquals("https://example.org", Win32RetainedControlSupport.readWindowText(singleEditHwnd))

        SendMessageW(singleEditHwnd, WM_KEYDOWN.toUInt(), VK_RETURN.toULong(), 0L)
        assertEquals("https://example.org", submittedValue)
    }

    @Test
    fun testLabelTargetClickAndControlFocusTransferToRealWin32Hwnds() = withVisualHost { factory ->
        val styledField = StyledTextField(value = "", placeholder = "Enter email").apply {
            backgroundColor = RgbaColor(28, 32, 42, 255)
            textColor = RgbaColor(242, 244, 248, 255)
            placeholderColor = RgbaColor(150, 160, 175, 255)
            borderColor = RgbaColor(80, 110, 160, 255)
            borderWidthPx = 1f
            borderRadiusPx = 16f
            widthPx = 240
            heightPx = 36
        }
        val emailLabel = Label(text = "Email:", target = styledField).apply {
            widthPx = 80
            heightPx = 24
        }
        val styledArea = StyledTextArea(value = "", placeholder = "Notes").apply {
            backgroundColor = RgbaColor(28, 32, 42, 255)
            borderRadiusPx = 12f
            widthPx = 240
            heightPx = 72
        }
        val plainField = TextField(value = "", placeholder = "Plain").apply {
            widthPx = 200
            heightPx = 30
        }
        val plainBtn = Button("Submit").apply { widthPx = 100; heightPx = 30 }
        val styledBtn = StyledButton("Styled").apply { widthPx = 100; heightPx = 30 }
        val selectBox = Select(
            options = listOf(SelectOption("a", "Alpha"), SelectOption("b", "Beta")),
            selectedValue = "a"
        ).apply {
            widthPx = 120
            heightPx = 30
        }
        val checkBox = CheckBox("Agree").apply { widthPx = 120; heightPx = 24 }
        val slider = RangeSlider(minValue = 50.0, maxValue = 50.0, min = 0.0, max = 100.0).apply {
            widthPx = 160
            heightPx = 28
        }

        val root = VStack(gapPx = 8).apply {
            addChild(emailLabel)
            addChild(styledField)
            addChild(styledArea)
            addChild(plainField)
            addChild(plainBtn)
            addChild(styledBtn)
            addChild(selectBox)
            addChild(checkBox)
            addChild(slider)
        }
        val window = Window(title = "Focus Transfer Test", initialSize = Size(480, 480)).also {
            it.setContent(root)
            it.show(factory)
        }
        Win32NativeWindowHost.pumpVisualWindowEvents(20L)

        val labelImpl = emailLabel.ensureNativeImpl() as WindowsTextNativeImpl
        val styledImpl = styledField.ensureNativeImpl() as WindowsStyledTextInputNativeImpl
        val areaImpl = styledArea.ensureNativeImpl() as WindowsStyledTextInputNativeImpl
        val plainInputImpl = plainField.ensureNativeImpl() as WindowsTextInputNativeImpl
        val btnImpl = plainBtn.ensureNativeImpl() as WindowsButtonNativeImpl
        val styledBtnImpl = styledBtn.ensureNativeImpl() as WindowsStyledButtonNativeImpl
        val selectImpl = selectBox.ensureNativeImpl() as WindowsSelectNativeImpl
        val checkImpl = checkBox.ensureNativeImpl() as WindowsCheckboxNativeImpl
        val sliderImpl = slider.ensureNativeImpl() as WindowsRangeSliderNativeImpl

        val labelHwnd = assertNotNull(labelImpl.nativeWidget.hwnd)
        val styledEditHwnd = assertNotNull(styledImpl.editHandle.hwnd)

        // Click on the Static Label HWND (WM_LBUTTONDOWN -> WM_LBUTTONUP) must invoke Label.target focus
        assertFalse(styledField.isFocused)
        SendMessageW(labelHwnd, WM_LBUTTONDOWN.toUInt(), MK_LBUTTON.toULong(), 0L)
        SendMessageW(labelHwnd, WM_LBUTTONUP.toUInt(), 0uL, 0L)
        assertTrue(styledField.isFocused, "Clicking Label(target = styledField) should focus styledField")
        assertEquals(styledEditHwnd, GetFocus(), "Clicking Label must move real Win32 OS focus to inner Edit HWND")

        // Clicking directly on outer rounded pill HWND moves focus to inner Edit HWND
        styledField.isFocused = false
        plainBtn.isFocused = true
        val outerHwnd = assertNotNull(styledImpl.nativeWidget.hwnd)
        SendMessageW(outerHwnd, WM_LBUTTONDOWN.toUInt(), MK_LBUTTON.toULong(), 0L)
        SendMessageW(outerHwnd, WM_LBUTTONUP.toUInt(), 0uL, 0L)
        assertEquals(styledEditHwnd, GetFocus(), "Clicking outer rounded pill must focus inner Edit HWND")

        // All interactive Win32 controls transfer OS focus when setFocusedState(true) is invoked
        areaImpl.setFocusedState(true)
        assertEquals(areaImpl.editHandle.hwnd, GetFocus())

        plainInputImpl.setFocusedState(true)
        assertEquals(plainInputImpl.nativeWidget.hwnd, GetFocus())

        btnImpl.setFocusedState(true)
        assertEquals(btnImpl.nativeWidget.hwnd, GetFocus())

        styledBtnImpl.setFocusedState(true)
        assertEquals(styledBtnImpl.nativeWidget.hwnd, GetFocus())

        selectImpl.setFocusedState(true)
        assertEquals(selectImpl.nativeWidget.hwnd, GetFocus())

        checkImpl.setFocusedState(true)
        assertEquals(checkImpl.nativeWidget.hwnd, GetFocus())

        sliderImpl.setFocusedState(true)
        assertEquals(sliderImpl.nativeWidget.hwnd, GetFocus())

        // Touch `window` cleanly so compiler keeps local ref alive through assertions
        assertNotNull(window.nativeImpl)
    }

    @Test
    fun testChromiumTitleBarStripMountingHitTestingAndConcaveFilletGeometry() = withVisualHost { factory ->
        val activeTabCard = StyledContainer(tagName = "div", classPrefix = "gui-tab-item").apply {
            addClass("gui-tab-item", "gui-tab-item--active", "gui-tab-item--chromium")
            style.backgroundColor = RgbaColor(41, 42, 45, 255)
            style.borderColor = RgbaColor(60, 64, 67, 255)
            style.borderWidthPx = 1f
            style.borderRadius = CssCornerRadii.px(10f, 10f, 0f, 0f)
            widthPx = 156
            heightPx = 34
            setOnClick {}
            addChild(Text("Example").apply { widthPx = 88; heightPx = 20 })
            addChild(Button("×").apply { widthPx = 22; heightPx = 22 })
        }
        val newTabBtn = Button("+").apply {
            addClass("gui-tab-strip-new-tab")
            widthPx = 28
            heightPx = 26
        }
        val searchTabBtn = Button("⌄").apply {
            addClass("gui-tab-strip-search")
            widthPx = 28
            heightPx = 26
        }
        val dragSpacer = Spacer().apply {
            addClass("gui-tab-strip-spacer")
            heightPx = 28
            style.flexGrow = 1f
        }
        val tabStrip = HStack(gapPx = 4).apply {
            addClass("gui-tab-strip", "gui-tab-strip--titlebar")
            alignItems = AlignItems.END
            style.height = CssLength.px(36f)
            addChild(searchTabBtn)
            addChild(activeTabCard)
            addChild(newTabBtn)
            addChild(dragSpacer)
        }
        val trailingHelpBtn = Button("?").apply { widthPx = 28; heightPx = 26 }

        val titleBar = TitleBar(
            customTitleBar = true,
            showWindowTitle = false,
            showWindowIcon = true
        )
            .addLeading(tabStrip)
            .addTrailing(trailingHelpBtn)

        val window = Window(title = "Browser Chrome", initialSize = Size(960, 620)).also {
            it.setTitleBar(titleBar)
            it.setContent(VStack())
            it.show(factory)
        }
        Win32NativeWindowHost.pumpVisualWindowEvents(20L)
        val winImpl = window.ensureNativeImpl() as WindowsWindowNativeImpl
        val hostHwnd = assertNotNull(Win32NativeWindowHost.resolveHwndForWindow(winImpl))
        val titleBarH = Win32NativeWindowHost.getMainWindowTitleBarHeight(winImpl)
        assertTrue(titleBarH >= 32, "Expected custom titlebar height >= 32px, got $titleBarH")

        val stripImpl = tabStrip.ensureNativeImpl() as DesktopBaseNativeImpl<*>
        val stripBounds = stripImpl.getBounds()
        assertTrue(
            stripBounds.width > 500,
            "Titlebar Chromium tab strip must expand across available width instead of clamping at 180px; got $stripBounds"
        )
        assertEquals(
            titleBarH,
            stripBounds.y + stripBounds.height,
            "Titlebar Chromium tab strip must sit flush against top of navbar at y=$titleBarH; got $stripBounds"
        )

        val trailingImpl = trailingHelpBtn.ensureNativeImpl() as DesktopBaseNativeImpl<*>
        val trailingBounds = trailingImpl.getBounds()
        assertTrue(trailingBounds.width in 28..64, "Compact trailing button should stay compact; got $trailingBounds")
        assertTrue(stripBounds.x + stripBounds.width <= trailingBounds.x, "Tab strip must not overlap trailing button")

        // Hit-testing on children inside titlebar tab strip
        val activeTabHandle = activeTabCard.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        val newTabHandle = newTabBtn.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        val searchTabHandle = searchTabBtn.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle
        val spacerHandle = dragSpacer.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle

        assertTrue(Win32NativeWindowHost.isInteractiveTitleBarChildWidget(activeTabHandle))
        assertTrue(Win32NativeWindowHost.isInteractiveTitleBarChildWidget(newTabHandle))
        assertTrue(Win32NativeWindowHost.isInteractiveTitleBarChildWidget(searchTabHandle))
        assertFalse(Win32NativeWindowHost.isInteractiveTitleBarChildWidget(spacerHandle))

        // Verify host WM_NCHITTEST returns HTCLIENT over interactive tab/buttons and HTCAPTION over drag spacer
        val activeTabBounds = activeTabHandle.bounds
        assertTrue(
            activeTabBounds.width >= 80 && activeTabBounds.height >= 20,
            "Active Chromium tab inside titlebar strip must have laid-out bounds; got $activeTabBounds"
        )
        val tabHitLParam = screenHitLParamForClientPoint(
            hostHwnd,
            stripBounds.x + activeTabBounds.x + (activeTabBounds.width / 2).coerceAtLeast(16),
            stripBounds.y + activeTabBounds.y + (activeTabBounds.height / 2).coerceIn(8, 20)
        )
        assertEquals(
            HTCLIENT.toLong(),
            SendMessageW(hostHwnd, WM_NCHITTEST.toUInt(), 0uL, tabHitLParam),
            "Active Chromium tab inside titlebar must hit-test as HTCLIENT"
        )

        val newTabBounds = newTabHandle.bounds
        val plusHitLParam = screenHitLParamForClientPoint(
            hostHwnd,
            stripBounds.x + newTabBounds.x + newTabBounds.width / 2,
            stripBounds.y + newTabBounds.y + newTabBounds.height / 2
        )
        assertEquals(
            HTCLIENT.toLong(),
            SendMessageW(hostHwnd, WM_NCHITTEST.toUInt(), 0uL, plusHitLParam),
            "New tab + button inside titlebar must hit-test as HTCLIENT"
        )

        val spacerBounds = spacerHandle.bounds
        val spacerHitLParam = screenHitLParamForClientPoint(
            hostHwnd,
            stripBounds.x + spacerBounds.x + spacerBounds.width / 2,
            stripBounds.y + spacerBounds.y + (spacerBounds.height / 2).coerceAtLeast(10)
        )
        val spacerHwnd = assertNotNull(spacerHandle.hwnd)
        val spacerScreenX = (spacerHitLParam and 0xFFFFL).toShort().toInt()
        val spacerScreenY = ((spacerHitLParam shr 16) and 0xFFFFL).toShort().toInt()
        assertEquals(
            HTTRANSPARENT.toLong(),
            Win32NativeWindowHost.childHwndNcHitTest(spacerHwnd, spacerScreenX, spacerScreenY),
            "Empty drag spacer child HWND must yield HTTRANSPARENT to parent caption drag"
        )
        assertEquals(
            HTCAPTION.toLong(),
            SendMessageW(hostHwnd, WM_NCHITTEST.toUInt(), 0uL, spacerHitLParam),
            "Empty drag spacer region inside titlebar tab strip must hit-test as HTCAPTION"
        )

        // Verify active Chromium tab per-corner radii + bottom concave fillet ear SDF
        val activeContainerImpl = activeTabCard.ensureNativeImpl() as WindowsStyledContainerNativeImpl
        val spec = Win32StyledWidgetHelper.resolveEffectiveVisualSpec(
            entry = activeTabHandle,
            owner = activeContainerImpl,
            win32Class = activeTabHandle.actualWin32ClassName,
            isDark = true
        )
        val radii = Win32StyledWidgetHelper.resolveCornerRadii(
            entry = activeTabHandle,
            owner = activeContainerImpl,
            spec = spec,
            width = 156,
            height = 34
        )
        assertEquals(10f, radii.topLeft)
        assertEquals(10f, radii.topRight)
        assertEquals(0f, radii.bottomRight)
        assertEquals(0f, radii.bottomLeft)
        assertTrue(radii.chromiumFilletEarPx > 0f, "Expected Chromium active tab concave bottom fillet ears > 0")
        assertTrue(radii.skipBottomBorder, "Expected bottom border skipped where active tab joins navbar")

        val notchOutsideSd = Win32StyledWidgetHelper.chromiumActiveTabSignedDistance(
            px = 1f,
            py = 34f - radii.chromiumFilletEarPx + 1f,
            width = 156f,
            height = 34f,
            radii = radii
        )
        val earTipInsideSd = Win32StyledWidgetHelper.chromiumActiveTabSignedDistance(
            px = radii.chromiumFilletEarPx - 0.5f,
            py = 33.5f,
            width = 156f,
            height = 34f,
            radii = radii
        )
        assertTrue(notchOutsideSd > 0f, "Concave outer notch must remain outside tab fill (sd=$notchOutsideSd)")
        assertTrue(earTipInsideSd < 0f, "Flared bottom ear curve must be inside tab fill (sd=$earTipInsideSd)")
    }
}
