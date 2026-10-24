package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.dock.DockPosition
import com.nexustools.genericui.nativeimpl.dock.DockAreaNativeImpl
import com.nexustools.genericui.nativeimpl.dock.DockWorkspaceNativeImpl
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Java Swing `BorderLayout` / multi-split studio workspace implementation of [DockWorkspaceNativeImpl].
 */
class SwingDockWorkspaceImpl : SwingBaseContainerNativeImpl(JPanel(BorderLayout()), "dock-workspace"), DockWorkspaceNativeImpl<JComponent> {
    override fun setCentralDocumentNative(centralImpl: NativeImpl<*>?) {
        (centralImpl?.nativeWidget as? JComponent)?.let { nativeWidget.add(it, BorderLayout.CENTER) }
    }

    override fun attachDockAreaNative(position: DockPosition, areaImpl: DockAreaNativeImpl<*>) {
        val comp = areaImpl.nativeWidget as? JComponent ?: return
        val constraint = when (position) {
            DockPosition.LEFT -> BorderLayout.WEST
            DockPosition.RIGHT -> BorderLayout.EAST
            DockPosition.TOP -> BorderLayout.NORTH
            DockPosition.BOTTOM -> BorderLayout.SOUTH
            else -> BorderLayout.CENTER
        }
        nativeWidget.add(comp, constraint)
    }
}
