package com.nexustools.genericui.desktop.dock

import com.nexustools.genericui.component.dock.DockHeaderAction
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.dock.DockPanelNativeImpl

/**
 * Shared desktop base implementation of [DockPanelNativeImpl].
 */
open class DesktopDockPanelNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "dock-panel"), DockPanelNativeImpl<W> {

    protected var titleText: String = ""
    protected var iconName: String? = null
    protected var collapsedState: Boolean = false
    protected var onCollapsedChangedListener: ((Boolean) -> Unit)? = null
    protected var floatingState: Boolean = false
    protected var headerActionsList: List<DockHeaderAction> = emptyList()

    override fun setTitle(title: String) {
        titleText = title
        nativeWidget.attributes["title"] = title
    }

    override fun getTitle(): String = titleText

    override fun setIcon(icon: String?) {
        iconName = icon
        nativeWidget.attributes["icon"] = icon
    }

    override fun setCollapsed(collapsed: Boolean) {
        if (collapsedState != collapsed) {
            collapsedState = collapsed
            nativeWidget.attributes["collapsed"] = collapsed
            onCollapsedChangedListener?.invoke(collapsed)
        }
    }

    override fun isCollapsed(): Boolean = collapsedState

    override fun setOnCollapsedChanged(listener: ((Boolean) -> Unit)?) {
        onCollapsedChangedListener = listener
    }

    fun toggleCollapsed(): Boolean {
        setCollapsed(!collapsedState)
        return collapsedState
    }

    override fun setFloating(floating: Boolean) {
        floatingState = floating
        nativeWidget.attributes["floating"] = floating
    }

    override fun isFloating(): Boolean = floatingState

    override fun setHeaderActions(actions: List<DockHeaderAction>) {
        headerActionsList = actions
        nativeWidget.attributes["headerActions"] = actions
    }
}
