package com.nexustools.genericui.desktop.shell

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.window.DisplayCutoutMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.shell.MenuBarNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.window.PipConfig
import com.nexustools.genericui.window.TitleBar
import com.nexustools.genericui.window.WindowConfiguration

/**
 * Shared desktop base implementation of [WindowNativeImpl] supporting custom title bars,
 * platform-configurable embedded menu bar in title bar (`true` on Windows/GTK/Qt, `false` on macOS),
 * multi-window management, fullscreen, Picture-in-Picture, and window lifecycle callbacks.
 */
open class DesktopWindowNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    override val supportsCustomTitleBar: Boolean = true,
    override val supportsEmbeddedMenuBarInTitleBar: Boolean = true,
    override val supportsMultiWindow: Boolean = true
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "window"), WindowNativeImpl<W> {

    protected var windowTitle: String = ""
    protected var currentWindowSize: Size = Size(1280f, 800f)
    protected var currentWindowState: WindowState = WindowState.NORMAL
    protected var fullscreenFlag: Boolean = false
    protected var cutoutModeState: DisplayCutoutMode = DisplayCutoutMode.SHORT_EDGES
    protected var activeTitleBar: TitleBar? = null
    protected var attachedMenuBar: MenuBarNativeImpl<*>? = null
    protected var attachedNavBar: NativeImpl<*>? = null
    protected var attachedBottomNav: NativeImpl<*>? = null
    protected var inPipMode: Boolean = false
    protected var activePipConfig: PipConfig? = null
    protected var onWindowResizeListener: ((WindowConfiguration) -> Unit)? = null
    protected var onWindowFocusChangedListener: ((Boolean) -> Unit)? = null
    protected var onWindowCloseRequestedListener: (() -> Boolean)? = null

    override fun setTitle(title: String) {
        windowTitle = title
        nativeWidget.attributes["title"] = title
    }

    override fun getTitle(): String = windowTitle

    override fun setWindowSize(size: Size) {
        currentWindowSize = size
        nativeWidget.bounds = Rect(nativeWidget.bounds.x, nativeWidget.bounds.y, size.width, size.height)
        onWindowResizeListener?.invoke(
            WindowConfiguration(
                widthPx = size.width.toInt(),
                heightPx = size.height.toInt(),
                windowState = currentWindowState,
                isFullscreen = fullscreenFlag,
                isInPictureInPicture = inPipMode
            )
        )
    }

    override fun getWindowSize(): Size = currentWindowSize

    override fun setWindowState(state: WindowState) {
        currentWindowState = state
        fullscreenFlag = (state == WindowState.FULLSCREEN)
        nativeWidget.attributes["windowState"] = state
    }

    override fun getWindowState(): WindowState = currentWindowState

    override fun setFullscreen(fullscreen: Boolean, cutoutMode: DisplayCutoutMode) {
        fullscreenFlag = fullscreen
        cutoutModeState = cutoutMode
        currentWindowState = if (fullscreen) WindowState.FULLSCREEN else WindowState.NORMAL
        nativeWidget.attributes["fullscreen"] = fullscreen
    }

    override fun isFullscreen(): Boolean = fullscreenFlag

    override fun applyTitleBarInstructions(titleBar: TitleBar?) {
        activeTitleBar = titleBar
        nativeWidget.attributes["titleBar"] = titleBar
    }

    override fun setMenuBarNative(menuBarImpl: MenuBarNativeImpl<*>?) {
        attachedMenuBar = menuBarImpl
        nativeWidget.attributes["menuBar"] = menuBarImpl
        nativeWidget.attributes["embedMenuBarInTitleBar"] =
            supportsEmbeddedMenuBarInTitleBar && (activeTitleBar?.customTitleBar == true)
    }

    override fun setNavBarNative(navBarImpl: NativeImpl<*>?) {
        attachedNavBar = navBarImpl
        nativeWidget.attributes["navBar"] = navBarImpl
    }

    override fun setBottomNavigationBarNative(bottomNavImpl: NativeImpl<*>?) {
        attachedBottomNav = bottomNavImpl
        nativeWidget.attributes["bottomNav"] = bottomNavImpl
    }

    override fun enterPictureInPicture(config: PipConfig): Boolean {
        inPipMode = true
        activePipConfig = config
        currentWindowState = WindowState.PICTURE_IN_PICTURE
        return true
    }

    override fun exitPictureInPicture() {
        inPipMode = false
        activePipConfig = null
        if (currentWindowState == WindowState.PICTURE_IN_PICTURE) {
            currentWindowState = WindowState.NORMAL
        }
    }

    override fun isInPictureInPicture(): Boolean = inPipMode

    override fun showWindow() {
        setVisible(true)
        onWindowFocusChangedListener?.invoke(true)
    }

    override fun closeWindow() {
        val allow = onWindowCloseRequestedListener?.invoke() ?: true
        if (allow) {
            setVisible(false)
            onWindowFocusChangedListener?.invoke(false)
            dispose()
        }
    }

    override fun setOnWindowResize(listener: ((WindowConfiguration) -> Unit)?) {
        onWindowResizeListener = listener
    }

    override fun setOnWindowFocusChanged(listener: ((Boolean) -> Unit)?) {
        onWindowFocusChangedListener = listener
    }

    override fun setOnWindowCloseRequested(listener: (() -> Boolean)?) {
        onWindowCloseRequestedListener = listener
    }
}
