package com.nexustools.genericui.macos

import com.nexustools.genericui.nativeimpl.net.DefaultUdpSocketNativeImpl

/**
 * macOS Network.framework (`nw_parameters_create_secure_udp`) / BSD `sendto` & `recvfrom` implementation
 * of [com.nexustools.genericui.nativeimpl.net.UdpSocketNativeImpl].
 */
class MacosUdpSocketNativeImpl : DefaultUdpSocketNativeImpl() {
    val nativeApi: String = "NWConnection_UDP_BSD_recvfrom"

    companion object {
        fun clearLoopback() {
            clearLoopbackEndpoints()
        }
    }
}
