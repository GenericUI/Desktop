package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.component.tabs.TabBarStyle
import com.nexustools.genericui.component.tabs.TabPosition
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.tabs.TabContainerNativeImpl
import com.nexustools.genericui.nativeimpl.tabs.TabNativeImpl

/**
 * Shared desktop base implementation of [TabContainerNativeImpl] for `WC_TABCONTROL` (`:windows`),
 * `NSTabView` (`:macos`), `AdwTabView`/`GtkNotebook` (`:gtk`), and `QTabWidget` (`:qt`).
 *
 * Translates each [TabNativeImpl] ([DesktopTabNativeImpl]) into the platform's native tab item
 * (including native close buttons for `CloseableTab`, badges for `BadgeTab`, and embedded custom
 * native widgets for custom tab headers).
 */
open class DesktopTabContainerNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    override val supportsNativeFadeTransition: Boolean = true
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "nav-tabs"), TabContainerNativeImpl<W> {

    protected val tabImpls: MutableList<TabNativeImpl<*>?> = mutableListOf()
    protected val tabTitles: MutableList<String> = mutableListOf()
    protected var selectedIndexVal: Int = 0
    protected var tabPositionVal: TabPosition = TabPosition.TOP
    protected var tabBarStyleVal: TabBarStyle = TabBarStyle.TABS
    protected var onTabSelectedListener: ((Int) -> Unit)? = null
    protected var onTabCloseRequestedListener: ((Int) -> Boolean)? = null

    override fun addTabNative(tabImpl: TabNativeImpl<*>, contentImpl: NativeImpl<*>, index: Int) {
        val desktopTab = tabImpl as? DesktopTabNativeImpl<*>
        val title = desktopTab?.titleVal ?: ""
        if (index < 0 || index >= tabTitles.size) {
            tabImpls.add(tabImpl)
            tabTitles.add(title)
        } else {
            tabImpls.add(index, tabImpl)
            tabTitles.add(index, title)
        }
        val tabWidget = tabImpl.nativeWidget as? DesktopNativeWidget
        if (tabWidget != null) {
            nativeWidget.addChildWidget(tabWidget, index)
        }
        addChildNative(contentImpl, index)
    }

    override fun addTabNative(title: String, contentImpl: NativeImpl<*>, index: Int) {
        if (index < 0 || index >= tabTitles.size) {
            tabImpls.add(null)
            tabTitles.add(title)
        } else {
            tabImpls.add(index, null)
            tabTitles.add(index, title)
        }
        addChildNative(contentImpl, index)
    }

    override fun updateTabNative(index: Int, tabImpl: TabNativeImpl<*>) {
        if (index in tabImpls.indices) {
            tabImpls[index] = tabImpl
            (tabImpl as? DesktopTabNativeImpl<*>)?.let { tabTitles[index] = it.titleVal }
        }
    }

    override fun removeTabNative(index: Int) {
        if (index in tabTitles.indices) {
            val removedTab = tabImpls.removeAt(index)
            val removedTabWidget = removedTab?.nativeWidget as? DesktopNativeWidget
            if (removedTabWidget != null) {
                nativeWidget.removeChildWidget(removedTabWidget)
            }
            tabTitles.removeAt(index)
            if (index in childImpls.indices) {
                removeChildNative(childImpls[index])
            }
            selectedIndexVal = selectedIndexVal.coerceIn(0, (tabTitles.size - 1).coerceAtLeast(0))
        }
    }

    override fun setTabTitleNative(index: Int, title: String) {
        if (index in tabTitles.indices) {
            tabTitles[index] = title
            tabImpls[index]?.setTitleNative(title)
        }
    }

    override fun setTabPositionNative(position: TabPosition) {
        tabPositionVal = position
        nativeWidget.attributes["tabPosition"] = position.name
    }

    override fun setTabBarStyleNative(style: TabBarStyle) {
        tabBarStyleVal = style
        nativeWidget.attributes["tabBarStyle"] = style.name
    }

    override fun setSelectedTabIndex(index: Int, fadeTransition: Boolean) {
        val clamped = index.coerceIn(0, (tabTitles.size - 1).coerceAtLeast(0))
        if (selectedIndexVal != clamped) {
            selectedIndexVal = clamped
            nativeWidget.attributes["selectedTabIndex"] = clamped
            nativeWidget.attributes["fadeTransition"] = fadeTransition
            onTabSelectedListener?.invoke(clamped)
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
