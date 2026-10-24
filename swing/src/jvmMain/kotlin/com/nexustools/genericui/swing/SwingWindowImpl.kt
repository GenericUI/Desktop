package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.window.DisplayCutoutMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.shell.MenuBarNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.window.PipConfig
import com.nexustools.genericui.window.TitleBar
import com.nexustools.genericui.window.WindowConfiguration
import java.awt.BorderLayout
import java.awt.GraphicsEnvironment
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JMenuBar
import javax.swing.JPanel

/**
 * Java Swing `JFrame` + root `JPanel` implementation of [WindowNativeImpl].
 *
 * Safe to instantiate in both desktop AWT environments and headless CI test environments
 * (`GraphicsEnvironment.isHeadless()`).
 */
class SwingWindowImpl : SwingBaseContainerNativeImpl(JPanel(BorderLayout()), "window"), WindowNativeImpl<JComponent> {
    override val supportsCustomTitleBar: Boolean = true
    override val supportsEmbeddedMenuBarInTitleBar: Boolean = true
    override val supportsMultiWindow: Boolean = true

    val jFrame: JFrame? = if (!GraphicsEnvironment.isHeadless()) {
        try {
            JFrame("GenericUI").apply {
                contentPane.add(nativeWidget, BorderLayout.CENTER)
                setSize(1280, 800)
            }
        } catch (_: Throwable) {
            null
        }
    } else null

    private var titleText: String = "GenericUI"
    private var windowSize: Size = Size(1280, 800)
    private var windowState: WindowState = WindowState.NORMAL
    private var fullscreenFlag: Boolean = false
    private var pipActive: Boolean = false
    private var activeTitleBar: TitleBar? = null

    private var menuBarImpl: MenuBarNativeImpl<*>? = null
    private var navBarImpl: NativeImpl<*>? = null
    private var bottomNavImpl: NativeImpl<*>? = null

    private var onResizeListener: ((WindowConfiguration) -> Unit)? = null
    private var onFocusListener: ((Boolean) -> Unit)? = null
    private var onCloseRequestedListener: (() -> Boolean)? = null

    override fun setTitle(title: String) {
        titleText = title
        jFrame?.title = title
    }

    override fun getTitle(): String = titleText

    override fun setWindowSize(size: Size) {
        windowSize = size
        nativeWidget.setBounds(0, 0, size.width, size.height)
        jFrame?.setSize(size.width, size.height)
        onResizeListener?.invoke(WindowConfiguration(widthPx = size.width, heightPx = size.height))
    }

    override fun getWindowSize(): Size = windowSize

    override fun setWindowState(state: WindowState) {
        windowState = state
        jFrame?.extendedState = when (state) {
            WindowState.MINIMIZED -> JFrame.ICONIFIED
            WindowState.MAXIMIZED -> JFrame.MAXIMIZED_BOTH
            else -> JFrame.NORMAL
        }
    }

    override fun getWindowState(): WindowState = windowState

    override fun setFullscreen(fullscreen: Boolean, cutoutMode: DisplayCutoutMode) {
        fullscreenFlag = fullscreen
    }

    override fun isFullscreen(): Boolean = fullscreenFlag

    override fun applyTitleBarInstructions(titleBar: TitleBar?) {
        activeTitleBar = titleBar
    }

    override fun setMenuBarNative(menuBarImpl: MenuBarNativeImpl<*>?) {
        this.menuBarImpl = menuBarImpl
        val swingMenuBar = menuBarImpl?.nativeWidget as? JMenuBar
        jFrame?.jMenuBar = swingMenuBar
    }

    override fun setNavBarNative(navBarImpl: NativeImpl<*>?) {
        (this.navBarImpl?.nativeWidget as? JComponent)?.let { nativeWidget.remove(it) }
        this.navBarImpl = navBarImpl
        (navBarImpl?.nativeWidget as? JComponent)?.let { nativeWidget.add(it, BorderLayout.NORTH) }
    }

    override fun setBottomNavigationBarNative(bottomNavImpl: NativeImpl<*>?) {
        (this.bottomNavImpl?.nativeWidget as? JComponent)?.let { nativeWidget.remove(it) }
        this.bottomNavImpl = bottomNavImpl
        (bottomNavImpl?.nativeWidget as? JComponent)?.let { nativeWidget.add(it, BorderLayout.SOUTH) }
    }

    override fun enterPictureInPicture(config: PipConfig): Boolean {
        pipActive = true
        jFrame?.isAlwaysOnTop = true
        return true
    }

    override fun exitPictureInPicture() {
        pipActive = false
        jFrame?.isAlwaysOnTop = false
    }

    override fun isInPictureInPicture(): Boolean = pipActive

    override fun showWindow() {
        setVisible(true)
        jFrame?.isVisible = true
        onFocusListener?.invoke(true)
    }

    override fun closeWindow() {
        val allow = onCloseRequestedListener?.invoke() ?: true
        if (allow) {
            setVisible(false)
            jFrame?.isVisible = false
            jFrame?.dispose()
        }
    }

    override fun getBounds(): Rect = Rect(0, 0, windowSize.width, windowSize.height)

    override fun setOnWindowResize(listener: ((WindowConfiguration) -> Unit)?) { onResizeListener = listener }
    override fun setOnWindowFocusChanged(listener: ((Boolean) -> Unit)?) { onFocusListener = listener }
    override fun setOnWindowCloseRequested(listener: (() -> Boolean)?) { onCloseRequestedListener = listener }
}
