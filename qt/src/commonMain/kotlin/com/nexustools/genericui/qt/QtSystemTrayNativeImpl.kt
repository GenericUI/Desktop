package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.system.DesktopSystemTrayNativeImpl

/**
 * Qt 6 `QSystemTrayIcon` implementation of [com.nexustools.genericui.nativeimpl.system.SystemTrayNativeImpl].
 */
class QtSystemTrayNativeImpl : DesktopSystemTrayNativeImpl(
    isSupported = true
)
