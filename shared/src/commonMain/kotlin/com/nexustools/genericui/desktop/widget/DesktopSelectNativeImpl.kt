package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.form.SelectNativeImpl

/**
 * Shared desktop base implementation of [SelectNativeImpl].
 */
open class DesktopSelectNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseNativeImpl<W>(nativeWidget, "form-select"), SelectNativeImpl<W> {

    protected var optionsList: List<Pair<String, String>> = emptyList()
    protected var selectedVal: String? = null
    protected var onSelectionChangedListener: ((String) -> Unit)? = null

    override fun setOptions(options: List<Pair<String, String>>) {
        optionsList = options
        nativeWidget.attributes["options"] = options
        if (selectedVal == null && options.isNotEmpty()) {
            selectedVal = options.first().first
        }
    }

    override fun setSelectedValue(value: String?) {
        if (selectedVal != value) {
            selectedVal = value
            nativeWidget.attributes["selectedValue"] = value
            if (value != null) {
                onSelectionChangedListener?.invoke(value)
            }
        }
    }

    override fun getSelectedValue(): String? = selectedVal

    override fun setOnSelectionChanged(listener: ((String) -> Unit)?) {
        onSelectionChangedListener = listener
    }

    fun getOptions(): List<Pair<String, String>> = optionsList
}
