package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.dock.DockGroupMode
import com.nexustools.genericui.nativeimpl.dock.DockGroupNativeImpl
import com.nexustools.genericui.enums.css.CssDisplay
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane

/**
 * Java Swing [DockGroupNativeImpl] supporting tabbed panels ([JTabbedPane]) and proportional
 * horizontal/vertical flex splits without overlapping or clipping panel bodies.
 */
class SwingDockGroupImpl : SwingBaseContainerNativeImpl(
    JPanel(BorderLayout()).apply { isOpaque = false },
    "dock-group"
), DockGroupNativeImpl<JComponent> {

    private val rootPanel: JPanel = nativeWidget as JPanel
    val tabbedPane: JTabbedPane = JTabbedPane()
    private val stackedPanel: JPanel = SwingContainerImpl.SwingLayoutPanel()
    private val managedChildren = mutableListOf<Pair<NativeImpl<*>, JComponent>>()

    private var modeVal: DockGroupMode = DockGroupMode.TABBED
    private var activeIndex: Int = 0
    private var suppressTabEvents: Boolean = false

    init {
        rootPanel.isOpaque = false
        stackedPanel.isOpaque = false
        rootPanel.add(tabbedPane, BorderLayout.CENTER)
        tabbedPane.addChangeListener {
            if (!suppressTabEvents && tabbedPane.selectedIndex >= 0) {
                activeIndex = tabbedPane.selectedIndex
            }
        }
        syncSwingAppearance()
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        rootPanel.isOpaque = false
        stackedPanel.isOpaque = false
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
        rootPanel.removeAll()
        tabbedPane.removeAll()
        stackedPanel.removeAll()

        when (modeVal) {
            DockGroupMode.TABBED -> {
                for ((idx, pair) in managedChildren.withIndex()) {
                    val (childImpl, childComp) = pair
                    val title = (childImpl as? SwingDockPanelImpl)?.getTitle()?.takeIf { it.isNotEmpty() }
                        ?: "Panel ${idx + 1}"
                    childComp.minimumSize = Dimension(0, 0)
                    tabbedPane.addTab(title, childComp)
                }
                if (activeIndex in 0 until tabbedPane.tabCount) {
                    suppressTabEvents = true
                    try {
                        tabbedPane.selectedIndex = activeIndex
                    } finally {
                        suppressTabEvents = false
                    }
                }
                rootPanel.add(tabbedPane, BorderLayout.CENTER)
            }
            DockGroupMode.HORIZONTAL_SPLIT, DockGroupMode.VERTICAL_SPLIT -> {
                val isHorizontal = modeVal == DockGroupMode.HORIZONTAL_SPLIT
                stackedPanel.layout = SwingFlexLayoutManager(
                    defaultDisplay = CssDisplay.FLEX,
                    defaultFlexDirection = if (isHorizontal) FlexDirection.ROW else FlexDirection.COLUMN,
                    defaultGapPx = 4
                )
                for ((_, childComp) in managedChildren) {
                    val impl = childComp.getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl
                    impl?.setAttribute("data-stack-weight", "1")
                    stackedPanel.add(childComp)
                }
                rootPanel.add(stackedPanel, BorderLayout.CENTER)
            }
        }
        SwingFlexLayoutManager.layoutTreeNow(rootPanel)
        rootPanel.revalidate()
        rootPanel.repaint()
    }
}
