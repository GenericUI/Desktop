package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.macos.render.MacosControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * macOS AppKit `NSButton` (`NSButtonTypeSwitch` / `NSButtonTypeRadio`) and `NSSwitch` implementation
 * of [com.nexustools.genericui.nativeimpl.form.CheckboxNativeImpl].
 */
open class MacosCheckboxNativeImpl(
    nativeWidget: MacosViewHandle = MacosViewHandle("NSButton_Switch"),
    switchStyle: Boolean = false,
    radioStyle: Boolean = false
) : DesktopCheckboxNativeImpl<MacosViewHandle>(nativeWidget, switchStyle, radioStyle) {

    init {
        syncAppearance()
    }

    protected open fun syncAppearance() {
        val cell = when {
            switchStyle -> "NSSwitch"
            radioStyle -> "NSButtonCell_NSButtonTypeRadio"
            else -> "NSButtonCell_NSButtonTypeSwitch"
        }
        MacosControlRenderUtil.syncStandardControlAppearance(this, cell)
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        MacosControlRenderUtil.renderCheckbox(this, renderContext, bounds)

    override fun setLabel(label: String) {
        super.setLabel(label)
        syncAppearance()
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
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
