@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.widget

import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.linux.style.LinuxStyledSurfaceHelper
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import kotlinx.cinterop.COpaquePointer
import kotlin.math.ceil
import kotlin.math.max

/**
 * Custom retained `GtkDrawingArea` widget rendering crisp Bootstrap `Badge` pills / rounded tags
 * when `DesktopTextNativeImpl.isBadgeRole()` holds.
 */
object GtkBadgeWidget {

    fun paintBadge(
        impl: DesktopTextNativeImpl<GtkWidgetHandle>,
        cr: COpaquePointer,
        width: Int,
        height: Int
    ) {
        if (width <= 0 || height <= 0) return
        val dark = GtkRetainedContainerSupport.resolveDarkTheme()
        val handle = impl.nativeWidget
        val attrs = handle.attributes
        val pill = attrs["pill"] as? Boolean ?: ("rounded-pill" in handle.gtkCssClasses)
        val bType = attrs["bootstrapType"] as? BootstrapType
        val fallbackBg = when (bType) {
            BootstrapType.PRIMARY -> RgbaColor(13, 110, 253, 255)
            BootstrapType.SECONDARY -> RgbaColor(108, 117, 125, 255)
            BootstrapType.SUCCESS -> RgbaColor(25, 135, 84, 255)
            BootstrapType.DANGER -> RgbaColor(220, 53, 69, 255)
            BootstrapType.WARNING -> RgbaColor(255, 193, 7, 255)
            BootstrapType.INFO -> RgbaColor(13, 202, 240, 255)
            BootstrapType.LIGHT -> RgbaColor(248, 249, 250, 255)
            BootstrapType.DARK -> RgbaColor(33, 37, 41, 255)
            BootstrapType.NONE, BootstrapType.LINK, null -> BootstrapColorPalette.resolveColor(BootstrapType.SECONDARY, dark)
        }
        val bg = attrs["backgroundColor"] as? RgbaColor ?: fallbackBg
        val fg = LinuxStyledSurfaceHelper.resolveWidgetTextColor(handle)
            ?: if (bType == BootstrapType.WARNING || bType == BootstrapType.INFO || bType == BootstrapType.LIGHT) {
                RgbaColor(33, 37, 41, 255)
            } else {
                RgbaColor.WHITE
            }

        val radius = if (pill) (height * 0.5f) else (attrs["borderRadiusPx"] as? Float ?: 6f)
        val spec = GtkSurfacePaintSpec(
            backgroundColor = bg,
            borderColor = attrs["borderColor"] as? RgbaColor,
            borderWidthPx = attrs["borderWidthPx"] as? Float ?: 0f,
            fallbackRadiusPx = radius,
            opacity = impl.getOpacity(),
            darkTheme = dark
        )
        GtkSurfaceRenderHelper.paintSurface(cr, width, height, spec)

        val text = impl.nativeWidget.attributes["text"] as? String ?: ""
        if (text.isNotEmpty()) {
            val fontSize = (attrs["fontSizePx"] as? Float) ?: 11.5f
            GtkSurfaceRenderHelper.drawPangoText(
                cr = cr,
                text = text,
                x = 4.0,
                y = 0.0,
                width = (width - 8.0).coerceAtLeast(1.0),
                height = height.toDouble(),
                color = fg,
                fontSizePx = fontSize,
                fontWeight = Gtk4DynamicBindings.PANGO_WEIGHT_SEMIBOLD,
                align = Gtk4DynamicBindings.PANGO_ALIGN_CENTER,
                singleLine = true,
                opacity = impl.getOpacity()
            )
        }
    }

    fun measureBadgeSize(text: String, fontSizePx: Float = 11.5f): Pair<Float, Float> {
        val (tw, th) = GtkSurfaceRenderHelper.measureTextSize(
            text = text.ifEmpty { "0" },
            fontSizePx = fontSizePx,
            fontWeight = Gtk4DynamicBindings.PANGO_WEIGHT_SEMIBOLD
        )
        val w = max(22f, ceil(tw + 14f))
        val h = max(18f, ceil(th + 6f))
        return w to h
    }
}
