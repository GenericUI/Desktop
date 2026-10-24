package com.nexustools.genericui.swing

import com.nexustools.genericui.component.system.Notification
import com.nexustools.genericui.component.system.NotificationChannel
import com.nexustools.genericui.nativeimpl.system.NotificationManagerNativeImpl

/**
 * Java AWT `TrayIcon.displayMessage` desktop notification implementation of [NotificationManagerNativeImpl].
 */
class SwingNotificationManagerImpl : NotificationManagerNativeImpl {
    override fun registerChannel(channel: NotificationChannel) {}
    override fun showNotification(notification: Notification, onActionClicked: ((String) -> Unit)?) {}
    override fun cancelNotification(notificationId: String) {}
    override fun dispose() {}
}
