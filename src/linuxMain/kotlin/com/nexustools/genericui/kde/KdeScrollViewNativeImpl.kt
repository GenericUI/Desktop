@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopScrollViewNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.COpaquePointer
import kotlin.math.roundToInt

/**
 * Retained KDE scroll viewport backed by Qt 6 **`QScrollArea`**, hosting child `QWidget`s inside
 * its internal viewport content widget and syncing scroll offsets and scrollbar ranges cleanly.
 */
open class KdeScrollViewNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QScrollArea")
) : DesktopScrollViewNativeImpl<KdeWidgetHandle>(nativeWidget) {

    private var viewportContentHandle: KdeWidgetHandle? = null
    private var suppressScrollSignal = false

    init {
        KdeDynamicBindings.ensureQApplication()
        val saPtr = KdeDynamicBindings.createQScrollArea(null)
        val contentPtr = KdeDynamicBindings.createQWidget(null, 0)
        viewportContentHandle = KdeWidgetHandle("QWidget_ScrollViewportContent", contentPtr)
        KdeDynamicBindings.configureScrollArea(saPtr, contentPtr)
        KdeDynamicBindings.setWidgetVisible(contentPtr, true)

        nativeWidget.attachToQWidget(saPtr, "QScrollArea", customPainted = false)
        KdePainterSupport.attachStandardInputHooks(saPtr, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)

        KdeVTableBridge.attachWidgetHooks(saPtr, KdeVTableBridge.HOOK_RESIZE) {
            onResize = { _, w, h ->
                layoutChildrenInBounds(w.toFloat(), h.toFloat())
            }
        }
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(viewportContentHandle)
        val b = nativeWidget.bounds
        layoutChildrenInBounds(b.width.coerceAtLeast(100).toFloat(), b.height.coerceAtLeast(100).toFloat())
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        super.onChildRemovedNative(childImpl)
    }

    override fun onChildrenClearedNative() {
        for (c in childImpls) (c.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        super.onChildrenClearedNative()
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        val vp = viewportContentHandle ?: return
        val contentW = width.coerceAtLeast(1f)
        var maxContentH = height.coerceAtLeast(1f)
        var maxContentW = contentW

        for (child in childImpls) {
            if (child is DesktopBaseNativeImpl<*>) {
                val pref = child.measureIntrinsicSize(contentW, null)
                val cw = pref.width.toFloat().coerceAtLeast(contentW)
                val ch = pref.height.toFloat().coerceAtLeast(1f)
                child.setBounds(Rect(0f, 0f, cw, ch))
                maxContentW = maxOf(maxContentW, cw)
                maxContentH = maxOf(maxContentH, ch)
            }
        }
        vp.applyBoundsToNative(Rect(0f, 0f, maxContentW, maxContentH))
        vp.qWidgetPtr?.let { ptr ->
            KdeDynamicBindings.resizeWidget(ptr, maxContentW.roundToInt(), maxContentH.roundToInt())
        }
        syncScrollBarsFromState()
    }

    private fun syncScrollBarsFromState() {
        val saPtr = nativeWidget.qWidgetPtr ?: return
        if (nativeWidget.kdeDestroyed) return
        suppressScrollSignal = true
        try {
            KdeDynamicBindings.setScrollAreaOffsets(saPtr, scrollX.coerceAtLeast(0), scrollY.coerceAtLeast(0))
        } finally {
            suppressScrollSignal = false
        }
    }

    override fun scrollTo(x: Int, y: Int) {
        super.scrollTo(x, y)
        syncScrollBarsFromState()
    }

    override fun scrollToBottom() {
        val saPtr = nativeWidget.qWidgetPtr
        val contentH = viewportContentHandle?.bounds?.height ?: 0
        val viewH = nativeWidget.bounds.height
        val maxScroll = (contentH - viewH).coerceAtLeast(0)
        scrollTo(scrollX, maxScroll)
        if (saPtr != null) KdeDynamicBindings.setScrollAreaOffsets(saPtr, scrollX, maxScroll)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val w = maxWidth ?: 300f
        val h = maxHeight ?: 240f
        return Size(w, h)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        nativeWidget.applyBoundsToNative(rect)
        layoutChildrenInBounds(rect.width.toFloat(), rect.height.toFloat())
    }

    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeTooltipChanged(tooltip: String?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, nativeWidget.isSelfPointerEventsNone(), tooltip, getOpacity())
    }
    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, cursor, nativeWidget.isSelfPointerEventsNone(), nativeWidget.tooltipText, getOpacity())
    }
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, pointerEventsNone, nativeWidget.tooltipText, getOpacity())
    }
    override fun onNativeTransformChanged(transform: CssTransform?) { nativeWidget.requestRepaint() }

    override fun dispose() {
        viewportContentHandle = null
        nativeWidget.destroyQWidget()
        super.dispose()
    }
}
