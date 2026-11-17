package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.dialog.DialogPresentationStyle
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.dialog.DialogWindowNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dialog
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JPanel

/**
 * Java Swing `JDialog` implementation of [DialogWindowNativeImpl].
 */
class SwingDialogWindowImpl : SwingBaseContainerNativeImpl(JPanel(BorderLayout()), "dialogwindow"), DialogWindowNativeImpl<JComponent> {
    private val bodyPanel = JPanel(BorderLayout())
    private var titleText: String = ""
    private var styleVal: DialogPresentationStyle = DialogPresentationStyle.MODAL_CENTERED
    private var modalFlag: Boolean = true
    private var fullscreenFlag: Boolean = false
    private var hasExplicitBounds: Boolean = false
    private var onCloseListener: (() -> Unit)? = null

    var ownerWindow: WindowNativeImpl<*>? = null
        private set

    var zOrderLayer: Int = 0
        private set

    var jDialog: JDialog? = null
        private set

    private val fallbackRootPane = javax.swing.JRootPane()

    private fun setClientPropOnWidgetAndRoot(key: String, value: Any?) {
        nativeWidget.putClientProperty(key, value)
        fallbackRootPane.putClientProperty(key, value)
        nativeWidget.rootPane?.putClientProperty(key, value)
        jDialog?.rootPane?.putClientProperty(key, value)
    }

