package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
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

    override fun configureAsToolModalWindow(
        ownerWindow: WindowNativeImpl<*>?,
        showInTaskbar: Boolean,
        minimizable: Boolean,
        alwaysOnTopOfOwner: Boolean
    ) {
        super.configureAsToolModalWindow(ownerWindow, showInTaskbar, minimizable, alwaysOnTopOfOwner)
        if (!showInTaskbar) {
            nativeWidget.dwExStyle = nativeWidget.dwExStyle or Win32HwndHandle.WS_EX_TOOLWINDOW
        } else {
            nativeWidget.dwExStyle = nativeWidget.dwExStyle and Win32HwndHandle.WS_EX_TOOLWINDOW.inv()
        }
        if (!minimizable) {
            nativeWidget.dwStyle = nativeWidget.dwStyle and Win32HwndHandle.WS_MINIMIZEBOX.inv()
        }
        nativeWidget.attributes["win32ToolWindow"] = (!showInTaskbar).toString()
        nativeWidget.attributes["win32OwnedByHwnd"] = (ownerWindow?.nativeWidget as? Win32HwndHandle)?.nativePointer
    }

    override fun applyTitleBarInstructions(titleBar: TitleBar?) {
        super.applyTitleBarInstructions(titleBar)
        val custom = titleBar?.customTitleBar == true
        nativeWidget.attributes["DwmExtendFrameIntoClientArea"] = custom
        nativeWidget.attributes["WM_NCHITTEST_CustomCaption"] = custom
        val allowMinimize = if (isToolModalWindow) minimizable else (titleBar?.showMinimizeButton ?: true)
        if (!allowMinimize) {
            nativeWidget.dwStyle = nativeWidget.dwStyle and Win32HwndHandle.WS_MINIMIZEBOX.inv()
        } else {
            nativeWidget.dwStyle = nativeWidget.dwStyle or Win32HwndHandle.WS_MINIMIZEBOX
        }
    }

    override fun setTitle(title: String) {
        super.setTitle(title)
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
            Win32NativeWindowHost.updateMainWindowTitle(this, title)
        }
    }

    override fun showWindow() {
        super.showWindow()
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
            Win32NativeWindowHost.createAndShowWin32Window(this)
        }
    }
}
