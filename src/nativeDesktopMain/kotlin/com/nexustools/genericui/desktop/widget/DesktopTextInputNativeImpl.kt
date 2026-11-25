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

    private var lastControlVariant: String? = null
    protected var textValue: String = ""
    protected var placeholderText: String = ""
    protected var labelText: String? = null
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

    val controlVariant: String?
        get() = resolveControlVariant()

    fun resolveControlVariant(): String? =
        (nativeWidget.attributes["data-control-variant"] as? String)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }

    protected open fun onVariantChanged(variant: String?) {}

    override fun setAttribute(name: String, value: String?) {
        super<DesktopBaseNativeImpl>.setAttribute(name, value)
        if (name == "data-control-variant") {
            val nextVariant = resolveControlVariant()
            if (lastControlVariant != nextVariant) {
                lastControlVariant = nextVariant
                onVariantChanged(nextVariant)
            }
        }
    }

    override fun syncStyleToNativeWidget() {
        super.syncStyleToNativeWidget()
        val nextVariant = resolveControlVariant()
        if (lastControlVariant != nextVariant) {
            lastControlVariant = nextVariant
            onVariantChanged(nextVariant)
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

    override fun setLabel(label: String?) {
        val normalized = label?.takeIf { it.isNotBlank() }
        if (labelText == normalized) return
        labelText = normalized
        if (normalized != null) {
            nativeWidget.attributes["label"] = normalized
        } else {
            nativeWidget.attributes.remove("label")
        }
        nativeWidget.notifyTreeInvalidated()
    }

    override fun getLabel(): String? = labelText

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
