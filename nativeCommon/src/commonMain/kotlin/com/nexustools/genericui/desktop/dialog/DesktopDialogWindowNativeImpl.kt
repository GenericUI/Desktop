package com.nexustools.genericui.desktop.dialog

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.dialog.DialogPresentationStyle
import com.nexustools.genericui.nativeimpl.dialog.DialogWindowNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl

/**
 * Shared desktop base implementation of [DialogWindowNativeImpl].
 */
open class DesktopDialogWindowNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "modal"), DialogWindowNativeImpl<W> {

    companion object {
        val activeDialogs = mutableListOf<DesktopDialogWindowNativeImpl<*>>()
    }

    protected var titleText: String = ""
    protected var presentationStyleState: DialogPresentationStyle = DialogPresentationStyle.MODAL_WINDOW
    protected var modalState: Boolean = true
    protected var fullscreenState: Boolean = false
    protected var ownerWindowRef: WindowNativeImpl<*>? = null
    protected var onDialogClosedListener: (() -> Unit)? = null

    override fun setVisible(visible: Boolean) {
        super.setVisible(visible)
        if (visible) {
            if (!activeDialogs.contains(this)) {
                activeDialogs.add(this)
            }
        } else {
            activeDialogs.remove(this)
        }
    }

    override fun setTitle(title: String) {
        titleText = title
        nativeWidget.attributes["title"] = title
    }

    override fun getTitle(): String = titleText

    override fun setPresentationStyle(style: DialogPresentationStyle) {
        presentationStyleState = style
        nativeWidget.attributes["presentationStyle"] = style
    }

    override fun getPresentationStyle(): DialogPresentationStyle = presentationStyleState

    override fun setModal(modal: Boolean) {
        modalState = modal
        nativeWidget.attributes["modal"] = modal
    }

    override fun setFullscreen(fullscreen: Boolean) {
        fullscreenState = fullscreen
        nativeWidget.attributes["fullscreen"] = fullscreen
    }

    override fun showDialog(ownerWindow: WindowNativeImpl<*>?) {
        ownerWindowRef = ownerWindow
        setVisible(true)
    }

    override fun closeDialog() {
        setVisible(false)
        activeDialogs.remove(this)
        onDialogClosedListener?.invoke()
    }

    override fun setOnDialogClosed(listener: (() -> Unit)?) {
        onDialogClosedListener = listener
    }
}
