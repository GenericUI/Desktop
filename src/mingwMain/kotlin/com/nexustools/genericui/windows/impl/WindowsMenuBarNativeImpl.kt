package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.shell.DesktopMenuBarNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32NavigationRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for `MenuBar` (`HMENU_BAR`).
 */
class WindowsMenuBarNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("HMENU_BAR")
) : DesktopMenuBarNativeImpl<Win32HwndHandle>(widget), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32NavigationRenderUtil.renderMenuOrToolBar(
        ctx = ctx,
        widget = nativeWidget,
        navbarImpl = null,
        left = left,
        top = top,
        right = right,
        height = fixedHeight ?: 32
    )
}
