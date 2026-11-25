package com.nexustools.genericui.swing

import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.nativeimpl.dock.DockWindowNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import java.awt.BorderLayout
import java.awt.Dialog
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowListener
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.WindowConstants

/**
 * Java Swing utility `JDialog` floating dock window implementation of [DockWindowNativeImpl].
 */
class SwingDockWindowImpl : SwingBaseContainerNativeImpl(
    JPanel(BorderLayout()),
    "dock-window"
), DockWindowNativeImpl<JComponent> {

    override val isToolModalWindow: Boolean = true
    override val hasTaskbarEntry: Boolean = false
    override val isMinimizable: Boolean = false
    override val isAlwaysOnTopOfOwner: Boolean = true

    var ownerWindow: WindowNativeImpl<*>? = null
        private set

    var zOrderLayer: Int = 0
        private set

    var jDialog: JDialog? = null
        private set

    private var titleText: String = ""
    private var requestedBounds: Rect? = null
    private var closeListener: (() -> Unit)? = null

    val windowClosingListener: WindowListener = object : WindowAdapter() {
        override fun windowClosing(e: WindowEvent?) {
            closeListener?.invoke()
        }
    }

    init {
        nativeWidget.putClientProperty("tool-modal", "true")
        nativeWidget.putClientProperty("show-in-taskbar", "false")
        nativeWidget.putClientProperty("minimizable", "false")
        nativeWidget.putClientProperty("always-on-top-of-owner", "true")
    }

    override fun setTitle(title: String) {
        titleText = title
        nativeWidget.putClientProperty("title", title)
        jDialog?.title = title
    }

    override fun setWindowBounds(bounds: Rect) {
        requestedBounds = bounds
        nativeWidget.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
        jDialog?.setBounds(
            bounds.x,
            bounds.y,
            bounds.width.coerceAtLeast(160),
            bounds.height.coerceAtLeast(120)
        )
    }

    override fun showDockWindow() {
        nativeWidget.isVisible = true
        if (!GraphicsEnvironment.isHeadless()) {
            val swingOwner = ownerWindow as? SwingWindowImpl
            val dialog = ensureNativeDialog(swingOwner)
            dialog.title = titleText
            val bounds = requestedBounds
            if (bounds != null) {
                dialog.setBounds(
                    bounds.x,
                    bounds.y,
                    bounds.width.coerceAtLeast(160),
                    bounds.height.coerceAtLeast(120)
                )
            } else if (SwingApplicationImpl.isInteractiveSwingDisplayEnabled) {
                dialog.pack()
                if (dialog.width < 200 || dialog.height < 140) {
                    dialog.setSize(dialog.width.coerceAtLeast(240), dialog.height.coerceAtLeast(180))
                }
                swingOwner?.jFrame?.let { dialog.setLocationRelativeTo(it) }
            } else {
                dialog.setSize(240, 180)
                nativeWidget.setBounds(0, 0, 240, 180)
            }
            if (SwingApplicationImpl.isInteractiveSwingDisplayEnabled) {
                dialog.isVisible = true
            }
        }
        if (SwingApplicationImpl.isInteractiveSwingDisplayEnabled) {
            bringToFront()
        }
    }

    override fun hideDockWindow() {
        nativeWidget.isVisible = false
        jDialog?.isVisible = false
    }

    override fun setOnDockWindowCloseRequested(listener: (() -> Unit)?) {
        closeListener = listener
    }

    override fun attachToOwnerWindow(ownerWindow: WindowNativeImpl<*>?) {
        (this.ownerWindow as? SwingWindowImpl)?.unregisterOwnedDockWindow(this)
        this.ownerWindow = ownerWindow
        nativeWidget.putClientProperty("ownerWindow", ownerWindow)
        (ownerWindow as? SwingWindowImpl)?.registerOwnedDockWindow(this)
        if (!GraphicsEnvironment.isHeadless() && jDialog != null && jDialog?.owner !== (ownerWindow as? SwingWindowImpl)?.jFrame) {
            val wasVisible = jDialog?.isVisible == true
            disposeNativeDialog()
            if (wasVisible) {
                showDockWindow()
            }
        }
        bringToFront()
    }

    override fun bringToFront() {
        zOrderLayer = SwingWindowImpl.nextZOrderSequence()
        nativeWidget.putClientProperty("zOrderLayer", zOrderLayer)
        nativeWidget.putClientProperty("broughtToFront", true)
        runCatching {
            if (jDialog?.isVisible == true) {
                jDialog?.toFront()
            }
        }
    }

    override fun dispose() {
        (ownerWindow as? SwingWindowImpl)?.unregisterOwnedDockWindow(this)
        disposeNativeDialog()
        closeListener = null
        super.dispose()
    }

    private fun ensureNativeDialog(swingOwner: SwingWindowImpl?): JDialog {
        val existing = jDialog
        if (existing != null && existing.owner === swingOwner?.jFrame) {
            return existing
        }
        disposeNativeDialog()
        val created = JDialog(swingOwner?.jFrame, titleText, Dialog.ModalityType.MODELESS).apply {
            defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            isAlwaysOnTop = true
            contentPane.layout = BorderLayout()
            contentPane.add(nativeWidget, BorderLayout.CENTER)
            addWindowListener(windowClosingListener)
        }
        jDialog = created
        return created
    }

    private fun disposeNativeDialog() {
        val dialog = jDialog ?: return
        jDialog = null
        runCatching {
            dialog.removeWindowListener(windowClosingListener)
            dialog.isVisible = false
            dialog.dispose()
        }
    }
}
