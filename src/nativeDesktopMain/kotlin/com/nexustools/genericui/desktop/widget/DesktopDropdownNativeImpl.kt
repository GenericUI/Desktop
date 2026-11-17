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
}
