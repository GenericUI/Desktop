package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopProgressBarNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.render.GtkControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * GTK4 `GtkProgressBar` implementation of
 * [com.nexustools.genericui.nativeimpl.feedback.ProgressBarNativeImpl].
 */
open class GtkProgressBarNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkProgressBar")
) : DesktopProgressBarNativeImpl<GtkWidgetHandle>(nativeWidget) {

    init {
        syncAppearance()
    }

    protected open fun syncAppearance() {
        GtkControlRenderUtil.syncStandardControlAppearance(this, "gtk_render_background+gtk_render_frame[progressbar]")
    }

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        GtkControlRenderUtil.renderProgressBar(this, renderContext, bounds)

    override fun setProgress(progress: Float) {
        super.setProgress(progress)
        syncAppearance()
    }

    override fun setIndeterminate(indeterminate: Boolean) {
        super.setIndeterminate(indeterminate)
        syncAppearance()
    }

    override fun setLabel(label: String?) {
        super.setLabel(label)
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
