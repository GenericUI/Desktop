@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.window.WindowState
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.widget.GtkRetainedContainerSupport
import com.nexustools.genericui.gtk.widget.GtkSemanticContainerWidget
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.titlebar.LinuxOverlayTitleBarResolver
import com.nexustools.genericui.linux.titlebar.LinuxTitleBarProfileManager
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer

/**
 * Retained GTK4 container implementation keeping plain containers transparent (`GtkBox + GtkCustomLayout`),
 * using `GtkOverlay + GtkCustomLayout` for `LayeredContainer`, and painting [GtkSemanticContainerWidget]
 * surfaces or [GtkInteractiveSurfaceWidget] frames when semantic classes or interactive surface specs apply.
 */
open class GtkContainerNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkBox"),
    classPrefix: String = "container",
    tagName: String = "div"
) : DesktopContainerNativeImpl<GtkWidgetHandle>(nativeWidget, classPrefix, tagName) {

    init {
        val isLayered = GtkRetainedContainerSupport.isLayeredHostTagOrClass(
            classPrefix = classPrefix,
            tagName = tagName,
            classes = nativeWidget.cachedAllClassTokens
        )
        if (isLayered) {
            nativeWidget.updateActiveGtkClassName("GtkOverlay")
        }
        layoutController = GtkRetainedContainerSupport.initializeContainer(
            host = this,
            isLayeredHost = isLayered,
            useSurfaceOverlay = false,
            surfacePainter = null
        )
        nativeWidget.widgetMaterializer = { materializeContainerWidget() }
    }

    internal fun isOverlayWindowTitleBarRole(): Boolean =
        (nativeWidget.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] as? String)
            ?.trim()
            ?.lowercase() == DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR

    internal fun findAncestorWindowImpl(): DesktopWindowNativeImpl<*>? {
        var cur: DesktopNativeWidget? = nativeWidget.parentWidget
        while (cur != null) {
            val impl = cur.attributes["nativeImpl"]
            if (impl is DesktopWindowNativeImpl<*>) return impl
            cur = cur.parentWidget
        }
        return null
    }

    internal fun shouldCollapseOverlayTitleBar(): Boolean {
        if (!isOverlayWindowTitleBarRole()) return false
        if (nativeWidget.attributes["data-titlebar-active"]?.toString()?.equals("false", ignoreCase = true) == true) {
            return true
        }
        val profile = LinuxTitleBarProfileManager.currentProfile()
        if (!profile.isReplicable) return true
        return findAncestorWindowImpl()?.isFullscreen() == true
    }

    internal fun toggleMaximizeFromTitleBar(winImpl: DesktopWindowNativeImpl<*>) {
        if (winImpl.isToolModalWindow) return
        if (winImpl.nativeWidget.attributes["maximizable"]?.toString() == "false") return
        val next = if (winImpl.getWindowState() == WindowState.MAXIMIZED) {
            WindowState.NORMAL
        } else {
            WindowState.MAXIMIZED
        }
        winImpl.setWindowState(next)
    }

    internal fun handleOverlayTitleBarPressed(
        nPress: Int,
        buttonNum: UInt,
        ev: PointerEvent,
        gdkEvent: COpaquePointer?
    ): Boolean {
        if (!isOverlayWindowTitleBarRole() || shouldCollapseOverlayTitleBar()) return false
        if (LinuxOverlayTitleBarResolver.isPointOverTitleBarInteractive(nativeWidget, ev.x, ev.y)) {
            return false
        }
        val winImpl = findAncestorWindowImpl() ?: return false
        if (buttonNum == Gtk4DynamicBindings.GDK_BUTTON_SECONDARY) {
            if (onContextMenuListener != null) {
                dispatchContextMenu(ev)
            } else if (winImpl is GtkWindowNativeImpl) {
                GtkNativeWindowHost.showWindowMenu(winImpl, gdkEvent, ev.screenX.toInt(), ev.screenY.toInt())
            }
            return true
        }
        if (buttonNum == Gtk4DynamicBindings.GDK_BUTTON_PRIMARY && nPress >= 2) {
            if (onDoubleClickListener != null) {
                dispatchDoubleClick()
            } else {
                toggleMaximizeFromTitleBar(winImpl)
            }
            return true
        }
        if (buttonNum == Gtk4DynamicBindings.GDK_BUTTON_PRIMARY && nPress == 1 && onPointerDownListener == null) {
            winImpl.startSystemMove()
        }
        return false
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        if (isOverlayWindowTitleBarRole()) {
            if (shouldCollapseOverlayTitleBar()) return Size(0, 0)
            val compactH = LinuxTitleBarProfileManager.currentProfile().metrics.compactHeightPx
            return Size((maxWidth?.toInt() ?: 480).coerceAtLeast(1), compactH)
        }
        return super.measureIntrinsicSize(maxWidth, maxHeight)
    }

    override fun setBounds(rect: Rect) {
        val resolved = if (shouldCollapseOverlayTitleBar()) {
            Rect(rect.x, rect.y, rect.width, 0)
        } else {
            rect
        }
        super.setBounds(resolved)
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        if (shouldCollapseOverlayTitleBar()) {
            for (child in getLogicalChildren()) {
                (child as? DesktopBaseNativeImpl<*>)?.setBounds(Rect(0, 0, 0, 0))
            }
            return
        }
        if (isOverlayWindowTitleBarRole()) {
            layoutOverlayTitleBarChildren(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1))
            return
        }
        super.layoutChildrenInBounds(width, height)
    }

    private fun layoutOverlayTitleBarChildren(totalW: Int, totalH: Int) {
        val visibleChildren = getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>().filter {
            it.nativeWidget.visible && !it.nativeWidget.disposed
        }
        LinuxOverlayTitleBarResolver.layoutOverlayTitleBarChildren(
            visibleChildren = visibleChildren,
            windowTitleBar = nativeWidget,
            totalW = totalW,
            totalH = totalH
        )
    }

    private fun needsSurfaceOverlay(): Boolean {
        if (nativeWidget.interactiveSurfaceSpec != null) return true
        if (isOverlayWindowTitleBarRole()) return true
        val dark = GtkRetainedContainerSupport.resolveDarkTheme()
        return resolveSemanticSurfaceSpec(dark) != null
    }

    private fun materializeContainerWidget(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        val isLayered = GtkRetainedContainerSupport.isLayeredHostTagOrClass(
            classPrefix = nativeWidget.cachedAllClassTokens.firstOrNull() ?: "",
            tagName = tagName,
            classes = nativeWidget.cachedAllClassTokens
        )
        if (isLayered) {
            nativeWidget.updateActiveGtkClassName("GtkOverlay")
        }
        val controller = layoutController ?: return null
        val useOverlay = needsSurfaceOverlay()
        val painter: ((COpaquePointer, Int, Int) -> Unit)? = if (useOverlay) {
            { cr, w, h -> GtkSemanticContainerWidget.paintIfSemanticOrInteractive(this, cr, w, h) }
        } else {
            null
        }
        return GtkRetainedContainerSupport.materializeContainer(
            host = this,
            controller = controller,
            isLayeredHost = isLayered,
            useSurfaceOverlay = useOverlay,
            surfacePainter = painter
        )
    }

    private fun upgradeSurfaceOverlayIfNeeded() {
        val existingPtr = nativeWidget.realGtkWidgetPtr ?: return
        if (nativeWidget.backgroundSurfacePtr != null || !needsSurfaceOverlay()) {
            nativeWidget.backgroundSurfacePtr?.let { Gtk4DynamicBindings.gtkWidgetQueueDraw(it) }
            return
        }
        val controller = layoutController ?: return
        val isLayered = GtkRetainedContainerSupport.isLayeredHostTagOrClass(
            classPrefix = "",
            tagName = tagName,
            classes = nativeWidget.cachedAllClassTokens
        )
        val painter: (COpaquePointer, Int, Int) -> Unit = { cr, w, h ->
            GtkSemanticContainerWidget.paintIfSemanticOrInteractive(this, cr, w, h)
        }
        if (isLayered) {
            val bgDa = GtkWidgetLifecycleBridge.createDrawingSurfaceForPainter(nativeWidget, painter) ?: return
            Gtk4DynamicBindings.gtkWidgetSetCanTarget(bgDa, false)
            Gtk4DynamicBindings.gtkOverlaySetChild(existingPtr, bgDa)
            nativeWidget.backgroundSurfacePtr = bgDa
            Gtk4DynamicBindings.gtkWidgetQueueDraw(bgDa)
            return
        }
        // Replace plain GtkBox cleanly with GtkOverlay(bgDrawingArea + contentBox) preserving children/bounds.
        val detachedChildren = mutableListOf<COpaquePointer>()
        var cur = Gtk4DynamicBindings.gtkWidgetGetFirstChild(existingPtr)
        while (cur != null) {
            val next = Gtk4DynamicBindings.gtkWidgetGetNextSibling(cur)
            Gtk4DynamicBindings.gtkWidgetUnparent(cur)
            detachedChildren.add(cur)
            cur = next
        }
        nativeWidget.realGtkWidgetPtr = null
        val newPtr = GtkRetainedContainerSupport.materializeContainer(
            host = this,
            controller = controller,
            isLayeredHost = false,
            useSurfaceOverlay = true,
            surfacePainter = painter
        )
        if (newPtr != null) {
            val layoutBox = nativeWidget.containerLayoutBoxPtr ?: newPtr
            for (c in detachedChildren) {
                if (Gtk4DynamicBindings.gtkWidgetGetParent(c) == null) {
                    Gtk4DynamicBindings.gtkWidgetInsertAfter(c, layoutBox, null)
                }
            }
            GtkWidgetLifecycleBridge.swapLiveWidgetInParent(this, existingPtr, newPtr)
        }
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        GtkRetainedContainerSupport.attachChildWidgetIfRealized(this, childImpl)
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        GtkRetainedContainerSupport.detachChildWidget(this, childImpl)
    }

    override fun onChildrenClearedNative() {
        GtkRetainedContainerSupport.clearChildWidgets(this)
    }

    override fun setAttribute(name: String, value: String?) {
        super.setAttribute(name, value)
        onNativeStyleSynced()
    }

    override fun setType(type: BootstrapType?) {
        super.setType(type)
        onNativeStyleSynced()
    }

    override fun setBackgroundType(type: BootstrapType?) {
        super.setBackgroundType(type)
        onNativeStyleSynced()
    }

    override fun setTextColorType(type: BootstrapType?) {
        super.setTextColorType(type)
        onNativeStyleSynced()
    }

    override fun setOutline(outline: Boolean) {
        super.setOutline(outline)
        onNativeStyleSynced()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        onNativeStyleSynced()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        onNativeStyleSynced()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        onNativeStyleSynced()
    }

    override fun onNativeBoundsChanged(rect: Rect) = GtkWidgetLifecycleBridge.onBoundsChanged(this, rect)
    override fun onNativeVisibilityChanged(visible: Boolean) = GtkWidgetLifecycleBridge.onVisibilityChanged(this, visible)
    override fun onNativeEnabledChanged(enabled: Boolean) = GtkWidgetLifecycleBridge.onEnabledChanged(this, enabled)
    override fun onNativeOpacityChanged(opacity: Float) = GtkWidgetLifecycleBridge.onOpacityChanged(this, opacity)
    override fun onNativeTooltipChanged(tooltip: String?) = GtkWidgetLifecycleBridge.onTooltipChanged(this, tooltip)
    override fun onNativeCursorChanged(cursor: CssCursorValue?) = GtkWidgetLifecycleBridge.onCursorChanged(this, cursor)
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) = GtkWidgetLifecycleBridge.onPointerEventsChanged(this, pointerEventsNone)
    override fun onNativeZIndexChanged(zIndex: Int) = GtkWidgetLifecycleBridge.onZIndexChanged(this, zIndex)
    override fun onNativeTransformChanged(transform: CssTransform?) = GtkWidgetLifecycleBridge.onTransformChanged(this, transform)

    override fun onNativeStyleSynced() {
        GtkRetainedContainerSupport.syncOverflowPolicy(this)
        upgradeSurfaceOverlayIfNeeded()
        GtkWidgetLifecycleBridge.onStyleSynced(this)
    }
}
