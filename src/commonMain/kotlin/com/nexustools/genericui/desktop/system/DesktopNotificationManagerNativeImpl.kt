package com.nexustools.genericui.desktop.system

import com.nexustools.genericui.component.system.Notification
import com.nexustools.genericui.component.system.NotificationChannel
import com.nexustools.genericui.nativeimpl.system.NotificationManagerNativeImpl

/**
 * Shared desktop base implementation of [NotificationManagerNativeImpl].
 *
 * Maintains registered [channels] and requires platform backends to implement OS notification
 * dispatch and cancellation.
 */
open class DesktopNotificationManagerNativeImpl(
    val backendToolkit: String = "Desktop"
) : NotificationManagerNativeImpl {

    override var isDisposed: Boolean = false
        protected set

    protected val channels: MutableMap<String, NotificationChannel> = linkedMapOf()
    protected val activeNotifications: MutableMap<String, Notification> = linkedMapOf()

    override fun registerChannel(channel: NotificationChannel) {
        if (isDisposed) return
        channels[channel.id] = channel
    }

    override fun showNotification(notification: Notification, onActionClicked: ((String) -> Unit)?) {
        throw UnsupportedOperationException("$backendToolkit notification dispatch requires a platform OS implementation.")
    }

    override fun cancelNotification(notificationId: String) {
        throw UnsupportedOperationException("$backendToolkit notification cancellation requires a platform OS implementation.")
    }

    override fun dispose() {
        isDisposed = true
        channels.clear()
        activeNotifications.clear()
    }
}
