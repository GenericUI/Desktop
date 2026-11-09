package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopTabNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.render.GtkControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * GTK4 / Libadwaita `AdwTabPage` / `GtkNotebookPage` implementation of
 * [com.nexustools.genericui.nativeimpl.tabs.TabNativeImpl].
 */
open class GtkTabNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("AdwTabPage"),
    closable: Boolean = false
) : DesktopTabNativeImpl<GtkWidgetHandle>(nativeWidget, closable) {

    init {
        syncAppearance()
    }

    protected open fun syncAppearance() {
        GtkControlRenderUtil.syncStandardControlAppearance(this, "gtk_render_background+gtk_render_frame[tab]")
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        GtkControlRenderUtil.renderTab(this, renderContext, bounds)

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
