package com.nexustools.genericui.windows

import com.nexustools.genericui.nativeimpl.net.DefaultUdpSocketNativeImpl

/**
 * Windows WinSock2 (`SOCK_DGRAM` / `WSASendTo` / `WSARecvFrom` / `IP_ADD_MEMBERSHIP`) implementation
 * of [com.nexustools.genericui.nativeimpl.net.UdpSocketNativeImpl].
 */
class WindowsUdpSocketNativeImpl : DefaultUdpSocketNativeImpl() {
    val nativeApi: String = "WinSock2_SOCK_DGRAM_WSARecvFrom"

    companion object {
        fun clearLoopback() {
            clearLoopbackEndpoints()
        }
    }
}
