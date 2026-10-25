package com.nexustools.genericui.swing

import com.nexustools.genericui.event.KeyShortcut
import com.nexustools.genericui.nativeimpl.shell.MenuItemNativeImpl
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JRadioButtonMenuItem
import javax.swing.KeyStroke

/**
 * Java Swing `JMenuItem` / `JCheckBoxMenuItem` / `JRadioButtonMenuItem` implementation of [MenuItemNativeImpl].
 */
class SwingMenuItemImpl(
    val checkable: Boolean = false,
    val radio: Boolean = false
) : SwingBaseNativeImpl(
    when {
        radio -> JRadioButtonMenuItem()
        checkable -> JCheckBoxMenuItem()
        else -> JMenuItem()
    },
    "dropdown-item"
), MenuItemNativeImpl<JComponent> {
    override val nativeWidget: JMenuItem = super.nativeWidget as JMenuItem

    private var checkedState: Boolean = false
    private var iconName: String? = null
    private var shortcutVal: KeyShortcut? = null

    init {
        nativeWidget.addActionListener {
            if (checkable || radio) {
                checkedState = nativeWidget.isSelected
            }
            onClickListener?.invoke()
        }
    }

    fun trigger() {
        if (!isEnabled()) return
        onClickListener?.invoke()
    }

    override fun setLabel(label: String) {
        nativeWidget.text = label
    }

    override fun getLabel(): String = nativeWidget.text ?: ""

    override fun setIcon(icon: String?) {
        iconName = icon
        nativeWidget.putClientProperty("icon", icon)
    }

    override fun getIcon(): String? = iconName

    override fun setShortcut(shortcut: KeyShortcut?) {
        shortcutVal = shortcut
        if (shortcut != null) {
            val formatted = shortcut.toString().replace("+", " ").replace("Ctrl", "control").replace("Cmd", "meta")
            KeyStroke.getKeyStroke(formatted)?.let { nativeWidget.accelerator = it }
        } else {
            nativeWidget.accelerator = null
        }
    }

    override fun getShortcut(): KeyShortcut? = shortcutVal

    override fun setChecked(checked: Boolean) {
        checkedState = checked
        nativeWidget.isSelected = checked
    }

    override fun isChecked(): Boolean = checkedState
}
