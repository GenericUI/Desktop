package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.feedback.SpinnerNativeImpl
import javax.swing.JComponent
import javax.swing.JProgressBar

/**
 * Java Swing indeterminate `JProgressBar` implementation of [SpinnerNativeImpl].
 */
class SwingSpinnerImpl : SwingBaseNativeImpl(JProgressBar().apply { isIndeterminate = true }, "spinner-border"), SpinnerNativeImpl<JComponent> {
    override val nativeWidget: JProgressBar = super.nativeWidget as JProgressBar

    override fun setGrowStyle(grow: Boolean) {
        nativeWidget.putClientProperty("growStyle", grow)
    }

    override fun setLabel(label: String?) {
        nativeWidget.string = label ?: ""
        nativeWidget.isStringPainted = !label.isNullOrEmpty()
    }
}
