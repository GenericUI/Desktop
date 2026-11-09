package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32NavigationRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for `TabContainer` (`SysTabControl32`).
 */
class WindowsTabContainerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("SysTabControl32")
) : DesktopTabContainerNativeImpl<Win32HwndHandle>(widget), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32NavigationRenderUtil.renderTabContainer(
        ctx = ctx,
        widget = nativeWidget,
        tabContainerImpl = this,
        left = left,
        top = top,
        right = right
    )
}
