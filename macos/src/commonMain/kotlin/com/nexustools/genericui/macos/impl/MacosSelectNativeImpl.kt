package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.macos.render.MacosControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * macOS AppKit `NSPopUpButton` (`NSPopUpButtonCell`) implementation of
 * [com.nexustools.genericui.nativeimpl.form.SelectNativeImpl].
 */
open class MacosSelectNativeImpl(
    nativeWidget: MacosViewHandle = MacosViewHandle("NSPopUpButton")
) : DesktopSelectNativeImpl<MacosViewHandle>(nativeWidget) {

    init {
        syncAppearance()
    }

    protected open fun syncAppearance() {
        MacosControlRenderUtil.syncStandardControlAppearance(this, "NSPopUpButtonCell")
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        MacosControlRenderUtil.renderSelect(this, renderContext, bounds)

    override fun setOptions(options: List<Pair<String, String>>) {
        super.setOptions(options)
        syncAppearance()
    }

    override fun setSelectedValue(value: String?) {
        super.setSelectedValue(value)
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

    override fun setOutline(outline: Boolean) {
        super.setOutline(outline)
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
