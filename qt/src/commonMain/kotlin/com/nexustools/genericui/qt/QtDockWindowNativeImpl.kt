package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.dock.DesktopDockWindowNativeImpl

/**
 * Qt 6 floating `QDockWidget` / `Qt::Tool` utility window implementation of
 * [com.nexustools.genericui.nativeimpl.dock.DockWindowNativeImpl].
 */
class QtDockWindowNativeImpl : DesktopDockWindowNativeImpl<QtWidgetHandle>(
    nativeWidget = QtWidgetHandle("QDockWidget", QtWidgetHandle.Qt_Tool)
)
