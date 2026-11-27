@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.content.ImageView
import com.nexustools.genericui.component.layout.styled.StyledContainer
import com.nexustools.genericui.component.layout.VStack
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.surface.Card
import com.nexustools.genericui.component.surface.styled.StyledComponent
import com.nexustools.genericui.component.tabs.Tab
import com.nexustools.genericui.component.tabs.TabContainer
import com.nexustools.genericui.component.typography.Heading
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.desktop.system.resolveNativeExecutableDirectory
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.BorderRadiusStyle
import com.nexustools.genericui.enums.bootstrap.SpacingScale
import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageEncodeOptions
import com.nexustools.genericui.image.ImageFormat
import com.nexustools.genericui.res.DataUrlCodec
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.windows.impl.WindowsContainerNativeImpl
import com.nexustools.genericui.windows.impl.WindowsContextMenuNativeImpl
import com.nexustools.genericui.windows.impl.WindowsImageNativeImpl
import com.nexustools.genericui.windows.impl.WindowsStyledComponentNativeImpl
import com.nexustools.genericui.windows.impl.WindowsStyledContainerNativeImpl
import com.nexustools.genericui.windows.impl.WindowsTabContainerNativeImpl
import com.nexustools.genericui.windows.impl.WindowsTabNativeImpl
import com.nexustools.genericui.windows.impl.WindowsTextNativeImpl
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.windows.BITMAP
import platform.windows.DeleteObject
import platform.windows.FW_BOLD
import platform.windows.FW_NORMAL
import platform.windows.GetBitmapBits
import platform.windows.GWL_STYLE
import platform.windows.GetIconInfo
import platform.windows.GetObjectW
import platform.windows.GetWindowLongPtrW
import platform.windows.GetWindowRect
import platform.windows.HFONT
import platform.windows.HFONT__
import platform.windows.HICON
import platform.windows.HICON__
import platform.windows.HWND
import platform.windows.ICONINFO
import platform.windows.ICON_BIG
import platform.windows.ICON_SMALL
import platform.windows.IDI_APPLICATION
import platform.windows.IsWindow
import platform.windows.LOGFONTW
import platform.windows.LoadIconW
import platform.windows.POINT
import platform.windows.RECT
import platform.windows.SS_CENTER
import platform.windows.SS_LEFT
import platform.windows.SS_RIGHT
import platform.windows.SS_TYPEMASK
import platform.windows.ScreenToClient
import platform.windows.SendMessageW
import platform.windows.TPM_LEFTALIGN
import platform.windows.TPM_RIGHTALIGN
import platform.windows.TPM_TOPALIGN
import platform.windows.WM_GETFONT
import platform.windows.WM_GETICON
import platform.windows.WM_LBUTTONDOWN
import platform.windows.WM_LBUTTONUP
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * End-to-end Win32 visual & native-property regression suite verifying:
 * - Bug 1: `ThemeMode.SYSTEM` strips Bootstrap colors on `StyledContainer`/`StyledComponent`
 *   while preserving non-color properties (`borderRadius`, `padding`, explicit CSS borders) and
 *   keeping Bootstrap-specific semantic surfaces (`Card`, `Badge`) enabled.
 * - Bug 2: `Heading(level = 1..6)` resolves distinct semantic `HFONT` heights/weights and
 *   intrinsic measurement reflects semantic heading metrics before/after mount.
 * - Bug 3: `Text` / `Heading` alignment propagates to Win32 `"Static"` `SS_CENTER` / `SS_RIGHT` / `SS_LEFT`
 *   styles dynamically and shifts rendered glyph pixels.
 * - Bug 4: Executable-directory relative assets, `Window.setWindowIcon`, owned `HICON` lifecycle,
 *   and `ImageView` synchronous/local & `data:` decoding work end-to-end.
 */
class WindowsVisualRenderingRegressionTest {

    private fun runWithDeadline(timeoutSeconds: Int = 15, block: () -> Unit) {
        val deadline = TimeSource.Monotonic.markNow() + timeoutSeconds.seconds
        runBlocking {
            withTimeout(timeoutSeconds.seconds) {
                block()
            }
        }
        assertFalse(deadline.hasPassedNow(), "Test exceeded ${timeoutSeconds}s deadline")
    }

    private inline fun withVisualWindowHost(block: (WindowsNativeFactory) -> Unit) {
        val prevFactory = GenericUI.activeFactory
        val prevInteractive = WindowsApplicationNativeImpl.interactiveWin32GuiEnabled
        val prevVisualTest = Win32NativeWindowHost.visualTestMode
        WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = true
        Win32NativeWindowHost.visualTestMode = true
        try {
            val factory = WindowsNativeFactory()
            GenericUI.initialize(factory)
            block(factory)
        } finally {
            Win32NativeWindowHost.closeAllVisualWindows()
            Win32NativeWindowHost.visualTestMode = prevVisualTest
            WindowsApplicationNativeImpl.interactiveWin32GuiEnabled = prevInteractive
            prevFactory?.let { GenericUI.initialize(it) }
        }
    }

    private fun mapHwndToHostClientRect(childHwnd: HWND, hostHwnd: HWND): IntArray = memScoped {
        val r = alloc<RECT>()
        assertTrue(GetWindowRect(childHwnd, r.ptr) != 0, "GetWindowRect must succeed for child HWND")
        val tl = alloc<POINT>().apply { x = r.left; y = r.top }
        val br = alloc<POINT>().apply { x = r.right; y = r.bottom }
        ScreenToClient(hostHwnd, tl.ptr)
        ScreenToClient(hostHwnd, br.ptr)
        intArrayOf(tl.x, tl.y, br.x, br.y)
    }

    private fun joinWinPath(dir: String, fileName: String): String =
        if (dir.endsWith('\\') || dir.endsWith('/')) "$dir$fileName" else "$dir\\$fileName"

