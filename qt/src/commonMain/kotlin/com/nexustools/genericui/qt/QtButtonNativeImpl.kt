package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapSize
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.qt.render.QtControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.style.transition.CssTransitionSpec

/**
 * Qt6 `QPushButton` implementation of [com.nexustools.genericui.nativeimpl.action.ButtonNativeImpl].
 *
 * Non-styled [QtButtonNativeImpl] instances use native Qt6 `QPalette` / `QStyle` rendering (`Qt::ColorScheme::Dark` /
 * `Qt::ColorScheme::Light` for Dark / Light / Auto themes), ignore aesthetic and typography `ComponentStyle`
 * overrides unless hosted in a custom style engine, and support [BootstrapType] tinting via
 * [QtControlRenderUtil].
 */
open class QtButtonNativeImpl(
    nativeWidget: QtWidgetHandle = QtWidgetHandle("QPushButton"),
    classPrefix: String = "btn"
) : DesktopButtonNativeImpl<QtWidgetHandle>(nativeWidget, classPrefix) {

    init {
        syncQtButtonAppearance()
    }

    protected open fun syncQtButtonAppearance() {
        QtControlRenderUtil.syncStandardControlAppearance(this, "QStyle::CE_PushButton[QStyleOptionButton]")
    }

    override fun measureDesktopNode(parentWidthPx: Int, parentHeightPx: Int, dpiScale: Float): Size =
        QtControlRenderUtil.measureButton(this, dpiScale)

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        QtControlRenderUtil.renderButton(this, renderContext, bounds)

    override fun setText(text: String) {
        super.setText(text)
        syncQtButtonAppearance()
    }

    override fun setIcon(icon: String?) {
        super.setIcon(icon)
        syncQtButtonAppearance()
    }

    override fun setSize(size: BootstrapSize?) {
        super.setSize(size)
        syncQtButtonAppearance()
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
        syncQtButtonAppearance()
    }

    override fun setBackgroundType(type: BootstrapType?) {
        super.setBackgroundType(type)
        syncQtButtonAppearance()
    }

    override fun setTextColorType(type: BootstrapType?) {
        super.setTextColorType(type)
        syncQtButtonAppearance()
    }

    override fun setOutline(outline: Boolean) {
        super.setOutline(outline)
        syncQtButtonAppearance()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        syncQtButtonAppearance()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        syncQtButtonAppearance()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        syncQtButtonAppearance()
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
