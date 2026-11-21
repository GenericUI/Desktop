package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.window.DisplayCutoutMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.geometry.Point
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.shell.MenuBarNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.window.TitleBar
import com.nexustools.genericui.window.TitleCapabilities
import com.nexustools.genericui.window.WindowAspectRatioHelper
import com.nexustools.genericui.window.WindowConfiguration
import java.awt.AWTEvent
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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

            override fun componentMoved(e: ComponentEvent?) {
                val frame = jFrame ?: return
                if (!frame.isVisible || fullscreenFlag || getWindowState() != WindowState.NORMAL) return
                val nx = frame.x
                val ny = frame.y
                if (!hasExplicitPosition || positionX != nx || positionY != ny) {
                    positionX = nx
                    positionY = ny
                    hasExplicitPosition = true
                    onMovedListener?.invoke(nx, ny)
                }
            }
        })
        jFrame?.addWindowStateListener(WindowStateListener { e ->
            val frame = jFrame ?: return@WindowStateListener
            val ext = e?.newState ?: frame.extendedState
            syncWindowStateFromExtendedState(ext)
        })
        installUndecoratedDragAndResizeListeners()
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
    private var positionX: Int = 0
    private var positionY: Int = 0
    private var hasExplicitPosition: Boolean = false
    private var windowSize: Size = Size(1280, 800)
    private var minWindowSize: Size? = null
    private var alwaysOnTopFlag: Boolean = false
    private var aspectRatioValue: Double? = null
    private var isApplyingSwingAspectClamp: Boolean = false
    private var isSyncingFromComponentResize: Boolean = false
    private var windowState: WindowState = WindowState.NORMAL
    private var fullscreenFlag: Boolean = false
    private var undecoratedFlag: Boolean = false
    private var activeTitleBar: TitleBar? = null
    private var onMovedListener: ((Int, Int) -> Unit)? = null
    private var onWindowStateChangedListener: ((WindowState) -> Unit)? = null
    private var dragMoveLatchActive: Boolean = false
    private var dragMoveStartScreenX: Int = 0
    private var dragMoveStartScreenY: Int = 0
    private var dragMoveStartWinX: Int = 0
    private var dragMoveStartWinY: Int = 0
    private var resizeEdgeMask: Int = 0
    private var resizeStartScreenX: Int = 0
    private var resizeStartScreenY: Int = 0
    private var resizeStartBounds: Rectangle? = null
    private var activeResizeCursorSet: Boolean = false
    private var awtMouseDragListener: AWTEventListener? = null
    private var lastHandledMouseEvent: MouseEvent? = null

    private fun installUndecoratedDragAndResizeListeners() {
        val directAdapter = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) = handleUndecoratedMouseEvent(e)
            override fun mouseReleased(e: MouseEvent) = handleUndecoratedMouseEvent(e)
            override fun mouseDragged(e: MouseEvent) = handleUndecoratedMouseEvent(e)
            override fun mouseMoved(e: MouseEvent) = handleUndecoratedMouseEvent(e)
            override fun mouseExited(e: MouseEvent) = handleUndecoratedMouseEvent(e)
        }
        nativeWidget.addMouseListener(directAdapter)
        nativeWidget.addMouseMotionListener(directAdapter)

        val frame = jFrame ?: return
        runCatching {
            val listener = AWTEventListener { event ->
                val me = event as? MouseEvent ?: return@AWTEventListener
                val src = me.source as? Component ?: return@AWTEventListener
                if (src !== frame && SwingUtilities.getWindowAncestor(src) !== frame) {
                    return@AWTEventListener
                }
                handleUndecoratedMouseEvent(me)
            }
            Toolkit.getDefaultToolkit().addAWTEventListener(
                listener,
                AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK
            )
            awtMouseDragListener = listener
        }
    }

    private fun computeResizeEdgeMask(frame: JFrame, screenX: Int, screenY: Int): Int {
        val fx = screenX - frame.x
        val fy = screenY - frame.y
        val w = frame.width
        val h = frame.height
        if (w <= 0 || h <= 0 || fx !in 0 until w || fy !in 0 until h) return 0
        var mask = 0
        if (fx < UNDECORATED_RESIZE_BORDER_PX) mask = mask or EDGE_LEFT
        if (fx >= w - UNDECORATED_RESIZE_BORDER_PX) mask = mask or EDGE_RIGHT
        if (fy < UNDECORATED_RESIZE_BORDER_PX) mask = mask or EDGE_TOP
        if (fy >= h - UNDECORATED_RESIZE_BORDER_PX) mask = mask or EDGE_BOTTOM
        return mask
    }

    private fun updateUndecoratedResizeCursor(frame: JFrame, edgeMask: Int) {
        if (edgeMask == 0) {
            if (activeResizeCursorSet) {
                activeResizeCursorSet = false
                val defaultCursor = Cursor.getDefaultCursor()
                frame.cursor = defaultCursor
                nativeWidget.cursor = defaultCursor
            }
            return
        }
        val cursorType = when (edgeMask) {
            EDGE_TOP or EDGE_LEFT -> Cursor.NW_RESIZE_CURSOR
            EDGE_TOP or EDGE_RIGHT -> Cursor.NE_RESIZE_CURSOR
            EDGE_BOTTOM or EDGE_LEFT -> Cursor.SW_RESIZE_CURSOR
            EDGE_BOTTOM or EDGE_RIGHT -> Cursor.SE_RESIZE_CURSOR
            EDGE_LEFT -> Cursor.W_RESIZE_CURSOR
            EDGE_RIGHT -> Cursor.E_RESIZE_CURSOR
            EDGE_TOP -> Cursor.N_RESIZE_CURSOR
            EDGE_BOTTOM -> Cursor.S_RESIZE_CURSOR
            else -> Cursor.DEFAULT_CURSOR
        }
        val targetCursor = Cursor.getPredefinedCursor(cursorType)
        activeResizeCursorSet = true
        frame.cursor = targetCursor
        nativeWidget.cursor = targetCursor
    }

    private fun performUndecoratedEdgeResize(frame: JFrame, me: MouseEvent) {
        val startBounds = resizeStartBounds ?: return
        val dx = me.xOnScreen - resizeStartScreenX
        val dy = me.yOnScreen - resizeStartScreenY
        val insets = frame.insets
        val insetW = (insets.left + insets.right).coerceAtLeast(0)
        val insetH = (insets.top + insets.bottom).coerceAtLeast(0)
        val sx = startBounds.x
        val sy = startBounds.y
        val sw = startBounds.width
        val sh = startBounds.height

        var left = if ((resizeEdgeMask and EDGE_LEFT) != 0) sx + dx else sx
        var right = if ((resizeEdgeMask and EDGE_RIGHT) != 0) sx + sw + dx else sx + sw
        var top = if ((resizeEdgeMask and EDGE_TOP) != 0) sy + dy else sy
        var bottom = if ((resizeEdgeMask and EDGE_BOTTOM) != 0) sy + sh + dy else sy + sh

        val minClientW = maxOf(minWindowSize?.width ?: 120, 120)
        val minClientH = maxOf(minWindowSize?.height ?: 80, 80)
        val minOuterW = minClientW + insetW
        val minOuterH = minClientH + insetH

        if (right - left < minOuterW) {
            if ((resizeEdgeMask and EDGE_LEFT) != 0) left = right - minOuterW else right = left + minOuterW
        }
        if (bottom - top < minOuterH) {
            if ((resizeEdgeMask and EDGE_TOP) != 0) top = bottom - minOuterH else bottom = top + minOuterH
        }

        val ratio = aspectRatioValue
        if (ratio != null && ratio.isFinite() && ratio > 0.0) {
            val propClientW = (right - left - insetW).coerceAtLeast(1)
            val propClientH = (bottom - top - insetH).coerceAtLeast(1)
            val minClientSize = Size(minClientW, minClientH)
            val hasH = (resizeEdgeMask and (EDGE_LEFT or EDGE_RIGHT)) != 0
            val hasV = (resizeEdgeMask and (EDGE_TOP or EDGE_BOTTOM)) != 0
            val resolvedClient = when {
                hasH && !hasV -> WindowAspectRatioHelper.clampClientSize(
                    size = Size(propClientW, propClientH),
                    aspectRatio = ratio,
                    minSize = minClientSize,
                    preferWidth = true
                )
                !hasH && hasV -> WindowAspectRatioHelper.clampClientSize(
                    size = Size(propClientW, propClientH),
                    aspectRatio = ratio,
                    minSize = minClientSize,
                    preferWidth = false
                )
                else -> WindowAspectRatioHelper.enforceResizeDelta(
                    previousSize = Size((sw - insetW).coerceAtLeast(1), (sh - insetH).coerceAtLeast(1)),
                    requestedSize = Size(propClientW, propClientH),
                    aspectRatio = ratio,
                    minSize = minClientSize
                )
            }
            val targetW = resolvedClient.width + insetW
            val targetH = resolvedClient.height + insetH
            if ((resizeEdgeMask and EDGE_LEFT) != 0) left = right - targetW else right = left + targetW
            if ((resizeEdgeMask and EDGE_TOP) != 0) top = bottom - targetH else bottom = top + targetH
        }

        val finalW = (right - left).coerceAtLeast(minOuterW)
        val finalH = (bottom - top).coerceAtLeast(minOuterH)
        val finalClient = Size((finalW - insetW).coerceAtLeast(1), (finalH - insetH).coerceAtLeast(1))
        val moved = positionX != left || positionY != top || !hasExplicitPosition
        positionX = left
        positionY = top
        hasExplicitPosition = true
        windowSize = finalClient

        isApplyingSwingAspectClamp = true
        try {
            frame.setBounds(left, top, finalW, finalH)
            nativeWidget.setBounds(0, 0, finalClient.width, finalClient.height)
        } finally {
            isApplyingSwingAspectClamp = false
        }
        if (moved) {
            onMovedListener?.invoke(left, top)
        }
        onResizeListener?.invoke(
            WindowConfiguration(
                widthPx = finalClient.width,
                heightPx = finalClient.height,
                windowState = windowState,
                isFullscreen = fullscreenFlag
            )
        )
    }

    private fun handleUndecoratedMouseEvent(me: MouseEvent) {
        if (me === lastHandledMouseEvent) return
        lastHandledMouseEvent = me
        val frame = jFrame ?: return
        if (!frame.isVisible) {
            if (me.id == MouseEvent.MOUSE_RELEASED) {
                dragMoveLatchActive = false
                resizeEdgeMask = 0
                resizeStartBounds = null
            }
            return
        }
        val normalWindowed = !fullscreenFlag && getWindowState() == WindowState.NORMAL
        when (me.id) {
            MouseEvent.MOUSE_MOVED -> {
                if (undecoratedFlag && normalWindowed && resizeEdgeMask == 0 && !dragMoveLatchActive) {
                    updateUndecoratedResizeCursor(frame, computeResizeEdgeMask(frame, me.xOnScreen, me.yOnScreen))
                } else if (activeResizeCursorSet && resizeEdgeMask == 0) {
                    updateUndecoratedResizeCursor(frame, 0)
                }
            }
            MouseEvent.MOUSE_EXITED -> {
                if (activeResizeCursorSet && resizeEdgeMask == 0) {
                    updateUndecoratedResizeCursor(frame, 0)
                }
            }
            MouseEvent.MOUSE_PRESSED -> {
                if (!SwingUtilities.isLeftMouseButton(me) || !undecoratedFlag || !normalWindowed) return
                val edgeMask = computeResizeEdgeMask(frame, me.xOnScreen, me.yOnScreen)
                if (edgeMask != 0) {
                    dragMoveLatchActive = false
                    resizeEdgeMask = edgeMask
                    resizeStartScreenX = me.xOnScreen
                    resizeStartScreenY = me.yOnScreen
                    resizeStartBounds = frame.bounds
                    updateUndecoratedResizeCursor(frame, edgeMask)
                    me.consume()
                } else if (me.component === nativeWidget || me.component === frame || me.component === frame.contentPane) {
                    resizeEdgeMask = 0
                    dragMoveLatchActive = true
                    dragMoveStartScreenX = me.xOnScreen
                    dragMoveStartScreenY = me.yOnScreen
                    dragMoveStartWinX = frame.x
                    dragMoveStartWinY = frame.y
                }
            }
            MouseEvent.MOUSE_DRAGGED -> {
                if (!normalWindowed) return
                if (dragMoveLatchActive) {
                    val nx = dragMoveStartWinX + (me.xOnScreen - dragMoveStartScreenX)
                    val ny = dragMoveStartWinY + (me.yOnScreen - dragMoveStartScreenY)
                    if (frame.x != nx || frame.y != ny) {
                        frame.setLocation(nx, ny)
                    }
                    if (!hasExplicitPosition || positionX != nx || positionY != ny) {
                        positionX = nx
                        positionY = ny
                        hasExplicitPosition = true
                        onMovedListener?.invoke(nx, ny)
                    }
                } else if (resizeEdgeMask != 0 && undecoratedFlag) {
                    performUndecoratedEdgeResize(frame, me)
                }
            }
            MouseEvent.MOUSE_RELEASED -> {
                dragMoveLatchActive = false
                resizeEdgeMask = 0
                resizeStartBounds = null
                if (undecoratedFlag && normalWindowed) {
                    updateUndecoratedResizeCursor(frame, computeResizeEdgeMask(frame, me.xOnScreen, me.yOnScreen))
                } else {
                    updateUndecoratedResizeCursor(frame, 0)
                }
            }
        }
    }

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
        val effective = alwaysOnTopFlag || (isToolModalWindow && alwaysOnTopOfOwner)
        runCatching { jFrame?.isAlwaysOnTop = effective }
    }

    private fun applySwingUndecorated(undecorated: Boolean) {
        val frame = jFrame ?: return
        if (frame.isUndecorated == undecorated) return
        runCatching {
            if (frame.isDisplayable) {
                val wasVisible = frame.isVisible
                frame.dispose()
                frame.isUndecorated = undecorated
                applySwingAlwaysOnTop()
                if (wasVisible) {
                    frame.isVisible = true
                }
            } else {
                frame.isUndecorated = undecorated
            }
        }
    }

    private fun resolveExtendedWindowState(): WindowState {
        if (fullscreenFlag) return WindowState.FULLSCREEN
        val ext = jFrame?.extendedState ?: return windowState
        return when {
            (ext and JFrame.ICONIFIED) != 0 -> WindowState.MINIMIZED
            (ext and JFrame.MAXIMIZED_BOTH) != 0 -> WindowState.MAXIMIZED
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
            else -> WindowState.NORMAL
        }
        if (prevState == WindowState.MAXIMIZED && windowState == WindowState.NORMAL && aspectRatioValue != null) {
            val frame = jFrame
            if (frame != null) {
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
        if (prevState != windowState) {
            onWindowStateChangedListener?.invoke(windowState)
            onResizeListener?.invoke(
                WindowConfiguration(
                    widthPx = windowSize.width,
                    heightPx = windowSize.height,
                    windowState = windowState,
                    isFullscreen = fullscreenFlag
                )
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
        val prevState = windowState
        if (!fullscreenFlag) {
            windowState = when {
                isIconifiedNow -> WindowState.MINIMIZED
                isMaximizedNow -> WindowState.MAXIMIZED
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
            if (prevState != windowState) {
                onWindowStateChangedListener?.invoke(windowState)
            }
            onResizeListener?.invoke(
                WindowConfiguration(
                    widthPx = finalClient.width,
                    heightPx = finalClient.height,
                    windowState = windowState,
                    isFullscreen = fullscreenFlag
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
                isFullscreen = fullscreenFlag
            )
        )
    }

    override fun getWindowSize(): Size = windowSize

    override fun setWindowPosition(x: Int, y: Int) {
        positionX = x
        positionY = y
        hasExplicitPosition = true
        runOnEdt { jFrame?.setLocation(x, y) }
    }

    override fun getWindowPosition(): Point = Point(positionX.toFloat(), positionY.toFloat())

    override fun hasExplicitWindowPosition(): Boolean = hasExplicitPosition

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
        if (prevState != state) {
            onWindowStateChangedListener?.invoke(state)
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

    override fun isUndecorated(): Boolean = undecoratedFlag

    override fun setUndecorated(undecorated: Boolean) {
        undecoratedFlag = undecorated
        nativeWidget.putClientProperty("undecorated", undecorated)
        runOnEdt {
            applySwingUndecorated(undecorated)
        }
    }

    override fun startSystemMove() {
        if (GraphicsEnvironment.isHeadless()) return
        runOnEdt {
            val frame = jFrame ?: return@runOnEdt
            if (!frame.isVisible || fullscreenFlag || getWindowState() != WindowState.NORMAL) return@runOnEdt
            val ptr = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: return@runOnEdt
            resizeEdgeMask = 0
            dragMoveLatchActive = true
            dragMoveStartScreenX = ptr.x
            dragMoveStartScreenY = ptr.y
            dragMoveStartWinX = frame.x
            dragMoveStartWinY = frame.y
        }
    }

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
                applySwingUndecorated(undecoratedFlag)
                applySwingAlwaysOnTop()
                frame.isVisible = true
                if (!fullscreenFlag && getWindowState() == WindowState.NORMAL) {
                    if (hasExplicitPosition) {
                        frame.setLocation(positionX, positionY)
                    }
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
            awtMouseDragListener?.let { listener ->
                awtMouseDragListener = null
                runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
            }
            dragMoveLatchActive = false
            resizeEdgeMask = 0
            resizeStartBounds = null
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
    override fun setOnWindowStateChanged(listener: ((WindowState) -> Unit)?) { onWindowStateChangedListener = listener }
    override fun setOnWindowMoved(listener: ((Int, Int) -> Unit)?) { onMovedListener = listener }
    override fun setOnWindowFocusChanged(listener: ((Boolean) -> Unit)?) { onFocusListener = listener }
    override fun setOnWindowCloseRequested(listener: (() -> Boolean)?) { onCloseRequestedListener = listener }

    companion object {
        private const val UNDECORATED_RESIZE_BORDER_PX: Int = 6
        private const val EDGE_LEFT: Int = 1
        private const val EDGE_RIGHT: Int = 2
        private const val EDGE_TOP: Int = 4
        private const val EDGE_BOTTOM: Int = 8

        private var zOrderCounter: Int = 0

        internal fun nextZOrderSequence(): Int = ++zOrderCounter
    }
}
