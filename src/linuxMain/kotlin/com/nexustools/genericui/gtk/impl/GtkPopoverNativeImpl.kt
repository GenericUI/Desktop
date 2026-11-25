@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.desktop.widget.DesktopPopoverNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.ui.Placement
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkRetainedContainerSupport
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction

/**
 * Retained GTK4 floating popover mounting `GtkPopover` over an inner `GtkCustomLayout` container,
 * anchored cleanly via `gtk_popover_set_pointing_to` and shown/dismissed via `popup`/`popdown`.
 */
class GtkPopoverNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkPopover")
) : DesktopPopoverNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private var closedRef: StableRef<GtkPopoverNativeImpl>? = null

    init {
        nativeWidget.ownerImpl = this
        layoutController = DesktopContainerLayoutController(host = this, applyScaleToBounds = false)
        nativeWidget.customLayoutController = layoutController
        nativeWidget.widgetMaterializer = { materializePopover() }
    }

    private fun materializePopover(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val pop = Gtk4DynamicBindings.gtkPopoverNew() ?: return null
        val contentBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 0) ?: return null
        val controller = layoutController ?: return null

        Gtk4DynamicBindings.gtkPopoverSetAutohide(pop, true)
        Gtk4DynamicBindings.gtkPopoverSetHasArrow(pop, true)
        applyPlacementToPopover(pop, placementState)
        GtkCustomLayoutManager.attachToContainer(this, contentBox, controller, null)
        Gtk4DynamicBindings.gtkPopoverSetChild(pop, contentBox)

        closedRef?.dispose()
        val ref = StableRef.create(this)
        closedRef = ref
        LinuxGlibGioBindings.signalConnect(pop, "closed", popoverClosedCb.reinterpret(), ref.asCPointer())

        nativeWidget.containerLayoutBoxPtr = contentBox
        nativeWidget.popoverPtr = pop
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = pop,
            focusTargetPtr = contentBox,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = false
        )
        for (child in getLogicalChildren()) {
            GtkRetainedContainerSupport.attachChildWidgetIfRealized(this, child)
        }
        return pop
    }

    private fun applyPlacementToPopover(pop: COpaquePointer, placement: Placement) {
        val pos = when (placement) {
            Placement.TOP, Placement.TOP_START, Placement.TOP_END -> Gtk4DynamicBindings.GTK_POS_TOP
            Placement.START -> Gtk4DynamicBindings.GTK_POS_LEFT
            Placement.END -> Gtk4DynamicBindings.GTK_POS_RIGHT
            Placement.BOTTOM, Placement.BOTTOM_START, Placement.BOTTOM_END -> Gtk4DynamicBindings.GTK_POS_BOTTOM
        }
        Gtk4DynamicBindings.gtkPopoverSetPosition(pop, pos)
    }

    override fun setPlacement(placement: Placement) {
        super.setPlacement(placement)
        nativeWidget.realGtkWidgetPtr?.let { applyPlacementToPopover(it, placement) }
    }

    override fun showAnchored(anchor: NativeImpl<*>?, x: Int, y: Int) {
        super.showAnchored(anchor, x, y)
        val pop = nativeWidget.ensureLiveWidgetPtr() ?: return
        val anchorHandle = anchor?.nativeWidget as? GtkWidgetHandle
        val anchorPtr = anchorHandle?.ensureLiveWidgetPtr() ?: anchorHandle?.realGtkWidgetPtr
        if (anchorPtr != null) {
            val existingParent = Gtk4DynamicBindings.gtkWidgetGetParent(pop)
            if (existingParent != anchorPtr) {
                if (existingParent != null) {
                    Gtk4DynamicBindings.gtkWidgetUnparent(pop)
                }
                Gtk4DynamicBindings.gtkWidgetSetParent(pop, anchorPtr)
            }
            val ab = (anchor as? com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl<*>)?.getBounds()
            Gtk4DynamicBindings.gtkPopoverSetPointingTo(
                pop,
                0,
                0,
                ab?.width?.coerceAtLeast(1) ?: 1,
                ab?.height?.coerceAtLeast(1) ?: 1
            )
        } else {
            Gtk4DynamicBindings.gtkPopoverSetPointingTo(pop, x, y, 1, 1)
        }
        Gtk4DynamicBindings.gtkPopoverPopup(pop)
    }

    override fun hide() {
        super.hide()
        nativeWidget.realGtkWidgetPtr?.let { Gtk4DynamicBindings.gtkPopoverPopdown(it) }
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

    override fun onNativeBoundsChanged(rect: Rect) = GtkWidgetLifecycleBridge.onBoundsChanged(this, rect)
    override fun onNativeVisibilityChanged(visible: Boolean) = GtkWidgetLifecycleBridge.onVisibilityChanged(this, visible)
    override fun onNativeEnabledChanged(enabled: Boolean) = GtkWidgetLifecycleBridge.onEnabledChanged(this, enabled)
    override fun onNativeOpacityChanged(opacity: Float) = GtkWidgetLifecycleBridge.onOpacityChanged(this, opacity)
    override fun onNativeTooltipChanged(tooltip: String?) = GtkWidgetLifecycleBridge.onTooltipChanged(this, tooltip)
    override fun onNativeCursorChanged(cursor: CssCursorValue?) = GtkWidgetLifecycleBridge.onCursorChanged(this, cursor)
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) = GtkWidgetLifecycleBridge.onPointerEventsChanged(this, pointerEventsNone)
    override fun onNativeZIndexChanged(zIndex: Int) = GtkWidgetLifecycleBridge.onZIndexChanged(this, zIndex)
    override fun onNativeTransformChanged(transform: CssTransform?) = GtkWidgetLifecycleBridge.onTransformChanged(this, transform)
    override fun onNativeStyleSynced() = GtkWidgetLifecycleBridge.onStyleSynced(this)

    override fun dispose() {
        nativeWidget.realGtkWidgetPtr?.let { pop ->
            if (Gtk4DynamicBindings.gtkWidgetGetParent(pop) != null) {
                Gtk4DynamicBindings.gtkWidgetUnparent(pop)
            }
        }
        closedRef?.dispose()
        closedRef = null
        super.dispose()
    }

    companion object {
        private val popoverClosedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
            val impl = userData?.asStableRef<GtkPopoverNativeImpl>()?.get() ?: return@staticCFunction
            impl.nativeWidget.attributes["open"] = false
        }
    }
}
