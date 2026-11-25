@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuNativeImpl
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkGMenuBuilder
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import kotlinx.cinterop.COpaquePointer

/**
 * Retained GTK4 menu model backing `Menu` submenus inside `GtkMenuBarNativeImpl` and
 * `GtkContextMenuNativeImpl` via GIO `GMenu` and live optional `GtkMenuButton` materialization.
 */
class GtkMenuNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GMenu")
) : DesktopMenuNativeImpl<GtkWidgetHandle>(nativeWidget) {

    var onStructureChanged: (() -> Unit)? = null

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeMenuButton() }
    }

    private fun materializeMenuButton(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null
        val btn = Gtk4DynamicBindings.gtkMenuButtonNew() ?: return null
        Gtk4DynamicBindings.gtkMenuButtonSetLabel(btn, getLabel())
        syncMenuModel(btn)
        nativeWidget.primaryLeafPtr = btn
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = btn,
            focusTargetPtr = btn,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = false
        )
        return btn
    }

    private fun syncMenuModel(btn: COpaquePointer? = nativeWidget.realGtkWidgetPtr) {
        btn ?: return
        val pair = GtkGMenuBuilder.buildMenuAndActionGroup(
            children = getLogicalChildren(),
            actionPrefix = "submenu_${nativeWidget.nativePointer}",
            wrapTopLevelSubmenus = false
        ) ?: return
        val (gMenu, actionGroup) = pair
        Gtk4DynamicBindings.gtkWidgetInsertActionGroup(btn, "submenu_${nativeWidget.nativePointer}", actionGroup)
        Gtk4DynamicBindings.gtkMenuButtonSetMenuModel(btn, gMenu)
        LinuxGlibGioBindings.objectUnref(actionGroup)
        LinuxGlibGioBindings.objectUnref(gMenu)
    }

    override fun setLabel(label: String) {
        super.setLabel(label)
        nativeWidget.realGtkWidgetPtr?.let { Gtk4DynamicBindings.gtkMenuButtonSetLabel(it, label) }
        onStructureChanged?.invoke()
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        when (childImpl) {
            is GtkMenuItemNativeImpl -> childImpl.onItemMetadataChanged = {
                syncMenuModel()
                onStructureChanged?.invoke()
            }
            is GtkMenuNativeImpl -> childImpl.onStructureChanged = {
                syncMenuModel()
                onStructureChanged?.invoke()
            }
        }
        syncMenuModel()
        onStructureChanged?.invoke()
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        syncMenuModel()
        onStructureChanged?.invoke()
    }

    override fun onChildrenClearedNative() {
        syncMenuModel()
        onStructureChanged?.invoke()
    }
}
