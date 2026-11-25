@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyListNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.ui.Orientation
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.threading.GenericUiThreading
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Retained KDE virtualized list container backed by Qt 6 **`QScrollArea`** + inner canvas
 * `QWidget`, mounting and positioning ONLY visible-window item widgets on scroll.
 */
open class KdeLazyListNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QScrollArea_LazyList")
) : DesktopLazyListNativeImpl<KdeWidgetHandle>(nativeWidget) {

    private val canvasHandle: KdeWidgetHandle
    private var suppressScrollCallback = false

    init {
        KdeDynamicBindings.ensureQApplication()
        val scrollPtr = KdeDynamicBindings.createQScrollArea(null)
        val canvasPtr = KdeDynamicBindings.createQWidget(scrollPtr, 0)
        KdeDynamicBindings.scrollAreaSetWidget(scrollPtr, canvasPtr, resizable = false)
        KdeDynamicBindings.setWidgetVisible(canvasPtr, true)

        nativeWidget.attachToQWidget(scrollPtr, "QScrollArea_LazyList", customPainted = false)
        canvasHandle = KdeWidgetHandle("QWidget_LazyListCanvas", canvasPtr).apply { desiredParentPtr = scrollPtr }

        syncScrollPolicies()
        val vBar = KdeDynamicBindings.scrollAreaVBar(scrollPtr)
        val hBar = KdeDynamicBindings.scrollAreaHBar(scrollPtr)
        vBar?.let { bar ->
            KdeVTableBridge.connectSignal(bar, "valueChanged(int)") { args ->
                if (!suppressScrollCallback && orientationState == Orientation.VERTICAL) {
                    val v = KdeVTableBridge.readSignalArgInt(args, 1) ?: return@connectSignal
                    val extent = nativeWidget.bounds.height.coerceAtLeast(1).toFloat()
                    GenericUiThreading.runOnLogic { dispatchHostViewportScroll(v.toFloat(), extent) }
                }
            }
        }
        hBar?.let { bar ->
            KdeVTableBridge.connectSignal(bar, "valueChanged(int)") { args ->
                if (!suppressScrollCallback && orientationState == Orientation.HORIZONTAL) {
                    val v = KdeVTableBridge.readSignalArgInt(args, 1) ?: return@connectSignal
                    val extent = nativeWidget.bounds.width.coerceAtLeast(1).toFloat()
                    GenericUiThreading.runOnLogic { dispatchHostViewportScroll(v.toFloat(), extent) }
                }
            }
        }
        KdeVTableBridge.attachWidgetHooks(scrollPtr, KdeVTableBridge.HOOK_RESIZE) {
            onResize = { _, w, h ->
                val ext = if (orientationState == Orientation.HORIZONTAL) w else h
                viewportEngine.viewportExtentPx = ext.coerceAtLeast(1).toFloat()
                rematerializeVisibleWindow()
                layoutMountedItems()
            }
        }
        KdePainterSupport.attachStandardInputHooks(scrollPtr, this, KdeVTableBridge.HOOK_NONE, dispatchLeftClickOnRelease = false)
    }

    private fun syncScrollPolicies() {
        val ptr = nativeWidget.qWidgetPtr ?: return
        val horiz = orientationState == Orientation.HORIZONTAL
        KdeDynamicBindings.scrollAreaSetScrollBarPolicies(
            ptr,
            if (horiz) KdeDynamicBindings.SCROLLBAR_AS_NEEDED else KdeDynamicBindings.SCROLLBAR_ALWAYS_OFF,
            if (horiz) KdeDynamicBindings.SCROLLBAR_ALWAYS_OFF else KdeDynamicBindings.SCROLLBAR_AS_NEEDED
        )
    }

    override fun setOrientation(orientation: Orientation) {
        super.setOrientation(orientation)
        syncScrollPolicies()
        layoutMountedItems()
    }

    override fun rematerializeVisibleWindow() {
        super.rematerializeVisibleWindow()
        layoutMountedItems()
    }

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        super.addChildNative(childImpl, index)
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(canvasHandle)
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        super.removeChildNative(childImpl)
    }

    override fun clearChildrenNative() {
        for (c in childImpls) (c.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        super.clearChildrenNative()
    }

    private fun layoutMountedItems() {
        val canvasPtr = canvasHandle.qWidgetPtr ?: return
        val viewW = nativeWidget.bounds.width.coerceAtLeast(100)
        val viewH = nativeWidget.bounds.height.coerceAtLeast(100)
        val itemExtent = viewportEngine.estimatedItemExtentPx.roundToInt().coerceAtLeast(1)
        val totalExtent = max(viewportEngine.totalItemCount * itemExtent, if (orientationState == Orientation.HORIZONTAL) viewW else viewH)

        if (orientationState == Orientation.HORIZONTAL) {
            KdeDynamicBindings.resizeWidget(canvasPtr, totalExtent, viewH)
        } else {
            KdeDynamicBindings.resizeWidget(canvasPtr, viewW, totalExtent)
        }

        for ((index, impl) in materializedItems) {
            val base = impl as? DesktopBaseNativeImpl<*> ?: continue
            val h = base.nativeWidget as? KdeWidgetHandle ?: continue
            h.syncChildParentAndVisibility(canvasHandle)
            val rect = if (orientationState == Orientation.HORIZONTAL) {
                Rect(index * itemExtent, 0, itemExtent, viewH)
            } else {
                Rect(0, index * itemExtent, viewW, itemExtent)
            }
            base.setBounds(rect)
        }
    }

    override fun scrollToIndex(index: Int) {
        super.scrollToIndex(index)
        val scrollPtr = nativeWidget.qWidgetPtr ?: return
        val bar = if (orientationState == Orientation.HORIZONTAL) {
            KdeDynamicBindings.scrollAreaHBar(scrollPtr)
        } else {
            KdeDynamicBindings.scrollAreaVBar(scrollPtr)
        }
        suppressScrollCallback = true
        try {
            bar?.let { KdeDynamicBindings.sliderSetValue(it, viewportEngine.scrollOffsetPx.roundToInt()) }
        } finally {
            suppressScrollCallback = false
        }
        layoutMountedItems()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val estTotalH = (viewportEngine.totalItemCount * viewportEngine.estimatedItemExtentPx).roundToInt().coerceIn(80, maxHeight?.toInt() ?: 480)
        return Size(maxWidth?.toInt() ?: 320, estTotalH)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        nativeWidget.applyBoundsToNative(rect)
        val ext = if (orientationState == Orientation.HORIZONTAL) rect.width else rect.height
        viewportEngine.viewportExtentPx = ext.coerceAtLeast(1).toFloat()
        rematerializeVisibleWindow()
        layoutMountedItems()
    }

    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeOpacityChanged(opacity: Float) {}
    override fun onNativeTooltipChanged(tooltip: String?) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativeCursorChanged(cursor: CssCursorValue?) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativeZIndexChanged(zIndex: Int) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.raiseWidget(it) } }
    override fun onNativeTransformChanged(transform: CssTransform?) {}
    override fun onNativeStyleSynced() { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }

    override fun dispose() {
        clearChildrenNative()
        super.dispose()
        nativeWidget.destroyQWidget()
    }
}
