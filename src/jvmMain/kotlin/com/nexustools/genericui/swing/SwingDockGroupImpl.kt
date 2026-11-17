package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.dock.DockGroupMode
import com.nexustools.genericui.nativeimpl.dock.DockGroupNativeImpl
import java.awt.BorderLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane

/**
 * Java Swing implementation of [DockGroupNativeImpl] supporting tabbed (`JTabbedPane`) as well as
 * vertical/horizontal stacked split dock groups.
 */
class SwingDockGroupImpl : SwingBaseContainerNativeImpl(
    JPanel(BorderLayout()),
    "dock-group"
), DockGroupNativeImpl<JComponent> {

    private val rootPanel: JPanel = nativeWidget as JPanel
    val tabbedPane: JTabbedPane = JTabbedPane()
    private val stackedPanel: JPanel = JPanel()
    private val managedChildren = mutableListOf<Pair<NativeImpl<*>, JComponent>>()

    private var modeVal: DockGroupMode = DockGroupMode.TABBED
    private var activeIndex: Int = 0
    private var suppressTabEvents: Boolean = false

    init {
        rootPanel.add(tabbedPane, BorderLayout.CENTER)
        tabbedPane.addChangeListener {
            if (!suppressTabEvents && tabbedPane.selectedIndex >= 0) {
                activeIndex = tabbedPane.selectedIndex
            }
        }
    }

    override fun setGroupMode(mode: DockGroupMode) {
        if (modeVal == mode) return
        modeVal = mode
        rebuildModeView()
    }

    override fun getGroupMode(): DockGroupMode = modeVal

    override fun setActivePanelIndex(index: Int) {
        activeIndex = index
        if (modeVal == DockGroupMode.TABBED && index in 0 until tabbedPane.tabCount) {
            suppressTabEvents = true
            try {
                tabbedPane.selectedIndex = index
            } finally {
                suppressTabEvents = false
            }
        }
    }

    override fun getActivePanelIndex(): Int = activeIndex

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        val childComp = childImpl.nativeWidget as? JComponent ?: return
        managedChildren.removeAll { it.first === childImpl }
        val insertIndex = if (index < 0 || index >= managedChildren.size) managedChildren.size else index
        managedChildren.add(insertIndex, childImpl to childComp)
        rebuildModeView()
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        managedChildren.removeAll { it.first === childImpl }
        if (activeIndex >= managedChildren.size) {
            activeIndex = (managedChildren.size - 1).coerceAtLeast(0)
        }
        rebuildModeView()
    }

    override fun clearChildrenNative() {
        managedChildren.clear()
        activeIndex = 0
        rebuildModeView()
    }

    private fun rebuildModeView() {
        suppressTabEvents = true
        try {
            rootPanel.removeAll()
            tabbedPane.removeAll()
            stackedPanel.removeAll()

            if (modeVal == DockGroupMode.TABBED) {
                for ((childImpl, comp) in managedChildren) {
                    val title = (childImpl as? SwingDockPanelImpl)?.getTitle()?.takeIf { it.isNotEmpty() }
                        ?: (comp.getClientProperty("title") as? String)?.takeIf { it.isNotEmpty() }
                        ?: "Panel"
                    tabbedPane.addTab(title, comp)
                }
                if (activeIndex in 0 until tabbedPane.tabCount) {
                    tabbedPane.selectedIndex = activeIndex
                }
                rootPanel.add(tabbedPane, BorderLayout.CENTER)
            } else {
                val axis = if (modeVal == DockGroupMode.HORIZONTAL_SPLIT) {
                    BoxLayout.X_AXIS
                } else {
                    BoxLayout.Y_AXIS
                }
                stackedPanel.layout = BoxLayout(stackedPanel, axis)
                for ((_, comp) in managedChildren) {
                    stackedPanel.add(comp)
                }
                rootPanel.add(stackedPanel, BorderLayout.CENTER)
            }
        } finally {
            suppressTabEvents = false
        }
        rootPanel.revalidate()
        rootPanel.repaint()
    }
}
