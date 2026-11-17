package com.nexustools.genericui.desktop.system

import com.nexustools.genericui.nativeimpl.system.NetworkMonitorNativeImpl

/**
 * Shared desktop base implementation of [NetworkMonitorNativeImpl].
 *
 * Platform desktop backends must override OS reachability checks via [queryNativeOnlineState] or
 * [isOnline]; explicit [setSimulatedOnlineState] overrides are supported for deterministic tests.
 */
open class DesktopNetworkMonitorNativeImpl(
    val backendToolkit: String = "Desktop"
) : NetworkMonitorNativeImpl {

    override var isDisposed: Boolean = false
        protected set

    protected var onlineState: Boolean? = null
    protected var onConnectivityChangedListener: ((Boolean) -> Unit)? = null

    protected open fun queryNativeOnlineState(): Boolean {
        throw UnsupportedOperationException("$backendToolkit network monitoring requires a platform OS reachability implementation.")
    }

    override fun isOnline(): Boolean {
        check(!isDisposed) { "$backendToolkit network monitor has been disposed." }
        return onlineState ?: queryNativeOnlineState()
    }

    override fun setOnConnectivityChanged(listener: ((Boolean) -> Unit)?) {
        onConnectivityChangedListener = listener
    }

    fun setSimulatedOnlineState(online: Boolean?) {
        val previous = onlineState
        onlineState = online
        if (online != null && previous != online) {
            onConnectivityChangedListener?.invoke(online)
        }
    }

    override fun dispose() {
        isDisposed = true
        onlineState = null
        onConnectivityChangedListener = null
    }
}
