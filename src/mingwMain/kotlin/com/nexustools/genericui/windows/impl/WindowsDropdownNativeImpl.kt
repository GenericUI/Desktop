package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopDropdownNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32RenderContext
import com.nexustools.genericui.windows.render.Win32ToggleSelectionRenderUtil

/**
 * Win32 native implementation for `Dropdown` action button menus.
 */
class WindowsDropdownNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("WC_COMBOBOXEXW")
) : DesktopDropdownNativeImpl<Win32HwndHandle>(widget), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32ToggleSelectionRenderUtil.renderDropdownButton(
        ctx = ctx,
        widget = nativeWidget,
        dropdownImpl = this,
        left = left,
        top = top,
        right = right,
        height = fixedHeight ?: 30
    )
}
