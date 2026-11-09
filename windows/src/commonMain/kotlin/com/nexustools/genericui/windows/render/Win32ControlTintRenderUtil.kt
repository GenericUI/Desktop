@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.render

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.render.DesktopControlTintRenderUtil
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.windows.Win32DwmUxThemeBridge
import com.nexustools.genericui.windows.Win32NativeWindowHost
import platform.windows.HFONT

/**
 * Bridges `Win32DwmUxThemeBridge` offscreen empty-text `uxtheme.dll` control capture with
 * [DesktopControlTintRenderUtil.tintNativeControlPixels] and post-tint text/icon rendering.
 *
 * Ensures that:
 * 1. When `BootstrapType == BootstrapType.NONE` (or `null`), the 100% unmodified native `uxtheme.dll`
 *    control is drawn and the native text/icon is rendered on top.
 * 2. When `BootstrapType != BootstrapType.NONE`, the native `uxtheme.dll` control is rendered with
 *    NO text into an offscreen 32-bpp ARGB buffer, tinted via [DesktopControlTintRenderUtil] using
 *    the active `ThemeConfig` color, blitted to the target HDC/backbuffer, and then the contrasting
 *    text/icon is drawn on top afterward.
 */
object Win32ControlTintRenderUtil {

    /**
     * Resolves the effective [BootstrapType] for [widget] from its [DesktopBaseNativeImpl] snapshot
     * or Bootstrap class tokens, returning `null` when `BootstrapType.NONE` or unset.
     */
    fun resolveWidgetBootstrapType(widget: DesktopNativeWidget): BootstrapType? {
        val impl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val snapshotType = impl?.getType()?.takeIf { it != BootstrapType.NONE }
            ?: impl?.getBackgroundType()?.takeIf { it != BootstrapType.NONE }
        if (snapshotType != null) return snapshotType

        val tokens = widget.cachedAllClassTokens
        return when {
            "btn-primary" in tokens || "bg-primary" in tokens || "alert-primary" in tokens || "text-bg-primary" in tokens -> BootstrapType.PRIMARY
            "btn-danger" in tokens || "bg-danger" in tokens || "alert-danger" in tokens || "text-bg-danger" in tokens -> BootstrapType.DANGER
            "btn-success" in tokens || "bg-success" in tokens || "alert-success" in tokens || "text-bg-success" in tokens -> BootstrapType.SUCCESS
            "btn-warning" in tokens || "bg-warning" in tokens || "alert-warning" in tokens || "text-bg-warning" in tokens -> BootstrapType.WARNING
            "btn-info" in tokens || "bg-info" in tokens || "alert-info" in tokens || "text-bg-info" in tokens -> BootstrapType.INFO
            "btn-secondary" in tokens || "bg-secondary" in tokens || "alert-secondary" in tokens || "text-bg-secondary" in tokens -> BootstrapType.SECONDARY
            "btn-light" in tokens || "bg-light" in tokens || "alert-light" in tokens || "text-bg-light" in tokens -> BootstrapType.LIGHT
            "btn-dark" in tokens || "bg-dark" in tokens || "alert-dark" in tokens || "text-bg-dark" in tokens -> BootstrapType.DARK
            "btn-link" in tokens -> BootstrapType.LINK
            "btn-outline-primary" in tokens || "border-primary" in tokens -> BootstrapType.PRIMARY
            "btn-outline-danger" in tokens || "border-danger" in tokens -> BootstrapType.DANGER
            "btn-outline-success" in tokens || "border-success" in tokens -> BootstrapType.SUCCESS
            "btn-outline-warning" in tokens || "border-warning" in tokens -> BootstrapType.WARNING
            "btn-outline-info" in tokens || "border-info" in tokens -> BootstrapType.INFO
            "btn-outline-secondary" in tokens || "border-secondary" in tokens -> BootstrapType.SECONDARY
            "btn-outline-light" in tokens || "border-light" in tokens -> BootstrapType.LIGHT
            "btn-outline-dark" in tokens || "border-dark" in tokens -> BootstrapType.DARK
            else -> null
        }
    }

    /**
     * Returns whether [widget] is configured as an outline control (`bootstrap.outline == true` or
     * `btn-outline-*`).
     */
    fun isWidgetOutlineMode(widget: DesktopNativeWidget): Boolean {
        val impl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        if (impl?.isOutline() == true) return true
        return widget.cachedAllClassTokens.any { it.startsWith("btn-outline-") }
    }

    /**
     * Resolves the target [RgbaColor] tint for [widget] in [ctx], returning `null` when
     * `BootstrapType.NONE` (or unset) so the unmodified native `uxtheme.dll` control is rendered.
     */
    fun resolveControlTintOrNull(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        explicitType: BootstrapType? = null
    ): RgbaColor? {
        val type = (explicitType ?: resolveWidgetBootstrapType(widget))
            ?.takeIf { it != BootstrapType.NONE }
            ?: return null
        return BootstrapColorPalette.resolveTintOrNull(type, ctx.darkMode, ctx.themeConfig)
    }

