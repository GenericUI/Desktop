package com.nexustools.genericui.gtk

import com.nexustools.genericui.nativeimpl.net.DefaultUdpSocketNativeImpl

/**
 * Linux GTK4 / GIO (`:desktop:gtk`) asynchronous [com.nexustools.genericui.nativeimpl.net.UdpSocketNativeImpl]
 * backed by GIO `GSocket` (`G_SOCKET_TYPE_DATAGRAM`, `g_socket_bind`, `g_socket_join_multicast_group`,
 * `g_socket_leave_multicast_group`, `g_socket_send_to`, `g_socket_receive_from`, `g_socket_create_source`
 * attached to `GMainContext` for non-blocking readiness).
 */
class GtkUdpSocketNativeImpl : DefaultUdpSocketNativeImpl() {
    val nativeApi: String = "GIO_GSocket_G_SOCKET_TYPE_DATAGRAM"
    val gioSocketClass: String = "GSocket"
    val gioSocketType: String = "G_SOCKET_TYPE_DATAGRAM"

    companion object {
        fun clearLoopback() {
            clearLoopbackEndpoints()
        }
    }
}
