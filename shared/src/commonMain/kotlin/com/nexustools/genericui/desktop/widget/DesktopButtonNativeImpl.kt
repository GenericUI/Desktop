package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.action.ButtonNativeImpl

/**
 * Shared desktop base implementation of [ButtonNativeImpl].
 */
open class DesktopButtonNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    classPrefix: String = "btn"
) : DesktopBaseNativeImpl<W>(nativeWidget, classPrefix), ButtonNativeImpl<W> {

    protected var buttonText: String = ""
    protected var buttonIcon: String? = null

    override fun setText(text: String) {
        buttonText = text
        nativeWidget.attributes["text"] = text
    }

    override fun getText(): String = buttonText

    override fun setIcon(icon: String?) {
        buttonIcon = icon
        nativeWidget.attributes["icon"] = icon
    }

    override fun getIcon(): String? = buttonIcon
}
