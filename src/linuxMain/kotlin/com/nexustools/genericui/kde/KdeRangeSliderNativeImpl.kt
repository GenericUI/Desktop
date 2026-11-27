@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.widget.DesktopRangeSliderNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.threading.GenericUiThreading
import kotlin.math.roundToInt

/**
 * Retained KDE slider control backed by Qt 6 horizontal **`QSlider`**, mapping continuous
 * floating-point `[minVal..maxVal]` ranges accurately onto discrete Qt slider steps.
 */
class KdeRangeSliderNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("QSlider")
) : DesktopRangeSliderNativeImpl<KdeWidgetHandle>(nativeWidget) {

    private var suppressSignalSync = false
    private val scaleSteps: Int = 1000

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQSlider(KdeDynamicBindings.HORIZONTAL, null)
        nativeWidget.attachToQWidget(ptr, "QSlider", customPainted = false)
        KdeVTableBridge.connectSignal(ptr, "valueChanged(int)") { args ->
            if (!suppressSignalSync) {
                val raw = KdeVTableBridge.readSignalArgInt(args, 1) ?: KdeDynamicBindings.getSliderValue(ptr)
                val span = (maxVal - minVal).coerceAtLeast(1e-9)
                val fraction = (raw.toDouble() / scaleSteps.toDouble()).coerceIn(0.0, 1.0)
                val mapped = (minVal + span * fraction).coerceIn(minVal, maxVal)
                currentVal = mapped
                nativeWidget.attributes["value"] = mapped
                GenericUiThreading.runOnLogic {
                    onValueChangedListener?.invoke(mapped)
                }
            }
        }
        KdePainterSupport.attachStandardInputHooks(ptr, this, KdeVTableBridge.HOOK_RESIZE, dispatchLeftClickOnRelease = false)
        syncSliderState()
    }

    private fun syncSliderState() {
        val ptr = nativeWidget.qWidgetPtr ?: return
        if (nativeWidget.kdeDestroyed) return
        suppressSignalSync = true
        try {
            val span = (maxVal - minVal).coerceAtLeast(1e-9)
            val norm = ((currentVal - minVal) / span).coerceIn(0.0, 1.0)
            val scaledVal = (norm * scaleSteps).roundToInt()
            val scaledStep = if (stepVal > 0.0) ((stepVal / span) * scaleSteps).roundToInt().coerceAtLeast(1) else 1
            KdeDynamicBindings.setSliderState(ptr, 0, scaleSteps, scaledStep, scaledVal)
        } finally {
            suppressSignalSync = false
        }
    }

    override fun setRange(min: Double, max: Double, step: Double) {
        super.setRange(min, max, step)
        syncSliderState()
    }

    override fun setValue(value: Double) {
        super.setValue(value)
        syncSliderState()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val w = if (maxWidth != null) minOf(200f, maxWidth) else 200f
        return Size(w, 28f)
    }

    override fun onNativeBoundsChanged(rect: Rect) { nativeWidget.applyBoundsToNative(rect) }
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

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        KdePainterSupport.syncWidgetFocus(this, isFocusedState())
    }

    override fun dispose() {
        nativeWidget.destroyQWidget()
        super.dispose()
    }
}
