package com.nexustools.genericui.linux.titlebar

import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.color.RgbaColor

/**
 * Toolkit-agnostic drawing surface contract implemented by GTK4 Cairo and KDE QPainter backends
 * (as well as test recorders) to render Linux titlebars via [LinuxTitleBarRenderer].
 */
interface LinuxTitleBarCanvas {
    fun save()
    fun restore()
    fun clipRect(rect: Rect)
    fun fillRect(rect: Rect, color: RgbaColor)
    fun fillTopRoundedRect(rect: Rect, topRadiusPx: Float, color: RgbaColor)
    fun fillLinearGradientTopRounded(rect: Rect, topRadiusPx: Float, topColor: RgbaColor, bottomColor: RgbaColor)
    fun fillRoundedRect(rect: Rect, radiusPx: Float, color: RgbaColor)
    fun strokeRoundedRect(rect: Rect, radiusPx: Float, color: RgbaColor, strokeWidthPx: Float)
    fun fillCircle(cx: Float, cy: Float, radius: Float, color: RgbaColor)
    fun strokeCircle(cx: Float, cy: Float, radius: Float, color: RgbaColor, strokeWidthPx: Float)
    fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float, color: RgbaColor, strokeWidthPx: Float)
    fun drawText(text: String, bounds: Rect, color: RgbaColor, fontSizePx: Float, bold: Boolean, centered: Boolean)
    fun measureTextWidth(text: String, fontSizePx: Float, bold: Boolean): Int
    fun drawArgbPixels(pixels: IntArray, srcWidth: Int, srcHeight: Int, dstRect: Rect, opacity: Float = 1f)
}
