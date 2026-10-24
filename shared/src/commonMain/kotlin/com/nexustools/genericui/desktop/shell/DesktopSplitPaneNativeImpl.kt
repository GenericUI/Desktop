package com.nexustools.genericui.desktop.shell

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.ui.Orientation
import com.nexustools.genericui.nativeimpl.shell.SplitPaneNativeImpl

/**
 * Shared desktop base implementation of [SplitPaneNativeImpl].
 */
open class DesktopSplitPaneNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "split-pane"), SplitPaneNativeImpl<W> {

    protected var orientationState: Orientation = Orientation.HORIZONTAL
    protected var dividerRatioVal: Float = 0.5f

    override fun setOrientation(orientation: Orientation) {
        orientationState = orientation
        nativeWidget.attributes["orientation"] = orientation
    }

    override fun getOrientation(): Orientation = orientationState

    override fun setDividerPosition(ratio: Float) {
        dividerRatioVal = ratio.coerceIn(0.05f, 0.95f)
        nativeWidget.attributes["dividerPosition"] = dividerRatioVal
    }

    override fun getDividerPosition(): Float = dividerRatioVal
}
