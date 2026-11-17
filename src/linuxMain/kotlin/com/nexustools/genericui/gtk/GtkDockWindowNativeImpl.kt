package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.dock.DesktopDockWindowNativeImpl

class GtkDockWindowNativeImpl : DesktopDockWindowNativeImpl<GtkWidgetHandle>(
    nativeWidget = GtkWidgetHandle("GtkWindow_Utility")
) {
    init {
        nativeWidget.attributes["floatingUtilityWindow"] = true
        nativeWidget.attributes["skipTaskbarHint"] = true
    }
}
