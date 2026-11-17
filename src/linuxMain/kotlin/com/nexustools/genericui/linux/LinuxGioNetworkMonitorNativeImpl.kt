@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.nativeimpl.system.NetworkMonitorNativeImpl
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction

class LinuxGioNetworkMonitorNativeImpl : NetworkMonitorNativeImpl {
    private val lock = LinuxReentrantLock()
    private var monitorPtr: COpaquePointer? = null
    private var signalHandlerId: ULong = 0uL
    private var selfRef: StableRef<LinuxGioNetworkMonitorNativeImpl>? = null
    private var listener: ((Boolean) -> Unit)? = null
    private var disposedState = false

    override val isDisposed: Boolean
        get() = lock.withLock { disposedState }

    init {
        val monitor = LinuxGlibGioBindings.networkMonitorGetDefault()
        if (monitor != null) {
            monitorPtr = monitor
            val ref = StableRef.create(this)
            selfRef = ref
            val callback = staticCFunction { _: COpaquePointer?, networkAvailable: Int, userData: COpaquePointer? ->
                if (userData != null) {
                    val instance = userData.asStableRef<LinuxGioNetworkMonitorNativeImpl>().get()
                    instance.notifyConnectivityChanged(networkAvailable != 0)
                }
            }
            signalHandlerId = LinuxGlibGioBindings.signalConnect(
                instance = monitor,
                signalName = "network-changed",
                handler = callback,
                userData = ref.asCPointer()
            )
        }
    }

    override fun isOnline(): Boolean {
        if (isDisposed) return false
        val monitor = lock.withLock { monitorPtr } ?: return true
        return LinuxGlibGioBindings.networkMonitorGetAvailable(monitor)
    }

    override fun setOnConnectivityChanged(listener: ((Boolean) -> Unit)?) {
        lock.withLock {
            this.listener = listener
        }
    }

    private fun notifyConnectivityChanged(online: Boolean) {
        val cb = lock.withLock {
            if (disposedState) null else listener
        }
        cb?.invoke(online)
    }

    override fun dispose() {
        val (mon, sigId, ref) = lock.withLock {
            if (disposedState) return
            disposedState = true
            listener = null
            val m = monitorPtr
            val s = signalHandlerId
            val r = selfRef
            monitorPtr = null
            signalHandlerId = 0uL
            selfRef = null
            Triple(m, s, r)
        }
        if (mon != null && sigId != 0uL) {
            LinuxGlibGioBindings.signalHandlerDisconnect(mon, sigId)
        }
        ref?.dispose()
    }
}
