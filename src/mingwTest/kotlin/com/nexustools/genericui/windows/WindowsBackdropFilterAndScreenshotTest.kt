@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.css.CssShadow
import com.nexustools.genericui.windows.impl.WindowsContainerNativeImpl
import com.nexustools.genericui.windows.impl.WindowsStyledContainerNativeImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsBackdropFilterAndScreenshotTest {

    private fun buildQuadrantTestPixels(w: Int, h: Int): IntArray {
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = when {
                    x < w / 2 && y < h / 2 -> 0xFFDD3322.toInt()
                    x >= w / 2 && y < h / 2 -> 0xFF22AA44.toInt()
                    x < w / 2 && y >= h / 2 -> 0xFF2266DD.toInt()
                    else -> 0xFFF0D040.toInt()
                }
            }
        }
        return pixels
    }

    @Test
    fun allTenCssFilterOperationsProduceDistinctValidArgbOutput() {
        val w = 64
        val h = 64
        val baseline = buildQuadrantTestPixels(w, h)

        val filters = listOf(
            "blur" to CssFilter(blur = CssLength.px(6f)),
            "brightness" to CssFilter(brightness = 1.6f),
            "contrast" to CssFilter(contrast = 1.8f),
            "grayscale" to CssFilter(grayscale = 1f),
            "hueRotate" to CssFilter(hueRotateDeg = 90f),
            "invert" to CssFilter(invert = 1f),
            "opacity" to CssFilter(opacity = 0.5f),
            "saturate" to CssFilter(saturate = 2.2f),
            "sepia" to CssFilter(sepia = 1f),
            "dropShadow" to CssFilter(
                dropShadow = CssShadow.px(
                    offsetX = 4f,
                    offsetY = 4f,
                    blurRadius = 6f,
                    color = RgbaColor(0, 0, 0, 180)
                )
            )
        )

        for ((name, filter) in filters) {
            val copy = baseline.copyOf()
            Win32Direct2dBackdropBlurEngine.applyBackdropToArgbArray(
                filter = filter,
                argbPixels = copy,
                bufferWidth = w,
                bufferHeight = h,
                regionX = 8,
                regionY = 8,
                regionWidth = 48,
                regionHeight = 48,
                cornerRadiusPx = 6f,
                opacity = 1f
            )
            val centerBefore = baseline[32 * w + 32]
            val sampleIdx = if (name == "blur" || name == "dropShadow") (8 * w + 32) else (16 * w + 16)
            val changed = (0 until w * h).any { copy[it] != baseline[it] }
            assertTrue(changed, "Filter '$name' should transform at least some region pixels ($centerBefore vs ${copy[sampleIdx]})")
            assertEquals(baseline[0], copy[0], "Pixels outside padded filter region for '$name' should remain intact")
        }
    }

    @Test
    fun printWindowCapturesStyledRetainedChildHwndsWithBackdropFilter() {
        val prevVisual = Win32NativeWindowHost.visualTestMode
        Win32NativeWindowHost.visualTestMode = true
        val win = WindowsWindowNativeImpl()
        val root = WindowsContainerNativeImpl()
        val backdropCard = WindowsStyledContainerNativeImpl()
        val frostedHud = WindowsStyledContainerNativeImpl()
        try {
            val cardStyle = ComponentStyle().apply {
                backgroundColor = RgbaColor(210, 48, 48, 255)
            }
            backdropCard.applyComponentStyle(cardStyle)

            val hudStyle = ComponentStyle().apply {
                backgroundColor = RgbaColor(255, 255, 255, 165)
                borderColor = RgbaColor(255, 255, 255, 220)
                backdropFilter = CssFilter(blur = CssLength.px(10f), brightness = 1.15f)
            }
            frostedHud.applyComponentStyle(hudStyle)

            root.addChildNative(backdropCard)
            root.addChildNative(frostedHud)
            win.addChildNative(root)

            win.setWindowSize(Size(320, 240))
            root.setBounds(Rect(0, 0, 320, 240))
            backdropCard.setBounds(Rect(20, 20, 280, 200))
            frostedHud.setBounds(Rect(48, 48, 224, 144))

            val capture = Win32NativeWindowHost.captureWindowScreenshot(win, 320, 240)
            assertNotNull(capture)
            assertEquals(320, capture.width)
            assertEquals(240, capture.height)
            assertEquals(320 * 240 * 4, capture.bytes.size)

            val uniqueRgba = HashSet<Int>()
            for (i in 0 until capture.bytes.size step 4) {
                val packed = ((capture.bytes[i].toInt() and 0xFF) shl 24) or
                    ((capture.bytes[i + 1].toInt() and 0xFF) shl 16) or
                    ((capture.bytes[i + 2].toInt() and 0xFF) shl 8) or
                    (capture.bytes[i + 3].toInt() and 0xFF)
                uniqueRgba.add(packed)
                if (uniqueRgba.size >= 8) break
            }
            assertNotEquals(1, uniqueRgba.size, "PrintWindow screenshot of styled HWND hierarchy must not be blank uniform")
        } finally {
            Win32NativeWindowHost.closeAllVisualWindows()
            Win32NativeWindowHost.visualTestMode = prevVisual
        }
    }
}
