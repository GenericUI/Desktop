package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.style.DesktopStyledWidgetDelegate
import com.nexustools.genericui.nativeimpl.surface.StyledComponentNativeImpl
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.StyledVisualSpec

/**
 * Shared desktop base implementation of [StyledComponentNativeImpl] across `:windows`, `:macos`, `:gtk`, and `:kde`.
 *
 * Resolves base, `:hover`, `:focus`, `:active`, and `:disabled` [StyledVisualSpec] layers for
 * CSS-styled visual leaf components (`<genericui:styled-component>`) while strictly ignoring
 * `BootstrapType` semantic tinting per [com.nexustools.genericui.style.policy.AestheticStylePolicy].
 */
open class DesktopStyledComponentNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    classPrefix: String = "styled-component",
    val tagName: String = "genericui:styled-component"
) : DesktopBaseNativeImpl<W>(nativeWidget, classPrefix), StyledComponentNativeImpl<W> {

    private var delegateInitialized: Boolean = false

    private val styledDelegate = DesktopStyledWidgetDelegate(
        owner = this,
        visualsAttributeKey = "styledComponentVisuals",
        allowsBootstrapTypeTint = false,
        onOpacitySynced = { op -> applyResolvedOpacity(op, notifyTree = false) },
        onStyleSynced = { notifyStyleSyncedIfReady() }
    )

    init {
        nativeWidget.attributes["componentRole"] = "styled-component"
        nativeWidget.attributes["tagName"] = tagName
        nativeWidget.attributes["useSystemControlAppearance"] = "false"
        delegateInitialized = true
    }

    override fun syncStyleToNativeWidget() {
        super<DesktopBaseNativeImpl>.syncStyleToNativeWidget()
        if (delegateInitialized) {
            styledDelegate.syncToWidget()
        }
    }

    protected fun syncResolvedVisualsToWidget(visualOnly: Boolean = false) {
        styledDelegate.syncToWidget(visualOnly = visualOnly)
    }

    override fun acceptsAestheticSkinStyles(): Boolean = true

    override fun acceptsTypographyStyles(): Boolean = true

    override fun acceptsBootstrapSemanticColors(): Boolean = isHostedInCustomStyleEngine()

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        styledDelegate.invalidate()
        super<DesktopBaseNativeImpl>.applyComponentBootstrap(bootstrap)
        styledDelegate.syncToWidget()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        styledDelegate.invalidate()
        super<DesktopBaseNativeImpl>.applyComponentStyle(style)
        styledDelegate.syncToWidget()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        styledDelegate.invalidate()
        super<DesktopBaseNativeImpl>.applyComputedStyle(style)
        styledDelegate.syncToWidget()
    }

    override fun setStyledVisuals(visuals: StyledVisualSpec) {
        styledDelegate.setBaseVisuals(visuals)
    }

    override fun getStyledVisuals(): StyledVisualSpec = styledDelegate.resolveBaseVisuals()

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
        super<DesktopBaseNativeImpl>.setHoveredState(effective)
        if (changed) {
            styledDelegate.syncToWidget(visualOnly = true)
        }
    }

    override fun setFocusedState(focused: Boolean) {
        val effective = focused && nativeWidget.enabled && isFocusable() && nativeWidget.isEffectivelyFocusable()
        val changed = isFocusedState() != effective
        super<DesktopBaseNativeImpl>.setFocusedState(effective)
        if (changed) {
            styledDelegate.syncToWidget(visualOnly = true)
        }
    }

    override fun setActivePressedState(active: Boolean) {
        val effective = active && nativeWidget.enabled
        val changed = isActivePressedState() != effective
        super<DesktopBaseNativeImpl>.setActivePressedState(effective)
        if (changed) {
            styledDelegate.syncToWidget(visualOnly = true)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        val changed = isEnabled() != enabled || (!enabled && (isHoveredState() || isFocusedState() || isActivePressedState()))
        super<DesktopBaseNativeImpl>.setEnabled(enabled)
        if (changed) {
            styledDelegate.syncToWidget()
        }
    }

    override fun resolveCurrentVisuals(): StyledVisualSpec = styledDelegate.resolveCurrentVisuals()
}
