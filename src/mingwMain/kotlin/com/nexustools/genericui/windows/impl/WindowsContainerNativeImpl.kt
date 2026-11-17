package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32ContainerChromeRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for standard `Container`, `Card`, `Alert`, `VStack`, and `HStack` layouts.
 */
class WindowsContainerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIChildContainerClass"),
    classPrefix: String = "container"
) : DesktopContainerNativeImpl<Win32HwndHandle>(widget, classPrefix), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32ContainerChromeRenderUtil.render(
        ctx = ctx,
        widget = nativeWidget,
        left = left,
        top = top,
        right = right
    )
}
