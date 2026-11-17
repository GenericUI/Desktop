package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopStyledButtonNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32ButtonRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for custom-skinned `StyledButton` controls.
 */
class WindowsStyledButtonNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIStyledButtonClass"),
    classPrefix: String = "styled-btn"
) : DesktopStyledButtonNativeImpl<Win32HwndHandle>(widget, classPrefix), WindowsRenderableNativeImpl {

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
