package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.feedback.SpinnerNativeImpl

/**
 * Shared desktop base implementation of [SpinnerNativeImpl].
 */
open class DesktopSpinnerNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseNativeImpl<W>(nativeWidget, "spinner-border"), SpinnerNativeImpl<W> {

    protected var growStyleState: Boolean = false
    protected var labelText: String? = null

    override fun setGrowStyle(grow: Boolean) {
        growStyleState = grow
        nativeWidget.attributes["growStyle"] = grow
    }

    override fun setLabel(label: String?) {
        labelText = label
        nativeWidget.attributes["label"] = label
    }
}
