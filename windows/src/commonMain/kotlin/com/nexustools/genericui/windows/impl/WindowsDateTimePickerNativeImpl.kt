package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32InputFieldRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for `DatePicker` and `TimePicker` controls.
 */
class WindowsDateTimePickerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("SysDateTimePick32")
) : DesktopTextInputNativeImpl<Win32HwndHandle>(widget, multiline = false), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
        nativeWidget.attributes["inputType"] = "date"
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32InputFieldRenderUtil.renderDateTimePicker(
        ctx = ctx,
        widget = nativeWidget,
        inputImpl = this,
        left = left,
        top = top,
        right = right,
        height = fixedHeight ?: 30
    )
}
