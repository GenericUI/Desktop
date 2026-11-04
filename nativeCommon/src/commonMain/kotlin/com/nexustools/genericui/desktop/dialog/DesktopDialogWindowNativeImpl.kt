package com.nexustools.genericui.desktop.dialog

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.enums.dialog.DialogPresentationStyle
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.dialog.DialogWindowNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl

/**
 * Shared desktop base implementation of [DialogWindowNativeImpl].
 */
open class DesktopDialogWindowNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "dialogwindow"), DialogWindowNativeImpl<W> {

    companion object {
        val activeDialogs = mutableListOf<DesktopDialogWindowNativeImpl<*>>()

        var onShowDialogHook: ((DesktopDialogWindowNativeImpl<*>, DesktopWindowNativeImpl<*>?) -> Unit)? = null
        var onCloseDialogHook: ((DesktopDialogWindowNativeImpl<*>) -> Unit)? = null
        var onTitleChangedHook: ((DesktopDialogWindowNativeImpl<*>, String) -> Unit)? = null
    }

    protected var titleText: String = ""
    protected var presentationStyleState: DialogPresentationStyle = DialogPresentationStyle.MODAL_WINDOW
    protected var modalState: Boolean = true
    protected var fullscreenState: Boolean = false
    protected var ownerWindowRef: WindowNativeImpl<*>? = null
    protected var onDialogClosedListener: (() -> Unit)? = null
    private var hasExplicitBounds: Boolean = false

    var boundsWidth: Int = 560
        protected set

    var boundsHeight: Int = 400
        protected set

    var zOrderLayer: Int = 0
        protected set

    val ownerWindow: WindowNativeImpl<*>?
        get() = ownerWindowRef

    val isModal: Boolean
        get() = modalState

    val isFullscreen: Boolean
        get() = fullscreenState

    init {
        nativeWidget.attributes["isSeparateNativeWindow"] = true
        nativeWidget.attributes["inWindowOverlay"] = false
        nativeWidget.attributes["minimizable"] = false
        nativeWidget.attributes["maximizable"] = false
        nativeWidget.attributes["closable"] = true
        nativeWidget.attributes["modal"] = modalState
        nativeWidget.attributes["presentationStyle"] = presentationStyleState
    }

    override fun setVisible(visible: Boolean) {
        super.setVisible(visible)
        if (visible) {
            if (!activeDialogs.contains(this)) {
                activeDialogs.add(this)
            }
        } else {
            activeDialogs.remove(this)
        }
        nativeWidget.notifyTreeInvalidated()
    }

    override fun setTitle(title: String) {
        titleText = title
        nativeWidget.attributes["title"] = title
        onTitleChangedHook?.invoke(this, title)
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

    override fun setPreferredSize(size: Size?) {
        super.setPreferredSize(size)
        if (size == null) {
            hasExplicitBounds = false
            nativeWidget.attributes["hasExplicitBounds"] = false
        } else {
            hasExplicitBounds = true
            nativeWidget.attributes["hasExplicitBounds"] = true
            boundsWidth = size.width
            boundsHeight = size.height
            nativeWidget.bounds = Rect(nativeWidget.bounds.x, nativeWidget.bounds.y, size.width, size.height)
        }
    }

    open fun bringToFront() {
        zOrderLayer = DesktopWindowNativeImpl.nextZOrderSequence()
        nativeWidget.attributes["zOrderLayer"] = zOrderLayer
        nativeWidget.attributes["broughtToFront"] = true
    }

    override fun showDialog(ownerWindow: WindowNativeImpl<*>?) {
        (ownerWindowRef as? DesktopWindowNativeImpl<*>)?.unregisterOwnedDialogWindow(this)
        ownerWindowRef = ownerWindow
        val desktopOwner = ownerWindow as? DesktopWindowNativeImpl<*>
        desktopOwner?.registerOwnedDialogWindow(this)
        nativeWidget.attributes["ownerWindow"] = ownerWindow
        nativeWidget.attributes["attachedToMainWindow"] = ownerWindow != null
        nativeWidget.attributes["transientFor"] = desktopOwner?.nativeWidget
        setVisible(true)
        bringToFront()
        onShowDialogHook?.invoke(this, desktopOwner)
    }

    override fun closeDialog() {
        val wasVisible = isVisible() || activeDialogs.contains(this)
        setVisible(false)
        activeDialogs.remove(this)
        (ownerWindowRef as? DesktopWindowNativeImpl<*>)?.unregisterOwnedDialogWindow(this)
        onCloseDialogHook?.invoke(this)
        if (wasVisible) {
            onDialogClosedListener?.invoke()
        }
    }

    override fun setOnDialogClosed(listener: (() -> Unit)?) {
        onDialogClosedListener = listener
    }

    override fun dispose() {
        (ownerWindowRef as? DesktopWindowNativeImpl<*>)?.unregisterOwnedDialogWindow(this)
        activeDialogs.remove(this)
        onCloseDialogHook?.invoke(this)
        super.dispose()
    }
}
