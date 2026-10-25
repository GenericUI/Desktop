package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.dock.DesktopDockWindowNativeImpl

/**
 * Qt 6 floating `QDockWidget` / `Qt::Tool` utility window implementation of
 * [com.nexustools.genericui.nativeimpl.dock.DockWindowNativeImpl]
 * (no taskbar entry, no minimize button, stays on top of owner window).
 */
class QtDockWindowNativeImpl : DesktopDockWindowNativeImpl<QtWidgetHandle>(
    nativeWidget = QtWidgetHandle(
        qtClassName = "QDockWidget",
        qtWindowFlags = QtWidgetHandle.Qt_Tool or QtWidgetHandle.Qt_WindowStaysOnTopHint
    )
) {
    init {
        nativeWidget.attributes["skip-taskbar"] = true
        nativeWidget.attributes["minimizable"] = false
        nativeWidget.attributes["transient-for-owner"] = true
    }
}
