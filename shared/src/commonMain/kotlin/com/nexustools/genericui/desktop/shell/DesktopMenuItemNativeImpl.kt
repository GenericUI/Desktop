package com.nexustools.genericui.desktop.shell

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.event.KeyShortcut
import com.nexustools.genericui.nativeimpl.shell.MenuItemNativeImpl

/**
 * Shared desktop base implementation of [MenuItemNativeImpl].
 */
open class DesktopMenuItemNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    val checkable: Boolean = false,
    val radio: Boolean = false
) : DesktopBaseNativeImpl<W>(nativeWidget, "dropdown-item"), MenuItemNativeImpl<W> {

    protected var labelText: String = ""
    protected var iconName: String? = null
    protected var shortcutVal: KeyShortcut? = null
    protected var checkedState: Boolean = false

    override fun setLabel(label: String) {
        labelText = label
        nativeWidget.attributes["label"] = label
    }

    override fun getLabel(): String = labelText

    override fun setIcon(icon: String?) {
        iconName = icon
        nativeWidget.attributes["icon"] = icon
    }

    override fun getIcon(): String? = iconName

    override fun setShortcut(shortcut: KeyShortcut?) {
        shortcutVal = shortcut
        nativeWidget.attributes["shortcut"] = shortcut
    }

    override fun getShortcut(): KeyShortcut? = shortcutVal

    override fun setChecked(checked: Boolean) {
        checkedState = checked
        nativeWidget.attributes["checked"] = checked
    }

    override fun isChecked(): Boolean = checkedState
}
