package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.nativeimpl.action.ButtonNativeImpl

/**
 * Shared desktop base implementation of [ButtonNativeImpl].
 */
open class DesktopButtonNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W,
    classPrefix: String = "btn"
) : DesktopBaseNativeImpl<W>(nativeWidget, classPrefix), ButtonNativeImpl<W> {

    var buttonType: BootstrapType? = BootstrapType.NONE
        protected set
    var isOutline: Boolean = false
        protected set

    private var buttonInitialized: Boolean = false
    protected var buttonText: String = ""
    protected var buttonIcon: String? = null

    init {
        buttonType = styleSnapshot.type
        isOutline = styleSnapshot.outline
        buttonInitialized = true
    }

    fun isNativeDefaultButtonMode(): Boolean =
        (buttonType == null || buttonType == BootstrapType.NONE) && !isOutline

    protected open fun onButtonModeChanged() {}

    override fun syncStyleToNativeWidget() {
        val prevType = buttonType
        val prevOutline = isOutline
        super.syncStyleToNativeWidget()
        val nextType = styleSnapshot.type
        val nextOutline = styleSnapshot.outline
        buttonType = nextType
        isOutline = nextOutline
        if (buttonInitialized && (prevType != nextType || prevOutline != nextOutline)) {
            onButtonModeChanged()
        }
    }

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
