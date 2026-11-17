package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopDividerNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32ContainerChromeRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for horizontal and vertical `Divider` separators.
 */
class WindowsDividerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("WC_STATICW_ETCHEDHORZ")
) : DesktopDividerNativeImpl<Win32HwndHandle>(widget), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32ContainerChromeRenderUtil.renderDivider(
        ctx = ctx,
        widget = nativeWidget,
        dividerImpl = this,
        left = left,
        top = top,
        right = right
    )
}
