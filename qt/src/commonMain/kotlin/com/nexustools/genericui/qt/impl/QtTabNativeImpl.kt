package com.nexustools.genericui.qt.impl

import com.nexustools.genericui.desktop.widget.DesktopTabNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.qt.QtWidgetHandle
import com.nexustools.genericui.qt.render.QtControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * Qt6 `QTabBar` item (`QStyle::CE_TabBarTab`) implementation of
 * [com.nexustools.genericui.nativeimpl.tabs.TabNativeImpl].
 */
open class QtTabNativeImpl(
    nativeWidget: QtWidgetHandle = QtWidgetHandle("QTabBarItem"),
    closable: Boolean = false
) : DesktopTabNativeImpl<QtWidgetHandle>(nativeWidget, closable) {

    init {
        syncAppearance()
    }

    protected open fun syncAppearance() {
        QtControlRenderUtil.syncStandardControlAppearance(this, "QStyle::CE_TabBarTab[QStyleOptionTab]")
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        QtControlRenderUtil.renderTab(this, renderContext, bounds)

    override fun setTitleNative(title: String) {
        super.setTitleNative(title)
        syncAppearance()
    }

    override fun setSelectedNative(selected: Boolean) {
        super.setSelectedNative(selected)
        syncAppearance()
    }

    override fun setType(type: BootstrapType?) {
        super.setType(type)
        syncAppearance()
    }

    override fun setBackgroundType(type: BootstrapType?) {
        super.setBackgroundType(type)
        syncAppearance()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        syncAppearance()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        syncAppearance()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        syncAppearance()
    }
}
