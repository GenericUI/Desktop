@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.component.dock.ToolPaletteItem
import com.nexustools.genericui.desktop.dock.DesktopToolPaletteNativeImpl
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
import kotlin.math.ceil

/**
 * Retained GTK4 tool palette mounting a `GtkScrolledWindow` over a multi-column `GtkFlowBox`
 * of `GtkButton` tool items with active state highlighting and tooltip hints.
 */
class GtkToolPaletteNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkFlowBox_ToolPalette")
) : DesktopToolPaletteNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private class ToolClickPayload(
        val owner: GtkToolPaletteNativeImpl,
        val toolId: String
    )

    private var flowBoxPtr: COpaquePointer? = null
    private val toolButtons = linkedMapOf<String, COpaquePointer>()

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeToolPalette() }
    }

    private fun materializeToolPalette(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val sw = Gtk4DynamicBindings.gtkScrolledWindowNew() ?: return null
        val fb = Gtk4DynamicBindings.gtkFlowBoxNew() ?: return null
        Gtk4DynamicBindings.gtkFlowBoxSetSelectionMode(fb, Gtk4DynamicBindings.GTK_SELECTION_NONE)
        Gtk4DynamicBindings.gtkFlowBoxSetColumnsRange(fb, columnCount, columnCount)
        Gtk4DynamicBindings.gtkScrolledWindowSetPolicy(
            sw,
            Gtk4DynamicBindings.GTK_POLICY_NEVER,
            Gtk4DynamicBindings.GTK_POLICY_AUTOMATIC
        )
        Gtk4DynamicBindings.gtkScrolledWindowSetChild(sw, fb)

        flowBoxPtr = fb
        nativeWidget.scrolledWindowPtr = sw
        nativeWidget.primaryLeafPtr = fb

        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = sw,
            focusTargetPtr = fb,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = false
        )
        rebuildToolItems()
        return sw
    }

    private fun rebuildToolItems() {
        val fb = flowBoxPtr ?: return
        var cur = Gtk4DynamicBindings.gtkWidgetGetFirstChild(fb)
        while (cur != null) {
            val next = Gtk4DynamicBindings.gtkWidgetGetNextSibling(cur)
            Gtk4DynamicBindings.gtkFlowBoxRemove(fb, cur)
            cur = next
        }
        toolButtons.clear()

        for (tool in toolsList) {
            val btn = Gtk4DynamicBindings.gtkButtonNewWithLabel(tool.name) ?: continue
            tool.tooltip?.let { Gtk4DynamicBindings.gtkWidgetSetTooltipText(btn, it) }
            if (tool.id == currentToolId) {
                Gtk4DynamicBindings.gtkWidgetAddCssClass(btn, "suggested-action")
            }
            val ref = StableRef.create(ToolClickPayload(this, tool.id))
            LinuxGlibGioBindings.signalConnect(
                instance = btn,
                signalName = "clicked",
                handler = toolClickedCb.reinterpret(),
                userData = ref.asCPointer(),
                destroyNotify = destroyToolPayloadCb.reinterpret()
            )
            Gtk4DynamicBindings.gtkFlowBoxInsert(fb, btn, -1)
            toolButtons[tool.id] = btn
        }
    }

    override fun setColumns(columns: Int) {
        super.setColumns(columns)
        flowBoxPtr?.let { Gtk4DynamicBindings.gtkFlowBoxSetColumnsRange(it, columnCount, columnCount) }
    }

    override fun setTools(tools: List<ToolPaletteItem>, activeToolId: String?) {
        super.setTools(tools, activeToolId)
        rebuildToolItems()
    }

    override fun setActiveToolId(toolId: String?) {
        val prev = currentToolId
        super.setActiveToolId(toolId)
        prev?.let { toolButtons[it]?.let { btn -> Gtk4DynamicBindings.gtkWidgetRemoveCssClass(btn, "suggested-action") } }
        toolId?.let { toolButtons[it]?.let { btn -> Gtk4DynamicBindings.gtkWidgetAddCssClass(btn, "suggested-action") } }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val ptr = nativeWidget.realGtkWidgetPtr
        if (ptr != null) {
            val (w, h) = GtkCustomLayoutManager.measureGtkWidgetIntrinsic(ptr, maxWidth, maxHeight)
            if (w > 0 && h > 0) return Size(w, h)
        }
        val cols = columnCount.coerceAtLeast(1)
        val rows = ceil(toolsList.size.toFloat() / cols.toFloat()).toInt().coerceAtLeast(1)
        return Size((cols * 56).coerceAtLeast(72), (rows * 44).coerceAtLeast(64))
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
        private val toolClickedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { _, userData ->
            val p = userData?.asStableRef<ToolClickPayload>()?.get() ?: return@staticCFunction
            p.owner.setActiveToolId(p.toolId)
            p.owner.onToolSelectedListener?.invoke(p.toolId)
        }

        private val destroyToolPayloadCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { data, _ ->
            data?.asStableRef<ToolClickPayload>()?.dispose()
        }
    }
}
