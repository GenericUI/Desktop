package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32InputFieldRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for `ColorPicker` controls.
 */
class WindowsColorPickerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIColorPickerClass")
) : DesktopTextInputNativeImpl<Win32HwndHandle>(widget, multiline = false), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
        nativeWidget.attributes["inputType"] = "color"
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32InputFieldRenderUtil.renderColorPicker(
        ctx = ctx,
        widget = nativeWidget,
        inputImpl = this,
        left = left,
        top = top,
        right = right,
        height = fixedHeight ?: 30
    )
}
