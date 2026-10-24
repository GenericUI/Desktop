package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.system.DesktopSystemTrayNativeImpl

/**
 * GTK / Linux system tray implementation using the freedesktop / KDE `org.kde.StatusNotifierItem` D-Bus protocol.
 */
class GtkSystemTrayNativeImpl : DesktopSystemTrayNativeImpl(
    isSupported = true
)
