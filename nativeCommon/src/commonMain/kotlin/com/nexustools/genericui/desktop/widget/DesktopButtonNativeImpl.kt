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
        if (buttonText != text) {
            buttonText = text
            nativeWidget.attributes["text"] = text
            nativeWidget.notifyTreeInvalidated()
        }
    }

    override fun getText(): String = buttonText

    override fun setIcon(icon: String?) {
        if (buttonIcon != icon) {
            buttonIcon = icon
            nativeWidget.attributes["icon"] = icon
            nativeWidget.notifyTreeInvalidated()
        }
    }

    override fun getIcon(): String? = buttonIcon
}
