package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopTableNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32DataDisplayRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for `Table` / `DataGrid` (`SysListView32`), supporting proportional column weights.
 */
class WindowsTableNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("SysListView32")
) : DesktopTableNativeImpl<Win32HwndHandle>(widget), WindowsRenderableNativeImpl {

    private var columnWeights: FloatArray = FloatArray(0)

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun setColumnWeights(weights: FloatArray) {
        columnWeights = weights.copyOf()
        nativeWidget.attributes["columnWeights"] = columnWeights
    }

    fun getColumnWeights(): FloatArray = columnWeights.copyOf()

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32DataDisplayRenderUtil.renderTableOrDataGrid(
        ctx = ctx,
        widget = nativeWidget,
        tableImpl = this,
        left = left,
        top = top,
        right = right
    )
}
