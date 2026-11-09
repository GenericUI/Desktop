package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.nativeimpl.layout.ContainerNativeImpl
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Java Swing `JPanel` implementation of [ContainerNativeImpl].
 *
 * Non-styled [SwingContainerImpl] instances use native system theme rendering (Dark/Light/Auto)
 * and do not apply custom CSS gradients or `backdrop-filter` blur.
 */
open class SwingContainerImpl(
    classPrefix: String = "container",
    panel: JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 8))
) : SwingBaseContainerNativeImpl(panel, classPrefix), ContainerNativeImpl<JComponent> {

    init {
        syncSwingAppearance()
    }

    override fun setFlexDirection(direction: FlexDirection?) {
        super.setFlexDirection(direction)
        syncContainerLayout()
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        if (this !is SwingStyledContainerImpl) {
            nativeWidget.putClientProperty("backdropBlurPx", null)
            nativeWidget.putClientProperty("backdrop-filter", null)
            nativeWidget.putClientProperty("-webkit-backdrop-filter", null)
            nativeWidget.putClientProperty("backgroundGradient", null)
        }
        syncContainerLayout()
    }

    private fun syncContainerLayout() {
        val dir = styleSnapshot.flexDirection
        val isVertical = dir == FlexDirection.COLUMN ||
            dir == FlexDirection.COLUMN_REVERSE ||
            (dir == null && (
                componentClassPrefix.contains("vstack", ignoreCase = true) ||
                    componentClassPrefix.contains("card", ignoreCase = true) ||
                    componentClassPrefix.contains("fragment", ignoreCase = true)
                ))
        if (isVertical) {
            if (nativeWidget.layout !is BoxLayout) {
                nativeWidget.layout = BoxLayout(nativeWidget, BoxLayout.Y_AXIS)
            }
            nativeWidget.alignmentX = Component.LEFT_ALIGNMENT
            for (child in nativeWidget.components) {
                if (child is JComponent) {
                    child.alignmentX = Component.LEFT_ALIGNMENT
                }
            }
        } else if (dir == FlexDirection.ROW || dir == FlexDirection.ROW_REVERSE || componentClassPrefix.contains("hstack", ignoreCase = true)) {
            if (nativeWidget.layout !is FlowLayout) {
                nativeWidget.layout = FlowLayout(FlowLayout.LEFT, 8, 6)
            }
            nativeWidget.alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    override fun setBackgroundGradient(gradient: Gradient?) {
        if (this is SwingStyledContainerImpl) {
            super<SwingBaseContainerNativeImpl>.setBackgroundGradient(gradient)
        } else {
            super<SwingBaseContainerNativeImpl>.setBackgroundGradient(null)
            nativeWidget.putClientProperty("backgroundGradient", null)
        }
    }

    override fun getBackgroundGradient(): Gradient? =
        if (this is SwingStyledContainerImpl) super<SwingBaseContainerNativeImpl>.getBackgroundGradient() else null
}
