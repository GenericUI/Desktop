package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.action.DropdownNativeImpl
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/**
 * Java Swing `JButton` + `JPopupMenu` implementation of [DropdownNativeImpl].
 */
class SwingDropdownImpl : SwingBaseContainerNativeImpl(JButton(), "dropdown"), DropdownNativeImpl<JComponent> {
    override val nativeWidget: JButton = super.nativeWidget as JButton
    private val popupMenu = JPopupMenu()
    private var onItemSelectedListener: ((String) -> Unit)? = null

    override fun setLabel(label: String) {
        nativeWidget.text = label
    }

    override fun setItems(items: List<Pair<String, String>>) {
        popupMenu.removeAll()
        for ((id, label) in items) {
            val item = JMenuItem(label)
            item.addActionListener {
                onItemSelectedListener?.invoke(id)
            }
            popupMenu.add(item)
        }
    }

    override fun setOnItemSelected(listener: ((String) -> Unit)?) {
        onItemSelectedListener = listener
    }
}
