@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuBarNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkGMenuBuilder
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer

/**
 * Retained GTK4 application menu bar mounting `GtkPopoverMenuBar` driven by a live GIO `GMenu`
 * and `GSimpleActionGroup`.
 */
class GtkMenuBarNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkPopoverMenuBar")
) : DesktopMenuBarNativeImpl<GtkWidgetHandle>(nativeWidget) {

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeMenuBar() }
    }

    private fun materializeMenuBar(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val pmb = Gtk4DynamicBindings.gtkPopoverMenuBarNewFromModel(null) ?: return null
        nativeWidget.primaryLeafPtr = pmb
        rebuildModel(pmb)
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = pmb,
            focusTargetPtr = pmb,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = false
        )
        return pmb
    }

    private fun rebuildModel(pmb: COpaquePointer? = nativeWidget.realGtkWidgetPtr) {
        pmb ?: return
        val pair = GtkGMenuBuilder.buildMenuAndActionGroup(
            children = getLogicalChildren(),
            actionPrefix = "menubar",
            wrapTopLevelSubmenus = true
        ) ?: return
        val (gMenu, actionGroup) = pair
        Gtk4DynamicBindings.gtkWidgetInsertActionGroup(pmb, "menubar", actionGroup)
        Gtk4DynamicBindings.gtkPopoverMenuBarSetMenuModel(pmb, gMenu)
        LinuxGlibGioBindings.objectUnref(actionGroup)
        LinuxGlibGioBindings.objectUnref(gMenu)
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        if (childImpl is GtkMenuNativeImpl) {
            childImpl.onStructureChanged = { rebuildModel() }
        }
        rebuildModel()
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        rebuildModel()
    }

    override fun onChildrenClearedNative() {
        rebuildModel()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val ptr = nativeWidget.realGtkWidgetPtr
        if (ptr != null) {
            val (w, h) = GtkCustomLayoutManager.measureGtkWidgetIntrinsic(ptr, maxWidth, maxHeight)
            if (w > 0 && h > 0) return Size(w, h)
        }
        return Size((getLogicalChildren().size.coerceAtLeast(1) * 72), 32)
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
