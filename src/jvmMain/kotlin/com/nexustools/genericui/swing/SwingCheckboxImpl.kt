package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.form.CheckboxNativeImpl
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
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
    override val nativeWidget: JToggleButton get() = super.nativeWidget as JToggleButton

    private var onCheckedChangedListener: ((Boolean) -> Unit)? = null
    private var focusedStateVal: Boolean = false

    init {
        nativeWidget.isOpaque = false
        nativeWidget.isContentAreaFilled = false
        nativeWidget.addActionListener {
            onCheckedChangedListener?.invoke(nativeWidget.isSelected)
        }
        nativeWidget.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent?) {
                val effective = isEnabled() && isEffectivelyFocusableInSwing()
                focusedStateVal = effective
                if (!effective) {
                    clearAwtFocusOnEdtIfOwner(nativeWidget)
                }
            }

            override fun focusLost(e: FocusEvent?) {
                focusedStateVal = false
            }
        })
        syncSwingAppearance()
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        val hasExplicitBg = customBackgroundColor != null ||
            lastComponentStyle?.backgroundColor != null ||
            lastComputedStyle?.backgroundColor != null
        if (!hasExplicitBg) {
            nativeWidget.isOpaque = false
            nativeWidget.isContentAreaFilled = false
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

    override fun setFocusedState(focused: Boolean) {
        val effective = focused && isEnabled() && isEffectivelyFocusableInSwing()
        focusedStateVal = effective
        if (effective) {
            requestAwtFocusOnEdt(nativeWidget)
        } else {
            clearAwtFocusOnEdtIfOwner(nativeWidget)
        }
    }

    override fun isFocusedState(): Boolean = focusedStateVal || nativeWidget.isFocusOwner
}
