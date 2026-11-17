package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapSize
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.render.GtkControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.style.transition.CssTransitionSpec

/**
 * GTK4 / Libadwaita `GtkButton` implementation of [com.nexustools.genericui.nativeimpl.action.ButtonNativeImpl].
 *
 * Non-styled [GtkButtonNativeImpl] instances use native GTK4 / Libadwaita control rendering (`ADW_COLOR_SCHEME_FORCE_DARK` /
 * `ADW_COLOR_SCHEME_FORCE_LIGHT` / `ADW_COLOR_SCHEME_DEFAULT` for Dark / Light / Auto themes), ignore aesthetic and
 * typography `ComponentStyle` overrides unless hosted in a custom style engine, and support [BootstrapType] tinting
 * via [GtkControlRenderUtil].
 */
open class GtkButtonNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkButton"),
    classPrefix: String = "btn"
) : DesktopButtonNativeImpl<GtkWidgetHandle>(nativeWidget, classPrefix) {

    init {
        syncGtkButtonAppearance()
    }

    protected open fun syncGtkButtonAppearance() {
        GtkControlRenderUtil.syncStandardControlAppearance(this, "gtk_render_background+gtk_render_frame[button]")
    }

    override fun measureDesktopNode(parentWidthPx: Int, parentHeightPx: Int, dpiScale: Float): Size =
        GtkControlRenderUtil.measureButton(this, dpiScale)

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        GtkControlRenderUtil.renderButton(this, renderContext, bounds)

    override fun setText(text: String) {
        super.setText(text)
        syncGtkButtonAppearance()
    }

    override fun setIcon(icon: String?) {
        super.setIcon(icon)
        syncGtkButtonAppearance()
    }

    override fun setSize(size: BootstrapSize?) {
        super.setSize(size)
        syncGtkButtonAppearance()
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
        syncGtkButtonAppearance()
    }

    override fun setBackgroundType(type: BootstrapType?) {
        super.setBackgroundType(type)
        syncGtkButtonAppearance()
    }

    override fun setTextColorType(type: BootstrapType?) {
        super.setTextColorType(type)
        syncGtkButtonAppearance()
    }

    override fun setOutline(outline: Boolean) {
        super.setOutline(outline)
        syncGtkButtonAppearance()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        syncGtkButtonAppearance()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        syncGtkButtonAppearance()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        syncGtkButtonAppearance()
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
