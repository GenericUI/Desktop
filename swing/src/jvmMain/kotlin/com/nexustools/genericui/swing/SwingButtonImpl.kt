package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.action.ButtonNativeImpl
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Java Swing `JButton` implementation of [ButtonNativeImpl].
 */
class SwingButtonImpl(
    classPrefix: String = "btn"
) : SwingBaseNativeImpl(JButton(), classPrefix), ButtonNativeImpl<JComponent> {
    override val nativeWidget: JButton = super.nativeWidget as JButton
    private var iconVal: String? = null

    init {
        nativeWidget.addActionListener {
            onClickListener?.invoke()
        }
    }

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
