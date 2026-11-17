@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.qt.render

import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.enums.ui.ObjectFit
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.MonocolorImageRecolorProcessor
import com.nexustools.genericui.linux.LinuxReentrantLock
import com.nexustools.genericui.qt.Qt6DynamicBindings
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.resources.ResourceID
import com.nexustools.genericui.style.color.ImageTintMode
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object QtGraphicsDrawUtil {
    var onAsyncAssetLoaded: (() -> Unit)? = null

    private val lock = LinuxReentrantLock()
    private val imageCache = LinkedHashMap<String, ImageBuffer>(128)

    fun pushClip(ctx: QtRenderContext, x: Int, y: Int, w: Int, h: Int): Boolean =
        ctx.pushClipBounds(x, y, w, h)

    fun popClip(ctx: QtRenderContext) {
        ctx.popClipBounds()
    }

    fun fillRect(ctx: QtRenderContext, x: Int, y: Int, w: Int, h: Int, color: RgbaColor) {
        if (w <= 0 || h <= 0 || color.a <= 0) return
        val effectiveAlpha = ((color.a / 255f) * ctx.currentOpacity).coerceIn(0f, 1f)
        if (effectiveAlpha <= 0.002f) return
        val l = max(x, ctx.clipLeft).coerceAtLeast(0)
        val t = max(y, ctx.clipTop).coerceAtLeast(0)
        val r = min(x + w, ctx.clipRight).coerceAtMost(ctx.bufferWidth)
        val b = min(y + h, ctx.clipBottom).coerceAtMost(ctx.bufferHeight)
        if (r <= l || b <= t) return

        val pixels = ctx.argbPixels
        if (effectiveAlpha >= 0.996f) {
            val solid = (255 shl 24) or (color.r shl 16) or (color.g shl 8) or color.b
            for (py in t until b) {
                val row = py * ctx.bufferWidth
                for (px in l until r) {
                    pixels[row + px] = solid
                }
            }
        } else {
            for (py in t until b) {
                val row = py * ctx.bufferWidth
                for (px in l until r) {
                    pixels[row + px] = blendOver(pixels[row + px], color.r, color.g, color.b, effectiveAlpha)
                }
            }
        }
    }

    fun fillRoundedRect(
        ctx: QtRenderContext,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        radius: Float,
        color: RgbaColor
    ) {
        if (w <= 0 || h <= 0 || color.a <= 0) return
        if (radius <= 0.5f) {
            fillRect(ctx, x, y, w, h, color)
            return
        }
        val effectiveAlpha = ((color.a / 255f) * ctx.currentOpacity).coerceIn(0f, 1f)
        if (effectiveAlpha <= 0.002f) return
        val l = max(x, ctx.clipLeft).coerceAtLeast(0)
        val t = max(y, ctx.clipTop).coerceAtLeast(0)
        val r = min(x + w, ctx.clipRight).coerceAtMost(ctx.bufferWidth)
        val b = min(y + h, ctx.clipBottom).coerceAtMost(ctx.bufferHeight)
        if (r <= l || b <= t) return
        val maxR = min(radius, min(w, h) * 0.5f)
        val pixels = ctx.argbPixels
        for (py in t until b) {
            val row = py * ctx.bufferWidth
            val ly = (py - y) + 0.5f
            for (px in l until r) {
                val lx = (px - x) + 0.5f
                val cov = roundedCoverage(lx, ly, w.toFloat(), h.toFloat(), maxR)
                if (cov > 0.002f) {
                    pixels[row + px] = blendOver(pixels[row + px], color.r, color.g, color.b, effectiveAlpha * cov)
                }
            }
        }
    }

    fun strokeRoundedRect(
        ctx: QtRenderContext,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        radius: Float,
        lineWidth: Float,
        color: RgbaColor
    ) {
        if (w <= 0 || h <= 0 || lineWidth <= 0f || color.a <= 0) return
        val effectiveAlpha = ((color.a / 255f) * ctx.currentOpacity).coerceIn(0f, 1f)
        if (effectiveAlpha <= 0.002f) return
        val l = max(x, ctx.clipLeft).coerceAtLeast(0)
        val t = max(y, ctx.clipTop).coerceAtLeast(0)
        val r = min(x + w, ctx.clipRight).coerceAtMost(ctx.bufferWidth)
        val b = min(y + h, ctx.clipBottom).coerceAtMost(ctx.bufferHeight)
        if (r <= l || b <= t) return

        val outerR = min(radius.coerceAtLeast(0f), min(w, h) * 0.5f)
        val innerR = (outerR - lineWidth).coerceAtLeast(0f)
        val iw = (w - lineWidth * 2f).coerceAtLeast(0f)
        val ih = (h - lineWidth * 2f).coerceAtLeast(0f)
        val pixels = ctx.argbPixels
        for (py in t until b) {
            val row = py * ctx.bufferWidth
            val ly = (py - y) + 0.5f
            for (px in l until r) {
                val lx = (px - x) + 0.5f
                val outerCov = roundedCoverage(lx, ly, w.toFloat(), h.toFloat(), outerR)
                if (outerCov <= 0.002f) continue
                val innerCov = if (iw > 0f && ih > 0f) {
                    roundedCoverage(lx - lineWidth, ly - lineWidth, iw, ih, innerR)
                } else {
                    0f
                }
                val strokeCov = (outerCov - innerCov).coerceIn(0f, 1f)
                if (strokeCov > 0.002f) {
                    pixels[row + px] = blendOver(pixels[row + px], color.r, color.g, color.b, effectiveAlpha * strokeCov)
                }
            }
        }
    }

    fun fillGradientRoundedRect(
        ctx: QtRenderContext,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        radius: Float,
        gradient: Gradient
    ) {
        if (w <= 0 || h <= 0) return
        val l = max(x, ctx.clipLeft).coerceAtLeast(0)
        val t = max(y, ctx.clipTop).coerceAtLeast(0)
        val r = min(x + w, ctx.clipRight).coerceAtMost(ctx.bufferWidth)
        val b = min(y + h, ctx.clipBottom).coerceAtMost(ctx.bufferHeight)
        if (r <= l || b <= t) return
        val maxR = min(radius.coerceAtLeast(0f), min(w, h) * 0.5f)
        val wf = w.coerceAtLeast(1).toFloat()
        val hf = h.coerceAtLeast(1).toFloat()
        val pixels = ctx.argbPixels
        for (py in t until b) {
            val row = py * ctx.bufferWidth
            val v = ((py - y) + 0.5f) / hf
            val ly = (py - y) + 0.5f
            for (px in l until r) {
                val lx = (px - x) + 0.5f
                val cov = if (maxR > 0.5f) roundedCoverage(lx, ly, w.toFloat(), h.toFloat(), maxR) else 1f
                if (cov <= 0.002f) continue
                val u = lx / wf
                val col = gradient.sampleAt(u, v)
                val a = ((col.a / 255f) * ctx.currentOpacity * cov).coerceIn(0f, 1f)
                if (a > 0.002f) {
                    pixels[row + px] = blendOver(pixels[row + px], col.r, col.g, col.b, a)
                }
            }
        }
    }

    fun fillCircle(
        ctx: QtRenderContext,
        cx: Float,
        cy: Float,
        radius: Float,
        color: RgbaColor
    ) {
        if (radius <= 0.25f || color.a <= 0) return
        val effA = ((color.a / 255f) * ctx.currentOpacity).coerceIn(0f, 1f)
        if (effA <= 0.002f) return
        val l = max(kotlin.math.floor(cx - radius - 1f).toInt(), ctx.clipLeft).coerceAtLeast(0)
        val t = max(kotlin.math.floor(cy - radius - 1f).toInt(), ctx.clipTop).coerceAtLeast(0)
        val r = min(ceil(cx + radius + 1f).toInt(), ctx.clipRight).coerceAtMost(ctx.bufferWidth)
        val b = min(ceil(cy + radius + 1f).toInt(), ctx.clipBottom).coerceAtMost(ctx.bufferHeight)
        if (r <= l || b <= t) return
        val pixels = ctx.argbPixels
        for (py in t until b) {
            val row = py * ctx.bufferWidth
            val dy = (py + 0.5f) - cy
            for (px in l until r) {
                val dx = (px + 0.5f) - cx
                val dist = sqrt(dx * dx + dy * dy)
                val cov = (radius + 0.5f - dist).coerceIn(0f, 1f)
                if (cov > 0.002f) {
                    pixels[row + px] = blendOver(pixels[row + px], color.r, color.g, color.b, effA * cov)
                }
            }
        }
    }

    fun measureText(text: String, fontSizePx: Float = 14f, weightNumeric: Int = 400): Size {
        if (text.isEmpty()) return Size(0f, ceil(fontSizePx * 1.35f))
        val weightScale = if (weightNumeric >= 600) 1.04f else 1.0f
        val estW = ceil(text.length * fontSizePx * 0.56f * weightScale).coerceAtLeast(1f)
        val estH = ceil(fontSizePx * 1.38f).coerceAtLeast(14f)
        return Size(estW, estH)
    }

    fun drawText(
        ctx: QtRenderContext,
        text: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        color: RgbaColor,
        fontSizePx: Float = 14f,
        weightNumeric: Int = 400,
        italic: Boolean = false,
        fontFamily: String? = null,
        alignment: TextAlignment = TextAlignment.START,
        singleLine: Boolean = true
    ) {
        if (text.isEmpty() || w <= 0 || h <= 0 || color.a <= 0) return
        val effectiveAlpha = ((color.a / 255f) * ctx.currentOpacity).coerceIn(0f, 1f)
        if (effectiveAlpha <= 0.002f) return
        val blendedColor = color.withAlpha(effectiveAlpha)

        val qp = ctx.qPainter
        if (qp != null) {
            val hAlign = when (alignment) {
                TextAlignment.CENTER -> Qt6DynamicBindings.QT_ALIGN_HCENTER
                TextAlignment.END -> Qt6DynamicBindings.QT_ALIGN_RIGHT
                else -> Qt6DynamicBindings.QT_ALIGN_LEFT
            }
            val wrap = if (singleLine) 0 else Qt6DynamicBindings.QT_TEXT_WORD_WRAP
            val alignFlags = hAlign or Qt6DynamicBindings.QT_ALIGN_VCENTER or wrap
            Qt6DynamicBindings.painterDrawText(
                painter = qp,
                text = text,
                x = x,
                y = y,
                w = w,
                h = h,
                color = blendedColor,
                pixelSize = fontSizePx.roundToInt().coerceAtLeast(8),
                family = fontFamily?.trim()?.takeIf { it.isNotEmpty() } ?: "Sans",
                weight = weightNumeric,
                italic = italic,
                alignFlags = alignFlags
            )
            return
        }

        val cx = x.coerceIn(0, (ctx.bufferWidth - 1).coerceAtLeast(0))
        val cy = y.coerceIn(0, (ctx.bufferHeight - 1).coerceAtLeast(0))
        val cw = min(w, ctx.bufferWidth - cx)
        val ch = min(h, ctx.bufferHeight - cy)
        if (cw <= 0 || ch <= 0) return
        QtControlRenderUtil.blitTextSliceToArgbBuffer(
            dst = ctx.argbPixels,
            dstStride = ctx.bufferWidth,
            dstX = cx,
            dstY = cy,
            width = cw,
            height = ch,
            text = text,
            foreground = blendedColor,
            disabled = false
        )
    }

    fun drawImageNative(
        ctx: QtRenderContext,
        impl: DesktopImageNativeImpl<*>,
        bounds: Rect
    ) {
        if (bounds.width <= 0 || bounds.height <= 0) return
        val widget = impl.nativeWidget
        val src = widget.attributes["src"] as? String
        val directBuf = widget.attributes["imageBuffer"] as? ImageBuffer
        val resId = widget.attributes["resourceId"] as? ResourceID
        val objectFit = (widget.attributes["objectFit"] as? ObjectFit) ?: ObjectFit.CONTAIN

        val buffer = directBuf ?: run {
            val cacheKey = "${src.orEmpty()}:${resId?.toResourceUri().orEmpty()}:${bounds.width}x${bounds.height}"
            lock.withLock { imageCache[cacheKey] } ?: run {
                val imgSource = when {
                    resId != null -> ImageSource.fromResource(resId)
                    !src.isNullOrBlank() && (src.startsWith("http://", true) || src.startsWith("https://", true) || src.startsWith("file://", true)) ->
                        ImageSource.fromUrl(src)
                    !src.isNullOrBlank() -> {
                        val drawable = Resources.getDrawable(src)
                        val bytes = drawable?.svgSource?.encodeToByteArray()
                        if (bytes != null) ImageSource.fromBytes(bytes) else ImageSource.fromFile(src)
                    }
                    else -> null
                }
                val loaded = imgSource?.let {
                    ImageLoader.activeNativeCodec?.decode(it, ImageDecodeOptions(targetWidth = bounds.width, targetHeight = bounds.height))
                }
                if (loaded != null) {
                    lock.withLock {
                        if (imageCache.size >= 128) {
                            imageCache.keys.firstOrNull()?.let { imageCache.remove(it) }
                        }
                        imageCache[cacheKey] = loaded
                    }
                }
                loaded
            }
        } ?: return

        val recolored = applyTintIfRequested(buffer, widget.attributes, ctx.darkMode)
        val rgba = recolored.toRgba8888()
        val fitted = computeObjectFitRect(rgba.width, rgba.height, bounds.x, bounds.y, bounds.width, bounds.height, objectFit)
        blitRgbaBytes(ctx, rgba.bytes, rgba.width, rgba.height, fitted)
    }

    fun drawInteractiveSurface(
        ctx: QtRenderContext,
        spec: DesktopInteractiveSurfaceSpec,
        bounds: Rect
    ) {
        val surfW = spec.surfaceWidth().coerceAtLeast(1)
        val surfH = spec.surfaceHeight().coerceAtLeast(1)
        val rgba = spec.captureRgba?.invoke() ?: return
        if (rgba.size < surfW * surfH * 4) return
        val dst = computeObjectFitRect(surfW, surfH, bounds.x, bounds.y, bounds.width, bounds.height, ObjectFit.CONTAIN)
        blitRgbaBytes(ctx, rgba, surfW, surfH, dst)
    }

    private fun blitRgbaBytes(
        ctx: QtRenderContext,
        rgba: ByteArray,
        srcW: Int,
        srcH: Int,
        dst: Rect
    ) {
        if (srcW <= 0 || srcH <= 0 || dst.width <= 0 || dst.height <= 0) return
        val l = max(dst.x, ctx.clipLeft).coerceAtLeast(0)
        val t = max(dst.y, ctx.clipTop).coerceAtLeast(0)
        val r = min(dst.right, ctx.clipRight).coerceAtMost(ctx.bufferWidth)
        val b = min(dst.bottom, ctx.clipBottom).coerceAtMost(ctx.bufferHeight)
        if (r <= l || b <= t) return

        val pixels = ctx.argbPixels
        for (py in t until b) {
            val row = py * ctx.bufferWidth
            val sy = (((py - dst.y).toLong() * srcH) / dst.height.coerceAtLeast(1)).toInt().coerceIn(0, srcH - 1)
            for (px in l until r) {
                val sx = (((px - dst.x).toLong() * srcW) / dst.width.coerceAtLeast(1)).toInt().coerceIn(0, srcW - 1)
                val base = (sy * srcW + sx) * 4
                val a = ((rgba[base + 3].toInt() and 0xFF) / 255f) * ctx.currentOpacity
                if (a > 0.002f) {
                    val sr = rgba[base].toInt() and 0xFF
                    val sg = rgba[base + 1].toInt() and 0xFF
                    val sb = rgba[base + 2].toInt() and 0xFF
                    pixels[row + px] = blendOver(pixels[row + px], sr, sg, sb, a.coerceIn(0f, 1f))
                }
            }
        }
    }

    private fun applyTintIfRequested(buffer: ImageBuffer, attrs: Map<String, Any?>, isDarkTheme: Boolean): ImageBuffer {
        val tintColor = attrs["tintColor"] as? RgbaColor ?: return buffer
        val tintMode = (attrs["tintMode"] as? ImageTintMode) ?: ImageTintMode.NONE
        if (tintMode == ImageTintMode.NONE) return buffer
        return MonocolorImageRecolorProcessor.processImageBuffer(buffer, tintMode, tintColor, isDarkTheme)
    }

    private fun roundedCoverage(px: Float, py: Float, width: Float, height: Float, radius: Float): Float {
        if (px < 0f || py < 0f || px > width || py > height) return 0f
        if (radius <= 0.5f) return 1f
        val r = min(radius, min(width, height) * 0.5f)
        val cx = when {
            px < r -> r
            px > width - r -> width - r
            else -> px
        }
        val cy = when {
            py < r -> r
            py > height - r -> height - r
            else -> py
        }
        val dx = px - cx
        val dy = py - cy
        val dist = sqrt(dx * dx + dy * dy)
        return (r + 0.5f - dist).coerceIn(0f, 1f)
    }

    private fun blendOver(dst: Int, srcR: Int, srcG: Int, srcB: Int, alpha: Float): Int {
        if (alpha >= 0.996f) {
            return (255 shl 24) or (srcR shl 16) or (srcG shl 8) or srcB
        }
        val dstA = ((dst ushr 24) and 0xFF) / 255f
        val dstR = (dst ushr 16) and 0xFF
        val dstG = (dst ushr 8) and 0xFF
        val dstB = dst and 0xFF
        val outA = (alpha + dstA * (1f - alpha)).coerceIn(0f, 1f)
        if (outA <= 0.001f) return 0
        val inv = 1f - alpha
        val outR = ((srcR * alpha + dstR * dstA * inv) / outA).roundToInt().coerceIn(0, 255)
        val outG = ((srcG * alpha + dstG * dstA * inv) / outA).roundToInt().coerceIn(0, 255)
        val outB = ((srcB * alpha + dstB * dstA * inv) / outA).roundToInt().coerceIn(0, 255)
        val aByte = (outA * 255f).roundToInt().coerceIn(0, 255)
        return (aByte shl 24) or (outR shl 16) or (outG shl 8) or outB
    }

    private fun computeObjectFitRect(
        srcW: Int,
        srcH: Int,
        dstX: Int,
        dstY: Int,
        dstW: Int,
        dstH: Int,
        fit: ObjectFit
    ): Rect {
        if (fit == ObjectFit.FILL || srcW <= 0 || srcH <= 0) {
            return Rect(dstX, dstY, dstW, dstH)
        }
        val scaleX = dstW.toFloat() / srcW.toFloat()
        val scaleY = dstH.toFloat() / srcH.toFloat()
        val scale = when (fit) {
            ObjectFit.COVER -> max(scaleX, scaleY)
            ObjectFit.SCALE_DOWN -> min(1f, min(scaleX, scaleY))
            ObjectFit.NONE -> 1f
            else -> min(scaleX, scaleY)
        }
        val outW = (srcW * scale).roundToInt().coerceAtLeast(1)
        val outH = (srcH * scale).roundToInt().coerceAtLeast(1)
        val outX = dstX + (dstW - outW) / 2
        val outY = dstY + (dstH - outH) / 2
        return Rect(outX, outY, outW, outH)
    }
}
