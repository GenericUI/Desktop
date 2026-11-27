@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.action.DropdownButton
import com.nexustools.genericui.component.action.SplitDropdownButton
import com.nexustools.genericui.component.action.styled.StyledButton
import com.nexustools.genericui.component.action.ToggleButton
import com.nexustools.genericui.component.feedback.ProgressBar
import com.nexustools.genericui.component.form.CheckBox
import com.nexustools.genericui.component.form.RangeSlider
import com.nexustools.genericui.component.form.Select
import com.nexustools.genericui.component.form.SelectOption
import com.nexustools.genericui.component.form.TextField
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.ScrollView
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.tabs.Tab
import com.nexustools.genericui.component.tabs.TabContainer
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.BootstrapWidth
import com.nexustools.genericui.enums.bootstrap.DisplayMode
import com.nexustools.genericui.enums.bootstrap.SpacingScale
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCustomCursor
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.theme.SystemThemeDetector
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.windows.BS_AUTOCHECKBOX
import platform.windows.BS_PUSHBUTTON
import platform.windows.BS_SPLITBUTTON
import platform.windows.GWL_STYLE
import platform.windows.GetClassNameW
import platform.windows.GetWindowLongW
import platform.windows.HWND
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsUxThemeAndCurCodecTest {

    private fun readWin32ClassName(hwnd: HWND): String = memScoped {
        val buf = allocArray<UShortVar>(128)
        GetClassNameW(hwnd, buf, 128)
        buf.toKString()
    }

    @Test
    fun testWindowsCurCodecEncodesImageBufferAndSvgWithExactHotspotAndIdType2() {
        val image = ImageBuffer(24, 24)
        image.fill(RgbaColor.TRANSPARENT)
        image.setPixel(7, 9, RgbaColor(220, 53, 69, 255))

        val imageCursor = CssCustomCursor.fromImageBuffer(
            imageBuffer = image,
            hotspotX = 7,
            hotspotY = 9,
            fallback = CssCursor.CROSSHAIR
        )
        val curBytes = WindowsCurCodec.encodeToCurBytes(imageCursor)
        assertNotNull(curBytes)
        assertTrue(WindowsCurCodec.isValidCurBinary(curBytes))

        assertEquals(0, (curBytes[0].toInt() and 0xFF) or ((curBytes[1].toInt() and 0xFF) shl 8))
        assertEquals(2, (curBytes[2].toInt() and 0xFF) or ((curBytes[3].toInt() and 0xFF) shl 8))
        assertEquals(1, (curBytes[4].toInt() and 0xFF) or ((curBytes[5].toInt() and 0xFF) shl 8))

        assertEquals(7 to 9, WindowsCurCodec.parseCurHotspot(curBytes))
        assertEquals(24 to 24, WindowsCurCodec.parseCurDimensions(curBytes))

        val svgCursor = CssCustomCursor.fromSvg(
            svgMarkup = """<svg viewBox="0 0 32 32"><circle cx="16" cy="16" r="10" fill="#0d6efd"/></svg>""",
            widthPx = 32,
            heightPx = 32,
            hotspotX = 16,
            hotspotY = 16,
            fallback = CssCursor.POINTER
        )
        val svgCurBytes = WindowsCurCodec.encodeToCurBytes(svgCursor)
        assertNotNull(svgCurBytes)
        assertTrue(WindowsCurCodec.isValidCurBinary(svgCurBytes))
        assertEquals(16 to 16, WindowsCurCodec.parseCurHotspot(svgCurBytes))
        assertEquals(32 to 32, WindowsCurCodec.parseCurDimensions(svgCurBytes))
    }

    @Test
    fun testDesktopBaseNativeImplUserCssClassIsolationHidesBootstrapEnumClasses() {
        GenericUI.initialize(WindowsNativeFactory())
        val window = Window("Test Window")

        val button = Button("Delete Item")
        button.getBootstrap().apply {
            type = BootstrapType.DANGER
            width = BootstrapWidth.W_100
            display = DisplayMode.FLEX
            paddingX = SpacingScale.S4
            marginX = SpacingScale.AUTO
        }
        window.addChild(button)
        window.show()

        val impl = button.ensureNativeImpl() as DesktopBaseNativeImpl<*>
        assertTrue(impl.getClasses().isEmpty())
        assertEquals("", impl.getClass())
        assertFalse(impl.hasClass("btn-danger"))
        assertFalse(impl.hasClass("w-100"))
        assertFalse(impl.hasClass("d-flex"))
        assertFalse(impl.hasClass("px-4"))
        assertFalse(impl.hasClass("mx-auto"))

        val internalEnumClasses = impl.getEnumClasses()
        assertTrue(internalEnumClasses.contains("btn-danger"))
        assertTrue(internalEnumClasses.contains("w-100"))
        assertTrue(internalEnumClasses.contains("d-flex"))
        assertTrue(internalEnumClasses.contains("px-4"))
        assertTrue(internalEnumClasses.contains("mx-auto"))
        assertEquals(1.0f, impl.resolveBootstrapWidthFraction())
        assertEquals(true to true, impl.isHorizontalMarginAuto())
        assertEquals(SpacingScale.S4.toPixels(), impl.getEffectivePaddingInsets().left)
        assertEquals(SpacingScale.S4.toPixels(), impl.getEffectivePaddingInsets().right)

        button.addClass("my-custom-action")
        assertEquals(setOf("my-custom-action"), impl.getClasses())
        assertEquals("my-custom-action", impl.getClass())
        assertTrue(impl.hasClass("my-custom-action"))
        assertFalse(impl.hasClass("btn-danger"))
        assertFalse(impl.hasClass("w-100"))
    }

    @Test
    fun testUnstyledNativeControlVsBootstrapTypeSemanticTintingAndThemeAuto() {
        val prevOverride = SystemThemeDetector.overrideDetectedTheme
        val prevForced = Win32NativeWindowHost.forcedThemeMode
        try {
            val factory = WindowsNativeFactory()
            GenericUI.initialize(factory)
            val window = Window("Test Window")

            val unstyledBtn = Button("System Default")
            window.addChild(unstyledBtn)
            window.show()
            val unstyledImpl = unstyledBtn.ensureNativeImpl() as DesktopButtonNativeImpl<*>
            assertTrue(unstyledImpl.isNativeDefaultButtonMode())
            val unstyledHwnd = assertNotNull((unstyledImpl.nativeWidget as Win32HwndHandle).hwnd)
            assertEquals("Button", readWin32ClassName(unstyledHwnd))
            assertEquals(BS_PUSHBUTTON, GetWindowLongW(unstyledHwnd, GWL_STYLE) and 0x0F)

            val dangerBtn = Button("Danger Action").apply {
                setType(BootstrapType.DANGER)
            }
            window.addChild(dangerBtn)
            val dangerImpl = dangerBtn.ensureNativeImpl() as DesktopButtonNativeImpl<*>
            assertFalse(dangerImpl.isNativeDefaultButtonMode())
            val dangerHwnd = assertNotNull((dangerImpl.nativeWidget as Win32HwndHandle).hwnd)
            assertEquals("Button", readWin32ClassName(dangerHwnd))

            val lightSpec = Win32StyledWidgetHelper.resolveBootstrapButtonVisualSpec(dangerImpl, isDark = false)
            val darkSpec = Win32StyledWidgetHelper.resolveBootstrapButtonVisualSpec(dangerImpl, isDark = true)
            assertEquals(BootstrapColorPalette.resolveColor(BootstrapType.DANGER, darkMode = false), lightSpec.backgroundColor)
            assertEquals(BootstrapColorPalette.resolveColor(BootstrapType.DANGER, darkMode = true), darkSpec.backgroundColor)
            assertEquals(BootstrapColorPalette.resolveForegroundOn(BootstrapType.DANGER), lightSpec.textColor)

            // Standard Button ignores inline aesthetic backgroundColor override while StyledButton obeys it
            val customBg = RgbaColor(18, 140, 95)
            dangerBtn.getStyle().apply {
                backgroundColor = customBg
                paddingLeft = CssLength.px(18)
            }
            assertEquals(
                BootstrapColorPalette.resolveColor(BootstrapType.DANGER, darkMode = false),
                Win32StyledWidgetHelper.resolveBootstrapButtonVisualSpec(dangerImpl, isDark = false).backgroundColor
            )
            assertEquals(18, dangerImpl.getEffectivePaddingInsets().left)

            val styledDangerBtn = StyledButton("Styled Danger").apply {
                setType(BootstrapType.DANGER)
            }
            window.addChild(styledDangerBtn)
            val styledDangerImpl = styledDangerBtn.ensureNativeImpl() as DesktopBaseNativeImpl<*>
            val styledHandle = styledDangerImpl.nativeWidget as Win32HwndHandle
            assertEquals("GenericUIStyledButtonW", readWin32ClassName(assertNotNull(styledHandle.hwnd)))
            styledDangerBtn.getStyle().apply {
                backgroundColor = customBg
                paddingLeft = CssLength.px(18)
            }
            assertEquals(
                customBg,
                Win32StyledWidgetHelper.resolveEffectiveVisualSpec(
                    styledHandle,
                    styledDangerImpl,
                    "GenericUIStyledButtonW",
                    isDark = false
                ).backgroundColor
            )
            assertEquals(18, styledDangerImpl.getEffectivePaddingInsets().left)

            // Button-only BootstrapType rule: ToggleButton uses genuine "Button" (WC_BUTTON) with NM_CUSTOMDRAW
            // tinting when DANGER is set, while non-Button native controls retain genuine unmodified Win32 / ComCtl32 HWND classes.
            val toggleBtn = ToggleButton("Toggle", selected = true, type = BootstrapType.DANGER)
            val dropdownBtn = DropdownButton("Menu").apply { setType(BootstrapType.PRIMARY) }
            val splitDropdownBtn = SplitDropdownButton("Split").apply { setType(BootstrapType.SUCCESS) }
            val progressDanger = ProgressBar(progress = 0.5f).apply { setType(BootstrapType.DANGER) }
            val checkboxDanger = CheckBox("Check", checked = true).apply { setType(BootstrapType.DANGER) }
            val sliderDanger = RangeSlider(minValue = 50.0, maxValue = 50.0).apply { setType(BootstrapType.DANGER) }
            val selectDanger = Select(listOf(SelectOption("a", "A")), "a").apply { setType(BootstrapType.DANGER) }
            val tabsDanger = TabContainer().apply {
                setType(BootstrapType.DANGER)
                addTab(Tab(title = "T1"), Text("Body"))
            }
            val scrollDanger = ScrollView().apply { setType(BootstrapType.DANGER) }
            val textFieldDanger = TextField("Hello").apply { setType(BootstrapType.DANGER) }
            val containerDanger = Container().apply { setType(BootstrapType.DANGER) }

            listOf(
                toggleBtn,
                dropdownBtn,
                splitDropdownBtn,
                progressDanger,
                checkboxDanger,
                sliderDanger,
                selectDanger,
                tabsDanger,
                scrollDanger,
                textFieldDanger,
                containerDanger
            ).forEach { window.addChild(it) }

            val toggleImpl = toggleBtn.ensureNativeImpl() as DesktopButtonNativeImpl<*>
            assertFalse(toggleImpl.isNativeDefaultButtonMode())
            assertEquals("Button", readWin32ClassName(assertNotNull((toggleImpl.nativeWidget as Win32HwndHandle).hwnd)))

            val ddHwnd = assertNotNull((dropdownBtn.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle).hwnd)
            assertEquals("Button", readWin32ClassName(ddHwnd))
            assertEquals(BS_SPLITBUTTON, GetWindowLongW(ddHwnd, GWL_STYLE) and 0x0F)

            val splitHwnd = assertNotNull((splitDropdownBtn.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle).hwnd)
            assertEquals("Button", readWin32ClassName(splitHwnd))
            assertEquals(BS_SPLITBUTTON, GetWindowLongW(splitHwnd, GWL_STYLE) and 0x0F)

            assertEquals("msctls_progress32", readWin32ClassName(assertNotNull((progressDanger.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle).hwnd)))
            val chkHwnd = assertNotNull((checkboxDanger.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle).hwnd)
            assertEquals("Button", readWin32ClassName(chkHwnd))
            assertEquals(BS_AUTOCHECKBOX, GetWindowLongW(chkHwnd, GWL_STYLE) and 0x0F)
            assertEquals("msctls_trackbar32", readWin32ClassName(assertNotNull((sliderDanger.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle).hwnd)))
            assertEquals("ComboBox", readWin32ClassName(assertNotNull((selectDanger.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle).hwnd)))
            assertEquals("SysTabControl32", readWin32ClassName(assertNotNull((tabsDanger.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle).hwnd)))
            assertEquals("GenericUIScrollViewW", readWin32ClassName(assertNotNull((scrollDanger.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle).hwnd)))
            assertEquals("Edit", readWin32ClassName(assertNotNull((textFieldDanger.ensureNativeImpl()!!.nativeWidget as Win32HwndHandle).hwnd)))

            // ThemeMode.AUTO detection
            val app = Application(Container("div"))
            app.run(factory)
            val appImpl = app.appNativeImpl as WindowsApplicationNativeImpl
            SystemThemeDetector.overrideDetectedTheme = ThemeMode.DARK
            app.setThemeMode(ThemeMode.AUTO)
            assertEquals(ThemeMode.AUTO, app.getThemeMode())
            assertEquals(ThemeMode.DARK, app.getResolvedTheme())
            assertEquals(true, appImpl.nativeApplication.attributes["dwmUseImmersiveDarkMode"])
            assertTrue(Win32NativeWindowHost.isWindowsSystemDarkTheme())

            SystemThemeDetector.overrideDetectedTheme = ThemeMode.LIGHT
            app.setThemeMode(ThemeMode.AUTO)
            assertEquals(ThemeMode.LIGHT, app.getResolvedTheme())
            assertEquals(false, appImpl.nativeApplication.attributes["dwmUseImmersiveDarkMode"])
            assertFalse(Win32NativeWindowHost.isWindowsSystemDarkTheme())
        } finally {
            SystemThemeDetector.overrideDetectedTheme = prevOverride
            Win32NativeWindowHost.forcedThemeMode = prevForced
        }
    }
}
