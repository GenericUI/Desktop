package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.system.NetworkMonitorNativeImpl
import java.net.NetworkInterface

/**
 * Java `java.net.NetworkInterface` implementation of [NetworkMonitorNativeImpl].
 */
class SwingNetworkMonitorImpl : NetworkMonitorNativeImpl {
    private var connectivityListener: ((Boolean) -> Unit)? = null

    override fun isOnline(): Boolean = try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        interfaces != null && interfaces.asSequence().any { it.isUp && !it.isLoopback }
    } catch (_: Throwable) {
        true
    }

    override fun setOnConnectivityChanged(listener: ((Boolean) -> Unit)?) {
        connectivityListener = listener
    }

    override fun dispose() {}
}
