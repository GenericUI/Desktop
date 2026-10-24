package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.typography.TextNativeImpl
import javax.swing.JComponent
import javax.swing.JLabel

/**
 * Java Swing `JLabel` implementation of [TextNativeImpl].
 */
class SwingTextImpl(
    tagName: String = "span",
    classPrefix: String = ""
) : SwingBaseNativeImpl(JLabel(), classPrefix), TextNativeImpl<JComponent> {
    override val nativeWidget: JLabel = super.nativeWidget as JLabel
    private var maxLinesVal: Int? = null

    init {
        nativeWidget.putClientProperty("tagName", tagName)
    }

    override fun setText(text: String) {
        nativeWidget.text = text
    }

    override fun getText(): String = nativeWidget.text ?: ""

    override fun setMaxLines(maxLines: Int?) {
        maxLinesVal = maxLines
        nativeWidget.putClientProperty("maxLines", maxLines)
    }

    override fun getMaxLines(): Int? = maxLinesVal
}
