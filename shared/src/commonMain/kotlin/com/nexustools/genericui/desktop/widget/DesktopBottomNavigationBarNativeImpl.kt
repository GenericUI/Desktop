package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.navigation.BottomNavigationBarNativeImpl

/**
 * Shared desktop base implementation of [BottomNavigationBarNativeImpl].
 */
open class DesktopBottomNavigationBarNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseNativeImpl<W>(nativeWidget, "bottom-nav"), BottomNavigationBarNativeImpl<W> {

    protected var itemsList: List<Triple<String, String, String?>> = emptyList()
    protected var selectedId: String? = null
    protected var onItemSelectedListener: ((String) -> Unit)? = null

    override fun setItems(items: List<Triple<String, String, String?>>) {
        itemsList = items
        nativeWidget.attributes["items"] = items
        if (selectedId == null && items.isNotEmpty()) {
            selectedId = items.first().first
        }
    }

    override fun setSelectedItemId(itemId: String) {
        if (selectedId != itemId) {
            selectedId = itemId
            nativeWidget.attributes["selectedItemId"] = itemId
            onItemSelectedListener?.invoke(itemId)
        }
    }

    override fun getSelectedItemId(): String? = selectedId

    override fun setOnItemSelected(listener: ((String) -> Unit)?) {
        onItemSelectedListener = listener
    }
}
