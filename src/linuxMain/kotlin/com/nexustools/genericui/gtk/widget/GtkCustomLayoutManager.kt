@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.widget

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
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
import kotlin.math.roundToInt

/**
 * Retained GTK4 `GtkCustomLayout` adapter bridging `gtk_custom_layout_new` `measure` &
 * `allocate` C callbacks directly to [DesktopContainerLayoutController] and `GskTransform`.
 */
object GtkCustomLayoutManager {

    private class LayoutHostBinding(
        val host: DesktopBaseContainerNativeImpl<GtkWidgetHandle>,
        val hostWidgetPtr: COpaquePointer,
        val controller: DesktopContainerLayoutController,
        val fixedBackgroundPtr: COpaquePointer? = null,
        var allocating: Boolean = false
    )

    private val quarkLayoutHost by lazy {
        Gtk4DynamicBindings.gQuarkFromString("genericui-custom-layout-host")
    }

    private val destroyBindingCb = staticCFunction<COpaquePointer?, Unit> { ptr ->
        ptr?.asStableRef<LayoutHostBinding>()?.dispose()
    }

    private val requestModeCb = staticCFunction<COpaquePointer?, Int> { _ ->
        Gtk4DynamicBindings.GTK_SIZE_REQUEST_HEIGHT_FOR_WIDTH
    }

    private val measureCb = staticCFunction<
        COpaquePointer?,
        Int,
        Int,
        kotlinx.cinterop.CPointer<IntVar>?,
        kotlinx.cinterop.CPointer<IntVar>?,
        kotlinx.cinterop.CPointer<IntVar>?,
        kotlinx.cinterop.CPointer<IntVar>?,
        Unit
    > { widget, orientation, forSize, minimumPtr, naturalPtr, minBaselinePtr, natBaselinePtr ->
        val binding = resolveBinding(widget)
        if (binding == null) {
            minimumPtr?.pointed?.value = 0
            naturalPtr?.pointed?.value = 0
            minBaselinePtr?.pointed?.value = -1
            natBaselinePtr?.pointed?.value = -1
            return@staticCFunction
        }

        val hintW = if (orientation == Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL && forSize > 0) forSize.toFloat() else null
        val hintH = if (orientation == Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL && forSize > 0) forSize.toFloat() else null
        val preferred = binding.controller.measurePreferredSize(hintW, hintH)
        val natAxis = if (orientation == Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL) {
            preferred.width.coerceAtLeast(0)
        } else {
            preferred.height.coerceAtLeast(0)
        }

        // Also ensure materialized GTK leaf descendants are queried via gtk_widget_measure.
        for (child in binding.host.getLogicalChildren()) {
            val childHandle = child.nativeWidget as? GtkWidgetHandle ?: continue
            val childPtr = childHandle.realGtkWidgetPtr ?: continue
            if (child !is DesktopBaseContainerNativeImpl<*>) {
                Gtk4DynamicBindings.gtkWidgetMeasure(childPtr, orientation, forSize)
            }
        }

        minimumPtr?.pointed?.value = natAxis
        naturalPtr?.pointed?.value = natAxis
        minBaselinePtr?.pointed?.value = -1
        natBaselinePtr?.pointed?.value = -1
    }

    private val allocateCb = staticCFunction<COpaquePointer?, Int, Int, Int, Unit> { widget, width, height, _ ->
        val binding = resolveBinding(widget) ?: return@staticCFunction
        if (binding.allocating) return@staticCFunction
        binding.allocating = true
        try {
            binding.fixedBackgroundPtr?.let { bgPtr ->
                if (Gtk4DynamicBindings.gtkWidgetGetParent(bgPtr) == binding.hostWidgetPtr) {
                    Gtk4DynamicBindings.gtkWidgetSizeAllocate(bgPtr, 0, 0, width, height, -1)
                }
            }

            binding.host.nativeWidget.onBeforeLayoutAllocate?.invoke(width, height)
            binding.controller.layoutInBounds(width.toFloat(), height.toFloat())
            for (child in binding.host.getLogicalChildren()) {
                allocateChildImpl(binding.hostWidgetPtr, child)
            }
        } finally {
            binding.allocating = false
        }
    }

