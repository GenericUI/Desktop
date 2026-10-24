package com.nexustools.genericui.desktop.shell

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.shell.ContextMenuNativeImpl

/**
 * Shared desktop base implementation of [ContextMenuNativeImpl].
 */
open class DesktopContextMenuNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "dropdown-menu"), ContextMenuNativeImpl<W> {

    override fun showAt(anchor: NativeImpl<*>?, x: Int, y: Int) {
        nativeWidget.attributes["popupAnchor"] = anchor
        nativeWidget.attributes["popupX"] = x
        nativeWidget.attributes["popupY"] = y
        setVisible(true)
    }

    override fun hide() {
        setVisible(false)
    }
}
