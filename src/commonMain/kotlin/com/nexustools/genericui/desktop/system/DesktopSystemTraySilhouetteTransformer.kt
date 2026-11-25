package com.nexustools.genericui.desktop.system

import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.PixelFormat
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Converts arbitrary tray icon artwork into a monochrome `(0, 0, 0, alpha)` template silhouette
 * suitable for macOS menu bar template rendering (`NSImage.setTemplate(true)`) when no
 * dedicated macOS template icon has been specified.
 *
 * Existing monochrome alpha masks are preserved verbatim (re-tinted to black + original alpha).
 * Full-color icons and artwork with opaque outer backgrounds use bimodal Otsu luminance
 * thresholding to punch out bright interior details (screens, badges, glyph voids) and isolate
 * foreground silhouettes on a transparent background.
 */
object DesktopSystemTraySilhouetteTransformer {
    private const val MIN_VISIBLE_ALPHA = 16
    private const val OPAQUE_BORDER_ALPHA = 200
    private const val MIN_FEATHER_HALF_WIDTH = 14f
    private const val MAX_FEATHER_HALF_WIDTH = 44f

    fun toMacosTemplateSilhouette(source: ImageBuffer): ImageBuffer {
        val rgba = source.asRgba8888()
        val width = rgba.width
        val height = rgba.height
        val src = rgba.bytes
        val totalPixels = width * height
        val expectedBytes = totalPixels * 4
        if (src.size < expectedBytes || totalPixels <= 0) {
            return ImageBuffer(width, height, PixelFormat.RGBA_8888, rgba.colorSpace)
        }

        val out = ByteArray(expectedBytes)
        val hist = DoubleArray(256)
        var visibleWeight = 0.0
        var visibleCount = 0
        var transparentCount = 0
        var minLum = 255f
        var maxLum = 0f

        var borderCount = 0
        var opaqueBorderCount = 0
        var borderLumSum = 0.0

        for (y in 0 until height) {
            val isBorderY = y == 0 || y == height - 1
            for (x in 0 until width) {
                val idx = (y * width + x) * 4
                val r = src[idx].toInt() and 0xFF
                val g = src[idx + 1].toInt() and 0xFF
                val b = src[idx + 2].toInt() and 0xFF
                val a = src[idx + 3].toInt() and 0xFF

                if (isBorderY || x == 0 || x == width - 1) {
                    borderCount++
                    if (a >= OPAQUE_BORDER_ALPHA) {
                        opaqueBorderCount++
                        borderLumSum += luminance(r, g, b)
                    }
                }

                if (a <= MIN_VISIBLE_ALPHA) {
                    transparentCount++
                    continue
                }

                visibleCount++
                val lum = luminance(r, g, b)
                if (lum < minLum) minLum = lum
                if (lum > maxLum) maxLum = lum

                val bin = lum.roundToInt().coerceIn(0, 255)
                val w = a / 255.0
                hist[bin] += w
                visibleWeight += w
            }
        }

        if (visibleCount == 0) {
            return ImageBuffer(width, height, PixelFormat.RGBA_8888, rgba.colorSpace, out)
        }

        val lumSpread = maxLum - minLum
        val alreadyTemplateMask = DesktopSystemTrayTemplateAnalysis.shouldTreatAsTemplateImage(rgba)
        if (alreadyTemplateMask && lumSpread <= 48f) {
            copyAlphaMaskAsBlack(src, out, expectedBytes)
            return ImageBuffer(width, height, PixelFormat.RGBA_8888, rgba.colorSpace, out)
        }

        val transparentFraction = transparentCount.toDouble() / totalPixels.toDouble()
        val opaqueBorderFraction = if (borderCount > 0) opaqueBorderCount.toDouble() / borderCount.toDouble() else 0.0
        val hasOpaqueOuterBackground = opaqueBorderFraction >= 0.85 && transparentFraction < 0.05

        // Compute Otsu threshold and bimodal statistics across visible pixels.
        var totalSum = 0.0
        for (bin in 0..255) {
            totalSum += bin * hist[bin]
        }

        var w0 = 0.0
        var sum0 = 0.0
        var bestVar = -1.0
        var bestTStart = 127
        var bestTEnd = 127
        var bestW0 = 0.0
        var bestW1 = 0.0
        var bestMu0 = 0f
        var bestMu1 = 0f

        for (t in 0..254) {
            w0 += hist[t]
            if (w0 <= 0.0) continue
            val w1 = visibleWeight - w0
            if (w1 <= 0.0) break
            sum0 += t * hist[t]
            val mu0 = (sum0 / w0).toFloat()
            val mu1 = ((totalSum - sum0) / w1).toFloat()
            val diff = (mu0 - mu1).toDouble()
            val betweenVar = w0 * w1 * diff * diff
            if (betweenVar > bestVar + 1e-6) {
                bestVar = betweenVar
                bestTStart = t
                bestTEnd = t
                bestW0 = w0
                bestW1 = w1
                bestMu0 = mu0
                bestMu1 = mu1
            } else if (abs(betweenVar - bestVar) <= 1e-6 && t == bestTEnd + 1) {
                bestTEnd = t
            }
        }

        val threshold = (bestTStart + bestTEnd) * 0.5f + 0.5f
        val hasBimodalSeparation = bestVar > 0.0 &&
            (bestMu1 - bestMu0) >= 56f &&
            bestMu1 >= 150f &&
            bestW0 >= visibleWeight * 0.02 &&
            bestW1 >= visibleWeight * 0.02

        val adaptiveFeather = ((bestMu1 - bestMu0) * 0.22f).coerceIn(
            MIN_FEATHER_HALF_WIDTH,
            MAX_FEATHER_HALF_WIDTH
        )

        if (hasOpaqueOuterBackground) {
            val hasOpaqueContrast = bestVar > 0.0 && (bestMu1 - bestMu0) >= 24f
            if (!hasOpaqueContrast) {
                // Uniform opaque image with no extractable foreground/background boundary.
                copyAlphaMaskAsBlack(src, out, expectedBytes)
                return ImageBuffer(width, height, PixelFormat.RGBA_8888, rgba.colorSpace, out)
            }
            val borderMeanLum = if (opaqueBorderCount > 0) (borderLumSum / opaqueBorderCount).toFloat() else 255f
            val keepBrightForeground = borderMeanLum <= threshold
            applyThresholdMask(
                src = src,
                out = out,
                totalBytes = expectedBytes,
                threshold = threshold,
                featherHalfWidth = adaptiveFeather,
                keepBrightForeground = keepBrightForeground
            )
            return ImageBuffer(width, height, PixelFormat.RGBA_8888, rgba.colorSpace, out)
        }

        if (hasBimodalSeparation) {
            applyThresholdMask(
                src = src,
                out = out,
                totalBytes = expectedBytes,
                threshold = threshold,
                featherHalfWidth = adaptiveFeather,
                keepBrightForeground = false
            )
        } else {
            copyAlphaMaskAsBlack(src, out, expectedBytes)
        }

        return ImageBuffer(width, height, PixelFormat.RGBA_8888, rgba.colorSpace, out)
    }

