@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.nexustools.genericui.linux.style

import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.FastBackdropBlurKernel
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.plus
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import kotlin.concurrent.atomics.AtomicReference
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Pooled, zero-steady-state-allocation pre-multiplied `BGRA_8888` (`0xAARRGGBB` little-endian
 * words used by GTK4 Cairo `CAIRO_FORMAT_ARGB32` and KDE `QImage::Format_ARGB32_Premultiplied`)
 * backdrop filter processor shared across Linux backends.
 *
 * Delegates adaptive `1x`/`1/2`/`1/4` Gaussian-approximated downsampled blurring, rounded-corner
 * alpha masking, component opacity blending, and all 10 [CssFilter] operations (`blur`, `brightness`,
 * `contrast`, `grayscale`, `hue-rotate`, `invert`, `opacity`, `saturate`, `sepia`, `drop-shadow`)
 * to [FastBackdropBlurKernel.applyBackdropToPremulBgraRegion].
 *
 * Strictly toolkit-agnostic: zero imports from `gtk` or `kde`.
 */
class LinuxBackdropFilterController {

    fun hasActiveEffects(filter: CssFilter?): Boolean =
        filter != null && !filter.isNone && filter.hasActiveEffects

    /**
     * Applies [filter] directly in-place onto a pre-multiplied `BGRA_8888` [IntArray] framebuffer
     * with zero steady-state allocations.
     */
    fun applyToPremulBgraBuffer(
        filter: CssFilter,
        pixels: IntArray,
        strideWords: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        regionX: Int,
        regionY: Int,
        regionWidth: Int,
        regionHeight: Int,
        cornerRadiusPx: Float = 0f,
        componentOpacity: Float = 1f
    ): Boolean {
        if (!hasActiveEffects(filter)) return false
        return FastBackdropBlurKernel.applyBackdropToPremulBgraRegion(
            filter = filter,
            pixels = pixels,
            strideWords = strideWords,
            bufferWidth = bufferWidth,
            bufferHeight = bufferHeight,
            regionX = regionX,
            regionY = regionY,
            regionWidth = regionWidth,
            regionHeight = regionHeight,
            cornerRadiusPx = cornerRadiusPx,
            componentOpacity = componentOpacity
        )
    }

    /**
     * Applies [filter] in-place onto a native pre-multiplied `BGRA_8888` pixel buffer pointed to by
     * [dataPtr] (with [strideBytes] bytes per row) using a pooled scratch [IntArray] sized strictly
     * to the padded capture neighborhood (`0` steady-state allocations).
     */
    fun applyToPremulBgraPointer(
        filter: CssFilter,
        dataPtr: CPointer<ByteVar>,
        strideBytes: Int,
        bufferWidth: Int,
        bufferHeight: Int,
        regionX: Int,
        regionY: Int,
        regionWidth: Int,
        regionHeight: Int,
        cornerRadiusPx: Float = 0f,
        componentOpacity: Float = 1f
    ): Boolean {
        if (!hasActiveEffects(filter)) return false
        val effOpacity = componentOpacity.coerceIn(0f, 1f)
        if (effOpacity <= 0.004f) return false
        if (bufferWidth <= 0 || bufferHeight <= 0 || strideBytes < bufferWidth * 4) return false

        val x0 = regionX.coerceIn(0, bufferWidth)
        val y0 = regionY.coerceIn(0, bufferHeight)
        val x1 = (regionX + regionWidth).coerceIn(0, bufferWidth)
        val y1 = (regionY + regionHeight).coerceIn(0, bufferHeight)
        val clippedW = x1 - x0
        val clippedH = y1 - y0
        if (clippedW <= 0 || clippedH <= 0) return false

        val pads = computeHaloPadding(filter)
        val padLeft = pads[0]
        val padTop = pads[1]
        val padRight = pads[2]
        val padBottom = pads[3]

        val capX0 = (x0 - padLeft).coerceAtLeast(0)
        val capY0 = (y0 - padTop).coerceAtLeast(0)
        val capX1 = (x1 + padRight).coerceAtMost(bufferWidth)
        val capY1 = (y1 + padBottom).coerceAtMost(bufferHeight)
        val capW = capX1 - capX0
        val capH = capY1 - capY0
        if (capW <= 0 || capH <= 0) return false

        val scratch = acquireScratchBuffer(capW * capH)
        try {
            val rowCopyBytes = (capW.toLong() * 4L).convert<platform.posix.size_t>()
            val writeBackBytes = (clippedW.toLong() * 4L).convert<platform.posix.size_t>()
            val capXByteOffset = capX0 * 4
            val x0ByteOffset = x0 * 4
            val localXOffset = x0 - capX0

            scratch.usePinned { pinned ->
                for (py in capY0 until capY1) {
                    val dstOffset = (py - capY0) * capW
                    val srcPtr = dataPtr + (py * strideBytes + capXByteOffset)
                    memcpy(pinned.addressOf(dstOffset), srcPtr, rowCopyBytes)
                }
            }

            val applied = FastBackdropBlurKernel.applyBackdropToPremulBgraRegion(
                filter = filter,
                pixels = scratch,
                strideWords = capW,
                bufferWidth = capW,
                bufferHeight = capH,
                regionX = regionX - capX0,
                regionY = regionY - capY0,
                regionWidth = regionWidth,
                regionHeight = regionHeight,
                cornerRadiusPx = cornerRadiusPx,
                componentOpacity = effOpacity
            )
            if (!applied) return false

            scratch.usePinned { pinned ->
                for (py in y0 until y1) {
                    val srcOffset = (py - capY0) * capW + localXOffset
                    val dstPtr = dataPtr + (py * strideBytes + x0ByteOffset)
                    memcpy(dstPtr, pinned.addressOf(srcOffset), writeBackBytes)
                }
            }
            return true
        } finally {
            releaseScratchBuffer(scratch)
        }
    }

