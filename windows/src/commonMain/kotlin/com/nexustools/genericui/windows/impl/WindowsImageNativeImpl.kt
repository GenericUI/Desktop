package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32DataDisplayRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for `Image`, `Icon`, and `Avatar` (`WC_STATICW_BITMAP`).
 */
class WindowsImageNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("WC_STATICW_BITMAP")
) : DesktopImageNativeImpl<Win32HwndHandle>(widget), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32DataDisplayRenderUtil.renderImageOrAvatar(
        ctx = ctx,
        widget = nativeWidget,
        imageImpl = this,
        left = left,
        top = top,
        right = right
    )
}
