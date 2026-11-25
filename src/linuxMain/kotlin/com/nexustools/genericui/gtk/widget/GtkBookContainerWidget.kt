@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.widget

import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.style.color.RgbaColor
import kotlinx.cinterop.COpaquePointer

/**
 * Retained surface painter rendering crisp print-preview page sheets inside [GtkBookContainerNativeImpl].
 */
object GtkBookContainerWidget {

    fun paintPageSheet(
        cr: COpaquePointer,
        width: Int,
        height: Int,
        darkTheme: Boolean,
        documentTitle: String,
        opacity: Float
    ) {
        if (width <= 0 || height <= 0) return
        val backdropBg = if (darkTheme) RgbaColor(32, 34, 39, 255) else RgbaColor(232, 235, 240, 255)
        Gtk4DynamicBindings.cairoSave(cr)
        Gtk4DynamicBindings.cairoSetSourceRgba(
            cr,
            backdropBg.r / 255.0,
            backdropBg.g / 255.0,
            backdropBg.b / 255.0,
            opacity.toDouble()
        )
        Gtk4DynamicBindings.cairoRectangle(cr, 0.0, 0.0, width.toDouble(), height.toDouble())
        Gtk4DynamicBindings.cairoFill(cr)
        Gtk4DynamicBindings.cairoRestore(cr)

        val pad = 14
        val sheetW = (width - pad * 2).coerceAtLeast(16)
        val sheetH = (height - pad * 2).coerceAtLeast(16)
        Gtk4DynamicBindings.cairoSave(cr)
        Gtk4DynamicBindings.cairoTranslate(cr, pad.toDouble(), pad.toDouble())
        val sheetSpec = GtkSurfacePaintSpec(
            backgroundColor = RgbaColor.WHITE,
            borderColor = RgbaColor(200, 205, 214, 255),
            borderWidthPx = 1f,
            fallbackRadiusPx = 4f,
            boxShadowColor = RgbaColor(0, 0, 0, 48),
            boxShadowBlurPx = 10f,
            opacity = opacity,
            darkTheme = darkTheme
        )
        GtkSurfaceRenderHelper.paintSurface(cr, sheetW, sheetH, sheetSpec)
        Gtk4DynamicBindings.cairoRestore(cr)
    }
}
