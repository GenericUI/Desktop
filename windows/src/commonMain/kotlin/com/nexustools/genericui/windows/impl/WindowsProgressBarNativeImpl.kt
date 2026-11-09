package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopProgressBarNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32RenderContext
import com.nexustools.genericui.windows.render.Win32SliderProgressRenderUtil

/**
 * Win32 native implementation for `ProgressBar` and `Meter` controls.
 */
class WindowsProgressBarNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("msctls_progress32")
) : DesktopProgressBarNativeImpl<Win32HwndHandle>(widget), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32SliderProgressRenderUtil.renderProgressBar(
        ctx = ctx,
        widget = nativeWidget,
        progressImpl = this,
        left = left,
        top = top,
        right = right,
        height = fixedHeight ?: 14
    )
}
