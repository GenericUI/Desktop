package com.nexustools.genericui.macos

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.enums.window.DisplayCutoutMode
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
        nativeWidget.attributes["floatingPanel"] = alwaysOnTopOfOwner
        nativeWidget.attributes["hidesOnDeactivate"] = false
        nativeWidget.attributes["level"] = if (alwaysOnTopOfOwner) "NSFloatingWindowLevel" else "NSNormalWindowLevel"
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            MacosNativeWindowHost.syncWindowProperties(this)
        }
    }

    override fun applyTitleBarInstructions(titleBar: TitleBar?) {
        super.applyTitleBarInstructions(titleBar)
        val custom = titleBar?.customTitleBar == true
        if (custom) {
            nativeWidget.styleMask = nativeWidget.styleMask or MacosViewHandle.NSWindowStyleMaskFullSizeContentView
        }
        val allowMinimize = if (isToolModalWindow) minimizable else (titleBar?.showMinimizeButton ?: true)
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
        menuBarMergesIntoTitleNavbar = false
    )

    override fun setTitle(title: String) {
        super.setTitle(title)
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            MacosNativeWindowHost.syncWindowTitle(this, title)
        }
    }

    override fun setMinWindowSize(minSize: Size?) {
        super.setMinWindowSize(minSize)
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !suppressHostSync) {
            MacosNativeWindowHost.syncWindowSize(this)
        }
    }

    override fun setWindowSize(size: Size) {
        super.setWindowSize(size)
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !suppressHostSync) {
            MacosNativeWindowHost.syncWindowSize(this)
        }
    }

    override fun setWindowState(state: WindowState) {
        super.setWindowState(state)
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled && !suppressHostSync) {
            MacosNativeWindowHost.syncWindowState(this)
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
                        isInPictureInPicture = inPipMode,
                        themeMode = resolvedTheme
                    )
                )
            }
        }
    }

    internal fun notifyHostResizedFromOs(widthPx: Int, heightPx: Int, fullscreenNow: Boolean) {
        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val newSize = Size(w, h)
        val changed = currentWindowSize != newSize ||
            fullscreenFlag != fullscreenNow ||
            nativeWidget.bounds.width != w ||
            nativeWidget.bounds.height != h
        suppressHostSync = true
        try {
            currentWindowSize = newSize
            fullscreenFlag = fullscreenNow
            if (fullscreenNow) {
                currentWindowState = WindowState.FULLSCREEN
            } else if (currentWindowState == WindowState.FULLSCREEN) {
                currentWindowState = WindowState.NORMAL
            }
            nativeWidget.attributes["fullscreen"] = fullscreenNow
            nativeWidget.attributes["windowState"] = currentWindowState
            nativeWidget.bounds = Rect(0, 0, w, h)
        } finally {
            suppressHostSync = false
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
                    isInPictureInPicture = inPipMode,
                    themeMode = resolvedTheme
                )
            )
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
