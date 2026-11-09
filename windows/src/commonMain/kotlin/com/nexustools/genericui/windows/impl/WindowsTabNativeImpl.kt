package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopTabNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32NavigationRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for individual `Tab` items.
 */
class WindowsTabNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUITabItemClass"),
    closable: Boolean = false
) : DesktopTabNativeImpl<Win32HwndHandle>(widget, closable), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32NavigationRenderUtil.renderStandaloneTab(
        ctx = ctx,
        widget = nativeWidget,
        tabImpl = this,
        left = left,
        top = top,
        right = right,
        height = fixedHeight ?: 32
    )
}
