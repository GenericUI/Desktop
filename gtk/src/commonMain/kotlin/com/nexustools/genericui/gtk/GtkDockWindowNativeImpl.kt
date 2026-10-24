package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.dock.DesktopDockWindowNativeImpl

/**
 * GTK 4 floating utility `GtkWindow` implementation of [com.nexustools.genericui.nativeimpl.dock.DockWindowNativeImpl].
 */
class GtkDockWindowNativeImpl : DesktopDockWindowNativeImpl<GtkWidgetHandle>(
    nativeWidget = GtkWidgetHandle("GtkWindow_UtilityDock")
)