    /**
     * Downsamples [source] to `[targetWidth] x [targetHeight]` using exact fractional
     * box/area averaging in premultiplied RGBA space, then unpremultiplies back to straight
     * `RGBA_8888` so antialiased edges remain smooth without halo artifacts.
     */
    fun downsampleAreaBox(source: ImageBuffer, targetWidth: Int, targetHeight: Int): ImageBuffer {
        require(targetWidth > 0 && targetHeight > 0) {
            "Target dimensions must be positive (${targetWidth}x$targetHeight)"
        }
        val rgba = source.asRgba8888()
        if (rgba.width == targetWidth && rgba.height == targetHeight) {
            return rgba
        }

        val srcW = rgba.width
        val srcH = rgba.height
        val src = rgba.bytes
        val out = ByteArray(targetWidth * targetHeight * 4)
        if (src.size < srcW * srcH * 4 || srcW <= 0 || srcH <= 0) {
            return ImageBuffer(targetWidth, targetHeight, PixelFormat.RGBA_8888, rgba.colorSpace, out)
        }

        val scaleX = srcW.toDouble() / targetWidth.toDouble()
        val scaleY = srcH.toDouble() / targetHeight.toDouble()

        for (dy in 0 until targetHeight) {
            val sy0 = dy * scaleY
            val sy1 = (dy + 1) * scaleY
            val iy0 = floor(sy0).toInt().coerceIn(0, srcH - 1)
            val iy1 = ceil(sy1).toInt().coerceIn(1, srcH)

            for (dx in 0 until targetWidth) {
                val sx0 = dx * scaleX
                val sx1 = (dx + 1) * scaleX
                val ix0 = floor(sx0).toInt().coerceIn(0, srcW - 1)
                val ix1 = ceil(sx1).toInt().coerceIn(1, srcW)

                var sumR = 0.0
                var sumG = 0.0
                var sumB = 0.0
                var sumA = 0.0
                var totalWeight = 0.0

                for (iy in iy0 until iy1) {
                    val wy = (minOf(sy1, (iy + 1).toDouble()) - maxOf(sy0, iy.toDouble())).coerceAtLeast(0.0)
                    if (wy <= 0.0) continue
                    val rowOffset = iy * srcW
                    for (ix in ix0 until ix1) {
                        val wx = (minOf(sx1, (ix + 1).toDouble()) - maxOf(sx0, ix.toDouble())).coerceAtLeast(0.0)
                        if (wx <= 0.0) continue
                        val w = wx * wy
                        val srcIdx = (rowOffset + ix) * 4
                        val a = (src[srcIdx + 3].toInt() and 0xFF) / 255.0
                        if (a > 0.0) {
                            val r = (src[srcIdx].toInt() and 0xFF) / 255.0
                            val g = (src[srcIdx + 1].toInt() and 0xFF) / 255.0
                            val b = (src[srcIdx + 2].toInt() and 0xFF) / 255.0
                            sumR += w * r * a
                            sumG += w * g * a
                            sumB += w * b * a
                            sumA += w * a
                        }
                        totalWeight += w
                    }
                }

                val outIdx = (dy * targetWidth + dx) * 4
                val avgA = if (totalWeight > 0.0) (sumA / totalWeight).coerceIn(0.0, 1.0) else 0.0
                val outA = (avgA * 255.0).roundToInt().coerceIn(0, 255)
                if (outA == 0 || sumA <= 1e-9) {
                    out[outIdx] = 0
                    out[outIdx + 1] = 0
                    out[outIdx + 2] = 0
                    out[outIdx + 3] = 0
                } else {
                    out[outIdx] = ((sumR / sumA) * 255.0).roundToInt().coerceIn(0, 255).toByte()
                    out[outIdx + 1] = ((sumG / sumA) * 255.0).roundToInt().coerceIn(0, 255).toByte()
                    out[outIdx + 2] = ((sumB / sumA) * 255.0).roundToInt().coerceIn(0, 255).toByte()
                    out[outIdx + 3] = outA.toByte()
                }
            }
        }

        return ImageBuffer(targetWidth, targetHeight, PixelFormat.RGBA_8888, rgba.colorSpace, out)
    }

