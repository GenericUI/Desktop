package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.enums.window.DisplayCutoutMode
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.event.PointerDragKind
import com.nexustools.genericui.event.PointerDragManager
import com.nexustools.genericui.event.PointerDragSession
import com.nexustools.genericui.geometry.Point
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.nativeimpl.shell.MenuBarNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
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
import java.awt.Taskbar
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowStateListener
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JMenuBar
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlinx.coroutines.Job

/**
 * Java Swing `JFrame` + root `JPanel` implementation of [WindowNativeImpl].
 *
 * Safe to instantiate in both desktop AWT environments and headless CI test environments
 * (`GraphicsEnvironment.isHeadless()`).
 */
class SwingWindowImpl : SwingBaseContainerNativeImpl(JPanel(BorderLayout()), "window"), WindowNativeImpl<JComponent> {
    override val supportsCustomTitleBar: Boolean = false
    override val supportsEmbeddedMenuBarInTitleBar: Boolean = false
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

    private val centerContentHost: JPanel = object : SwingContainerImpl.SwingLayoutPanel() {
        override fun isOptimizedDrawingEnabled(): Boolean = componentCount <= 1
    }.apply {
        layout = CenterWindowContentLayoutManager()
        isOpaque = false
        putClientProperty("genericuiWindowRootHost", true)
    }

    init {
        nativeWidget.add(centerContentHost, BorderLayout.CENTER)
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
        jFrame?.addWindowListener(object : WindowAdapter() {
            override fun windowIconified(e: WindowEvent?) {
                val frame = jFrame ?: return
                if (frame.isUndecorated && alwaysOnTopFlag && frame.isAlwaysOnTop) {
                    runCatching { frame.isAlwaysOnTop = false }
                }
            }

            override fun windowDeiconified(e: WindowEvent?) {
                val frame = jFrame ?: return
                if (alwaysOnTopFlag) {
                    runCatching { frame.isAlwaysOnTop = true }
                } else if (isToolModalWindow && alwaysOnTopOfOwner) {
                    runCatching { frame.isAlwaysOnTop = true }
                }
                SwingAnimationNativeImpl.registerWindow(frame)
            }

            override fun windowActivated(e: WindowEvent?) {
                jFrame?.let { SwingAnimationNativeImpl.registerWindow(it) }
            }
        })
        installUndecoratedDragAndResizeListeners()
    }

    private fun loadDefaultWindowIcon(): java.awt.Image? =
        resolveWindowIconImageSync(defaultWindowIconPath)

    private var windowIconPathState: String? = null
    private var windowIconLoadJob: Job? = null

    fun getWindowIconPath(): String? = windowIconPathState

    override fun setWindowIcon(iconPathOrSvg: String?) {
        windowIconLoadJob?.cancel()
        windowIconLoadJob = null
        val normalized = iconPathOrSvg?.trim()?.takeIf { it.isNotEmpty() }
        windowIconPathState = normalized
        val effectiveSpec = normalized ?: defaultWindowIconPath
        nativeWidget.putClientProperty("windowIcon", effectiveSpec)

        val syncImage = resolveWindowIconImageSync(effectiveSpec)
        if (syncImage != null) {
            applyAwtWindowAndTaskbarIcon(syncImage)
            return
        }

        val isRemote = effectiveSpec.startsWith("http://", ignoreCase = true) ||
            effectiveSpec.startsWith("https://", ignoreCase = true)
        if (isRemote || effectiveSpec.contains("://")) {
            windowIconLoadJob = ImageLoader.launchLoad(
                source = ImageSource.fromUrl(effectiveSpec),
                options = ImageDecodeOptions(targetWidth = 128, targetHeight = 128, antialiasing = true),
                onLoaded = { buf ->
                    val awtImg = SwingControlTintRenderUtil.imageBufferToBufferedImage(buf)
                    applyAwtWindowAndTaskbarIcon(awtImg)
                },
                onError = {}
            )
        }
    }

