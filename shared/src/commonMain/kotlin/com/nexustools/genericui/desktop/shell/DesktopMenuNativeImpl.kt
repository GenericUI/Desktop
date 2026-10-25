package com.nexustools.genericui.desktop.shell

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.shell.MenuNativeImpl

/**
 * Shared desktop base implementation of [MenuNativeImpl].
 */
open class DesktopMenuNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "menu"), MenuNativeImpl<W> {

    protected var labelText: String = ""
    protected var iconName: String? = null

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
}
