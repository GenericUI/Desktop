package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.dock.DesktopDockWindowNativeImpl
import com.nexustools.genericui.geometry.Rect

/**
 * macOS implementation of floating dock tool windows (`NSPanel`).
 */
class MacosDockWindowNativeImpl : DesktopDockWindowNativeImpl<MacosViewHandle>(
    nativeWidget = MacosViewHandle(
        objcClassName = "NSPanel",
        styleMask = MacosViewHandle.NSWindowStyleMaskTitled or
            MacosViewHandle.NSWindowStyleMaskClosable or
            MacosViewHandle.NSWindowStyleMaskResizable or
            MacosViewHandle.NSWindowStyleMaskUtilityWindow
    )
) {
    override fun showDockWindow() {
        super.showDockWindow()
        MacosNativeWindowHost.presentDockWindow(this)
    }

    override fun hideDockWindow() {
        super.hideDockWindow()
        MacosNativeWindowHost.closeDockWindow(this)
    }

    override fun setTitle(title: String) {
        super.setTitle(title)
        MacosNativeWindowHost.syncDockWindowProperties(this)
    }

    override fun setWindowBounds(bounds: Rect) {
        super.setWindowBounds(bounds)
        MacosNativeWindowHost.syncDockWindowProperties(this)
    }

    override fun dispose() {
        MacosNativeWindowHost.closeDockWindow(this)
        super.dispose()
    }
}
