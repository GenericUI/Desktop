package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.shell.MenuBarNativeImpl
import javax.swing.JComponent
import javax.swing.JMenuBar

/**
 * Java Swing `JMenuBar` implementation of [MenuBarNativeImpl].
 */
class SwingMenuBarImpl : SwingBaseContainerNativeImpl(JMenuBar(), "menubar"), MenuBarNativeImpl<JComponent> {
    override val nativeWidget: JMenuBar = super.nativeWidget as JMenuBar
}
