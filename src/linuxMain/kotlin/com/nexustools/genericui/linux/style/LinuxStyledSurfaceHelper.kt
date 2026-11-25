package com.nexustools.genericui.linux.style

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.linux.titlebar.LinuxOverlayTitleBarResolver
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarProfile
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCornerRadii
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssFilterColorMatrix
import com.nexustools.genericui.style.css.GradientStopNormalizer
import com.nexustools.genericui.style.css.NormalizedLinearGradient
import com.nexustools.genericui.style.css.NormalizedRadialGradient
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.style.gradient.Gradient.LinearGradient
import com.nexustools.genericui.style.gradient.Gradient.RadialGradient
import kotlin.math.max
import kotlin.math.min

/**
 * Shared Linux styled-widget surface geometry, border stroke dash, gradient normalization,
 * color matrix, and overlay titlebar adapter helpers for GTK4 and KDE renderers.
 *
 * Strictly toolkit-agnostic: zero imports from `gtk` or `kde`.
 */
object LinuxStyledSurfaceHelper {

    /**
     * Clamps a uniform corner [radiusPx] to half of the smaller box dimension (`[widthPx] x [heightPx]`).
     */
    fun clampCornerRadius(radiusPx: Float, widthPx: Float, heightPx: Float): Float {
        if (widthPx <= 0f || heightPx <= 0f || radiusPx <= 0f) return 0f
        return min(radiusPx, min(widthPx, heightPx) * 0.5f).coerceAtLeast(0f)
    }

    /**
     * Resolves CSS Backgrounds & Borders Level 3 clamped corner radii `[topLeft, topRight, bottomRight, bottomLeft]`
     * in pixels for a box of size `[widthPx] x [heightPx]`, shrinking proportionally when adjacent corner sums
     * exceed the available side length.
     */
    fun resolveClampedCornerRadii(
        radii: CssCornerRadii?,
        fallbackRadiusPx: Float?,
        widthPx: Float,
        heightPx: Float
    ): FloatArray {
        if (widthPx <= 0f || heightPx <= 0f) return FloatArray(4)
        var tl: Float
        var tr: Float
        var br: Float
        var bl: Float
        if (radii != null) {
            tl = (radii.topLeft.resolvePx(widthPx) ?: 0f).coerceAtLeast(0f)
            tr = (radii.topRight.resolvePx(widthPx) ?: 0f).coerceAtLeast(0f)
            br = (radii.bottomRight.resolvePx(widthPx) ?: 0f).coerceAtLeast(0f)
            bl = (radii.bottomLeft.resolvePx(widthPx) ?: 0f).coerceAtLeast(0f)
        } else {
            val uniform = (fallbackRadiusPx ?: 0f).coerceAtLeast(0f)
            tl = uniform
            tr = uniform
            br = uniform
            bl = uniform
        }
        var scale = 1f
        if (tl + tr > widthPx) scale = min(scale, widthPx / (tl + tr))
        if (bl + br > widthPx) scale = min(scale, widthPx / (bl + br))
        if (tl + bl > heightPx) scale = min(scale, heightPx / (tl + bl))
        if (tr + br > heightPx) scale = min(scale, heightPx / (tr + br))
        if (scale < 1f) {
            tl *= scale
            tr *= scale
            br *= scale
            bl *= scale
        }
        return floatArrayOf(tl, tr, br, bl)
    }

    /**
     * Resolves a clamped uniform corner radius from [spec] within `[widthPx] x [heightPx]`.
     */
    fun resolveUniformCornerRadiusPx(
        spec: StyledVisualSpec,
        widthPx: Float,
        heightPx: Float
    ): Float = clampCornerRadius(spec.borderRadiusPx ?: 0f, widthPx, heightPx)

    /**
     * Returns `true` when [borderStyle] and [borderWidthPx] represent a visible border stroke.
     */
    fun isVisibleBorder(borderStyle: CssBorderStyle?, borderWidthPx: Float): Boolean =
        borderWidthPx > 0f && borderStyle != CssBorderStyle.NONE && borderStyle != CssBorderStyle.HIDDEN

    /**
     * Returns `true` when [borderStyle] should be stroked as a CSS `double` border (`borderWidthPx >= 3px`).
     */
    fun isDoubleBorder(borderStyle: CssBorderStyle?, borderWidthPx: Float): Boolean =
        borderStyle == CssBorderStyle.DOUBLE && borderWidthPx >= 3f

    /**
     * Returns pixel dash/gap lengths for `SOLID`, `DASHED`, `DOTTED`, and `DOUBLE` borders
     * (empty array for solid/continuous strokes).
     */
    fun resolveDashPatternPx(borderStyle: CssBorderStyle?, borderWidthPx: Float): FloatArray {
        val w = borderWidthPx.coerceAtLeast(1f)
        return when (borderStyle) {
            CssBorderStyle.DASHED -> floatArrayOf(max(4f, w * 3f), max(3f, w * 2f))
            CssBorderStyle.DOTTED -> floatArrayOf(w, max(2f, w * 1.5f))
            CssBorderStyle.SOLID, CssBorderStyle.DOUBLE, null -> FloatArray(0)
            else -> FloatArray(0)
        }
    }

