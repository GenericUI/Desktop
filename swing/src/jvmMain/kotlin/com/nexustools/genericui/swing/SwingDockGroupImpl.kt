package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.dock.DockGroupMode
import com.nexustools.genericui.nativeimpl.dock.DockGroupNativeImpl
import javax.swing.JComponent
import javax.swing.JTabbedPane

/**
 * Java Swing `JTabbedPane` implementation of [DockGroupNativeImpl].
 */
class SwingDockGroupImpl : SwingBaseContainerNativeImpl(JTabbedPane(), "dock-group"), DockGroupNativeImpl<JComponent> {
    private val tabbedPane: JTabbedPane = nativeWidget as JTabbedPane
    private var modeVal: DockGroupMode = DockGroupMode.TABBED
    private var activeIndex: Int = 0

    override fun setGroupMode(mode: DockGroupMode) {
        modeVal = mode
    }

    override fun getGroupMode(): DockGroupMode = modeVal

    override fun setActivePanelIndex(index: Int) {
        activeIndex = index
        if (index in 0 until tabbedPane.tabCount) {
            tabbedPane.selectedIndex = index
        }
    }

    override fun getActivePanelIndex(): Int = activeIndex
}
