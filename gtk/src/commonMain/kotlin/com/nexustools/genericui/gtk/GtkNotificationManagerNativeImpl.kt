package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.system.DesktopNotificationManagerNativeImpl

/**
 * GTK / GIO `GNotification` (`g_application_send_notification` / `org.freedesktop.Notifications`)
 * implementation of [com.nexustools.genericui.nativeimpl.system.NotificationManagerNativeImpl].
 */
class GtkNotificationManagerNativeImpl : DesktopNotificationManagerNativeImpl(
    backendToolkit = "GIO GNotification (org.freedesktop.Notifications)"
)
