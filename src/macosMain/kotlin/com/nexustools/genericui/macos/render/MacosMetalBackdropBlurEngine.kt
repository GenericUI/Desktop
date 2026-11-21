@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.render

import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.FastBackdropBlurKernel
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.interpretObjCPointer
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIFilter
import platform.CoreImage.CIImage
import platform.CoreImage.filterWithName
import platform.CoreImage.kCIFormatBGRA8
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Foundation.NSRecursiveLock
import platform.Foundation.dataWithBytes
import platform.Foundation.numberWithDouble
import platform.Foundation.setValue
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLDeviceProtocol
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Hardware Metal-backed Core Image (`MTLCreateSystemDefaultDevice` + `CIContext.contextWithMTLDevice` +
 * clamped `CIGaussianBlur` + `CIColorControls`) backdrop blur & CSS filter engine for macOS,
 * falling back to [FastBackdropBlurKernel] cleanly when Metal / CoreImage GPU execution is unavailable.
 */
object MacosMetalBackdropBlurEngine {

    private val lock = NSRecursiveLock()
    private inline fun <T> withEngineLock(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
    private var initAttempted = false
    private var metalDevice: MTLDeviceProtocol? = null
    private var metalCiContext: CIContext? = null
    private var rgbColorSpace: CGColorSpaceRef? = null

    private var scratchInputArgb = IntArray(0)
    private var scratchOutputArgb = IntArray(0)

    fun isMetalGpuActive(): Boolean = withEngineLock {
        ensureMetalContext() != null
    }

    internal fun obtainScratchInputBuffer(minSize: Int): IntArray {
        if (scratchInputArgb.size < minSize) {
            var cap = max(4096, scratchInputArgb.size)
            while (cap < minSize) cap = cap shl 1
            scratchInputArgb = IntArray(cap)
        }
        return scratchInputArgb
    }

    /**
     * Applies [filter] onto region `(regionX, regionY, regionWidth, regionHeight)` of [argbPixels]
     * (`bufferWidth x bufferHeight` top-down `0xAARRGGBB` little-endian BGRA bytes), optionally masked
     * by [cornerRadiusPx]. Uses Metal `CIContext` (`CIGaussianBlur` + `CIColorControls`) first and
     * falls back cleanly to [FastBackdropBlurKernel].
     */
    fun applyBackdropToArgbRegion(
        filter: CssFilter,
        argbPixels: IntArray,
        bufferWidth: Int,
        bufferHeight: Int,
        regionX: Int,
        regionY: Int,
        regionWidth: Int,
        regionHeight: Int,
        cornerRadiusPx: Float = 0f
    ): Boolean {
        if (!filter.hasActiveEffects || bufferWidth <= 0 || bufferHeight <= 0) return false
        if (argbPixels.size < bufferWidth * bufferHeight) return false

        val canUseSimpleMetalOps =
            filter.hueRotateDeg == null &&
                filter.sepia == null &&
                filter.invert == null

        val handledGpu = if (canUseSimpleMetalOps) {
            withEngineLock {
                tryApplyViaMetalCoreImage(
                    filter = filter,
                    argbPixels = argbPixels,
                    bufferWidth = bufferWidth,
                    bufferHeight = bufferHeight,
                    regionX = regionX,
                    regionY = regionY,
                    regionWidth = regionWidth,
                    regionHeight = regionHeight,
                    cornerRadiusPx = cornerRadiusPx
                )
            }
        } else {
            false
        }
        if (handledGpu) return true

        return FastBackdropBlurKernel.applyBackdropToArgbRegion(
            filter = filter,
            argbPixels = argbPixels,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            regionX = regionX,
            regionY = regionY,
            regionWidth = regionWidth,
            regionHeight = regionHeight,
            cornerRadiusPx = cornerRadiusPx
        )
    }

    private fun ensureMetalContext(): CIContext? {
        if (initAttempted) return metalCiContext
        initAttempted = true
        val cs = CGColorSpaceCreateDeviceRGB()
        rgbColorSpace = cs
        val dev = MTLCreateSystemDefaultDevice()
        metalDevice = dev
        metalCiContext = if (dev != null) {
            CIContext.contextWithMTLDevice(interpretObjCPointer(dev.objcPtr()))
        } else {
            CIContext.context()
        }
        return metalCiContext
    }

    private fun tryApplyViaMetalCoreImage(
        filter: CssFilter,
        argbPixels: IntArray,
        bufferWidth: Int,
        bufferHeight: Int,
        regionX: Int,
        regionY: Int,
        regionWidth: Int,
        regionHeight: Int,
        cornerRadiusPx: Float
    ): Boolean {
        val ciCtx = ensureMetalContext() ?: return false
        val cs = rgbColorSpace ?: return false

        val x0 = regionX.coerceIn(0, bufferWidth)
        val y0 = regionY.coerceIn(0, bufferHeight)
        val x1 = (regionX + regionWidth).coerceIn(0, bufferWidth)
        val y1 = (regionY + regionHeight).coerceIn(0, bufferHeight)
        if (x1 <= x0 || y1 <= y0) return false

        val totalPixels = bufferWidth * bufferHeight
        if (scratchOutputArgb.size < totalPixels) {
            var cap = max(4096, scratchOutputArgb.size)
            while (cap < totalPixels) cap = cap shl 1
            scratchOutputArgb = IntArray(cap)
        }

        val rowBytes = bufferWidth * 4
        val extentRect = CGRectMake(0.0, 0.0, bufferWidth.toDouble(), bufferHeight.toDouble())

        val bitmapData = argbPixels.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), (totalPixels * 4).convert())
        }
        var currentImage: CIImage = CIImage.imageWithBitmapData(
            data = bitmapData,
            bytesPerRow = rowBytes.convert(),
            size = CGSizeMake(bufferWidth.toDouble(), bufferHeight.toDouble()),
            format = kCIFormatBGRA8,
            colorSpace = cs
        )

        val blurPx = if (filter.hasBlur) (filter.blur?.resolvePx() ?: 0f).coerceIn(0f, 48f) else 0f
        if (blurPx > 0f) {
            val blurFilter = CIFilter.filterWithName("CIGaussianBlur") ?: return false
            blurFilter.setValue(currentImage.imageByClampingToExtent(), forKey = "inputImage")
            blurFilter.setValue(NSNumber.numberWithDouble(blurPx.toDouble()), forKey = "inputRadius")
            val blurred = blurFilter.outputImage?.imageByCroppingToRect(extentRect) ?: return false
            currentImage = blurred
        }

        val effectiveSat = (filter.saturate ?: 1f) * (1f - (filter.grayscale ?: 0f).coerceIn(0f, 1f))
        val effectiveContrast = filter.contrast ?: 1f
        val effectiveBrightness = (filter.brightness ?: 1f) - 1f
        if (effectiveSat != 1f || effectiveContrast != 1f || effectiveBrightness != 0f) {
            val controlsFilter = CIFilter.filterWithName("CIColorControls") ?: return false
            controlsFilter.setValue(currentImage, forKey = "inputImage")
            controlsFilter.setValue(NSNumber.numberWithDouble(effectiveSat.toDouble()), forKey = "inputSaturation")
            controlsFilter.setValue(NSNumber.numberWithDouble(effectiveContrast.toDouble()), forKey = "inputContrast")
            controlsFilter.setValue(NSNumber.numberWithDouble(effectiveBrightness.toDouble()), forKey = "inputBrightness")
            val adjusted = controlsFilter.outputImage?.imageByCroppingToRect(extentRect) ?: return false
            currentImage = adjusted
        }

        val outBuf = scratchOutputArgb
        outBuf.usePinned { pinned ->
            ciCtx.render(
                image = currentImage,
                toBitmap = pinned.addressOf(0),
                rowBytes = rowBytes.convert(),
                bounds = extentRect,
                format = kCIFormatBGRA8,
                colorSpace = cs
            )
        }

        val effectiveRadius = cornerRadiusPx.coerceIn(0f, min(regionWidth, regionHeight) * 0.5f)
        val checkRounded = effectiveRadius > 0.5f
        val opacity = (filter.opacity ?: 1f).coerceIn(0f, 1f)
        val scaleAlpha = opacity < 1f

        for (py in y0 until y1) {
            val rowOff = py * bufferWidth
            val localY = (py - regionY).toFloat()
            for (px in x0 until x1) {
                if (checkRounded && !isPointInsideRoundedRect(
                        x = (px - regionX).toFloat(),
                        y = localY,
                        width = regionWidth.toFloat(),
                        height = regionHeight.toFloat(),
                        radius = effectiveRadius
                    )
                ) {
                    continue
                }
                val word = outBuf[rowOff + px]
                if (!scaleAlpha) {
                    argbPixels[rowOff + px] = word
                } else {
                    val oa = (((word ushr 24) and 0xFF) * opacity).roundToInt().coerceIn(0, 255)
                    argbPixels[rowOff + px] = (oa shl 24) or (word and 0x00FFFFFF)
                }
            }
        }
        return true
    }

    private fun isPointInsideRoundedRect(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        radius: Float
    ): Boolean {
        val px = x + 0.5f
        val py = y + 0.5f
        val cx = when {
            px < radius -> radius
            px > width - radius -> width - radius
            else -> return true
        }
        val cy = when {
            py < radius -> radius
            py > height - radius -> height - radius
            else -> return true
        }
        val dx = px - cx
        val dy = py - cy
        return (dx * dx + dy * dy) <= (radius * radius)
    }
}
