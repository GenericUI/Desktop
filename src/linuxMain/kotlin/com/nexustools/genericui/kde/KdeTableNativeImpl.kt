@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.widget.DesktopTableNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssTransform

/**
 * Retained KDE data table widget backed by Qt 6 **`QTableWidget`** with Breeze styling,
 * automatic header sizing, read-only cells, and alternating row stripes.
 */
open class KdeTableNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QTableWidget")
) : DesktopTableNativeImpl<KdeWidgetHandle>(nativeWidget) {

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQTableWidget(null)
        nativeWidget.attachToQWidget(ptr, "QTableWidget", customPainted = false)
        KdePainterSupport.attachStandardInputHooks(
            widgetPtr = ptr,
            impl = this,
            hookMask = KdeVTableBridge.HOOK_RESIZE,
            dispatchLeftClickOnRelease = false
        )
        syncTableData()
    }

    private fun syncTableData() {
        val ptr = nativeWidget.qWidgetPtr ?: return
        if (!KdeVTableBridge.isPeerAlive(ptr)) return
        KdeDynamicBindings.populateQTableWidget(ptr, headersList, rowsList)
        KdeDynamicBindings.tableSetAlternatingRowColors(ptr, stripedState)
    }

    override fun setHeaders(headers: List<String>) {
        super.setHeaders(headers)
        syncTableData()
    }

    override fun setRows(rows: List<List<String>>) {
        super.setRows(rows)
        syncTableData()
    }

    override fun setStriped(striped: Boolean) {
        super.setStriped(striped)
        nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.tableSetAlternatingRowColors(it, striped) }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val cols = headersList.size.coerceAtLeast(rowsList.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1)
        val rows = (rowsList.size + if (headersList.isNotEmpty()) 1 else 0).coerceAtLeast(2)
        val w = (cols * 120).coerceIn(220, maxWidth?.toInt() ?: 720)
        val h = (rows * 30 + 8).coerceIn(90, maxHeight?.toInt() ?: 480)
        return Size(w, h)
    }

    override fun onNativeBoundsChanged(rect: Rect) { nativeWidget.applyBoundsToNative(rect) }
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
        super.dispose()
        nativeWidget.destroyQWidget()
    }
}
