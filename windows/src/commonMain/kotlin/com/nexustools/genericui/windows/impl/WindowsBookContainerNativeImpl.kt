package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopBookContainerNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32RenderContext
import com.nexustools.genericui.windows.render.Win32WorkspaceWindowRenderUtil

/**
 * Win32 native implementation for printable paginated `BookContainer` documents.
 */
class WindowsBookContainerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIBookContainerClass")
) : DesktopBookContainerNativeImpl<Win32HwndHandle>(widget), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32WorkspaceWindowRenderUtil.renderBookContainer(
        ctx = ctx,
        widget = nativeWidget,
        bookImpl = this,
        left = left,
        top = top,
        right = right
    )
}
