package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.feedback.ProgressBarNativeImpl
import javax.swing.JComponent
import javax.swing.JProgressBar

/**
 * Java Swing `JProgressBar` implementation of [ProgressBarNativeImpl].
 */
class SwingProgressBarImpl : SwingBaseNativeImpl(
    object : JProgressBar(0, 100) {
        override fun contains(x: Int, y: Int): Boolean {
            val impl = getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl ?: return super.contains(x, y)
            return impl.hitTestContains(this, x, y, super.contains(x, y))
        }
    },
    "progress"
), ProgressBarNativeImpl<JComponent> {
    override val nativeWidget: JProgressBar get() = super.nativeWidget as JProgressBar

    private var progressVal: Float = 0.0f
    private var indeterminateFlag: Boolean = false
    private var busyFlag: Boolean = false

    override fun setProgress(progress: Float) {
        progressVal = progress.coerceIn(0.0f, 1.0f)
        nativeWidget.value = (progressVal * 100.0f).toInt()
    }

    override fun getProgress(): Float = progressVal

    override fun setIndeterminate(indeterminate: Boolean) {
        indeterminateFlag = indeterminate
        nativeWidget.isIndeterminate = indeterminateFlag || busyFlag
    }

    override fun isIndeterminate(): Boolean = indeterminateFlag

    override fun setBusy(busy: Boolean) {
        busyFlag = busy
        nativeWidget.isIndeterminate = indeterminateFlag || busyFlag
    }

    override fun isBusy(): Boolean = busyFlag

    override fun setLabel(label: String?) {
        nativeWidget.string = label ?: ""
        nativeWidget.isStringPainted = !label.isNullOrEmpty()
    }
}