    /**
     * Double-precision dash array variant of [resolveDashPatternPx] for Cairo `cairo_set_dash`.
     */
    fun resolveDashPatternDoubles(borderStyle: CssBorderStyle?, borderWidthPx: Float): DoubleArray {
        val px = resolveDashPatternPx(borderStyle, borderWidthPx)
        return if (px.isEmpty()) DoubleArray(0) else DoubleArray(px.size) { px[it].toDouble() }
    }

    /**
     * Computes `[strokeWidthPx, outerInsetPx, innerInsetPx]` for rendering a CSS `double` border
     * within a box border of total thickness [borderWidthPx].
     */
    fun resolveDoubleBorderMetrics(borderWidthPx: Float): FloatArray {
        if (borderWidthPx < 3f) {
            val w = borderWidthPx.coerceAtLeast(0f)
            return floatArrayOf(w, w * 0.5f, w * 0.5f)
        }
        val lineW = max(1f, borderWidthPx / 3f)
        val outerInset = lineW * 0.5f
        val innerInset = borderWidthPx - lineW * 0.5f
        return floatArrayOf(lineW, outerInset, innerInset)
    }

    /**
     * Normalizes a [LinearGradient] into pixel coordinates and monotonic `0f..1f` stops via [GradientStopNormalizer].
     */
    fun normalizeLinearGradient(
        gradient: LinearGradient,
        widthPx: Float,
        heightPx: Float
    ): NormalizedLinearGradient = GradientStopNormalizer.normalizeLinear(gradient, widthPx, heightPx)

    /**
     * Normalizes a [RadialGradient] into pixel center/radii and monotonic `0f..1f` stops via [GradientStopNormalizer].
     */
    fun normalizeRadialGradient(
        gradient: RadialGradient,
        widthPx: Float,
        heightPx: Float
    ): NormalizedRadialGradient = GradientStopNormalizer.normalizeRadial(gradient, widthPx, heightPx)

    /**
     * Resolves the active [Gradient] from [spec] or fallback widget attributes.
     */
    fun resolveEffectiveGradient(
        spec: StyledVisualSpec?,
        widget: DesktopNativeWidget? = null
    ): Gradient? = spec?.backgroundGradient ?: (widget?.attributes?.get("backgroundGradient") as? Gradient)

    /**
     * Builds the canonical 20-element 4x5 RGBA color matrix for [filter] via [CssFilterColorMatrix].
     */
    fun buildFilterColorMatrix4x5(filter: CssFilter): FloatArray =
        CssFilterColorMatrix.buildMatrix4x5(filter)

    /**
     * Resolves any active overlay backdrop filter on [widget] via [LinuxOverlayTitleBarResolver].
     */
    fun resolveOverlayBackdropFilter(
        widget: DesktopNativeWidget,
        styledVisuals: StyledVisualSpec? = widget.attributes["styledVisuals"] as? StyledVisualSpec
    ): CssFilter? = LinuxOverlayTitleBarResolver.resolveBackdropFilter(widget, styledVisuals)

    /**
     * Resolves the foreground text color for [widget] via [LinuxOverlayTitleBarResolver].
     */
    fun resolveWidgetTextColor(widget: DesktopNativeWidget?): RgbaColor? =
        LinuxOverlayTitleBarResolver.resolveWidgetTextColorRgba(widget)

    /**
     * Resolves full Linux overlay titlebar appearance via [LinuxOverlayTitleBarResolver].
     */
    fun resolveOverlayTitleBarAppearance(
        windowTitleBar: DesktopNativeWidget,
        titleLabelWidget: DesktopNativeWidget?,
        effectiveProfile: LinuxTitleBarProfile,
        contextIsDark: Boolean,
        isUndecoratedWindow: Boolean,
        isMaximized: Boolean
    ): LinuxOverlayTitleBarResolver.ResolvedOverlayTitleBarAppearance =
        LinuxOverlayTitleBarResolver.resolveAppearance(
            windowTitleBar = windowTitleBar,
            titleLabelWidget = titleLabelWidget,
            effectiveProfile = effectiveProfile,
            contextIsDark = contextIsDark,
            isUndecoratedWindow = isUndecoratedWindow,
            isMaximized = isMaximized
        )

    /**
     * Anchors a Linux popup surface cleanly below/above [anchorBounds] inside [viewport].
     */
    fun computeAnchoredPopupRect(
        anchorBounds: Rect,
        popupWidthPx: Int,
        popupHeightPx: Int,
        viewport: Rect,
        gapPx: Int = 2,
        marginPx: Int = 2
    ): Rect = LinuxOverlayTitleBarResolver.computeAnchoredPopupRect(
        anchorBounds = anchorBounds,
        popupWidthPx = popupWidthPx,
        popupHeightPx = popupHeightPx,
        viewport = viewport,
        gapPx = gapPx,
        marginPx = marginPx
    )

    /**
     * Resolves an 8-direction undecorated resize edge bitmask via [LinuxOverlayTitleBarResolver].
     */
    fun computeResizeEdgeBitmask(
        width: Int,
        height: Int,
        x: Float,
        y: Float,
        borderPx: Float = 6f,
        hitOverInteractiveControl: Boolean = false,
        perimeterPx: Float = 2f
    ): Int = LinuxOverlayTitleBarResolver.computeResizeEdgeBitmask(
        width = width,
        height = height,
        x = x,
        y = y,
        borderPx = borderPx,
        hitOverInteractiveControl = hitOverInteractiveControl,
        perimeterPx = perimeterPx
    )
}
