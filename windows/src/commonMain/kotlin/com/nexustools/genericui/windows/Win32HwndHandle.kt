package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.core.DesktopNativeWidget

/**
 * Win32 native window handle (`HWND`) descriptor wrapping a Win32 class name (`WC_BUTTONW`, `WC_EDITW`,
 * `WC_TABCONTROLW`, `GenericUIWindowClass`, etc.), window style (`WS_*`), and extended style (`WS_EX_*`).
 */
class Win32HwndHandle(
    win32ClassName: String,
    var dwStyle: Long = WS_CHILD or WS_VISIBLE,
    var dwExStyle: Long = 0L,
    hwndPointer: Long = nextPointerId()
) : DesktopNativeWidget(
    backendToolkit = "Win32",
    nativeClassName = win32ClassName,
    nativePointer = hwndPointer
) {
    companion object {
        const val WS_OVERLAPPEDWINDOW: Long = 0x00CF0000L
        const val WS_POPUP: Long = 0x80000000L
        const val WS_CHILD: Long = 0x40000000L
        const val WS_VISIBLE: Long = 0x10000000L
        const val WS_CAPTION: Long = 0x00C00000L
        const val WS_SYSMENU: Long = 0x00080000L
        const val WS_THICKFRAME: Long = 0x00040000L
        const val WS_MINIMIZEBOX: Long = 0x00020000L
        const val WS_MAXIMIZEBOX: Long = 0x00010000L
        const val WS_EX_TOOLWINDOW: Long = 0x00000080L
        const val WS_EX_TOPMOST: Long = 0x00000008L
        const val WS_EX_NOREDIRECTIONBITMAP: Long = 0x00200000L
    }
}
