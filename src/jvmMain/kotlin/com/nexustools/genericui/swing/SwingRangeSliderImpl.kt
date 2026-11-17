package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.form.RangeSliderNativeImpl
import javax.swing.JComponent
import javax.swing.JSlider
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Java Swing `JSlider` implementation of [RangeSliderNativeImpl].
 *
 * Synchronizes `(min, max, step)` bounds onto [JSlider.minimum]/[JSlider.maximum], attaches a
 * Swing [javax.swing.event.ChangeListener] to forward interactive slider drags to
 * [onValueChangedListener], and supports fractional ranges via discrete tick scaling.
 */
class SwingRangeSliderImpl : SwingBaseNativeImpl(JSlider(0, 100, 0), "form-range"), RangeSliderNativeImpl<JComponent> {
    override val nativeWidget: JSlider get() = super.nativeWidget as JSlider

    private var minVal: Double = 0.0
    private var maxVal: Double = 100.0
    private var stepVal: Double = 1.0
    private var currentVal: Double = 0.0
    private var useDirectIntegerScale: Boolean = true
    private var effectiveTickStep: Double = 1.0
    private var suppressChangeEvents: Boolean = false
    private var onValueChangedListener: ((Double) -> Unit)? = null

    init {
        configureSliderBounds()
        nativeWidget.addChangeListener {
            if (suppressChangeEvents) return@addChangeListener
            val mapped = sliderUnitsToDouble(nativeWidget.value)
            if (abs(mapped - currentVal) > 1e-9) {
                currentVal = mapped
                onValueChangedListener?.invoke(mapped)
            }
        }
    }

    override fun setRange(min: Double, max: Double, step: Double) {
        val safeMin = if (min <= max) min else max
        val safeMax = if (max >= min) max else min
        minVal = safeMin
        maxVal = safeMax
        stepVal = step
        currentVal = currentVal.coerceIn(minVal, maxVal)
        configureSliderBounds()
    }

    override fun setValue(value: Double) {
        val clamped = value.coerceIn(minVal, maxVal)
        val changed = abs(clamped - currentVal) > 1e-9
        currentVal = clamped
        syncSliderPosition(clamped)
        if (changed) {
            onValueChangedListener?.invoke(clamped)
        }
    }

    override fun getValue(): Double = currentVal

    override fun setOnValueChanged(listener: ((Double) -> Unit)?) {
        onValueChangedListener = listener
    }

    private fun configureSliderBounds() {
        val span = (maxVal - minVal).coerceAtLeast(0.0)
        val isIntegerBounds =
            abs(minVal - minVal.roundToInt().toDouble()) < 1e-9 &&
                abs(maxVal - maxVal.roundToInt().toDouble()) < 1e-9
        val isIntegerStep =
            stepVal <= 0.0 || abs(stepVal - stepVal.roundToInt().toDouble()) < 1e-9

        suppressChangeEvents = true
        try {
            if (isIntegerBounds && isIntegerStep && span <= 100_000.0) {
                useDirectIntegerScale = true
                effectiveTickStep = if (stepVal > 0.0) stepVal else 1.0
                val minInt = minVal.roundToInt()
                val maxInt = maxVal.roundToInt().coerceAtLeast(minInt)
                nativeWidget.minimum = minInt
                nativeWidget.maximum = maxInt
                if (effectiveTickStep > 1.0) {
                    nativeWidget.minorTickSpacing = effectiveTickStep.roundToInt().coerceAtLeast(1)
                    nativeWidget.snapToTicks = true
                } else {
                    nativeWidget.minorTickSpacing = 1
                    nativeWidget.snapToTicks = false
                }
            } else {
                useDirectIntegerScale = false
                val tick = if (stepVal > 0.0) stepVal else (if (span > 0.0) span / 1000.0 else 1.0)
                effectiveTickStep = tick.coerceAtLeast(1e-6)
                val stepCount = if (span > 0.0) {
                    (span / effectiveTickStep).roundToInt().coerceIn(1, 100_000)
                } else {
                    0
                }
                nativeWidget.minimum = 0
                nativeWidget.maximum = stepCount
                nativeWidget.minorTickSpacing = 1
                nativeWidget.snapToTicks = false
            }
            nativeWidget.value = doubleToSliderUnits(currentVal)
        } finally {
            suppressChangeEvents = false
        }
    }

    private fun syncSliderPosition(value: Double) {
        suppressChangeEvents = true
        try {
            nativeWidget.value = doubleToSliderUnits(value)
        } finally {
            suppressChangeEvents = false
        }
    }

    private fun doubleToSliderUnits(value: Double): Int {
        val clamped = value.coerceIn(minVal, maxVal)
        return if (useDirectIntegerScale) {
            clamped.roundToInt().coerceIn(nativeWidget.minimum, nativeWidget.maximum)
        } else {
            val tick = effectiveTickStep.coerceAtLeast(1e-6)
            ((clamped - minVal) / tick).roundToInt().coerceIn(nativeWidget.minimum, nativeWidget.maximum)
        }
    }

    private fun sliderUnitsToDouble(units: Int): Double {
        val raw = if (useDirectIntegerScale) {
            units.toDouble()
        } else {
            minVal + (units * effectiveTickStep)
        }
        return raw.coerceIn(minVal, maxVal)
    }
}
