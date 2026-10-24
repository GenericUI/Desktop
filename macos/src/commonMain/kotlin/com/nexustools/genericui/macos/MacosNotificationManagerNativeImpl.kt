package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.system.DesktopNotificationManagerNativeImpl

/**
 * macOS UserNotifications framework (`UNUserNotificationCenter` / `UNMutableNotificationContent`)
 * implementation of [com.nexustools.genericui.nativeimpl.system.NotificationManagerNativeImpl].
 */
class MacosNotificationManagerNativeImpl : DesktopNotificationManagerNativeImpl(
    backendToolkit = "macOS UNUserNotificationCenter"
)
