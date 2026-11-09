package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.macos.render.MacosControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.style.transition.CssTransitionSpec

/**
 * macOS AppKit `NSStackView` / `NSView` implementation of [com.nexustools.genericui.nativeimpl.layout.ContainerNativeImpl].
 *
 * Non-styled [MacosContainerNativeImpl] instances use native AppKit appearance (`NSAppearanceNameAqua` /
 * `NSAppearanceNameDarkAqua` for Light / Dark / Auto themes), ignore aesthetic and typography `ComponentStyle`
 * overrides unless hosted in a custom style engine, and support [BootstrapType] tinting via
 * [MacosControlRenderUtil].
 */
open class MacosContainerNativeImpl(
    nativeWidget: MacosViewHandle = MacosViewHandle("NSStackView"),
    classPrefix: String = "container"
) : DesktopContainerNativeImpl<MacosViewHandle>(nativeWidget, classPrefix) {

    init {
        syncMacosContainerAppearance()
    }

    protected open fun syncMacosContainerAppearance() {
        MacosControlRenderUtil.syncStandardControlAppearance(this, "NSStackView_NSBox")
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        MacosControlRenderUtil.renderContainer(this, renderContext, bounds)

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

    override fun setBackgroundGradient(gradient: Gradient?) {
        super.setBackgroundGradient(null)
        nativeWidget.attributes.remove("backgroundGradient")
    }

    override fun getBackgroundGradient(): Gradient? = null

    override fun setType(type: BootstrapType?) {
        super.setType(type)
        syncMacosContainerAppearance()
    }

    override fun setBackgroundType(type: BootstrapType?) {
        super.setBackgroundType(type)
        syncMacosContainerAppearance()
    }

    override fun setTextColorType(type: BootstrapType?) {
        super.setTextColorType(type)
        syncMacosContainerAppearance()
    }

    override fun setOutline(outline: Boolean) {
        super.setOutline(outline)
        syncMacosContainerAppearance()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        syncMacosContainerAppearance()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        syncMacosContainerAppearance()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        syncMacosContainerAppearance()
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
