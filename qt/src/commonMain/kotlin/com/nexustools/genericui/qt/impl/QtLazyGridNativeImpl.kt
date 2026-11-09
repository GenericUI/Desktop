package com.nexustools.genericui.qt.impl

import com.nexustools.genericui.desktop.lazy.DesktopLazyGridNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.qt.QtWidgetHandle
import com.nexustools.genericui.qt.render.QtControlRenderUtil
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle

/**
 * Qt6 `QListView` (IconMode virtualized) implementation of
 * [com.nexustools.genericui.nativeimpl.lazy.LazyGridNativeImpl], supporting adaptive column counts
 * via [minColumnWidthPx] and [setMinColumnWidthPx].
 */
open class QtLazyGridNativeImpl(
    nativeWidget: QtWidgetHandle = QtWidgetHandle("QListView_IconMode_Virtualized")
) : DesktopLazyGridNativeImpl<QtWidgetHandle>(nativeWidget) {

    var minColumnWidthPx: Int = 0
        private set

    init {
        syncAppearance()
    }

    protected open fun syncAppearance() {
        QtControlRenderUtil.syncStandardControlAppearance(this, "QStyle::CE_ItemViewItem[QListView_IconMode]")
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
        QtControlRenderUtil.resolveAdaptiveGridColumns(
            configuredColumns = getColumnCount(),
            minColumnWidthPx = minColumnWidthPx,
            availableWidthPx = availableWidthPx
        )

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean =
        QtControlRenderUtil.renderLazyGrid(this, minColumnWidthPx, renderContext, bounds)

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
