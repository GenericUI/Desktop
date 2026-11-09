package com.nexustools.genericui.qt.impl

import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.qt.QtWidgetHandle
import com.nexustools.genericui.qt.render.QtControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * Qt6 `QLineEdit` / `QPlainTextEdit` implementation of
 * [com.nexustools.genericui.nativeimpl.form.TextInputNativeImpl].
 *
 * Ignores aesthetic and typography [ComponentStyle] overrides unless hosted in a custom style engine,
 * and supports [BootstrapType] tinting via [QtControlRenderUtil].
 */
open class QtTextInputNativeImpl(
    nativeWidget: QtWidgetHandle = QtWidgetHandle("QLineEdit"),
    multiline: Boolean = false
) : DesktopTextInputNativeImpl<QtWidgetHandle>(nativeWidget, multiline) {

    init {
        syncAppearance()
    }

    protected open fun syncAppearance() {
        val elem = if (isMultiline()) {
            "QStyle::CE_ShapedFrame[QPlainTextEdit]"
        } else {
            "QStyle::PE_PanelLineEdit[QStyleOptionFrame]"
        }
        QtControlRenderUtil.syncStandardControlAppearance(this, elem)
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        QtControlRenderUtil.renderTextInput(this, renderContext, bounds)

    override fun setValue(value: String) {
        super.setValue(value)
        syncAppearance()
    }

    override fun setPlaceholder(placeholder: String) {
        super.setPlaceholder(placeholder)
        syncAppearance()
    }

    override fun setMultiline(multiline: Boolean) {
        super.setMultiline(multiline)
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
