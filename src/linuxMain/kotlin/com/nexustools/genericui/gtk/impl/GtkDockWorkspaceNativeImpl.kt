@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockWorkspaceNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.dock.DockPosition
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.nativeimpl.dock.DockAreaNativeImpl
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer

/**
 * Retained GTK4 multi-pane docking workspace mounting nested `GtkPaned` splitters organizing
 * left, right, top, bottom dock areas around the central document surface.
 */
class GtkDockWorkspaceNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkPaned_DockWorkspace")
) : DesktopDockWorkspaceNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private var outerHorizPaned: COpaquePointer? = null
    private var rightHorizPaned: COpaquePointer? = null
    private var centerVertTopPaned: COpaquePointer? = null
    private var centerVertBottomPaned: COpaquePointer? = null

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeWorkspace() }
    }

    private fun materializeWorkspace(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val hOuter = Gtk4DynamicBindings.gtkPanedNew(Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL) ?: return null
        val hRight = Gtk4DynamicBindings.gtkPanedNew(Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL) ?: return null
        val vTop = Gtk4DynamicBindings.gtkPanedNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL) ?: return null
        val vBottom = Gtk4DynamicBindings.gtkPanedNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL) ?: return null

        Gtk4DynamicBindings.gtkPanedSetResizeAndShrink(hOuter, resizeStart = false, resizeEnd = true)
        Gtk4DynamicBindings.gtkPanedSetResizeAndShrink(hRight, resizeStart = true, resizeEnd = false)
        Gtk4DynamicBindings.gtkPanedSetResizeAndShrink(vTop, resizeStart = false, resizeEnd = true)
        Gtk4DynamicBindings.gtkPanedSetResizeAndShrink(vBottom, resizeStart = true, resizeEnd = false)

        Gtk4DynamicBindings.gtkPanedSetEndChild(vTop, vBottom)
        Gtk4DynamicBindings.gtkPanedSetStartChild(hRight, vTop)
        Gtk4DynamicBindings.gtkPanedSetEndChild(hOuter, hRight)

        outerHorizPaned = hOuter
        rightHorizPaned = hRight
        centerVertTopPaned = vTop
        centerVertBottomPaned = vBottom

        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = hOuter,
            focusTargetPtr = hOuter,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = false
        )
        syncAreasAndCentral()
        return hOuter
    }

    private fun syncAreasAndCentral() {
        val hOuter = outerHorizPaned ?: return
        val hRight = rightHorizPaned ?: return
        val vTop = centerVertTopPaned ?: return
        val vBottom = centerVertBottomPaned ?: return

        attachAreaPtr(hOuter, start = true, areaImpl = dockAreas[DockPosition.LEFT], defaultPos = 240)
        attachAreaPtr(hRight, start = false, areaImpl = dockAreas[DockPosition.RIGHT], defaultPos = 640)
        attachAreaPtr(vTop, start = true, areaImpl = dockAreas[DockPosition.TOP], defaultPos = 160)
        attachAreaPtr(vBottom, start = false, areaImpl = dockAreas[DockPosition.BOTTOM], defaultPos = 420)

        val centralPtr = (centralDocument?.nativeWidget as? GtkWidgetHandle)?.let { h ->
            h.ensureLiveWidgetPtr() ?: h.realGtkWidgetPtr
        }
        Gtk4DynamicBindings.gtkPanedSetStartChild(vBottom, centralPtr)
    }

    private fun attachAreaPtr(paned: COpaquePointer, start: Boolean, areaImpl: DockAreaNativeImpl<*>?, defaultPos: Int) {
        val areaHandle = areaImpl?.nativeWidget as? GtkWidgetHandle
        val areaPtr = areaHandle?.ensureLiveWidgetPtr() ?: areaHandle?.realGtkWidgetPtr
        if (start) {
            Gtk4DynamicBindings.gtkPanedSetStartChild(paned, areaPtr)
            if (areaPtr != null) Gtk4DynamicBindings.gtkPanedSetPosition(paned, defaultPos)
        } else {
            Gtk4DynamicBindings.gtkPanedSetEndChild(paned, areaPtr)
            if (areaPtr != null) Gtk4DynamicBindings.gtkPanedSetPosition(paned, defaultPos)
        }
    }

    override fun setCentralDocumentNative(centralImpl: NativeImpl<*>?) {
        super.setCentralDocumentNative(centralImpl)
        syncAreasAndCentral()
    }

    override fun attachDockAreaNative(position: DockPosition, areaImpl: DockAreaNativeImpl<*>) {
        super.attachDockAreaNative(position, areaImpl)
        syncAreasAndCentral()
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
}