    private fun luminance(r: Int, g: Int, b: Int): Float =
        0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun copyAlphaMaskAsBlack(src: ByteArray, out: ByteArray, totalBytes: Int) {
        var idx = 0
        while (idx + 3 < totalBytes) {
            val a = src[idx + 3].toInt() and 0xFF
            out[idx] = 0
            out[idx + 1] = 0
            out[idx + 2] = 0
            out[idx + 3] = if (a == 0) 0 else a.toByte()
            idx += 4
        }
    }

    private fun applyThresholdMask(
        src: ByteArray,
        out: ByteArray,
        totalBytes: Int,
        threshold: Float,
        featherHalfWidth: Float,
        keepBrightForeground: Boolean
    ) {
        val low = threshold - featherHalfWidth
        val high = threshold + featherHalfWidth
        val span = high - low

        var idx = 0
        while (idx + 3 < totalBytes) {
            val a = src[idx + 3].toInt() and 0xFF
            if (a == 0) {
                out[idx] = 0
                out[idx + 1] = 0
                out[idx + 2] = 0
                out[idx + 3] = 0
                idx += 4
                continue
            }

            val r = src[idx].toInt() and 0xFF
            val g = src[idx + 1].toInt() and 0xFF
            val b = src[idx + 2].toInt() and 0xFF
            val lum = luminance(r, g, b)

            val keepFactor = if (keepBrightForeground) {
                ((lum - low) / span).coerceIn(0f, 1f)
            } else {
                ((high - lum) / span).coerceIn(0f, 1f)
            }

            val outAlpha = (a * keepFactor).roundToInt().coerceIn(0, 255)
            out[idx] = 0
            out[idx + 1] = 0
            out[idx + 2] = 0
            out[idx + 3] = outAlpha.toByte()
            idx += 4
        }
    }
}
