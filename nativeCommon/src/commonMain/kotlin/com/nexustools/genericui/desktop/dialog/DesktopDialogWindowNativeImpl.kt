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
    private var isDialogShown: Boolean = false
    private var isDialogVisible: Boolean = false

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
        nativeWidget.attributes["dismissOnBackdropClick"] = true
        nativeWidget.attributes["modal"] = modalState
        nativeWidget.attributes["backdropBlurPx"] = if (modalState) 6 else 0
        nativeWidget.attributes["presentationStyle"] = presentationStyleState
    }

    override fun setVisible(visible: Boolean) {
        val changed = isDialogVisible != visible || nativeWidget.visible != visible
        isDialogVisible = visible
        nativeWidget.visible = visible
        if (visible && isDialogShown) {
            if (!activeDialogs.contains(this)) {
                activeDialogs.add(this)
            }
        } else if (!visible) {
            activeDialogs.remove(this)
        }
        if (changed) {
            notifyTreeInvalidated()
        }
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
        nativeWidget.attributes["backdropBlurPx"] = if (modal) 6 else 0
    }

    fun isBackdropDismissAllowed(): Boolean =
        modalState &&
            (nativeWidget.attributes["closable"] as? Boolean ?: true) &&
            (nativeWidget.attributes["dismissOnBackdropClick"] as? Boolean ?: true)

    fun dismissOnBackdropClick(): Boolean {
        if (!isBackdropDismissAllowed()) return false
        closeDialog()
        return true
    }

    override fun setFullscreen(fullscreen: Boolean) {
        fullscreenState = fullscreen
        nativeWidget.attributes["fullscreen"] = fullscreen
    }

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        hasExplicitBounds = true
        nativeWidget.attributes["hasExplicitBounds"] = true
        boundsWidth = width
        boundsHeight = height
        nativeWidget.bounds = Rect(x.toFloat(), y.toFloat(), width.toFloat(), height.toFloat())
        nativeWidget.subtreeMutationVersion = ++DesktopNativeWidget.globalTreeMutationVersion
        notifyTreeInvalidated()
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
        nativeWidget.subtreeMutationVersion = ++DesktopNativeWidget.globalTreeMutationVersion
        notifyTreeInvalidated()
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
        isDialogShown = true
        isDialogVisible = true
        super.setVisible(true)
        if (!activeDialogs.contains(this)) {
            activeDialogs.add(this)
        }
        bringToFront()
        onShowDialogHook?.invoke(this, desktopOwner)
        notifyTreeInvalidated()
    }

    override fun closeDialog() {
        val wasVisible = isVisible() || activeDialogs.contains(this)
        isDialogShown = false
        isDialogVisible = false
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
        isDialogShown = false
        isDialogVisible = false
        (ownerWindowRef as? DesktopWindowNativeImpl<*>)?.unregisterOwnedDialogWindow(this)
        activeDialogs.remove(this)
        onCloseDialogHook?.invoke(this)
        super.dispose()
    }
}
