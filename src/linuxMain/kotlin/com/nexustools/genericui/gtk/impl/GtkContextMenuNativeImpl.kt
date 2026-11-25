@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.shell.DesktopContextMenuNativeImpl
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkGMenuBuilder
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import kotlinx.cinterop.COpaquePointer

/**
 * Retained GTK4 context menu mounting `GtkPopoverMenu` driven by a GIO `GMenu` and anchored
 * cleanly at `(x, y)` on `showAt(...)`.
 */
class GtkContextMenuNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkPopoverMenu")
) : DesktopContextMenuNativeImpl<GtkWidgetHandle>(nativeWidget) {

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeContextMenu() }
    }

    private fun materializeContextMenu(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val pm = Gtk4DynamicBindings.gtkPopoverMenuNewFromModel(null) ?: return null
        Gtk4DynamicBindings.gtkPopoverSetHasArrow(pm, false)
        Gtk4DynamicBindings.gtkPopoverSetAutohide(pm, true)
        rebuildModel(pm)
        nativeWidget.popoverPtr = pm
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = pm,
            focusTargetPtr = pm,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = false
        )
        return pm
    }

    private fun rebuildModel(pm: COpaquePointer? = nativeWidget.realGtkWidgetPtr) {
        pm ?: return
        val pair = GtkGMenuBuilder.buildMenuAndActionGroup(
            children = getLogicalChildren(),
            actionPrefix = "ctxmenu",
            wrapTopLevelSubmenus = false
        ) ?: return
        val (gMenu, actionGroup) = pair
        Gtk4DynamicBindings.gtkWidgetInsertActionGroup(pm, "ctxmenu", actionGroup)
        Gtk4DynamicBindings.gtkPopoverMenuSetMenuModel(pm, gMenu)
        LinuxGlibGioBindings.objectUnref(actionGroup)
        LinuxGlibGioBindings.objectUnref(gMenu)
    }

    override fun showAt(anchor: NativeImpl<*>?, x: Int, y: Int) {
        super.showAt(anchor, x, y)
        val pm = nativeWidget.ensureLiveWidgetPtr() ?: return
        val anchorHandle = anchor?.nativeWidget as? GtkWidgetHandle
        val anchorPtr = anchorHandle?.ensureLiveWidgetPtr() ?: anchorHandle?.realGtkWidgetPtr
        if (anchorPtr != null) {
            val existingParent = Gtk4DynamicBindings.gtkWidgetGetParent(pm)
            if (existingParent != anchorPtr) {
                if (existingParent != null) {
                    Gtk4DynamicBindings.gtkWidgetUnparent(pm)
                }
                Gtk4DynamicBindings.gtkWidgetSetParent(pm, anchorPtr)
            }
            Gtk4DynamicBindings.gtkPopoverSetPointingTo(pm, x, y, 1, 1)
            Gtk4DynamicBindings.gtkPopoverPopup(pm)
        }
    }

    override fun hide() {
        super.hide()
        nativeWidget.realGtkWidgetPtr?.let { Gtk4DynamicBindings.gtkPopoverPopdown(it) }
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        rebuildModel()
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        rebuildModel()
    }

    override fun onChildrenClearedNative() {
        rebuildModel()
    }

    override fun dispose() {
        nativeWidget.realGtkWidgetPtr?.let { pm ->
            if (Gtk4DynamicBindings.gtkWidgetGetParent(pm) != null) {
                Gtk4DynamicBindings.gtkWidgetUnparent(pm)
            }
        }
        super.dispose()
    }
}
