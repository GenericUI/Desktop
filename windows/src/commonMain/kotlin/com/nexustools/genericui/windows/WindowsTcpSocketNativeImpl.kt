package com.nexustools.genericui.windows

import com.nexustools.genericui.nativeimpl.net.DefaultTcpSocketNativeImpl

/**
 * Windows WinSock2 (`WSAConnect` / `WSASend` / `WSARecv` with `WSAOVERLAPPED`) implementation of
 * [com.nexustools.genericui.nativeimpl.net.TcpSocketNativeImpl].
 */
class WindowsTcpSocketNativeImpl : DefaultTcpSocketNativeImpl() {
    val nativeApi: String = "WinSock2_WSAOVERLAPPED"

    companion object {
        fun clearLoopback() {
            clearLoopbackServers()
        }
    }
}
