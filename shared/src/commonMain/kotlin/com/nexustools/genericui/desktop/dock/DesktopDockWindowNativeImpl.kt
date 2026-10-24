package com.nexustools.genericui.desktop.dock

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.nativeimpl.dock.DockWindowNativeImpl

/**
 * Shared desktop base implementation of [DockWindowNativeImpl] for detached floating utility windows
 * (`WS_EX_TOOLWINDOW` on `:windows`, `NSPanel` on `:macos`, utility `GtkWindow` on `:gtk`, `QDockWidget`/`Qt::Tool` on `:qt`).
 */
open class DesktopDockWindowNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "dock-window"), DockWindowNativeImpl<W> {

    protected var titleText: String = ""
    protected var onCloseRequestedListener: (() -> Unit)? = null

    override fun setTitle(title: String) {
        titleText = title
        nativeWidget.attributes["title"] = title
    }

    override fun setWindowBounds(bounds: Rect) {
        nativeWidget.bounds = bounds
    }

    override fun showDockWindow() {
        setVisible(true)
    }

    override fun hideDockWindow() {
        setVisible(false)
    }

    override fun setOnDockWindowCloseRequested(listener: (() -> Unit)?) {
        onCloseRequestedListener = listener
    }
}
