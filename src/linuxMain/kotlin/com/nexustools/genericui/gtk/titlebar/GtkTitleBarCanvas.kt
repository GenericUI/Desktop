@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.titlebar

import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.widget.GtkSurfaceRenderHelper
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarCanvas
import com.nexustools.genericui.style.color.RgbaColor
import kotlinx.cinterop.COpaquePointer
import kotlin.math.PI
import kotlin.math.max

/**
 * Cairo + Pango implementation of [LinuxTitleBarCanvas] operating directly on a GTK4 `cairo_t*`
 * without any legacy single-canvas render context dependencies.
 */
class GtkTitleBarCanvas(
    private val cr: COpaquePointer
) : LinuxTitleBarCanvas {

    override fun save() {
        Gtk4DynamicBindings.cairoSave(cr)
    }

    override fun restore() {
        Gtk4DynamicBindings.cairoRestore(cr)
    }

    override fun clipRect(rect: Rect) {
        if (rect.width <= 0 || rect.height <= 0) return
        Gtk4DynamicBindings.cairoRectangle(
            cr,
            rect.x.toDouble(),
            rect.y.toDouble(),
            rect.width.toDouble(),
            rect.height.toDouble()
        )
        Gtk4DynamicBindings.cairoClip(cr)
    }

    override fun fillRect(rect: Rect, color: RgbaColor) {
        if (rect.width <= 0 || rect.height <= 0 || color.a <= 0) return
        Gtk4DynamicBindings.cairoSave(cr)
        setRgba(color)
        Gtk4DynamicBindings.cairoRectangle(
            cr,
            rect.x.toDouble(),
            rect.y.toDouble(),
            rect.width.toDouble(),
            rect.height.toDouble()
        )
        Gtk4DynamicBindings.cairoFill(cr)
        Gtk4DynamicBindings.cairoRestore(cr)
    }

    override fun fillTopRoundedRect(rect: Rect, topRadiusPx: Float, color: RgbaColor) {
        if (rect.width <= 0 || rect.height <= 0 || color.a <= 0) return
        val r = topRadiusPx.coerceAtLeast(0f).toDouble()
        Gtk4DynamicBindings.cairoSave(cr)
        setRgba(color)
        Gtk4DynamicBindings.gtkWidgetSetClipPathForRoundedCorners(
            cr = cr,
            x = rect.x.toDouble(),
            y = rect.y.toDouble(),
            w = rect.width.toDouble(),
            h = rect.height.toDouble(),
            tl = r,
            tr = r,
            br = 0.0,
            bl = 0.0
        )
        Gtk4DynamicBindings.cairoFill(cr)
        Gtk4DynamicBindings.cairoRestore(cr)
    }

    override fun fillLinearGradientTopRounded(
        rect: Rect,
        topRadiusPx: Float,
        topColor: RgbaColor,
        bottomColor: RgbaColor
    ) {
        if (rect.width <= 0 || rect.height <= 0) return
        val r = topRadiusPx.coerceAtLeast(0f).toDouble()
        Gtk4DynamicBindings.cairoSave(cr)
        Gtk4DynamicBindings.gtkWidgetSetClipPathForRoundedCorners(
            cr = cr,
            x = rect.x.toDouble(),
            y = rect.y.toDouble(),
            w = rect.width.toDouble(),
            h = rect.height.toDouble(),
            tl = r,
            tr = r,
            br = 0.0,
            bl = 0.0
        )
        val pat = Gtk4DynamicBindings.cairoPatternCreateLinear(
            rect.x.toDouble(),
            rect.y.toDouble(),
            rect.x.toDouble(),
            rect.bottom.toDouble()
        )
        if (pat != null) {
            Gtk4DynamicBindings.cairoPatternAddColorStopRgba(
                pat, 0.0,
                topColor.r / 255.0, topColor.g / 255.0, topColor.b / 255.0, topColor.a / 255.0
            )
            Gtk4DynamicBindings.cairoPatternAddColorStopRgba(
                pat, 1.0,
                bottomColor.r / 255.0, bottomColor.g / 255.0, bottomColor.b / 255.0, bottomColor.a / 255.0
            )
            Gtk4DynamicBindings.cairoSetSource(cr, pat)
            Gtk4DynamicBindings.cairoFill(cr)
            Gtk4DynamicBindings.cairoPatternDestroy(pat)
        } else {
            setRgba(bottomColor)
            Gtk4DynamicBindings.cairoFill(cr)
        }
        Gtk4DynamicBindings.cairoRestore(cr)
    }

    override fun fillRoundedRect(rect: Rect, radiusPx: Float, color: RgbaColor) {
        if (rect.width <= 0 || rect.height <= 0 || color.a <= 0) return
        val r = radiusPx.coerceAtLeast(0f).toDouble()
        Gtk4DynamicBindings.cairoSave(cr)
        setRgba(color)
        Gtk4DynamicBindings.gtkWidgetSetClipPathForRoundedCorners(
            cr, rect.x.toDouble(), rect.y.toDouble(), rect.width.toDouble(), rect.height.toDouble(),
            r, r, r, r
        )
        Gtk4DynamicBindings.cairoFill(cr)
        Gtk4DynamicBindings.cairoRestore(cr)
    }

    override fun strokeRoundedRect(rect: Rect, radiusPx: Float, color: RgbaColor, strokeWidthPx: Float) {
        if (rect.width <= 0 || rect.height <= 0 || color.a <= 0 || strokeWidthPx <= 0f) return
        val sw = strokeWidthPx.toDouble()
        val inset = sw * 0.5
        val r = max(0.0, radiusPx.toDouble() - inset)
        Gtk4DynamicBindings.cairoSave(cr)
        setRgba(color)
        Gtk4DynamicBindings.cairoSetLineWidth(cr, sw)
        Gtk4DynamicBindings.gtkWidgetSetClipPathForRoundedCorners(
            cr,
            rect.x + inset,
            rect.y + inset,
            (rect.width - sw).coerceAtLeast(1.0),
            (rect.height - sw).coerceAtLeast(1.0),
            r, r, r, r
        )
        Gtk4DynamicBindings.cairoStroke(cr)
        Gtk4DynamicBindings.cairoRestore(cr)
    }

    override fun fillCircle(cx: Float, cy: Float, radius: Float, color: RgbaColor) {
        if (radius <= 0f || color.a <= 0) return
        Gtk4DynamicBindings.cairoSave(cr)
        setRgba(color)
        Gtk4DynamicBindings.cairoNewPath(cr)
        Gtk4DynamicBindings.cairoArc(cr, cx.toDouble(), cy.toDouble(), radius.toDouble(), 0.0, 2.0 * PI)
        Gtk4DynamicBindings.cairoFill(cr)
        Gtk4DynamicBindings.cairoRestore(cr)
    }

    override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: RgbaColor, strokeWidthPx: Float) {
        if (radius <= 0f || color.a <= 0 || strokeWidthPx <= 0f) return
        Gtk4DynamicBindings.cairoSave(cr)
        setRgba(color)
        Gtk4DynamicBindings.cairoSetLineWidth(cr, strokeWidthPx.toDouble())
        Gtk4DynamicBindings.cairoNewPath(cr)
        Gtk4DynamicBindings.cairoArc(cr, cx.toDouble(), cy.toDouble(), radius.toDouble(), 0.0, 2.0 * PI)
        Gtk4DynamicBindings.cairoStroke(cr)
        Gtk4DynamicBindings.cairoRestore(cr)
    }

    override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, color: RgbaColor, strokeWidthPx: Float) {
        if (color.a <= 0 || strokeWidthPx <= 0f) return
        Gtk4DynamicBindings.cairoSave(cr)
        setRgba(color)
        Gtk4DynamicBindings.cairoSetLineWidth(cr, strokeWidthPx.toDouble())
        Gtk4DynamicBindings.cairoSetLineCap(cr, Gtk4DynamicBindings.CAIRO_LINE_CAP_ROUND)
        Gtk4DynamicBindings.cairoNewPath(cr)
        Gtk4DynamicBindings.cairoMoveTo(cr, x1.toDouble(), y1.toDouble())
        Gtk4DynamicBindings.cairoLineTo(cr, x2.toDouble(), y2.toDouble())
        Gtk4DynamicBindings.cairoStroke(cr)
        Gtk4DynamicBindings.cairoRestore(cr)
    }

    override fun drawText(
        text: String,
        bounds: Rect,
        color: RgbaColor,
        fontSizePx: Float,
        bold: Boolean,
        centered: Boolean
    ) {
        if (text.isEmpty() || bounds.width <= 0 || bounds.height <= 0 || color.a <= 0) return
        GtkSurfaceRenderHelper.drawPangoText(
            cr = cr,
            text = text,
            x = bounds.x.toDouble(),
            y = bounds.y.toDouble(),
            width = bounds.width.toDouble(),
            height = bounds.height.toDouble(),
            color = color,
            fontSizePx = fontSizePx,
            fontWeight = if (bold) Gtk4DynamicBindings.PANGO_WEIGHT_BOLD else Gtk4DynamicBindings.PANGO_WEIGHT_NORMAL,
            align = if (centered) Gtk4DynamicBindings.PANGO_ALIGN_CENTER else Gtk4DynamicBindings.PANGO_ALIGN_LEFT,
            singleLine = true,
            ellipsize = true
        )
    }

    override fun measureTextWidth(text: String, fontSizePx: Float, bold: Boolean): Int {
        if (text.isEmpty()) return 0
        val weight = if (bold) Gtk4DynamicBindings.PANGO_WEIGHT_BOLD else Gtk4DynamicBindings.PANGO_WEIGHT_NORMAL
        return GtkSurfaceRenderHelper.measureTextSize(text, fontSizePx, fontWeight = weight).first
    }

    override fun drawArgbPixels(pixels: IntArray, srcWidth: Int, srcHeight: Int, dstRect: Rect, opacity: Float) {
        if (srcWidth <= 0 || srcHeight <= 0 || dstRect.width <= 0 || dstRect.height <= 0) return
        if (pixels.size < srcWidth * srcHeight) return
        val premulWords = IntArray(srcWidth * srcHeight)
        for (i in 0 until srcWidth * srcHeight) {
            val w = pixels[i]
            val a = ((w ushr 24) and 0xFF)
            val r = (w ushr 16) and 0xFF
            val g = (w ushr 8) and 0xFF
            val b = w and 0xFF
            val pr = (r * a + 127) / 255
            val pg = (g * a + 127) / 255
            val pb = (b * a + 127) / 255
            premulWords[i] = (a shl 24) or (pr shl 16) or (pg shl 8) or pb
        }
        val surface = GtkSurfaceRenderHelper.premulBgraWordsToCairoSurface(premulWords, srcWidth, srcHeight) ?: return
        try {
            Gtk4DynamicBindings.cairoSave(cr)
            Gtk4DynamicBindings.cairoTranslate(cr, dstRect.x.toDouble(), dstRect.y.toDouble())
            if (srcWidth != dstRect.width || srcHeight != dstRect.height) {
                Gtk4DynamicBindings.cairoScale(
                    cr,
                    dstRect.width.toDouble() / srcWidth.toDouble(),
                    dstRect.height.toDouble() / srcHeight.toDouble()
                )
            }
            Gtk4DynamicBindings.cairoSetSourceSurface(cr, surface, 0.0, 0.0)
            if (opacity < 0.999f) {
                Gtk4DynamicBindings.cairoPaintWithAlpha(cr, opacity.coerceIn(0f, 1f).toDouble())
            } else {
                Gtk4DynamicBindings.cairoPaint(cr)
            }
            Gtk4DynamicBindings.cairoRestore(cr)
        } finally {
            Gtk4DynamicBindings.cairoSurfaceDestroy(surface)
        }
    }

    private fun setRgba(c: RgbaColor) {
        Gtk4DynamicBindings.cairoSetSourceRgba(
            cr,
            c.r / 255.0,
            c.g / 255.0,
            c.b / 255.0,
            c.a / 255.0
        )
    }
}
