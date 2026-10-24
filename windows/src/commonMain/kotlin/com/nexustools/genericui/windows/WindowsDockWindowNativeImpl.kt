package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.dock.DesktopDockWindowNativeImpl

/**
 * Win32 floating utility dock window (`WS_EX_TOOLWINDOW` `HWND`) implementation of
 * [com.nexustools.genericui.nativeimpl.dock.DockWindowNativeImpl].
 */
class WindowsDockWindowNativeImpl : DesktopDockWindowNativeImpl<Win32HwndHandle>(
    nativeWidget = Win32HwndHandle(
        win32ClassName = "GenericUIDockToolWindowW",
        dwStyle = Win32HwndHandle.WS_POPUP or Win32HwndHandle.WS_THICKFRAME or Win32HwndHandle.WS_VISIBLE,
        dwExStyle = Win32HwndHandle.WS_EX_TOOLWINDOW
    )
)
