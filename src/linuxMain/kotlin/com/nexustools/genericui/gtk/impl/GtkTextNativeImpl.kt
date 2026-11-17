package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.FontWeight
import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.enums.bootstrap.TextOverflow
import com.nexustools.genericui.enums.bootstrap.TextTransform
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.render.GtkControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * GTK4 `GtkLabel` (`gtk_render_layout`) implementation of
 * [com.nexustools.genericui.nativeimpl.typography.TextNativeImpl] for `Label`, `Text`, `Heading`,
 * `Badge`, `Blockquote`, and `CodeBlock`.
 *
 * Honors typography and text-color properties from [ComponentStyle] and [ComponentBootstrap] while
 * ignoring background/border skinning overrides unless hosted inside a custom style engine (`WebView`).
 */
open class GtkTextNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkLabel"),
    tagName: String = "span",
    classPrefix: String = ""
) : DesktopTextNativeImpl<GtkWidgetHandle>(nativeWidget, tagName, classPrefix) {

    init {
        nativeWidget.attributes["tagName"] = tagName
        nativeWidget.attributes["componentRole"] = if (classPrefix.isNotBlank()) classPrefix else "text"
        syncAppearance()
    }

    protected open fun syncAppearance() {
        GtkControlRenderUtil.syncTypographyElementAppearance(this, "gtk_render_layout[label]")
    }

    override fun measureDesktopNode(parentWidthPx: Int, parentHeightPx: Int, dpiScale: Float): Size =
        GtkControlRenderUtil.measureText(this, dpiScale)

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        GtkControlRenderUtil.renderText(this, renderContext, bounds)

    override fun setText(text: String) {
        super.setText(text)
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

    override fun setTextColorType(type: BootstrapType?) {
        super.setTextColorType(type)
        syncAppearance()
    }

    override fun setTextAlignment(alignment: TextAlignment?) {
        super.setTextAlignment(alignment)
        syncAppearance()
    }

    override fun setFontWeight(weight: FontWeight?) {
        super.setFontWeight(weight)
        syncAppearance()
    }

    override fun setTextTransform(transform: TextTransform?) {
        super.setTextTransform(transform)
        syncAppearance()
    }

    override fun setTextOverflow(overflow: TextOverflow?) {
        super.setTextOverflow(overflow)
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