    fun attachToContainer(
        host: DesktopBaseContainerNativeImpl<GtkWidgetHandle>,
        hostWidgetPtr: COpaquePointer,
        controller: DesktopContainerLayoutController,
        fixedBackgroundPtr: COpaquePointer? = null
    ) {
        val binding = LayoutHostBinding(host, hostWidgetPtr, controller, fixedBackgroundPtr)
        val stable = StableRef.create(binding)
        Gtk4DynamicBindings.gObjectSetQdataFull(
            obj = hostWidgetPtr,
            quark = quarkLayoutHost,
            data = stable.asCPointer(),
            destroy = destroyBindingCb.reinterpret()
        )
        val layoutMgr = Gtk4DynamicBindings.gtkCustomLayoutNew(
            requestMode = requestModeCb.reinterpret(),
            measure = measureCb.reinterpret(),
            allocate = allocateCb.reinterpret()
        )
        if (layoutMgr != null) {
            Gtk4DynamicBindings.gtkWidgetSetLayoutManager(hostWidgetPtr, layoutMgr)
            host.nativeWidget.gtkLayoutManagerPtr = layoutMgr
        }
        controller.onZOrderUpdated = { ordered ->
            syncChildrenZOrder(hostWidgetPtr, ordered, fixedBackgroundPtr)
        }
    }

    fun requestRelayout(widgetPtr: COpaquePointer?) {
        widgetPtr ?: return
        Gtk4DynamicBindings.gtkWidgetGetLayoutManager(widgetPtr)?.let { mgr ->
            Gtk4DynamicBindings.gtkLayoutManagerLayoutChanged(mgr)
        }
        Gtk4DynamicBindings.gtkWidgetQueueAllocate(widgetPtr)
        Gtk4DynamicBindings.gtkWidgetQueueResize(widgetPtr)
    }

    fun attachChildWidget(
        parentPtr: COpaquePointer,
        childPtr: COpaquePointer,
        insertAfterPtr: COpaquePointer? = null
    ) {
        val existingParent = Gtk4DynamicBindings.gtkWidgetGetParent(childPtr)
        if (existingParent != null && existingParent != parentPtr) {
            Gtk4DynamicBindings.gtkWidgetUnparent(childPtr)
        }
        if (Gtk4DynamicBindings.gtkWidgetGetParent(childPtr) != parentPtr) {
            Gtk4DynamicBindings.gtkWidgetInsertAfter(childPtr, parentPtr, insertAfterPtr)
        }
    }

    fun detachChildWidget(childPtr: COpaquePointer?) {
        childPtr ?: return
        if (Gtk4DynamicBindings.gtkWidgetGetParent(childPtr) != null) {
            Gtk4DynamicBindings.gtkWidgetUnparent(childPtr)
        }
    }

    fun syncChildrenZOrder(
        parentPtr: COpaquePointer,
        orderedChildren: List<DesktopBaseNativeImpl<*>>,
        fixedFirstChildPtr: COpaquePointer? = null
    ) {
        val desiredPtrs = ArrayList<COpaquePointer>(orderedChildren.size + 1)
        if (fixedFirstChildPtr != null && Gtk4DynamicBindings.gtkWidgetGetParent(fixedFirstChildPtr) == parentPtr) {
            desiredPtrs.add(fixedFirstChildPtr)
        }
        for (child in orderedChildren) {
            val handle = child.nativeWidget as? GtkWidgetHandle ?: continue
            val ptr = handle.realGtkWidgetPtr ?: continue
            if (Gtk4DynamicBindings.gtkWidgetGetParent(ptr) == parentPtr) {
                desiredPtrs.add(ptr)
            }
        }
        if (desiredPtrs.isEmpty()) return

        if (matchesCurrentSiblingOrder(parentPtr, desiredPtrs)) return

        var prev: COpaquePointer? = null
        for (ptr in desiredPtrs) {
            Gtk4DynamicBindings.gtkWidgetInsertAfter(ptr, parentPtr, prev)
            prev = ptr
        }
    }

