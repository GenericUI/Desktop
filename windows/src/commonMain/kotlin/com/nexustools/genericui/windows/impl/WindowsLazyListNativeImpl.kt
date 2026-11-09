package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.lazy.DesktopLazyListNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32DataDisplayRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for virtualized `LazyList` / `LazyColumn` (`SysListView32_OwnerData`).
 */
class WindowsLazyListNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("SysListView32_OwnerData")
) : DesktopLazyListNativeImpl<Win32HwndHandle>(widget), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32DataDisplayRenderUtil.renderLazyList(
        ctx = ctx,
        widget = nativeWidget,
        lazyListImpl = this,
        left = left,
        top = top,
        right = right
    )
}
