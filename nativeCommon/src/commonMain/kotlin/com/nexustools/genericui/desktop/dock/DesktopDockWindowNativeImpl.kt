package com.nexustools.genericui.desktop.dock

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.nativeimpl.dock.DockWindowNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl

/**
 * Shared desktop base implementation of [DockWindowNativeImpl] for detached floating utility windows
 * (`WS_EX_TOOLWINDOW` on `:windows`, `NSPanel` on `:macos`, utility `GtkWindow` on `:gtk`, `QDockWidget`/`Qt::Tool` on `:qt`).
 *
 * Strictly behaves as a tool modal window attached to its owner [WindowNativeImpl]:
 * no taskbar entry, no minimize button, and always on top of the owner window.
 */
open class DesktopDockWindowNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "dock-window"), DockWindowNativeImpl<W> {

    override val isToolModalWindow: Boolean = true
    override val hasTaskbarEntry: Boolean = false
    override val isMinimizable: Boolean = false
    override val isAlwaysOnTopOfOwner: Boolean = true

    var ownerWindow: WindowNativeImpl<*>? = null
        protected set

    var zOrderLayer: Int = 0
        protected set

    protected var titleText: String = ""
    protected var onCloseRequestedListener: (() -> Unit)? = null

    init {
        nativeWidget.attributes["tool-modal"] = "true"
        nativeWidget.attributes["show-in-taskbar"] = "false"
        nativeWidget.attributes["minimizable"] = "false"
        nativeWidget.attributes["always-on-top-of-owner"] = "true"
    }

    override fun setTitle(title: String) {
        titleText = title
        nativeWidget.attributes["title"] = title
    }

    override fun setWindowBounds(bounds: Rect) {
        nativeWidget.bounds = bounds
    }

    override fun showDockWindow() {
        setVisible(true)
        bringToFront()
    }

    override fun hideDockWindow() {
        setVisible(false)
    }

    override fun setOnDockWindowCloseRequested(listener: (() -> Unit)?) {
        onCloseRequestedListener = listener
    }

    override fun attachToOwnerWindow(ownerWindow: WindowNativeImpl<*>?) {
        (this.ownerWindow as? DesktopWindowNativeImpl<*>)?.unregisterOwnedDockWindow(this)
        this.ownerWindow = ownerWindow
        nativeWidget.attributes["ownerWindow"] = ownerWindow
        nativeWidget.attributes["tool-modal"] = "true"
        nativeWidget.attributes["show-in-taskbar"] = "false"
        nativeWidget.attributes["minimizable"] = "false"
        nativeWidget.attributes["always-on-top-of-owner"] = "true"
        (ownerWindow as? DesktopWindowNativeImpl<*>)?.registerOwnedDockWindow(this)
        bringToFront()
    }

    override fun bringToFront() {
        zOrderLayer = DesktopWindowNativeImpl.nextZOrderSequence()
        nativeWidget.attributes["zOrderLayer"] = zOrderLayer
        nativeWidget.attributes["broughtToFront"] = true
    }

    override fun dispose() {
        (ownerWindow as? DesktopWindowNativeImpl<*>)?.unregisterOwnedDockWindow(this)
        ownerWindow = null
        super.dispose()
    }
}
