package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32RenderContext
import com.nexustools.genericui.windows.render.Win32ToggleSelectionRenderUtil

/**
 * Win32 native implementation for `Select` / `ComboBox` selection controls.
 */
class WindowsSelectNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("WC_COMBOBOXW")
) : DesktopSelectNativeImpl<Win32HwndHandle>(widget), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32ToggleSelectionRenderUtil.renderSelectOrComboBox(
        ctx = ctx,
        widget = nativeWidget,
        selectImpl = this,
        left = left,
        top = top,
        right = right,
        height = fixedHeight ?: 28
    )
}
