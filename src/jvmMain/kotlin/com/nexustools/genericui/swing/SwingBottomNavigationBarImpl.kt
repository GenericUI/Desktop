package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.navigation.BottomNavigationBarNativeImpl
import java.awt.GridLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.SwingConstants

/**
 * Java Swing `JPanel` + [JToggleButton] bar implementation of [BottomNavigationBarNativeImpl].
 */
class SwingBottomNavigationBarImpl : SwingBaseNativeImpl(
    JPanel(GridLayout(1, 1, 4, 0)),
    "navbar fixed-bottom"
), BottomNavigationBarNativeImpl<JComponent> {

    override val nativeWidget: JPanel get() = super.nativeWidget as JPanel

    private val buttonGroup = ButtonGroup()
    private val itemButtons = linkedMapOf<String, JToggleButton>()
    private var selectedItemIdVal: String? = null
    private var onItemSelectedListener: ((String) -> Unit)? = null

    override fun setItems(items: List<Triple<String, String, String?>>) {
        nativeWidget.removeAll()
        for (btn in itemButtons.values) {
            buttonGroup.remove(btn)
        }
        itemButtons.clear()

        nativeWidget.layout = GridLayout(1, items.size.coerceAtLeast(1), 4, 0)
        nativeWidget.putClientProperty("itemCount", items.size)

        if (selectedItemIdVal == null || items.none { it.first == selectedItemIdVal }) {
            selectedItemIdVal = items.firstOrNull()?.first
        }

        for ((id, label, icon) in items) {
            val toggle = JToggleButton(label).apply {
                isSelected = (id == selectedItemIdVal)
                horizontalTextPosition = SwingConstants.CENTER
                verticalTextPosition = SwingConstants.BOTTOM
                addActionListener {
                    if (isSelected) {
                        setSelectedItemId(id)
                    }
                }
            }
            attachNavItemIcon(toggle, icon)
            buttonGroup.add(toggle)
            itemButtons[id] = toggle
            nativeWidget.add(toggle)
        }
        nativeWidget.revalidate()
        nativeWidget.repaint()
    }

    override fun setSelectedItemId(itemId: String) {
        selectedItemIdVal = itemId
        itemButtons[itemId]?.isSelected = true
        onItemSelectedListener?.invoke(itemId)
    }

    override fun getSelectedItemId(): String? = selectedItemIdVal

    override fun setOnItemSelected(listener: ((String) -> Unit)?) {
        onItemSelectedListener = listener
    }

    fun buttonForItem(itemId: String): JToggleButton? = itemButtons[itemId]

    private fun attachNavItemIcon(button: JToggleButton, icon: String?) {
        val raw = icon?.trim().orEmpty()
        if (raw.isEmpty()) return
        if (!raw.contains("://") && !raw.contains("/") && !raw.endsWith(".png", true) &&
            !raw.endsWith(".jpg", true) && !raw.endsWith(".svg", true)
        ) {
            button.text = "$raw ${button.text}".trim()
            return
        }
        SwingControlTintRenderUtil.loadScaledIconAsync(raw, 18) { loaded ->
            button.icon = loaded
            button.revalidate()
            button.repaint()
        }
    }
}
