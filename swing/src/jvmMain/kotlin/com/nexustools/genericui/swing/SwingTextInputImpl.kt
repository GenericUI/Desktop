package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.form.TextInputNativeImpl
import javax.swing.JComponent
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.text.JTextComponent

/**
 * Java Swing `JTextField` / `JTextArea` implementation of [TextInputNativeImpl].
 */
class SwingTextInputImpl(
    initialMultiline: Boolean = false
) : SwingBaseNativeImpl(if (initialMultiline) JTextArea(3, 20) else JTextField(20), "form-control"), TextInputNativeImpl<JComponent> {
    override val nativeWidget: JTextComponent = super.nativeWidget as JTextComponent

    private var placeholderVal: String = ""
    private var onValueChangedListener: ((String) -> Unit)? = null
    private var onSubmitListener: ((String) -> Unit)? = null

    init {
        (nativeWidget as? JTextField)?.addActionListener {
            onSubmitListener?.invoke(nativeWidget.text ?: "")
        }
    }

    override fun setValue(value: String) {
        nativeWidget.text = value
        onValueChangedListener?.invoke(value)
    }

    override fun getValue(): String = nativeWidget.text ?: ""

    override fun setPlaceholder(placeholder: String) {
        placeholderVal = placeholder
        nativeWidget.putClientProperty("placeholder", placeholder)
    }

    override fun getPlaceholder(): String = placeholderVal

    override fun setReadOnly(readOnly: Boolean) {
        nativeWidget.isEditable = !readOnly
    }

    override fun setPasswordMode(password: Boolean) {
        nativeWidget.putClientProperty("passwordMode", password)
    }

    override fun setMultiline(multiline: Boolean) {
        nativeWidget.putClientProperty("multiline", multiline)
    }

    override fun setOnValueChanged(listener: ((String) -> Unit)?) {
        onValueChangedListener = listener
    }

    override fun setOnSubmit(listener: ((String) -> Unit)?) {
        onSubmitListener = listener
    }
}
