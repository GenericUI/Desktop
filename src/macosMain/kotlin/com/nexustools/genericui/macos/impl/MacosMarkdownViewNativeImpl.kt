package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.widget.DesktopMarkdownViewNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.macos.render.MacosControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * macOS AppKit `NSTextView` attributed-string implementation of
 * [com.nexustools.genericui.nativeimpl.typography.MarkdownViewNativeImpl].
 *
 * Honors typography and text-color properties from [ComponentStyle] and [ComponentBootstrap].
 */
open class MacosMarkdownViewNativeImpl(
    nativeWidget: MacosViewHandle = MacosViewHandle("NSTextView_Attributed")
) : DesktopMarkdownViewNativeImpl<MacosViewHandle>(nativeWidget) {

    init {
        nativeWidget.attributes["componentRole"] = "markdown-view"
        syncAppearance()
    }

    protected open fun syncAppearance() {
        MacosControlRenderUtil.syncTypographyElementAppearance(this, "NSTextView_NSAttributedString_Markdown")
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        MacosControlRenderUtil.renderMarkdownView(this, renderContext, bounds)

    override fun setMarkdown(markdown: String) {
        super.setMarkdown(markdown)
        syncAppearance()
    }

    override fun setType(type: BootstrapType?) {
        super.setType(type)
        syncAppearance()
    }

    override fun setTextColorType(type: BootstrapType?) {
        super.setTextColorType(type)
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
