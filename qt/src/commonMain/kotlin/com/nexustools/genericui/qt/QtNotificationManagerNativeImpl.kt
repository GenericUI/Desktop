package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.system.DesktopNotificationManagerNativeImpl

/**
 * Qt 6 `QSystemTrayIcon::showMessage` / platform notification bridge implementation of
 * [com.nexustools.genericui.nativeimpl.system.NotificationManagerNativeImpl].
 */
class QtNotificationManagerNativeImpl : DesktopNotificationManagerNativeImpl(
    backendToolkit = "Qt6 QSystemTrayIcon::showMessage"
)
