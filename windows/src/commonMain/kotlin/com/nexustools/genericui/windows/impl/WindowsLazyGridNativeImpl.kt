package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.lazy.DesktopLazyGridNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32DataDisplayRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for virtualized `LazyGrid` (`SysListView32_TileGrid`), supporting adaptive `minColumnWidthPx`.
 */
class WindowsLazyGridNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("SysListView32_TileGrid")
) : DesktopLazyGridNativeImpl<Win32HwndHandle>(widget), WindowsRenderableNativeImpl {

    var minColumnWidthPx: Int = 0
        private set

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun setMinColumnWidthPx(minWidthPx: Int) {
        minColumnWidthPx = minWidthPx.coerceAtLeast(0)
        nativeWidget.attributes["minColumnWidthPx"] = minColumnWidthPx
    }

    fun getMinColumnWidthPx(): Int = minColumnWidthPx

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32DataDisplayRenderUtil.renderLazyGrid(
        ctx = ctx,
        widget = nativeWidget,
        lazyGridImpl = this,
        left = left,
        top = top,
        right = right
    )
}
