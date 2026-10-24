package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.shell.MenuNativeImpl
import javax.swing.JComponent
import javax.swing.JMenu

/**
 * Java Swing `JMenu` implementation of [MenuNativeImpl].
 */
class SwingMenuImpl : SwingBaseContainerNativeImpl(JMenu(), "menu"), MenuNativeImpl<JComponent> {
    override val nativeWidget: JMenu = super.nativeWidget as JMenu

    override fun setLabel(label: String) {
        nativeWidget.text = label
    }

    override fun getLabel(): String = nativeWidget.text ?: ""
}
