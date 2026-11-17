@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.component.system.Notification
import com.nexustools.genericui.component.system.NotificationChannel
import com.nexustools.genericui.nativeimpl.system.NotificationManagerNativeImpl
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction

class LinuxDbusNotificationManagerNativeImpl(
    private val appName: String = "GenericUI"
) : NotificationManagerNativeImpl {
    private val lock = LinuxReentrantLock()
    private val channels = LinkedHashMap<String, NotificationChannel>()
    private val activeNotifications = LinkedHashMap<String, Notification>()
    private val dbusIdByLocalId = LinkedHashMap<String, UInt>()
    private val localIdByDbusId = LinkedHashMap<UInt, String>()
    private val actionCallbacks = LinkedHashMap<String, (String) -> Unit>()

    private var sessionBus: COpaquePointer? = null
    private var actionSignalSubId: UInt = 0u
    private var closedSignalSubId: UInt = 0u
    private var selfRef: StableRef<LinuxDbusNotificationManagerNativeImpl>? = null
    private var nextFallbackId: UInt = 1u
    private var disposedState = false

    override val isDisposed: Boolean
        get() = lock.withLock { disposedState }

    override fun registerChannel(channel: NotificationChannel) {
        lock.withLock {
            if (!disposedState) {
                channels[channel.id] = channel
            }
        }
    }

    override fun showNotification(notification: Notification, onActionClicked: ((String) -> Unit)?) {
        val (existingReplacesId, channel) = lock.withLock {
            if (disposedState) return
            activeNotifications[notification.id] = notification
            if (onActionClicked != null) {
                actionCallbacks[notification.id] = onActionClicked
            } else {
                actionCallbacks.remove(notification.id)
            }
            (dbusIdByLocalId[notification.id] ?: 0u) to channels[notification.channelId]
        }

        val bus = ensureSessionBus() ?: return
        val params = buildNotifyParams(notification, existingReplacesId, channel) ?: return
        val reply = LinuxGlibGioBindings.dbusCallSync(
            connection = bus,
            busName = NOTIFICATIONS_BUS_NAME,
            objectPath = NOTIFICATIONS_OBJECT_PATH,
            interfaceName = NOTIFICATIONS_INTERFACE,
            methodName = "Notify",
            parameters = params
        )
        if (reply != null) {
            val child = LinuxGlibGioBindings.variantGetChildValue(reply, 0)
            val serverId = if (child != null) LinuxGlibGioBindings.variantGetUint32(child) else 0u
            LinuxGlibGioBindings.variantUnref(child)
            LinuxGlibGioBindings.variantUnref(reply)

            val resolvedId = if (serverId != 0u) serverId else lock.withLock {
                nextFallbackId++
            }
            lock.withLock {
                dbusIdByLocalId[notification.id] = resolvedId
                localIdByDbusId[resolvedId] = notification.id
            }
        }
    }

    override fun cancelNotification(notificationId: String) {
        val dbusId = lock.withLock {
            if (disposedState) return
            activeNotifications.remove(notificationId)
            actionCallbacks.remove(notificationId)
            val id = dbusIdByLocalId.remove(notificationId)
            if (id != null) localIdByDbusId.remove(id)
            id
        } ?: return

        val bus = lock.withLock { sessionBus } ?: return
        val uintArg = LinuxGlibGioBindings.variantNewUint32(dbusId) ?: return
        val tuple = LinuxGlibGioBindings.variantNewTuple(listOf(uintArg)) ?: run {
            LinuxGlibGioBindings.variantUnref(uintArg)
            return
        }
        val reply = LinuxGlibGioBindings.dbusCallSync(
            connection = bus,
            busName = NOTIFICATIONS_BUS_NAME,
            objectPath = NOTIFICATIONS_OBJECT_PATH,
            interfaceName = NOTIFICATIONS_INTERFACE,
            methodName = "CloseNotification",
            parameters = tuple
        )
        LinuxGlibGioBindings.variantUnref(reply)
    }

    override fun dispose() {
        val (bus, sub1, sub2, ref) = lock.withLock {
            if (disposedState) return
            disposedState = true
            channels.clear()
            activeNotifications.clear()
            dbusIdByLocalId.clear()
            localIdByDbusId.clear()
            actionCallbacks.clear()
            val b = sessionBus
            val s1 = actionSignalSubId
            val s2 = closedSignalSubId
            val r = selfRef
            sessionBus = null
            actionSignalSubId = 0u
            closedSignalSubId = 0u
            selfRef = null
            Tuple4(b, s1, s2, r)
        }
        if (bus != null) {
            LinuxGlibGioBindings.dbusSignalUnsubscribe(bus, sub1)
            LinuxGlibGioBindings.dbusSignalUnsubscribe(bus, sub2)
            LinuxGlibGioBindings.objectUnref(bus)
        }
        ref?.dispose()
    }

    private fun ensureSessionBus(): COpaquePointer? {
        lock.withLock {
            if (disposedState) return null
            sessionBus?.let { return it }
        }
        val bus = LinuxGlibGioBindings.busGetSessionSync() ?: return null
        val ref = StableRef.create(this)
        val actionCb = staticCFunction {
                _: COpaquePointer?,
                _: CPointer<ByteVar>?,
                _: CPointer<ByteVar>?,
                _: CPointer<ByteVar>?,
                _: CPointer<ByteVar>?,
                parameters: COpaquePointer?,
                userData: COpaquePointer? ->
            if (userData != null && parameters != null) {
                val mgr = userData.asStableRef<LinuxDbusNotificationManagerNativeImpl>().get()
                mgr.handleActionInvokedSignal(parameters)
            }
        }
        val closedCb = staticCFunction {
                _: COpaquePointer?,
                _: CPointer<ByteVar>?,
                _: CPointer<ByteVar>?,
                _: CPointer<ByteVar>?,
                _: CPointer<ByteVar>?,
                parameters: COpaquePointer?,
                userData: COpaquePointer? ->
            if (userData != null && parameters != null) {
                val mgr = userData.asStableRef<LinuxDbusNotificationManagerNativeImpl>().get()
                mgr.handleNotificationClosedSignal(parameters)
            }
        }

        val subAction = LinuxGlibGioBindings.dbusSignalSubscribe(
            connection = bus,
            sender = null,
            interfaceName = NOTIFICATIONS_INTERFACE,
            member = "ActionInvoked",
            objectPath = NOTIFICATIONS_OBJECT_PATH,
            callback = actionCb,
            userData = ref.asCPointer()
        )
        val subClosed = LinuxGlibGioBindings.dbusSignalSubscribe(
            connection = bus,
            sender = null,
            interfaceName = NOTIFICATIONS_INTERFACE,
            member = "NotificationClosed",
            objectPath = NOTIFICATIONS_OBJECT_PATH,
            callback = closedCb,
            userData = ref.asCPointer()
        )

        val stored = lock.withLock {
            if (disposedState || sessionBus != null) {
                false
            } else {
                sessionBus = bus
                selfRef = ref
                actionSignalSubId = subAction
                closedSignalSubId = subClosed
                true
            }
        }
        if (!stored) {
            LinuxGlibGioBindings.dbusSignalUnsubscribe(bus, subAction)
            LinuxGlibGioBindings.dbusSignalUnsubscribe(bus, subClosed)
            LinuxGlibGioBindings.objectUnref(bus)
            ref.dispose()
            return lock.withLock { sessionBus }
        }
        return bus
    }

    private fun handleActionInvokedSignal(parameters: COpaquePointer) {
        val idChild = LinuxGlibGioBindings.variantGetChildValue(parameters, 0)
        val keyChild = LinuxGlibGioBindings.variantGetChildValue(parameters, 1)
        val dbusId = if (idChild != null) LinuxGlibGioBindings.variantGetUint32(idChild) else 0u
        val actionKey = LinuxGlibGioBindings.variantGetString(keyChild) ?: ""
        LinuxGlibGioBindings.variantUnref(idChild)
        LinuxGlibGioBindings.variantUnref(keyChild)

        val cb = lock.withLock {
            val localId = localIdByDbusId[dbusId]
            if (localId != null) actionCallbacks[localId] else null
        }
        if (cb != null && actionKey.isNotEmpty()) {
            cb(actionKey)
        }
    }

    private fun handleNotificationClosedSignal(parameters: COpaquePointer) {
        val idChild = LinuxGlibGioBindings.variantGetChildValue(parameters, 0)
        val dbusId = if (idChild != null) LinuxGlibGioBindings.variantGetUint32(idChild) else 0u
        LinuxGlibGioBindings.variantUnref(idChild)

        lock.withLock {
            val localId = localIdByDbusId.remove(dbusId)
            if (localId != null) {
                dbusIdByLocalId.remove(localId)
                activeNotifications.remove(localId)
                actionCallbacks.remove(localId)
            }
        }
    }

    private fun buildNotifyParams(
        notification: Notification,
        replacesId: UInt,
        channel: NotificationChannel?
    ): COpaquePointer? {
        val strType = LinuxGlibGioBindings.variantTypeNew("s") ?: return null
        val dictEntryType = LinuxGlibGioBindings.variantTypeNew("{sv}") ?: run {
            LinuxGlibGioBindings.variantTypeFree(strType)
            return null
        }
        try {
            val actionVariants = ArrayList<COpaquePointer>(notification.actions.size * 2)
            for ((actionKey, actionLabel) in notification.actions) {
                LinuxGlibGioBindings.variantNewString(actionKey)?.let { actionVariants.add(it) }
                LinuxGlibGioBindings.variantNewString(actionLabel)?.let { actionVariants.add(it) }
            }
            val actionsArray = LinuxGlibGioBindings.variantNewArray(strType, actionVariants) ?: return null

            val hintsList = ArrayList<COpaquePointer>(1)
            val urgency: UByte = if (channel == null || channel.highImportance) 2u else 1u
            val urgKey = LinuxGlibGioBindings.variantNewString("urgency")
            val urgByte = LinuxGlibGioBindings.variantNewByte(urgency)
            val urgVal = LinuxGlibGioBindings.variantNewVariant(urgByte)
            val urgEntry = LinuxGlibGioBindings.variantNewDictEntry(urgKey, urgVal)
            if (urgEntry != null) hintsList.add(urgEntry)
            val hintsDict = LinuxGlibGioBindings.variantNewArray(dictEntryType, hintsList) ?: return null

            val children = listOfNotNull(
                LinuxGlibGioBindings.variantNewString(appName),
                LinuxGlibGioBindings.variantNewUint32(replacesId),
                LinuxGlibGioBindings.variantNewString(notification.iconPathOrUrl ?: ""),
                LinuxGlibGioBindings.variantNewString(notification.title),
                LinuxGlibGioBindings.variantNewString(notification.body),
                actionsArray,
                hintsDict,
                LinuxGlibGioBindings.variantNewInt32(-1)
            )
            if (children.size != 8) return null
            return LinuxGlibGioBindings.variantNewTuple(children)
        } finally {
            LinuxGlibGioBindings.variantTypeFree(strType)
            LinuxGlibGioBindings.variantTypeFree(dictEntryType)
        }
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private companion object {
        const val NOTIFICATIONS_BUS_NAME = "org.freedesktop.Notifications"
        const val NOTIFICATIONS_OBJECT_PATH = "/org/freedesktop/Notifications"
        const val NOTIFICATIONS_INTERFACE = "org.freedesktop.Notifications"
    }
}
