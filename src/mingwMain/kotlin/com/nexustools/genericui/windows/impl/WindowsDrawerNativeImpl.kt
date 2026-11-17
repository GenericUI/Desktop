package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32NavigationRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for `Drawer`, `SideNav`, and `NavigationRail` panels.
 */
class WindowsDrawerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIDrawerClass"),
    classPrefix: String = "offcanvas"
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
    ): Int = Win32NavigationRenderUtil.renderNavigationRailOrDrawer(
        ctx = ctx,
        widget = nativeWidget,
        left = left,
        top = top,
        right = right
    )
}
