package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.macos.render.MacosControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * macOS AppKit `NSTabView` implementation of [com.nexustools.genericui.nativeimpl.tabs.TabContainerNativeImpl].
 */
open class MacosTabContainerNativeImpl(
    nativeWidget: MacosViewHandle = MacosViewHandle("NSTabView")
) : DesktopTabContainerNativeImpl<MacosViewHandle>(
    nativeWidget = nativeWidget,
    supportsNativeFadeTransition = true
) {
    init {
        MacosControlRenderUtil.syncStandardControlAppearance(this, "NSTabView")
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        MacosControlRenderUtil.renderTabContainer(this, renderContext, bounds)

    override fun setType(type: BootstrapType?) {
        super.setType(type)
        MacosControlRenderUtil.syncStandardControlAppearance(this, "NSTabView")
    }

    override fun setBackgroundType(type: BootstrapType?) {
        super.setBackgroundType(type)
        MacosControlRenderUtil.syncStandardControlAppearance(this, "NSTabView")
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        MacosControlRenderUtil.syncStandardControlAppearance(this, "NSTabView")
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        MacosControlRenderUtil.syncStandardControlAppearance(this, "NSTabView")
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        MacosControlRenderUtil.syncStandardControlAppearance(this, "NSTabView")
    }
}
