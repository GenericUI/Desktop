package com.nexustools.genericui.desktop.system

import com.nexustools.genericui.nativeimpl.system.NetworkMonitorNativeImpl

/**
 * Shared desktop base implementation of [NetworkMonitorNativeImpl] for Windows `INetworkListManager`,
 * macOS `NWPathMonitor`, Linux `GNetworkMonitor`, and Qt `QNetworkInformation`.
 */
open class DesktopNetworkMonitorNativeImpl(
    val backendToolkit: String = "Desktop"
) : NetworkMonitorNativeImpl {

    override var isDisposed: Boolean = false
        protected set

    protected var onlineState: Boolean = true
    protected var onConnectivityChangedListener: ((Boolean) -> Unit)? = null

    override fun isOnline(): Boolean = onlineState

    override fun setOnConnectivityChanged(listener: ((Boolean) -> Unit)?) {
        onConnectivityChangedListener = listener
    }

    fun setSimulatedOnlineState(online: Boolean) {
        if (onlineState != online) {
            onlineState = online
            onConnectivityChangedListener?.invoke(online)
        }
    }

    override fun dispose() {
        isDisposed = true
    }
}
