package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.form.CheckboxNativeImpl

/**
 * Shared desktop base implementation of [CheckboxNativeImpl].
 */
open class DesktopCheckboxNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    val switchStyle: Boolean = false,
    val radioStyle: Boolean = false
) : DesktopBaseNativeImpl<W>(nativeWidget, "form-check"), CheckboxNativeImpl<W> {

    protected var labelText: String = ""
    protected var checkedState: Boolean = false
    protected var onCheckedChangedListener: ((Boolean) -> Unit)? = null

    override fun setLabel(label: String) {
        labelText = label
        nativeWidget.attributes["label"] = label
    }

    override fun getLabel(): String = labelText

    override fun setChecked(checked: Boolean) {
        if (checkedState != checked) {
            checkedState = checked
            nativeWidget.attributes["checked"] = checked
            onCheckedChangedListener?.invoke(checked)
        }
    }

    override fun isChecked(): Boolean = checkedState

    override fun setOnCheckedChanged(listener: ((Boolean) -> Unit)?) {
        onCheckedChangedListener = listener
    }

    fun dispatchToggle(checked: Boolean = checkedState) {
        onCheckedChangedListener?.invoke(checked)
    }
}
