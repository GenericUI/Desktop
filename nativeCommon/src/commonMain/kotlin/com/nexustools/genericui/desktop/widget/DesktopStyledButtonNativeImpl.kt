package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.action.StyledButtonNativeImpl
import com.nexustools.genericui.style.css.StyledVisualSpec

/**
 * Shared desktop base implementation of [StyledButtonNativeImpl] across `:windows`, `:macos`, `:gtk`, and `:qt`.
 *
 * Resolves base, `:hover`, `:focus`, `:active`, and `:disabled` [StyledVisualSpec] layers so native desktop
 * renderers (such as `Win32NativeWindowHost` on `:windows`, AppKit on `:macos`, Cairo/GTK on `:gtk`, and
 * `QPainter` on `:qt`) draw custom translucent/gradient surfaces, rounded borders, focus rings, and state highlights.
 */
open class DesktopStyledButtonNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    classPrefix: String = "styled-btn"
) : DesktopButtonNativeImpl<W>(nativeWidget, classPrefix), StyledButtonNativeImpl<W> {

    private var baseVisuals: StyledVisualSpec = StyledVisualSpec()
    private var hoverVisuals: StyledVisualSpec? = null
    private var focusVisuals: StyledVisualSpec? = null
    private var activeVisuals: StyledVisualSpec? = null
    private var disabledVisuals: StyledVisualSpec? = null
    private var cachedResolvedVisuals: StyledVisualSpec? = null

    private fun syncResolvedVisualsToWidget(visualOnly: Boolean = false) {
        cachedResolvedVisuals = null
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
        nativeWidget.notifyTreeInvalidated(visualOnly = visualOnly)
    }

    override fun applyComponentStyle(style: com.nexustools.genericui.style.css.ComponentStyle) {
        cachedResolvedVisuals = null
        super<DesktopButtonNativeImpl>.applyComponentStyle(style)
    }

    override fun applyComputedStyle(style: com.nexustools.genericui.style.css.ComputedStyle) {
        cachedResolvedVisuals = null
        super<DesktopButtonNativeImpl>.applyComputedStyle(style)
    }

    override fun setBaseVisuals(visuals: StyledVisualSpec) {
        baseVisuals = visuals
        nativeWidget.attributes["baseVisuals"] = visuals
        syncResolvedVisualsToWidget()
    }

    override fun getBaseVisuals(): StyledVisualSpec = baseVisuals

    override fun setHoverVisuals(visuals: StyledVisualSpec?) {
        hoverVisuals = visuals
        if (visuals != null) nativeWidget.attributes["hoverVisuals"] = visuals else nativeWidget.attributes.remove("hoverVisuals")
        syncResolvedVisualsToWidget()
    }

    override fun getHoverVisuals(): StyledVisualSpec? = hoverVisuals

    override fun setFocusVisuals(visuals: StyledVisualSpec?) {
        focusVisuals = visuals
        if (visuals != null) nativeWidget.attributes["focusVisuals"] = visuals else nativeWidget.attributes.remove("focusVisuals")
        syncResolvedVisualsToWidget()
    }

    override fun getFocusVisuals(): StyledVisualSpec? = focusVisuals

    override fun setActiveVisuals(visuals: StyledVisualSpec?) {
        activeVisuals = visuals
        if (visuals != null) nativeWidget.attributes["activeVisuals"] = visuals else nativeWidget.attributes.remove("activeVisuals")
        syncResolvedVisualsToWidget()
    }

    override fun getActiveVisuals(): StyledVisualSpec? = activeVisuals

    override fun setDisabledVisuals(visuals: StyledVisualSpec?) {
        disabledVisuals = visuals
        if (visuals != null) nativeWidget.attributes["disabledVisuals"] = visuals else nativeWidget.attributes.remove("disabledVisuals")
        syncResolvedVisualsToWidget()
    }

    override fun getDisabledVisuals(): StyledVisualSpec? = disabledVisuals

    override fun setHoveredState(hovered: Boolean) {
        val effective = hovered && nativeWidget.enabled
        val changed = isHoveredState() != effective
        super<DesktopButtonNativeImpl>.setHoveredState(effective)
        if (changed) {
            syncResolvedVisualsToWidget(visualOnly = true)
        }
    }

    override fun setFocusedState(focused: Boolean) {
        val effective = focused && nativeWidget.enabled
        val changed = isFocusedState() != effective
        super<DesktopButtonNativeImpl>.setFocusedState(effective)
        if (changed) {
            syncResolvedVisualsToWidget(visualOnly = true)
        }
    }

    override fun setActivePressedState(active: Boolean) {
        val effective = active && nativeWidget.enabled
        val changed = isActivePressedState() != effective
        super<DesktopButtonNativeImpl>.setActivePressedState(effective)
        if (changed) {
            syncResolvedVisualsToWidget(visualOnly = true)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        val changed = isEnabled() != enabled || (!enabled && (isHoveredState() || isFocusedState() || isActivePressedState()))
        super<DesktopButtonNativeImpl>.setEnabled(enabled)
        if (changed) {
            syncResolvedVisualsToWidget()
        }
    }

    override fun resolveCurrentVisuals(): StyledVisualSpec {
        cachedResolvedVisuals?.let { return it }
        val computed = getComputedStyle()
        val computedBase = StyledVisualSpec.fromComponentAndComputed(lastComponentStyle, computed)
        var effective = computedBase.mergeWith(baseVisuals)

        if (!nativeWidget.enabled) {
            val computedDisabled = computed?.disabledStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(computedDisabled).mergeWith(disabledVisuals)
            cachedResolvedVisuals = effective
            return effective
        }

        if (isHoveredState()) {
            val computedHover = computed?.hoverStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(computedHover).mergeWith(hoverVisuals)
        }

        if (isFocusedState()) {
            val computedFocus = computed?.focusStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(computedFocus).mergeWith(focusVisuals)
        }

        if (isActivePressedState()) {
            val computedActive = computed?.activeStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(computedActive).mergeWith(activeVisuals)
        }

        cachedResolvedVisuals = effective
        return effective
    }
}
