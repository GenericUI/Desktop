package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.render.GtkControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * GTK4 `GtkCheckButton` (`gtk_render_check` / `gtk_render_option`) and `GtkSwitch`
 * (`gtk_render_slider`) implementation of [com.nexustools.genericui.nativeimpl.form.CheckboxNativeImpl].
 */
open class GtkCheckboxNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkCheckButton"),
    switchStyle: Boolean = false,
    radioStyle: Boolean = false
) : DesktopCheckboxNativeImpl<GtkWidgetHandle>(nativeWidget, switchStyle, radioStyle) {

    init {
        syncAppearance()
    }

    protected open fun syncAppearance() {
        val node = when {
            switchStyle -> "gtk_render_background+gtk_render_slider[switch]"
            radioStyle -> "gtk_render_option[radio]"
            else -> "gtk_render_check[checkbutton]"
        }
        GtkControlRenderUtil.syncStandardControlAppearance(this, node)
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        GtkControlRenderUtil.renderCheckbox(this, renderContext, bounds)

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
