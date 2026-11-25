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
    protected var busyState: Boolean = false
    protected var labelText: String? = null

    override fun setProgress(progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        if (progressValue != clamped) {
            progressValue = clamped
            nativeWidget.attributes["progress"] = clamped
            nativeWidget.notifyTreeInvalidated()
        }
    }

    override fun getProgress(): Float = progressValue

    override fun setIndeterminate(indeterminate: Boolean) {
        val effective = indeterminate || busyState
        if (indeterminateState != indeterminate || nativeWidget.attributes["indeterminate"] != effective) {
            indeterminateState = indeterminate
            nativeWidget.attributes["indeterminate"] = effective
            nativeWidget.notifyTreeInvalidated()
        }
    }

    override fun isIndeterminate(): Boolean = indeterminateState

    override fun setBusy(busy: Boolean) {
        val effective = busy || indeterminateState
        if (busyState != busy || nativeWidget.attributes["indeterminate"] != effective) {
            busyState = busy
            nativeWidget.attributes["busy"] = busy
            nativeWidget.attributes["indeterminate"] = effective
            nativeWidget.notifyTreeInvalidated()
        }
    }

    override fun isBusy(): Boolean = busyState

    override fun setLabel(label: String?) {
        if (labelText != label) {
            labelText = label
            nativeWidget.attributes["label"] = label
            nativeWidget.notifyTreeInvalidated()
        }
    }
}
