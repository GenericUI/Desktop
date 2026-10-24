package com.nexustools.genericui.desktop.system

import com.nexustools.genericui.component.system.Notification
import com.nexustools.genericui.component.system.NotificationChannel
import com.nexustools.genericui.nativeimpl.system.NotificationManagerNativeImpl

/**
 * Shared desktop base implementation of [NotificationManagerNativeImpl] for Windows Toast Notifications,
 * macOS `UNUserNotificationCenter`, GTK `GNotification`, and Qt `QSystemTrayIcon::showMessage`.
 */
open class DesktopNotificationManagerNativeImpl(
    val backendToolkit: String = "Desktop"
) : NotificationManagerNativeImpl {

    override var isDisposed: Boolean = false
        protected set

    protected val channels: MutableMap<String, NotificationChannel> = linkedMapOf()
    protected val activeNotifications: MutableMap<String, Notification> = linkedMapOf()

    override fun registerChannel(channel: NotificationChannel) {
        channels[channel.id] = channel
    }

    override fun showNotification(notification: Notification, onActionClicked: ((String) -> Unit)?) {
        activeNotifications[notification.id] = notification
    }

    override fun cancelNotification(notificationId: String) {
        activeNotifications.remove(notificationId)
    }

    override fun dispose() {
        activeNotifications.clear()
        isDisposed = true
    }
}
