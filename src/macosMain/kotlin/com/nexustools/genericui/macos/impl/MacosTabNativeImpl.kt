package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.widget.DesktopTabNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.macos.render.MacosControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * macOS AppKit `NSTabViewItem` implementation of [com.nexustools.genericui.nativeimpl.tabs.TabNativeImpl].
 */
open class MacosTabNativeImpl(
    nativeWidget: MacosViewHandle = MacosViewHandle("NSTabViewItem"),
    closable: Boolean = false
) : DesktopTabNativeImpl<MacosViewHandle>(nativeWidget, closable) {

    init {
        syncAppearance()
    }

    protected open fun syncAppearance() {
        MacosControlRenderUtil.syncStandardControlAppearance(this, "NSTabViewItem")
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        MacosControlRenderUtil.renderTab(this, renderContext, bounds)

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
