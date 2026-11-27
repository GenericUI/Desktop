package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.style.DesktopStyledWidgetDelegate
import com.nexustools.genericui.nativeimpl.layout.StyledContainerNativeImpl
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.policy.AestheticStylePolicy

/**
 * Shared desktop base implementation of [StyledContainerNativeImpl] across `:windows`, `:macos`, `:gtk`, and `:kde`.
 */
open class DesktopStyledContainerNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    classPrefix: String = "styled-container",
    tagName: String = "div"
) : DesktopContainerNativeImpl<W>(nativeWidget, classPrefix, tagName), StyledContainerNativeImpl<W> {

    private var delegateInitialized: Boolean = false

    private val styledDelegate = DesktopStyledWidgetDelegate(
        owner = this,
        visualsAttributeKey = "styledContainerVisuals",
        allowsBootstrapTypeTint = AestheticStylePolicy.isBootstrapSpecificNonControlTagOrPrefix(tagName, classPrefix),
        onOpacitySynced = { op -> applyResolvedOpacity(op, notifyTree = false) },
        onStyleSynced = { notifyStyleSyncedIfReady() }
    )

    init {
        nativeWidget.attributes["componentRole"] = "styled-container"
        nativeWidget.attributes["useSystemControlAppearance"] = "false"
        delegateInitialized = true
    }

    override fun syncStyleToNativeWidget() {
        super<DesktopContainerNativeImpl>.syncStyleToNativeWidget()
        if (delegateInitialized) {
            styledDelegate.syncToWidget()
        }
    }

    protected fun syncResolvedVisualsToWidget(visualOnly: Boolean = false) {
        styledDelegate.syncToWidget(visualOnly = visualOnly)
    }

    override fun setStyledVisuals(visuals: StyledVisualSpec) {
        if (styledDelegate.getBaseVisuals() == visuals && nativeWidget.attributes["styledVisuals"] == visuals) return
        styledDelegate.setBaseVisuals(visuals)
    }

    override fun acceptsAestheticSkinStyles(): Boolean = true

    override fun acceptsTypographyStyles(): Boolean = true

    override fun acceptsBootstrapSemanticColors(): Boolean =
        isHostedInCustomStyleEngine() ||
            AestheticStylePolicy.isBootstrapSpecificNonControlTagOrPrefix(tagName, componentClassPrefix)

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        styledDelegate.invalidate()
        super<DesktopContainerNativeImpl>.applyComponentBootstrap(bootstrap)
        styledDelegate.syncToWidget()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        styledDelegate.invalidate()
        super<DesktopContainerNativeImpl>.applyComponentStyle(style)
        styledDelegate.syncToWidget()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        styledDelegate.invalidate()
        super<DesktopContainerNativeImpl>.applyComputedStyle(style)
        styledDelegate.syncToWidget()
    }

    override fun getStyledVisuals(): StyledVisualSpec = styledDelegate.resolveBaseVisuals()
}
