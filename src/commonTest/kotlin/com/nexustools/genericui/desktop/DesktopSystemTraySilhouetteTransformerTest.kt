package com.nexustools.genericui.desktop

import com.nexustools.genericui.desktop.system.DesktopSystemTraySilhouetteTransformer
import com.nexustools.genericui.desktop.system.DesktopSystemTrayTemplateAnalysis
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.style.color.RgbaColor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopSystemTraySilhouetteTransformerTest {

    @Test
    fun `preserves monochrome alpha mask pixels as template black silhouette`() {
        val mono = ImageBuffer(16, 16, PixelFormat.RGBA_8888)
        mono.fill(RgbaColor.TRANSPARENT)
        for (y in 4 until 12) {
            for (x in 4 until 12) {
                mono.setPixel(x, y, RgbaColor(255, 255, 255, 220))
            }
        }

        val transformed = DesktopSystemTraySilhouetteTransformer.toMacosTemplateSilhouette(mono)
        assertTrue(DesktopSystemTrayTemplateAnalysis.shouldTreatAsTemplateImage(transformed))

        val inside = transformed.getPixel(8, 8)
        val outside = transformed.getPixel(1, 1)
        assertEquals(0, inside.r)
        assertEquals(0, inside.g)
        assertEquals(0, inside.b)
        assertEquals(220, inside.a)
        assertEquals(0, outside.a)
    }

    @Test
    fun `punches out bright interior details from multicolor icon with dark strokes`() {
        val tv = ImageBuffer(24, 24, PixelFormat.RGBA_8888)
        tv.fill(RgbaColor.TRANSPARENT)
        for (y in 3 until 21) {
            for (x in 3 until 21) {
                val isBorder = x < 6 || x >= 18 || y < 6 || y >= 18
                tv.setPixel(
                    x,
                    y,
                    if (isBorder) RgbaColor(0, 0, 0, 255) else RgbaColor(190, 218, 255, 255)
                )
            }
        }
        // Bright play triangle center
        for (y in 10 until 14) {
            for (x in 10 until 14) {
                tv.setPixel(x, y, RgbaColor(255, 193, 53, 255))
            }
        }

        val transformed = DesktopSystemTraySilhouetteTransformer.toMacosTemplateSilhouette(tv)
        assertTrue(DesktopSystemTrayTemplateAnalysis.shouldTreatAsTemplateImage(transformed))

        // Structural frame preserved as opaque black silhouette
        val framePixel = transformed.getPixel(4, 12)
        assertEquals(255, framePixel.a)
        assertEquals(0, framePixel.r)

        // Bright screen and interior detail punched out cleanly
        val screenPixel = transformed.getPixel(8, 8)
        assertEquals(0, screenPixel.a)

        // Exterior remains transparent
        val exteriorPixel = transformed.getPixel(0, 0)
        assertEquals(0, exteriorPixel.a)
    }

    @Test
    fun `removes opaque light outer background while keeping dark foreground`() {
        val badge = ImageBuffer(16, 16, PixelFormat.RGBA_8888)
        badge.fill(RgbaColor(245, 245, 245, 255))
        for (y in 5 until 11) {
            for (x in 5 until 11) {
                badge.setPixel(x, y, RgbaColor(24, 24, 28, 255))
            }
        }

        val transformed = DesktopSystemTraySilhouetteTransformer.toMacosTemplateSilhouette(badge)
        assertTrue(DesktopSystemTrayTemplateAnalysis.shouldTreatAsTemplateImage(transformed))
        assertEquals(0, transformed.getPixel(1, 1).a)
        assertEquals(255, transformed.getPixel(8, 8).a)
    }

    @Test
    fun `removes opaque dark outer background while keeping light foreground`() {
        val badge = ImageBuffer(16, 16, PixelFormat.RGBA_8888)
        badge.fill(RgbaColor(16, 18, 24, 255))
        for (y in 5 until 11) {
            for (x in 5 until 11) {
                badge.setPixel(x, y, RgbaColor(240, 244, 255, 255))
            }
        }

        val transformed = DesktopSystemTraySilhouetteTransformer.toMacosTemplateSilhouette(badge)
        assertTrue(DesktopSystemTrayTemplateAnalysis.shouldTreatAsTemplateImage(transformed))
        assertEquals(0, transformed.getPixel(1, 1).a)
        assertEquals(255, transformed.getPixel(8, 8).a)
    }

    @Test
    fun `preserves soft outer antialiased alpha pixels instead of clipping to zero`() {
        val mono = ImageBuffer(8, 8, PixelFormat.RGBA_8888)
        mono.fill(RgbaColor.TRANSPARENT)
        mono.setPixel(3, 3, RgbaColor(0, 0, 0, 255))
        mono.setPixel(2, 3, RgbaColor(0, 0, 0, 10))

        val monoTransformed = DesktopSystemTraySilhouetteTransformer.toMacosTemplateSilhouette(mono)
        assertEquals(10, monoTransformed.getPixel(2, 3).a)
        assertEquals(255, monoTransformed.getPixel(3, 3).a)

        // Bimodal icon with dark frame, bright center, and faint outer fringe pixel (a = 10)
        val bimodal = ImageBuffer(16, 16, PixelFormat.RGBA_8888)
        bimodal.fill(RgbaColor.TRANSPARENT)
        for (y in 2 until 14) {
            for (x in 2 until 14) {
                val isBorder = x < 5 || x >= 11 || y < 5 || y >= 11
                bimodal.setPixel(
                    x,
                    y,
                    if (isBorder) RgbaColor(0, 0, 0, 255) else RgbaColor(210, 225, 255, 255)
                )
            }
        }
        bimodal.setPixel(1, 5, RgbaColor(0, 0, 0, 10))

        val bimodalTransformed = DesktopSystemTraySilhouetteTransformer.toMacosTemplateSilhouette(bimodal)
        assertEquals(10, bimodalTransformed.getPixel(1, 5).a)
        assertEquals(0, bimodalTransformed.getPixel(7, 7).a)
    }

    @Test
    fun `preserves smooth intermediate alphas across antialiased luminance edges`() {
        val icon = ImageBuffer(24, 24, PixelFormat.RGBA_8888)
        icon.fill(RgbaColor.TRANSPARENT)
        for (y in 2 until 22) {
            for (x in 2 until 22) {
                val isBorder = x < 6 || x >= 18 || y < 6 || y >= 18
                icon.setPixel(
                    x,
                    y,
                    if (isBorder) RgbaColor(0, 0, 0, 255) else RgbaColor(205, 215, 225, 255)
                )
            }
        }
        // Intermediate antialiased pixels at inner edge between lum=0 and lum=213
        icon.setPixel(6, 12, RgbaColor(82, 86, 90, 255))
        icon.setPixel(7, 12, RgbaColor(124, 130, 136, 255))

        val transformed = DesktopSystemTraySilhouetteTransformer.toMacosTemplateSilhouette(icon)
        val edgeA1 = transformed.getPixel(6, 12).a
        val edgeA2 = transformed.getPixel(7, 12).a
        assertTrue(edgeA1 in 1..254, "Expected smooth intermediate alpha for lum~85, got $edgeA1")
        assertTrue(edgeA2 in 1..254, "Expected smooth intermediate alpha for lum~129, got $edgeA2")
        assertTrue(edgeA1 > edgeA2, "Darker antialiased edge ($edgeA1) should retain higher alpha than lighter edge ($edgeA2)")
    }

    @Test
    fun `downsampleAreaBox produces smooth antialiased 36x36 and 18x18 silhouettes from 144x144`() {
        val srcSize = 144
        val hiRes = ImageBuffer(srcSize, srcSize, PixelFormat.RGBA_8888)
        hiRes.fill(RgbaColor.TRANSPARENT)
        val center = (srcSize - 1) / 2.0
        val outerR2 = 56.0 * 56.0
        val innerR2 = 36.0 * 36.0
        for (y in 0 until srcSize) {
            for (x in 0 until srcSize) {
                val dx = x - center
                val dy = y - center
                val d2 = dx * dx + dy * dy
                if (d2 <= outerR2 && d2 >= innerR2) {
                    hiRes.setPixel(x, y, RgbaColor(0, 0, 0, 255))
                }
            }
        }

        val out36 = DesktopSystemTraySilhouetteTransformer.downsampleAreaBox(hiRes, 36, 36)
        assertEquals(36, out36.width)
        assertEquals(36, out36.height)
        assertEquals(0, out36.getPixel(0, 0).a)
        assertEquals(0, out36.getPixel(18, 18).a)
        assertEquals(255, out36.getPixel(18, 6).a)
        var semi36 = 0
        for (y in 0 until 36) {
            for (x in 0 until 36) {
                val a = out36.getPixel(x, y).a
                if (a in 1..254) semi36++
            }
        }
        assertTrue(semi36 > 16, "Expected smooth antialiased rim pixels in 36x36 output, got $semi36")

        val out18 = DesktopSystemTraySilhouetteTransformer.downsampleAreaBox(hiRes, 18, 18)
        assertEquals(18, out18.width)
        assertEquals(18, out18.height)
        assertEquals(0, out18.getPixel(0, 0).a)
        assertEquals(0, out18.getPixel(9, 9).a)
        assertEquals(255, out18.getPixel(9, 3).a)
        var semi18 = 0
        for (y in 0 until 18) {
            for (x in 0 until 18) {
                val a = out18.getPixel(x, y).a
                if (a in 1..254) semi18++
            }
        }
        assertTrue(semi18 > 8, "Expected smooth antialiased rim pixels in 18x18 output, got $semi18")
    }
}
