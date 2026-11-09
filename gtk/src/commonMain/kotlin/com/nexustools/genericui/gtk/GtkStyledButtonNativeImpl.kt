package com.nexustools.genericui.gtk

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.desktop.widget.DesktopStyledButtonNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.render.GtkControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.style.transition.CssTransitionSpec

/**
 * GTK4 / Cairo `GtkButton` implementation of [com.nexustools.genericui.nativeimpl.action.StyledButtonNativeImpl]
 * supporting translucent RGBA backgrounds, linear/radial gradients, rounded borders, focus rings, and interactive
 * `:hover`, `:focus`, `:active`, and `:disabled` states while ignoring `backdrop-filter` blur on desktop.
 */
class GtkStyledButtonNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkButton_Styled"),
    classPrefix: String = "styled-btn"
) : DesktopStyledButtonNativeImpl<GtkWidgetHandle>(nativeWidget, classPrefix) {

    init {
        nativeWidget.attributes["useSystemControlAppearance"] = "false"
        syncGtkStyledButtonVisuals()
    }

    private fun syncGtkStyledButtonVisuals() {
        val effectiveTheme = Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()
        val isDark = effectiveTheme == ThemeMode.DARK
        nativeWidget.attributes["effectiveThemeMode"] = effectiveTheme
        nativeWidget.attributes["gtkPreferDarkTheme"] = isDark
        nativeWidget.attributes["adwColorScheme"] = if (isDark) "ADW_COLOR_SCHEME_FORCE_DARK" else "ADW_COLOR_SCHEME_FORCE_LIGHT"
        nativeWidget.attributes["useSystemControlAppearance"] = "false"
        nativeWidget.attributes.remove("backdropBlurPx")
        nativeWidget.attributes.remove("backdropFilter")

        val resolved = resolveCurrentVisuals()
        nativeWidget.attributes["styledButtonVisuals"] = resolved
        resolved.backgroundColor?.let { nativeWidget.attributes["backgroundColor"] = it }
        resolved.backgroundGradient?.let { nativeWidget.attributes["backgroundGradient"] = it }
        resolved.textColor?.let { nativeWidget.attributes["textColor"] = it }
        resolved.borderColor?.let { nativeWidget.attributes["borderColor"] = it }
        resolved.borderWidthPx?.let { nativeWidget.attributes["borderWidthPx"] = it }
        resolved.borderStyle?.let { nativeWidget.attributes["borderStyle"] = it }
        resolved.borderRadiusPx?.let { nativeWidget.attributes["borderRadiusPx"] = it }
        resolved.focusRingColor?.let { nativeWidget.attributes["focusRingColor"] = it }
        resolved.focusRingWidthPx?.let { nativeWidget.attributes["focusRingWidthPx"] = it }
        resolved.opacity?.let {
            val op = it.coerceIn(0f, 1f)
            nativeWidget.opacity = op
            nativeWidget.attributes["opacity"] = op
        }
        resolved.padding?.let { nativeWidget.padding = it }
        nativeWidget.notifyTreeInvalidated()
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        GtkControlRenderUtil.renderStyledButton(this, renderContext, bounds)

    override fun setAttribute(name: String, value: String?) {
        if (
            name.equals("backdropBlurPx", ignoreCase = true) ||
            name.equals("backdrop-filter", ignoreCase = true) ||
            name.equals("-webkit-backdrop-filter", ignoreCase = true)
        ) {
            nativeWidget.attributes.remove("backdropBlurPx")
            return
        }
        super.setAttribute(name, value)
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        syncGtkStyledButtonVisuals()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        syncGtkStyledButtonVisuals()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        syncGtkStyledButtonVisuals()
    }

    override fun resolveCurrentVisuals(): StyledVisualSpec {
        val isDark = (Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()) == ThemeMode.DARK
        val computed = getComputedStyle()
        val computedBase = StyledVisualSpec.fromComponentAndComputed(
            componentStyle = lastComponentStyle,
            computedStyle = computed,
            snapshot = styleSnapshot,
            darkTheme = isDark
        )
        var effective = computedBase.mergeWith(getBaseVisuals())

        if (!nativeWidget.enabled) {
            val computedDisabled = computed?.disabledStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(computedDisabled).mergeWith(getDisabledVisuals())
            return effective
        }

        if (isHoveredState()) {
            val computedHover = computed?.hoverStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(computedHover).mergeWith(getHoverVisuals())
        }

        if (isFocusedState()) {
            val computedFocus = computed?.focusStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(computedFocus).mergeWith(getFocusVisuals())
        }

        if (isActivePressedState()) {
            val computedActive = computed?.activeStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(computedActive).mergeWith(getActiveVisuals())
        }

        return effective
    }

    override fun runNativeTransition(
        property: String,
        fromValue: String?,
        toValue: String,
        spec: CssTransitionSpec,
        onComplete: () -> Unit
    ): Boolean {
        val handled = super.runNativeTransition(property, fromValue, toValue, spec, onComplete)
        if (property.equals("opacity", ignoreCase = true)) {
            val fromAlpha = (fromValue?.toFloatOrNull() ?: getOpacity()).coerceIn(0f, 1f)
            val targetAlpha = (toValue.toFloatOrNull() ?: getOpacity()).coerceIn(0f, 1f)
            nativeWidget.attributes["transitionFromOpacity"] = fromAlpha
            nativeWidget.attributes["transitionToOpacity"] = targetAlpha
            setOpacity(targetAlpha)
            if (spec.durationMs <= 0f) {
                onComplete()
            }
        }
        return handled
    }
}
