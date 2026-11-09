package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.dock.DesktopDockWorkspaceNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32RenderContext
import com.nexustools.genericui.windows.render.Win32WorkspaceWindowRenderUtil

/**
 * Win32 native implementation for `DockWorkspace`.
 */
class WindowsDockWorkspaceNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIDockWorkspaceClass")
) : DesktopDockWorkspaceNativeImpl<Win32HwndHandle>(widget), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32WorkspaceWindowRenderUtil.renderDockWorkspace(
        ctx = ctx,
        widget = nativeWidget,
        dockImpl = this,
        left = left,
        top = top,
        right = right
    )
}
