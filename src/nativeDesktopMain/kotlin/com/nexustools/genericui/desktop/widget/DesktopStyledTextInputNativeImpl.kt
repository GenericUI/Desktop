package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.style.DesktopStyledWidgetDelegate
import com.nexustools.genericui.nativeimpl.form.StyledTextInputNativeImpl
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.StyledVisualSpec

/**
 * Shared desktop base implementation of [StyledTextInputNativeImpl] across `:windows`, `:macos`, `:gtk`, and `:kde`.
 *
 * Resolves base, `:hover`, `:focus`, `:active`, and `:disabled` [StyledVisualSpec] layers while preserving
 * full typography (`fontSizePx`, `fontFamily`, `fontWeightNumeric`), placeholder color (`placeholderColor`),
 * rounded pill geometry, borders, gradients, focus rings, and padding across native backends.
 */
abstract class DesktopStyledTextInputNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    multiline: Boolean = false,
    classPrefix: String = if (multiline) "styled-textarea" else "styled-textfield"
) : DesktopTextInputNativeImpl<W>(nativeWidget, multiline, classPrefix), StyledTextInputNativeImpl<W> {

    private var delegateInitialized: Boolean = false

    private val styledDelegate = DesktopStyledWidgetDelegate(
        owner = this,
        visualsAttributeKey = "styledTextInputVisuals",
        allowsBootstrapTypeTint = false,
        onOpacitySynced = { op -> applyResolvedOpacity(op, notifyTree = false) },
        onStyleSynced = {
            if (delegateInitialized) {
                applyStyledVisuals(resolveCurrentVisuals())
                notifyStyleSyncedIfReady()
            }
        }
    )

    init {
        nativeWidget.attributes["componentRole"] = "styled-text-input"
        nativeWidget.attributes["useSystemControlAppearance"] = "false"
        delegateInitialized = true
    }

    protected abstract fun applyStyledVisuals(visuals: StyledVisualSpec)

    protected fun refreshStyledVisuals(visualOnly: Boolean = false) {
        if (!delegateInitialized) return
        styledDelegate.syncToWidget(visualOnly = visualOnly)
    }

    override fun syncStyleToNativeWidget() {
        super<DesktopTextInputNativeImpl>.syncStyleToNativeWidget()
        refreshStyledVisuals()
    }

    override fun acceptsAestheticSkinStyles(): Boolean = true

    override fun acceptsTypographyStyles(): Boolean = true

    override fun acceptsBootstrapSemanticColors(): Boolean = isHostedInCustomStyleEngine()

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        styledDelegate.invalidate()
        super<DesktopTextInputNativeImpl>.applyComponentBootstrap(bootstrap)
        refreshStyledVisuals()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        styledDelegate.invalidate()
        super<DesktopTextInputNativeImpl>.applyComponentStyle(style)
        refreshStyledVisuals()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        styledDelegate.invalidate()
        super<DesktopTextInputNativeImpl>.applyComputedStyle(style)
        refreshStyledVisuals()
    }

    override fun setBaseVisuals(visuals: StyledVisualSpec) {
        styledDelegate.setBaseVisuals(visuals)
    }

    override fun getBaseVisuals(): StyledVisualSpec = styledDelegate.getBaseVisuals()

    override fun setHoverVisuals(visuals: StyledVisualSpec?) {
        styledDelegate.setHoverVisuals(visuals)
    }

    override fun getHoverVisuals(): StyledVisualSpec? = styledDelegate.getHoverVisuals()

    override fun setFocusVisuals(visuals: StyledVisualSpec?) {
        styledDelegate.setFocusVisuals(visuals)
    }

    override fun getFocusVisuals(): StyledVisualSpec? = styledDelegate.getFocusVisuals()

    override fun setActiveVisuals(visuals: StyledVisualSpec?) {
        styledDelegate.setActiveVisuals(visuals)
    }

    override fun getActiveVisuals(): StyledVisualSpec? = styledDelegate.getActiveVisuals()

    override fun setDisabledVisuals(visuals: StyledVisualSpec?) {
        styledDelegate.setDisabledVisuals(visuals)
    }

    override fun getDisabledVisuals(): StyledVisualSpec? = styledDelegate.getDisabledVisuals()

    override fun setHoveredState(hovered: Boolean) {
        val effective = hovered && nativeWidget.enabled
        val changed = isHoveredState() != effective
        super<DesktopTextInputNativeImpl>.setHoveredState(effective)
        if (changed) {
            refreshStyledVisuals(visualOnly = true)
        }
    }

    override fun setFocusedState(focused: Boolean) {
        val effective = focused && nativeWidget.enabled && isFocusable() && nativeWidget.isEffectivelyFocusable()
        val changed = isFocusedState() != effective
        super<DesktopTextInputNativeImpl>.setFocusedState(effective)
        if (changed) {
            refreshStyledVisuals(visualOnly = true)
        }
    }

    override fun setActivePressedState(active: Boolean) {
        val effective = active && nativeWidget.enabled
        val changed = isActivePressedState() != effective
        super<DesktopTextInputNativeImpl>.setActivePressedState(effective)
        if (changed) {
            refreshStyledVisuals(visualOnly = true)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        val changed = isEnabled() != enabled || (!enabled && (isHoveredState() || isFocusedState() || isActivePressedState()))
        super<DesktopTextInputNativeImpl>.setEnabled(enabled)
        if (changed) {
            refreshStyledVisuals()
        }
    }

    override fun resolveCurrentVisuals(): StyledVisualSpec = styledDelegate.resolveCurrentVisuals()
}
