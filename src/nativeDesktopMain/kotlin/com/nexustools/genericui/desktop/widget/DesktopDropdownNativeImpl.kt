package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.action.DropdownNativeImpl

/**
 * Shared desktop base implementation of [DropdownNativeImpl].
 */
open class DesktopDropdownNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "dropdown"), DropdownNativeImpl<W> {

    protected var labelText: String = ""
    protected var itemsList: List<Pair<String, String>> = emptyList()
    protected var onItemSelectedListener: ((String) -> Unit)? = null
    protected var onPrimaryActionListener: (() -> Unit)? = null
    private var lastSplitVariant: Boolean = false

    private fun checkSplitVariantChanged() {
        val nextSplit = isSplitDropdownVariant()
        if (lastSplitVariant != nextSplit) {
            lastSplitVariant = nextSplit
            onDropdownVariantChanged()
        }
    }

    fun isSplitDropdownVariant(): Boolean {
        if (onPrimaryActionListener != null || hasClickListener()) return true
        val variant = (nativeWidget.attributes["data-control-variant"] as? String)?.trim()?.lowercase()
        if (variant == "split" || variant == "split-dropdown" || variant == "split-dropdown-button") return true
        val role = (nativeWidget.attributes["role"] as? String)?.trim()?.lowercase()
        if (role == "split-dropdown-button") return true
        val htmlTag = (nativeWidget.attributes["htmlTag"] as? String)?.trim()?.lowercase()
        if (htmlTag == "button") return true
        val tokens = nativeWidget.cachedAllClassTokens
        return "btn-split-dropdown" in tokens ||
            "split-dropdown-button" in tokens ||
            "splitdropdownbutton" in tokens
    }

    protected open fun onDropdownVariantChanged() {}

    fun dispatchPrimaryAction() {
        if (!nativeWidget.enabled || isPointerInteractionDisabled()) return
        val listener = onPrimaryActionListener ?: onClickListener
        listener?.invoke()
    }

    fun dispatchItemSelected(itemId: String) {
        if (!nativeWidget.enabled || isPointerInteractionDisabled()) return
        onItemSelectedListener?.invoke(itemId)
    }

    override fun setLabel(label: String) {
        labelText = label
        nativeWidget.attributes["label"] = label
    }

    override fun setItems(items: List<Pair<String, String>>) {
        itemsList = items
        nativeWidget.attributes["items"] = items
    }

    override fun setOnItemSelected(listener: ((String) -> Unit)?) {
        onItemSelectedListener = listener
    }

    override fun setAttribute(name: String, value: String?) {
        super<DesktopBaseContainerNativeImpl>.setAttribute(name, value)
        if (name == "data-control-variant" ||
            name == "role" ||
            name == "htmlTag" ||
            name == "class" ||
            name == "bootstrapClass"
        ) {
            checkSplitVariantChanged()
        }
    }

    override fun syncStyleToNativeWidget() {
        super.syncStyleToNativeWidget()
        checkSplitVariantChanged()
    }

    override fun setOnClick(listener: (() -> Unit)?) {
        super<DesktopBaseContainerNativeImpl>.setOnClick(listener)
        checkSplitVariantChanged()
    }

    override fun setOnPrimaryAction(listener: (() -> Unit)?) {
        onPrimaryActionListener = listener
        nativeWidget.attributes["hasPrimaryAction"] = (listener != null)
        checkSplitVariantChanged()
    }
}
