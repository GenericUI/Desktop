package com.nexustools.genericui.macos

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.desktop.widget.DesktopStyledButtonNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.macos.render.MacosControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.style.transition.CssTransitionSpec

/**
 * macOS AppKit `NSButton` + `CALayer` implementation of [com.nexustools.genericui.nativeimpl.action.StyledButtonNativeImpl]
 * supporting translucent RGBA backgrounds, linear/radial gradients, rounded borders, focus rings, and interactive
 * `:hover`, `:focus`, `:active`, and `:disabled` states while ignoring `backdrop-filter` blur on desktop.
 */
class MacosStyledButtonNativeImpl(
    nativeWidget: MacosViewHandle = MacosViewHandle("NSButton_Styled"),
    classPrefix: String = "styled-btn"
) : DesktopStyledButtonNativeImpl<MacosViewHandle>(nativeWidget, classPrefix) {

    init {
        nativeWidget.attributes["useSystemControlAppearance"] = "false"
        syncMacosStyledButtonVisuals()
    }

    private fun syncMacosStyledButtonVisuals() {
        val effectiveTheme = Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()
        val isDark = effectiveTheme == ThemeMode.DARK
        nativeWidget.attributes["effectiveThemeMode"] = effectiveTheme
        nativeWidget.attributes["nsAppearance"] = if (isDark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua"
        nativeWidget.attributes["useSystemControlAppearance"] = "false"

        val resolved = resolveCurrentVisuals()
        nativeWidget.attributes["styledButtonVisuals"] = resolved
        val blurPx = resolved.backdropBlurPx ?: resolved.backdropFilter?.blur?.resolvePx()
        if (blurPx != null && blurPx > 0f) {
            nativeWidget.attributes["backdropBlurPx"] = blurPx
        } else {
            nativeWidget.attributes.remove("backdropBlurPx")
        }
        val bf = resolved.backdropFilter
        if (bf != null && !bf.isNone) {
            nativeWidget.attributes["backdropFilter"] = bf
        } else {
            nativeWidget.attributes.remove("backdropFilter")
        }
        if (resolved.backgroundColor != null) nativeWidget.attributes["backgroundColor"] = resolved.backgroundColor else nativeWidget.attributes.remove("backgroundColor")
        if (resolved.backgroundGradient != null) nativeWidget.attributes["backgroundGradient"] = resolved.backgroundGradient else nativeWidget.attributes.remove("backgroundGradient")
        if (resolved.textColor != null) nativeWidget.attributes["textColor"] = resolved.textColor else nativeWidget.attributes.remove("textColor")
        if (resolved.borderColor != null) nativeWidget.attributes["borderColor"] = resolved.borderColor else nativeWidget.attributes.remove("borderColor")
        if (resolved.borderWidthPx != null) nativeWidget.attributes["borderWidthPx"] = resolved.borderWidthPx else nativeWidget.attributes.remove("borderWidthPx")
        if (resolved.borderStyle != null) nativeWidget.attributes["borderStyle"] = resolved.borderStyle else nativeWidget.attributes.remove("borderStyle")
        if (resolved.borderRadiusPx != null) nativeWidget.attributes["borderRadiusPx"] = resolved.borderRadiusPx else nativeWidget.attributes.remove("borderRadiusPx")
        if (resolved.focusRingColor != null) nativeWidget.attributes["focusRingColor"] = resolved.focusRingColor else nativeWidget.attributes.remove("focusRingColor")
        if (resolved.focusRingWidthPx != null) nativeWidget.attributes["focusRingWidthPx"] = resolved.focusRingWidthPx else nativeWidget.attributes.remove("focusRingWidthPx")
        resolved.opacity?.let {
            val op = it.coerceIn(0f, 1f)
            nativeWidget.opacity = op
            nativeWidget.attributes["opacity"] = op
        }
        resolved.padding?.let { nativeWidget.padding = it }
        nativeWidget.notifyTreeInvalidated()
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        MacosControlRenderUtil.renderStyledButton(this, renderContext, bounds)

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        syncMacosStyledButtonVisuals()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        syncMacosStyledButtonVisuals()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        syncMacosStyledButtonVisuals()
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
