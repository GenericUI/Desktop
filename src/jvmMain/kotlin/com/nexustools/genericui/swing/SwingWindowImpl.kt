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
import com.nexustools.genericui.window.TitleCapabilities
import com.nexustools.genericui.window.WindowAspectRatioHelper
import com.nexustools.genericui.window.WindowConfiguration
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JMenuBar
import javax.swing.JPanel
import javax.swing.SwingUtilities

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

    var isToolModalWindow: Boolean = false
        private set
    var showInTaskbar: Boolean = true
        private set
    var minimizable: Boolean = true
        private set
    var alwaysOnTopOfOwner: Boolean = false
        private set
    var ownerWindow: WindowNativeImpl<*>? = null
        private set
    var zOrderLayer: Int = 0
        private set

    private val ownedToolWindows = mutableListOf<SwingWindowImpl>()
    private val ownedDockWindows = mutableListOf<SwingDockWindowImpl>()
    private val _ownedDialogWindows = mutableListOf<SwingDialogWindowImpl>()
    val ownedDialogWindows: List<SwingDialogWindowImpl>
        get() = _ownedDialogWindows

    val defaultWindowIconPath: String = "icon.png"

    val jFrame: JFrame? = if (!GraphicsEnvironment.isHeadless()) {
        try {
            JFrame("GenericUI").apply {
                defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
                contentPane.add(nativeWidget, BorderLayout.CENTER)
                setSize(1280, 800)
                setLocationRelativeTo(null)
                loadDefaultWindowIcon()?.let { iconImage = it }
            }
        } catch (_: Throwable) {
            null
        }
    } else null

    init {
        nativeWidget.putClientProperty("windowIcon", defaultWindowIconPath)
        jFrame?.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                val frame = jFrame ?: return
                if (!frame.isVisible) return
                handleHostFrameResized(
                    outerWidth = frame.width,
                    outerHeight = frame.height,
                    insetsLeft = frame.insets.left,
                    insetsTop = frame.insets.top,
                    insetsRight = frame.insets.right,
                    insetsBottom = frame.insets.bottom,
                    extendedState = frame.extendedState
                )
            }
        })
        jFrame?.addWindowStateListener(WindowStateListener { e ->
            val frame = jFrame ?: return@WindowStateListener
            val ext = e?.newState ?: frame.extendedState
            syncWindowStateFromExtendedState(ext)
        })
    }

    private fun loadDefaultWindowIcon(): java.awt.Image? {
        return try {
            val localFile = java.io.File(defaultWindowIconPath)
            if (localFile.isFile) {
                javax.imageio.ImageIO.read(localFile)
            } else {
                val stream = SwingWindowImpl::class.java.getResourceAsStream("/$defaultWindowIconPath")
                if (stream != null) stream.use { javax.imageio.ImageIO.read(it) } else null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private var titleText: String = "GenericUI"
    private var windowSize: Size = Size(1280, 800)
    private var minWindowSize: Size? = null
    private var alwaysOnTopFlag: Boolean = false
    private var aspectRatioValue: Double? = null
    private var isApplyingSwingAspectClamp: Boolean = false
    private var isSyncingFromComponentResize: Boolean = false
    private var windowState: WindowState = WindowState.NORMAL
    private var fullscreenFlag: Boolean = false
    private var pipActive: Boolean = false
    private var activeTitleBar: TitleBar? = null

    private inline fun runOnEdt(crossinline block: () -> Unit) {
        if (GraphicsEnvironment.isHeadless() || SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            try {
                SwingUtilities.invokeAndWait { block() }
            } catch (_: Throwable) {
                SwingUtilities.invokeLater { block() }
            }
        }
    }

    private fun applySwingAlwaysOnTop() {
        val effective = alwaysOnTopFlag || (isToolModalWindow && alwaysOnTopOfOwner) || pipActive
        runCatching { jFrame?.isAlwaysOnTop = effective }
    }

    private fun resolveExtendedWindowState(): WindowState {
        if (fullscreenFlag) return WindowState.FULLSCREEN
        val ext = jFrame?.extendedState ?: return windowState
        return when {
            (ext and JFrame.ICONIFIED) != 0 -> WindowState.MINIMIZED
            (ext and JFrame.MAXIMIZED_BOTH) != 0 -> WindowState.MAXIMIZED
            pipActive -> WindowState.PICTURE_IN_PICTURE
            windowState == WindowState.MINIMIZED || windowState == WindowState.MAXIMIZED || windowState == WindowState.FULLSCREEN -> WindowState.NORMAL
            else -> windowState
        }
    }

    private fun syncWindowStateFromExtendedState(extendedState: Int) {
        if (fullscreenFlag) return
        val isMaximizedNow = (extendedState and JFrame.MAXIMIZED_BOTH) != 0
        val isIconifiedNow = (extendedState and JFrame.ICONIFIED) != 0
        val prevState = windowState
        windowState = when {
            isIconifiedNow -> WindowState.MINIMIZED
            isMaximizedNow -> WindowState.MAXIMIZED
            pipActive -> WindowState.PICTURE_IN_PICTURE
            else -> WindowState.NORMAL
        }
        if (prevState == WindowState.MAXIMIZED && windowState == WindowState.NORMAL && aspectRatioValue != null) {
            val frame = jFrame ?: return
            handleHostFrameResized(
                outerWidth = frame.width,
                outerHeight = frame.height,
                insetsLeft = frame.insets.left,
                insetsTop = frame.insets.top,
                insetsRight = frame.insets.right,
                insetsBottom = frame.insets.bottom,
                extendedState = extendedState
            )
        }
    }

    internal fun handleHostFrameResized(
        outerWidth: Int,
        outerHeight: Int,
        insetsLeft: Int,
        insetsTop: Int,
        insetsRight: Int,
        insetsBottom: Int,
        extendedState: Int
    ): Size {
        if (isApplyingSwingAspectClamp) {
            return windowSize
        }
        val isMaximizedNow = (extendedState and JFrame.MAXIMIZED_BOTH) != 0
        val isIconifiedNow = (extendedState and JFrame.ICONIFIED) != 0
        if (!fullscreenFlag) {
            windowState = when {
                isIconifiedNow -> WindowState.MINIMIZED
                isMaximizedNow -> WindowState.MAXIMIZED
                pipActive -> WindowState.PICTURE_IN_PICTURE
                else -> WindowState.NORMAL
            }
        }
        val insetW = (insetsLeft + insetsRight).coerceAtLeast(0)
        val insetH = (insetsTop + insetsBottom).coerceAtLeast(0)
        val clientW = (outerWidth - insetW).coerceAtLeast(1)
        val clientH = (outerHeight - insetH).coerceAtLeast(1)
        val incomingClient = Size(clientW, clientH)

        val ratio = aspectRatioValue
        val finalClient = if (
            windowState == WindowState.NORMAL &&
            !fullscreenFlag &&
            !isMaximizedNow &&
            ratio != null &&
            !WindowAspectRatioHelper.matchesAspectRatio(incomingClient, ratio, tolerancePx = 2)
        ) {
            val clamped = WindowAspectRatioHelper.enforceResizeDelta(
                previousSize = windowSize,
                requestedSize = incomingClient,
                aspectRatio = ratio,
                minSize = minWindowSize
            )
            if (clamped != incomingClient) {
                isApplyingSwingAspectClamp = true
                try {
                    jFrame?.setSize(clamped.width + insetW, clamped.height + insetH)
                } finally {
                    isApplyingSwingAspectClamp = false
                }
            }
            clamped
        } else {
            incomingClient
        }

        isSyncingFromComponentResize = true
        try {
            windowSize = finalClient
            nativeWidget.setBounds(0, 0, finalClient.width, finalClient.height)
            onResizeListener?.invoke(
                WindowConfiguration(
                    widthPx = finalClient.width,
                    heightPx = finalClient.height,
                    windowState = windowState,
                    isFullscreen = fullscreenFlag,
                    isInPictureInPicture = pipActive
                )
            )
        } finally {
            isSyncingFromComponentResize = false
        }
        return finalClient
    }

    private var menuBarImpl: MenuBarNativeImpl<*>? = null
    private var navBarImpl: NativeImpl<*>? = null
    private var bottomNavImpl: NativeImpl<*>? = null

    private var onResizeListener: ((WindowConfiguration) -> Unit)? = null
    private var onFocusListener: ((Boolean) -> Unit)? = null
    private var onCloseRequestedListener: (() -> Boolean)? = null

    internal fun registerOwnedToolWindow(toolWindow: SwingWindowImpl) {
        if (toolWindow !== this && toolWindow !in ownedToolWindows) {
            ownedToolWindows.add(toolWindow)
        }
    }

    internal fun unregisterOwnedToolWindow(toolWindow: SwingWindowImpl) {
        ownedToolWindows.remove(toolWindow)
    }

    internal fun registerOwnedDockWindow(dockWindow: SwingDockWindowImpl) {
        if (dockWindow !in ownedDockWindows) {
            ownedDockWindows.add(dockWindow)
        }
    }

    internal fun unregisterOwnedDockWindow(dockWindow: SwingDockWindowImpl) {
        ownedDockWindows.remove(dockWindow)
    }

    internal fun registerOwnedDialogWindow(dialogWindow: SwingDialogWindowImpl) {
        if (dialogWindow !in _ownedDialogWindows) {
            _ownedDialogWindows.add(dialogWindow)
        }
    }

    internal fun unregisterOwnedDialogWindow(dialogWindow: SwingDialogWindowImpl) {
        _ownedDialogWindows.remove(dialogWindow)
    }

    override fun configureAsToolModalWindow(
        ownerWindow: WindowNativeImpl<*>?,
        showInTaskbar: Boolean,
        minimizable: Boolean,
        alwaysOnTopOfOwner: Boolean
    ) {
        (this.ownerWindow as? SwingWindowImpl)?.unregisterOwnedToolWindow(this)
        this.ownerWindow = ownerWindow
        this.isToolModalWindow = true
        this.showInTaskbar = showInTaskbar
        this.minimizable = minimizable
        this.alwaysOnTopOfOwner = alwaysOnTopOfOwner

        nativeWidget.putClientProperty("tool-modal", "true")
        nativeWidget.putClientProperty("show-in-taskbar", showInTaskbar.toString())
        nativeWidget.putClientProperty("minimizable", minimizable.toString())
        nativeWidget.putClientProperty("always-on-top-of-owner", alwaysOnTopOfOwner.toString())
        nativeWidget.putClientProperty("ownerWindow", ownerWindow)

        runOnEdt {
            try {
                if (!showInTaskbar && jFrame != null && !jFrame.isDisplayable) {
                    jFrame.type = Window.Type.UTILITY
                }
                applySwingAlwaysOnTop()
            } catch (_: Throwable) {
            }
        }

        (ownerWindow as? SwingWindowImpl)?.registerOwnedToolWindow(this)
        bringToFront()
    }

    override fun bringToFront() {
        zOrderLayer = nextZOrderSequence()
        nativeWidget.putClientProperty("zOrderLayer", zOrderLayer)
        nativeWidget.putClientProperty("broughtToFront", true)
        try {
            if (jFrame?.isVisible == true) {
                jFrame.toFront()
            }
        } catch (_: Throwable) {
        }
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
        titleText = title
        jFrame?.title = title
    }

    override fun getTitle(): String = titleText

    override fun setAlwaysOnTop(alwaysOnTop: Boolean) {
        alwaysOnTopFlag = alwaysOnTop
        nativeWidget.putClientProperty("alwaysOnTop", alwaysOnTop)
        runOnEdt {
            applySwingAlwaysOnTop()
        }
    }

    override fun isAlwaysOnTop(): Boolean = alwaysOnTopFlag

    override fun setAspectRatio(aspectRatio: Double?) {
        WindowAspectRatioHelper.validateAspectRatio(aspectRatio)
        aspectRatioValue = aspectRatio
        if (aspectRatio != null) {
            nativeWidget.putClientProperty("aspectRatio", aspectRatio)
        } else {
            nativeWidget.putClientProperty("aspectRatio", null)
        }
    }

    override fun getAspectRatio(): Double? = aspectRatioValue

    override fun getMinWindowSize(): Size? = minWindowSize

    override fun setMinWindowSize(minSize: Size?) {
        minWindowSize = minSize
        if (minSize != null) {
            nativeWidget.putClientProperty("minWidthPx", minSize.width)
            nativeWidget.putClientProperty("minHeightPx", minSize.height)
        } else {
            nativeWidget.putClientProperty("minWidthPx", null)
            nativeWidget.putClientProperty("minHeightPx", null)
        }
        runOnEdt {
            val frame = jFrame ?: return@runOnEdt
            val insets = frame.insets
            frame.minimumSize = minSize?.let {
                Dimension(it.width + insets.left + insets.right, it.height + insets.top + insets.bottom)
            }
        }
        if (minSize != null) {
            val cur = getWindowSize()
            if (cur.width < minSize.width || cur.height < minSize.height) {
                setWindowSize(Size(maxOf(cur.width, minSize.width), maxOf(cur.height, minSize.height)))
            }
        }
    }

    override fun setWindowSize(size: Size) {
        val minSize = minWindowSize
        val minClamped = if (minSize != null) {
            Size(maxOf(size.width, minSize.width), maxOf(size.height, minSize.height))
        } else {
            size
        }
        val effectiveState = resolveExtendedWindowState()
        val ratio = aspectRatioValue
        val clamped = if (ratio != null && effectiveState == WindowState.NORMAL && !fullscreenFlag) {
            WindowAspectRatioHelper.clampClientSize(minClamped, ratio, minSize)
        } else {
            minClamped
        }
        windowSize = clamped
        nativeWidget.setBounds(0, 0, clamped.width, clamped.height)
        if (!isSyncingFromComponentResize) {
            runOnEdt {
                val frame = jFrame ?: return@runOnEdt
                val insets = frame.insets
                val targetOuterW = clamped.width + insets.left + insets.right
                val targetOuterH = clamped.height + insets.top + insets.bottom
                if (frame.width != targetOuterW || frame.height != targetOuterH) {
                    isApplyingSwingAspectClamp = true
                    try {
                        frame.setSize(targetOuterW, targetOuterH)
                    } finally {
                        isApplyingSwingAspectClamp = false
                    }
                }
            }
        }
        onResizeListener?.invoke(
            WindowConfiguration(
                widthPx = clamped.width,
                heightPx = clamped.height,
                windowState = effectiveState,
                isFullscreen = fullscreenFlag,
                isInPictureInPicture = pipActive
            )
        )
    }

    override fun getWindowSize(): Size = windowSize

    override fun setWindowState(state: WindowState) {
        val prevState = windowState
        windowState = state
        fullscreenFlag = (state == WindowState.FULLSCREEN)
        runOnEdt {
            jFrame?.extendedState = when (state) {
                WindowState.MINIMIZED -> JFrame.ICONIFIED
                WindowState.MAXIMIZED -> JFrame.MAXIMIZED_BOTH
                else -> JFrame.NORMAL
            }
        }
        if (prevState == WindowState.MAXIMIZED && state == WindowState.NORMAL && aspectRatioValue != null) {
            setWindowSize(windowSize)
        }
    }

    override fun getWindowState(): WindowState = resolveExtendedWindowState()

    private var fullscreenElementImpl: NativeImpl<*>? = null

    override fun setFullscreen(fullscreen: Boolean, cutoutMode: DisplayCutoutMode) {
        fullscreenFlag = fullscreen
        if (!fullscreen) {
            fullscreenElementImpl = null
            nativeWidget.putClientProperty("fullscreenElement", null)
            if (windowState == WindowState.FULLSCREEN) {
                windowState = resolveExtendedWindowState()
            }
            if (windowState == WindowState.NORMAL && aspectRatioValue != null) {
                setWindowSize(windowSize)
            }
        } else {
            windowState = WindowState.FULLSCREEN
        }
    }

    override fun isFullscreen(): Boolean = fullscreenFlag

    override fun setFullscreenElement(elementImpl: NativeImpl<*>?, cutoutMode: DisplayCutoutMode) {
        fullscreenElementImpl = elementImpl
        nativeWidget.putClientProperty("fullscreenElement", elementImpl?.nativeWidget)
        setFullscreen(elementImpl != null, cutoutMode)
    }

    override fun getFullscreenElement(): NativeImpl<*>? = fullscreenElementImpl

    override fun applyTitleBarInstructions(titleBar: TitleBar?) {
        activeTitleBar?.setHostEmbedsComponents(false)
        activeTitleBar = titleBar
        if (titleBar != null) {
            titleBar.setHostEmbedsComponents(getTitleCapabilities().canEmbedComponentsInTitleBar)
            if (!isToolModalWindow) {
                minimizable = titleBar.showMinimizeButton
            }
            nativeWidget.putClientProperty(
                "minimizable",
                (if (isToolModalWindow) minimizable else titleBar.showMinimizeButton).toString()
            )
            nativeWidget.putClientProperty("maximizable", titleBar.showMaximizeButton.toString())
            nativeWidget.putClientProperty("closable", titleBar.showCloseButton.toString())
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
        supportsSystemAeroOrBackdrop = false,
        isToolWindowTitleBar = false,
        isTitleBarRequired = true,
        titleBarRendersAsNavbar = false,
        menuBarRendersAsNavbar = false,
        menuBarMergesIntoTitleNavbar = false
    )

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
        runOnEdt { applySwingAlwaysOnTop() }
        return true
    }

    override fun exitPictureInPicture() {
        pipActive = false
        runOnEdt { applySwingAlwaysOnTop() }
    }

    override fun isInPictureInPicture(): Boolean = pipActive

    override fun setVisible(visible: Boolean) {
        super.setVisible(visible)
        runOnEdt { jFrame?.isVisible = visible }
    }

    override fun showWindow() {
        if (!isVisible()) {
            runOnEdt { jFrame?.isVisible = false }
            return
        }
        setVisible(true)
        runOnEdt {
            val frame = jFrame
            if (frame != null) {
                applySwingAlwaysOnTop()
                frame.isVisible = true
                if (!fullscreenFlag && getWindowState() == WindowState.NORMAL) {
                    val insets = frame.insets
                    val insetW = insets.left + insets.right
                    val insetH = insets.top + insets.bottom
                    minWindowSize?.let { min ->
                        frame.minimumSize = Dimension(min.width + insetW, min.height + insetH)
                    }
                    val targetW = windowSize.width + insetW
                    val targetH = windowSize.height + insetH
                    if (frame.width != targetW || frame.height != targetH) {
                        isApplyingSwingAspectClamp = true
                        try {
                            frame.setSize(targetW, targetH)
                        } finally {
                            isApplyingSwingAspectClamp = false
                        }
                    }
                }
            }
        }
        bringToFront()
        onFocusListener?.invoke(true)
    }

    override fun closeWindow() {
        val allow = onCloseRequestedListener?.invoke() ?: true
        if (allow) {
            setVisible(false)
            jFrame?.isVisible = false
            jFrame?.dispose()
            (ownerWindow as? SwingWindowImpl)?.unregisterOwnedToolWindow(this)
            ownerWindow = null
            ownedToolWindows.clear()
            ownedDockWindows.clear()
            for (dialogWin in _ownedDialogWindows.toList()) {
                dialogWin.closeDialog()
            }
            _ownedDialogWindows.clear()
        }
    }

    override fun getBounds(): Rect = Rect(0, 0, windowSize.width, windowSize.height)

    override fun setOnWindowResize(listener: ((WindowConfiguration) -> Unit)?) { onResizeListener = listener }
    override fun setOnWindowFocusChanged(listener: ((Boolean) -> Unit)?) { onFocusListener = listener }
    override fun setOnWindowCloseRequested(listener: (() -> Boolean)?) { onCloseRequestedListener = listener }

    companion object {
        private var zOrderCounter: Int = 0

        internal fun nextZOrderSequence(): Int = ++zOrderCounter
    }
}
