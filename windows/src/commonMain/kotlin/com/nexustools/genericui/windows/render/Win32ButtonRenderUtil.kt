@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.render

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledButtonNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapSize
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.windows.Win32DwmUxThemeBridge
import com.nexustools.genericui.windows.Win32NativeWindowHost
import platform.windows.FW_BOLD
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders action button components on Windows (`Button`, `IconButton`, `ToggleButton`,
 * `SplitButton`, `FloatingActionButton`, `StyledButton`).
 *
 * - For standard buttons (`Button`, `IconButton`, `ToggleButton`, `SplitButton`, `FloatingActionButton`):
 *   - Draws 100% native `uxtheme.dll` `BP_PUSHBUTTON` when `BootstrapType.NONE` (or unset).
 *   - Draws an offscreen empty-text native `uxtheme.dll` `BP_PUSHBUTTON` sprite tinted with the
 *     active `ThemeConfig` color (`DesktopControlTintRenderUtil.tintNativeControlPixels`) when
 *     `BootstrapType != NONE`, and overlays the contrasting text and icon on top afterward.
 *   - Ignores aesthetic skin and typography `ComponentStyle` properties unless the button is a
 *     `StyledButton` or `component.isHostedInCustomStyleEngine()` is `true`.
 * - For `StyledButton` (or buttons hosted inside a custom style engine):
 *   - Honors full `ComponentStyle` and `StyledVisualSpec` background, gradient, border, radius,
 *     focus ring, and typography properties.
 */
object Win32ButtonRenderUtil {

