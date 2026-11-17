package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopTableNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.render.GtkControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * GTK4 `GtkColumnView` implementation of [com.nexustools.genericui.nativeimpl.content.TableNativeImpl],
 * supporting proportional column weights via [setColumnWeights].
 */
open class GtkTableNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkColumnView")
) : DesktopTableNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private var columnWeightsList: List<Float> = emptyList()

    init {
        syncAppearance()
    }

    protected open fun syncAppearance() {
        GtkControlRenderUtil.syncStandardControlAppearance(this, "GtkColumnView")
    }

    override fun setColumnWeights(weights: FloatArray) {
        columnWeightsList = weights.map { it.coerceAtLeast(0.01f) }
        nativeWidget.attributes["columnWeights"] = columnWeightsList
        val colCount = maxOf(headersList.size, columnWeightsList.size, 1)
        val totalW = nativeWidget.bounds.width.coerceAtLeast(colCount * 40)
        nativeWidget.attributes["resolvedColumnWidthsPx"] =
            GtkControlRenderUtil.computeWeightedColumnWidths(totalW, colCount, columnWeightsList)
        notifyTreeInvalidated()
    }

    fun setColumnWeights(weights: List<Float>) {
        setColumnWeights(weights.toFloatArray())
    }

    fun getColumnWeights(): List<Float> = columnWeightsList

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        GtkControlRenderUtil.renderTable(this, columnWeightsList, renderContext, bounds)

    override fun setHeaders(headers: List<String>) {
        super.setHeaders(headers)
        if (columnWeightsList.isNotEmpty()) {
            val colCount = maxOf(headers.size, columnWeightsList.size, 1)
            val totalW = nativeWidget.bounds.width.coerceAtLeast(colCount * 40)
            nativeWidget.attributes["resolvedColumnWidthsPx"] =
                GtkControlRenderUtil.computeWeightedColumnWidths(totalW, colCount, columnWeightsList)
        }
        syncAppearance()
    }

    override fun setRows(rows: List<List<String>>) {
        super.setRows(rows)
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
