@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

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

    init {
        Win32WinMain.ensureWindowsControlsInitialized()
        layoutController = com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController(this).apply {
            onZOrderUpdated = { sorted ->
                Win32NativeWindowHost.resolveHwndForWindow(this@WindowsWindowNativeImpl)?.let { hostHwnd ->
                    Win32ControlMessageRouter.layoutChildrenBatch(hostHwnd, sorted)
                }
            }
        }
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        val hostHwnd = Win32NativeWindowHost.resolveHwndForWindow(this) ?: return
        val base = childImpl as? com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl<*> ?: return
        val handle = base.nativeWidget as? Win32HwndHandle ?: return
        Win32InteractiveSurfaceHwnd.ensureSurfaceChildHwnd(handle, base)
        Win32ControlMessageRouter.attachChildHwnd(handle, hostHwnd)
        Win32NativeWindowHost.layoutAndSyncWindowChildren(hostHwnd)
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        super.onChildRemovedNative(childImpl)
        val handle = childImpl.nativeWidget as? Win32HwndHandle
        if (handle != null) {
            Win32ControlMessageRouter.detachChildHwnd(handle)
        }
        Win32NativeWindowHost.resolveHwndForWindow(this)?.let { hostHwnd ->
            Win32NativeWindowHost.layoutAndSyncWindowChildren(hostHwnd)
        }
    }

    override fun onChildrenClearedNative() {
        for (child in childImpls.toList()) {
            val handle = child.nativeWidget as? Win32HwndHandle ?: continue
            Win32ControlMessageRouter.detachChildHwnd(handle)
        }
        super.onChildrenClearedNative()
    }

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

    internal fun computeDesiredWindowStyle(
        undecorated: Boolean = isUndecorated(),
        preserveVisible: Boolean = (nativeWidget.dwStyle and Win32HwndHandle.WS_VISIBLE) != 0L
    ): Long {
        val resizable = (nativeWidget.attributes["resizable"] as? Boolean) != false
        val titleBar = getTitleBarInstructions()
        val allowMin = !isToolModalWindow && (titleBar?.showMinimizeButton ?: true)
        val allowMax = !isToolModalWindow && resizable && (titleBar?.showMaximizeButton ?: true)
        var style = if (undecorated) {
            var base = Win32HwndHandle.WS_POPUP or Win32HwndHandle.WS_SYSMENU
            if (resizable) base = base or Win32HwndHandle.WS_THICKFRAME
            if (allowMin) base = base or Win32HwndHandle.WS_MINIMIZEBOX
            if (allowMax) base = base or Win32HwndHandle.WS_MAXIMIZEBOX
            base
        } else {
            var base = Win32HwndHandle.WS_OVERLAPPEDWINDOW
            if (!resizable) base = base and Win32HwndHandle.WS_THICKFRAME.inv()
            if (!allowMin) base = base and Win32HwndHandle.WS_MINIMIZEBOX.inv()
            if (!allowMax) base = base and Win32HwndHandle.WS_MAXIMIZEBOX.inv()
            base
        }
        if (preserveVisible) {
            style = style or Win32HwndHandle.WS_VISIBLE
        }
        return style
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
        nativeWidget.dwStyle = computeDesiredWindowStyle(
            undecorated = isUndecorated(),
            preserveVisible = (nativeWidget.dwStyle and Win32HwndHandle.WS_VISIBLE) != 0L
        )
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

    override fun isUndecorated(): Boolean = super.isUndecorated()

    override fun setUndecorated(undecorated: Boolean) {
        super.setUndecorated(undecorated)
        nativeWidget.dwStyle = computeDesiredWindowStyle(
            undecorated = undecorated,
            preserveVisible = if (undecorated) {
                (nativeWidget.dwStyle and Win32HwndHandle.WS_VISIBLE) != 0L
            } else {
                true
            }
        )
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || Win32NativeWindowHost.hasWindow(this)) {
            Win32NativeWindowHost.syncWindowUndecorated(this, undecorated)
        }
    }

    override fun setOnWindowStateChanged(listener: ((WindowState) -> Unit)?) {
        super.setOnWindowStateChanged(listener)
    }

    override fun startSystemMove() {
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || Win32NativeWindowHost.hasWindow(this)) {
            Win32NativeWindowHost.startSystemMove(this)
        }
    }

    override fun setAlwaysOnTop(alwaysOnTop: Boolean) {
        super.setAlwaysOnTop(alwaysOnTop)
        if (alwaysOnTop) {
            nativeWidget.dwExStyle = nativeWidget.dwExStyle or Win32HwndHandle.WS_EX_TOPMOST
        } else {
            nativeWidget.dwExStyle = nativeWidget.dwExStyle and Win32HwndHandle.WS_EX_TOPMOST.inv()
        }
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || Win32NativeWindowHost.hasWindow(this)) {
            Win32NativeWindowHost.setWindowAlwaysOnTop(this, alwaysOnTop)
        }
    }

    override fun setAspectRatio(aspectRatio: Double?) {
        super.setAspectRatio(aspectRatio)
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || Win32NativeWindowHost.hasWindow(this)) {
            Win32NativeWindowHost.setWindowAspectRatio(this, aspectRatio)
        }
    }

    override fun setWindowSize(size: Size) {
        super.setWindowSize(size)
        if (!suppressHostSync && (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || Win32NativeWindowHost.hasWindow(this))) {
            Win32NativeWindowHost.setWindowClientSize(this, getWindowSize())
        }
    }

    override fun setWindowPosition(x: Int, y: Int) {
        super.setWindowPosition(x, y)
        if (!suppressHostSync && (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || Win32NativeWindowHost.hasWindow(this))) {
            Win32NativeWindowHost.setWindowPosition(this, x, y)
        }
    }

    internal fun notifyHostPositionChanged(x: Int, y: Int) {
        suppressHostSync = true
        try {
            updatePositionFromHost(x, y)
        } finally {
            suppressHostSync = false
        }
    }

    override fun setWindowState(state: WindowState) {
        super.setWindowState(state)
        if (!suppressHostSync && (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || Win32NativeWindowHost.hasWindow(this))) {
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
        if (iconicNow && !fullscreenNow) {
            val prevState = currentWindowState
            suppressHostSync = true
            try {
                fullscreenFlag = false
                currentWindowState = WindowState.MINIMIZED
                nativeWidget.attributes["fullscreen"] = false
                nativeWidget.attributes["windowState"] = WindowState.MINIMIZED
            } finally {
                suppressHostSync = false
            }
            if (prevState != currentWindowState) {
                onWindowStateChangedListener?.invoke(currentWindowState)
                onWindowResizeListener?.invoke(
                    WindowConfiguration(
                        widthPx = currentWindowSize.width,
                        heightPx = currentWindowSize.height,
                        windowState = currentWindowState,
                        isFullscreen = false
                    )
                )
            }
            return
        }
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val incoming = Size(w, h)
        suppressHostSync = true
        val prevState = currentWindowState
        try {
            fullscreenFlag = fullscreenNow
            currentWindowState = when {
                fullscreenNow -> WindowState.FULLSCREEN
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
                if (changed || prevState != currentWindowState) {
                    onWindowResizeListener?.invoke(
                        WindowConfiguration(
                            widthPx = w,
                            heightPx = h,
                            windowState = currentWindowState,
                            isFullscreen = fullscreenFlag
                        )
                    )
                }
            } else {
                super.setWindowSize(incoming)
            }
        } finally {
            suppressHostSync = false
        }
        if (prevState != currentWindowState) {
            onWindowStateChangedListener?.invoke(currentWindowState)
        }
    }

    internal fun notifyHostFocusChanged(focused: Boolean) {
        if (isFocusedState() == focused && nativeWidget.attributes.containsKey("focused")) return
        suppressHostSync = true
        try {
            dispatchFocusChanged(focused)
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
        menuBarMergesIntoTitleNavbar = false,
        prefersBlurredTranslucentOverlayTitleBar = false
    )

    override fun setFullscreen(fullscreen: Boolean, cutoutMode: DisplayCutoutMode) {
        super.setFullscreen(fullscreen, cutoutMode)
        val targetWidget = if (fullscreen) getFullscreenElement()?.nativeWidget as? DesktopNativeWidget else null
        if (fullscreen && targetWidget != null) {
            nativeWidget.attributes["fullscreenElement"] = targetWidget
        } else {
            nativeWidget.attributes.remove("fullscreenElement")
        }
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || Win32NativeWindowHost.hasWindow(this)) {
            Win32NativeWindowHost.setWindowFullscreen(
                windowImpl = this,
                fullscreen = fullscreen,
                fullscreenWidget = targetWidget
            )
        }
    }

    internal fun notifyHostFullscreenExited() {
        val sz = getWindowSize()
        val resolvedTheme = com.nexustools.genericui.component.shell.Application.current?.getResolvedTheme()
            ?: com.nexustools.genericui.style.theme.SystemThemeDetector.detectSystemTheme()
        onWindowResizeListener?.invoke(
            WindowConfiguration(
                widthPx = sz.width,
                heightPx = sz.height,
                windowState = currentWindowState,
                isFullscreen = false,
                themeMode = resolvedTheme
            )
        )
    }

    override fun setFullscreenElement(elementImpl: NativeImpl<*>?, cutoutMode: DisplayCutoutMode) {
        super.setFullscreenElement(elementImpl, cutoutMode)
        Win32NativeWindowHost.resolveHwndForWindow(this)?.let { hostHwnd ->
            Win32NativeWindowHost.layoutAndSyncWindowChildren(hostHwnd)
        }
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        super.onNativeBoundsChanged(rect)
        val hostHwnd = Win32NativeWindowHost.resolveHwndForWindow(this)
        if (hostHwnd != null) {
            Win32NativeWindowHost.layoutAndSyncWindowChildren(hostHwnd)
        } else if (rect.width > 0 && rect.height > 0) {
            layoutChildrenInBounds(rect.width.toFloat(), rect.height.toFloat())
        }
    }

    override fun setVisible(visible: Boolean) {
        super.setVisible(visible)
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || Win32NativeWindowHost.hasWindow(this)) {
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

    override fun closeWindow() {
        super.closeWindow()
        if (isDisposed && (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled || Win32NativeWindowHost.hasWindow(this))) {
            Win32NativeWindowHost.closeSecondaryOrPrimaryWindow(this)
        }
    }
}
