@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.impl

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyListNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.ui.Orientation
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.gtk.widget.GtkCustomLayoutManager
import com.nexustools.genericui.gtk.widget.GtkWidgetLifecycleBridge
import com.nexustools.genericui.linux.LinuxGlibGioBindings
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
 * Retained GTK4 virtualized list mounting a `GtkScrolledWindow` over an inner virtual-extent
 * `GtkBox`, attaching and recycling ONLY visible-window item `GtkWidget*`s on scroll.
 */
class GtkLazyListNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkListView")
) : DesktopLazyListNativeImpl<GtkWidgetHandle>(nativeWidget) {

    private var scrolledWindowPtr: COpaquePointer? = null
    private var contentBoxPtr: COpaquePointer? = null
    private var adjPtr: COpaquePointer? = null
    private var signalRef: StableRef<GtkLazyListNativeImpl>? = null

    init {
        nativeWidget.ownerImpl = this
        nativeWidget.widgetMaterializer = { materializeLazyList() }
    }

    private fun materializeLazyList(): COpaquePointer? {
        nativeWidget.realGtkWidgetPtr?.let { return it }
        if (!Gtk4DynamicBindings.ensureGtkInitialized()) return null

        val sw = Gtk4DynamicBindings.gtkScrolledWindowNew() ?: return null
        val contentBox = Gtk4DynamicBindings.gtkBoxNew(Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL, 0) ?: return null
        val isHoriz = orientationState == Orientation.HORIZONTAL

        Gtk4DynamicBindings.gtkScrolledWindowSetPolicy(
            sw,
            if (isHoriz) Gtk4DynamicBindings.GTK_POLICY_AUTOMATIC else Gtk4DynamicBindings.GTK_POLICY_NEVER,
            if (isHoriz) Gtk4DynamicBindings.GTK_POLICY_NEVER else Gtk4DynamicBindings.GTK_POLICY_AUTOMATIC
        )

        val stable = StableRef.create(this)
        signalRef?.dispose()
        signalRef = stable
        Gtk4DynamicBindings.gObjectSetQdataFull(
            obj = contentBox,
            quark = quarkListHost,
            data = stable.asCPointer(),
            destroy = null
        )
        val mgr = Gtk4DynamicBindings.gtkCustomLayoutNew(
            requestMode = requestModeCb.reinterpret(),
            measure = measureCb.reinterpret(),
            allocate = allocateCb.reinterpret()
        )
        if (mgr != null) {
            Gtk4DynamicBindings.gtkWidgetSetLayoutManager(contentBox, mgr)
        }
        Gtk4DynamicBindings.gtkScrolledWindowSetChild(sw, contentBox)

        adjPtr = if (isHoriz) {
            Gtk4DynamicBindings.gtkScrolledWindowGetHadjustment(sw)
        } else {
            Gtk4DynamicBindings.gtkScrolledWindowGetVadjustment(sw)
        }
        adjPtr?.let { adj ->
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
        syncMountedItemsToBox()
        return sw
    }

    override fun rematerializeVisibleWindow() {
        super.rematerializeVisibleWindow()
        syncMountedItemsToBox()
    }

    private fun syncMountedItemsToBox() {
        val box = contentBoxPtr ?: return
        val wantedPtrs = HashSet<COpaquePointer>()
        for (impl in materializedItems.values) {
            val h = impl.nativeWidget as? GtkWidgetHandle ?: continue
            val p = h.ensureLiveWidgetPtr() ?: h.realGtkWidgetPtr ?: continue
            wantedPtrs.add(p)
        }
        var cur = Gtk4DynamicBindings.gtkWidgetGetFirstChild(box)
        while (cur != null) {
            val next = Gtk4DynamicBindings.gtkWidgetGetNextSibling(cur)
            if (cur !in wantedPtrs) {
                Gtk4DynamicBindings.gtkWidgetUnparent(cur)
            }
            cur = next
        }
        for ((_, impl) in materializedItems) {
            val h = impl.nativeWidget as? GtkWidgetHandle ?: continue
            val p = h.realGtkWidgetPtr ?: continue
            if (Gtk4DynamicBindings.gtkWidgetGetParent(p) != box) {
                GtkCustomLayoutManager.attachChildWidget(box, p, null)
            }
        }
        GtkCustomLayoutManager.requestRelayout(box)
    }

    private fun allocateVisibleItems(boxW: Int, boxH: Int) {
        val isHoriz = orientationState == Orientation.HORIZONTAL
        val itemExtent = viewportEngine.estimatedItemExtentPx.roundToInt().coerceAtLeast(1)
        for ((index, impl) in materializedItems) {
            val itemBase = impl as? DesktopBaseNativeImpl<*>
            val itemH = itemBase?.nativeWidget as? GtkWidgetHandle ?: continue
            val itemPtr = itemH.ensureLiveWidgetPtr() ?: itemH.realGtkWidgetPtr ?: continue
            val x = if (isHoriz) index * itemExtent else 0
            val y = if (isHoriz) 0 else index * itemExtent
            val w = if (isHoriz) itemExtent else max(boxW, 1)
            val h = if (isHoriz) max(boxH, 1) else itemExtent
            itemBase.setBounds(Rect(x, y, w, h))
            Gtk4DynamicBindings.gtkWidgetSizeAllocate(itemPtr, x, y, w, h, -1)
        }
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        val isHoriz = orientationState == Orientation.HORIZONTAL
        val extent = (if (isHoriz) rect.width else rect.height).toFloat().coerceAtLeast(1f)
        val scrollOff = adjPtr?.let { Gtk4DynamicBindings.gtkAdjustmentGetValue(it).toFloat() }
            ?: viewportEngine.scrollOffsetPx
        dispatchHostViewportScroll(scrollOff, extent)
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

    override fun dispose() {
        signalRef?.dispose()
        signalRef = null
        super.dispose()
    }

    companion object {
        private val quarkListHost by lazy { Gtk4DynamicBindings.gQuarkFromString("genericui-lazylist-host") }

        private val adjChangedCb = staticCFunction<COpaquePointer?, COpaquePointer?, Unit> { adj, userData ->
            val impl = userData?.asStableRef<GtkLazyListNativeImpl>()?.get() ?: return@staticCFunction
            if (adj == null) return@staticCFunction
            val scrollPx = Gtk4DynamicBindings.gtkAdjustmentGetValue(adj).toFloat()
            val pagePx = Gtk4DynamicBindings.gtkAdjustmentGetPageSize(adj).toFloat().coerceAtLeast(1f)
            impl.dispatchHostViewportScroll(scrollPx, pagePx)
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
        > { box, orientation, _, minPtr, natPtr, minBasePtr, natBasePtr ->
            val impl = box?.let { Gtk4DynamicBindings.gObjectGetQdata(it, quarkListHost)?.asStableRef<GtkLazyListNativeImpl>()?.get() }
            val totalExt = ceil(impl?.viewportEngine?.totalContentExtentPx ?: 0f).toInt().coerceAtLeast(1)
            val isHorizList = impl?.orientationState == Orientation.HORIZONTAL
            val isMainAxis = (orientation == Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL) == isHorizList
            val size = if (isMainAxis) totalExt else 120
            minPtr?.pointed?.value = size
            natPtr?.pointed?.value = size
            minBasePtr?.pointed?.value = -1
            natBasePtr?.pointed?.value = -1
        }

        private val allocateCb = staticCFunction<COpaquePointer?, Int, Int, Int, Unit> { box, width, height, _ ->
            box ?: return@staticCFunction
            val impl = Gtk4DynamicBindings.gObjectGetQdata(box, quarkListHost)?.asStableRef<GtkLazyListNativeImpl>()?.get()
                ?: return@staticCFunction
            impl.allocateVisibleItems(width, height)
        }
    }
}
