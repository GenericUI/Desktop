@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopDropdownNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkSurfaceRenderHelper
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlin.math.ceil
import kotlin.math.max

/**
 * Retained GTK4 dropdown implementation mounting a genuine `GtkMenuButton + GtkPopoverMenu` for
 * `DropdownButton`, and a linked `[GtkButton + GtkMenuButton]` box for `SplitDropdownButton`.
 */
class GtkDropdownNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkMenuButton"),
    classPrefix: String = "dropdown"
) : DesktopDropdownNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private class ActionPayload(
        val owner: GtkDropdownNativeImpl,
        val itemId: String
    )

    private var splitPrimaryClickRef: StableRef<GtkDropdownNativeImpl>? = null

    init {
        nativeWidget.ownerImpl = this
        syncClassMetadata()
        nativeWidget.widgetMaterializer = { materializeDropdown() }
    }

    private fun syncClassMetadata() {
        val isSplit = isSplitDropdownVariant()
        nativeWidget.updateActiveGtkClassName(if (isSplit) "GtkBox_SplitDropdown" else "GtkMenuButton")
    }

    private fun materializeDropdown(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null
        val isSplit = isSplitDropdownVariant()
        syncClassMetadata()

        val rootPtr: COpaquePointer
        val menuBtn: COpaquePointer
        val focusTarget: COpaquePointer

        if (isSplit) {
            val box = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL, 0) ?: return null
            Gtk4DynamicBindings.gtkWidgetAddCssClass(box, "linked")
            val primaryBtn = Gtk4DynamicBindings.gtkButtonNew()
            val dropBtn = Gtk4DynamicBindings.gtkMenuButtonNew()
            if (primaryBtn == null || dropBtn == null) return null

            Gtk4DynamicBindings.gtkButtonSetLabel(primaryBtn, labelText)
            splitPrimaryClickRef?.dispose()
            val ref = StableRef.create(this)
            splitPrimaryClickRef = ref
            LinuxGlibGioBindings.signalConnect(
                instance = primaryBtn,
                signalName = "clicked",
                handler = splitPrimaryClickedCb.reinterpret(),
                userData = ref.asCPointer()
            )

            Gtk4DynamicBindings.gtkBoxAppend(box, primaryBtn)
            Gtk4DynamicBindings.gtkBoxAppend(box, dropBtn)
            nativeWidget.primaryLeafPtr = primaryBtn
            nativeWidget.secondaryLeafPtr = dropBtn
            rootPtr = box
            menuBtn = dropBtn
            focusTarget = primaryBtn
        } else {
            val mb = Gtk4DynamicBindings.gtkMenuButtonNew() ?: return null
            Gtk4DynamicBindings.gtkMenuButtonSetLabel(mb, labelText)
            nativeWidget.primaryLeafPtr = mb
            nativeWidget.secondaryLeafPtr = null
            rootPtr = mb
            menuBtn = mb
            focusTarget = mb
        }

        rebuildMenuModel(rootPtr, menuBtn)
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = rootPtr,
            focusTargetPtr = focusTarget,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = true
        )
        return rootPtr
    }

    private fun rebuildMenuModel(actionHost: COpaquePointer, menuBtn: COpaquePointer) {
        val gMenu = Gtk4DynamicBindings.gMenuNew() ?: return
        val actionGroup = Gtk4DynamicBindings.gSimpleActionGroupNew()
        if (actionGroup != null) {
            itemsList.forEachIndexed { idx, (id, text) ->
                val actionName = "item_$idx"
                val act = Gtk4DynamicBindings.gSimpleActionNew(actionName)
                if (act != null) {
                    val payloadRef = StableRef.create(ActionPayload(this, id))
                    LinuxGlibGioBindings.signalConnect(
                        instance = act,
                        signalName = "activate",
                        handler = actionActivatedCb.reinterpret(),
                        userData = payloadRef.asCPointer(),
                        destroyNotify = destroyPayloadClosureCb.reinterpret()
                    )
                    Gtk4DynamicBindings.gActionMapAddAction(actionGroup, act)
                    LinuxGlibGioBindings.objectUnref(act)
                }
                Gtk4DynamicBindings.gMenuAppend(gMenu, text, "dropdown.$actionName")
            }
            Gtk4DynamicBindings.gtkWidgetInsertActionGroup(actionHost, "dropdown", actionGroup)
            LinuxGlibGioBindings.objectUnref(actionGroup)
        }
        val popoverMenu = Gtk4DynamicBindings.gtkPopoverMenuNewFromModel(gMenu)
        if (popoverMenu != null) {
            Gtk4DynamicBindings.gtkMenuButtonSetPopover(menuBtn, popoverMenu)
            nativeWidget.popoverPtr = popoverMenu
        } else {
            Gtk4DynamicBindings.gtkMenuButtonSetMenuModel(menuBtn, gMenu)
        }
        LinuxGlibGioBindings.objectUnref(gMenu)
    }

    override fun onDropdownVariantChanged() {
        syncClassMetadata()
        val oldPtr = nativeWidget.realGtkWidgetPtr ?: return
        nativeWidget.realGtkWidgetPtr = null
        val newPtr = materializeDropdown()
        if (newPtr != null) {
            GtkWidgetLifecycleBridge.swapLiveWidgetInParent(this, oldPtr, newPtr)
        }
    }

    override fun setLabel(label: String) {
        super.setLabel(label)
        val ptr = nativeWidget.realGtkWidgetPtr ?: return
        if (isSplitDropdownVariant()) {
            nativeWidget.primaryLeafPtr?.let { Gtk4DynamicBindings.gtkButtonSetLabel(it, label) }
        } else {
            Gtk4DynamicBindings.gtkMenuButtonSetLabel(ptr, label)
        }
        GtkCustomLayoutManager.requestRelayout(ptr)
    }

    override fun setItems(items: List<Pair<String, String>>) {
        super.setItems(items)
        val ptr = nativeWidget.realGtkWidgetPtr ?: return
        val menuBtn = if (isSplitDropdownVariant()) nativeWidget.secondaryLeafPtr else ptr
        if (menuBtn != null) {
            rebuildMenuModel(ptr, menuBtn)
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val ptr = nativeWidget.realGtkWidgetPtr
        if (ptr != null) {
            val (w, h) = GtkCustomLayoutManager.measureGtkWidgetIntrinsic(ptr, maxWidth, maxHeight)
            if (w > 0 && h > 0) return Size(w, h)
        }
        val (tw, th) = GtkSurfaceRenderHelper.measureTextSize(labelText.ifEmpty { "Menu" }, 14f)
        val arrowPad = if (isSplitDropdownVariant()) 56 else 40
        return Size(max(80, tw + arrowPad), max(34, th + 14))
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
        splitPrimaryClickRef?.dispose()
        splitPrimaryClickRef = null
        super.dispose()
    }

    companion object {
        private val splitPrimaryClickedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
            val impl = userData?.asStableRef<GtkDropdownNativeImpl>()?.get() ?: return@staticCFunction
            if (!impl.nativeWidget.pointerEventsNone && impl.nativeWidget.enabled) {
                impl.dispatchPrimaryAction()
            }
        }

        private val actionActivatedCb = staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, Unit> { _, _, userData ->
            val payload = userData?.asStableRef<ActionPayload>()?.get() ?: return@staticCFunction
            payload.owner.dispatchItemSelected(payload.itemId)
        }

        private val destroyPayloadClosureCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { data, _ ->
            data?.asStableRef<ActionPayload>()?.dispose()
        }
    }
}
