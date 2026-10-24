package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.ui.Placement
import com.nexustools.genericui.nativeimpl.overlay.PopoverNativeImpl
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JPopupMenu

/**
 * Java Swing `JPopupMenu` implementation of [PopoverNativeImpl].
 */
class SwingPopoverImpl : SwingBaseContainerNativeImpl(JPopupMenu(), "popover"), PopoverNativeImpl<JComponent> {
    private val popupMenu: JPopupMenu = nativeWidget as JPopupMenu

    override fun setTitle(title: String?) {
        popupMenu.label = title ?: ""
    }

    override fun setPlacement(placement: Placement) {
        popupMenu.putClientProperty("placement", placement.name)
    }

    override fun showAnchored(anchor: NativeImpl<*>?, x: Int, y: Int) {
        val comp = anchor?.nativeWidget as? Component
        if (comp != null && comp.isShowing) {
            popupMenu.show(comp, x, y)
        } else {
            popupMenu.setLocation(x, y)
        }
    }

    override fun hide() {
        popupMenu.isVisible = false
    }
}
