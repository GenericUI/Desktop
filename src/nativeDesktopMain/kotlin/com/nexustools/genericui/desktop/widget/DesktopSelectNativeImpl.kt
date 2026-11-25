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

    private var lastControlVariant: String? = null
    protected var optionsList: List<Pair<String, String>> = emptyList()
    protected var selectedVal: String? = null
    protected var onSelectionChangedListener: ((String) -> Unit)? = null

    val controlVariant: String?
        get() = resolveControlVariant()

    fun resolveControlVariant(): String? {
        val explicit = (nativeWidget.attributes["data-control-variant"] as? String)
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
        if (explicit != null) return explicit
        val dataAllowCustom = (nativeWidget.attributes["data-allow-custom-input"] as? String)
            ?.equals("true", ignoreCase = true) == true
        if (nativeWidget.attributes["allowCustomInput"] == true ||
            dataAllowCustom ||
            "combobox" in nativeWidget.cachedAllClassTokens
        ) {
            return "combobox"
        }
        return null
    }

    fun isComboBoxVariant(): Boolean = resolveControlVariant() == "combobox"

    protected open fun onVariantChanged(variant: String?) {}

    override fun setAttribute(name: String, value: String?) {
        super<DesktopBaseNativeImpl>.setAttribute(name, value)
        if (name == "data-control-variant" ||
            name == "allowCustomInput" ||
            name == "data-allow-custom-input" ||
            name == "class" ||
            name == "bootstrapClass"
        ) {
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

    override fun setOptions(options: List<Pair<String, String>>) {
        optionsList = options
        nativeWidget.attributes["options"] = options
        if (selectedVal == null && options.isNotEmpty()) {
            selectedVal = options.first().first
        }
        nativeWidget.notifyTreeInvalidated()
    }

    override fun setSelectedValue(value: String?) {
        if (selectedVal != value) {
            selectedVal = value
            nativeWidget.attributes["selectedValue"] = value
            nativeWidget.notifyTreeInvalidated()
        }
    }

    fun notifyUserSelectionChanged(value: String) {
        val changed = selectedVal != value
        selectedVal = value
        nativeWidget.attributes["selectedValue"] = value
        nativeWidget.notifyTreeInvalidated()
        if (changed) {
            onSelectionChangedListener?.invoke(value)
        }
    }

    override fun getSelectedValue(): String? = selectedVal

    override fun setOnSelectionChanged(listener: ((String) -> Unit)?) {
        onSelectionChangedListener = listener
    }

    fun getOptions(): List<Pair<String, String>> = optionsList
}
