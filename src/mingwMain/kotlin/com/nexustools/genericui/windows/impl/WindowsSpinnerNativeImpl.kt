package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopSpinnerNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32RenderContext
import com.nexustools.genericui.windows.render.Win32SliderProgressRenderUtil

/**
 * Win32 native implementation for `Spinner` loading indicator controls.
 */
class WindowsSpinnerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUISpinnerClass")
) : DesktopSpinnerNativeImpl<Win32HwndHandle>(widget), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32SliderProgressRenderUtil.renderSpinner(
        ctx = ctx,
        widget = nativeWidget,
        spinnerImpl = this,
        left = left,
        top = top,
        right = right,
        height = fixedHeight ?: 26
    )
}
