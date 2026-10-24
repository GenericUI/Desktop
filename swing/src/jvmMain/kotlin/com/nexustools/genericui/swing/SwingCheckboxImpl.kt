package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.form.CheckboxNativeImpl
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JRadioButton
import javax.swing.JToggleButton

/**
 * Java Swing `JCheckBox` / `JRadioButton` implementation of [CheckboxNativeImpl].
 */
class SwingCheckboxImpl(
    switchStyle: Boolean = false,
    radioStyle: Boolean = false
) : SwingBaseNativeImpl(
    if (radioStyle) JRadioButton() else JCheckBox(),
    if (switchStyle) "form-check form-switch" else "form-check"
), CheckboxNativeImpl<JComponent> {
    override val nativeWidget: JToggleButton = super.nativeWidget as JToggleButton

    private var onCheckedChangedListener: ((Boolean) -> Unit)? = null

    init {
        nativeWidget.addActionListener {
            onCheckedChangedListener?.invoke(nativeWidget.isSelected)
        }
    }

    override fun setLabel(label: String) {
        nativeWidget.text = label
    }

    override fun getLabel(): String = nativeWidget.text ?: ""

    override fun setChecked(checked: Boolean) {
        nativeWidget.isSelected = checked
        onCheckedChangedListener?.invoke(checked)
    }

    override fun isChecked(): Boolean = nativeWidget.isSelected

    override fun setOnCheckedChanged(listener: ((Boolean) -> Unit)?) {
        onCheckedChangedListener = listener
    }
}
