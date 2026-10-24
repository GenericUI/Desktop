package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.navigation.BottomNavigationBarNativeImpl
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Java Swing `JPanel` implementation of [BottomNavigationBarNativeImpl].
 */
class SwingBottomNavigationBarImpl : SwingBaseNativeImpl(JPanel(), "navbar fixed-bottom"), BottomNavigationBarNativeImpl<JComponent> {
    override val nativeWidget: JPanel = super.nativeWidget as JPanel

    private var selectedItemIdVal: String? = null
    private var onItemSelectedListener: ((String) -> Unit)? = null

    override fun setItems(items: List<Triple<String, String, String?>>) {
        nativeWidget.putClientProperty("itemCount", items.size)
        if (selectedItemIdVal == null && items.isNotEmpty()) {
            selectedItemIdVal = items.first().first
        }
    }

    override fun setSelectedItemId(itemId: String) {
        selectedItemIdVal = itemId
        onItemSelectedListener?.invoke(itemId)
    }

    override fun getSelectedItemId(): String? = selectedItemIdVal

    override fun setOnItemSelected(listener: ((String) -> Unit)?) {
        onItemSelectedListener = listener
    }
}
