package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopRangeSliderNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32RenderContext
import com.nexustools.genericui.windows.render.Win32SliderProgressRenderUtil

/**
 * Win32 native implementation for `RangeSlider` trackbar controls.
 */
class WindowsRangeSliderNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("msctls_trackbar32")
) : DesktopRangeSliderNativeImpl<Win32HwndHandle>(widget), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32SliderProgressRenderUtil.renderRangeSlider(
        ctx = ctx,
        widget = nativeWidget,
        sliderImpl = this,
        left = left,
        top = top,
        right = right,
        height = fixedHeight ?: 20
    )
}
