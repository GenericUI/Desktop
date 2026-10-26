package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.form.RangeSliderNativeImpl

/**
 * Shared desktop base implementation of [RangeSliderNativeImpl].
 */
open class DesktopRangeSliderNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseNativeImpl<W>(nativeWidget, "form-range"), RangeSliderNativeImpl<W> {

    protected var minVal: Double = 0.0
    protected var maxVal: Double = 100.0
    protected var stepVal: Double = 1.0
    protected var currentVal: Double = 0.0
    protected var onValueChangedListener: ((Double) -> Unit)? = null

    override fun setRange(min: Double, max: Double, step: Double) {
        minVal = min
        maxVal = max.coerceAtLeast(min)
        stepVal = step.coerceAtLeast(0.0001)
        currentVal = currentVal.coerceIn(minVal, maxVal)
    }

    override fun setValue(value: Double) {
        val clamped = value.coerceIn(minVal, maxVal)
        if (kotlin.math.abs(currentVal - clamped) > 1e-4) {
            currentVal = clamped
            nativeWidget.attributes["value"] = clamped
            onValueChangedListener?.invoke(clamped)
        }
    }

    override fun getValue(): Double = currentVal

    override fun setOnValueChanged(listener: ((Double) -> Unit)?) {
        onValueChangedListener = listener
    }

    fun getMin(): Double = minVal

    fun getMax(): Double = maxVal

    fun getStep(): Double = stepVal

    fun setNormalizedFraction(fraction: Double) {
        val clampedFraction = fraction.coerceIn(0.0, 1.0)
        val raw = minVal + clampedFraction * (maxVal - minVal)
        val stepped = if (stepVal > 0.0) {
            minVal + kotlin.math.round((raw - minVal) / stepVal) * stepVal
        } else {
            raw
        }
        setValue(stepped)
    }
}
