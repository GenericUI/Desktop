package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.form.SelectNativeImpl
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

/**
 * Java Swing `JComboBox<String>` implementation of [SelectNativeImpl].
 */
class SwingSelectImpl : SwingBaseNativeImpl(JComboBox<String>(), "form-select"), SelectNativeImpl<JComponent> {
    @Suppress("UNCHECKED_CAST")
    override val nativeWidget: JComboBox<String> = super.nativeWidget as JComboBox<String>

    private var optionsList: List<Pair<String, String>> = emptyList()
    private var selectedVal: String? = null
    private var onSelectionChangedListener: ((String) -> Unit)? = null

    override fun setOptions(options: List<Pair<String, String>>) {
        optionsList = options.toList()
        nativeWidget.model = DefaultComboBoxModel(optionsList.map { it.second }.toTypedArray())
        if (selectedVal == null && optionsList.isNotEmpty()) {
            selectedVal = optionsList.first().first
        }
    }

    override fun setSelectedValue(value: String?) {
        selectedVal = value
        val idx = optionsList.indexOfFirst { it.first == value }
        if (idx >= 0) {
            nativeWidget.selectedIndex = idx
        }
        if (value != null) {
            onSelectionChangedListener?.invoke(value)
        }
    }

    override fun getSelectedValue(): String? = selectedVal

    override fun setOnSelectionChanged(listener: ((String) -> Unit)?) {
        onSelectionChangedListener = listener
    }
}
