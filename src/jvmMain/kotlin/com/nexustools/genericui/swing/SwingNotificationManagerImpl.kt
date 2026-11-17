package com.nexustools.genericui.swing

import com.nexustools.genericui.component.system.Notification
import com.nexustools.genericui.component.system.NotificationChannel
import com.nexustools.genericui.nativeimpl.system.NotificationManagerNativeImpl
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.ActionListener

/**
 * Java AWT `TrayIcon.displayMessage` desktop notification implementation of [NotificationManagerNativeImpl].
 */
class SwingNotificationManagerImpl : NotificationManagerNativeImpl {

    override var isDisposed: Boolean = false
        private set

    private val channels = linkedMapOf<String, NotificationChannel>()
    private val activeNotifications = linkedMapOf<String, Notification>()
    private var ownedTrayIcon: TrayIcon? = null
    private var activeActionListener: ActionListener? = null

    override fun registerChannel(channel: NotificationChannel) {
        if (isDisposed) return
        channels[channel.id] = channel
    }

    override fun showNotification(notification: Notification, onActionClicked: ((String) -> Unit)?) {
        check(!isDisposed) { "SwingNotificationManagerImpl has been disposed." }
        if (!SwingSystemTrayImpl.checkSystemTraySupported()) {
            throw UnsupportedOperationException(
                "Desktop notifications require java.awt.SystemTray support (unavailable in headless environments)."
            )
        }
        val icon = resolveOrCreateTrayIcon(notification)
        activeActionListener?.let { prev -> icon.removeActionListener(prev) }
        val defaultActionId = notification.actions.firstOrNull()?.first
        val newListener = if (onActionClicked != null && defaultActionId != null) {
            ActionListener { onActionClicked(defaultActionId) }.also {
                icon.addActionListener(it)
            }
        } else {
            null
        }
        activeActionListener = newListener
        val channel = channels[notification.channelId]
        val messageType = if (channel?.highImportance == true) {
            TrayIcon.MessageType.WARNING
        } else {
            TrayIcon.MessageType.INFO
        }
        activeNotifications[notification.id] = notification
        icon.displayMessage(notification.title, notification.body, messageType)
    }

    override fun cancelNotification(notificationId: String) {
        check(!isDisposed) { "SwingNotificationManagerImpl has been disposed." }
        activeNotifications.remove(notificationId)
        if (activeNotifications.isEmpty()) {
            releaseOwnedTrayIcon()
        }
    }

    override fun dispose() {
        isDisposed = true
        channels.clear()
        activeNotifications.clear()
        releaseOwnedTrayIcon()
    }

    private fun resolveOrCreateTrayIcon(notification: Notification): TrayIcon {
        SwingSystemTrayImpl.currentPrimaryTrayIcon()?.let { existing ->
            return existing
        }
        ownedTrayIcon?.let { existing ->
            notification.iconPathOrUrl?.let { iconPath ->
                SwingSystemTrayImpl.loadTrayImage(iconPath)?.let { img -> existing.image = img }
            }
            return existing
        }
        val img = SwingSystemTrayImpl.loadTrayImage(notification.iconPathOrUrl)
            ?: SwingSystemTrayImpl.createDefaultTrayImage()
        val created = TrayIcon(img, notification.title).apply {
            isImageAutoSize = true
        }
        SystemTray.getSystemTray().add(created)
        ownedTrayIcon = created
        return created
    }

    private fun releaseOwnedTrayIcon() {
        val icon = ownedTrayIcon
        val listener = activeActionListener
        activeActionListener = null
        ownedTrayIcon = null
        if (icon != null) {
            if (listener != null) {
                runCatching { icon.removeActionListener(listener) }
            }
            runCatching { SystemTray.getSystemTray().remove(icon) }
        }
    }
}
