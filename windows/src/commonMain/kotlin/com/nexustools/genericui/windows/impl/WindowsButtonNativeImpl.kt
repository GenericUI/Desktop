package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32ButtonRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for standard `Button` controls.
 */
class WindowsButtonNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("WC_BUTTONW"),
    classPrefix: String = "btn"
) : DesktopButtonNativeImpl<Win32HwndHandle>(widget, classPrefix), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32ButtonRenderUtil.render(
        ctx = ctx,
        widget = nativeWidget,
        left = left,
        top = top,
        right = right,
        fixedHeight = fixedHeight
    )
}
