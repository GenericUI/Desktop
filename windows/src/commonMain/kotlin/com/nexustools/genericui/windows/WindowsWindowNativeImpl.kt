package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.window.TitleBar
import com.nexustools.genericui.window.TitleCapabilities

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
    supportsMultiWindow = true,
    defaultWindowIconPath = "icon.ico"
) {
    val titleBarKind: Win32TitleBarKind
        get() = if (isToolModalWindow) Win32TitleBarKind.TOOL_WINDOW else Win32TitleBarKind.STANDARD_WINDOW

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
        if (!minimizable || isToolModalWindow) {
            nativeWidget.dwStyle = nativeWidget.dwStyle and
                (Win32HwndHandle.WS_MINIMIZEBOX or Win32HwndHandle.WS_MAXIMIZEBOX).inv()
        }
        nativeWidget.attributes["win32ToolWindow"] = (!showInTaskbar).toString()
        nativeWidget.attributes["win32TitleBarKind"] = titleBarKind.name
        nativeWidget.attributes["win32OwnedByHwnd"] = (ownerWindow?.nativeWidget as? Win32HwndHandle)?.nativePointer
    }

    override fun applyTitleBarInstructions(titleBar: TitleBar?) {
        super.applyTitleBarInstructions(titleBar)
        val hasCustomOrEmbedded = titleBar != null && (
            titleBar.customTitleBar ||
                titleBar.leadingComponents.isNotEmpty() ||
                titleBar.centerComponents.isNotEmpty() ||
                titleBar.trailingComponents.isNotEmpty() ||
                isToolModalWindow
            )
        nativeWidget.attributes["DwmExtendFrameIntoClientArea"] = hasCustomOrEmbedded
        nativeWidget.attributes["WM_NCHITTEST_CustomCaption"] = hasCustomOrEmbedded
        nativeWidget.attributes["win32TitleBarKind"] = titleBarKind.name
        val allowMinimize = if (isToolModalWindow) false else (titleBar?.showMinimizeButton ?: true)
        val allowMaximize = if (isToolModalWindow) false else (titleBar?.showMaximizeButton ?: true)
        if (!allowMinimize) {
            nativeWidget.dwStyle = nativeWidget.dwStyle and Win32HwndHandle.WS_MINIMIZEBOX.inv()
        } else {
            nativeWidget.dwStyle = nativeWidget.dwStyle or Win32HwndHandle.WS_MINIMIZEBOX
        }
        if (!allowMaximize) {
            nativeWidget.dwStyle = nativeWidget.dwStyle and Win32HwndHandle.WS_MAXIMIZEBOX.inv()
        } else {
            nativeWidget.dwStyle = nativeWidget.dwStyle or Win32HwndHandle.WS_MAXIMIZEBOX
        }
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
            Win32NativeWindowHost.onMainWindowTitleBarChanged(this)
        }
    }

    override fun setTitle(title: String) {
        super.setTitle(title)
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
            Win32NativeWindowHost.updateMainWindowTitle(this, title)
        }
    }

    override fun getTitleCapabilities(): TitleCapabilities = TitleCapabilities(
        isSupported = true,
        isEmulated = false,
        supportsCustomTitleBar = true,
        supportsCustomComponents = true,
        supportsLeadingComponents = true,
        supportsCenterComponents = true,
        supportsTrailingComponents = true,
        supportsEmbeddedMenuBar = true,
        supportsNavbarMenuBar = false,
        supportsWindowIcon = true,
        supportsWindowTitle = true,
        supportsMinimizeButton = true,
        supportsMaximizeButton = true,
        supportsCloseButton = true,
        supportsCollapseToggle = false,
        supportsControlsPlacement = true,
        supportsCustomHeight = true,
        supportsSystemAeroOrBackdrop = true,
        isToolWindowTitleBar = false,
        isTitleBarRequired = true,
        titleBarRendersAsNavbar = false,
        menuBarRendersAsNavbar = false,
        menuBarMergesIntoTitleNavbar = false
    )

    override fun showWindow() {
        super.showWindow()
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
            Win32NativeWindowHost.createAndShowWin32Window(this)
        }
    }
}
