package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.dock.DockPosition
import com.nexustools.genericui.nativeimpl.dock.DockAreaNativeImpl
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Java Swing implementation of [DockAreaNativeImpl].
 */
class SwingDockAreaImpl : SwingBaseContainerNativeImpl(JPanel(), "dock-area"), DockAreaNativeImpl<JComponent> {
    private var collapsedFlag: Boolean = false

    override fun setPosition(position: DockPosition) {
        nativeWidget.putClientProperty("position", position.name)
    }

    override fun setCollapsed(collapsed: Boolean) {
        collapsedFlag = collapsed
        nativeWidget.isVisible = !collapsed
    }

    override fun isCollapsed(): Boolean = collapsedFlag
}
