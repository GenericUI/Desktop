package com.nexustools.genericui.swing

import com.nexustools.genericui.component.dock.DockHeaderAction
import com.nexustools.genericui.nativeimpl.dock.DockPanelNativeImpl
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Java Swing implementation of [DockPanelNativeImpl].
 */
class SwingDockPanelImpl : SwingBaseContainerNativeImpl(JPanel(), "dock-panel"), DockPanelNativeImpl<JComponent> {
    private var titleText: String = ""
    private var collapsedFlag: Boolean = false
    private var floatingFlag: Boolean = false

    override fun setTitle(title: String) {
        titleText = title
        nativeWidget.putClientProperty("title", title)
    }

    override fun getTitle(): String = titleText

    override fun setIcon(icon: String?) {
        nativeWidget.putClientProperty("icon", icon)
    }

    override fun setCollapsed(collapsed: Boolean) {
        collapsedFlag = collapsed
    }

    override fun isCollapsed(): Boolean = collapsedFlag

    override fun setFloating(floating: Boolean) {
        floatingFlag = floating
    }

    override fun isFloating(): Boolean = floatingFlag

    override fun setHeaderActions(actions: List<DockHeaderAction>) {
        nativeWidget.putClientProperty("headerActionCount", actions.size)
    }
}
