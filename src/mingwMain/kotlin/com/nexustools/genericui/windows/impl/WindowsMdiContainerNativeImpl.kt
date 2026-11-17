package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32RenderContext
import com.nexustools.genericui.windows.render.Win32WorkspaceWindowRenderUtil

/**
 * Win32 native implementation for `MdiContainer` and `MdiWindow` (`MDICLIENT`).
 */
class WindowsMdiContainerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("MDICLIENT"),
    classPrefix: String = "mdi-container"
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
    ): Int = Win32WorkspaceWindowRenderUtil.renderMdiContainerOrWindow(
        ctx = ctx,
        widget = nativeWidget,
        left = left,
        top = top,
        right = right
    )
}
