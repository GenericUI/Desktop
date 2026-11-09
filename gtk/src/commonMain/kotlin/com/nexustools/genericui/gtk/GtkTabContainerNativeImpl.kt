package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.render.GtkControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * GTK 4 / Libadwaita `AdwViewStack` + `GtkStack` (`GTK_STACK_TRANSITION_TYPE_CROSSFADE`)
 * implementation of [com.nexustools.genericui.nativeimpl.tabs.TabContainerNativeImpl].
 */
open class GtkTabContainerNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("AdwViewStack")
) : DesktopTabContainerNativeImpl<GtkWidgetHandle>(
    nativeWidget = nativeWidget,
    supportsNativeFadeTransition = true
) {
    init {
        GtkControlRenderUtil.syncStandardControlAppearance(this, "gtk_render_background[AdwViewStack]")
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        GtkControlRenderUtil.renderTabContainer(this, renderContext, bounds)

    override fun setType(type: BootstrapType?) {
        super.setType(type)
        GtkControlRenderUtil.syncStandardControlAppearance(this, "gtk_render_background[AdwViewStack]")
    }

    override fun setBackgroundType(type: BootstrapType?) {
        super.setBackgroundType(type)
        GtkControlRenderUtil.syncStandardControlAppearance(this, "gtk_render_background[AdwViewStack]")
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        GtkControlRenderUtil.syncStandardControlAppearance(this, "gtk_render_background[AdwViewStack]")
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        GtkControlRenderUtil.syncStandardControlAppearance(this, "gtk_render_background[AdwViewStack]")
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        GtkControlRenderUtil.syncStandardControlAppearance(this, "gtk_render_background[AdwViewStack]")
    }
}
