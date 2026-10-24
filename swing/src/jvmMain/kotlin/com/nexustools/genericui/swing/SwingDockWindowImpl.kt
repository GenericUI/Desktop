package com.nexustools.genericui.swing

import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.nativeimpl.dock.DockWindowNativeImpl
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Java Swing utility `JDialog` / floating panel implementation of [DockWindowNativeImpl].
 */
class SwingDockWindowImpl : SwingBaseContainerNativeImpl(JPanel(), "dock-window"), DockWindowNativeImpl<JComponent> {
    private var closeListener: (() -> Unit)? = null

    override fun setTitle(title: String) {
        nativeWidget.putClientProperty("title", title)
    }

    override fun setWindowBounds(bounds: Rect) {
        nativeWidget.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
    }

    override fun showDockWindow() {
        nativeWidget.isVisible = true
    }

    override fun hideDockWindow() {
        nativeWidget.isVisible = false
    }

    override fun setOnDockWindowCloseRequested(listener: (() -> Unit)?) {
        closeListener = listener
    }
}
