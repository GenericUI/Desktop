package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopStyledContainerNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32ContainerChromeRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for custom-skinned `StyledContainer` surfaces.
 */
class WindowsStyledContainerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIStyledContainerClass"),
    classPrefix: String = "styled-container",
    tagName: String = "genericui:styled-container"
) : DesktopStyledContainerNativeImpl<Win32HwndHandle>(widget, classPrefix), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
        nativeWidget.attributes["tagName"] = tagName
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32ContainerChromeRenderUtil.renderContainerOrCard(
        ctx = ctx,
        widget = nativeWidget,
        left = left,
        top = top,
        right = right
    )
}
