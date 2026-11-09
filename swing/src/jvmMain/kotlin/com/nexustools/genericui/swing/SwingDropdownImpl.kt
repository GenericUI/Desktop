package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.nativeimpl.action.DropdownNativeImpl
import com.nexustools.genericui.style.color.BootstrapColorPalette
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/**
 * Java Swing `JButton` + `JPopupMenu` implementation of [DropdownNativeImpl].
 */
class SwingDropdownImpl : SwingBaseContainerNativeImpl(
    SwingControlTintRenderUtil.createNativeOrTintedButton(),
    "dropdown"
), DropdownNativeImpl<JComponent> {
    override val nativeWidget: JButton get() = super.nativeWidget as JButton
    private val popupMenu = JPopupMenu()
    private var onItemSelectedListener: ((String) -> Unit)? = null

    init {
        nativeWidget.addActionListener {
            if (popupMenu.componentCount > 0) {
                popupMenu.show(nativeWidget, 0, nativeWidget.height)
            }
        }
        syncSwingAppearance()
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        val bType = getType() ?: BootstrapType.NONE
        nativeWidget.isContentAreaFilled = true
        nativeWidget.isBorderPainted = true
        nativeWidget.putClientProperty("bootstrapType", bType)
        nativeWidget.putClientProperty("bootstrapOutline", isOutline())
        nativeWidget.putClientProperty("activeThemeConfig", BootstrapColorPalette.activeThemeConfig)
        nativeWidget.repaint()
    }

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
