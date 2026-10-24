package com.nexustools.genericui.swing

import com.nexustools.genericui.component.tabs.TabBarStyle
import com.nexustools.genericui.component.tabs.TabPosition
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.nativeimpl.tabs.TabContainerNativeImpl
import com.nexustools.genericui.nativeimpl.tabs.TabNativeImpl
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane

/**
 * Java Swing `JTabbedPane` implementation of [TabContainerNativeImpl].
 *
 * Mounts each tab's native [SwingTabImpl] header component directly into the `JTabbedPane`
 * tab bar via `JTabbedPane.setTabComponentAt(index, tabImpl.nativeWidget)`.
 */
class SwingTabContainerImpl : SwingBaseContainerNativeImpl(JTabbedPane(), "nav-tabs"), TabContainerNativeImpl<JComponent> {
    override val supportsNativeFadeTransition: Boolean = false

    private val tabbedPane: JTabbedPane = nativeWidget as JTabbedPane
    private var selectedIndexVal: Int = 0
    private var suppressChangeEvent: Boolean = false
    private var onTabSelectedListener: ((Int) -> Unit)? = null
    private var onTabCloseRequestedListener: ((Int) -> Boolean)? = null

    init {
        tabbedPane.addChangeListener {
            if (!suppressChangeEvent && tabbedPane.selectedIndex >= 0) {
                selectedIndexVal = tabbedPane.selectedIndex
                onTabSelectedListener?.invoke(selectedIndexVal)
            }
        }
    }

    override fun addTabNative(tabImpl: TabNativeImpl<*>, contentImpl: NativeImpl<*>, index: Int) {
        val contentComp = (contentImpl.nativeWidget as? JComponent) ?: JPanel()
        val swingTab = tabImpl as? SwingTabImpl
        val title = swingTab?.titleVal ?: ""
        val tooltip = swingTab?.tooltipVal
        val insertIndex = if (index < 0 || index > tabbedPane.tabCount) tabbedPane.tabCount else index

        suppressChangeEvent = true
        try {
            tabbedPane.insertTab(title, null, contentComp, tooltip, insertIndex)
            val headerComp = tabImpl.nativeWidget as? JComponent
            if (headerComp != null) {
                tabbedPane.setTabComponentAt(insertIndex, headerComp)
            }
        } finally {
            suppressChangeEvent = false
        }
    }

    override fun addTabNative(title: String, contentImpl: NativeImpl<*>, index: Int) {
        val contentComp = (contentImpl.nativeWidget as? JComponent) ?: JPanel()
        val insertIndex = if (index < 0 || index > tabbedPane.tabCount) tabbedPane.tabCount else index
        suppressChangeEvent = true
        try {
            tabbedPane.insertTab(title, null, contentComp, null, insertIndex)
        } finally {
            suppressChangeEvent = false
        }
    }

    override fun updateTabNative(index: Int, tabImpl: TabNativeImpl<*>) {
        if (index in 0 until tabbedPane.tabCount) {
            val swingTab = tabImpl as? SwingTabImpl
            if (swingTab != null) {
                tabbedPane.setTitleAt(index, swingTab.titleVal)
                tabbedPane.setToolTipTextAt(index, swingTab.tooltipVal)
            }
            (tabImpl.nativeWidget as? JComponent)?.let { tabbedPane.setTabComponentAt(index, it) }
        }
    }

    override fun removeTabNative(index: Int) {
        if (index in 0 until tabbedPane.tabCount) {
            suppressChangeEvent = true
            try {
                tabbedPane.removeTabAt(index)
                selectedIndexVal = tabbedPane.selectedIndex.coerceAtLeast(0)
            } finally {
                suppressChangeEvent = false
            }
        }
    }

    override fun setTabTitleNative(index: Int, title: String) {
        if (index in 0 until tabbedPane.tabCount) {
            tabbedPane.setTitleAt(index, title)
        }
    }

    override fun setTabPositionNative(position: TabPosition) {
        tabbedPane.tabPlacement = when (position) {
            TabPosition.TOP -> JTabbedPane.TOP
            TabPosition.BOTTOM -> JTabbedPane.BOTTOM
            TabPosition.LEFT -> JTabbedPane.LEFT
            TabPosition.RIGHT -> JTabbedPane.RIGHT
        }
    }

    override fun setTabBarStyleNative(style: TabBarStyle) {
        tabbedPane.putClientProperty("tabBarStyle", style.name)
    }

    override fun setSelectedTabIndex(index: Int, fadeTransition: Boolean) {
        selectedIndexVal = index
        if (index in 0 until tabbedPane.tabCount && tabbedPane.selectedIndex != index) {
            suppressChangeEvent = true
            try {
                tabbedPane.selectedIndex = index
            } finally {
                suppressChangeEvent = false
            }
        }
    }

    override fun getSelectedTabIndex(): Int = selectedIndexVal

    override fun setOnTabSelected(listener: ((Int) -> Unit)?) {
        onTabSelectedListener = listener
    }

    override fun setOnTabCloseRequested(listener: ((Int) -> Boolean)?) {
        onTabCloseRequestedListener = listener
    }
}
