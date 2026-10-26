package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.typography.TextNativeImpl

/**
 * Shared desktop base implementation of [TextNativeImpl].
 */
open class DesktopTextNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    val tagName: String = "span",
    classPrefix: String = ""
) : DesktopBaseNativeImpl<W>(nativeWidget, classPrefix), TextNativeImpl<W> {

    protected var textContent: String = ""
    protected var maxLinesLimit: Int? = null

    override fun setText(text: String) {
        textContent = text
        nativeWidget.attributes["text"] = text
    }

    override fun getText(): String = textContent

    override fun setMaxLines(maxLines: Int?) {
        maxLinesLimit = maxLines
        nativeWidget.attributes["maxLines"] = maxLines
    }

    override fun getMaxLines(): Int? = maxLinesLimit
}
