@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.widget.DesktopProgressBarNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import kotlin.math.roundToInt

/**
 * Retained KDE progress indicator backed by a real Qt 6 **`QProgressBar`** (`Breeze` style)
 * supporting determinate progress `[0.0 .. 1.0]`, native Qt indeterminate busy marquee
 * (`setRange(0, 0)`), and optional label display.
 */
open class KdeProgressBarNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QProgressBar")
) : DesktopProgressBarNativeImpl<KdeWidgetHandle>(nativeWidget) {

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQProgressBar(null)
        nativeWidget.attachToQWidget(ptr, "QProgressBar", customPainted = false)
        KdePainterSupport.attachStandardInputHooks(
            widgetPtr = ptr,
            impl = this,
            hookMask = KdeVTableBridge.HOOK_RESIZE,
            dispatchLeftClickOnRelease = false
        )
        syncProgressToWidget()
    }

    private fun syncProgressToWidget() {
        val ptr = nativeWidget.qWidgetPtr ?: return
        if (!KdeVTableBridge.isPeerAlive(ptr)) return
        val indet = indeterminateState || busyState
        val showText = !labelText.isNullOrEmpty()
        if (indet) {
            KdeDynamicBindings.setProgressBarState(ptr, min = 0, max = 0, value = 0, showText = showText)
        } else {
            val pctValue = (progressValue * 1000f).roundToInt().coerceIn(0, 1000)
            KdeDynamicBindings.setProgressBarState(ptr, min = 0, max = 1000, value = pctValue, showText = showText)
        }
    }

    override fun setProgress(progress: Float) {
        super.setProgress(progress)
        syncProgressToWidget()
    }

    override fun setIndeterminate(indeterminate: Boolean) {
        super.setIndeterminate(indeterminate)
        syncProgressToWidget()
    }

    override fun setBusy(busy: Boolean) {
        super.setBusy(busy)
        syncProgressToWidget()
    }

    override fun setLabel(label: String?) {
        super.setLabel(label)
        syncProgressToWidget()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val w = (maxWidth ?: 200f).coerceAtLeast(80f)
        return Size(w, 20f)
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
