package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.dock.DesktopDockWindowNativeImpl

/**
 * GTK 4 floating utility `GtkWindow` implementation of [com.nexustools.genericui.nativeimpl.dock.DockWindowNativeImpl]
 * (`gtk_window_set_transient_for`, no taskbar entry, no minimize button).
 */
class GtkDockWindowNativeImpl : DesktopDockWindowNativeImpl<GtkWidgetHandle>(
    nativeWidget = GtkWidgetHandle("GtkWindow_UtilityDock")
) {
    init {
        nativeWidget.attributes["skip-taskbar"] = true
        nativeWidget.attributes["minimizable"] = false
        nativeWidget.attributes["transient-for-owner"] = true
    }
}
