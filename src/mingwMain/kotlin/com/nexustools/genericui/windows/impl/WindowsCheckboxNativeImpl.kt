package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32RenderContext
import com.nexustools.genericui.windows.render.Win32ToggleSelectionRenderUtil

/**
 * Win32 native implementation for `Checkbox`, `Switch`, and `RadioButton` controls.
 */
class WindowsCheckboxNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("WC_BUTTONW_AUTOCHECKBOX"),
    switchStyle: Boolean = false,
    radioStyle: Boolean = false
) : DesktopCheckboxNativeImpl<Win32HwndHandle>(widget, switchStyle, radioStyle), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32ToggleSelectionRenderUtil.renderCheckRadioOrSwitch(
        ctx = ctx,
        widget = nativeWidget,
        checkboxImpl = this,
        left = left,
        top = top,
        right = right,
        height = fixedHeight ?: 22
    )
}