    companion object {
        private const val SCRATCH_POOL_SIZE = 4
        private const val MIN_SCRATCH_WORDS = 4096
        private const val MAX_BLUR_RADIUS_PX = 48f

        val shared: LinuxBackdropFilterController = LinuxBackdropFilterController()

        /**
         * Computes `[padLeft, padTop, padRight, padBottom]` halo capture padding in pixels for
         * [filter], accounting for `blur * 1.5f` and `drop-shadow` offset + blur halo + spread.
         */
        fun computeHaloPadding(filter: CssFilter?): IntArray {
            if (filter == null || filter.isNone || !filter.hasActiveEffects) return intArrayOf(0, 0, 0, 0)
            val blurPx = if (filter.hasBlur) (filter.blur?.resolvePx() ?: 0f).coerceIn(0f, MAX_BLUR_RADIUS_PX) else 0f
            val blurPad = if (blurPx > 0f) ceil(blurPx * 1.5f).toInt().coerceIn(1, 72) else 0
            val shadow = filter.dropShadow?.takeIf { filter.hasDropShadow }
            val shadowBlurPx = if (shadow != null) (shadow.blurRadius.resolvePx() ?: 0f).coerceIn(0f, MAX_BLUR_RADIUS_PX) else 0f
            val shadowOffX = shadow?.offsetX?.resolvePx() ?: 0f
            val shadowOffY = shadow?.offsetY?.resolvePx() ?: 0f
            val shadowSpread = abs(shadow?.spreadRadius?.resolvePx() ?: 0f)
            val shadowBlurPad = if (shadow != null && shadowBlurPx > 0f) ceil(shadowBlurPx * 1.5f).toInt().coerceIn(1, 72) else 0
            val padLeft = if (shadow != null) max(blurPad, shadowBlurPad + ceil(max(0f, shadowOffX) + shadowSpread).toInt().coerceAtMost(64)) else blurPad
            val padRight = if (shadow != null) max(blurPad, shadowBlurPad + ceil(max(0f, -shadowOffX) + shadowSpread).toInt().coerceAtMost(64)) else blurPad
            val padTop = if (shadow != null) max(blurPad, shadowBlurPad + ceil(max(0f, shadowOffY) + shadowSpread).toInt().coerceAtMost(64)) else blurPad
            val padBottom = if (shadow != null) max(blurPad, shadowBlurPad + ceil(max(0f, -shadowOffY) + shadowSpread).toInt().coerceAtMost(64)) else blurPad
            return intArrayOf(padLeft, padTop, padRight, padBottom)
        }

        private val scratchPool = Array<AtomicReference<IntArray?>>(SCRATCH_POOL_SIZE) {
            AtomicReference(null)
        }

        private fun acquireScratchBuffer(minWords: Int): IntArray {
            var existing: IntArray? = null
            for (i in 0 until SCRATCH_POOL_SIZE) {
                val popped = scratchPool[i].exchange(null)
                if (popped != null) {
                    existing = popped
                    break
                }
            }
            if (existing != null && existing.size >= minWords) {
                return existing
            }
            var cap = max(existing?.size ?: 0, MIN_SCRATCH_WORDS)
            while (cap < minWords) {
                cap = cap shl 1
            }
            return IntArray(cap)
        }

        private fun releaseScratchBuffer(buffer: IntArray) {
            for (i in 0 until SCRATCH_POOL_SIZE) {
                if (scratchPool[i].compareAndSet(null, buffer)) return
            }
        }
    }
}
