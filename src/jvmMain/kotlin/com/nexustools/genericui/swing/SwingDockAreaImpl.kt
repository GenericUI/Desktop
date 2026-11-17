package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.dock.DockPosition
import com.nexustools.genericui.nativeimpl.dock.DockAreaNativeImpl
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Java Swing directional dock sidebar/strip container implementation of [DockAreaNativeImpl].
 */
class SwingDockAreaImpl : SwingBaseContainerNativeImpl(JPanel(), "dock-area"), DockAreaNativeImpl<JComponent> {

    private var positionVal: DockPosition = DockPosition.LEFT
    private var collapsedFlag: Boolean = false

    init {
        applyOrientationLayout(positionVal)
    }

    override fun setPosition(position: DockPosition) {
        positionVal = position
        nativeWidget.putClientProperty("position", position.name)
        applyOrientationLayout(position)
    }

    fun getDockPosition(): DockPosition = positionVal

    override fun setCollapsed(collapsed: Boolean) {
        collapsedFlag = collapsed
        nativeWidget.isVisible = !collapsed
        nativeWidget.revalidate()
        nativeWidget.repaint()
    }

    override fun isCollapsed(): Boolean = collapsedFlag

    private fun applyOrientationLayout(position: DockPosition) {
        val panel = nativeWidget as JPanel
        panel.layout = when (position) {
            DockPosition.TOP,
            DockPosition.BOTTOM -> BoxLayout(panel, BoxLayout.X_AXIS)
            DockPosition.LEFT,
            DockPosition.RIGHT -> BoxLayout(panel, BoxLayout.Y_AXIS)
            else -> BorderLayout()
        }
        panel.revalidate()
        panel.repaint()
    }
}