    /**
     * Resolves the Win32 `COLORREF` (`0x00BBGGRR`) for the control foreground text/icon.
     * When a [BootstrapType] tint is active, returns the contrasting foreground color from
     * [DesktopControlTintRenderUtil.resolveContrastingForeground]; otherwise returns the native
     * system control text color from [ctx].
     */
    fun resolveControlForegroundColorRef(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        explicitType: BootstrapType? = null,
        hovered: Boolean = false,
        pressed: Boolean = false,
        disabled: Boolean = !widget.enabled
    ): UInt {
        if (disabled) {
            return ctx.textMutedColorRef
        }
        val outline = isWidgetOutlineMode(widget)
        val tint = resolveControlTintOrNull(ctx, widget, explicitType)
        if (tint != null) {
            val fgRgba = DesktopControlTintRenderUtil.resolveContrastingForeground(
                tint = tint,
                outlineMode = outline,
                hovered = hovered,
                pressed = pressed
            )
            return ctx.toColorRef(fgRgba)
        }
        return ctx.textPrimaryColorRef
    }

    /**
     * Draws an empty-text native `uxtheme.dll` control background for [baseClass] (`"BUTTON"`,
     * `"EDIT"`, `"COMBOBOX"`, `"PROGRESS"`, `"TRACKBAR"`, `"TAB"`, `"HEADER"`, `"SPIN"`,
     * `"TOOLBAR"`, `"STATUS"`), applying [DesktopControlTintRenderUtil] tinting when a
     * [BootstrapType] is active on [widget] (or [explicitType]).
     */
    fun drawNativeOrTintedControlSurface(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        baseClass: String,
        partId: Int,
        stateId: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        explicitType: BootstrapType? = null,
        hovered: Boolean = ctx.isWidgetHovered(widget, left, top, right, bottom),
        pressed: Boolean = ctx.isWidgetPressed(widget),
        disabled: Boolean = !widget.enabled
    ): Boolean {
        val type = explicitType ?: resolveWidgetBootstrapType(widget)
        val outline = isWidgetOutlineMode(widget)
        return Win32DwmUxThemeBridge.drawBootstrapThemedControlBackground(
            hwnd = ctx.hwnd,
            hdc = ctx.hdc,
            baseClass = baseClass,
            partId = partId,
            stateId = stateId,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            bootstrapType = type,
            darkMode = ctx.darkMode,
            outlineMode = outline,
            hovered = hovered,
            pressed = pressed,
            disabled = disabled,
            themeConfig = ctx.themeConfig
        )
    }

    /**
     * Draws a complete native `uxtheme.dll` control surface (unmodified when `BootstrapType.NONE`,
     * or empty-text `uxtheme.dll` sprite + [DesktopControlTintRenderUtil] tint when `BootstrapType != NONE`)
     * followed by the crisp post-tint text and optional icon overlay on top.
     */
    fun drawNativeOrTintedControlWithTextOverlay(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        baseClass: String,
        partId: Int,
        stateId: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        text: String,
        icon: String? = null,
        font: HFONT? = ctx.fontNormal,
        centerText: Boolean = true,
        textPaddingLeft: Int = 10,
        textPaddingRight: Int = 10,
        explicitType: BootstrapType? = null,
        hovered: Boolean = ctx.isWidgetHovered(widget, left, top, right, bottom),
        pressed: Boolean = ctx.isWidgetPressed(widget),
        disabled: Boolean = !widget.enabled
    ): Boolean {
        val drewBg = drawNativeOrTintedControlSurface(
            ctx = ctx,
            widget = widget,
            baseClass = baseClass,
            partId = partId,
            stateId = stateId,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            explicitType = explicitType,
            hovered = hovered,
            pressed = pressed,
            disabled = disabled
        )
        val cleanIcon = icon?.trim()?.takeIf { it.isNotEmpty() }
        val cleanText = text.trim()
        val displayLabel = when {
            cleanIcon != null && cleanText.isNotEmpty() -> "$cleanIcon  $cleanText"
            cleanIcon != null -> cleanIcon
            else -> cleanText
        }
        if (displayLabel.isNotEmpty()) {
            ctx.selectFont(font)
            val fgColorRef = resolveControlForegroundColorRef(
                ctx = ctx,
                widget = widget,
                explicitType = explicitType,
                hovered = hovered,
                pressed = pressed,
                disabled = disabled
            )
            val pressOffset = if (pressed && !disabled) 1 else 0
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = displayLabel,
                left = left + textPaddingLeft,
                top = top + pressOffset,
                right = (right - textPaddingRight).coerceAtLeast(left + textPaddingLeft + 1),
                bottom = bottom + pressOffset,
                colorRef = fgColorRef,
                center = centerText
            )
        }
        return drewBg
    }
}