    private fun buildValid32BitIcoBytes(size: Int, r: Int, g: Int, b: Int): ByteArray {
        val xorBytesSize = size * size * 4
        val andStride = ((size + 31) / 32) * 4
        val andBytesSize = andStride * size
        val bmpDataSize = 40 + xorBytesSize + andBytesSize
        val totalSize = 22 + bmpDataSize
        val out = ByteArray(totalSize)

        fun writeU16(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v ushr 8) and 0xFF).toByte()
        }

        fun writeI32(off: Int, v: Int) {
            out[off] = (v and 0xFF).toByte()
            out[off + 1] = ((v ushr 8) and 0xFF).toByte()
            out[off + 2] = ((v ushr 16) and 0xFF).toByte()
            out[off + 3] = ((v ushr 24) and 0xFF).toByte()
        }

        // ICONDIR
        writeU16(0, 0)
        writeU16(2, 1) // ICON
        writeU16(4, 1) // 1 image
        // ICONDIRENTRY
        out[6] = size.toByte()
        out[7] = size.toByte()
        out[8] = 0
        out[9] = 0
        writeU16(10, 1) // wPlanes
        writeU16(12, 32) // wBitCount
        writeI32(14, bmpDataSize)
        writeI32(18, 22)
        // BITMAPINFOHEADER
        writeI32(22, 40)
        writeI32(26, size)
        writeI32(30, size * 2) // doubled height (XOR + AND)
        writeU16(34, 1)
        writeU16(36, 32)
        writeI32(38, 0) // BI_RGB
        writeI32(42, xorBytesSize)
        // XOR BGRA pixels
        var p = 62
        repeat(size * size) {
            out[p++] = b.toByte()
            out[p++] = g.toByte()
            out[p++] = r.toByte()
            out[p++] = 0xFF.toByte()
        }
        return out
    }

    private fun buildSolidPngBytes(width: Int, height: Int, r: Int, g: Int, b: Int): ByteArray {
        val pixels = ByteArray(width * height * 4)
        for (i in 0 until width * height) {
            val off = i * 4
            pixels[off] = r.toByte()
            pixels[off + 1] = g.toByte()
            pixels[off + 2] = b.toByte()
            pixels[off + 3] = 0xFF.toByte()
        }
        val buf = ImageBuffer(width = width, height = height, bytes = pixels)
        return assertNotNull(
            WindowsWicImageCodecNativeImpl().encode(buf, ImageEncodeOptions(format = ImageFormat.PNG)),
            "WindowsWicImageCodecNativeImpl must encode test PNG"
        )
    }

    private fun readFontMetrics(hFont: HFONT): Pair<Int, Int> = memScoped {
        val lf = alloc<LOGFONTW>()
        val copied = GetObjectW(hFont, sizeOf<LOGFONTW>().toInt(), lf.ptr)
        assertTrue(copied > 0, "GetObjectW(LOGFONTW) must succeed for resolved HFONT")
        abs(lf.lfHeight) to lf.lfWeight
    }

    private fun assertColorIconValid(hIcon: HICON?, label: String) {
        val icon = assertNotNull(hIcon, "$label must resolve to a non-null HICON")
        memScoped {
            val info = alloc<ICONINFO>()
            assertTrue(GetIconInfo(icon, info.ptr) != 0, "$label GetIconInfo must succeed")
            try {
                val colorBmp = assertNotNull(info.hbmColor, "$label must carry an hbmColor bitmap")
                val bm = alloc<BITMAP>()
                assertTrue(GetObjectW(colorBmp, sizeOf<BITMAP>().toInt(), bm.ptr) > 0)
                assertTrue(bm.bmWidth > 0 && bm.bmHeight > 0, "$label icon size must be positive")
            } finally {
                info.hbmColor?.let { DeleteObject(it) }
                info.hbmMask?.let { DeleteObject(it) }
            }
        }
    }

    @Test
    fun styledContainerStripsBootstrapColorsInSystemModePreservesCssBorderAndCardAllowsSemanticBorder() =
        runWithDeadline {
            withVisualWindowHost { factory ->
                val backdrop = StyledContainer().apply {
                    setBackground(RgbaColor(246, 247, 250, 255))
                    setPadding(EdgeInsets.all(16))
                }
                val styledBox = StyledContainer().apply {
                    setType(BootstrapType.DANGER)
                    setBorderRadius(BorderRadiusStyle.LARGE)
                    setPaddingAll(SpacingScale.THREE)
                }
                val styledLeaf = StyledComponent().apply {
                    setType(BootstrapType.WARNING)
                    setBorderRadius(BorderRadiusStyle.LARGE)
                }
                val primaryCard = Card(title = "Card Title", type = BootstrapType.PRIMARY)
                val badgeStyledContainer = WindowsStyledContainerNativeImpl(
                    classPrefix = "card",
                    tagName = "genericui:card"
                )
                val badgeStyledLeaf = WindowsStyledComponentNativeImpl(
                    classPrefix = "badge",
                    tagName = "badge"
                )
                val root = VStack(gapPx = 12).apply {
                    addChild(styledBox)
                    addChild(styledLeaf)
                    addChild(primaryCard)
                }
                backdrop.addChild(root)

                val window = Window(title = "Bug1 Aesthetic Policy Test", initialSize = Size(320, 260))
                window.setContent(backdrop)
                window.show(factory)

                val boxImplBefore = styledBox.ensureNativeImpl() as WindowsStyledContainerNativeImpl
                assertFalse(
                    boxImplBefore.acceptsBootstrapSemanticColors(),
                    "Generic StyledContainer must reject Bootstrap semantic colors in SYSTEM mode"
                )
                val preBorderVisuals = boxImplBefore.getStyledVisuals()
                assertNull(
                    preBorderVisuals.backgroundColor,
                    "Bootstrap DANGER backgroundColor must be stripped from StyledContainer in SYSTEM mode"
                )
                assertNull(
                    preBorderVisuals.borderColor,
                    "Bootstrap DANGER borderColor must be stripped from StyledContainer in SYSTEM mode"
                )
                assertEquals(8f, preBorderVisuals.borderRadiusPx, "Non-color borderRadius must remain intact")
                assertEquals(16, preBorderVisuals.padding?.top, "Non-color SpacingScale.THREE padding must remain intact")

                val leafImpl = styledLeaf.ensureNativeImpl() as WindowsStyledComponentNativeImpl
                assertFalse(
                    leafImpl.acceptsBootstrapSemanticColors(),
                    "Generic StyledComponent must reject Bootstrap semantic colors in SYSTEM mode"
                )
                assertNull(
                    leafImpl.getStyledVisuals().backgroundColor,
                    "Bootstrap WARNING backgroundColor must be stripped from StyledComponent"
                )
                assertEquals(8f, leafImpl.getStyledVisuals().borderRadiusPx, "Non-color borderRadius must remain on StyledComponent")

                assertTrue(badgeStyledContainer.acceptsBootstrapSemanticColors(), "Card prefix must allow semantic colors")
                assertTrue(badgeStyledLeaf.acceptsBootstrapSemanticColors(), "Badge tag/prefix must allow semantic colors")
                badgeStyledContainer.dispose()
                badgeStyledLeaf.dispose()

                // Apply explicit CSS border on StyledContainer while BootstrapType.DANGER stays set
                styledBox.setBorder(widthPx = 3, color = RgbaColor(20, 120, 220, 255), radiusPx = 8)
                styledBox.getStyle().setWidth(CssLength.px(180f))
                styledBox.getStyle().setHeight(CssLength.px(90f))

                Win32NativeWindowHost.pumpVisualWindowEvents(24L)

                val postBorderVisuals = boxImplBefore.getStyledVisuals()
                assertEquals(RgbaColor(20, 120, 220, 255), postBorderVisuals.borderColor)
                assertEquals(3f, postBorderVisuals.borderWidthPx)
                assertEquals(8f, postBorderVisuals.borderRadiusPx)
                assertNull(postBorderVisuals.backgroundColor, "Explicit CSS border must not re-enable DANGER fill")

                val cardImpl = primaryCard.ensureNativeImpl() as WindowsContainerNativeImpl
                val cardSurface = cardImpl.resolveSemanticSurfaceSpec(darkTheme = Win32NativeWindowHost.isEffectivelyDarkTheme())
                assertNotNull(cardSurface, "Card must retain semantic surface styling under SYSTEM mode")
                assertNotNull(cardSurface.borderColor, "Card must keep a resolved borderColor")
                assertTrue((cardSurface.borderWidthPx ?: 0f) >= 1f, "Card borderWidthPx must remain non-zero")

                val hostHwnd = assertNotNull(Win32NativeWindowHost.resolveHwndForWindow(window.ensureNativeImpl() as WindowsWindowNativeImpl))
                val boxHwnd = assertNotNull(boxImplBefore.nativeWidget.hwnd)
                val boxRect = mapHwndToHostClientRect(boxHwnd, hostHwnd)
                val shot = assertNotNull(Win32NativeWindowHost.captureWindowScreenshot(window.ensureNativeImpl() as WindowsWindowNativeImpl, 320, 260))

                val midX = ((boxRect[0] + boxRect[2]) / 2).coerceIn(1, shot.width - 2)
                val midY = ((boxRect[1] + boxRect[3]) / 2).coerceIn(1, shot.height - 2)
                val centerPx = (midY * shot.width + midX) * 4
                val cR = shot.bytes[centerPx].toInt() and 0xFF
                val cG = shot.bytes[centerPx + 1].toInt() and 0xFF
                val cB = shot.bytes[centerPx + 2].toInt() and 0xFF
                assertFalse(
                    cR > cG + 60 && cR > cB + 60,
                    "StyledContainer center must not be filled with Bootstrap DANGER red (rgb=$cR,$cG,$cB)"
                )

                var blueBorderHits = 0
                for (dy in 0..5) {
                    val sy = (boxRect[1] + dy).coerceIn(0, shot.height - 1)
                    val off = (sy * shot.width + midX) * 4
                    val pr = shot.bytes[off].toInt() and 0xFF
                    val pg = shot.bytes[off + 1].toInt() and 0xFF
                    val pb = shot.bytes[off + 2].toInt() and 0xFF
                    if (pb > pr + 60 && pb > pg + 20) {
                        blueBorderHits++
                    }
                }
                assertTrue(blueBorderHits > 0, "StyledContainer explicit blue CSS border must render visibly at top edge")
            }
        }

    @Test
    fun headingsResolveDistinctH1ToH6Win32FontsAndIntrinsicMetrics() = runWithDeadline {
        withVisualWindowHost { factory ->
            val h1 = Heading("Sample Title", level = 1)
            val h2 = Heading("Sample Title", level = 2)
            val h3 = Heading("Sample Title", level = 3)
            val h4 = Heading("Sample Title", level = 4)
            val h5 = Heading("Sample Title", level = 5)
            val h6 = Heading("Sample Title", level = 6)
            val body = Text("Sample Title")

            val headings = listOf(h1, h2, h3, h4, h5, h6)
            val root = VStack(gapPx = 4).apply {
                headings.forEach { addChild(it) }
                addChild(body)
            }

            val window = Window(title = "Bug2 Heading Typography", initialSize = Size(480, 420))
            window.setContent(root)
            window.show(factory)

            // Verify intrinsic sizing uses resolveUiFontForImpl before full visual layout pump
            val h1PreSize = (h1.ensureNativeImpl() as WindowsTextNativeImpl)
                .measureIntrinsicSize(null, null)
            val bodyPreSize = (body.ensureNativeImpl() as WindowsTextNativeImpl)
                .measureIntrinsicSize(null, null)
            assertTrue(
                h1PreSize.height > bodyPreSize.height && h1PreSize.width > bodyPreSize.width,
                "H1 intrinsic size ($h1PreSize) must exceed body Text ($bodyPreSize)"
            )

            Win32NativeWindowHost.pumpVisualWindowEvents(24L)

            val heights = mutableListOf<Int>()
            val expectedPx = listOf(32, 26, 22, 18, 16, 14)
            for ((idx, heading) in headings.withIndex()) {
                val impl = heading.ensureNativeImpl() as WindowsTextNativeImpl
                val hwnd = assertNotNull(impl.nativeWidget.hwnd)
                val fontPtr = SendMessageW(hwnd, WM_GETFONT.toUInt(), 0uL, 0L)
                assertTrue(fontPtr != 0L, "Heading level ${idx + 1} HWND must hold an HFONT via WM_GETFONT")
                val hFont = assertNotNull(fontPtr.toCPointer<HFONT__>())
                val (heightPx, weight) = readFontMetrics(hFont)
                assertEquals(expectedPx[idx], heightPx, "H${idx + 1} font height must match semantic scale")
                assertEquals(FW_BOLD, weight, "H${idx + 1} font weight must be bold ($FW_BOLD)")
                heights.add(heightPx)
            }
            for (i in 0 until heights.lastIndex) {
                assertTrue(
                    heights[i] > heights[i + 1],
                    "H${i + 1} height (${heights[i]}) must be strictly larger than H${i + 2} (${heights[i + 1]})"
                )
            }

            val bodyImpl = body.ensureNativeImpl() as WindowsTextNativeImpl
            val bodyHwnd = assertNotNull(bodyImpl.nativeWidget.hwnd)
            val bodyFontPtr = SendMessageW(bodyHwnd, WM_GETFONT.toUInt(), 0uL, 0L)
            assertTrue(bodyFontPtr != 0L)
            val (bodyHeightPx, bodyWeight) = readFontMetrics(assertNotNull(bodyFontPtr.toCPointer<HFONT__>()))
            assertEquals(13, bodyHeightPx, "Default Text font height must remain 13px")
            assertEquals(FW_NORMAL, bodyWeight, "Default Text font weight must be normal ($FW_NORMAL)")

            val h1Measured = (h1.ensureNativeImpl() as WindowsTextNativeImpl).measureIntrinsicSize(null, null)
            val h4Measured = (h4.ensureNativeImpl() as WindowsTextNativeImpl).measureIntrinsicSize(null, null)
            val bodyMeasured = bodyImpl.measureIntrinsicSize(null, null)
            assertTrue(
                h1Measured.height > h4Measured.height && h4Measured.height > bodyMeasured.height,
                "Measured heights must order H1 ($h1Measured) > H4 ($h4Measured) > Text ($bodyMeasured)"
            )
        }
    }

    @Test
    fun textAndHeadingAlignmentAppliesWin32StaticStylesAndShiftsRenderedInk() = runWithDeadline {
        withVisualWindowHost { factory ->
            val container = StyledContainer().apply {
                setBackground(RgbaColor(255, 255, 255, 255))
                setPadding(EdgeInsets.all(12))
            }
            val startText = Text("III").apply {
                setTextAlignment(TextAlignment.START)
                getStyle().setColor(RgbaColor(0, 0, 0, 255))
                getStyle().setWidth(CssLength.px(360f))
                getStyle().setHeight(CssLength.px(28f))
            }
            val centerText = Text("III").apply {
                setTextAlignment(TextAlignment.CENTER)
                getStyle().setColor(RgbaColor(0, 0, 0, 255))
                getStyle().setWidth(CssLength.px(360f))
                getStyle().setHeight(CssLength.px(28f))
            }
            val endText = Text("III").apply {
                setTextAlignment(TextAlignment.END)
                getStyle().setColor(RgbaColor(0, 0, 0, 255))
                getStyle().setWidth(CssLength.px(360f))
                getStyle().setHeight(CssLength.px(28f))
            }
            val dynamicHeading = Heading("HDR", level = 3).apply {
                getStyle().setWidth(CssLength.px(360f))
                getStyle().setHeight(CssLength.px(32f))
            }

            val root = VStack(gapPx = 8).apply {
                addChild(startText)
                addChild(centerText)
                addChild(endText)
                addChild(dynamicHeading)
            }
            container.addChild(root)

            val window = Window(title = "Bug3 Static Alignment Test", initialSize = Size(400, 220))
            window.setContent(container)
            window.show(factory)
            Win32NativeWindowHost.pumpVisualWindowEvents(24L)

            val startImpl = startText.ensureNativeImpl() as WindowsTextNativeImpl
            val centerImpl = centerText.ensureNativeImpl() as WindowsTextNativeImpl
            val endImpl = endText.ensureNativeImpl() as WindowsTextNativeImpl
            val headingImpl = dynamicHeading.ensureNativeImpl() as WindowsTextNativeImpl

            assertEquals(SS_LEFT, GetWindowLongPtrW(assertNotNull(startImpl.nativeWidget.hwnd), GWL_STYLE).toInt() and SS_TYPEMASK)
            assertEquals(SS_CENTER, GetWindowLongPtrW(assertNotNull(centerImpl.nativeWidget.hwnd), GWL_STYLE).toInt() and SS_TYPEMASK)
            assertEquals(SS_RIGHT, GetWindowLongPtrW(assertNotNull(endImpl.nativeWidget.hwnd), GWL_STYLE).toInt() and SS_TYPEMASK)
            assertEquals(SS_CENTER, centerImpl.nativeWidget.dwStyle.toInt() and SS_TYPEMASK)
            assertEquals(SS_RIGHT, endImpl.nativeWidget.dwStyle.toInt() and SS_TYPEMASK)

            // Dynamic alignment switches on live Static HWND via both enum & ComputedStyle
            dynamicHeading.setTextAlignment(TextAlignment.CENTER)
            Win32NativeWindowHost.pumpVisualWindowEvents(12L)
            assertEquals(SS_CENTER, GetWindowLongPtrW(assertNotNull(headingImpl.nativeWidget.hwnd), GWL_STYLE).toInt() and SS_TYPEMASK)
            headingImpl.applyComputedStyle(ComputedStyle(textAlign = "right"))
            Win32NativeWindowHost.pumpVisualWindowEvents(12L)
            assertEquals(SS_RIGHT, GetWindowLongPtrW(assertNotNull(headingImpl.nativeWidget.hwnd), GWL_STYLE).toInt() and SS_TYPEMASK)
            assertEquals(SS_RIGHT, headingImpl.nativeWidget.dwStyle.toInt() and SS_TYPEMASK)

            // Verify actual rendered ink centroid shifts across START < CENTER < END
            assertTrue(startImpl.nativeWidget.bounds.width >= 300, "Aligned Static control must have >= 300px width")
            val hostHwnd = assertNotNull(Win32NativeWindowHost.resolveHwndForWindow(window.ensureNativeImpl() as WindowsWindowNativeImpl))
            val shot = assertNotNull(Win32NativeWindowHost.captureWindowScreenshot(window.ensureNativeImpl() as WindowsWindowNativeImpl, 400, 220))

            fun computeDarkInkCentroidX(impl: WindowsTextNativeImpl, label: String): Double {
                val rect = mapHwndToHostClientRect(assertNotNull(impl.nativeWidget.hwnd), hostHwnd)
                var sumX = 0.0
                var count = 0
                val left = rect[0].coerceIn(0, shot.width - 1)
                val top = rect[1].coerceIn(0, shot.height - 1)
                val right = rect[2].coerceIn(left, shot.width)
                val bottom = rect[3].coerceIn(top, shot.height)
                for (y in top until bottom) {
                    for (x in left until right) {
                        val off = (y * shot.width + x) * 4
                        val r = shot.bytes[off].toInt() and 0xFF
                        val g = shot.bytes[off + 1].toInt() and 0xFF
                        val b = shot.bytes[off + 2].toInt() and 0xFF
                        if (r < 140 && g < 140 && b < 140) {
                            sumX += (x - left).toDouble()
                            count++
                        }
                    }
                }
                assertTrue(count > 0, "$label Static control must render visible dark glyph pixels inside $left..$right")
                return sumX / count
            }

            val startCentroidX = computeDarkInkCentroidX(startImpl, "START")
            val centerCentroidX = computeDarkInkCentroidX(centerImpl, "CENTER")
            val endCentroidX = computeDarkInkCentroidX(endImpl, "END")
            assertTrue(
                startCentroidX + 40.0 < centerCentroidX && centerCentroidX + 40.0 < endCentroidX,
                "Rendered ink centroids must satisfy START ($startCentroidX) << CENTER ($centerCentroidX) << END ($endCentroidX)"
            )
        }
    }

    @Test
    fun exeDirAssetsWindowIconOwnershipAndImageViewDecoding() = runWithDeadline {
        withVisualWindowHost { factory ->
            val exeDir = assertNotNull(resolveNativeExecutableDirectory(), "resolveNativeExecutableDirectory must return valid path on Windows")
            val icoName = "__genericui_bug4_icon_a.ico"
            val pngIconName = "__genericui_bug4_icon_b.png"
            val imgName = "__genericui_bug4_image.png"
            val icoPath = joinWinPath(exeDir, icoName)
            val pngIconPath = joinWinPath(exeDir, pngIconName)
            val imgPath = joinWinPath(exeDir, imgName)

            val icoBytes = buildValid32BitIcoBytes(16, r = 220, g = 45, b = 30)
            val pngIconBytes = buildSolidPngBytes(32, 32, r = 30, g = 110, b = 235)
            val imgPngBytes = buildSolidPngBytes(24, 24, r = 24, g = 216, b = 56)

            try {
                assertTrue(DesktopConfigDirectoryHelper.writeBytesToFile(icoPath, icoBytes), "Failed to write test ICO in $exeDir")
                assertTrue(DesktopConfigDirectoryHelper.writeBytesToFile(pngIconPath, pngIconBytes), "Failed to write test PNG icon in $exeDir")
                assertTrue(DesktopConfigDirectoryHelper.writeBytesToFile(imgPath, imgPngBytes), "Failed to write test PNG image in $exeDir")

                val resolvedImgPath = DesktopConfigDirectoryHelper.resolveLocalAssetFilePath("$imgName?v=2#frag")
                assertNotNull(resolvedImgPath, "resolveLocalAssetFilePath must locate exe-dir asset with query/fragment stripped")
                assertNotNull(DesktopConfigDirectoryHelper.readResourceOrLocalFile("$imgName?cache=1"))

                val dataUriPng = DataUrlCodec.encodeBytes(imgPngBytes, "image/png")
                val exeImageView = ImageView(sourceUrl = imgName).apply {
                    getStyle().setWidth(CssLength.px(24f))
                    getStyle().setHeight(CssLength.px(24f))
                }
                val dataUriImageView = ImageView(sourceUrl = dataUriPng).apply {
                    getStyle().setWidth(CssLength.px(24f))
                    getStyle().setHeight(CssLength.px(24f))
                }

                val content = StyledContainer().apply {
                    setBackground(RgbaColor(240, 242, 246, 255))
                    setPadding(EdgeInsets.all(16))
                    addChild(
                        VStack(gapPx = 10).apply {
                            addChild(exeImageView)
                            addChild(dataUriImageView)
                        }
                    )
                }

                val window = Window(title = "Bug4 Assets & Icon Test", initialSize = Size(320, 220))
                window.setContent(content)
                window.setWindowIcon(icoName)
                window.show(factory)
                Win32NativeWindowHost.pumpVisualWindowEvents(24L)

                val winImpl = window.ensureNativeImpl() as WindowsWindowNativeImpl
                val hwnd = assertNotNull(Win32NativeWindowHost.resolveHwndForWindow(winImpl))
                assertTrue(IsWindow(hwnd) != 0)
                val defaultSysIcon = LoadIconW(null, IDI_APPLICATION)
                assertNotEquals(defaultSysIcon, Win32NativeWindowHost.appIconBig)
                assertNotEquals(defaultSysIcon, Win32NativeWindowHost.appIconSmall)

                val bigIcon1Ptr = SendMessageW(hwnd, WM_GETICON.toUInt(), ICON_BIG.toULong(), 0L)
                val smallIcon1Ptr = SendMessageW(hwnd, WM_GETICON.toUInt(), ICON_SMALL.toULong(), 0L)
                assertTrue(bigIcon1Ptr != 0L && smallIcon1Ptr != 0L, "Window must receive initial big & small HICONs from exe-dir .ico")
                val bigIcon1 = bigIcon1Ptr.toCPointer<HICON__>()
                val smallIcon1 = smallIcon1Ptr.toCPointer<HICON__>()
                assertNotEquals(defaultSysIcon, bigIcon1)
                assertNotEquals(defaultSysIcon, smallIcon1)
                assertColorIconValid(bigIcon1, "Initial .ico ICON_BIG")
                assertColorIconValid(smallIcon1, "Initial .ico ICON_SMALL")

                // Dynamically swap window icon to exe-dir PNG and confirm distinct live HICON handles
                window.setWindowIcon(pngIconName)
                Win32NativeWindowHost.pumpVisualWindowEvents(20L)
                val bigIcon2Ptr = SendMessageW(hwnd, WM_GETICON.toUInt(), ICON_BIG.toULong(), 0L)
                val smallIcon2Ptr = SendMessageW(hwnd, WM_GETICON.toUInt(), ICON_SMALL.toULong(), 0L)
                assertTrue(bigIcon2Ptr != 0L && smallIcon2Ptr != 0L, "Updated PNG window icon must assign valid HICONs")
                val bigIcon2 = bigIcon2Ptr.toCPointer<HICON__>()
                val smallIcon2 = smallIcon2Ptr.toCPointer<HICON__>()
                assertNotEquals(defaultSysIcon, bigIcon2)
                assertNotEquals(bigIcon1, bigIcon2, "Swapping window icon must replace ICON_BIG handle")
                assertNotEquals(smallIcon1, smallIcon2, "Swapping window icon must replace ICON_SMALL handle")
                assertColorIconValid(bigIcon2, "Updated .png ICON_BIG")
                assertColorIconValid(smallIcon2, "Updated .png ICON_SMALL")

                // Verify ImageView synchronous decoding & rendering for both exe-dir PNG and data: URI
                val exeImgImpl = exeImageView.ensureNativeImpl() as WindowsImageNativeImpl
                val dataImgImpl = dataUriImageView.ensureNativeImpl() as WindowsImageNativeImpl
                val exeBuf = assertNotNull(exeImgImpl.loadedImageBuffer, "ImageView must decode relative exe-dir PNG synchronously")
                assertEquals(24, exeBuf.width)
                assertEquals(24, exeBuf.height)
                assertEquals(Size(24, 24), exeImgImpl.measureIntrinsicSize(null, null))
                assertNotNull(exeImgImpl.nativeWidget.attributes["imageBuffer"], "imageBuffer attribute must be populated")

                val dataBuf = assertNotNull(dataImgImpl.loadedImageBuffer, "ImageView must decode data: PNG synchronously")
                assertEquals(24, dataBuf.width)
                assertEquals(24, dataBuf.height)

                val imgRect = mapHwndToHostClientRect(assertNotNull(exeImgImpl.nativeWidget.hwnd), hwnd)
                val shot = assertNotNull(Win32NativeWindowHost.captureWindowScreenshot(winImpl, 320, 220))
                val sampleX = ((imgRect[0] + imgRect[2]) / 2).coerceIn(0, shot.width - 1)
                val sampleY = ((imgRect[1] + imgRect[3]) / 2).coerceIn(0, shot.height - 1)
                val pxOff = (sampleY * shot.width + sampleX) * 4
                val pr = shot.bytes[pxOff].toInt() and 0xFF
                val pg = shot.bytes[pxOff + 1].toInt() and 0xFF
                val pb = shot.bytes[pxOff + 2].toInt() and 0xFF
                assertTrue(
                    pg > pr + 80 && pg > pb + 80,
                    "Rendered ImageView center pixel must show bright green asset color (rgb=$pr,$pg,$pb)"
                )
            } finally {
                DesktopConfigDirectoryHelper.deleteFile(icoPath)
                DesktopConfigDirectoryHelper.deleteFile(pngIconPath)
                DesktopConfigDirectoryHelper.deleteFile(imgPath)
            }
        }
    }

    @Test
    fun svgRasterizerHandlesBrowserGlobeLinearGradientsRoundedRectEllipsePolygonAndLine() = runWithDeadline {
        val browserGlobeSvg = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" width="256" height="256">
              <defs>
                <linearGradient id="browser-bg" x1="0" y1="0" x2="256" y2="256" gradientUnits="userSpaceOnUse">
                  <stop offset="0%" stop-color="#1F2937"/>
                  <stop offset="100%" stop-color="#0F172A"/>
                </linearGradient>
                <linearGradient id="ring-outer" x1="28" y1="28" x2="228" y2="228" gradientUnits="userSpaceOnUse">
                  <stop offset="0%" stop-color="#4285F4"/>
                  <stop offset="35%" stop-color="#EA4335"/>
                  <stop offset="70%" stop-color="#FBBC05"/>
                  <stop offset="100%" stop-color="#34A853"/>
                </linearGradient>
                <linearGradient id="core-blue" x1="88" y1="88" x2="168" y2="168" gradientUnits="userSpaceOnUse">
                  <stop offset="0%" stop-color="#60A5FA"/>
                  <stop offset="100%" stop-color="#2563EB"/>
                </linearGradient>
              </defs>
              <rect x="16" y="16" width="224" height="224" rx="48" fill="url(#browser-bg)" stroke="url(#ring-outer)" stroke-width="8"/>
              <circle cx="128" cy="128" r="78" fill="none" stroke="url(#ring-outer)" stroke-width="14"/>
              <ellipse cx="128" cy="128" rx="42" ry="78" fill="none" stroke="#93C5FD" stroke-width="5" stroke-opacity="0.65"/>
              <line x1="50" y1="128" x2="206" y2="128" stroke="#93C5FD" stroke-width="5" stroke-opacity="0.65"/>
              <circle cx="128" cy="128" r="34" fill="url(#core-blue)" stroke="#F8FAFC" stroke-width="6"/>
              <polygon points="128,68 140,120 128,112 116,120" fill="#FBBF24"/>
            </svg>
        """.trimIndent()

        val rasterized = assertNotNull(
            Win32SvgIconRasterizer.rasterize(browserGlobeSvg, 48, 48),
            "Win32SvgIconRasterizer must decode browser globe SVG with linearGradient + ellipse + polygon"
        )
        assertEquals(48, rasterized.width)
        assertEquals(48, rasterized.height)
        assertEquals(0, rasterized.getPixel(0, 0).a, "Rounded corner outside rx=48 must stay transparent")

        // Interior dark slate gradient background (x~10, y~10 -> ~53,53 in 256 space)
        val bgPx = rasterized.getPixel(10, 10)
        assertTrue(bgPx.a >= 250 && bgPx.b > bgPx.r, "Rounded background must render slate gradient, got $bgPx")

        // Core blue center gradient pixel (24, 24)
        val centerPx = rasterized.getPixel(24, 24)
        assertTrue(centerPx.a >= 250 && centerPx.b > 180, "Center lens gradient must be bright blue, got $centerPx")

        // Compass needle gold polygon tip around y = 18 (96 in 256 space), x = 24 (128 in 256 space)
        val needlePx = rasterized.getPixel(24, 18)
        assertTrue(needlePx.r > 200 && needlePx.g > 150 && needlePx.b < 100, "Polygon compass needle must be gold/yellow, got $needlePx")
    }

    @Test
    fun windowsCurCodecProducesNonEmptyAndMaskHIcon() = runWithDeadline {
        val img = ImageBuffer(32, 32)
        for (y in 8 until 24) {
            for (x in 8 until 24) {
                img.setPixel(x, y, RgbaColor(66, 133, 244, 255))
            }
        }
        val hIcon = assertNotNull(WindowsCurCodec.createHIconFromImage(img))
        try {
            assertColorIconValid(hIcon, "WindowsCurCodec HICON")
            memScoped {
                val info = alloc<ICONINFO>()
                assertTrue(GetIconInfo(hIcon, info.ptr) != 0)
                val maskBmp = info.hbmMask
                try {
                    assertNotNull(maskBmp)
                    val bm = alloc<BITMAP>()
                    assertTrue(GetObjectW(maskBmp, sizeOf<BITMAP>().toInt(), bm.ptr) > 0)
                    val maskBytes = ByteArray(bm.bmWidthBytes * bm.bmHeight)
                    maskBytes.usePinned { pinned ->
                        assertTrue(GetBitmapBits(maskBmp, maskBytes.size, pinned.addressOf(0)) > 0)
                    }
                    // Transparent pixel (0, 0) must have AND mask bit = 1 (0x80 set in byte 0)
                    assertTrue((maskBytes[0].toInt() and 0x80) != 0, "Transparent corner must set AND mask bit")
                    // Opaque pixel (16, 16) must have AND mask bit = 0
                    val opaqueBit = maskBytes[16 * bm.bmWidthBytes + (16 ushr 3)].toInt() and (0x80 ushr (16 and 7))
                    assertEquals(0, opaqueBit, "Opaque interior pixel must clear AND mask bit")
                } finally {
                    info.hbmColor?.let { DeleteObject(it) }
                    info.hbmMask?.let { DeleteObject(it) }
                }
            }
        } finally {
            platform.windows.DestroyIcon(hIcon)
        }
    }

    @Test
    fun contextMenuPlacementAlignsRightNearWindowOrMonitorEdge() = runWithDeadline {
        val mid = WindowsContextMenuNativeImpl.computePopupMenuPlacement(
            screenX = 400,
            screenY = 120,
            clientRightScreen = 1200,
            monitorWorkRight = 1920
        )
        assertEquals(TPM_LEFTALIGN or TPM_TOPALIGN, mid.alignFlags)
        assertEquals(400, mid.trackX)

        val rightBtnMenu = WindowsContextMenuNativeImpl.computePopupMenuPlacement(
            screenX = 1164,
            screenY = 72,
            clientRightScreen = 1200,
            monitorWorkRight = 1920,
            anchorWidth = 32,
            anchorScreenRight = 1196,
            anchorLocalX = 0
        )
        assertEquals(TPM_RIGHTALIGN or TPM_TOPALIGN, rightBtnMenu.alignFlags)
        assertEquals(1196, rightBtnMenu.trackX)

        val monitorEdgeMenu = WindowsContextMenuNativeImpl.computePopupMenuPlacement(
            screenX = 1850,
            screenY = 200,
            clientRightScreen = 2200,
            monitorWorkRight = 1920
        )
        assertEquals(TPM_RIGHTALIGN or TPM_TOPALIGN, monitorEdgeMenu.alignFlags)
    }

    @Test
    fun tabAndTabContainerRenderFaviconsAndClickableCloseButtons() = runWithDeadline {
        val svgFavicon = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 16 16\"><circle cx=\"8\" cy=\"8\" r=\"6\" fill=\"#EF4444\"/></svg>"
        val dataUri = DataUrlCodec.encodeBytes(svgFavicon.encodeToByteArray(), "image/svg+xml")

        val decodedViaCodec = WindowsWicImageCodecNativeImpl().decode(
            com.nexustools.genericui.image.ImageSource.fromBytes(svgFavicon.encodeToByteArray(), ImageFormat.SVG),
            ImageDecodeOptions(targetWidth = 16, targetHeight = 16)
        )
        assertNotNull(decodedViaCodec)

        withVisualWindowHost { factory ->
            var standaloneCloses = 0
            val standaloneTab = Tab(
                title = "Standalone",
                icon = dataUri,
                closable = true,
                onCloseRequested = { standaloneCloses++; true }
            )

            var closedTabId: String? = null
            val tabHome = Tab(title = "Home", icon = dataUri, closable = true)
            val tabDocs = Tab(title = "Docs", icon = dataUri, closable = true)
            val docsTabId = tabDocs.tabId
            val tabs = TabContainer().apply {
                addTab(tabHome, Text("Home Body"))
                addTab(tabDocs, Text("Docs Body"))
                onTabCloseRequested = { entry ->
                    closedTabId = entry.id
                    true
                }
            }

            val root = VStack(gapPx = 8).apply {
                setPadding(EdgeInsets.all(10))
                addChild(standaloneTab)
                addChild(tabs)
            }
            val window = Window(title = "Tabs & Favicons", initialSize = Size(480, 320))
            window.setContent(root)
            window.show(factory)
            Win32NativeWindowHost.pumpVisualWindowEvents(24L)

            val standaloneImpl = standaloneTab.ensureNativeImpl() as WindowsTabNativeImpl
            assertNotNull(standaloneImpl.decodedIconBuffer, "WindowsTabNativeImpl must decode SVG data URI favicon")

            val winImpl = window.ensureNativeImpl() as WindowsWindowNativeImpl
            val hostHwnd = assertNotNull(Win32NativeWindowHost.resolveHwndForWindow(winImpl))
            assertEquals(0, Win32ControlMessageRouter.batchingHostLayoutDepth)

            // Test standalone Tab close button hit-test & click
            val tabHwnd = assertNotNull(standaloneImpl.nativeWidget.hwnd)
            val closeRc = assertNotNull(standaloneImpl.currentCloseButtonRect())
            val cx = closeRc.x + closeRc.width / 2
            val cy = closeRc.y + closeRc.height / 2
            assertTrue(standaloneImpl.isPointInCloseButton(cx, cy))
            val lParamClose = ((cy and 0xFFFF) shl 16 or (cx and 0xFFFF)).toLong()
            SendMessageW(tabHwnd, WM_LBUTTONDOWN.toUInt(), 1uL, lParamClose)
            SendMessageW(tabHwnd, WM_LBUTTONUP.toUInt(), 0uL, lParamClose)
            assertEquals(1, standaloneCloses, "Clicking standalone tab × button must trigger onCloseRequested")

            // Test SysTabControl32 TCS_OWNERDRAWFIXED + clickable close button on tab 1 without switching away from tab 0
            val tcImpl = tabs.ensureNativeImpl() as WindowsTabContainerNativeImpl
            val tcHwnd = assertNotNull(tcImpl.nativeWidget.hwnd)
            val tcStyle = GetWindowLongPtrW(tcHwnd, GWL_STYLE).toLong()
            assertTrue((tcStyle and 0x2000L) != 0L, "SysTabControl32 must enable TCS_OWNERDRAWFIXED")

            val tcItemRc = memScoped {
                val r = alloc<RECT>()
                assertTrue(SendMessageW(tcHwnd, 0x130Au /* TCM_GETITEMRECT */, 1uL, r.ptr.toLong()) != 0L)
                intArrayOf(r.left, r.top, r.right, r.bottom)
            }
            val tab1Impl = tabDocs.ensureNativeImpl() as WindowsTabNativeImpl
            val closeRect1 = assertNotNull(
                WindowsTabContainerNativeImpl.closeButtonRectForTabBounds(
                    left = tcItemRc[0],
                    top = tcItemRc[1],
                    right = tcItemRc[2],
                    bottom = tcItemRc[3],
                    hasIcon = tab1Impl.decodedIconBuffer != null,
                    iconSizePx = tab1Impl.iconSizePxVal,
                    closable = true
                )
            )
            val c1x = closeRect1.x + closeRect1.width / 2
            val c1y = closeRect1.y + closeRect1.height / 2
            assertEquals(1, tcImpl.hitTestTabCloseButtonAt(tcHwnd, c1x, c1y))
            val lParamTabClose = ((c1y and 0xFFFF) shl 16 or (c1x and 0xFFFF)).toLong()
            SendMessageW(tcHwnd, WM_LBUTTONDOWN.toUInt(), 1uL, lParamTabClose)
            SendMessageW(tcHwnd, WM_LBUTTONUP.toUInt(), 0uL, lParamTabClose)
            assertEquals(docsTabId, closedTabId, "Clicking SysTabControl32 close button must request closing clicked tab")
            assertEquals(0, tabs.selectedIndex, "Closing unselected tab must not switch active tab first")
            assertEquals(1, tabs.entries.size, "Closed tab must be removed cleanly from TabContainer")
            assertTrue(IsWindow(hostHwnd) != 0)
        }
    }
}
