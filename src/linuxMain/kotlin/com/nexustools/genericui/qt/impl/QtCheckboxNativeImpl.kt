package com.nexustools.genericui.qt.impl

import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.qt.QtWidgetHandle
import com.nexustools.genericui.qt.render.QtControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * Qt 6 `QCheckBox` / `QRadioButton` implementation of
 * [com.nexustools.genericui.nativeimpl.form.CheckboxNativeImpl].
 */
open class QtCheckboxNativeImpl(
    nativeWidget: QtWidgetHandle = QtWidgetHandle("QCheckBox"),
    switchStyle: Boolean = false,
    radioStyle: Boolean = false
) : DesktopCheckboxNativeImpl<QtWidgetHandle>(nativeWidget, switchStyle, radioStyle) {

    init {
        syncAppearance()
    }

    protected open fun syncAppearance() {
        val role = when {
            switchStyle -> "QStyle::CE_CheckBox[Switch]"
            radioStyle -> "QStyle::CE_RadioButton"
            else -> "QStyle::CE_CheckBox"
        }
        QtControlRenderUtil.syncStandardControlAppearance(this, role)
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        QtControlRenderUtil.renderCheckbox(this, renderContext, bounds)

    override fun setLabel(label: String) {
        super.setLabel(label)
        syncAppearance()
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
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
