package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.lazy.DesktopTreeViewNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32DataDisplayRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for `TreeView` (`SysTreeView32`).
 */
class WindowsTreeViewNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("SysTreeView32")
) : DesktopTreeViewNativeImpl<Win32HwndHandle>(widget), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32DataDisplayRenderUtil.renderTreeView(
        ctx = ctx,
        widget = nativeWidget,
        treeImpl = this,
        left = left,
        top = top,
        right = right
    )
}
