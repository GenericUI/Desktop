@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.shell.DesktopMenuItemNativeImpl
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.event.KeyShortcut
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction

/**
 * Retained GTK4 menu item backed by GIO `GMenuItem` metadata and optional live button realization.
 */
class GtkMenuItemNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GMenuItem"),
    checkable: Boolean = false,
    radio: Boolean = false
) : DesktopMenuItemNativeImpl<GtkWidgetHandle>(nativeWidget, checkable, radio) {

    var onItemMetadataChanged: (() -> Unit)? = null
    private var clickRef: StableRef<GtkMenuItemNativeImpl>? = null

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeMenuItemWidget() }
    }

    private fun materializeMenuItemWidget(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null
        val btn = Gtk4DynamicBindings.gtkButtonNewWithLabel(formatLabel()) ?: return null
        Gtk4DynamicBindings.gtkWidgetAddCssClass(btn, "flat")
        clickRef?.dispose()
        val ref = StableRef.create(this)
        clickRef = ref
        LinuxGlibGioBindings.signalConnect(btn, "clicked", menuBtnClickedCb.reinterpret(), ref.asCPointer())
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = btn,
            focusTargetPtr = btn,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = true
        )
        return btn
    }

    private fun formatLabel(): String {
        val prefix = if (checkable && checkedState) "✓ " else ""
        val sc = shortcutVal?.toString()?.let { "\t$it" } ?: ""
        return "$prefix$labelText$sc"
    }

    override fun setLabel(label: String) {
        super.setLabel(label)
        nativeWidget.realGtkWidgetPtr?.let { Gtk4DynamicBindings.gtkButtonSetLabel(it, formatLabel()) }
        onItemMetadataChanged?.invoke()
    }

    override fun setShortcut(shortcut: KeyShortcut?) {
        super.setShortcut(shortcut)
        nativeWidget.realGtkWidgetPtr?.let { Gtk4DynamicBindings.gtkButtonSetLabel(it, formatLabel()) }
        onItemMetadataChanged?.invoke()
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        nativeWidget.realGtkWidgetPtr?.let { Gtk4DynamicBindings.gtkButtonSetLabel(it, formatLabel()) }
        onItemMetadataChanged?.invoke()
    }

    override fun dispose() {
        clickRef?.dispose()
        clickRef = null
        super.dispose()
    }

    companion object {
        private val menuBtnClickedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
            val impl = userData?.asStableRef<GtkMenuItemNativeImpl>()?.get() ?: return@staticCFunction
            if (impl.isEnabled()) {
                impl.dispatchClick()
            }
        }
    }
}
