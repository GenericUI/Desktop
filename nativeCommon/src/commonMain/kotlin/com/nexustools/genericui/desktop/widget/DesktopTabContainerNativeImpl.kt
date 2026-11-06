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
    protected val contentImpls: MutableList<NativeImpl<*>?> = mutableListOf()
    protected val buttonTabFlags: MutableList<Boolean> = mutableListOf()
    protected var selectedIndexVal: Int = 0
    protected var tabPositionVal: TabPosition = TabPosition.TOP
    protected var tabBarStyleVal: TabBarStyle = TabBarStyle.TABS
    protected var onTabSelectedListener: ((Int) -> Unit)? = null
    protected var onTabCloseRequestedListener: ((Int) -> Boolean)? = null

    fun isButtonTab(index: Int): Boolean = buttonTabFlags.getOrElse(index) { false }

    fun getSelectedContentWidget(): DesktopNativeWidget? =
        contentImpls.getOrNull(selectedIndexVal)?.nativeWidget as? DesktopNativeWidget

    protected fun syncContentWidgetsVisibility() {
        for (i in tabImpls.indices) {
            val isBtn = buttonTabFlags.getOrElse(i) { false }
            val isActive = (i == selectedIndexVal) && !isBtn
            tabImpls.getOrNull(i)?.setSelectedNative(isActive)
            (contentImpls.getOrNull(i)?.nativeWidget as? DesktopNativeWidget)?.let { w ->
                w.visible = isActive
                w.attributes["visible"] = isActive
            }
        }
    }

    override fun addTabNative(tabImpl: TabNativeImpl<*>, contentImpl: NativeImpl<*>, index: Int) {
        val desktopTab = tabImpl as? DesktopTabNativeImpl<*>
        val title = desktopTab?.titleVal ?: ""
        val existingIdx = tabImpls.indexOfFirst { it === tabImpl }
        if (existingIdx >= 0) {
            tabTitles[existingIdx] = title
            contentImpls[existingIdx] = contentImpl
            buttonTabFlags[existingIdx] = false
            if (index in 0 until tabImpls.size && existingIdx != index) {
                val movedImpl = tabImpls.removeAt(existingIdx)
                val movedTitle = tabTitles.removeAt(existingIdx)
                val movedContent = contentImpls.removeAt(existingIdx)
                val movedBtn = buttonTabFlags.removeAt(existingIdx)
                val targetIdx = index.coerceIn(0, tabImpls.size)
                tabImpls.add(targetIdx, movedImpl)
                tabTitles.add(targetIdx, movedTitle)
                contentImpls.add(targetIdx, movedContent)
                buttonTabFlags.add(targetIdx, movedBtn)
            }
        } else if (index < 0 || index >= tabTitles.size) {
            tabImpls.add(tabImpl)
            tabTitles.add(title)
            contentImpls.add(contentImpl)
            buttonTabFlags.add(false)
        } else {
            tabImpls.add(index, tabImpl)
            tabTitles.add(index, title)
            contentImpls.add(index, contentImpl)
            buttonTabFlags.add(index, false)
        }
        val tabWidget = tabImpl.nativeWidget as? DesktopNativeWidget
        if (tabWidget != null && !nativeWidget.children.contains(tabWidget)) {
            nativeWidget.addChildWidget(tabWidget, -1)
        }
        val contentWidget = contentImpl.nativeWidget as? DesktopNativeWidget
        if (contentWidget == null || !nativeWidget.children.contains(contentWidget)) {
            addChildNative(contentImpl, -1)
        }
        if (selectedIndexVal !in tabTitles.indices || isButtonTab(selectedIndexVal)) {
            val firstSelectable = buttonTabFlags.indexOfFirst { !it }
            if (firstSelectable >= 0) {
                selectedIndexVal = firstSelectable
                nativeWidget.attributes["selectedTabIndex"] = selectedIndexVal
            }
        }
        syncContentWidgetsVisibility()
    }

    override fun addButtonTabNative(tabImpl: TabNativeImpl<*>, index: Int) {
        val desktopTab = tabImpl as? DesktopTabNativeImpl<*>
        val title = desktopTab?.titleVal ?: ""
        (tabImpl.nativeWidget as? DesktopNativeWidget)?.attributes?.put("isButtonTab", true)
        val existingIdx = tabImpls.indexOfFirst { it === tabImpl }
        if (existingIdx >= 0) {
            tabTitles[existingIdx] = title
            contentImpls[existingIdx] = null
            buttonTabFlags[existingIdx] = true
        } else if (index < 0 || index >= tabTitles.size) {
            tabImpls.add(tabImpl)
            tabTitles.add(title)
            contentImpls.add(null)
            buttonTabFlags.add(true)
        } else {
            tabImpls.add(index, tabImpl)
            tabTitles.add(index, title)
            contentImpls.add(index, null)
            buttonTabFlags.add(index, true)
        }
        val tabWidget = tabImpl.nativeWidget as? DesktopNativeWidget
        if (tabWidget != null && !nativeWidget.children.contains(tabWidget)) {
            nativeWidget.addChildWidget(tabWidget, -1)
        }
        syncContentWidgetsVisibility()
    }

    override fun addTabNative(title: String, contentImpl: NativeImpl<*>, index: Int) {
        if (index < 0 || index >= tabTitles.size) {
            tabImpls.add(null)
            tabTitles.add(title)
            contentImpls.add(contentImpl)
            buttonTabFlags.add(false)
        } else {
            tabImpls.add(index, null)
            tabTitles.add(index, title)
            contentImpls.add(index, contentImpl)
            buttonTabFlags.add(index, false)
        }
        val contentWidget = contentImpl.nativeWidget as? DesktopNativeWidget
        if (contentWidget == null || !nativeWidget.children.contains(contentWidget)) {
            addChildNative(contentImpl, -1)
        }
        syncContentWidgetsVisibility()
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
            val removedContent = contentImpls.removeAt(index)
            val removedContentWidget = removedContent?.nativeWidget as? DesktopNativeWidget
            if (removedContentWidget != null) {
                nativeWidget.removeChildWidget(removedContentWidget)
            }
            tabTitles.removeAt(index)
            buttonTabFlags.removeAt(index)
            if (tabTitles.isNotEmpty()) {
                selectedIndexVal = selectedIndexVal.coerceIn(0, (tabTitles.size - 1).coerceAtLeast(0))
                if (isButtonTab(selectedIndexVal)) {
                    val firstSelectable = buttonTabFlags.indexOfFirst { !it }
                    if (firstSelectable >= 0) {
                        selectedIndexVal = firstSelectable
                    }
                }
            } else {
                selectedIndexVal = 0
            }
            syncContentWidgetsVisibility()
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

    fun getTabPosition(): TabPosition = tabPositionVal

    override fun setTabBarStyleNative(style: TabBarStyle) {
        tabBarStyleVal = style
        nativeWidget.attributes["tabBarStyle"] = style.name
    }

    override fun setSelectedTabIndex(index: Int, fadeTransition: Boolean) {
        if (tabTitles.isEmpty()) return
        val clamped = index.coerceIn(0, (tabTitles.size - 1).coerceAtLeast(0))
        if (isButtonTab(clamped)) {
            val desktopTab = tabImpls.getOrNull(clamped) as? DesktopTabNativeImpl<*>
            desktopTab?.setSelectedNative(false)
            if (desktopTab != null && desktopTab.hasClickListener()) {
                desktopTab.dispatchClick()
            } else {
                onTabSelectedListener?.invoke(clamped)
            }
            syncContentWidgetsVisibility()
            return
        }
        val changed = (selectedIndexVal != clamped)
        selectedIndexVal = clamped
        nativeWidget.attributes["selectedTabIndex"] = clamped
        nativeWidget.attributes["fadeTransition"] = fadeTransition
        syncContentWidgetsVisibility()
        if (changed) {
            onTabSelectedListener?.invoke(clamped)
        }
    }

    override fun getSelectedTabIndex(): Int = selectedIndexVal

    fun getActiveTabIndex(): Int = selectedIndexVal

    override fun setOnTabSelected(listener: ((Int) -> Unit)?) {
        onTabSelectedListener = listener
    }

    override fun setOnTabCloseRequested(listener: ((Int) -> Boolean)?) {
        onTabCloseRequestedListener = listener
    }

    fun getTabImpls(): List<TabNativeImpl<*>?> = tabImpls.toList()

    fun getTabTitles(): List<String> = tabTitles.toList()

    fun requestSelectTab(index: Int) {
        if (index in tabTitles.indices) {
            if (isButtonTab(index)) {
                val desktopTab = tabImpls.getOrNull(index) as? DesktopTabNativeImpl<*>
                desktopTab?.setSelectedNative(false)
                if (desktopTab != null && desktopTab.hasClickListener()) {
                    desktopTab.dispatchClick()
                } else {
                    onTabSelectedListener?.invoke(index)
                }
                syncContentWidgetsVisibility()
                return
            }
            selectedIndexVal = index
            nativeWidget.attributes["selectedTabIndex"] = index
            syncContentWidgetsVisibility()
            onTabSelectedListener?.invoke(index)
        }
    }

    fun requestCloseTab(index: Int) {
        if (index in tabTitles.indices) {
            onTabCloseRequestedListener?.invoke(index)
        }
    }
}
