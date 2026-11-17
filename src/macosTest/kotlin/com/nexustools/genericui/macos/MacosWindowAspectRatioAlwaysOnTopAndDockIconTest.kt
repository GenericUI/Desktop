package com.nexustools.genericui.macos

import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageColorSpace
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.res.DrawableResource
import com.nexustools.genericui.resources.ResourceID
import com.nexustools.genericui.resources.ResourceType
import com.nexustools.genericui.window.WindowAspectRatioHelper
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacosWindowAspectRatioAlwaysOnTopAndDockIconTest {

    private var savedAppleIconExplicitFlag: Boolean = false

    @BeforeTest
    fun saveGlobals() {
        savedAppleIconExplicitFlag = GenericUI.appleIconExplicitlyProvided
    }

    @AfterTest
    fun restoreGlobals() {
        GenericUI.appleIconExplicitlyProvided = savedAppleIconExplicitFlag
    }

    @Test
    fun alwaysOnTopTogglesWindowLevelAttributesAndToolModalPrecedence() {
        val impl = MacosWindowNativeImpl()
        assertFalse(impl.isAlwaysOnTop())

        impl.setAlwaysOnTop(true)
        assertTrue(impl.isAlwaysOnTop())
        assertEquals(true, impl.nativeWidget.attributes["alwaysOnTop"])
        assertEquals("NSFloatingWindowLevel", impl.nativeWidget.attributes["level"])

        impl.setAlwaysOnTop(false)
        assertFalse(impl.isAlwaysOnTop())
        assertEquals(false, impl.nativeWidget.attributes["alwaysOnTop"])
        assertEquals("NSNormalWindowLevel", impl.nativeWidget.attributes["level"])

        // Tool-modal owned floating window preserves floating level while alwaysOnTopOfOwner is true
        val owner = MacosWindowNativeImpl()
        impl.configureAsToolModalWindow(
            ownerWindow = owner,
            showInTaskbar = false,
            minimizable = false,
            alwaysOnTopOfOwner = true
        )
        assertEquals("NSFloatingWindowLevel", impl.nativeWidget.attributes["level"])
        impl.setAlwaysOnTop(false)
        assertEquals("NSFloatingWindowLevel", impl.nativeWidget.attributes["level"])
    }

    @Test
    fun aspectRatioEnforcesNormalSizeAndAllowsUnconstrainedZoomedAndFullscreen() {
        val impl = MacosWindowNativeImpl()
        val sixteenByNine = 16.0 / 9.0

        assertNull(impl.getAspectRatio())
        impl.setAspectRatio(sixteenByNine)
        assertEquals(sixteenByNine, impl.getAspectRatio())
        assertEquals(sixteenByNine, impl.nativeWidget.attributes["aspectRatio"])

        impl.setWindowSize(Size(1600, 1000))
        assertEquals(Size(1600, 900), impl.getWindowSize())
        assertTrue(WindowAspectRatioHelper.matchesAspectRatio(impl.getWindowSize(), sixteenByNine, tolerancePx = 2))

        // Native zoom (maximized) reports unrestricted full work-area bounds
        impl.notifyHostResizedFromOs(widthPx = 1920, heightPx = 1175, fullscreenNow = false, maximizedNow = true)
        assertEquals(WindowState.MAXIMIZED, impl.getWindowState())
        assertEquals(Size(1920, 1175), impl.getWindowSize())

        // Native fullscreen reports unrestricted display bounds
        impl.notifyHostResizedFromOs(widthPx = 2560, heightPx = 1600, fullscreenNow = true, maximizedNow = false)
        assertEquals(WindowState.FULLSCREEN, impl.getWindowState())
        assertTrue(impl.isFullscreen())
        assertEquals(Size(2560, 1600), impl.getWindowSize())

        // Returning to NORMAL restores 16:9 aspect enforcement on subsequent setWindowSize calls
        impl.notifyHostResizedFromOs(widthPx = 1400, heightPx = 900, fullscreenNow = false, maximizedNow = false)
        assertEquals(WindowState.NORMAL, impl.getWindowState())
        assertFalse(impl.isFullscreen())
        impl.setWindowSize(Size(1280, 900))
        assertEquals(Size(1280, 720), impl.getWindowSize())

        impl.setAspectRatio(null)
        assertNull(impl.getAspectRatio())
        impl.setWindowSize(Size(1111, 777))
        assertEquals(Size(1111, 777), impl.getWindowSize())
    }

    @Test
    fun runtimeDockIconWrapsGenericIconWithWhiteSquircleOnlyWhenAppleIconNotExplicit() {
        val size = 64
        val solidBlue = ByteArray(size * size * 4)
        for (i in 0 until size * size) {
            val base = i * 4
            solidBlue[base] = 0x10.toByte()
            solidBlue[base + 1] = 0x55.toByte()
            solidBlue[base + 2] = 0xFF.toByte()
            solidBlue[base + 3] = 0xFF.toByte()
        }
        val sourceBuffer = ImageBuffer(
            width = size,
            height = size,
            pixelFormat = PixelFormat.RGBA_8888,
            colorSpace = ImageColorSpace.SRGB,
            bytes = solidBlue
        )

        // Generic fallback icon -> composed >= 512x512 white squircle tile with transparent corners
        GenericUI.appleIconExplicitlyProvided = false
        assertFalse(
            MacosNativeWindowHost.isAlreadyWrappedAppleSquircleIcon(sourceBuffer),
            "Raw full-bleed blue icon must not be mistaken for a wrapped Apple squircle tile"
        )
        val composed = MacosNativeWindowHost.composeRuntimeDockIconBuffer(sourceBuffer)
        assertEquals(512, composed.width)
        assertEquals(512, composed.height)
        assertEquals(PixelFormat.RGBA_8888, composed.pixelFormat)
        assertTrue(
            MacosNativeWindowHost.isAlreadyWrappedAppleSquircleIcon(composed),
            "Composed Apple squircle icon must be recognized as already wrapped"
        )

        val cornerAlpha = composed.bytes[3].toInt() and 0xFF
        assertEquals(0, cornerAlpha, "Outer squircle corner must remain transparent")

        val cx = composed.width / 2
        val cy = composed.height / 2
        val centerIdx = (cy * composed.width + cx) * 4
        val centerR = composed.bytes[centerIdx].toInt() and 0xFF
        val centerB = composed.bytes[centerIdx + 2].toInt() and 0xFF
        val centerA = composed.bytes[centerIdx + 3].toInt() and 0xFF
        assertTrue(centerA == 255 && centerB > centerR, "Center should preserve scaled blue artwork")

        val borderStartY = (composed.height * 0.11).toInt()
        val borderEndY = (composed.height * 0.18).toInt()
        val hasWhiteTileBorder = (borderStartY..borderEndY).any { y ->
            val idx = (y * composed.width + cx) * 4
            val r = composed.bytes[idx].toInt() and 0xFF
            val g = composed.bytes[idx + 1].toInt() and 0xFF
            val b = composed.bytes[idx + 2].toInt() and 0xFF
            val a = composed.bytes[idx + 3].toInt() and 0xFF
            r >= 240 && g >= 240 && b >= 240 && a >= 240
        }
        assertTrue(hasWhiteTileBorder, "Generic fallback icon should sit on Chrome-style white squircle tile")

        // Explicit appleIcon -> returned unwrapped with full-bleed blue corner untouched
        GenericUI.appleIconExplicitlyProvided = true
        val explicitUnwrapped = MacosNativeWindowHost.composeRuntimeDockIconBuffer(sourceBuffer)
        assertEquals(64, explicitUnwrapped.width)
        assertEquals(64, explicitUnwrapped.height)
        assertEquals(0xFF, explicitUnwrapped.bytes[3].toInt() and 0xFF)
        assertEquals(0xFF, explicitUnwrapped.bytes[2].toInt() and 0xFF)
    }

    @Test
    fun svgFallbackDrawableDecodesAndTilesViaCoreGraphicsRasterizer() {
        val drawable = DrawableResource(
            resId = ResourceID(id = 0x7F010099, type = ResourceType.DRAWABLE, name = "icon", relativePath = "drawable/icon.svg"),
            format = "svg",
            svgSource = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" fill="#1E88E5"/></svg>"""
        )
        val decoded = MacosNativeWindowHost.decodeDrawableToBuffer(drawable)
        assertNotNull(decoded)
        assertTrue(decoded.width >= 64 && decoded.height >= 64)

        GenericUI.appleIconExplicitlyProvided = false
        val composed = MacosNativeWindowHost.composeRuntimeDockIconBuffer(decoded)
        assertTrue(composed.width >= 256 && composed.height >= 256)
        assertEquals(0, composed.bytes[3].toInt() and 0xFF)
    }

    @Test
    fun svgCompactLeadingDotPathTokensRasterizeWithoutHanging() {
        val drawable = DrawableResource(
            resId = ResourceID(id = 0x7F01009A, type = ResourceType.DRAWABLE, name = "ic_prev_dot", relativePath = "drawable/ic_prev_dot.svg"),
            format = "svg",
            svgSource = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16" fill="#FFFFFF"><path d="M4 4a.5.5 0 0 1 1 0v3.248l6.267-3.636c.54-.313 1.232.066 1.232.696v7.384c0 .63-.692 1.01-1.232.697L5 8.753V12a.5.5 0 0 1-1 0z"/></svg>"""
        )
        val decoded = MacosNativeWindowHost.decodeDrawableToBuffer(drawable)
        assertNotNull(decoded, "SVG path containing compact leading-dot decimals must decode promptly")
        assertTrue(decoded.width >= 16 && decoded.height >= 16)
        var nonTransparentPixels = 0
        var i = 3
        while (i < decoded.bytes.size) {
            if ((decoded.bytes[i].toInt() and 0xFF) > 0) {
                nonTransparentPixels++
            }
            i += 4
        }
        assertTrue(nonTransparentPixels > 10, "Rasterized leading-dot SVG path must draw visible pixels")
    }

    @Test
    fun invalidAspectRatioThrowsIllegalArgument() {
        val impl = MacosWindowNativeImpl()
        assertFailsWith<IllegalArgumentException> { impl.setAspectRatio(0.0) }
        assertFailsWith<IllegalArgumentException> { impl.setAspectRatio(-4.0 / 3.0) }
        assertFailsWith<IllegalArgumentException> { impl.setAspectRatio(Double.NaN) }
    }
}
