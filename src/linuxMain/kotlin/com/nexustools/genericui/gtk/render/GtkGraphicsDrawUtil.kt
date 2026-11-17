@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.nexustools.genericui.gtk.render

import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.enums.ui.ObjectFit
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.MonocolorImageRecolorProcessor
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.linux.LinuxReentrantLock
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.style.color.ImageTintMode
import com.nexustools.genericui.style.color.RgbaColor
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object GtkGraphicsDrawUtil {
    var onAsyncAssetLoaded: (() -> Unit)? = null

    private val lock = LinuxReentrantLock()
    private val imageCache = LinkedHashMap<String, ImageBuffer>(128)
    private val spritePremulCache = LinkedHashMap<Long, IntArray>(256)

    fun fillRect(ctx: GtkCairoRenderContext, x: Int, y: Int, w: Int, h: Int, color: RgbaColor) {
        val cr = ctx.cairoContext ?: return
        if (w <= 0 || h <= 0 || color.a <= 0) return
        val alpha = ((color.a / 255.0) * ctx.currentOpacity).coerceIn(0.0, 1.0)
        if (alpha <= 0.001) return
        Gtk4DynamicBindings.cairoSave(cr)
        Gtk4DynamicBindings.cairoSetSourceRgba(cr, color.r / 255.0, color.g / 255.0, color.b / 255.0, alpha)
        Gtk4DynamicBindings.cairoRectangle(cr, x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())
        Gtk4DynamicBindings.cairoFill(cr)
        Gtk4DynamicBindings.cairoRestore(cr)
    }

    fun fillRoundedRect(
        ctx: GtkCairoRenderContext,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        radius: Float,
        color: RgbaColor
    ) {
        val cr = ctx.cairoContext ?: return
        if (w <= 0 || h <= 0 || color.a <= 0) return
        if (radius <= 0.5f) {
            fillRect(ctx, x, y, w, h, color)
            return
        }
        val alpha = ((color.a / 255.0) * ctx.currentOpacity).coerceIn(0.0, 1.0)
        if (alpha <= 0.001) return
        Gtk4DynamicBindings.cairoSave(cr)
        appendRoundedRectPath(cr, x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), radius.toDouble())
        Gtk4DynamicBindings.cairoSetSourceRgba(cr, color.r / 255.0, color.g / 255.0, color.b / 255.0, alpha)
        Gtk4DynamicBindings.cairoFill(cr)
        Gtk4DynamicBindings.cairoRestore(cr)
    }

    fun strokeRoundedRect(
        ctx: GtkCairoRenderContext,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        radius: Float,
        lineWidth: Float,
        color: RgbaColor
    ) {
        val cr = ctx.cairoContext ?: return
        if (w <= 0 || h <= 0 || lineWidth <= 0f || color.a <= 0) return
        val alpha = ((color.a / 255.0) * ctx.currentOpacity).coerceIn(0.0, 1.0)
        if (alpha <= 0.001) return
        val inset = lineWidth * 0.5
        val rx = x + inset
        val ry = y + inset
        val rw = (w - lineWidth).coerceAtLeast(1f).toDouble()
        val rh = (h - lineWidth).coerceAtLeast(1f).toDouble()
        Gtk4DynamicBindings.cairoSave(cr)
        if (radius <= 0.5f) {
            Gtk4DynamicBindings.cairoRectangle(cr, rx, ry, rw, rh)
        } else {
            appendRoundedRectPath(cr, rx, ry, rw, rh, max(0.0, radius - inset))
        }
        Gtk4DynamicBindings.cairoSetLineWidth(cr, lineWidth.toDouble())
        Gtk4DynamicBindings.cairoSetSourceRgba(cr, color.r / 255.0, color.g / 255.0, color.b / 255.0, alpha)
        Gtk4DynamicBindings.cairoStroke(cr)
        Gtk4DynamicBindings.cairoRestore(cr)
    }

    fun fillLinearGradientRoundedRect(
        ctx: GtkCairoRenderContext,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        radius: Float,
        startColor: RgbaColor,
        endColor: RgbaColor,
        horizontal: Boolean = false
    ) {
        val cr = ctx.cairoContext ?: return
        if (w <= 0 || h <= 0) return
        val x1 = if (horizontal) (x + w).toDouble() else x.toDouble()
        val y1 = if (horizontal) y.toDouble() else (y + h).toDouble()
        val pattern = Gtk4DynamicBindings.cairoPatternCreateLinear(x.toDouble(), y.toDouble(), x1, y1)
        if (pattern == null) {
            fillRoundedRect(ctx, x, y, w, h, radius, startColor)
            return
        }
        try {
            val a0 = ((startColor.a / 255.0) * ctx.currentOpacity).coerceIn(0.0, 1.0)
            val a1 = ((endColor.a / 255.0) * ctx.currentOpacity).coerceIn(0.0, 1.0)
            Gtk4DynamicBindings.cairoPatternAddColorStopRgba(pattern, 0.0, startColor.r / 255.0, startColor.g / 255.0, startColor.b / 255.0, a0)
            Gtk4DynamicBindings.cairoPatternAddColorStopRgba(pattern, 1.0, endColor.r / 255.0, endColor.g / 255.0, endColor.b / 255.0, a1)
            Gtk4DynamicBindings.cairoSave(cr)
            if (radius > 0.5f) {
                appendRoundedRectPath(cr, x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), radius.toDouble())
            } else {
                Gtk4DynamicBindings.cairoRectangle(cr, x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())
            }
            Gtk4DynamicBindings.cairoSetSource(cr, pattern)
            Gtk4DynamicBindings.cairoFill(cr)
            Gtk4DynamicBindings.cairoRestore(cr)
        } finally {
            Gtk4DynamicBindings.cairoPatternDestroy(pattern)
        }
    }

    fun pushClip(ctx: GtkCairoRenderContext, x: Int, y: Int, w: Int, h: Int): Boolean {
        if (!ctx.pushClipBounds(x, y, w, h)) {
            ctx.popClipBounds()
            return false
        }
        val cr = ctx.cairoContext
        if (cr != null) {
            Gtk4DynamicBindings.cairoSave(cr)
            Gtk4DynamicBindings.cairoRectangle(cr, x.toDouble(), y.toDouble(), w.coerceAtLeast(0).toDouble(), h.coerceAtLeast(0).toDouble())
            Gtk4DynamicBindings.cairoClip(cr)
        }
        return true
    }

    fun popClip(ctx: GtkCairoRenderContext) {
        ctx.popClipBounds()
        ctx.cairoContext?.let { Gtk4DynamicBindings.cairoRestore(it) }
    }

    fun blitArgbSprite(
        ctx: GtkCairoRenderContext,
        cacheKey: Long,
        straightArgbPixels: IntArray,
        width: Int,
        height: Int,
        dstX: Int,
        dstY: Int,
        dstW: Int,
        dstH: Int
    ) {
        val cr = ctx.cairoContext ?: return
        if (width <= 0 || height <= 0 || straightArgbPixels.size < width * height) return
        val premul = if (cacheKey != 0L) {
            lock.withLock {
                spritePremulCache[cacheKey] ?: convertStraightArgbToCairoPremul(straightArgbPixels, width * height).also {
                    if (spritePremulCache.size >= 512) {
                        spritePremulCache.keys.firstOrNull()?.let { spritePremulCache.remove(it) }
                    }
                    spritePremulCache[cacheKey] = it
                }
            }
        } else {
            convertStraightArgbToCairoPremul(straightArgbPixels, width * height)
        }
        Gtk4DynamicBindings.cairoPaintPremulArgbSurface(
            cr = cr,
            premulArgb = premul,
            width = width,
            height = height,
            dstX = dstX.toDouble(),
            dstY = dstY.toDouble(),
            dstW = dstW.toDouble(),
            dstH = dstH.toDouble()
        )
    }

    fun measureText(
        text: String,
        fontSizePx: Float = 14f,
        weightNumeric: Int = 400,
        italic: Boolean = false,
        monospace: Boolean = false,
        maxWidth: Int = -1,
        singleLine: Boolean = true,
        ctx: GtkCairoRenderContext? = null
    ): Size {
        if (text.isEmpty()) {
            return Size(0f, ceil(fontSizePx * 1.35f))
        }
        val cr = ctx?.cairoContext
        if (cr != null) {
            val layout = Gtk4DynamicBindings.pangoCairoCreateLayout(cr)
            if (layout != null) {
                try {
                    configurePangoLayout(
                        layout = layout,
                        text = text,
                        fontSizePx = fontSizePx,
                        weightNumeric = weightNumeric,
                        italic = italic,
                        monospace = monospace,
                        maxWidthPx = maxWidth,
                        alignment = TextAlignment.START,
                        singleLine = singleLine,
                        underline = false,
                        strikethrough = false
                    )
                    val (pw, ph) = Gtk4DynamicBindings.pangoLayoutGetPixelSize(layout)
                    if (pw > 0 || ph > 0) {
                        return Size(pw.toFloat(), max(ph.toFloat(), ceil(fontSizePx * 1.3f)))
                    }
                } finally {
                    LinuxGlibGioBindings.objectUnref(layout)
                }
            }
        }
        val avgCharW = fontSizePx * if (monospace) 0.60f else 0.56f
        val lineH = ceil(fontSizePx * 1.35f)
        if (singleLine || maxWidth <= 0) {
            val lines = text.split('\n')
            val maxChars = lines.maxOfOrNull { it.length } ?: 0
            return Size(ceil(maxChars * avgCharW), lines.size * lineH)
        }
        val charsPerLine = max(1, (maxWidth / avgCharW).toInt())
        val wrappedLines = text.split('\n').sumOf { line ->
            max(1, (line.length + charsPerLine - 1) / charsPerLine)
        }
        return Size(min(maxWidth.toFloat(), ceil(text.length * avgCharW)), wrappedLines * lineH)
    }

    fun drawText(
        ctx: GtkCairoRenderContext,
        text: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        color: RgbaColor,
        fontSizePx: Float = 14f,
        weightNumeric: Int = 400,
        italic: Boolean = false,
        monospace: Boolean = false,
        alignment: TextAlignment = TextAlignment.START,
        singleLine: Boolean = true,
        underline: Boolean = false,
        strikethrough: Boolean = false
    ) {
        val cr = ctx.cairoContext ?: return
        if (text.isEmpty() || w <= 0 || h <= 0 || color.a <= 0) return
        val layout = Gtk4DynamicBindings.pangoCairoCreateLayout(cr) ?: return
        try {
            configurePangoLayout(
                layout = layout,
                text = text,
                fontSizePx = fontSizePx,
                weightNumeric = weightNumeric,
                italic = italic,
                monospace = monospace,
                maxWidthPx = w,
                alignment = alignment,
                singleLine = singleLine,
                underline = underline,
                strikethrough = strikethrough
            )
            val (_, textH) = Gtk4DynamicBindings.pangoLayoutGetPixelSize(layout)
            val drawY = if (singleLine && textH in 1 until h) {
                y + (h - textH) / 2.0
            } else {
                y.toDouble()
            }
            val alpha = ((color.a / 255.0) * ctx.currentOpacity).coerceIn(0.0, 1.0)
            Gtk4DynamicBindings.cairoSave(cr)
            Gtk4DynamicBindings.cairoRectangle(cr, x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble())
            Gtk4DynamicBindings.cairoClip(cr)
            Gtk4DynamicBindings.cairoMoveTo(cr, x.toDouble(), drawY)
            Gtk4DynamicBindings.cairoSetSourceRgba(cr, color.r / 255.0, color.g / 255.0, color.b / 255.0, alpha)
            Gtk4DynamicBindings.pangoCairoShowLayout(cr, layout)
            Gtk4DynamicBindings.cairoRestore(cr)
        } finally {
            LinuxGlibGioBindings.objectUnref(layout)
        }
    }

    fun drawImageNative(
        ctx: GtkCairoRenderContext,
        impl: DesktopImageNativeImpl<*>,
        bounds: Rect
    ) {
        if (bounds.width <= 0 || bounds.height <= 0) return
        val widget = impl.nativeWidget
        val src = widget.attributes["src"] as? String
        val directBuf = widget.attributes["imageBuffer"] as? ImageBuffer
        val resId = widget.attributes["resourceId"] as? com.nexustools.genericui.resources.ResourceID
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
        val premul = convertRgbaBytesToCairoPremul(rgba.bytes, rgba.width, rgba.height)
        val cr = ctx.cairoContext ?: return
        Gtk4DynamicBindings.cairoSave(cr)
        Gtk4DynamicBindings.cairoRectangle(cr, bounds.x.toDouble(), bounds.y.toDouble(), bounds.width.toDouble(), bounds.height.toDouble())
        Gtk4DynamicBindings.cairoClip(cr)
        Gtk4DynamicBindings.cairoPaintPremulArgbSurface(
            cr = cr,
            premulArgb = premul,
            width = rgba.width,
            height = rgba.height,
            dstX = fitted.x.toDouble(),
            dstY = fitted.y.toDouble(),
            dstW = fitted.width.toDouble(),
            dstH = fitted.height.toDouble()
        )
        Gtk4DynamicBindings.cairoRestore(cr)
    }

    fun drawInteractiveSurface(
        ctx: GtkCairoRenderContext,
        spec: DesktopInteractiveSurfaceSpec,
        bounds: Rect
    ) {
        val cr = ctx.cairoContext ?: return
        val surfW = spec.surfaceWidth().coerceAtLeast(1)
        val surfH = spec.surfaceHeight().coerceAtLeast(1)
        val dst = computeObjectFitRect(
            srcW = surfW,
            srcH = surfH,
            dstX = bounds.x,
            dstY = bounds.y,
            dstW = bounds.width,
            dstH = bounds.height,
            fit = ObjectFit.CONTAIN
        )
        val rgba = spec.captureRgba?.invoke() ?: return
        if (rgba.size < surfW * surfH * 4) return
        val premul = convertRgbaBytesToCairoPremul(rgba, surfW, surfH)
        Gtk4DynamicBindings.cairoPaintPremulArgbSurface(
            cr = cr,
            premulArgb = premul,
            width = surfW,
            height = surfH,
            dstX = dst.x.toDouble(),
            dstY = dst.y.toDouble(),
            dstW = dst.width.toDouble(),
            dstH = dst.height.toDouble()
        )
    }

    private fun configurePangoLayout(
        layout: kotlinx.cinterop.COpaquePointer,
        text: String,
        fontSizePx: Float,
        weightNumeric: Int,
        italic: Boolean,
        monospace: Boolean,
        maxWidthPx: Int,
        alignment: TextAlignment,
        singleLine: Boolean,
        underline: Boolean,
        strikethrough: Boolean
    ) {
        Gtk4DynamicBindings.pangoLayoutSetText(layout, text)
        if (maxWidthPx > 0) {
            Gtk4DynamicBindings.pangoLayoutSetWidth(layout, maxWidthPx * Gtk4DynamicBindings.PANGO_SCALE)
            if (singleLine) {
                Gtk4DynamicBindings.pangoLayoutSetEllipsize(layout, Gtk4DynamicBindings.PANGO_ELLIPSIZE_END)
            } else {
                Gtk4DynamicBindings.pangoLayoutSetEllipsize(layout, Gtk4DynamicBindings.PANGO_ELLIPSIZE_NONE)
                Gtk4DynamicBindings.pangoLayoutSetWrap(layout, Gtk4DynamicBindings.PANGO_WRAP_WORD_CHAR)
            }
        } else {
            Gtk4DynamicBindings.pangoLayoutSetWidth(layout, -1)
        }
        val pangoAlign = when (alignment) {
            TextAlignment.CENTER -> Gtk4DynamicBindings.PANGO_ALIGN_CENTER
            TextAlignment.END -> Gtk4DynamicBindings.PANGO_ALIGN_RIGHT
            else -> Gtk4DynamicBindings.PANGO_ALIGN_LEFT
        }
        Gtk4DynamicBindings.pangoLayoutSetAlignment(layout, pangoAlign)

        val desc = Gtk4DynamicBindings.pangoFontDescriptionNew()
        if (desc != null) {
            try {
                Gtk4DynamicBindings.pangoFontDescriptionSetFamily(desc, if (monospace) "monospace" else "sans-serif")
                Gtk4DynamicBindings.pangoFontDescriptionSetWeight(desc, weightNumeric.coerceIn(100, 900))
                Gtk4DynamicBindings.pangoFontDescriptionSetStyle(
                    desc,
                    if (italic) Gtk4DynamicBindings.PANGO_STYLE_ITALIC else Gtk4DynamicBindings.PANGO_STYLE_NORMAL
                )
                Gtk4DynamicBindings.pangoFontDescriptionSetAbsoluteSize(desc, fontSizePx.toDouble() * Gtk4DynamicBindings.PANGO_SCALE)
                Gtk4DynamicBindings.pangoLayoutSetFontDescription(layout, desc)
            } finally {
                Gtk4DynamicBindings.pangoFontDescriptionFree(desc)
            }
        }
        if (underline || strikethrough) {
            Gtk4DynamicBindings.pangoLayoutSetDecoration(layout, underline, strikethrough)
        }
    }

    private fun appendRoundedRectPath(
        cr: kotlinx.cinterop.COpaquePointer,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        radius: Double
    ) {
        val r = min(radius, min(w, h) * 0.5)
        Gtk4DynamicBindings.cairoNewSubPath(cr)
        Gtk4DynamicBindings.cairoArc(cr, x + w - r, y + r, r, -PI / 2.0, 0.0)
        Gtk4DynamicBindings.cairoArc(cr, x + w - r, y + h - r, r, 0.0, PI / 2.0)
        Gtk4DynamicBindings.cairoArc(cr, x + r, y + h - r, r, PI / 2.0, PI)
        Gtk4DynamicBindings.cairoArc(cr, x + r, y + r, r, PI, PI * 1.5)
        Gtk4DynamicBindings.cairoClosePath(cr)
    }

    private fun applyTintIfRequested(buffer: ImageBuffer, attrs: Map<String, Any?>, isDarkTheme: Boolean): ImageBuffer {
        val tintColor = attrs["tintColor"] as? RgbaColor ?: return buffer
        val tintMode = (attrs["tintMode"] as? ImageTintMode) ?: ImageTintMode.NONE
        if (tintMode == ImageTintMode.NONE) return buffer
        return MonocolorImageRecolorProcessor.processImageBuffer(buffer, tintMode, tintColor, isDarkTheme)
    }

    private fun convertStraightArgbToCairoPremul(src: IntArray, count: Int): IntArray {
        val out = IntArray(count)
        for (i in 0 until count) {
            val px = src[i]
            val a = (px ushr 24) and 0xFF
            if (a == 255) {
                out[i] = px
            } else if (a == 0) {
                out[i] = 0
            } else {
                val r = (((px ushr 16) and 0xFF) * a + 127) / 255
                val g = (((px ushr 8) and 0xFF) * a + 127) / 255
                val b = ((px and 0xFF) * a + 127) / 255
                out[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    private fun convertRgbaBytesToCairoPremul(rgba: ByteArray, width: Int, height: Int): IntArray {
        val total = width * height
        val out = IntArray(total)
        for (i in 0 until total) {
            val base = i * 4
            val r = rgba[base].toInt() and 0xFF
            val g = rgba[base + 1].toInt() and 0xFF
            val b = rgba[base + 2].toInt() and 0xFF
            val a = rgba[base + 3].toInt() and 0xFF
            if (a == 255) {
                out[i] = (255 shl 24) or (r shl 16) or (g shl 8) or b
            } else if (a == 0) {
                out[i] = 0
            } else {
                val pr = (r * a + 127) / 255
                val pg = (g * a + 127) / 255
                val pb = (b * a + 127) / 255
                out[i] = (a shl 24) or (pr shl 16) or (pg shl 8) or pb
            }
        }
        return out
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
