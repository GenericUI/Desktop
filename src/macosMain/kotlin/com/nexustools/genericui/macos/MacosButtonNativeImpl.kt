package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapSize
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.render.MacosControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.style.transition.CssTransitionSpec

/**
 * macOS AppKit `NSButton` implementation of [com.nexustools.genericui.nativeimpl.action.ButtonNativeImpl].
 *
 * Non-styled [MacosButtonNativeImpl] instances use native AppKit control rendering (`NSAppearanceNameAqua` /
 * `NSAppearanceNameDarkAqua` for Light / Dark / Auto themes), ignore aesthetic and typography `ComponentStyle`
 * overrides unless hosted in a custom style engine, and support [BootstrapType] tinting via
 * [MacosControlRenderUtil].
 */
open class MacosButtonNativeImpl(
    nativeWidget: MacosViewHandle = MacosViewHandle("NSButton"),
    classPrefix: String = "btn"
) : DesktopButtonNativeImpl<MacosViewHandle>(nativeWidget, classPrefix) {

    init {
        syncMacosButtonAppearance()
    }

    protected open fun syncMacosButtonAppearance() {
        MacosControlRenderUtil.syncStandardControlAppearance(this, "NSButtonCell_NSBezelStyleRounded")
    }

    override fun measureDesktopNode(parentWidthPx: Int, parentHeightPx: Int, dpiScale: Float): Size =
        MacosControlRenderUtil.measureButton(this, dpiScale)

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        MacosControlRenderUtil.renderButton(this, renderContext, bounds)

    override fun setText(text: String) {
        super.setText(text)
        syncMacosButtonAppearance()
    }

    override fun setIcon(icon: String?) {
        super.setIcon(icon)
        syncMacosButtonAppearance()
    }

    override fun setSize(size: BootstrapSize?) {
        super.setSize(size)
        syncMacosButtonAppearance()
    }

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
        syncMacosButtonAppearance()
    }

    override fun setBackgroundType(type: BootstrapType?) {
        super.setBackgroundType(type)
        syncMacosButtonAppearance()
    }

    override fun setTextColorType(type: BootstrapType?) {
        super.setTextColorType(type)
        syncMacosButtonAppearance()
    }

    override fun setOutline(outline: Boolean) {
        super.setOutline(outline)
        syncMacosButtonAppearance()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        syncMacosButtonAppearance()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        syncMacosButtonAppearance()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        syncMacosButtonAppearance()
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
