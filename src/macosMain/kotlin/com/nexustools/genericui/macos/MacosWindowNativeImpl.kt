package com.nexustools.genericui.macos

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.enums.window.DisplayCutoutMode
import com.nexustools.genericui.enums.window.TitleBarControlsPlacement
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.shell.MenuBarNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.style.transition.CssTransitionSpec
import com.nexustools.genericui.window.TitleBar
import com.nexustools.genericui.window.TitleCapabilities
import com.nexustools.genericui.window.WindowConfiguration

/**
 * macOS AppKit `NSWindow` implementation of [com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl].
 *
 * - `supportsCustomTitleBar = true`: Uses `NSWindowStyleMaskFullSizeContentView` +
 *   `titlebarAppearsTransparent = YES` + `titleVisibility = NSWindowTitleHidden` with native traffic light buttons.
 * - `supportsEmbeddedMenuBarInTitleBar = false`: macOS always places the application menu bar at the top of the
 *   screen via `NSApplication.mainMenu` (`NSMenu`) rather than embedding it inside the window title bar.
 */
class MacosWindowNativeImpl : DesktopWindowNativeImpl<MacosViewHandle>(
    nativeWidget = MacosViewHandle("NSWindow"),
    supportsCustomTitleBar = true,
    supportsEmbeddedMenuBarInTitleBar = false,
    supportsMultiWindow = true
) {
    private var suppressHostSync: Boolean = false

    override fun configureAsToolModalWindow(
        ownerWindow: WindowNativeImpl<*>?,
        showInTaskbar: Boolean,
        minimizable: Boolean,
        alwaysOnTopOfOwner: Boolean
    ) {
        super.configureAsToolModalWindow(ownerWindow, showInTaskbar, minimizable, alwaysOnTopOfOwner)
        nativeWidget.styleMask = (nativeWidget.styleMask or MacosViewHandle.NSWindowStyleMaskUtilityWindow) and
            (if (!minimizable) MacosViewHandle.NSWindowStyleMaskMiniaturizable.inv() else -1L)
        val effectiveFloating = alwaysOnTopFlag || alwaysOnTopOfOwner
        nativeWidget.attributes["floatingPanel"] = effectiveFloating
        nativeWidget.attributes["hidesOnDeactivate"] = false
        nativeWidget.attributes["level"] = if (effectiveFloating) "NSFloatingWindowLevel" else "NSNormalWindowLevel"
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            MacosNativeWindowHost.syncWindowProperties(this)
        }
    }

    override fun applyTitleBarInstructions(titleBar: TitleBar?) {
        super.applyTitleBarInstructions(titleBar)
        val undecoratedHost = super.isUndecorated() && !isToolModalWindow
        val custom = undecoratedHost || titleBar?.customTitleBar == true
        if (!super.isUndecorated() && titleBar?.customTitleBar == true) {
            nativeWidget.styleMask = nativeWidget.styleMask or MacosViewHandle.NSWindowStyleMaskFullSizeContentView
        }
        val allowMinimize = if (undecoratedHost) {
            true
        } else if (isToolModalWindow) {
            minimizable
        } else {
            titleBar?.showMinimizeButton ?: true
        }
        if (!allowMinimize) {
            nativeWidget.styleMask = nativeWidget.styleMask and MacosViewHandle.NSWindowStyleMaskMiniaturizable.inv()
        } else {
            nativeWidget.styleMask = nativeWidget.styleMask or MacosViewHandle.NSWindowStyleMaskMiniaturizable
        }
        nativeWidget.attributes["titlebarAppearsTransparent"] = custom
        nativeWidget.attributes["titleVisibility"] = if (custom) "NSWindowTitleHidden" else "NSWindowTitleVisible"
        titleBar?.onInstructionsChanged = {
            if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
                MacosNativeWindowHost.syncWindowProperties(this)
                MacosNativeWindowHost.requestRedraw()
            }
        }
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            MacosNativeWindowHost.syncWindowProperties(this)
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
        supportsEmbeddedMenuBar = false,
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
        defaultControlsPlacement = TitleBarControlsPlacement.LEADING,
        prefersBlurredTranslucentOverlayTitleBar = true,
        usesNativeOverlayTitleBarControls = true
    )

    override fun setTitle(title: String) {
        super.setTitle(title)
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            MacosNativeWindowHost.syncWindowTitle(this, title)
        }
    }

    override fun setAlwaysOnTop(alwaysOnTop: Boolean) {
        super.setAlwaysOnTop(alwaysOnTop)
        val effectiveFloating = alwaysOnTop || (isToolModalWindow && alwaysOnTopOfOwner)
        nativeWidget.attributes["level"] = if (effectiveFloating) "NSFloatingWindowLevel" else "NSNormalWindowLevel"
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !suppressHostSync) {
            MacosNativeWindowHost.syncWindowAlwaysOnTop(this)
        }
    }

    override fun setAspectRatio(aspectRatio: Double?) {
        val changed = getAspectRatio() != aspectRatio
        super.setAspectRatio(aspectRatio)
        if (changed && MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !suppressHostSync) {
            MacosNativeWindowHost.syncWindowAspectRatio(this)
        }
    }

    override fun setMinWindowSize(minSize: Size?) {
        val changed = getMinWindowSize() != minSize
        super.setMinWindowSize(minSize)
        if (changed && MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !suppressHostSync) {
            MacosNativeWindowHost.syncWindowSize(this)
        }
    }

    override fun setWindowSize(size: Size) {
        super.setWindowSize(size)
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !suppressHostSync) {
            MacosNativeWindowHost.syncWindowSize(this)
        }
    }

    override fun setWindowPosition(x: Int, y: Int) {
        super.setWindowPosition(x, y)
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !suppressHostSync) {
            MacosNativeWindowHost.syncWindowPosition(this)
        }
    }

    override fun setWindowState(state: WindowState) {
        super.setWindowState(state)
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !suppressHostSync) {
            MacosNativeWindowHost.syncWindowState(this)
        }
    }

    override fun isUndecorated(): Boolean = super.isUndecorated()

    override fun setUndecorated(undecorated: Boolean) {
        val changed = super.isUndecorated() != undecorated
        super.setUndecorated(undecorated)
        if (undecorated) {
            if (isToolModalWindow) {
                nativeWidget.styleMask = MacosViewHandle.NSWindowStyleMaskBorderless or
                    MacosViewHandle.NSWindowStyleMaskClosable or
                    (if (minimizable) MacosViewHandle.NSWindowStyleMaskMiniaturizable else 0L) or
                    MacosViewHandle.NSWindowStyleMaskResizable
            } else {
                nativeWidget.styleMask = MacosViewHandle.NSWindowStyleMaskBorderless or
                    MacosViewHandle.NSWindowStyleMaskClosable or
                    MacosViewHandle.NSWindowStyleMaskMiniaturizable or
                    MacosViewHandle.NSWindowStyleMaskResizable
                nativeWidget.attributes["titlebarAppearsTransparent"] = true
                nativeWidget.attributes["titleVisibility"] = "NSWindowTitleHidden"
            }
        } else {
            val tb = getTitleBarInstructions()
            var mask = MacosViewHandle.NSWindowStyleMaskTitled or
                MacosViewHandle.NSWindowStyleMaskClosable or
                MacosViewHandle.NSWindowStyleMaskResizable
            val allowMin = if (isToolModalWindow) minimizable else (tb?.showMinimizeButton ?: true)
            if (allowMin) {
                mask = mask or MacosViewHandle.NSWindowStyleMaskMiniaturizable
            }
            if (tb?.customTitleBar == true) {
                mask = mask or MacosViewHandle.NSWindowStyleMaskFullSizeContentView
            }
            if (isToolModalWindow) {
                mask = mask or MacosViewHandle.NSWindowStyleMaskUtilityWindow
            }
            nativeWidget.styleMask = mask
        }
        if (changed && MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !suppressHostSync) {
            MacosNativeWindowHost.syncWindowProperties(this)
        }
    }

    override fun setOnWindowStateChanged(listener: ((WindowState) -> Unit)?) {
        super.setOnWindowStateChanged(listener)
    }

    override fun startSystemMove() {
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !isDisposed) {
            MacosNativeWindowHost.startSystemMove(this)
        }
    }

    override fun setFullscreen(fullscreen: Boolean, cutoutMode: DisplayCutoutMode) {
        super.setFullscreen(fullscreen, cutoutMode)
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !suppressHostSync) {
            MacosNativeWindowHost.syncFullscreen(this, fullscreen)
        }
    }

    override fun setFullscreenElement(elementImpl: NativeImpl<*>?, cutoutMode: DisplayCutoutMode) {
        super.setFullscreenElement(elementImpl, cutoutMode)
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            MacosNativeWindowHost.requestRedraw()
        }
    }

    override fun setMenuBarNative(menuBarImpl: MenuBarNativeImpl<*>?) {
        super.setMenuBarNative(menuBarImpl)
        // Always routed to NSApplication.sharedApplication.mainMenu on macOS
        nativeWidget.attributes["nsApplicationMainMenu"] = menuBarImpl
        nativeWidget.attributes["embedMenuBarInTitleBar"] = false
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            MacosNativeWindowHost.syncMenuBar(this, menuBarImpl)
        }
    }

    override fun showWindow() {
        super.showWindow()
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && isVisible()) {
            MacosNativeWindowHost.presentWindow(this)
        }
    }

    override fun setVisible(visible: Boolean) {
        super.setVisible(visible)
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            MacosNativeWindowHost.syncWindowVisibility(this, visible)
        }
    }

    override fun bringToFront() {
        super.bringToFront()
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !suppressHostSync) {
            MacosNativeWindowHost.bringWindowToFront(this)
        }
    }

    override fun closeWindow() {
        super.closeWindow()
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && isDisposed) {
            MacosNativeWindowHost.onWindowClosed(this)
        }
    }

    override fun setNavBarNative(navBarImpl: com.nexustools.genericui.core.NativeImpl<*>?) {
        val changed = attachedNavBar !== navBarImpl
        super.setNavBarNative(navBarImpl)
        if (changed && MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !suppressHostSync) {
            MacosNativeWindowHost.syncWindowProperties(this)
            MacosNativeWindowHost.requestRedraw()
        }
    }

    override fun setBottomNavigationBarNative(bottomNavImpl: com.nexustools.genericui.core.NativeImpl<*>?) {
        val changed = attachedBottomNav !== bottomNavImpl
        super.setBottomNavigationBarNative(bottomNavImpl)
        if (changed && MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !suppressHostSync) {
            MacosNativeWindowHost.syncWindowProperties(this)
            MacosNativeWindowHost.requestRedraw()
        }
    }

    override fun dispose() {
        val alreadyDisposed = isDisposed
        super.dispose()
        if (!alreadyDisposed && MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            MacosNativeWindowHost.onWindowClosed(this)
        }
    }

    internal fun syncHostClientSizeFromChromeTransition(widthPx: Int, heightPx: Int) {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val newSize = Size(w, h)
        val changed = currentWindowSize != newSize ||
            nativeWidget.bounds.width != w ||
            nativeWidget.bounds.height != h
        suppressHostSync = true
        try {
            currentWindowSize = newSize
            nativeWidget.bounds = Rect(0, 0, w, h)
        } finally {
            suppressHostSync = false
        }
        if (changed) {
            com.nexustools.genericui.threading.GenericUiThreading.runOnLogic {
                val resolvedTheme = com.nexustools.genericui.component.shell.Application.current?.getResolvedTheme()
                    ?: com.nexustools.genericui.style.theme.SystemThemeDetector.detectSystemTheme()
                onWindowResizeListener?.invoke(
                    WindowConfiguration(
                        widthPx = w,
                        heightPx = h,
                        windowState = currentWindowState,
                        isFullscreen = fullscreenFlag,
                        themeMode = resolvedTheme
                    )
                )
            }
        }
    }

    internal fun notifyHostResizedFromOs(
        widthPx: Int,
        heightPx: Int,
        fullscreenNow: Boolean,
        maximizedNow: Boolean = false
    ) {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val newSize = Size(w, h)
        val nextState = when {
            fullscreenNow -> WindowState.FULLSCREEN
            maximizedNow -> WindowState.MAXIMIZED
            currentWindowState == WindowState.FULLSCREEN || currentWindowState == WindowState.MAXIMIZED -> WindowState.NORMAL
            else -> currentWindowState
        }
        val prevFullscreen = fullscreenFlag
        val hadFullscreenElement = fullscreenElementImpl != null
        val changed = currentWindowSize != newSize ||
            fullscreenFlag != fullscreenNow ||
            currentWindowState != nextState ||
            nativeWidget.bounds.width != w ||
            nativeWidget.bounds.height != h
        val prevState = currentWindowState
        suppressHostSync = true
        try {
            currentWindowSize = newSize
            fullscreenFlag = fullscreenNow
            currentWindowState = nextState
            if (!fullscreenNow && fullscreenElementImpl != null) {
                fullscreenElementImpl = null
                nativeWidget.attributes.remove("fullscreenElement")
            }
            nativeWidget.attributes["fullscreen"] = fullscreenNow
            nativeWidget.attributes["windowState"] = currentWindowState
            nativeWidget.bounds = Rect(0, 0, w, h)
            if (prevFullscreen != fullscreenNow || hadFullscreenElement) {
                nativeWidget.bumpAttrMutationVersion()
                nativeWidget.bumpSubtreeMutationVersion()
            }
        } finally {
            suppressHostSync = false
        }
        if (prevState != currentWindowState) {
            onWindowStateChangedListener?.invoke(currentWindowState)
        }
        if (changed) {
            val resolvedTheme = com.nexustools.genericui.component.shell.Application.current?.getResolvedTheme()
                ?: com.nexustools.genericui.style.theme.SystemThemeDetector.detectSystemTheme()
            onWindowResizeListener?.invoke(
                WindowConfiguration(
                    widthPx = w,
                    heightPx = h,
                    windowState = currentWindowState,
                    isFullscreen = fullscreenFlag,
                    themeMode = resolvedTheme
                )
            )
        }
        if (prevState != currentWindowState && MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            MacosNativeWindowHost.syncWindowAspectRatio(this)
        }
    }

    internal fun notifyHostMovedFromOs(x: Int, y: Int) {
        suppressHostSync = true
        try {
            updatePositionFromHost(x, y)
        } finally {
            suppressHostSync = false
        }
    }

    internal fun notifyHostMiniaturizedFromOs(
        miniaturized: Boolean,
        restoredState: WindowState = WindowState.NORMAL
    ) {
        val nextState = if (miniaturized) WindowState.MINIMIZED else restoredState
        val prevState = currentWindowState
        suppressHostSync = true
        try {
            currentWindowState = nextState
            nativeWidget.attributes["windowState"] = currentWindowState
        } finally {
            suppressHostSync = false
        }
        if (prevState != nextState) {
            val resolvedTheme = com.nexustools.genericui.component.shell.Application.current?.getResolvedTheme()
                ?: com.nexustools.genericui.style.theme.SystemThemeDetector.detectSystemTheme()
            val config = WindowConfiguration(
                widthPx = currentWindowSize.width,
                heightPx = currentWindowSize.height,
                windowState = currentWindowState,
                isFullscreen = fullscreenFlag,
                themeMode = resolvedTheme
            )
            com.nexustools.genericui.threading.GenericUiThreading.runOnLogic {
                onWindowStateChangedListener?.invoke(currentWindowState)
                onWindowResizeListener?.invoke(config)
            }
        }
    }

    internal fun getAttachedMenuBarImpl(): MenuBarNativeImpl<*>? = attachedMenuBar

    override fun runNativeTransition(
        property: String,
        fromValue: String?,
        toValue: String,
        spec: CssTransitionSpec,
        onComplete: () -> Unit
    ): Boolean {
        val handled = super.runNativeTransition(property, fromValue, toValue, spec, onComplete)
        if (property.equals("opacity", ignoreCase = true)) {
            val fromAlpha = (fromValue?.toFloatOrNull() ?: getOpacity()).coerceIn(0f, 1f)
            val targetAlpha = (toValue.toFloatOrNull() ?: getOpacity()).coerceIn(0f, 1f)
            nativeWidget.attributes["transitionFromOpacity"] = fromAlpha
            nativeWidget.attributes["transitionToOpacity"] = targetAlpha
            setOpacity(targetAlpha)
            if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
                MacosNativeWindowHost.syncWindowOpacity(this, targetAlpha)
            }
            if (spec.durationMs <= 0f) {
                onComplete()
            }
        }
        return handled
    }
}
