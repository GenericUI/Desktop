package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.dock.DesktopDockWindowNativeImpl
import com.nexustools.genericui.window.TitleBar

/**
 * Win32 floating utility dock window (`WS_EX_TOOLWINDOW` `HWND`) implementation of
 * [com.nexustools.genericui.nativeimpl.dock.DockWindowNativeImpl].
 *
 * Uses [Win32TitleBarKind.TOOL_WINDOW] (`SM_CYSMCAPTION`, `WP_SMALLCAPTION`, `WP_SMALLCLOSEBUTTON`,
 * compact tool caption height, and no Minimize/Maximize buttons) while supporting embedded
 * [TitleBar] components (`leadingComponents`, `centerComponents`, `trailingComponents`) via
 * [Win32NativeTitleBarRenderer.renderEmbeddedTitleBarComponents].
 */
class WindowsDockWindowNativeImpl : DesktopDockWindowNativeImpl<Win32HwndHandle>(
    nativeWidget = Win32HwndHandle(
        win32ClassName = "GenericUIDockToolWindowW",
        dwStyle = Win32HwndHandle.WS_POPUP or Win32HwndHandle.WS_THICKFRAME or Win32HwndHandle.WS_VISIBLE,
        dwExStyle = Win32HwndHandle.WS_EX_TOOLWINDOW
    )
) {
    val titleBarKind: Win32TitleBarKind = Win32TitleBarKind.TOOL_WINDOW

    private var titleBarInstructions: TitleBar? = TitleBar(
        customTitleBar = true,
        showWindowIcon = false,
        showMinimizeButton = false,
        showMaximizeButton = false,
        showCloseButton = true
    )

    init {
        nativeWidget.attributes["win32ToolWindow"] = "true"
        nativeWidget.attributes["win32TitleBarKind"] = titleBarKind.name
    }

    fun getTitleBarInstructions(): TitleBar? = titleBarInstructions

    fun applyTitleBarInstructions(titleBar: TitleBar?) {
        val resolved = (titleBar ?: TitleBar(
            customTitleBar = true,
            showWindowIcon = false,
            showMinimizeButton = false,
            showMaximizeButton = false,
            showCloseButton = true
        )).apply {
            showWindowIcon = false
            showMinimizeButton = false
            showMaximizeButton = false
        }
        titleBarInstructions = resolved
        nativeWidget.attributes["titleBar"] = resolved
        nativeWidget.attributes["win32TitleBarKind"] = titleBarKind.name
    }
}
