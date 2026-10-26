package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.feedback.ProgressBarNativeImpl

/**
 * Shared desktop base implementation of [ProgressBarNativeImpl].
 */
open class DesktopProgressBarNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseNativeImpl<W>(nativeWidget, "progress-bar"), ProgressBarNativeImpl<W> {

    protected var progressValue: Float = 0f
    protected var indeterminateState: Boolean = false
    protected var labelText: String? = null

    override fun setProgress(progress: Float) {
        progressValue = progress.coerceIn(0f, 1f)
        nativeWidget.attributes["progress"] = progressValue
    }

    override fun getProgress(): Float = progressValue

    override fun setIndeterminate(indeterminate: Boolean) {
        indeterminateState = indeterminate
        nativeWidget.attributes["indeterminate"] = indeterminate
    }

    override fun isIndeterminate(): Boolean = indeterminateState

    override fun setLabel(label: String?) {
        labelText = label
        nativeWidget.attributes["label"] = label
    }
}
