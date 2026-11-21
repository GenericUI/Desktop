package com.nexustools.genericui.desktop

import com.nexustools.genericui.desktop.system.DesktopSystemTrayTemplateAnalysis
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.style.color.RgbaColor
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopTrayTemplateAnalysisTest {

    @Test
    fun `monochrome alpha mask classifies as template while multicolor and opaque icons do not`() {
        // 1. Monochrome black alpha glyph on transparent canvas -> isTemplate = true
        val monoMask = ImageBuffer(16, 16, PixelFormat.RGBA_8888)
        monoMask.fill(RgbaColor.TRANSPARENT)
        for (y in 4 until 12) {
            for (x in 4 until 12) {
                monoMask.setPixel(x, y, RgbaColor(0, 0, 0, 255))
            }
        }
        assertTrue(DesktopSystemTrayTemplateAnalysis.shouldTreatAsTemplateImage(monoMask))

        // 2. Full-color icon with red + blue strokes on transparent canvas -> isTemplate = false
        val multicolor = monoMask.copy()
        multicolor.setPixel(8, 8, RgbaColor(235, 64, 52, 255))
        multicolor.setPixel(9, 8, RgbaColor(37, 99, 235, 255))
        assertFalse(DesktopSystemTrayTemplateAnalysis.shouldTreatAsTemplateImage(multicolor))

        // 3. Fully opaque solid black square with zero transparent pixels -> isTemplate = false
        val opaqueSquare = ImageBuffer(16, 16, PixelFormat.RGBA_8888)
        opaqueSquare.fill(RgbaColor(0, 0, 0, 255))
        assertFalse(DesktopSystemTrayTemplateAnalysis.shouldTreatAsTemplateImage(opaqueSquare))
    }
}
