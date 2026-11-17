package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.lazy.DesktopLazyGridNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.render.GtkControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * GTK4 `GtkGridView` implementation of [com.nexustools.genericui.nativeimpl.lazy.LazyGridNativeImpl],
 * supporting adaptive column counts via [minColumnWidthPx] and [setMinColumnWidthPx].
 */
open class GtkLazyGridNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkGridView")
) : DesktopLazyGridNativeImpl<GtkWidgetHandle>(nativeWidget) {

    var minColumnWidthPx: Int = 0
        private set

    init {
        syncAppearance()
    }

    protected open fun syncAppearance() {
        GtkControlRenderUtil.syncStandardControlAppearance(this, "GtkGridView")
    }

    override fun setMinColumnWidthPx(minWidthPx: Int) {
        minColumnWidthPx = minWidthPx.coerceAtLeast(0)
        nativeWidget.attributes["minColumnWidthPx"] = minColumnWidthPx
        val availW = nativeWidget.bounds.width.coerceAtLeast(minColumnWidthPx.coerceAtLeast(1))
        nativeWidget.attributes["effectiveColumns"] = resolveEffectiveColumns(availW)
        notifyTreeInvalidated()
    }

    fun getMinColumnWidthPx(): Int = minColumnWidthPx

    fun resolveEffectiveColumns(availableWidthPx: Int): Int =
        GtkControlRenderUtil.resolveAdaptiveGridColumns(
            configuredColumns = getColumnCount(),
            minColumnWidthPx = minColumnWidthPx,
            availableWidthPx = availableWidthPx
        )

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        GtkControlRenderUtil.renderLazyGrid(this, minColumnWidthPx, renderContext, bounds)

    override fun setColumns(columns: Int) {
        super.setColumns(columns)
        val availW = nativeWidget.bounds.width.coerceAtLeast(minColumnWidthPx.coerceAtLeast(1))
        nativeWidget.attributes["effectiveColumns"] = resolveEffectiveColumns(availW)
        syncAppearance()
    }

    override fun setType(type: BootstrapType?) {
        super.setType(type)
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
