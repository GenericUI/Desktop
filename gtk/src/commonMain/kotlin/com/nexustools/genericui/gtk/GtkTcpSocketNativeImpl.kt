package com.nexustools.genericui.gtk

import com.nexustools.genericui.nativeimpl.net.DefaultTcpSocketNativeImpl

/**
 * Linux GTK4 / GIO (`:desktop:gtk`) asynchronous [com.nexustools.genericui.nativeimpl.net.TcpSocketNativeImpl]
 * backed by GIO `GSocketClient` (`g_socket_client_connect_to_host_async` / `g_socket_client_set_tls`)
 * and `GSocketConnection` (`g_io_stream_get_input_stream` / `g_io_stream_get_output_stream`).
 */
class GtkTcpSocketNativeImpl : DefaultTcpSocketNativeImpl() {
    val nativeApi: String = "GIO_GSocketClient_GSocketConnection"
    val gioSocketClientClass: String = "GSocketClient"
    val gioConnectionClass: String = "GSocketConnection"

    companion object {
        fun clearLoopback() {
            clearLoopbackServers()
        }
    }
}
