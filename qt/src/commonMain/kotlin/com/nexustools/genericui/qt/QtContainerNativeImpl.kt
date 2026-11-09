package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.qt.render.QtControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.style.transition.CssTransitionSpec

/**
 * Qt6 `QWidget` + `QBoxLayout` implementation of [com.nexustools.genericui.nativeimpl.layout.ContainerNativeImpl].
 *
 * Non-styled [QtContainerNativeImpl] instances use native Qt6 `QPalette` system theme rendering (Dark/Light/Auto),
 * ignore aesthetic and typography `ComponentStyle` overrides unless hosted in a custom style engine, and support
 * [BootstrapType] tinting via [QtControlRenderUtil].
 */
open class QtContainerNativeImpl(
    nativeWidget: QtWidgetHandle = QtWidgetHandle("QWidget_QBoxLayout"),
    classPrefix: String = "container"
) : DesktopContainerNativeImpl<QtWidgetHandle>(nativeWidget, classPrefix) {

    init {
        syncQtContainerAppearance()
    }

    protected open fun syncQtContainerAppearance() {
        QtControlRenderUtil.syncStandardControlAppearance(this, "QStyle::PE_Widget[QWidget_QBoxLayout]")
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        QtControlRenderUtil.renderContainer(this, renderContext, bounds)

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
        syncQtContainerAppearance()
    }

    override fun setBackgroundType(type: BootstrapType?) {
        super.setBackgroundType(type)
        syncQtContainerAppearance()
    }

    override fun setTextColorType(type: BootstrapType?) {
        super.setTextColorType(type)
        syncQtContainerAppearance()
    }

    override fun setOutline(outline: Boolean) {
        super.setOutline(outline)
        syncQtContainerAppearance()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        syncQtContainerAppearance()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        syncQtContainerAppearance()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        syncQtContainerAppearance()
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