    init {
        setVisible(false)
        bodyPanel.isOpaque = false
        nativeWidget.add(bodyPanel, BorderLayout.CENTER)
        fallbackRootPane.contentPane.add(nativeWidget, BorderLayout.CENTER)
        nativeWidget.setBounds(120, 120, 560, 400)
        setClientPropOnWidgetAndRoot("isSeparateNativeWindow", true)
        setClientPropOnWidgetAndRoot("inWindowOverlay", false)
        setClientPropOnWidgetAndRoot("minimizable", false)
        setClientPropOnWidgetAndRoot("maximizable", false)
        setClientPropOnWidgetAndRoot("closable", true)
        setClientPropOnWidgetAndRoot("modal", modalFlag)
        setClientPropOnWidgetAndRoot("presentationStyle", styleVal)
    }

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        val childComp = childImpl.nativeWidget as? JComponent ?: return
        val clientClass = childComp.getClientProperty("class") as? String
        val isFooter = childImpl.hasClass("modal-footer") ||
            childImpl.getClasses().contains("modal-footer") ||
            (clientClass != null && clientClass.contains("modal-footer"))
        if (isFooter) {
            childComp.putClientProperty("class", childImpl.getClass().ifEmpty { "modal-footer" })
            childComp.putClientProperty("dialogSlot", "footer")
            childComp.border = BorderFactory.createMatteBorder(1, 0, 0, 0, Color(60, 68, 84))
            nativeWidget.putClientProperty("hasFooterButtonBar", true)
            nativeWidget.add(childComp, BorderLayout.SOUTH)
        } else {
            childComp.putClientProperty("dialogSlot", "body")
            if (index < 0 || index >= bodyPanel.componentCount) {
                bodyPanel.add(childComp, BorderLayout.CENTER)
            } else {
                bodyPanel.add(childComp, index)
            }
        }
        bodyPanel.revalidate()
        nativeWidget.revalidate()
        nativeWidget.repaint()
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        val childComp = childImpl.nativeWidget as? JComponent ?: return
        bodyPanel.remove(childComp)
        nativeWidget.remove(childComp)
        bodyPanel.revalidate()
        nativeWidget.revalidate()
        nativeWidget.repaint()
    }

    override fun clearChildrenNative() {
        bodyPanel.removeAll()
        nativeWidget.removeAll()
        nativeWidget.add(bodyPanel, BorderLayout.CENTER)
        nativeWidget.putClientProperty("hasFooterButtonBar", false)
        bodyPanel.revalidate()
        nativeWidget.revalidate()
        nativeWidget.repaint()
    }

    override fun setTitle(title: String) {
        titleText = title
        nativeWidget.putClientProperty("title", title)
        try {
            jDialog?.title = title
        } catch (_: Throwable) {
        }
    }

    override fun getTitle(): String = titleText

    override fun setPresentationStyle(style: DialogPresentationStyle) {
        styleVal = style
        nativeWidget.putClientProperty("presentationStyle", style)
    }

    override fun getPresentationStyle(): DialogPresentationStyle = styleVal

    override fun setModal(modal: Boolean) {
        modalFlag = modal
        nativeWidget.putClientProperty("modal", modal)
    }

    override fun setFullscreen(fullscreen: Boolean) {
        fullscreenFlag = fullscreen
        nativeWidget.putClientProperty("fullscreen", fullscreen)
    }

    override fun setPreferredSize(size: Size?) {
        super.setPreferredSize(size)
        if (size == null) {
            hasExplicitBounds = false
            nativeWidget.preferredSize = null
            setClientPropOnWidgetAndRoot("hasExplicitBounds", false)
            try {
                jDialog?.pack()
            } catch (_: Throwable) {
            }
        } else {
            hasExplicitBounds = true
            setClientPropOnWidgetAndRoot("hasExplicitBounds", true)
            try {
                jDialog?.setSize(size.width.coerceAtLeast(220), size.height.coerceAtLeast(140))
            } catch (_: Throwable) {
            }
        }
    }

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        hasExplicitBounds = true
        setClientPropOnWidgetAndRoot("hasExplicitBounds", true)
        super.setBounds(x, y, width, height)
        try {
            jDialog?.setBounds(x, y, width, height)
        } catch (_: Throwable) {
        }
    }

    fun bringToFront() {
        zOrderLayer = SwingWindowImpl.nextZOrderSequence()
        nativeWidget.putClientProperty("zOrderLayer", zOrderLayer)
        nativeWidget.putClientProperty("broughtToFront", true)
        try {
            if (jDialog?.isVisible == true) {
                jDialog?.toFront()
            }
        } catch (_: Throwable) {
        }
    }

    override fun showDialog(ownerWindow: WindowNativeImpl<*>?) {
        (this.ownerWindow as? SwingWindowImpl)?.unregisterOwnedDialogWindow(this)
        this.ownerWindow = ownerWindow
        val swingOwner = ownerWindow as? SwingWindowImpl
        swingOwner?.registerOwnedDialogWindow(this)
        setClientPropOnWidgetAndRoot("ownerWindow", ownerWindow)
        setClientPropOnWidgetAndRoot("attachedToMainWindow", ownerWindow != null)
        setClientPropOnWidgetAndRoot("transientFor", swingOwner?.nativeWidget)
        setVisible(true)
        if (!GraphicsEnvironment.isHeadless()) {
            try {
                val existing = jDialog
                val dialog = if (existing != null && existing.owner === swingOwner?.jFrame) {
                    existing
                } else {
                    existing?.dispose()
                    JDialog(swingOwner?.jFrame, titleText, Dialog.ModalityType.MODELESS).also { created ->
                        created.contentPane.add(nativeWidget, BorderLayout.CENTER)
                        jDialog = created
                    }
                }
                setClientPropOnWidgetAndRoot("isSeparateNativeWindow", true)
                setClientPropOnWidgetAndRoot("inWindowOverlay", false)
                setClientPropOnWidgetAndRoot("minimizable", false)
                setClientPropOnWidgetAndRoot("maximizable", false)
                setClientPropOnWidgetAndRoot("closable", true)
                setClientPropOnWidgetAndRoot("modal", modalFlag)
                setClientPropOnWidgetAndRoot("presentationStyle", styleVal)
                setClientPropOnWidgetAndRoot("ownerWindow", ownerWindow)
                setClientPropOnWidgetAndRoot("attachedToMainWindow", ownerWindow != null)
                setClientPropOnWidgetAndRoot("transientFor", swingOwner?.nativeWidget)
                dialog.title = titleText
                if (hasExplicitBounds) {
                    val b = getBounds()
                    dialog.setBounds(
                        b.x,
                        b.y,
                        b.width.coerceAtLeast(220),
                        b.height.coerceAtLeast(140)
                    )
                } else {
                    nativeWidget.preferredSize = null
                    dialog.pack()
                    val screenBounds = runCatching {
                        GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                    }.getOrNull()
                    val maxW = (screenBounds?.width ?: 1280) - 48
                    val maxH = (screenBounds?.height ?: 800) - 64
                    val packedW = dialog.width.coerceIn(220, maxW.coerceAtLeast(260))
                    val packedH = dialog.height.coerceIn(140, maxH.coerceAtLeast(220))
                    dialog.setSize(packedW, packedH)
                    dialog.setLocationRelativeTo(swingOwner?.jFrame)
                    nativeWidget.setBounds(dialog.x, dialog.y, packedW, packedH)
                }
                dialog.isVisible = true
            } catch (_: Throwable) {
            }
        }
        bringToFront()
    }

    override fun closeDialog() {
        val wasVisible = isVisible()
        setVisible(false)
        (ownerWindow as? SwingWindowImpl)?.unregisterOwnedDialogWindow(this)
        try {
            jDialog?.isVisible = false
            jDialog?.dispose()
            jDialog = null
        } catch (_: Throwable) {
        }
        if (wasVisible) {
            onCloseListener?.invoke()
        }
    }

    override fun setOnDialogClosed(listener: (() -> Unit)?) {
        onCloseListener = listener
    }

    override fun dispose() {
        (ownerWindow as? SwingWindowImpl)?.unregisterOwnedDialogWindow(this)
        try {
            jDialog?.isVisible = false
            jDialog?.dispose()
            jDialog = null
        } catch (_: Throwable) {
        }
        super.dispose()
    }
}
