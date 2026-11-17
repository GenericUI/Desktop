package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopBottomNavigationBarNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32NavigationRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for `BottomNavigationBar`.
 */
class WindowsBottomNavigationBarNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("ToolbarWindow32_BottomNav")
) : DesktopBottomNavigationBarNativeImpl<Win32HwndHandle>(widget), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32NavigationRenderUtil.renderBottomNavigationBar(
        ctx = ctx,
        widget = nativeWidget,
        bottomNavImpl = this,
        left = left,
        top = top,
        right = right,
        height = fixedHeight ?: 48
    )
}
