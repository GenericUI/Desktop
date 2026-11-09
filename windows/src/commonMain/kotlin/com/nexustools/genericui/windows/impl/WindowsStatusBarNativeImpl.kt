package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32NavigationRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for `StatusBar` (`msctls_statusbar32`).
 */
class WindowsStatusBarNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("msctls_statusbar32"),
    classPrefix: String = "status-bar"
) : DesktopContainerNativeImpl<Win32HwndHandle>(widget, classPrefix), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32NavigationRenderUtil.renderStatusBar(
        ctx = ctx,
        widget = nativeWidget,
        left = left,
        top = top,
        right = right,
        height = fixedHeight ?: 26
    )
}
