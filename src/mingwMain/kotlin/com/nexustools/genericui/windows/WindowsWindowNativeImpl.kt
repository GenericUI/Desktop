package com.nexustools.genericui.windows

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.enums.window.DisplayCutoutMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.window.TitleBar
import com.nexustools.genericui.window.TitleCapabilities
import com.nexustools.genericui.window.WindowAspectRatioHelper
import com.nexustools.genericui.window.WindowConfiguration

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
    private var suppressHostSync: Boolean = false

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

    override fun setAlwaysOnTop(alwaysOnTop: Boolean) {
        super.setAlwaysOnTop(alwaysOnTop)
        if (alwaysOnTop) {
            nativeWidget.dwExStyle = nativeWidget.dwExStyle or Win32HwndHandle.WS_EX_TOPMOST
        } else {
            nativeWidget.dwExStyle = nativeWidget.dwExStyle and Win32HwndHandle.WS_EX_TOPMOST.inv()
        }
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || Win32NativeWindowHost.activeWindowImpl === this) {
            Win32NativeWindowHost.setWindowAlwaysOnTop(this, alwaysOnTop)
        }
    }

    override fun setAspectRatio(aspectRatio: Double?) {
        super.setAspectRatio(aspectRatio)
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || Win32NativeWindowHost.activeWindowImpl === this) {
            Win32NativeWindowHost.setWindowAspectRatio(this, aspectRatio)
        }
    }

    override fun setWindowSize(size: Size) {
        super.setWindowSize(size)
        if (!suppressHostSync && (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || Win32NativeWindowHost.activeWindowImpl === this)) {
            Win32NativeWindowHost.setWindowClientSize(this, getWindowSize())
        }
    }

    override fun setWindowState(state: WindowState) {
        super.setWindowState(state)
        if (!suppressHostSync && (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || Win32NativeWindowHost.activeWindowImpl === this)) {
            Win32NativeWindowHost.setWindowState(this, state)
        }
    }

    internal fun syncHostWindowStateAndSize(
        widthPx: Int,
        heightPx: Int,
        fullscreenNow: Boolean,
        zoomedNow: Boolean,
        iconicNow: Boolean
    ) {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val incoming = Size(w, h)
        suppressHostSync = true
        try {
            fullscreenFlag = fullscreenNow
            currentWindowState = when {
                fullscreenNow -> WindowState.FULLSCREEN
                iconicNow -> WindowState.MINIMIZED
                zoomedNow -> WindowState.MAXIMIZED
                currentWindowState == WindowState.FULLSCREEN ||
                    currentWindowState == WindowState.MAXIMIZED ||
                    currentWindowState == WindowState.MINIMIZED -> WindowState.NORMAL
                else -> currentWindowState
            }
            nativeWidget.attributes["fullscreen"] = fullscreenNow
            nativeWidget.attributes["windowState"] = currentWindowState
            val ratio = aspectRatioValue
            if (
                currentWindowState == WindowState.NORMAL &&
                !fullscreenFlag &&
                ratio != null &&
                WindowAspectRatioHelper.matchesAspectRatio(incoming, ratio, tolerancePx = 2)
            ) {
                val changed = currentWindowSize != incoming ||
                    nativeWidget.bounds.width != w ||
                    nativeWidget.bounds.height != h
                currentWindowSize = incoming
                nativeWidget.bounds = Rect(nativeWidget.bounds.x, nativeWidget.bounds.y, w, h)
                if (changed) {
                    onWindowResizeListener?.invoke(
                        WindowConfiguration(
                            widthPx = w,
                            heightPx = h,
                            windowState = currentWindowState,
                            isFullscreen = fullscreenFlag,
                            isInPictureInPicture = inPipMode
                        )
                    )
                }
            } else {
                super.setWindowSize(incoming)
            }
        } finally {
            suppressHostSync = false
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

    override fun setFullscreen(fullscreen: Boolean, cutoutMode: DisplayCutoutMode) {
        super.setFullscreen(fullscreen, cutoutMode)
        val targetWidget = if (fullscreen) getFullscreenElement()?.nativeWidget as? DesktopNativeWidget else null
        if (fullscreen && targetWidget != null) {
            nativeWidget.attributes["fullscreenElement"] = targetWidget
        } else {
            nativeWidget.attributes.remove("fullscreenElement")
        }
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || Win32NativeWindowHost.activeWindowImpl === this) {
            Win32NativeWindowHost.setWindowFullscreen(
                windowImpl = this,
                fullscreen = fullscreen,
                targetElementWidget = targetWidget
            )
        }
    }

    override fun setFullscreenElement(elementImpl: NativeImpl<*>?, cutoutMode: DisplayCutoutMode) {
        super.setFullscreenElement(elementImpl, cutoutMode)
    }

    override fun setVisible(visible: Boolean) {
        super.setVisible(visible)
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || Win32NativeWindowHost.activeWindowImpl === this) {
            Win32NativeWindowHost.setWindowVisible(this, visible)
        }
    }

    override fun showWindow() {
        val visible = isVisible()
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
            Win32NativeWindowHost.createAndShowWin32Window(this, startHidden = !visible)
        }
        if (visible) {
            super.showWindow()
        }
    }
}
