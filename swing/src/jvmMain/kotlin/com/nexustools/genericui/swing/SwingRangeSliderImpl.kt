package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.form.RangeSliderNativeImpl
import javax.swing.JComponent
import javax.swing.JSlider

/**
 * Java Swing `JSlider` implementation of [RangeSliderNativeImpl].
 */
class SwingRangeSliderImpl : SwingBaseNativeImpl(JSlider(0, 1000, 0), "form-range"), RangeSliderNativeImpl<JComponent> {
    override val nativeWidget: JSlider = super.nativeWidget as JSlider

    private var minVal: Double = 0.0
    private var maxVal: Double = 100.0
    private var stepVal: Double = 1.0
    private var currentVal: Double = 0.0
    private var onValueChangedListener: ((Double) -> Unit)? = null

    override fun setRange(min: Double, max: Double, step: Double) {
        minVal = min
        maxVal = max
        stepVal = step
        currentVal = currentVal.coerceIn(minVal, maxVal)
    }

    override fun setValue(value: Double) {
        currentVal = value.coerceIn(minVal, maxVal)
        val span = (maxVal - minVal).takeIf { it > 0.0 } ?: 1.0
        nativeWidget.value = (((currentVal - minVal) / span) * 1000.0).toInt().coerceIn(0, 1000)
        onValueChangedListener?.invoke(currentVal)
    }

    override fun getValue(): Double = currentVal

    override fun setOnValueChanged(listener: ((Double) -> Unit)?) {
        onValueChangedListener = listener
    }
}
