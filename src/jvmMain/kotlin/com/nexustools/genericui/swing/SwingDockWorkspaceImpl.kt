package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.dock.DockPosition
import com.nexustools.genericui.nativeimpl.dock.DockAreaNativeImpl
import com.nexustools.genericui.nativeimpl.dock.DockWorkspaceNativeImpl
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane

/**
 * Java Swing nested `JSplitPane` multi-region studio workspace implementation of
 * [DockWorkspaceNativeImpl].
 */
class SwingDockWorkspaceImpl : SwingBaseContainerNativeImpl(
    JPanel(BorderLayout()),
    "dock-workspace"
), DockWorkspaceNativeImpl<JComponent> {

    private var centralComponent: JComponent? = null
    private val dockAreas = linkedMapOf<DockPosition, JComponent>()
    private var windowModeName: String = "SINGLE_WINDOW"

    override fun setCentralDocumentNative(centralImpl: NativeImpl<*>?) {
        centralComponent = centralImpl?.nativeWidget as? JComponent
        rebuildWorkspaceSplits()
    }

    override fun attachDockAreaNative(position: DockPosition, areaImpl: DockAreaNativeImpl<*>) {
        val comp = areaImpl.nativeWidget as? JComponent ?: return
        if (position == DockPosition.FLOATING) {
            nativeWidget.putClientProperty("hasFloatingArea", true)
            return
        }
        dockAreas[position] = comp
        rebuildWorkspaceSplits()
    }

    override fun setWindowModeNative(modeName: String) {
        windowModeName = modeName
        nativeWidget.putClientProperty("windowMode", modeName)
    }

    fun getDockAreaComponent(position: DockPosition): JComponent? = dockAreas[position]

    private fun rebuildWorkspaceSplits() {
        val root = nativeWidget as JPanel
        root.removeAll()

        var verticalCore: JComponent = centralComponent ?: dockAreas[DockPosition.CENTER] ?: JPanel(BorderLayout())

        dockAreas[DockPosition.TOP]?.let { top ->
            verticalCore = JSplitPane(JSplitPane.VERTICAL_SPLIT, true, top, verticalCore).apply {
                resizeWeight = 0.25
                border = null
            }
        }
        dockAreas[DockPosition.BOTTOM]?.let { bottom ->
            verticalCore = JSplitPane(JSplitPane.VERTICAL_SPLIT, true, verticalCore, bottom).apply {
                resizeWeight = 0.75
                border = null
            }
        }

        var horizontalCore: JComponent = verticalCore
        dockAreas[DockPosition.LEFT]?.let { left ->
            horizontalCore = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, true, left, horizontalCore).apply {
                resizeWeight = 0.2
                border = null
            }
        }
        dockAreas[DockPosition.RIGHT]?.let { right ->
            horizontalCore = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, true, horizontalCore, right).apply {
                resizeWeight = 0.8
                border = null
            }
        }

        root.add(horizontalCore, BorderLayout.CENTER)
        root.revalidate()
        root.repaint()
    }
}
