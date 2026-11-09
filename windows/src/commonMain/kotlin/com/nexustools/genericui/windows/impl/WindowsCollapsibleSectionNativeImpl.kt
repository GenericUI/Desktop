package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32ContainerChromeRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for `Collapse`, `Accordion`, and `Details` collapsible sections.
 */
class WindowsCollapsibleSectionNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUICollapsibleSectionClass"),
    classPrefix: String = "accordion"
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
    ): Int = Win32ContainerChromeRenderUtil.renderCollapsibleOrAccordion(
        ctx = ctx,
        widget = nativeWidget,
        left = left,
        top = top,
        right = right
    )
}
