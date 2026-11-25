@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopBottomNavigationBarNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction

/**
 * Retained GTK4 bottom navigation bar mounting a horizontal homogeneous `GtkBox` of `GtkButton`
 * switcher tabs synced cleanly with selection callbacks.
 */
class GtkBottomNavigationBarNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("AdwViewSwitcherBar")
) : DesktopBottomNavigationBarNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private class ItemClickPayload(
        val owner: GtkBottomNavigationBarNativeImpl,
        val itemId: String
    )

    private var barBoxPtr: COpaquePointer? = null
    private val itemButtons = linkedMapOf<String, COpaquePointer>()

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeBottomNav() }
    }

    private fun materializeBottomNav(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val box = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL, 4) ?: return null
        Gtk4DynamicBindings.gtkWidgetAddCssClass(box, "bottom-nav")
        barBoxPtr = box
        nativeWidget.primaryLeafPtr = box
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = box,
            focusTargetPtr = box,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = false
        )
        rebuildButtons()
        return box
    }

    private fun rebuildButtons() {
        val box = barBoxPtr ?: return
        var cur = Gtk4DynamicBindings.gtkWidgetGetFirstChild(box)
        while (cur != null) {
            val next = Gtk4DynamicBindings.gtkWidgetGetNextSibling(cur)
            Gtk4DynamicBindings.gtkBoxRemove(box, cur)
            cur = next
        }
        itemButtons.clear()

        for ((id, label, icon) in itemsList) {
            val text = if (!icon.isNullOrBlank()) "$icon\n$label" else label
            val btn = Gtk4DynamicBindings.gtkButtonNewWithLabel(text) ?: continue
            Gtk4DynamicBindings.gtkWidgetAddCssClass(btn, "flat")
            Gtk4DynamicBindings.gtkWidgetSetExpand(btn, hexpand = true, vexpand = true)
            if (id == selectedId) {
                Gtk4DynamicBindings.gtkWidgetAddCssClass(btn, "suggested-action")
            }
            val payloadRef = StableRef.create(ItemClickPayload(this, id))
            LinuxGlibGioBindings.signalConnect(
                instance = btn,
                signalName = "clicked",
                handler = itemClickedCb.reinterpret(),
                userData = payloadRef.asCPointer(),
                destroyNotify = destroyItemPayloadCb.reinterpret()
            )
            Gtk4DynamicBindings.gtkBoxAppend(box, btn)
            itemButtons[id] = btn
        }
    }

    override fun setItems(items: List<Triple<String, String, String?>>) {
        super.setItems(items)
        rebuildButtons()
        GtkWidgetLifecycleBridge.requestParentRelayout(this)
    }

    override fun setSelectedItemId(itemId: String) {
        val prev = selectedId
        super.setSelectedItemId(itemId)
        prev?.let { itemButtons[it]?.let { btn -> Gtk4DynamicBindings.gtkWidgetRemoveCssClass(btn, "suggested-action") } }
        itemButtons[itemId]?.let { btn -> Gtk4DynamicBindings.gtkWidgetAddCssClass(btn, "suggested-action") }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val ptr = nativeWidget.realGtkWidgetPtr
        if (ptr != null) {
            val (w, h) = GtkCustomLayoutManager.measureGtkWidgetIntrinsic(ptr, maxWidth, maxHeight)
            if (w > 0 && h > 0) return Size(w.coerceAtLeast(180), h.coerceAtLeast(48))
        }
        return Size((itemsList.size.coerceAtLeast(1) * 90).coerceAtLeast(180), 54)
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

    companion object {
        private val itemClickedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
            val p = userData?.asStableRef<ItemClickPayload>()?.get() ?: return@staticCFunction
            p.owner.setSelectedItemId(p.itemId)
            p.owner.onItemSelectedListener?.invoke(p.itemId)
        }

        private val destroyItemPayloadCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { data, _ ->
            data?.asStableRef<ItemClickPayload>()?.dispose()
        }
    }
}
