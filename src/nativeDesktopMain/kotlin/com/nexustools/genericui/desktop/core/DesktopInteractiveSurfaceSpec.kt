package com.nexustools.genericui.desktop.core

import com.nexustools.genericui.enums.ui.ObjectFit
import kotlin.math.roundToInt

/**
 * Generic pixel/hardware surface descriptor attached to a [DesktopNativeWidget] by surface-providing
 * extensions (`:extensions:media`, `:extensions:canvas2d`, `:extensions:canvas3d`) so desktop backends
 * can composite and scale live surfaces without referencing extension-specific types or tag names.
 */
class DesktopInteractiveSurfaceSpec(
    val surfaceWidth: () -> Int,
    val surfaceHeight: () -> Int,
    val swapCount: () -> Long,
    val acquireBgraPtr: (() -> Pair<Any?, Int>)? = null,
    val captureBgra: (() -> ByteArray)? = null,
    val captureRgba: (() -> ByteArray)? = null,
    val resizeSurface: ((Int, Int) -> Unit)? = null,
    val preferHardwareStretchBlit: Boolean = false,
    val suppressDefaultBorder: Boolean = false,
    val defaultAspectRatio: Float? = null,
    val isActiveStream: () -> Boolean = { false },
    val activeCaptionText: () -> String? = { null },
    val onBeforePresentFrame: ((frameSwapCount: Long, timestampMs: Double?) -> Unit)? = null,
    val preferredFrameRateHz: (() -> Double?)? = null,
    val contentFitMode: (() -> ObjectFit)? = null,
    val contentAspectRatio: (() -> Float?)? = null
) {
    /**
     * Resolved presentation geometry in container coordinates alongside clipped integer
     * destination bounds and matching source crop coordinates.
     */
    data class PresentationRect(
        val dstX: Double,
        val dstY: Double,
        val dstW: Double,
        val dstH: Double,
        val srcX: Int,
        val srcY: Int,
        val srcW: Int,
        val srcH: Int,
        val clippedDstX: Int,
        val clippedDstY: Int,
        val clippedDstW: Int,
        val clippedDstH: Int
    )

    private val beforePresentHooks = mutableListOf<(Long, Double?) -> Unit>()
    private var lastDispatchedBeforePresentSwap: Long = -1L

    fun resolveContentFitMode(): ObjectFit {
        contentFitMode?.invoke()?.let { return it }
        if (defaultAspectRatio != null && defaultAspectRatio.isFinite() && defaultAspectRatio > 0.01f) {
            return ObjectFit.CONTAIN
        }
        return if (preferHardwareStretchBlit) ObjectFit.FILL else ObjectFit.CONTAIN
    }

    fun resolveContentAspectRatio(srcW: Int, srcH: Int): Float {
        val dynamic = contentAspectRatio?.invoke()
        if (dynamic != null && dynamic.isFinite() && dynamic > 0.01f) {
            return dynamic
        }
        if (defaultAspectRatio != null && defaultAspectRatio.isFinite() && defaultAspectRatio > 0.01f) {
            return defaultAspectRatio
        }
        return srcW.coerceAtLeast(1).toFloat() / srcH.coerceAtLeast(1).toFloat()
    }

    fun shouldPaintBlackBackdrop(): Boolean =
        suppressDefaultBorder || defaultAspectRatio != null || contentAspectRatio != null

    fun registerBeforePresentHook(hook: (frameSwapCount: Long, timestampMs: Double?) -> Unit) {
        if (hook !in beforePresentHooks) {
            beforePresentHooks.add(hook)
        }
    }

    fun unregisterBeforePresentHook(hook: (frameSwapCount: Long, timestampMs: Double?) -> Unit) {
        beforePresentHooks.remove(hook)
    }

    fun dispatchBeforePresentFrame(frameSwapCount: Long, timestampMs: Double? = null) {
        if (frameSwapCount < 0L || frameSwapCount == lastDispatchedBeforePresentSwap) return
        lastDispatchedBeforePresentSwap = frameSwapCount
        onBeforePresentFrame?.invoke(frameSwapCount, timestampMs)
        if (beforePresentHooks.isNotEmpty()) {
            for (hook in beforePresentHooks.toList()) {
                hook(frameSwapCount, timestampMs)
            }
        }
    }

    fun attachTo(widget: DesktopNativeWidget) {
        widget.interactiveSurfaceSpec = this
        widget.isIsolatedStageSubtreeRoot = true
        widget.attributes["surfaceWidth"] = surfaceWidth
        widget.attributes["surfaceHeight"] = surfaceHeight
        widget.attributes["swapCount"] = swapCount
        widget.attributes["preferHardwareStretchBlit"] = preferHardwareStretchBlit
        widget.attributes["suppressDefaultBorder"] = suppressDefaultBorder
        widget.attributes["onBeforePresentFrame"] = { sc: Long, ts: Double? -> dispatchBeforePresentFrame(sc, ts) }
        if (defaultAspectRatio != null) {
            widget.attributes["defaultAspectRatio"] = defaultAspectRatio
        }
        if (acquireBgraPtr != null) {
            widget.attributes["captureBgraPtr"] = acquireBgraPtr
        }
        if (captureBgra != null) {
            widget.attributes["captureBgra"] = captureBgra
        }
        if (captureRgba != null) {
            widget.attributes["captureRgba"] = captureRgba
        }
        if (resizeSurface != null) {
            widget.attributes["resizeSurface"] = resizeSurface
        }
        if (contentFitMode != null) {
            widget.attributes["contentFitMode"] = contentFitMode
        }
        if (contentAspectRatio != null) {
            widget.attributes["contentAspectRatio"] = contentAspectRatio
        }
    }

    companion object {
        fun computePresentationRect(
            containerW: Double,
            containerH: Double,
            srcW: Int,
            srcH: Int,
            spec: DesktopInteractiveSurfaceSpec
        ): PresentationRect = computePresentationRect(
            containerW = containerW,
            containerH = containerH,
            srcW = srcW,
            srcH = srcH,
            aspectRatio = spec.resolveContentAspectRatio(srcW, srcH),
            fitMode = spec.resolveContentFitMode()
        )

        fun computePresentationRect(
            containerW: Double,
            containerH: Double,
            srcW: Int,
            srcH: Int,
            aspectRatio: Float,
            fitMode: ObjectFit
        ): PresentationRect {
            val cw = containerW.coerceAtLeast(0.0)
            val ch = containerH.coerceAtLeast(0.0)
            val sw = srcW.coerceAtLeast(1)
            val sh = srcH.coerceAtLeast(1)
            val ratio = if (aspectRatio.isFinite() && aspectRatio > 0.01f) {
                aspectRatio.toDouble()
            } else {
                sw.toDouble() / sh.toDouble()
            }
            if (cw <= 0.0 || ch <= 0.0) {
                return PresentationRect(0.0, 0.0, 0.0, 0.0, 0, 0, sw, sh, 0, 0, 0, 0)
            }

            val effectiveFit = when (fitMode) {
                ObjectFit.SCALE_DOWN -> if (sw <= cw && sh <= ch) ObjectFit.NONE else ObjectFit.CONTAIN
                else -> fitMode
            }

            val (dstX, dstY, dstW, dstH) = when (effectiveFit) {
                ObjectFit.FILL -> listOf(0.0, 0.0, cw, ch)
                ObjectFit.CONTAIN -> {
                    val (dw, dh) = if ((cw / ch) > ratio) (ch * ratio) to ch else cw to (cw / ratio)
                    listOf((cw - dw) * 0.5, (ch - dh) * 0.5, dw, dh)
                }
                ObjectFit.COVER -> {
                    val (dw, dh) = if ((cw / ch) > ratio) cw to (cw / ratio) else (ch * ratio) to ch
                    listOf((cw - dw) * 0.5, (ch - dh) * 0.5, dw, dh)
                }
                ObjectFit.NONE, ObjectFit.SCALE_DOWN -> {
                    val dw = sw.toDouble()
                    val dh = sh.toDouble()
                    listOf((cw - dw) * 0.5, (ch - dh) * 0.5, dw, dh)
                }
            }

            val maxCw = cw.toInt().coerceAtLeast(0)
            val maxCh = ch.toInt().coerceAtLeast(0)
            val left = maxOf(0.0, dstX)
            val top = maxOf(0.0, dstY)
            val right = minOf(cw, dstX + dstW)
            val bottom = minOf(ch, dstY + dstH)

            val clippedLeft = left.roundToInt().coerceIn(0, maxCw)
            val clippedTop = top.roundToInt().coerceIn(0, maxCh)
            val clippedRight = right.roundToInt().coerceIn(clippedLeft, maxCw)
            val clippedBottom = bottom.roundToInt().coerceIn(clippedTop, maxCh)
            val clippedDstW = clippedRight - clippedLeft
            val clippedDstH = clippedBottom - clippedTop

            if (dstW <= 0.0 || dstH <= 0.0 || clippedDstW <= 0 || clippedDstH <= 0) {
                return PresentationRect(dstX, dstY, dstW, dstH, 0, 0, sw, sh, 0, 0, 0, 0)
            }

            val u0 = ((clippedLeft - dstX) / dstW).coerceIn(0.0, 1.0)
            val u1 = ((clippedRight - dstX) / dstW).coerceIn(u0, 1.0)
            val v0 = ((clippedTop - dstY) / dstH).coerceIn(0.0, 1.0)
            val v1 = ((clippedBottom - dstY) / dstH).coerceIn(v0, 1.0)

            val cropSrcX = (u0 * sw).roundToInt().coerceIn(0, sw - 1)
            val cropSrcY = (v0 * sh).roundToInt().coerceIn(0, sh - 1)
            val cropSrcEndX = (u1 * sw).roundToInt().coerceIn(cropSrcX + 1, sw)
            val cropSrcEndY = (v1 * sh).roundToInt().coerceIn(cropSrcY + 1, sh)

            return PresentationRect(
                dstX = dstX,
                dstY = dstY,
                dstW = dstW,
                dstH = dstH,
                srcX = cropSrcX,
                srcY = cropSrcY,
                srcW = (cropSrcEndX - cropSrcX).coerceAtLeast(1),
                srcH = (cropSrcEndY - cropSrcY).coerceAtLeast(1),
                clippedDstX = clippedLeft,
                clippedDstY = clippedTop,
                clippedDstW = clippedDstW,
                clippedDstH = clippedDstH
            )
        }

        fun resolveBgraRowStrideBytes(width: Int, height: Int, reportedSizeOrStride: Int): Int {
            val minStride = width.coerceAtLeast(1) * 4
            if (reportedSizeOrStride <= 0) return minStride
            val h = height.coerceAtLeast(1)
            val minTotalBytes = minStride.toLong() * h.toLong()
            return if (h > 1 && reportedSizeOrStride.toLong() >= minTotalBytes && reportedSizeOrStride % h == 0) {
                reportedSizeOrStride / h
            } else {
                reportedSizeOrStride.coerceAtLeast(minStride)
            }
        }
    }
}
