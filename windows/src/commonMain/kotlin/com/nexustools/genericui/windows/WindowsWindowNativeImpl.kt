package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.window.TitleBar

/**
 * Win32 top-level `HWND` implementation of [com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl].
 *
 * Supports custom borderless title bars via `DwmExtendFrameIntoClientArea` and `WM_NCHITTEST`
 * (`HTCAPTION`, `HTMINBUTTON`, `HTMAXBUTTON`, `HTCLOSE`) as well as embedding the `MenuBar` directly
 * inside the custom title bar (`supportsCustomTitleBar = true`, `supportsEmbeddedMenuBarInTitleBar = true`).
 */
class WindowsWindowNativeImpl : DesktopWindowNativeImpl<Win32HwndHandle>(
    nativeWidget = Win32HwndHandle(
        win32ClassName = "GenericUITopLevelWindowW",
        dwStyle = Win32HwndHandle.WS_OVERLAPPEDWINDOW or Win32HwndHandle.WS_VISIBLE
    ),
    supportsCustomTitleBar = true,
    supportsEmbeddedMenuBarInTitleBar = true,
    supportsMultiWindow = true
) {
    fun attachedMenuBarWidget(): DesktopNativeWidget? =
        attachedMenuBar?.nativeWidget as? DesktopNativeWidget

    override fun applyTitleBarInstructions(titleBar: TitleBar?) {
        super.applyTitleBarInstructions(titleBar)
        val custom = titleBar?.customTitleBar == true
        nativeWidget.attributes["DwmExtendFrameIntoClientArea"] = custom
        nativeWidget.attributes["WM_NCHITTEST_CustomCaption"] = custom
    }

    override fun showWindow() {
        super.showWindow()
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
            Win32NativeWindowHost.createAndShowWin32Window(this)
        }
    }
}
