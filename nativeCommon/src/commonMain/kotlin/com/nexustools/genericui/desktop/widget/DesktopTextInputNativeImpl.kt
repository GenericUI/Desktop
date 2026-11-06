package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.form.TextInputNativeImpl

/**
 * Shared desktop base implementation of [TextInputNativeImpl].
 */
open class DesktopTextInputNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    multiline: Boolean = false
) : DesktopBaseNativeImpl<W>(nativeWidget, "form-control"), TextInputNativeImpl<W> {

    protected var textValue: String = ""
    protected var placeholderText: String = ""
    protected var readOnlyState: Boolean = false
    protected var passwordModeState: Boolean = false
    protected var multilineState: Boolean = multiline
    protected var onValueChangedListener: ((String) -> Unit)? = null
    protected var onSubmitListener: ((String) -> Unit)? = null

    init {
        if (multiline) {
            nativeWidget.attributes["multiline"] = true
        }
    }

    override fun setValue(value: String) {
        if (textValue != value) {
            textValue = value
            nativeWidget.attributes["value"] = value
            nativeWidget.notifyTreeInvalidated()
            onValueChangedListener?.invoke(value)
        }
    }

    override fun getValue(): String = textValue

    override fun setPlaceholder(placeholder: String) {
        placeholderText = placeholder
        nativeWidget.attributes["placeholder"] = placeholder
    }

    override fun getPlaceholder(): String = placeholderText

    override fun setReadOnly(readOnly: Boolean) {
        readOnlyState = readOnly
        nativeWidget.attributes["readOnly"] = readOnly
    }

    fun isReadOnly(): Boolean = readOnlyState

    override fun setPasswordMode(password: Boolean) {
        passwordModeState = password
        nativeWidget.attributes["passwordMode"] = password
    }

    fun isPasswordMode(): Boolean = passwordModeState

    override fun setMultiline(multiline: Boolean) {
        multilineState = multiline
        nativeWidget.attributes["multiline"] = multiline
    }

    fun isMultiline(): Boolean = multilineState

    override fun setOnValueChanged(listener: ((String) -> Unit)?) {
        onValueChangedListener = listener
    }

    override fun setOnSubmit(listener: ((String) -> Unit)?) {
        onSubmitListener = listener
    }

    override fun setFocusedState(focused: Boolean) {
        val wasFocused = isFocusedState()
        super<DesktopBaseNativeImpl>.setFocusedState(focused)
        val nowFocused = isFocusedState()
        if (nowFocused) {
            com.nexustools.genericui.event.InputFocusManager.isNativeTextInputFocused = true
        } else if (wasFocused) {
            com.nexustools.genericui.event.InputFocusManager.isNativeTextInputFocused = false
        }
    }

    override fun dispose() {
        if (isFocusedState()) {
            com.nexustools.genericui.event.InputFocusManager.isNativeTextInputFocused = false
        }
        super.dispose()
    }

    fun submitValue() {
        onSubmitListener?.invoke(textValue)
    }
}
