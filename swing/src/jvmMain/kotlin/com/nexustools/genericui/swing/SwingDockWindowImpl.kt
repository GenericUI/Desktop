package com.nexustools.genericui.swing

import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.nativeimpl.dock.DockWindowNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Java Swing utility `JDialog` / floating panel implementation of [DockWindowNativeImpl].
 */
class SwingDockWindowImpl : SwingBaseContainerNativeImpl(JPanel(), "dock-window"), DockWindowNativeImpl<JComponent> {
    override val isToolModalWindow: Boolean = true
    override val hasTaskbarEntry: Boolean = false
    override val isMinimizable: Boolean = false
    override val isAlwaysOnTopOfOwner: Boolean = true

    var ownerWindow: WindowNativeImpl<*>? = null
        private set

    var zOrderLayer: Int = 0
        private set

    private var closeListener: (() -> Unit)? = null

    init {
        nativeWidget.putClientProperty("tool-modal", "true")
        nativeWidget.putClientProperty("show-in-taskbar", "false")
        nativeWidget.putClientProperty("minimizable", "false")
        nativeWidget.putClientProperty("always-on-top-of-owner", "true")
    }

    override fun setTitle(title: String) {
        nativeWidget.putClientProperty("title", title)
    }

    override fun setWindowBounds(bounds: Rect) {
        nativeWidget.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
    }

    override fun showDockWindow() {
        nativeWidget.isVisible = true
        bringToFront()
    }

    override fun hideDockWindow() {
        nativeWidget.isVisible = false
    }

    override fun setOnDockWindowCloseRequested(listener: (() -> Unit)?) {
        closeListener = listener
    }

    override fun attachToOwnerWindow(ownerWindow: WindowNativeImpl<*>?) {
        (this.ownerWindow as? SwingWindowImpl)?.unregisterOwnedDockWindow(this)
        this.ownerWindow = ownerWindow
        nativeWidget.putClientProperty("ownerWindow", ownerWindow)
        (ownerWindow as? SwingWindowImpl)?.registerOwnedDockWindow(this)
        bringToFront()
    }

    override fun bringToFront() {
        zOrderLayer = SwingWindowImpl.nextZOrderSequence()
        nativeWidget.putClientProperty("zOrderLayer", zOrderLayer)
        nativeWidget.putClientProperty("broughtToFront", true)
    }
}
