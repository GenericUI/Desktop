package com.nexustools.genericui.gtk.widget

import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCornerRadii
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.gradient.Gradient.Gradient

/**
 * Resolved Cairo / GSK surface paint attributes for retained GTK4 containers, buttons, badges,
 * semantic surfaces, and custom titlebars.
 */
data class GtkSurfacePaintSpec(
    val backgroundColor: RgbaColor? = null,
    val backgroundGradient: Gradient? = null,
    val textColor: RgbaColor? = null,
    val borderColor: RgbaColor? = null,
    val borderWidthPx: Float = 0f,
    val borderStyle: CssBorderStyle? = null,
    val cornerRadii: CssCornerRadii? = null,
    val fallbackRadiusPx: Float = 0f,
    val boxShadowColor: RgbaColor? = null,
    val boxShadowBlurPx: Float = 0f,
    val boxShadowOffsetXPx: Float = 0f,
    val boxShadowOffsetYPx: Float = 2f,
    val focusRingColor: RgbaColor? = null,
    val focusRingWidthPx: Float = 0f,
    val insetHighlightColor: RgbaColor? = null,
    val backdropFilter: CssFilter? = null,
    val backdropBlurPx: Float = 0f,
    val foregroundFilter: CssFilter? = null,
    val opacity: Float = 1f,
    val darkTheme: Boolean = false
)
