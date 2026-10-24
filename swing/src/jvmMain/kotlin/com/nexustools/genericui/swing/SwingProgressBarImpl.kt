package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.feedback.ProgressBarNativeImpl
import javax.swing.JComponent
import javax.swing.JProgressBar

/**
 * Java Swing `JProgressBar` implementation of [ProgressBarNativeImpl].
 */
class SwingProgressBarImpl : SwingBaseNativeImpl(JProgressBar(0, 100), "progress"), ProgressBarNativeImpl<JComponent> {
    override val nativeWidget: JProgressBar = super.nativeWidget as JProgressBar

    private var progressVal: Float = 0.0f

    override fun setProgress(progress: Float) {
        progressVal = progress.coerceIn(0.0f, 1.0f)
        nativeWidget.value = (progressVal * 100.0f).toInt()
    }

    override fun getProgress(): Float = progressVal

    override fun setIndeterminate(indeterminate: Boolean) {
        nativeWidget.isIndeterminate = indeterminate
    }

    override fun isIndeterminate(): Boolean = nativeWidget.isIndeterminate

    override fun setLabel(label: String?) {
        nativeWidget.string = label ?: ""
        nativeWidget.isStringPainted = !label.isNullOrEmpty()
    }
}
