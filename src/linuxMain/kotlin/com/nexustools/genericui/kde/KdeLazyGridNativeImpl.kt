@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyGridNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.threading.GenericUiThreading
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Retained KDE virtualized grid container backed by Qt 6 **`QScrollArea`** + inner canvas
 * `QWidget`, attaching and recycling ONLY visible-row grid item `QWidget`s on scroll.
 */
open class KdeLazyGridNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QScrollArea_LazyGrid")
) : DesktopLazyGridNativeImpl<KdeWidgetHandle>(nativeWidget) {

    var minColumnWidthPx: Int = 0
        private set

    private val canvasHandle: KdeWidgetHandle
    private var suppressScrollCallback = false

    init {
        KdeDynamicBindings.ensureQApplication()
        val scrollPtr = KdeDynamicBindings.createQScrollArea(null)
        val canvasPtr = KdeDynamicBindings.createQWidget(scrollPtr, 0)
        KdeDynamicBindings.scrollAreaSetWidget(scrollPtr, canvasPtr, resizable = false)
        KdeDynamicBindings.scrollAreaSetScrollBarPolicies(
            scrollPtr,
            KdeDynamicBindings.SCROLLBAR_ALWAYS_OFF,
            KdeDynamicBindings.SCROLLBAR_AS_NEEDED
        )
        KdeDynamicBindings.setWidgetVisible(canvasPtr, true)

        nativeWidget.attachToQWidget(scrollPtr, "QScrollArea_LazyGrid", customPainted = false)
        canvasHandle = KdeWidgetHandle("QWidget_LazyGridCanvas", canvasPtr).apply { desiredParentPtr = scrollPtr }

        KdeDynamicBindings.scrollAreaVBar(scrollPtr)?.let { vBar ->
            KdeVTableBridge.connectSignal(vBar, "valueChanged(int)") { args ->
                if (!suppressScrollCallback) {
                    val v = KdeVTableBridge.readSignalArgInt(args, 1) ?: return@connectSignal
                    GenericUiThreading.runOnLogic {
                        viewportEngine.scrollOffsetPx = v.toFloat().coerceAtLeast(0f)
                        rematerializeVisibleWindow()
                        layoutMountedCells()
                    }
                }
            }
        }
        KdeVTableBridge.attachWidgetHooks(scrollPtr, KdeVTableBridge.HOOK_RESIZE) {
            onResize = { _, w, h ->
                if (w > 0 && minColumnWidthPx > 0) {
                    viewportEngine.columns = resolveEffectiveColumns(w)
                }
                viewportEngine.viewportExtentPx = h.coerceAtLeast(1).toFloat()
                rematerializeVisibleWindow()
                layoutMountedCells()
            }
        }
        KdePainterSupport.attachStandardInputHooks(scrollPtr, this, KdeVTableBridge.HOOK_NONE, dispatchLeftClickOnRelease = false)
    }

    override fun setMinColumnWidthPx(minWidthPx: Int) {
        minColumnWidthPx = minWidthPx.coerceAtLeast(0)
        super.setMinColumnWidthPx(minWidthPx)
        nativeWidget.attributes["minColumnWidthPx"] = minWidthPx
        val w = nativeWidget.bounds.width
        if (w > 0 && minColumnWidthPx > 0) {
            viewportEngine.columns = resolveEffectiveColumns(w)
        }
        rematerializeVisibleWindow()
        layoutMountedCells()
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
        layoutMountedCells()
    }

    override fun rematerializeVisibleWindow() {
        super.rematerializeVisibleWindow()
        layoutMountedCells()
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

    private fun layoutMountedCells() {
        val canvasPtr = canvasHandle.qWidgetPtr ?: return
        val boxW = nativeWidget.bounds.width.coerceAtLeast(100)
        val viewH = nativeWidget.bounds.height.coerceAtLeast(100)
        val cols = resolveEffectiveColumns(boxW).coerceAtLeast(1)
        val colW = (boxW / cols).coerceAtLeast(1)
        val rowH = viewportEngine.estimatedItemExtentPx.roundToInt().coerceAtLeast(1)
        val totalH = max(getTotalRowCount() * rowH, viewH)

        KdeDynamicBindings.resizeWidget(canvasPtr, boxW, totalH)

        for ((index, impl) in materializedItems) {
            val base = impl as? DesktopBaseNativeImpl<*> ?: continue
            val h = base.nativeWidget as? KdeWidgetHandle ?: continue
            h.syncChildParentAndVisibility(canvasHandle)
            val col = index % cols
            val row = index / cols
            val x = col * colW
            val y = row * rowH
            val cellW = if (col == cols - 1) (boxW - x).coerceAtLeast(1) else colW
            base.setBounds(Rect(x, y, cellW, rowH))
        }
    }

    override fun scrollToIndex(index: Int) {
        super.scrollToIndex(index)
        val scrollPtr = nativeWidget.qWidgetPtr ?: return
        suppressScrollCallback = true
        try {
            KdeDynamicBindings.scrollAreaVBar(scrollPtr)?.let { bar ->
                KdeDynamicBindings.sliderSetValue(bar, viewportEngine.scrollOffsetPx.roundToInt())
            }
        } finally {
            suppressScrollCallback = false
        }
        layoutMountedCells()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val rowH = viewportEngine.estimatedItemExtentPx.roundToInt().coerceAtLeast(1)
        val totalH = (getTotalRowCount() * rowH).coerceIn(80, maxHeight?.toInt() ?: 480)
        return Size(maxWidth?.toInt() ?: 360, totalH)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        nativeWidget.applyBoundsToNative(rect)
        if (rect.width > 0 && minColumnWidthPx > 0) {
            viewportEngine.columns = resolveEffectiveColumns(rect.width)
        }
        viewportEngine.viewportExtentPx = rect.height.coerceAtLeast(1).toFloat()
        rematerializeVisibleWindow()
        layoutMountedCells()
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
        clearMaterializedItems()
        super.dispose()
        nativeWidget.destroyQWidget()
    }
}
