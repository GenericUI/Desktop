package com.nexustools.genericui.desktop.shell

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockWindowNativeImpl
import com.nexustools.genericui.enums.window.DisplayCutoutMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.shell.MenuBarNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.window.PipConfig
import com.nexustools.genericui.window.TitleBar
import com.nexustools.genericui.window.TitleCapabilities
import com.nexustools.genericui.window.WindowConfiguration

/**
 * Shared desktop base implementation of [WindowNativeImpl] supporting custom title bars,
 * platform-configurable embedded menu bar in title bar (`true` on Windows/GTK/Qt, `false` on macOS),
 * multi-window management, tool-modal panel windows, fullscreen, Picture-in-Picture, and window lifecycle callbacks.
 */
open class DesktopWindowNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    override val supportsCustomTitleBar: Boolean = true,
    override val supportsEmbeddedMenuBarInTitleBar: Boolean = true,
    override val supportsMultiWindow: Boolean = true,
    val defaultWindowIconPath: String = "icon.png"
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "window"), WindowNativeImpl<W> {

    var isToolModalWindow: Boolean = false
        protected set
    var showInTaskbar: Boolean = true
        protected set
    var minimizable: Boolean = true
        protected set
    var alwaysOnTopOfOwner: Boolean = false
        protected set
    var ownerWindow: WindowNativeImpl<*>? = null
        protected set
    var zOrderLayer: Int = 0
        protected set

    private val ownedToolWindows = mutableListOf<DesktopWindowNativeImpl<*>>()
    private val ownedDockWindows = mutableListOf<DesktopDockWindowNativeImpl<*>>()
    private val _ownedDialogWindows = mutableListOf<DesktopDialogWindowNativeImpl<*>>()
    val ownedDialogWindows: List<DesktopDialogWindowNativeImpl<*>>
        get() = _ownedDialogWindows

    protected var windowTitle: String = ""
    protected var currentWindowSize: Size = Size(1280f, 800f)
    private var minWindowSizeState: Size? = null
    protected var currentWindowState: WindowState = WindowState.NORMAL
    protected var fullscreenFlag: Boolean = false
    protected var cutoutModeState: DisplayCutoutMode = DisplayCutoutMode.SHORT_EDGES
    protected var fullscreenElementImpl: NativeImpl<*>? = null
    protected var activeTitleBar: TitleBar? = null
    protected var attachedMenuBar: MenuBarNativeImpl<*>? = null
    protected var attachedNavBar: NativeImpl<*>? = null
    protected var attachedBottomNav: NativeImpl<*>? = null
    protected var inPipMode: Boolean = false
    protected var activePipConfig: PipConfig? = null
    protected var onWindowResizeListener: ((WindowConfiguration) -> Unit)? = null
    protected var onWindowFocusChangedListener: ((Boolean) -> Unit)? = null
    protected var onWindowCloseRequestedListener: ((() -> Boolean))? = null

    init {
        nativeWidget.attributes["windowIcon"] = defaultWindowIconPath
    }

    fun getTitleBarInstructions(): TitleBar? = activeTitleBar

    internal fun registerOwnedToolWindow(toolWindow: DesktopWindowNativeImpl<*>) {
        if (toolWindow !== this && toolWindow !in ownedToolWindows) {
            ownedToolWindows.add(toolWindow)
        }
    }

    internal fun unregisterOwnedToolWindow(toolWindow: DesktopWindowNativeImpl<*>) {
        ownedToolWindows.remove(toolWindow)
    }

    internal fun registerOwnedDockWindow(dockWindow: DesktopDockWindowNativeImpl<*>) {
        if (dockWindow !in ownedDockWindows) {
            ownedDockWindows.add(dockWindow)
        }
    }

    internal fun unregisterOwnedDockWindow(dockWindow: DesktopDockWindowNativeImpl<*>) {
        ownedDockWindows.remove(dockWindow)
    }

    internal fun registerOwnedDialogWindow(dialogWindow: DesktopDialogWindowNativeImpl<*>) {
        if (dialogWindow !in _ownedDialogWindows) {
            _ownedDialogWindows.add(dialogWindow)
        }
    }

    internal fun unregisterOwnedDialogWindow(dialogWindow: DesktopDialogWindowNativeImpl<*>) {
        _ownedDialogWindows.remove(dialogWindow)
    }

    override fun configureAsToolModalWindow(
        ownerWindow: WindowNativeImpl<*>?,
        showInTaskbar: Boolean,
        minimizable: Boolean,
        alwaysOnTopOfOwner: Boolean
    ) {
        (this.ownerWindow as? DesktopWindowNativeImpl<*>)?.unregisterOwnedToolWindow(this)
        this.ownerWindow = ownerWindow
        this.isToolModalWindow = true
        this.showInTaskbar = showInTaskbar
        this.minimizable = minimizable
        this.alwaysOnTopOfOwner = alwaysOnTopOfOwner

        nativeWidget.attributes["tool-modal"] = "true"
        nativeWidget.attributes["show-in-taskbar"] = showInTaskbar.toString()
        nativeWidget.attributes["minimizable"] = minimizable.toString()
        nativeWidget.attributes["always-on-top-of-owner"] = alwaysOnTopOfOwner.toString()
        nativeWidget.attributes["ownerWindow"] = ownerWindow

        (ownerWindow as? DesktopWindowNativeImpl<*>)?.registerOwnedToolWindow(this)
        bringToFront()
    }

    override fun bringToFront() {
        zOrderLayer = nextZOrderSequence()
        nativeWidget.attributes["zOrderLayer"] = zOrderLayer
        nativeWidget.attributes["broughtToFront"] = true
        for (toolWin in ownedToolWindows.toList()) {
            if (toolWin.isVisible()) {
                toolWin.bringToFront()
            }
        }
        for (dockWin in ownedDockWindows.toList()) {
            if (dockWin.isVisible()) {
                dockWin.bringToFront()
            }
        }
        for (dialogWin in _ownedDialogWindows.toList()) {
            if (dialogWin.isVisible()) {
                dialogWin.bringToFront()
            }
        }
    }

    override fun setTitle(title: String) {
        windowTitle = title
        nativeWidget.attributes["title"] = title
    }

    override fun getTitle(): String = windowTitle

    override fun getMinWindowSize(): Size? = minWindowSizeState

    override fun setMinWindowSize(minSize: Size?) {
        minWindowSizeState = minSize
        if (minSize != null) {
            nativeWidget.attributes["minWidthPx"] = minSize.width
            nativeWidget.attributes["minHeightPx"] = minSize.height
            val cur = getWindowSize()
            if (cur.width < minSize.width || cur.height < minSize.height) {
                setWindowSize(Size(maxOf(cur.width, minSize.width), maxOf(cur.height, minSize.height)))
            }
        } else {
            nativeWidget.attributes.remove("minWidthPx")
            nativeWidget.attributes.remove("minHeightPx")
        }
    }

    override fun setWindowSize(size: Size) {
        val minSize = minWindowSizeState
        val clamped = if (minSize != null) {
            Size(maxOf(size.width, minSize.width), maxOf(size.height, minSize.height))
        } else {
            size
        }
        currentWindowSize = clamped
        nativeWidget.bounds = Rect(nativeWidget.bounds.x, nativeWidget.bounds.y, clamped.width, clamped.height)
        onWindowResizeListener?.invoke(
            WindowConfiguration(
                widthPx = clamped.width,
                heightPx = clamped.height,
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
        if (!fullscreen) {
            fullscreenElementImpl = null
            nativeWidget.attributes.remove("fullscreenElement")
        }
        currentWindowState = if (fullscreen) WindowState.FULLSCREEN else WindowState.NORMAL
        nativeWidget.attributes["fullscreen"] = fullscreen
    }

    override fun isFullscreen(): Boolean = fullscreenFlag

    override fun setFullscreenElement(elementImpl: NativeImpl<*>?, cutoutMode: DisplayCutoutMode) {
        fullscreenElementImpl = elementImpl
        if (elementImpl != null) {
            nativeWidget.attributes["fullscreenElement"] = elementImpl.nativeWidget
        } else {
            nativeWidget.attributes.remove("fullscreenElement")
        }
        setFullscreen(elementImpl != null, cutoutMode)
    }

    override fun getFullscreenElement(): NativeImpl<*>? = fullscreenElementImpl

    override fun applyTitleBarInstructions(titleBar: TitleBar?) {
        activeTitleBar?.let { prev ->
            prev.setHostEmbedsComponents(false)
            for (comp in prev.leadingComponents + prev.centerComponents + prev.trailingComponents) {
                (comp.nativeImpl?.nativeWidget as? DesktopNativeWidget)?.attributes?.remove("mountedInTitleBar")
            }
        }
        activeTitleBar = titleBar
        nativeWidget.attributes["titleBar"] = titleBar
        if (titleBar != null) {
            val embeds = getTitleCapabilities().canEmbedComponentsInTitleBar
            titleBar.setHostEmbedsComponents(embeds)
            for (comp in titleBar.leadingComponents + titleBar.centerComponents + titleBar.trailingComponents) {
                (comp.nativeImpl?.nativeWidget as? DesktopNativeWidget)?.attributes?.put("mountedInTitleBar", embeds)
            }
            if (!isToolModalWindow) {
                minimizable = titleBar.showMinimizeButton
            }
            nativeWidget.attributes["minimizable"] = (if (isToolModalWindow) minimizable else titleBar.showMinimizeButton).toString()
            nativeWidget.attributes["maximizable"] = titleBar.showMaximizeButton.toString()
            nativeWidget.attributes["closable"] = titleBar.showCloseButton.toString()
        }
    }

    override fun getTitleCapabilities(): TitleCapabilities = TitleCapabilities(
        isSupported = true,
        isEmulated = false,
        supportsCustomTitleBar = supportsCustomTitleBar,
        supportsCustomComponents = supportsCustomTitleBar,
        supportsLeadingComponents = supportsCustomTitleBar,
        supportsCenterComponents = supportsCustomTitleBar,
        supportsTrailingComponents = supportsCustomTitleBar,
        supportsEmbeddedMenuBar = supportsEmbeddedMenuBarInTitleBar,
        supportsNavbarMenuBar = false,
        supportsWindowIcon = true,
        supportsWindowTitle = true,
        supportsMinimizeButton = true,
        supportsMaximizeButton = true,
        supportsCloseButton = true,
        supportsCollapseToggle = false,
        supportsControlsPlacement = true,
        supportsCustomHeight = true,
        supportsSystemAeroOrBackdrop = false,
        isToolWindowTitleBar = false,
        isTitleBarRequired = true,
        titleBarRendersAsNavbar = false,
        menuBarRendersAsNavbar = false,
        menuBarMergesIntoTitleNavbar = false
    )

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
        bringToFront()
        onWindowFocusChangedListener?.invoke(true)
    }

    override fun closeWindow() {
        val allow = onWindowCloseRequestedListener?.invoke() ?: true
        if (allow) {
            setVisible(false)
            onWindowFocusChangedListener?.invoke(false)
            (ownerWindow as? DesktopWindowNativeImpl<*>)?.unregisterOwnedToolWindow(this)
            ownerWindow = null
            ownedToolWindows.clear()
            ownedDockWindows.clear()
            for (dialogWin in _ownedDialogWindows.toList()) {
                dialogWin.closeDialog()
            }
            _ownedDialogWindows.clear()
            dispose()
        }
    }

    override fun setFocusedState(focused: Boolean) {
        super<DesktopBaseContainerNativeImpl>.setFocusedState(focused)
        if (focused) {
            bringToFront()
        }
    }

    open fun dispatchFocusChanged(focused: Boolean) {
        setFocusedState(focused)
        onWindowFocusChangedListener?.invoke(focused)
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

    companion object {
        private var zOrderCounter: Int = 0

        internal fun nextZOrderSequence(): Int = ++zOrderCounter
    }
}
