package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.nativeimpl.shell.ContextMenuNativeImpl
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JPopupMenu

/**
 * Java Swing `JPopupMenu` implementation of [ContextMenuNativeImpl].
 */
class SwingContextMenuImpl : SwingBaseContainerNativeImpl(JPopupMenu(), "dropdown-menu"), ContextMenuNativeImpl<JComponent> {
    override val nativeWidget: JPopupMenu = super.nativeWidget as JPopupMenu

    override fun showAt(anchor: NativeImpl<*>?, x: Int, y: Int) {
        val comp = anchor?.nativeWidget as? Component
        if (comp != null && comp.isShowing) {
            nativeWidget.show(comp, x, y)
        } else {
            nativeWidget.setLocation(x, y)
        }
    }

    override fun hide() {
        nativeWidget.isVisible = false
    }
}