    private fun applyAwtWindowAndTaskbarIcon(image: java.awt.Image) {
        nativeWidget.putClientProperty("windowIconLoaded", true)
        nativeWidget.putClientProperty("windowIconImage", image)
        runOnEdt {
            jFrame?.iconImage = image
            if (!GraphicsEnvironment.isHeadless()) {
                runCatching {
                    if (Taskbar.isTaskbarSupported()) {
                        val taskbar = Taskbar.getTaskbar()
                        if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                            taskbar.iconImage = image
                        }
                    }
                }
            }
        }
    }

    private fun resolveWindowIconImageSync(spec: String): java.awt.Image? {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        val opts = ImageDecodeOptions(targetWidth = 128, targetHeight = 128, antialiasing = true)
        val codecDecoded = runCatching {
            val active = ImageLoader.activeNativeCodec
            if (active != null) {
                ImageLoader.load(ImageSource.fromUrl(trimmed), opts)
            } else {
                val codec = SwingImageIoCodecImpl()
                ImageLoader.activeNativeCodec = codec
                codec.decode(ImageSource.fromUrl(trimmed), opts)
                    ?: ImageLoader.load(ImageSource.fromUrl(trimmed), opts)
            }
        }.getOrNull()
        if (codecDecoded != null && codecDecoded.width > 1 && codecDecoded.height > 1) {
            return SwingControlTintRenderUtil.imageBufferToBufferedImage(codecDecoded)
        }
        return try {
            val localFile = java.io.File(trimmed)
            if (localFile.isFile) {
                javax.imageio.ImageIO.read(localFile)
            } else {
                val clean = trimmed.removePrefix("res://").trimStart('/')
                val stream = SwingWindowImpl::class.java.getResourceAsStream("/$clean")
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
    private var pendingInitialClientSize: Size? = null
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
    private var activeWindowDragSession: PointerDragSession? = null
    private var cursorLockListener: ((Boolean, CssCursorValue?, PointerDragKind?) -> Unit)? = null

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

        if (cursorLockListener == null) {
            val lockListener: (Boolean, CssCursorValue?, PointerDragKind?) -> Unit = { locked, cursor, _ ->
                val applyOnEdt = {
                    val frame = jFrame
                    if (locked) {
                        if (cursor != null) {
                            val awtCursor = resolveAwtCursor(cursor)
                            frame?.cursor = awtCursor
                            applyLockedAwtCursorToSubtree(nativeWidget, awtCursor)
                        }
                    } else {
                        activeResizeCursorSet = false
                        val defaultCursor = Cursor.getDefaultCursor()
                        frame?.cursor = defaultCursor
                        nativeWidget.cursor = defaultCursor
                        restoreAwtCursorSubtree(nativeWidget)
                    }
                }
                if (GraphicsEnvironment.isHeadless() || SwingUtilities.isEventDispatchThread()) {
                    applyOnEdt()
                } else {
                    SwingUtilities.invokeLater { applyOnEdt() }
                }
            }
            cursorLockListener = lockListener
            PointerDragManager.addCursorLockListener(lockListener)
        }

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

    private fun resizeEdgeMaskToCssCursor(edgeMask: Int): CssCursor = when (edgeMask) {
        EDGE_TOP or EDGE_LEFT -> CssCursor.NW_RESIZE
        EDGE_TOP or EDGE_RIGHT -> CssCursor.NE_RESIZE
        EDGE_BOTTOM or EDGE_LEFT -> CssCursor.SW_RESIZE
        EDGE_BOTTOM or EDGE_RIGHT -> CssCursor.SE_RESIZE
        EDGE_LEFT -> CssCursor.W_RESIZE
        EDGE_RIGHT -> CssCursor.E_RESIZE
        EDGE_TOP -> CssCursor.N_RESIZE
        EDGE_BOTTOM -> CssCursor.S_RESIZE
        else -> CssCursor.DEFAULT
    }

    private fun endActiveWindowDragSession() {
        val session = activeWindowDragSession ?: return
        activeWindowDragSession = null
        session.dispose()
    }

    private fun applyLockedAwtCursorToSubtree(container: java.awt.Container, cursor: Cursor) {
        container.cursor = cursor
        for (child in container.components) {
            child.cursor = cursor
            if (child is java.awt.Container) {
                applyLockedAwtCursorToSubtree(child, cursor)
            }
        }
    }

    private fun restoreAwtCursorSubtree(container: java.awt.Container) {
        for (child in container.components) {
            val childImpl = (child as? JComponent)?.getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl
            if (childImpl != null) {
                childImpl.refreshEffectiveAwtCursor()
            } else {
                child.cursor = null
            }
            if (child is java.awt.Container) {
                restoreAwtCursorSubtree(child)
            }
        }
    }

    private inner class CenterWindowContentLayoutManager : java.awt.LayoutManager2 {
        override fun addLayoutComponent(name: String?, comp: Component?) {}
        override fun addLayoutComponent(comp: Component?, constraints: Any?) {}
        override fun removeLayoutComponent(comp: Component?) {}
        override fun invalidateLayout(target: java.awt.Container?) {}
        override fun getLayoutAlignmentX(target: java.awt.Container?): Float = 0.5f
        override fun getLayoutAlignmentY(target: java.awt.Container?): Float = 0.0f
        override fun maximumLayoutSize(target: java.awt.Container?): Dimension = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)

        override fun preferredLayoutSize(parent: java.awt.Container): Dimension {
            var maxW = windowSize.width.coerceAtLeast(1)
            var maxH = windowSize.height.coerceAtLeast(1)
            for (c in parent.components) {
                if (!c.isVisible) continue
                val p = c.preferredSize
                if (p != null) {
                    if (p.width > maxW) maxW = p.width
                    if (p.height > maxH) maxH = p.height
                }
            }
            return Dimension(maxW, maxH)
        }

        override fun minimumLayoutSize(parent: java.awt.Container): Dimension = Dimension(1, 1)

        override fun layoutContainer(parent: java.awt.Container) {
            val w = parent.width.takeIf { it > 0 } ?: windowSize.width.coerceAtLeast(1)
            val h = parent.height.takeIf { it > 0 } ?: windowSize.height.coerceAtLeast(1)
            val children = resolveCenterChildrenSnapshot()
            val zEntries = ArrayList<Triple<Component, Int, Int>>(children.size)

            // Separate normal-flow children from full-bleed overlays vs positioned layers (`position: absolute/fixed`)
            val fullFillOverlayIndices = mutableListOf<Int>()
            val positionedIndices = mutableListOf<Int>()
            val flowIndices = mutableListOf<Int>()
            for (i in children.indices) {
                val c = children[i]
                if (!c.isVisible) continue
                val jc = c as? JComponent
                val impl = jc?.getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl
                val isFullFill = jc?.getClientProperty("data-overlay-host-root") == "true" ||
                    jc?.getClientProperty("data-overlay-root") == "true" ||
                    impl?.buildLayoutStyleInput()?.let { inp ->
                        "gui-layer-fill" in inp.classes ||
                            inp.attributes["layerSizingMode"].equals("FILL", ignoreCase = true) ||
                            inp.attributes["layerRole"].equals("FILL", ignoreCase = true)
                    } == true
                val isPositioned = !isFullFill && impl?.buildLayoutStyleInput()?.let { inp ->
                    val pos = inp.styleProps["position"]?.lowercase()
                    pos == "absolute" || pos == "fixed" ||
                        "gui-layer-float" in inp.classes ||
                        inp.attributes["layerSizingMode"] != null ||
                        inp.attributes["layerRole"] != null
                } == true
                when {
                    isFullFill -> fullFillOverlayIndices.add(i)
                    isPositioned -> positionedIndices.add(i)
                    else -> flowIndices.add(i)
                }
            }

            if (flowIndices.size == 1 && children[flowIndices[0]] is JComponent) {
                val c = children[flowIndices[0]]
                c.setBounds(0, 0, w, h)
                c.invalidate()
                c.doLayout()
                (c as? JComponent)?.let {
                    (it.getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl)
                        ?.updateLogicalBoundsFromAwt(0, 0, w, h)
                }
            } else if (flowIndices.isNotEmpty()) {
                val flowSet = flowIndices.toHashSet()
                var yCursor = 0
                for (idx in flowIndices) {
                    val c = children[idx]
                    val remainingH = (h - yCursor).coerceAtLeast(0)
                    val measured = SwingIntrinsicSizeMeasurer.measureIntrinsicSize(c, w, remainingH.coerceAtLeast(1))
                    val targetH = if (idx == flowIndices.last() && flowSet.size > 1 && measured.height < remainingH) {
                        measured.height
                    } else {
                        measured.height.coerceAtLeast(1)
                    }
                    c.setBounds(0, yCursor, w, targetH)
                    c.invalidate()
                    c.doLayout()
                    yCursor += targetH
                }
            }

            for (idx in fullFillOverlayIndices) {
                val c = children[idx]
                c.setBounds(0, 0, w, h)
                c.invalidate()
                c.doLayout()
                (c as? JComponent)?.let {
                    (it.getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl)
                        ?.updateLogicalBoundsFromAwt(0, 0, w, h)
                }
            }

            for (idx in positionedIndices) {
                val c = children[idx]
                val box = SwingFlexLayoutManager.computePositionedChildBox(parent, c, w, h)
                val targetX = box?.borderBox?.x ?: 0
                val targetY = box?.borderBox?.y ?: 0
                val targetW = (box?.borderBox?.width ?: w).coerceAtLeast(0)
                val targetH = (box?.borderBox?.height ?: h).coerceAtLeast(0)
                c.setBounds(targetX, targetY, targetW, targetH)
                c.invalidate()
                c.doLayout()
                (c as? JComponent)?.let {
                    (it.getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl)
                        ?.updateLogicalBoundsFromAwt(targetX, targetY, targetW, targetH)
                }
            }

            for (i in children.indices) {
                val c = children[i]
                val jc = c as? JComponent
                val impl = jc?.getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl
                val z = impl?.buildLayoutStyleInput()?.let {
                    it.styleProps["z-index"]?.toIntOrNull() ?: it.attributes["layerZIndex"]?.toIntOrNull()
                } ?: if (jc?.getClientProperty("data-overlay-host-root") == "true" || jc?.getClientProperty("data-overlay-root") == "true") 1000 else 0
                zEntries.add(Triple(c, z, i))
            }

            if (zEntries.size > 1) {
                val ordered = zEntries.sortedWith(
                    Comparator { a, b ->
                        val zCmp = b.second.compareTo(a.second)
                        if (zCmp != 0) zCmp else b.third.compareTo(a.third)
                    }
                ).map { it.first }
                (parent as? SwingContainerImpl.SwingLayoutPanel)?.isReorderingZOrder = true
                try {
                    for (i in ordered.indices) {
                        val comp = ordered[i]
                        if (comp.parent === parent && i < parent.componentCount && parent.getComponent(i) !== comp) {
                            parent.setComponentZOrder(comp, i)
                        }
                    }
                } finally {
                    (parent as? SwingContainerImpl.SwingLayoutPanel)?.isReorderingZOrder = false
                }
            }
        }
    }

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        val childComp = childImpl.nativeWidget as? JComponent ?: return
        val logical = resolveCenterChildrenSnapshot().toMutableList()
        logical.remove(childComp)
        val insertIdx = if (index < 0 || index >= logical.size) logical.size else index
        logical.add(insertIdx, childComp)

        if (childComp.parent === centerContentHost) {
            centerContentHost.remove(childComp)
        }
        if (index < 0 || index >= centerContentHost.componentCount) {
            centerContentHost.add(childComp)
        } else {
            centerContentHost.add(childComp, index)
        }
        syncLogicalCenterList(logical)
        (childImpl as? SwingBaseNativeImpl)?.refreshThemeAppearance()
        performWindowLayoutNow()
        nativeWidget.repaint()
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        val childComp = childImpl.nativeWidget as? JComponent ?: return
        val logical = resolveCenterChildrenSnapshot().toMutableList()
        logical.remove(childComp)
        syncLogicalCenterList(logical)
        centerContentHost.remove(childComp)
        performWindowLayoutNow()
        nativeWidget.repaint()
    }

    override fun clearChildrenNative() {
        syncLogicalCenterList(emptyList())
        centerContentHost.removeAll()
        performWindowLayoutNow()
        nativeWidget.repaint()
    }

    private val windowLogicalCenterChildren = ArrayList<Component>()

    private fun syncLogicalCenterList(updated: List<Component>) {
        windowLogicalCenterChildren.clear()
        windowLogicalCenterChildren.addAll(updated)
    }

    private fun resolveCenterChildrenSnapshot(): List<Component> =
        if (windowLogicalCenterChildren.isNotEmpty() || centerContentHost.componentCount == 0) {
            windowLogicalCenterChildren.toList()
        } else {
            centerContentHost.components.toList()
        }

    internal fun findOverlayTitleBars(): List<JComponent> {
        val result = ArrayList<JComponent>()
        fun walk(comp: Component) {
            if (comp is JComponent) {
                val isHostRoot = comp.getClientProperty("data-overlay-host-root") == "true"
                val hasTitleTheme = comp.getClientProperty("data-titlebar-theme") != null
                if (hasTitleTheme && !isHostRoot) {
                    result.add(comp)
                }
            }
            if (comp is java.awt.Container) {
                for (child in comp.components) {
                    walk(child)
                }
            }
        }
        walk(nativeWidget)
        return result
    }

    internal fun performWindowLayoutNow() {
        val w = windowSize.width.coerceAtLeast(1)
        val h = windowSize.height.coerceAtLeast(1)
        if (nativeWidget.width != w || nativeWidget.height != h || jFrame == null) {
            nativeWidget.setBounds(0, 0, w, h)
        }
        SwingFlexLayoutManager.layoutTreeNow(nativeWidget)
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

        val minClientW = (minWindowSize?.width ?: 120).coerceAtLeast(1)
        val minClientH = (minWindowSize?.height ?: 80).coerceAtLeast(1)
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
            SwingFlexLayoutManager.layoutTreeNow(nativeWidget)
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

    private fun isProtectedInteractiveTarget(frame: JFrame, me: MouseEvent): Boolean {
        val rawSrc = me.source as? Component
        val deepComp: Component? = when {
            rawSrc is java.awt.Container -> {
                val pt = SwingUtilities.convertPoint(me.component ?: rawSrc, me.x, me.y, rawSrc)
                SwingUtilities.getDeepestComponentAt(rawSrc, pt.x, pt.y) ?: rawSrc
            }
            rawSrc != null -> rawSrc
            else -> {
                val pt = SwingUtilities.convertPoint(me.component ?: nativeWidget, me.x, me.y, nativeWidget)
                SwingUtilities.getDeepestComponentAt(nativeWidget, pt.x, pt.y)
            }
        }
        var cur: Component? = deepComp
        while (cur != null && cur !== nativeWidget && cur !== centerContentHost && cur !== frame.contentPane && cur !== frame) {
            if (cur is JComponent) {
                val controlAttr = cur.getClientProperty("data-window-control") as? String
                if (!controlAttr.isNullOrBlank()) return true
                val impl = cur.getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl
                if (impl != null && impl.hasInteractiveNonDragClickHandler()) {
                    return true
                }
            }
            if (cur is javax.swing.AbstractButton ||
                cur is javax.swing.text.JTextComponent ||
                cur is javax.swing.JComboBox<*> ||
                cur is javax.swing.JSlider ||
                cur is javax.swing.JScrollBar ||
                cur is javax.swing.JSpinner ||
                cur is javax.swing.JTabbedPane ||
                cur is javax.swing.JTable ||
                cur is javax.swing.JTree
            ) {
                if (cur.isEnabled) return true
            }
            cur = cur.parent
        }
        return false
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
                endActiveWindowDragSession()
            }
            return
        }
        if (me.isConsumed) {
            if (me.id == MouseEvent.MOUSE_RELEASED) {
                dragMoveLatchActive = false
                resizeEdgeMask = 0
                resizeStartBounds = null
                endActiveWindowDragSession()
            }
            return
        }
        val normalWindowed = !fullscreenFlag && getWindowState() == WindowState.NORMAL
        when (me.id) {
            MouseEvent.MOUSE_MOVED -> {
                if (PointerDragManager.isCursorLocked) return
                if (undecoratedFlag && normalWindowed && resizeEdgeMask == 0 && !dragMoveLatchActive) {
                    val overControl = isProtectedInteractiveTarget(frame, me)
                    val mask = if (overControl) 0 else computeResizeEdgeMask(frame, me.xOnScreen, me.yOnScreen)
                    updateUndecoratedResizeCursor(frame, mask)
                } else if (activeResizeCursorSet && resizeEdgeMask == 0) {
                    updateUndecoratedResizeCursor(frame, 0)
                }
            }
            MouseEvent.MOUSE_EXITED -> {
                if (PointerDragManager.isCursorLocked) return
                if (activeResizeCursorSet && resizeEdgeMask == 0) {
                    updateUndecoratedResizeCursor(frame, 0)
                }
            }
            MouseEvent.MOUSE_PRESSED -> {
                if (!SwingUtilities.isLeftMouseButton(me) || !undecoratedFlag || !normalWindowed) return
                if (isProtectedInteractiveTarget(frame, me)) {
                    return
                }
                val edgeMask = computeResizeEdgeMask(frame, me.xOnScreen, me.yOnScreen)
                if (edgeMask != 0) {
                    dragMoveLatchActive = false
                    resizeEdgeMask = edgeMask
                    resizeStartScreenX = me.xOnScreen
                    resizeStartScreenY = me.yOnScreen
                    resizeStartBounds = frame.bounds
                    activeWindowDragSession?.dispose()
                    activeWindowDragSession = PointerDragManager.startWindowResizeDrag(
                        lockedCursor = resizeEdgeMaskToCssCursor(edgeMask)
                    )
                    updateUndecoratedResizeCursor(frame, edgeMask)
                    me.consume()
                } else if (me.component === nativeWidget ||
                    me.component === centerContentHost ||
                    me.component === frame ||
                    me.component === frame.contentPane
                ) {
                    resizeEdgeMask = 0
                    dragMoveLatchActive = true
                    dragMoveStartScreenX = me.xOnScreen
                    dragMoveStartScreenY = me.yOnScreen
                    dragMoveStartWinX = frame.x
                    dragMoveStartWinY = frame.y
                    activeWindowDragSession?.dispose()
                    activeWindowDragSession = PointerDragManager.startWindowMoveDrag(lockedCursor = CssCursor.DEFAULT)
                    val defaultCursor = Cursor.getDefaultCursor()
                    frame.cursor = defaultCursor
                    nativeWidget.cursor = defaultCursor
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
                endActiveWindowDragSession()
                if (!PointerDragManager.isCursorLocked) {
                    if (undecoratedFlag && normalWindowed) {
                        val mask = if (isProtectedInteractiveTarget(frame, me)) 0 else computeResizeEdgeMask(frame, me.xOnScreen, me.yOnScreen)
                        updateUndecoratedResizeCursor(frame, mask)
                    } else {
                        updateUndecoratedResizeCursor(frame, 0)
                    }
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
        val frame = jFrame ?: return
        val effective = alwaysOnTopFlag || (isToolModalWindow && alwaysOnTopOfOwner)
        if (effective && frame.isUndecorated && (frame.extendedState and JFrame.ICONIFIED) != 0) {
            return
        }
        runCatching { frame.isAlwaysOnTop = effective }
    }

    private fun registerWithAnimationScheduler() {
        val target: Component = jFrame ?: nativeWidget
        SwingAnimationNativeImpl.registerWindow(target)
    }

    private fun unregisterFromAnimationScheduler() {
        jFrame?.let { SwingAnimationNativeImpl.unregisterWindow(it) }
        SwingAnimationNativeImpl.unregisterWindow(nativeWidget)
    }

    private fun applySwingUndecorated(undecorated: Boolean) {
        val frame = jFrame ?: return
        if (frame.isUndecorated != undecorated) {
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
        applyWindowsCornerPreference(frame, doNotRound = undecorated)
    }

    private fun applyWindowsCornerPreference(frame: JFrame, doNotRound: Boolean) {
        if (!System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true)) return
        if (!frame.isDisplayable) return
        runCatching {
            val nativeClass = Class.forName("com.sun.jna.Native")
            val getWindowPtr = nativeClass.getMethod("getWindowPointer", Window::class.java)
            val hwndPtr = getWindowPtr.invoke(null, frame) ?: return
            val memClass = Class.forName("com.sun.jna.Memory")
            val mem = memClass.getConstructor(Long::class.javaPrimitiveType).newInstance(4L)
            memClass.getMethod("setInt", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(mem, 0L, if (doNotRound) 1 /* DWMWCP_DONOTROUND */ else 0 /* DWMWCP_DEFAULT */)
            val libClass = Class.forName("com.sun.jna.NativeLibrary")
            val dwmLib = libClass.getMethod("getInstance", String::class.java).invoke(null, "dwmapi")
            val fn = libClass.getMethod("getFunction", String::class.java).invoke(dwmLib, "DwmSetWindowAttribute")
            val ptrClass = Class.forName("com.sun.jna.Pointer")
            val invokeInt = fn.javaClass.getMethod("invokeInt", Array<Any>::class.java)
            if (ptrClass.isInstance(hwndPtr)) {
                invokeInt.invoke(fn, arrayOf(hwndPtr, 33 /* DWMWA_WINDOW_CORNER_PREFERENCE */, mem, 4))
            }
        }
    }

    private fun resolveExtendedWindowState(): WindowState {
        if (fullscreenFlag) return WindowState.FULLSCREEN
        val frame = jFrame
        if (frame == null || !SwingApplicationImpl.isInteractiveSwingDisplayEnabled || !frame.isShowing) {
            return if (windowState == WindowState.FULLSCREEN) WindowState.NORMAL else windowState
        }
        val ext = frame.extendedState
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
        val targetInitial = pendingInitialClientSize
        if (targetInitial != null && windowState == WindowState.NORMAL && !fullscreenFlag && !isMaximizedNow) {
            pendingInitialClientSize = null
            if ((insetW > 0 || insetH > 0) && (clientW != targetInitial.width || clientH != targetInitial.height)) {
                isApplyingSwingAspectClamp = true
                try {
                    jFrame?.setSize(targetInitial.width + insetW, targetInitial.height + insetH)
                } finally {
                    isApplyingSwingAspectClamp = false
                }
            }
            isSyncingFromComponentResize = true
            try {
                windowSize = targetInitial
                nativeWidget.setBounds(0, 0, targetInitial.width, targetInitial.height)
                if (prevState != windowState) {
                    onWindowStateChangedListener?.invoke(windowState)
                }
                onResizeListener?.invoke(
                    WindowConfiguration(
                        widthPx = targetInitial.width,
                        heightPx = targetInitial.height,
                        windowState = windowState,
                        isFullscreen = fullscreenFlag
                    )
                )
            } finally {
                isSyncingFromComponentResize = false
            }
            return targetInitial
        }
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
        registerWithAnimationScheduler()
        bringToFront()
    }

    override fun bringToFront() {
        zOrderLayer = nextZOrderSequence()
        nativeWidget.putClientProperty("zOrderLayer", zOrderLayer)
        nativeWidget.putClientProperty("broughtToFront", true)
        if (isVisible()) {
            registerWithAnimationScheduler()
        }
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
                val frame = jFrame
                if (frame != null) {
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
                performWindowLayoutNow()
            }
        } else {
            performWindowLayoutNow()
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
        val wasFullscreen = fullscreenFlag
        windowState = state
        fullscreenFlag = (state == WindowState.FULLSCREEN)
        if (SwingApplicationImpl.isInteractiveSwingDisplayEnabled) {
            runOnEdt {
                val frame = jFrame ?: return@runOnEdt
                when (state) {
                    WindowState.MINIMIZED -> {
                        if (frame.isUndecorated && alwaysOnTopFlag) {
                            runCatching { frame.isAlwaysOnTop = false }
                        }
                        frame.extendedState = frame.extendedState or JFrame.ICONIFIED
                    }
                    WindowState.MAXIMIZED -> {
                        if (wasFullscreen && frame.isUndecorated != undecoratedFlag) {
                            applySwingUndecorated(undecoratedFlag)
                        }
                        if ((frame.extendedState and JFrame.ICONIFIED) != 0 && alwaysOnTopFlag) {
                            runCatching { frame.isAlwaysOnTop = true }
                        }
                        frame.extendedState = (frame.extendedState and JFrame.ICONIFIED.inv()) or JFrame.MAXIMIZED_BOTH
                    }
                    WindowState.FULLSCREEN -> {
                        setFullscreen(true)
                    }
                    WindowState.NORMAL -> {
                        if (wasFullscreen) {
                            setFullscreen(false)
                        }
                        if ((frame.extendedState and JFrame.ICONIFIED) != 0 && alwaysOnTopFlag) {
                            runCatching { frame.isAlwaysOnTop = true }
                        }
                        frame.extendedState = JFrame.NORMAL
                    }
                }
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
    private var savedWindowedBoundsBeforeFullscreen: Rectangle? = null

    override fun setFullscreen(fullscreen: Boolean, cutoutMode: DisplayCutoutMode) {
        val wasFullscreen = fullscreenFlag
        fullscreenFlag = fullscreen
        if (!fullscreen) {
            fullscreenElementImpl = null
            nativeWidget.putClientProperty("fullscreenElement", null)
            if (windowState == WindowState.FULLSCREEN) {
                windowState = resolveExtendedWindowState()
            }
            if (SwingApplicationImpl.isInteractiveSwingDisplayEnabled) {
                runOnEdt {
                    val frame = jFrame ?: return@runOnEdt
                    if (frame.isUndecorated != undecoratedFlag) {
                        applySwingUndecorated(undecoratedFlag)
                    }
                    savedWindowedBoundsBeforeFullscreen?.let { restored ->
                        savedWindowedBoundsBeforeFullscreen = null
                        frame.bounds = restored
                    }
                    applyWindowsCornerPreference(frame, doNotRound = undecoratedFlag)
                }
            }
            if (windowState == WindowState.NORMAL && aspectRatioValue != null) {
                setWindowSize(windowSize)
            }
        } else {
            windowState = WindowState.FULLSCREEN
            if (SwingApplicationImpl.isInteractiveSwingDisplayEnabled) {
                runOnEdt {
                    val frame = jFrame ?: return@runOnEdt
                    if (!wasFullscreen && savedWindowedBoundsBeforeFullscreen == null) {
                        savedWindowedBoundsBeforeFullscreen = frame.bounds
                    }
                    val screenBounds = frame.graphicsConfiguration?.bounds
                        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
                    if (frame.extendedState != JFrame.NORMAL) {
                        frame.extendedState = JFrame.NORMAL
                    }
                    if (!frame.isUndecorated) {
                        applySwingUndecorated(true)
                    }
                    frame.bounds = screenBounds
                    applyWindowsCornerPreference(frame, doNotRound = true)
                }
            }
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

    override fun getTitleCapabilities(): TitleCapabilities = DEFAULT_TITLE_CAPABILITIES

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
            applySwingUndecorated(undecorated || fullscreenFlag)
        }
    }

    override fun startSystemMove() {
        if (GraphicsEnvironment.isHeadless()) return
        runOnEdt {
            val frame = jFrame ?: return@runOnEdt
            if (!frame.isVisible || fullscreenFlag || getWindowState() != WindowState.NORMAL) return@runOnEdt
            if (resizeEdgeMask != 0) return@runOnEdt
            val recent = lastHandledMouseEvent
            if (recent != null && recent.id == MouseEvent.MOUSE_PRESSED &&
                (recent.isConsumed || isProtectedInteractiveTarget(frame, recent))
            ) {
                return@runOnEdt
            }
            val ptr = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: return@runOnEdt
            dragMoveLatchActive = true
            dragMoveStartScreenX = ptr.x
            dragMoveStartScreenY = ptr.y
            dragMoveStartWinX = frame.x
            dragMoveStartWinY = frame.y
            activeWindowDragSession?.dispose()
            activeWindowDragSession = PointerDragManager.startWindowMoveDrag(lockedCursor = CssCursor.DEFAULT)
            val defaultCursor = Cursor.getDefaultCursor()
            frame.cursor = defaultCursor
            nativeWidget.cursor = defaultCursor
        }
    }

    override fun setVisible(visible: Boolean) {
        super.setVisible(visible)
        if (visible) {
            registerWithAnimationScheduler()
        }
        runOnEdt {
            if (!visible) {
                jFrame?.isVisible = false
            } else if (SwingApplicationImpl.isInteractiveSwingDisplayEnabled) {
                jFrame?.isVisible = true
            }
        }
    }

    override fun showWindow() {
        if (!isVisible()) {
            runOnEdt { jFrame?.isVisible = false }
            return
        }
        super.setVisible(true)
        registerWithAnimationScheduler()
        runOnEdt {
            val frame = jFrame
            var desiredSize = windowSize
            if (frame != null) {
                isApplyingSwingAspectClamp = true
                try {
                    val effectiveUndecorated = undecoratedFlag || fullscreenFlag
                    applySwingUndecorated(effectiveUndecorated)
                    applySwingAlwaysOnTop()
                    if (SwingApplicationImpl.isInteractiveSwingDisplayEnabled) {
                        if (fullscreenFlag) {
                            pendingInitialClientSize = null
                            if (savedWindowedBoundsBeforeFullscreen == null) {
                                savedWindowedBoundsBeforeFullscreen = if (hasExplicitPosition) {
                                    Rectangle(positionX, positionY, desiredSize.width.coerceAtLeast(1), desiredSize.height.coerceAtLeast(1))
                                } else {
                                    Rectangle(frame.x, frame.y, desiredSize.width.coerceAtLeast(1), desiredSize.height.coerceAtLeast(1))
                                }
                            }
                            val screenBounds = frame.graphicsConfiguration?.bounds
                                ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.bounds
                            if (frame.extendedState != JFrame.NORMAL) {
                                frame.extendedState = JFrame.NORMAL
                            }
                            if (screenBounds.width > 0 && screenBounds.height > 0) {
                                frame.bounds = screenBounds
                                desiredSize = Size(screenBounds.width, screenBounds.height)
                                nativeWidget.setBounds(0, 0, screenBounds.width, screenBounds.height)
                            }
                            frame.isVisible = true
                            applyWindowsCornerPreference(frame, doNotRound = true)
                        } else if (getWindowState() == WindowState.NORMAL) {
                            pendingInitialClientSize = desiredSize
                            if (hasExplicitPosition) {
                                frame.setLocation(positionX, positionY)
                            }
                            frame.contentPane.preferredSize = Dimension(desiredSize.width.coerceAtLeast(1), desiredSize.height.coerceAtLeast(1))
                            nativeWidget.preferredSize = Dimension(desiredSize.width.coerceAtLeast(1), desiredSize.height.coerceAtLeast(1))
                            frame.pack()
                            frame.isVisible = true
                            applyWindowsCornerPreference(frame, doNotRound = effectiveUndecorated)
                            val insets = frame.insets
                            val insetW = insets.left + insets.right
                            val insetH = insets.top + insets.bottom
                            minWindowSize?.let { min ->
                                frame.minimumSize = Dimension(min.width + insetW, min.height + insetH)
                            }
                            val targetW = desiredSize.width + insetW
                            val targetH = desiredSize.height + insetH
                            if (frame.width != targetW || frame.height != targetH) {
                                frame.setSize(targetW, targetH)
                            }
                            frame.validate()
                        } else {
                            pendingInitialClientSize = null
                            frame.isVisible = true
                            applyWindowsCornerPreference(frame, doNotRound = effectiveUndecorated)
                        }
                    } else {
                        pendingInitialClientSize = null
                        frame.isVisible = false
                        if (!fullscreenFlag && getWindowState() == WindowState.NORMAL) {
                            minWindowSize?.let { min ->
                                frame.minimumSize = Dimension(min.width, min.height)
                            }
                        }
                    }
                    windowSize = desiredSize
                } finally {
                    isApplyingSwingAspectClamp = false
                }
            }
            performWindowLayoutNow()
            if (SwingApplicationImpl.isInteractiveSwingDisplayEnabled) {
                frame?.repaint()
            }
        }
        if (SwingApplicationImpl.isInteractiveSwingDisplayEnabled) {
            bringToFront()
        }
        onFocusListener?.invoke(true)
    }

    private fun cleanupWindowResources() {
        unregisterFromAnimationScheduler()
        awtMouseDragListener?.let { listener ->
            awtMouseDragListener = null
            runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(listener) }
        }
        cursorLockListener?.let { listener ->
            cursorLockListener = null
            PointerDragManager.removeCursorLockListener(listener)
        }
        dragMoveLatchActive = false
        resizeEdgeMask = 0
        resizeStartBounds = null
        endActiveWindowDragSession()
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

    override fun dispose() {
        windowIconLoadJob?.cancel()
        windowIconLoadJob = null
        runOnEdt {
            cleanupWindowResources()
        }
        super.dispose()
    }

    override fun closeWindow() {
        val allow = onCloseRequestedListener?.invoke() ?: true
        if (allow) {
            runOnEdt {
                cleanupWindowResources()
            }
        }
    }

    override fun getBounds(): Rect = Rect(0, 0, windowSize.width, windowSize.height)

    override fun setOnWindowResize(listener: ((WindowConfiguration) -> Unit)?) { onResizeListener = listener }
    override fun setOnWindowStateChanged(listener: ((WindowState) -> Unit)?) { onWindowStateChangedListener = listener }
    override fun setOnWindowMoved(listener: ((Int, Int) -> Unit)?) { onMovedListener = listener }
    override fun setOnWindowFocusChanged(listener: ((Boolean) -> Unit)?) { onFocusListener = listener }
    override fun setOnWindowCloseRequested(listener: (() -> Boolean)?) { onCloseRequestedListener = listener }

    companion object {
        internal val DEFAULT_TITLE_CAPABILITIES: TitleCapabilities = TitleCapabilities(
            isSupported = true,
            isEmulated = false,
            supportsCustomTitleBar = false,
            supportsCustomComponents = false,
            supportsLeadingComponents = false,
            supportsCenterComponents = false,
            supportsTrailingComponents = false,
            supportsEmbeddedMenuBar = false,
            supportsNavbarMenuBar = false,
            supportsWindowIcon = true,
            supportsWindowTitle = true,
            supportsMinimizeButton = true,
            supportsMaximizeButton = true,
            supportsCloseButton = true,
            supportsCollapseToggle = false,
            supportsControlsPlacement = false,
            supportsCustomHeight = false,
            supportsSystemAeroOrBackdrop = false,
            isToolWindowTitleBar = false,
            isTitleBarRequired = true,
            titleBarRendersAsNavbar = false,
            menuBarRendersAsNavbar = false,
            menuBarMergesIntoTitleNavbar = false
        )

        private const val UNDECORATED_RESIZE_BORDER_PX: Int = 6
        private const val EDGE_LEFT: Int = 1
        private const val EDGE_RIGHT: Int = 2
        private const val EDGE_TOP: Int = 4
        private const val EDGE_BOTTOM: Int = 8

        private var zOrderCounter: Int = 0

        internal fun nextZOrderSequence(): Int = ++zOrderCounter
    }
}
