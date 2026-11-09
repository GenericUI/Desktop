package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.qt.render.QtControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * Qt 6 `QTabWidget` implementation of [com.nexustools.genericui.nativeimpl.tabs.TabContainerNativeImpl]
 * with `QGraphicsOpacityEffect` + `QPropertyAnimation` fade transitions.
 */
open class QtTabContainerNativeImpl(
    nativeWidget: QtWidgetHandle = QtWidgetHandle("QTabWidget")
) : DesktopTabContainerNativeImpl<QtWidgetHandle>(
    nativeWidget = nativeWidget,
    supportsNativeFadeTransition = true
) {
    init {
        QtControlRenderUtil.syncStandardControlAppearance(this, "QStyle::CE_TabBarTabShape[QTabWidget]")
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        QtControlRenderUtil.renderTabContainer(this, renderContext, bounds)

    override fun setType(type: BootstrapType?) {
        super.setType(type)
        QtControlRenderUtil.syncStandardControlAppearance(this, "QStyle::CE_TabBarTabShape[QTabWidget]")
    }

    override fun setBackgroundType(type: BootstrapType?) {
        super.setBackgroundType(type)
        QtControlRenderUtil.syncStandardControlAppearance(this, "QStyle::CE_TabBarTabShape[QTabWidget]")
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        QtControlRenderUtil.syncStandardControlAppearance(this, "QStyle::CE_TabBarTabShape[QTabWidget]")
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        QtControlRenderUtil.syncStandardControlAppearance(this, "QStyle::CE_TabBarTabShape[QTabWidget]")
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        QtControlRenderUtil.syncStandardControlAppearance(this, "QStyle::CE_TabBarTabShape[QTabWidget]")
    }
}
