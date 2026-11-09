package com.nexustools.genericui.qt.impl

import com.nexustools.genericui.desktop.widget.DesktopSpinnerNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.qt.QtWidgetHandle
import com.nexustools.genericui.qt.render.QtControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * Qt6 `QWidget` (`QStyle::PE_IndicatorProgressChunk` / `QMovie` spinner) implementation of
 * [com.nexustools.genericui.nativeimpl.feedback.SpinnerNativeImpl].
 */
open class QtSpinnerNativeImpl(
    nativeWidget: QtWidgetHandle = QtWidgetHandle("QWidget_QMovieSpinner")
) : DesktopSpinnerNativeImpl<QtWidgetHandle>(nativeWidget) {

    init {
        syncAppearance()
    }

    protected open fun syncAppearance() {
        QtControlRenderUtil.syncStandardControlAppearance(this, "QStyle::PE_IndicatorProgressChunk[Spinner]")
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        QtControlRenderUtil.renderSpinner(this, renderContext, bounds)

    override fun setGrowStyle(grow: Boolean) {
        super.setGrowStyle(grow)
        syncAppearance()
    }

    override fun setLabel(label: String?) {
        super.setLabel(label)
        syncAppearance()
    }

    override fun setType(type: BootstrapType?) {
        super.setType(type)
        syncAppearance()
    }

    override fun setBackgroundType(type: BootstrapType?) {
        super.setBackgroundType(type)
        syncAppearance()
    }

    override fun setOutline(outline: Boolean) {
        super.setOutline(outline)
        syncAppearance()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        syncAppearance()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        syncAppearance()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        syncAppearance()
    }
}
