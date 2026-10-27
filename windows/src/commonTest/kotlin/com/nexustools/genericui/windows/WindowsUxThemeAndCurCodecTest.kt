@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsUxThemeAndCurCodecTest {

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

        // Verify 6-byte ICONDIR header: idReserved = 0, idType = 2 (.cur), idCount = 1
        assertEquals(0, (curBytes[0].toInt() and 0xFF) or ((curBytes[1].toInt() and 0xFF) shl 8))
        assertEquals(2, (curBytes[2].toInt() and 0xFF) or ((curBytes[3].toInt() and 0xFF) shl 8))
        assertEquals(1, (curBytes[4].toInt() and 0xFF) or ((curBytes[5].toInt() and 0xFF) shl 8))

        assertEquals(7 to 9, WindowsCurCodec.parseCurHotspot(curBytes))
        assertEquals(24 to 24, WindowsCurCodec.parseCurDimensions(curBytes))

        // Verify SVG custom cursor rasterization + .cur encoding
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
        val window = Window("Test Window").apply { show() }

        val button = Button("Delete Item")
        button.getBootstrap().apply {
            type = BootstrapType.DANGER
            width = BootstrapWidth.W_100
            display = DisplayMode.FLEX
            paddingX = SpacingScale.S4
            marginX = SpacingScale.AUTO
        }
        window.addChild(button)

        val impl = button.ensureNativeImpl() as DesktopBaseNativeImpl<*>

        // ComponentBootstrap enum classes MUST be 100% invisible to getClasses(), getClass(), and hasClass()
        assertTrue(impl.getClasses().isEmpty())
        assertEquals("", impl.getClass())
        assertFalse(impl.hasClass("btn-danger"))
        assertFalse(impl.hasClass("w-100"))
        assertFalse(impl.hasClass("d-flex"))
        assertFalse(impl.hasClass("px-4"))
        assertFalse(impl.hasClass("mx-auto"))

        // Internal enum class set still tracks the Bootstrap utilities for layout/rendering
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

        // Adding a user CSS class exposes ONLY the user CSS class
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
            val window = Window("Test Window").apply { show() }

            val unstyledBtn = Button("System Default")
            window.addChild(unstyledBtn)
            val unstyledImpl = unstyledBtn.ensureNativeImpl() as DesktopBaseNativeImpl<*>
            assertTrue(Win32NativeWindowHost.usesNativeSystemControlRendering(unstyledImpl.nativeWidget))
            assertEquals("Explorer::BUTTON", Win32NativeWindowHost.resolveButtonThemeClassList(darkTheme = false))
            assertEquals("DarkMode_Explorer::BUTTON", Win32NativeWindowHost.resolveButtonThemeClassList(darkTheme = true))

            val dangerBtn = Button("Danger Action").apply {
                setType(BootstrapType.DANGER)
            }
            window.addChild(dangerBtn)
            val dangerImpl = dangerBtn.ensureNativeImpl() as DesktopBaseNativeImpl<*>
            assertFalse(Win32NativeWindowHost.usesNativeSystemControlRendering(dangerImpl.nativeWidget))
            assertEquals(
                BootstrapColorPalette.resolveColor(BootstrapType.DANGER, darkMode = false),
                Win32NativeWindowHost.resolveControlBackgroundColor(dangerImpl.nativeWidget, darkTheme = false)
            )
            assertEquals(
                BootstrapColorPalette.resolveColor(BootstrapType.DANGER, darkMode = true),
                Win32NativeWindowHost.resolveControlBackgroundColor(dangerImpl.nativeWidget, darkTheme = true)
            )
            assertEquals(
                BootstrapColorPalette.resolveForegroundOn(BootstrapType.DANGER),
                Win32NativeWindowHost.resolveControlForegroundColor(dangerImpl.nativeWidget, darkTheme = false)
            )

            // Style override takes precedence over BootstrapType
            val customBg = RgbaColor(18, 140, 95)
            dangerBtn.getStyle().apply {
                backgroundColor = customBg
                paddingLeft = CssLength.px(18)
            }
            assertEquals(
                customBg,
                Win32NativeWindowHost.resolveControlBackgroundColor(dangerImpl.nativeWidget, darkTheme = false)
            )
            assertEquals(18, dangerImpl.getEffectivePaddingInsets().left)

            // ThemeMode.AUTO detection
            val app = Application()
            app.run(factory)
            val appImpl = app.appNativeImpl as WindowsApplicationNativeImpl
            SystemThemeDetector.overrideDetectedTheme = ThemeMode.DARK
            app.setTheme(ThemeMode.AUTO)
            assertEquals(ThemeMode.AUTO, app.getTheme())
            assertEquals(ThemeMode.DARK, app.getResolvedTheme())
            assertEquals(true, appImpl.nativeApplication.attributes["dwmUseImmersiveDarkMode"])
            assertTrue(Win32NativeWindowHost.isWindowsSystemDarkTheme())

            SystemThemeDetector.overrideDetectedTheme = ThemeMode.LIGHT
            app.setTheme(ThemeMode.AUTO)
            assertEquals(ThemeMode.LIGHT, app.getResolvedTheme())
            assertEquals(false, appImpl.nativeApplication.attributes["dwmUseImmersiveDarkMode"])
            assertFalse(Win32NativeWindowHost.isWindowsSystemDarkTheme())
        } finally {
            SystemThemeDetector.overrideDetectedTheme = prevOverride
            Win32NativeWindowHost.forcedThemeMode = prevForced
        }
    }
}
