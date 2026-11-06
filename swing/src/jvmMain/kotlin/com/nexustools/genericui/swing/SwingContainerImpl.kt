package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.layout.ContainerNativeImpl
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import java.awt.FlowLayout
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
    panel: JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
) : SwingBaseContainerNativeImpl(panel, classPrefix), ContainerNativeImpl<JComponent> {

    init {
        syncSwingAppearance()
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        if (this !is SwingStyledContainerImpl) {
            nativeWidget.putClientProperty("backdropBlurPx", null)
            nativeWidget.putClientProperty("backdrop-filter", null)
            nativeWidget.putClientProperty("-webkit-backdrop-filter", null)
            nativeWidget.putClientProperty("backgroundGradient", null)
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
