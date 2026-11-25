@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.widget.DesktopTableNativeImpl
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
import kotlin.math.max

/**
 * Retained GTK4 data table mounting a genuine `GtkColumnView` inside `GtkScrolledWindow`,
 * using `GtkColumnViewColumn` + `GtkSignalListItemFactory` + `GtkStringList` row encoding.
 */
class GtkTableNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkColumnView"),
    classPrefix: String = "table"
) : DesktopTableNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private class ColumnBindPayload(
        val colIndex: Int
    )

    private var columnViewPtr: COpaquePointer? = null
    private val attachedColumns = mutableListOf<COpaquePointer>()
    private var columnWeightsArr: FloatArray = FloatArray(0)

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeTable() }
    }

    private fun materializeTable(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val sw = Gtk4DynamicBindings.gtkScrolledWindowNew() ?: return null
        Gtk4DynamicBindings.gtkScrolledWindowSetPolicy(
            sw,
            Gtk4DynamicBindings.GTK_POLICY_AUTOMATIC,
            Gtk4DynamicBindings.GTK_POLICY_AUTOMATIC
        )
        val cv = Gtk4DynamicBindings.gtkColumnViewNew(buildSelectionModel()) ?: return null
        columnViewPtr = cv
        rebuildColumns(cv)
        Gtk4DynamicBindings.gtkScrolledWindowSetChild(sw, cv)

        nativeWidget.scrolledWindowPtr = sw
        nativeWidget.primaryLeafPtr = cv
        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = sw,
            focusTargetPtr = cv,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = true
        )
        return sw
    }

    private fun buildSelectionModel(): COpaquePointer? {
        val encodedRows = rowsList.map { row ->
            row.joinToString(separator = CELL_SEPARATOR)
        }
        val strList = Gtk4DynamicBindings.gtkStringListNew(encodedRows) ?: return null
        val sel = Gtk4DynamicBindings.gtkSingleSelectionNew(strList)
        LinuxGlibGioBindings.objectUnref(strList)
        return sel
    }

    private fun rebuildColumns(cv: COpaquePointer) {
        for (oldCol in attachedColumns) {
            Gtk4DynamicBindings.gtkColumnViewRemoveColumn(cv, oldCol)
        }
        attachedColumns.clear()

        val colCount = max(headersList.size, rowsList.maxOfOrNull { it.size } ?: 0)
        for (i in 0 until colCount) {
            val title = headersList.getOrNull(i) ?: "Col ${i + 1}"
            val factory = Gtk4DynamicBindings.gtkSignalListItemFactoryNew()
            if (factory != null) {
                LinuxGlibGioBindings.signalConnect(
                    instance = factory,
                    signalName = "setup",
                    handler = listItemSetupCb.reinterpret(),
                    userData = null
                )
                val payloadRef = StableRef.create(ColumnBindPayload(i))
                LinuxGlibGioBindings.signalConnect(
                    instance = factory,
                    signalName = "bind",
                    handler = listItemBindCb.reinterpret(),
                    userData = payloadRef.asCPointer(),
                    destroyNotify = destroyPayloadClosureCb.reinterpret()
                )
            }
            val col = Gtk4DynamicBindings.gtkColumnViewColumnNew(title, factory)
            if (factory != null) {
                LinuxGlibGioBindings.objectUnref(factory)
            }
            if (col != null) {
                Gtk4DynamicBindings.gtkColumnViewColumnSetExpand(col, true)
                Gtk4DynamicBindings.gtkColumnViewColumnSetResizable(col, true)
                Gtk4DynamicBindings.gtkColumnViewAppendColumn(cv, col)
                attachedColumns.add(col)
                LinuxGlibGioBindings.objectUnref(col)
            }
        }
    }

    private fun refreshRows() {
        val cv = columnViewPtr ?: return
        val sel = buildSelectionModel()
        Gtk4DynamicBindings.gtkColumnViewSetModel(cv, sel)
        if (sel != null) LinuxGlibGioBindings.objectUnref(sel)
    }

    override fun setHeaders(headers: List<String>) {
        super.setHeaders(headers)
        columnViewPtr?.let { rebuildColumns(it) }
        GtkWidgetLifecycleBridge.requestParentRelayout(this)
    }

    override fun setRows(rows: List<List<String>>) {
        super.setRows(rows)
        val cv = columnViewPtr ?: return
        if (attachedColumns.size < (rows.maxOfOrNull { it.size } ?: 0)) {
            rebuildColumns(cv)
        }
        refreshRows()
        GtkWidgetLifecycleBridge.requestParentRelayout(this)
    }

    override fun setColumnWeights(weights: FloatArray) {
        columnWeightsArr = weights.copyOf()
        nativeWidget.attributes["columnWeights"] = columnWeightsArr.toList()
        columnViewPtr?.let { rebuildColumns(it) }
    }

    fun getColumnWeights(): List<Float> = columnWeightsArr.toList()

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val ptr = nativeWidget.realGtkWidgetPtr
        if (ptr != null) {
            val (w, h) = GtkCustomLayoutManager.measureGtkWidgetIntrinsic(ptr, maxWidth, maxHeight)
            if (w > 0 && h > 0) return Size(max(180, w), max(100, h))
        }
        val cols = max(1, max(headersList.size, rowsList.maxOfOrNull { it.size } ?: 1))
        val prefW = cols * 110
        val prefH = 38 + rowsList.size * 30
        return Size(prefW.coerceAtLeast(180), prefH.coerceIn(100, 360))
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
        private const val CELL_SEPARATOR = "\u001F"

        private val listItemSetupCb = staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, Unit> { _, listItem, _ ->
            if (listItem == null) return@staticCFunction
            val lbl = Gtk4DynamicBindings.gtkLabelNew("") ?: return@staticCFunction
            Gtk4DynamicBindings.gtkLabelSetXalign(lbl, 0f)
            Gtk4DynamicBindings.gtkLabelSetEllipsize(lbl, Gtk4DynamicBindings.PANGO_ELLIPSIZE_END)
            Gtk4DynamicBindings.gtkWidgetSetMargins(lbl, 4, 4, 8, 8)
            Gtk4DynamicBindings.gtkListItemSetChild(listItem, lbl)
        }

        private val listItemBindCb = staticCFunction<COpaquePointer?, COpaquePointer?, COpaquePointer?, Unit> { _, listItem, userData ->
            if (listItem == null || userData == null) return@staticCFunction
            val payload = userData.asStableRef<ColumnBindPayload>().get()
            val lbl = Gtk4DynamicBindings.gtkListItemGetChild(listItem) ?: return@staticCFunction
            val itemObj = Gtk4DynamicBindings.gtkListItemGetItem(listItem) ?: return@staticCFunction
            val raw = Gtk4DynamicBindings.gtkStringObjectGetString(itemObj) ?: ""
            val cells = raw.split(CELL_SEPARATOR)
            Gtk4DynamicBindings.gtkLabelSetText(lbl, cells.getOrNull(payload.colIndex) ?: "")
        }

        private val destroyPayloadClosureCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { data, _ ->
            data?.asStableRef<ColumnBindPayload>()?.dispose()
        }
    }
}