    fun canRender(widget: DesktopNativeWidget): Boolean {
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopButtonNativeImpl<*>) return true
        val cls = widget.nativeClassName.lowercase()
        val role = (widget.attributes["componentRole"] as? String ?: "").lowercase()
        return cls in BUTTON_CLASSES || role in BUTTON_ROLES
    }

    fun render(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int? = null
    ): Int {
        val btnImpl = widget.attributes["nativeImpl"] as? DesktopButtonNativeImpl<*>
        val baseImpl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val availW = (right - left).coerceAtLeast(32)
        val isFullWidthBtn = Win32NativeWindowHost.widgetHasClass(widget, "w-100") ||
            btnImpl?.resolveBootstrapWidthFraction() == 1.0f ||
            baseImpl?.getComponentStyle()?.width?.toCssString() == "100%"
        val effectiveRight = if (fixedHeight != null || isFullWidthBtn) {
            right
        } else {
            min(right, left + max(32, ctx.measureInlineWidth(widget, availW)))
        }
        val height = fixedHeight ?: resolveButtonHeight(widget, btnImpl)
        val bottom = top + height
        widget.bounds = Rect(left, top, (effectiveRight - left).coerceAtLeast(1), height)

        val acceptsSkin = btnImpl is DesktopStyledButtonNativeImpl<*> ||
            baseImpl?.acceptsAestheticSkinStyles() == true
        if (acceptsSkin) {
            renderCustomStyledButton(ctx, widget, btnImpl, left, top, effectiveRight, bottom)
            drawCustomButtonIconIfPresent(ctx, widget, left, top, effectiveRight, bottom)
            return bottom
        }

        val role = (widget.attributes["componentRole"] as? String ?: "").lowercase()
        val cls = widget.nativeClassName.lowercase()
        when {
            role == "split-button" || cls == "splitbutton" ->
                renderSplitButton(ctx, widget, btnImpl, left, top, effectiveRight, bottom)
            else ->
                renderStandardNativeButton(ctx, widget, btnImpl, left, top, effectiveRight, bottom)
        }
        drawCustomButtonIconIfPresent(ctx, widget, left, top, effectiveRight, bottom)
        return bottom
    }

    /**
     * Renders a standard native Windows button (`Button`, `IconButton`, `ToggleButton`,
     * `FloatingActionButton`) using `uxtheme.dll` `BP_PUSHBUTTON` (unmodified when
     * `BootstrapType.NONE`, or empty-text `uxtheme.dll` sprite + `ThemeConfig` tint + post-tint
     * text/icon overlay when `BootstrapType != NONE`).
     */
    fun renderStandardNativeButton(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        btnImpl: DesktopButtonNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val hovered = ctx.isWidgetHovered(widget, left, top, right, bottom)
        val pressed = ctx.isWidgetPressed(widget) ||
            widget.attributes["toggled"] == true ||
            widget.attributes["active"] == true ||
            "active" in widget.cachedAllClassTokens
        val disabled = !widget.enabled

        val stateId = when {
            disabled -> Win32DwmUxThemeBridge.PBS_DISABLED
            pressed -> Win32DwmUxThemeBridge.PBS_PRESSED
            hovered -> Win32DwmUxThemeBridge.PBS_HOT
            else -> Win32DwmUxThemeBridge.PBS_NORMAL
        }

        val label = btnImpl?.getText()
            ?: (widget.attributes["text"] as? String)
            ?: Win32NativeWindowHost.extractWidgetText(widget)
        val icon = btnImpl?.getIcon() ?: (widget.attributes["icon"] as? String)
        val font = when (btnImpl?.getSize()) {
            BootstrapSize.SM -> ctx.fontSmall
            BootstrapSize.LG -> ctx.fontBold ?: ctx.fontNormal
            else -> ctx.fontNormal
        }

        Win32ControlTintRenderUtil.drawNativeOrTintedControlWithTextOverlay(
            ctx = ctx,
            widget = widget,
            baseClass = "BUTTON",
            partId = Win32DwmUxThemeBridge.BP_PUSHBUTTON,
            stateId = stateId,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            text = label,
            icon = icon,
            font = font,
            centerText = true,
            explicitType = btnImpl?.getType(),
            hovered = hovered,
            pressed = pressed,
            disabled = disabled
        )

        registerButtonHitRegion(ctx, widget, btnImpl, left, top, right, bottom, label)
    }

    /**
     * Renders a native `SplitButton` with a primary action region on the left and a native
     * dropdown chevron segment on the right.
     */
    fun renderSplitButton(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        btnImpl: DesktopButtonNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val dropW = 26.coerceAtMost((right - left) / 3)
        val splitX = (right - dropW).coerceAtLeast(left + 16)
        val hovered = ctx.isWidgetHovered(widget, left, top, right, bottom)
        val pressed = ctx.isWidgetPressed(widget)
        val disabled = !widget.enabled

        val stateId = when {
            disabled -> Win32DwmUxThemeBridge.PBS_DISABLED
            pressed -> Win32DwmUxThemeBridge.PBS_PRESSED
            hovered -> Win32DwmUxThemeBridge.PBS_HOT
            else -> Win32DwmUxThemeBridge.PBS_NORMAL
        }

        val label = btnImpl?.getText()
            ?: (widget.attributes["text"] as? String)
            ?: Win32NativeWindowHost.extractWidgetText(widget)
        val icon = btnImpl?.getIcon() ?: (widget.attributes["icon"] as? String)

        Win32ControlTintRenderUtil.drawNativeOrTintedControlWithTextOverlay(
            ctx = ctx,
            widget = widget,
            baseClass = "BUTTON",
            partId = Win32DwmUxThemeBridge.BP_PUSHBUTTON,
            stateId = stateId,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            text = "",
            hovered = hovered,
            pressed = pressed,
            disabled = disabled
        )

        val fgColorRef = Win32ControlTintRenderUtil.resolveControlForegroundColorRef(
            ctx = ctx,
            widget = widget,
            explicitType = btnImpl?.getType(),
            hovered = hovered,
            pressed = pressed,
            disabled = disabled
        )
        val displayLabel = if (!icon.isNullOrBlank() && label.isNotBlank()) "$icon  $label" else (icon?.ifBlank { null } ?: label)
        ctx.selectFont(ctx.fontNormal)
        Win32NativeWindowHost.drawTextInRect(
            hdc = ctx.hdc,
            text = displayLabel,
            left = left + 8,
            top = top,
            right = splitX - 4,
            bottom = bottom,
            colorRef = fgColorRef,
            center = true
        )
        // Divider line & dropdown arrow
        Win32NativeWindowHost.fillRectColor(ctx.hdc, splitX, top + 4, splitX + 1, bottom - 4, ctx.borderSubtleColorRef)
        Win32NativeWindowHost.drawTextInRect(
            hdc = ctx.hdc,
            text = "▾",
            left = splitX + 2,
            top = top,
            right = right - 2,
            bottom = bottom,
            colorRef = fgColorRef,
            center = true
        )

        registerButtonHitRegion(ctx, widget, btnImpl, left, top, right, bottom, label)
    }

    /**
     * Renders a `StyledButton` (or a button hosted in a custom style engine) honoring its full
     * `StyledVisualSpec` and `ComponentStyle` skin & typography properties.
     */
    fun renderCustomStyledButton(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        btnImpl: DesktopButtonNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val hovered = ctx.isWidgetHovered(widget, left, top, right, bottom)
        val pressed = ctx.isWidgetPressed(widget)
        val styledBtn = btnImpl as? DesktopStyledButtonNativeImpl<*>
        styledBtn?.setHoveredState(hovered)
        styledBtn?.setActivePressedState(pressed)

        val visuals = styledBtn?.resolveCurrentVisuals()
        val compStyle = (btnImpl as? DesktopBaseNativeImpl<*>)?.getComponentStyle()
        val label = btnImpl?.getText() ?: (widget.attributes["text"] as? String).orEmpty()
        val icon = btnImpl?.getIcon() ?: (widget.attributes["icon"] as? String)

        val bgGradient = visuals?.backgroundGradient ?: compStyle?.backgroundImage?.gradient
        val fillCol = visuals?.backgroundColor
            ?: compStyle?.backgroundColor
            ?: Win32ControlTintRenderUtil.resolveControlTintOrNull(ctx, widget, btnImpl?.getType())
            ?: RgbaColor(42, 48, 60, 220)
        val borderCol = visuals?.borderColor
            ?: compStyle?.borderColor
            ?: RgbaColor(255, 255, 255, if (hovered) 84 else 42)
        val borderWidth = visuals?.borderWidthPx
            ?: compStyle?.borderWidth?.top?.resolvePx()
            ?: 1f
        val radiusPx = visuals?.borderRadiusPx
            ?: compStyle?.borderRadius?.topLeft?.resolvePx()
            ?: 6f

        if (bgGradient != null) {
            Win32NativeWindowHost.drawGradientRect(ctx.hdc, left, top, right, bottom, bgGradient, opacity = ctx.opacity)
        } else {
            Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
                hdc = ctx.hdc,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                radiusPx = radiusPx,
                fillColor = fillCol,
                borderColor = borderCol,
                borderWidthPx = borderWidth,
                opacity = ctx.opacity
            )
        }

        val fontSizePx = compStyle?.fontSize?.resolvePx(14f)?.roundToInt() ?: 14
        val fontWeight = compStyle?.fontWeightNumeric ?: FW_BOLD
        val font = ctx.obtainFont(
            familyName = compStyle?.fontFamily?.toCssString(),
            sizePx = fontSizePx,
            weight = fontWeight
        )
        ctx.selectFont(font)

        val fgRgba = visuals?.textColor
            ?: compStyle?.color
            ?: RgbaColor(255, 255, 255, 255)
        val displayLabel = when {
            !icon.isNullOrBlank() && label.isNotBlank() -> "$icon  $label"
            !icon.isNullOrBlank() -> icon
            else -> label
        }
        if (displayLabel.isNotEmpty()) {
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = displayLabel,
                left = left + 8,
                top = top + 2,
                right = right - 8,
                bottom = bottom - 2,
                colorRef = ctx.toColorRef(fgRgba),
                center = true
            )
        }

        registerButtonHitRegion(ctx, widget, btnImpl, left, top, right, bottom, label)
    }

    private fun resolveButtonHeight(
        widget: DesktopNativeWidget,
        btnImpl: DesktopButtonNativeImpl<*>?
    ): Int {
        widget.preferredSize?.height?.takeIf { it > 0 }?.let { return it }
        return when (btnImpl?.getSize()) {
            BootstrapSize.SM -> 28
            BootstrapSize.LG -> 36
            else -> 28
        }
    }

    private fun drawCustomButtonIconIfPresent(
        ctx: Win32RenderContext,
        buttonWidget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val imgChild = ctx.visibleChildrenOf(buttonWidget).firstOrNull {
            it.attributes["nativeImpl"] is DesktopImageNativeImpl<*>
        } ?: return
        val imgImpl = imgChild.attributes["nativeImpl"] as DesktopImageNativeImpl<*>
        val url = imgImpl.getSourceUrl()?.trim().orEmpty()
        if (url.isNotEmpty()) {
            val padX = ((right - left - 18).coerceAtLeast(4)) / 2
            val padY = ((bottom - top - 18).coerceAtLeast(4)) / 2
            val placeholderUrl = imgImpl.getPlaceholderUrl()?.trim().takeUnless { it.isNullOrEmpty() }
                ?: ImageLoader.DEFAULT_PLACEHOLDER_URI
            ctx.drawImageBoxCallback?.invoke(
                ctx,
                imgChild,
                url,
                placeholderUrl,
                imgImpl.isAntialiasing(),
                "",
                left + padX,
                top + padY,
                (right - padX).coerceAtLeast(left + padX + 12),
                (bottom - padY).coerceAtLeast(top + padY + 12),
                ctx.bgCardColorRef
            )
        }
    }

    private fun registerButtonHitRegion(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        btnImpl: DesktopButtonNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        label: String
    ) {
        val baseImpl = (btnImpl as? DesktopBaseNativeImpl<*>)
            ?: (widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)
        val tooltip = baseImpl?.getTooltipText()?.takeIf { it.isNotBlank() && it != label }
        ctx.registerHitRegion(
            Win32NativeWindowHost.HitRegion(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                tooltip = tooltip,
                widget = widget,
                onClick = { _, _ -> baseImpl?.dispatchClick() }
            )
        )
    }

    private val BUTTON_CLASSES = setOf(
        "button",
        "iconbutton",
        "togglebutton",
        "splitbutton",
        "floatingactionbutton",
        "styledbutton"
    )
    private val BUTTON_ROLES = setOf(
        "button",
        "icon-button",
        "toggle-button",
        "split-button",
        "fab",
        "floating-action-button",
        "styled-button"
    )
}
