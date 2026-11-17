@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.system.DesktopNetworkMonitorNativeImpl
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.windows.GetModuleHandleW
import platform.windows.GetProcAddress
import platform.windows.HANDLE
import platform.windows.HANDLEVar
import platform.windows.LoadLibraryW

/**
 * Windows network connectivity monitor backed by `iphlpapi.dll` `GetAdaptersAddresses`
 * and `NotifyIpInterfaceChange` / `CancelMibChangeNotify2`.
 */
class WindowsNetworkMonitorNativeImpl : DesktopNetworkMonitorNativeImpl(
    backendToolkit = "Windows iphlpapi GetAdaptersAddresses / NotifyIpInterfaceChange"
) {
    private val lock = WindowsSpinLock()
    private var simulatedOverride: Boolean? = null
    private var notificationHandle: HANDLE? = null
    private var callbackContextRef: StableRef<WindowsNetworkMonitorNativeImpl>? = null

    init {
        onlineState = queryLiveConnectivity() ?: true
    }

    override fun isOnline(): Boolean {
        if (isDisposed) return false
        lock.withLock { simulatedOverride }?.let { return it }
        val live = queryLiveConnectivity()
        if (live != null) {
            lock.withLock { onlineState = live }
            return live
        }
        return lock.withLock { onlineState } ?: true
    }

    override fun setOnConnectivityChanged(listener: ((Boolean) -> Unit)?) {
        super.setOnConnectivityChanged(listener)
        if (isDisposed) return
        if (listener != null) {
            ensureIpChangeNotificationRegistered()
        } else {
            unregisterIpChangeNotification()
        }
    }

    internal fun handleNativeInterfaceChange() {
        if (isDisposed) return
        val live = queryLiveConnectivity() ?: return
        val listenerToNotify = lock.withLock {
            if (simulatedOverride != null || onlineState == live) {
                null
            } else {
                onlineState = live
                onConnectivityChangedListener
            }
        }
        listenerToNotify?.invoke(live)
    }

    override fun dispose() {
        unregisterIpChangeNotification()
        super.dispose()
    }

    private fun queryLiveConnectivity(): Boolean? {
        val fn = fnGetAdaptersAddresses ?: return null
        return memScoped {
            val sizeVar = alloc<UIntVar>()
            sizeVar.value = 16_384u
            var buf = ByteArray(sizeVar.value.toInt())
            var rc = buf.usePinned { pinned ->
                fn(AF_UNSPEC_U, GAA_QUERY_FLAGS, null, pinned.addressOf(0), sizeVar.ptr)
            }
            if (rc == ERROR_BUFFER_OVERFLOW_U) {
                val needed = sizeVar.value.toInt().coerceIn(1024, 4 * 1024 * 1024)
                buf = ByteArray(needed)
                rc = buf.usePinned { pinned ->
                    fn(AF_UNSPEC_U, GAA_QUERY_FLAGS, null, pinned.addressOf(0), sizeVar.ptr)
                }
            }
            if (rc == ERROR_NO_DATA_U) {
                return@memScoped false
            }
            if (rc != NO_ERROR_U) {
                return@memScoped null
            }

            var hasActiveNonLoopback = false
            buf.usePinned { pinned ->
                var cur: CPointer<ByteVar>? = pinned.addressOf(0)
                var guard = 0
                while (cur != null && guard < 1024) {
                    guard++
                    val ifType = (cur + OFFSET_IF_TYPE)!!.reinterpret<UIntVar>().pointed.value.toInt()
                    val operStatus = (cur + OFFSET_OPER_STATUS)!!.reinterpret<UIntVar>().pointed.value.toInt()
                    if (ifType != IF_TYPE_SOFTWARE_LOOPBACK && operStatus == IF_OPER_STATUS_UP) {
                        hasActiveNonLoopback = true
                        break
                    }
                    cur = (cur + OFFSET_NEXT)!!.reinterpret<COpaquePointerVar>().pointed.value?.reinterpret()
                }
            }
            hasActiveNonLoopback
        }
    }

    private fun ensureIpChangeNotificationRegistered() {
        val fnNotify = fnNotifyIpInterfaceChange ?: return
        lock.withLock {
            if (notificationHandle != null || isDisposed) return
            val ref = StableRef.create(this)
            val ok = memScoped {
                val hVar = alloc<HANDLEVar>()
                val status = fnNotify(
                    AF_UNSPEC_U.toUShort(),
                    staticCFunction(::win32IpInterfaceChangedTrampoline),
                    ref.asCPointer(),
                    0.toUByte(),
                    hVar.ptr
                )
                if (status == 0) {
                    notificationHandle = hVar.value
                    callbackContextRef = ref
                    true
                } else {
                    false
                }
            }
            if (!ok) {
                ref.dispose()
            }
        }
    }

    private fun unregisterIpChangeNotification() {
        val (handle, ref) = lock.withLock {
            val h = notificationHandle
            val r = callbackContextRef
            notificationHandle = null
            callbackContextRef = null
            h to r
        }
        if (handle != null) {
            runCatching { fnCancelMibChangeNotify2?.invoke(handle) }
        }
        ref?.dispose()
    }

    private companion object {
        const val AF_UNSPEC_U = 0u
        const val GAA_QUERY_FLAGS = 0x002Fu
        const val NO_ERROR_U = 0u
        const val ERROR_BUFFER_OVERFLOW_U = 111u
        const val ERROR_NO_DATA_U = 232u

        // Verified against x86_64 Windows IP_ADAPTER_ADDRESSES_LH layout in iptypes.h
        const val OFFSET_NEXT = 8
        const val OFFSET_IF_TYPE = 100
        const val OFFSET_OPER_STATUS = 104
        const val IF_TYPE_SOFTWARE_LOOPBACK = 24
        const val IF_OPER_STATUS_UP = 1

        private val iphlpapiModule by lazy {
            GetModuleHandleW("iphlpapi.dll") ?: LoadLibraryW("iphlpapi.dll")
        }

        private val fnGetAdaptersAddresses: CPointer<
            CFunction<(UInt, UInt, COpaquePointer?, COpaquePointer?, CPointer<UIntVar>) -> UInt>
        >? by lazy {
            iphlpapiModule?.let { mod ->
                GetProcAddress(mod, "GetAdaptersAddresses")?.reinterpret()
            }
        }

        private val fnNotifyIpInterfaceChange: CPointer<
            CFunction<(UShort, COpaquePointer?, COpaquePointer?, UByte, CPointer<HANDLEVar>) -> Int>
        >? by lazy {
            iphlpapiModule?.let { mod ->
                GetProcAddress(mod, "NotifyIpInterfaceChange")?.reinterpret()
            }
        }

        private val fnCancelMibChangeNotify2: CPointer<
            CFunction<(HANDLE?) -> Int>
        >? by lazy {
            iphlpapiModule?.let { mod ->
                GetProcAddress(mod, "CancelMibChangeNotify2")?.reinterpret()
            }
        }
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
private fun win32IpInterfaceChangedTrampoline(
    callerContext: COpaquePointer?,
    row: COpaquePointer?,
    notificationType: Int
) {
    if (callerContext == null) return
    val monitor = runCatching {
        callerContext.asStableRef<WindowsNetworkMonitorNativeImpl>().get()
    }.getOrNull() ?: return
    monitor.handleNativeInterfaceChange()
}