    fun allocateChildImpl(parentPtr: COpaquePointer, child: NativeImpl<*>) {
        val childBase = child as? DesktopBaseNativeImpl<*> ?: return
        val childHandle = childBase.nativeWidget as? GtkWidgetHandle ?: return
        if (childHandle.attributes["mountedInTitleBar"] == true) return
        val childPtr = childHandle.ensureLiveWidgetPtr() ?: childHandle.realGtkWidgetPtr ?: return
        if (Gtk4DynamicBindings.gtkWidgetGetParent(childPtr) != parentPtr) {
            attachChildWidget(parentPtr, childPtr, null)
        }
        val bounds = childBase.getBounds()
        val w = bounds.width.coerceAtLeast(0)
        val h = bounds.height.coerceAtLeast(0)
        val transform = childHandle.cssTransform
        if (transform == null || (transform.scaleX == 1f && transform.scaleY == 1f && transform.rotateDeg == 0f)) {
            Gtk4DynamicBindings.gtkWidgetSizeAllocate(childPtr, bounds.x, bounds.y, w, h, -1)
        } else {
            val gskTransform = buildGskTransform(bounds.x, bounds.y, w, h, transform)
            Gtk4DynamicBindings.gtkWidgetAllocate(childPtr, w, h, -1, gskTransform)
        }
    }

    fun buildGskTransform(x: Int, y: Int, width: Int, height: Int, transform: CssTransform): COpaquePointer? {
        val cx = width * 0.5f
        val cy = height * 0.5f
        var t: COpaquePointer? = null
        t = Gtk4DynamicBindings.gskTransformTranslate(t, x + cx, y + cy)
        if (transform.rotateDeg != 0f) {
            t = Gtk4DynamicBindings.gskTransformRotate(t, transform.rotateDeg)
        }
        if (transform.scaleX != 1f || transform.scaleY != 1f) {
            t = Gtk4DynamicBindings.gskTransformScale(t, transform.scaleX, transform.scaleY)
        }
        return Gtk4DynamicBindings.gskTransformTranslate(t, -cx, -cy)
    }

    fun measureGtkWidgetIntrinsic(widgetPtr: COpaquePointer, maxWidth: Float?, maxHeight: Float?): Pair<Int, Int> {
        val forW = maxWidth?.roundToInt()?.coerceAtLeast(1) ?: -1
        val (_, natW) = Gtk4DynamicBindings.gtkWidgetMeasure(
            widgetPtr,
            Gtk4DynamicBindings.GTK_ORIENTATION_HORIZONTAL,
            -1
        )
        val effectiveW = if (maxWidth != null) natW.coerceAtMost(forW) else natW
        val (_, natH) = Gtk4DynamicBindings.gtkWidgetMeasure(
            widgetPtr,
            Gtk4DynamicBindings.GTK_ORIENTATION_VERTICAL,
            effectiveW.takeIf { it > 0 } ?: -1
        )
        return natW to natH
    }

    private fun matchesCurrentSiblingOrder(parentPtr: COpaquePointer, desiredPtrs: List<COpaquePointer>): Boolean {
        var cur = Gtk4DynamicBindings.gtkWidgetGetFirstChild(parentPtr)
        var idx = 0
        while (cur != null && idx < desiredPtrs.size) {
            if (cur != desiredPtrs[idx]) return false
            idx++
            cur = Gtk4DynamicBindings.gtkWidgetGetNextSibling(cur)
        }
        return idx == desiredPtrs.size && cur == null
    }

    private fun resolveBinding(widget: COpaquePointer?): LayoutHostBinding? {
        widget ?: return null
        val qdata = Gtk4DynamicBindings.gObjectGetQdata(widget, quarkLayoutHost) ?: return null
        return qdata.asStableRef<LayoutHostBinding>().get()
    }
}
