package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.render.GtkControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * GTK4 `GtkEntry` / `GtkTextView` implementation of
 * [com.nexustools.genericui.nativeimpl.form.TextInputNativeImpl].
 *
 * Ignores aesthetic and typography [ComponentStyle] overrides unless hosted in a custom style engine,
 * and supports [BootstrapType] tinting via [GtkControlRenderUtil].
 */
open class GtkTextInputNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkEntry"),
    multiline: Boolean = false
) : DesktopTextInputNativeImpl<GtkWidgetHandle>(nativeWidget, multiline) {

    init {
        syncAppearance()
    }

    protected open fun syncAppearance() {
        val node = if (isMultiline()) "gtk_render_background+gtk_render_frame[textview]" else "gtk_render_background+gtk_render_frame[entry]"
        GtkControlRenderUtil.syncStandardControlAppearance(this, node)
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        GtkControlRenderUtil.renderTextInput(this, renderContext, bounds)

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
