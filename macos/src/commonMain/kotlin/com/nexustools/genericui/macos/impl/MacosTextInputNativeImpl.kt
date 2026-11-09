package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.macos.render.MacosControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * macOS AppKit `NSTextField` / `NSTextView` implementation of
 * [com.nexustools.genericui.nativeimpl.form.TextInputNativeImpl].
 *
 * Ignores aesthetic and typography [ComponentStyle] overrides unless hosted in a custom style engine,
 * and supports [BootstrapType] tinting via [MacosControlRenderUtil].
 */
open class MacosTextInputNativeImpl(
    nativeWidget: MacosViewHandle = MacosViewHandle("NSTextField"),
    multiline: Boolean = false
) : DesktopTextInputNativeImpl<MacosViewHandle>(nativeWidget, multiline) {

    init {
        syncAppearance()
    }

    protected open fun syncAppearance() {
        val cell = if (isMultiline()) "NSTextView_Scrollable" else "NSTextFieldCell_BezelSquare"
        MacosControlRenderUtil.syncStandardControlAppearance(this, cell)
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        MacosControlRenderUtil.renderTextInput(this, renderContext, bounds)

    override fun setValue(value: String) {
        super.setValue(value)
        syncAppearance()
    }

    override fun setPlaceholder(placeholder: String) {
        super.setPlaceholder(placeholder)
        syncAppearance()
    }

    override fun setMultiline(multiline: Boolean) {
        super.setMultiline(multiline)
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
