package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.ui.Orientation
import com.nexustools.genericui.nativeimpl.shell.SplitPaneNativeImpl
import javax.swing.JComponent
import javax.swing.JSplitPane

/**
 * Java Swing `JSplitPane` implementation of [SplitPaneNativeImpl].
 */
class SwingSplitPaneImpl : SwingBaseContainerNativeImpl(JSplitPane(JSplitPane.HORIZONTAL_SPLIT), "split-pane"), SplitPaneNativeImpl<JComponent> {
    override val nativeWidget: JSplitPane = super.nativeWidget as JSplitPane

    private var orientationVal: Orientation = Orientation.HORIZONTAL
    private var ratioVal: Float = 0.5f

    override fun setOrientation(orientation: Orientation) {
        orientationVal = orientation
        nativeWidget.orientation = if (orientation == Orientation.HORIZONTAL) {
            JSplitPane.HORIZONTAL_SPLIT
        } else {
            JSplitPane.VERTICAL_SPLIT
        }
    }

    override fun getOrientation(): Orientation = orientationVal

    override fun setDividerPosition(ratio: Float) {
        ratioVal = ratio.coerceIn(0.05f, 0.95f)
        nativeWidget.resizeWeight = ratioVal.toDouble()
    }

    override fun getDividerPosition(): Float = ratioVal
}
