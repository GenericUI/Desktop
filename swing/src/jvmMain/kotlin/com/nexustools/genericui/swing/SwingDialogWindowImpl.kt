package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.dialog.DialogPresentationStyle
import com.nexustools.genericui.nativeimpl.dialog.DialogWindowNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Java Swing `JDialog` / modal panel implementation of [DialogWindowNativeImpl].
 */
class SwingDialogWindowImpl : SwingBaseContainerNativeImpl(JPanel(), "modal"), DialogWindowNativeImpl<JComponent> {
    private var titleText: String = ""
    private var styleVal: DialogPresentationStyle = DialogPresentationStyle.MODAL_CENTERED
    private var modalFlag: Boolean = true
    private var fullscreenFlag: Boolean = false
    private var onCloseListener: (() -> Unit)? = null

    override fun setTitle(title: String) {
        titleText = title
    }

    override fun getTitle(): String = titleText

    override fun setPresentationStyle(style: DialogPresentationStyle) {
        styleVal = style
    }

    override fun getPresentationStyle(): DialogPresentationStyle = styleVal

    override fun setModal(modal: Boolean) {
        modalFlag = modal
    }

    override fun setFullscreen(fullscreen: Boolean) {
        fullscreenFlag = fullscreen
    }

    override fun showDialog(ownerWindow: WindowNativeImpl<*>?) {
        nativeWidget.isVisible = true
    }

    override fun closeDialog() {
        nativeWidget.isVisible = false
        onCloseListener?.invoke()
    }

    override fun setOnDialogClosed(listener: (() -> Unit)?) {
        onCloseListener = listener
    }
}
