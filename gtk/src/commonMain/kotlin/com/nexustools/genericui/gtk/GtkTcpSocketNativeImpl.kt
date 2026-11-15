package com.nexustools.genericui.gtk

import com.nexustools.genericui.linux.LinuxPosixTcpSocketNativeImpl

/**
 * Linux GTK4 / GIO (`:desktop:gtk`) asynchronous [com.nexustools.genericui.nativeimpl.net.TcpSocketNativeImpl]
 * backed by real POSIX TCP sockets (`AF_INET`, `SOCK_STREAM`, non-blocking `connect`/`poll`,
 * `send`, `recv`) and GIO `GSocketClient` / `GSocketConnection`.
 */
class GtkTcpSocketNativeImpl : LinuxPosixTcpSocketNativeImpl() {
    val nativeApi: String = "GIO_GSocketClient_GSocketConnection"
    val gioSocketClientClass: String = "GSocketClient"
    val gioConnectionClass: String = "GSocketConnection"

    companion object {
        fun clearLoopback() {
            clearLoopbackServers()
        }
    }
}
