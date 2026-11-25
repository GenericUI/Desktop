@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyGridNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.value
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Retained GTK4 virtualized grid mounting a `GtkScrolledWindow` over an inner virtual-extent
 * `GtkBox`, attaching and recycling ONLY visible-row grid item `GtkWidget*`s on scroll.
 */
open class GtkLazyGridNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkGridView")
) : DesktopLazyGridNativeImpl<GtkWidgetHandle>(nativeWidget) {

    var minColumnWidthPx: Int = 0
        private set

    private var scrolledWindowPtr: COpaquePointer? = null
    private var contentBoxPtr: COpaquePointer? = null
    private var vAdjPtr: COpaquePointer? = null
    private var signalRef: StableRef<GtkLazyGridNativeImpl>? = null

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.attributes["useSystemControlAppearance"] = "false"
        nativeWidget.widgetMaterializer = { materializeLazyGrid() }
    }

    private fun materializeLazyGrid(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val sw = Gtk4DynamicBindings.gtkScrolledWindowNew() ?: return null
        val contentBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 0) ?: return null
        Gtk4DynamicBindings.gtkScrolledWindowSetPolicy(
            sw,
            Gtk4DynamicBindings.GTK_POLICY_NEVER,
            Gtk4DynamicBindings.GTK_POLICY_AUTOMATIC
        )

        val stable = StableRef.create(this)
        signalRef?.dispose()
        signalRef = stable
        Gtk4DynamicBindings.gObjectSetQdataFull(contentBox, quarkGridHost, stable.asCPointer(), null)
        val mgr = Gtk4DynamicBindings.gtkCustomLayoutNew(
            requestMode = requestModeCb.reinterpret(),
            measure = measureCb.reinterpret(),
            allocate = allocateCb.reinterpret()
        )
        if (mgr != null) {
            Gtk4DynamicBindings.gtkWidgetSetLayoutManager(contentBox, mgr)
        }
        Gtk4DynamicBindings.gtkScrolledWindowSetChild(sw, contentBox)

        vAdjPtr = Gtk4DynamicBindings.gtkScrolledWindowGetVadjustment(sw)?.also { adj ->
            LinuxGlibGioBindings.signalConnect(adj, "value-changed", adjChangedCb.reinterpret(), stable.asCPointer())
        }

        scrolledWindowPtr = sw
        contentBoxPtr = contentBox
        nativeWidget.scrolledWindowPtr = sw
        nativeWidget.containerLayoutBoxPtr = contentBox

        GtkWidgetLifecycleBridge.attachControllersAndState(
            impl = this,
            rootPtr = sw,
            focusTargetPtr = contentBox,
            isTextInput = false,
            dispatchClickOnPrimaryRelease = false,
            attachClickGesture = true
        )
        syncMountedCellsToBox()
        return sw
    }

    override fun rematerializeVisibleWindow() {
        super.rematerializeVisibleWindow()
        syncMountedCellsToBox()
    }

    private fun syncMountedCellsToBox() {
        val box = contentBoxPtr ?: return
        val wanted = HashSet<COpaquePointer>()
        for (impl in materializedItems.values) {
            val h = impl.nativeWidget as? GtkWidgetHandle ?: continue
            val p = h.ensureLiveWidgetPtr() ?: h.realGtkWidgetPtr ?: continue
            wanted.add(p)
        }
        var cur = Gtk4DynamicBindings.gtkWidgetGetFirstChild(box)
        while (cur != null) {
            val next = Gtk4DynamicBindings.gtkWidgetGetNextSibling(cur)
            if (cur !in wanted) {
                Gtk4DynamicBindings.gtkWidgetUnparent(cur)
            }
            cur = next
        }
        for (impl in materializedItems.values) {
            val h = impl.nativeWidget as? GtkWidgetHandle ?: continue
            val p = h.realGtkWidgetPtr ?: continue
            if (Gtk4DynamicBindings.gtkWidgetGetParent(p) != box) {
                GtkCustomLayoutManager.attachChildWidget(box, p, null)
            }
        }
        GtkCustomLayoutManager.requestRelayout(box)
    }

    private fun allocateVisibleCells(boxW: Int) {
        val cols = resolveEffectiveColumns(boxW).coerceAtLeast(1)
        val colW = (boxW / cols).coerceAtLeast(1)
        val rowH = viewportEngine.estimatedItemExtentPx.roundToInt().coerceAtLeast(1)
        for ((index, impl) in materializedItems) {
            val itemBase = impl as? DesktopBaseNativeImpl<*>
            val itemH = itemBase?.nativeWidget as? GtkWidgetHandle ?: continue
            val itemPtr = itemH.ensureLiveWidgetPtr() ?: itemH.realGtkWidgetPtr ?: continue
            val col = index % cols
            val row = index / cols
            val x = col * colW
            val y = row * rowH
            val w = if (col == cols - 1) (boxW - x).coerceAtLeast(1) else colW
            itemBase.setBounds(Rect(x, y, w, rowH))
            Gtk4DynamicBindings.gtkWidgetSizeAllocate(itemPtr, x, y, w, rowH, -1)
        }
    }

    override fun setMinColumnWidthPx(minWidthPx: Int) {
        minColumnWidthPx = minWidthPx.coerceAtLeast(0)
        super.setMinColumnWidthPx(minWidthPx)
        nativeWidget.attributes["minColumnWidthPx"] = minColumnWidthPx
        GtkCustomLayoutManager.requestRelayout(contentBoxPtr)
    }

    fun getMinColumnWidthPx(): Int = minColumnWidthPx

    fun resolveEffectiveColumns(availableWidthPx: Int): Int =
        if (minColumnWidthPx > 0 && availableWidthPx > 0) {
            max(1, availableWidthPx / minColumnWidthPx)
        } else {
            getColumnCount()
        }

    override fun setColumns(columns: Int) {
        super.setColumns(columns)
        GtkCustomLayoutManager.requestRelayout(contentBoxPtr)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (rect.width > 0 && minColumnWidthPx > 0) {
            val effCols = resolveEffectiveColumns(rect.width)
            if (effCols != viewportEngine.columns) {
                viewportEngine.columns = effCols
            }
        }
        val pageH = rect.height.toFloat().coerceAtLeast(1f)
        viewportEngine.viewportExtentPx = pageH
        rematerializeVisibleWindow()
        GtkWidgetLifecycleBridge.onBoundsChanged(this, rect)
    }

    override fun onNativeVisibilityChanged(visible: Boolean) = GtkWidgetLifecycleBridge.onVisibilityChanged(this, visible)
    override fun onNativeEnabledChanged(enabled: Boolean) = GtkWidgetLifecycleBridge.onEnabledChanged(this, enabled)
    override fun onNativeOpacityChanged(opacity: Float) = GtkWidgetLifecycleBridge.onOpacityChanged(this, opacity)
    override fun onNativeTooltipChanged(tooltip: String?) = GtkWidgetLifecycleBridge.onTooltipChanged(this, tooltip)
    override fun onNativeCursorChanged(cursor: CssCursorValue?) = GtkWidgetLifecycleBridge.onCursorChanged(this, cursor)
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) = GtkWidgetLifecycleBridge.onPointerEventsChanged(this, pointerEventsNone)
    override fun onNativeZIndexChanged(zIndex: Int) = GtkWidgetLifecycleBridge.onZIndexChanged(this, zIndex)
    override fun onNativeTransformChanged(transform: CssTransform?) = GtkWidgetLifecycleBridge.onTransformChanged(this, transform)
    override fun onNativeStyleSynced() = GtkWidgetLifecycleBridge.onStyleSynced(this)

    override fun setType(type: BootstrapType?) {
        super.setType(type)
        onNativeStyleSynced()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        onNativeStyleSynced()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        onNativeStyleSynced()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        onNativeStyleSynced()
    }

    override fun dispose() {
        signalRef?.dispose()
        signalRef = null
        super.dispose()
    }

    companion object {
        private val quarkGridHost by lazy { Gtk4DynamicBindings.gQuarkFromString("genericui-lazygrid-host") }

        private val adjChangedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { adj, userData ->
            val impl = userData?.asStableRef<GtkLazyGridNativeImpl>()?.get() ?: return@staticCFunction
            if (adj == null) return@staticCFunction
            impl.viewportEngine.scrollOffsetPx = Gtk4DynamicBindings.gtkAdjustmentGetValue(adj).toFloat().coerceAtLeast(0f)
            impl.viewportEngine.viewportExtentPx = Gtk4DynamicBindings.gtkAdjustmentGetPageSize(adj).toFloat().coerceAtLeast(1f)
            impl.rematerializeVisibleWindow()
        }

        private val requestModeCb = staticCFunction<COpaquePointer?, Int> { _ ->
            Gtk4DynamicBindings.GTK_SIZE_REQUEST_HEIGHT_FOR_WIDTH
        }

        private val measureCb = staticCFunction<
            COpaquePointer?, Int, Int,
            kotlinx.cinterop.CPointer<IntVar>?,
            kotlinx.cinterop.CPointer<IntVar>?,
            kotlinx.cinterop.CPointer<IntVar>?,
            kotlinx.cinterop.CPointer<IntVar>?,
            Unit
        > { box, orientation, forSize, minPtr, natPtr, minBasePtr, natBasePtr ->
            val impl = box?.let { Gtk4DynamicBindings.gObjectGetQdata(it, quarkGridHost)?.asStableRef<GtkLazyGridNativeImpl>()?.get() }
            val size = if (orientation == Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL) {
                240
            } else {
                val availW = if (forSize > 0) forSize else (impl?.getBounds()?.width ?: 300)
                val cols = impl?.resolveEffectiveColumns(availW)?.coerceAtLeast(1) ?: 1
                val rows = ceil((impl?.getTotalCellCount() ?: 0).toFloat() / cols.toFloat()).toInt()
                val rowH = impl?.viewportEngine?.estimatedItemExtentPx?.roundToInt()?.coerceAtLeast(1) ?: 36
                max(1, rows * rowH)
            }
            minPtr?.pointed?.value = size
            natPtr?.pointed?.value = size
            minBasePtr?.pointed?.value = -1
            natBasePtr?.pointed?.value = -1
        }

        private val allocateCb = staticCFunction<COpaquePointer?, Int, Int, Int, Unit> { box, width, _, _ ->
            box ?: return@staticCFunction
            val impl = Gtk4DynamicBindings.gObjectGetQdata(box, quarkGridHost)?.asStableRef<GtkLazyGridNativeImpl>()?.get()
                ?: return@staticCFunction
            impl.allocateVisibleCells(width)
        }
    }
}
