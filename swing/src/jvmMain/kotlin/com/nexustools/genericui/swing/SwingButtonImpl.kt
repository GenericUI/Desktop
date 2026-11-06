package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.action.ButtonNativeImpl
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Java Swing `JButton` implementation of [ButtonNativeImpl].
 *
 * Non-styled [SwingButtonImpl] instances use native system theme rendering (Dark/Light/Auto)
 * and do not apply custom CSS gradients or `backdrop-filter` blur.
 */
open class SwingButtonImpl(
    classPrefix: String = "btn",
    button: JButton = JButton()
) : SwingBaseNativeImpl(button, classPrefix), ButtonNativeImpl<JComponent> {
    override val nativeWidget: JButton = super.nativeWidget as JButton
    private var iconVal: String? = null

    init {
        nativeWidget.addActionListener {
            onClickListener?.invoke()
        }
        syncSwingAppearance()
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        if (this !is SwingStyledButtonImpl) {
            nativeWidget.putClientProperty("backdropBlurPx", null)
            nativeWidget.putClientProperty("backdrop-filter", null)
            nativeWidget.putClientProperty("-webkit-backdrop-filter", null)
            nativeWidget.putClientProperty("backgroundGradient", null)
        }
    }

    override fun setBackgroundGradient(gradient: Gradient?) {
        if (this is SwingStyledButtonImpl) {
            super<SwingBaseNativeImpl>.setBackgroundGradient(gradient)
        } else {
            super<SwingBaseNativeImpl>.setBackgroundGradient(null)
            nativeWidget.putClientProperty("backgroundGradient", null)
        }
    }

    override fun getBackgroundGradient(): Gradient? =
        if (this is SwingStyledButtonImpl) super<SwingBaseNativeImpl>.getBackgroundGradient() else null

    override fun setText(text: String) {
        nativeWidget.text = text
    }

    override fun getText(): String = nativeWidget.text ?: ""

    override fun setIcon(icon: String?) {
        iconVal = icon
        nativeWidget.putClientProperty("icon", icon)
    }

    override fun getIcon(): String? = iconVal
}
