package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.style.DesktopStyledWidgetDelegate
import com.nexustools.genericui.nativeimpl.action.StyledButtonNativeImpl
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.StyledVisualSpec

/**
 * Shared desktop base implementation of [StyledButtonNativeImpl] across `:windows`, `:macos`, `:gtk`, and `:kde`.
 *
 * Resolves base, `:hover`, `:focus`, `:active`, and `:disabled` [StyledVisualSpec] layers so native desktop
 * renderers (such as `Win32NativeWindowHost` on `:windows`, AppKit on `:macos`, Cairo/GTK on `:gtk`, and
 * `QPainter` on `:kde`) draw custom translucent/gradient surfaces, rounded borders, focus rings, and state highlights.
 */
open class DesktopStyledButtonNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    classPrefix: String = "styled-btn"
) : DesktopButtonNativeImpl<W>(nativeWidget, classPrefix), StyledButtonNativeImpl<W> {

    private var delegateInitialized: Boolean = false

    private val styledDelegate = DesktopStyledWidgetDelegate(
        owner = this,
        visualsAttributeKey = "styledButtonVisuals",
        allowsBootstrapTypeTint = true,
        onOpacitySynced = { op -> applyResolvedOpacity(op, notifyTree = false) },
        onStyleSynced = { notifyStyleSyncedIfReady() }
    )

    init {
        nativeWidget.attributes["componentRole"] = "styled-button"
        nativeWidget.attributes["useSystemControlAppearance"] = "false"
        delegateInitialized = true
    }

    override fun syncStyleToNativeWidget() {
        super<DesktopButtonNativeImpl>.syncStyleToNativeWidget()
        if (delegateInitialized) {
            styledDelegate.syncToWidget()
        }
    }

    protected fun syncResolvedVisualsToWidget(visualOnly: Boolean = false) {
        styledDelegate.syncToWidget(visualOnly = visualOnly)
    }

    override fun acceptsAestheticSkinStyles(): Boolean = true

    override fun acceptsTypographyStyles(): Boolean = true

    override fun acceptsBootstrapSemanticColors(): Boolean = true

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        styledDelegate.invalidate()
        super<DesktopButtonNativeImpl>.applyComponentBootstrap(bootstrap)
        styledDelegate.syncToWidget()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        styledDelegate.invalidate()
        super<DesktopButtonNativeImpl>.applyComponentStyle(style)
        styledDelegate.syncToWidget()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        styledDelegate.invalidate()
        super<DesktopButtonNativeImpl>.applyComputedStyle(style)
        styledDelegate.syncToWidget()
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
        super<DesktopButtonNativeImpl>.setHoveredState(effective)
        if (changed) {
            styledDelegate.syncToWidget(visualOnly = true)
        }
    }

    override fun setFocusedState(focused: Boolean) {
        val effective = focused && nativeWidget.enabled && isFocusable() && nativeWidget.isEffectivelyFocusable()
        val changed = isFocusedState() != effective
        super<DesktopButtonNativeImpl>.setFocusedState(effective)
        if (changed) {
            styledDelegate.syncToWidget(visualOnly = true)
        }
    }

    override fun setActivePressedState(active: Boolean) {
        val effective = active && nativeWidget.enabled
        val changed = isActivePressedState() != effective
        super<DesktopButtonNativeImpl>.setActivePressedState(effective)
        if (changed) {
            styledDelegate.syncToWidget(visualOnly = true)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        val changed = isEnabled() != enabled || (!enabled && (isHoveredState() || isFocusedState() || isActivePressedState()))
        super<DesktopButtonNativeImpl>.setEnabled(enabled)
        if (changed) {
            styledDelegate.syncToWidget()
        }
    }

    override fun resolveCurrentVisuals(): StyledVisualSpec = styledDelegate.resolveCurrentVisuals()

    override fun setAttribute(name: String, value: String?) {
        super<DesktopButtonNativeImpl>.setAttribute(name, value)
        applyDesktopStyledFilterAttribute(
            name = name,
            value = value,
            getBase = { getBaseVisuals() },
            setBase = { setBaseVisuals(it) }
        )
    }
}
