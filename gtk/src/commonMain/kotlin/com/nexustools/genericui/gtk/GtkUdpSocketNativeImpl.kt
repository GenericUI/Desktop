package com.nexustools.genericui.gtk

import com.nexustools.genericui.linux.LinuxPosixUdpSocketNativeImpl

/**
 * Linux GTK4 / GIO (`:desktop:gtk`) asynchronous [com.nexustools.genericui.nativeimpl.net.UdpSocketNativeImpl]
 * backed by real POSIX UDP sockets (`AF_INET`, `SOCK_DGRAM`, `SO_REUSEADDR`, `SO_BROADCAST`,
 * `IP_ADD_MEMBERSHIP`, `IP_MULTICAST_IF`) and GIO `GSocket` (`G_SOCKET_TYPE_DATAGRAM`,
 * `g_socket_bind`, `g_socket_join_multicast_group`, `g_socket_leave_multicast_group`,
 * `g_socket_send_to`, `g_socket_receive_from`).
 */
class GtkUdpSocketNativeImpl : LinuxPosixUdpSocketNativeImpl() {
    val nativeApi: String = "GIO_GSocket_G_SOCKET_TYPE_DATAGRAM"
    val gioSocketClass: String = "GSocket"
    val gioSocketType: String = "G_SOCKET_TYPE_DATAGRAM"

    companion object {
        fun clearLoopback() {
            clearLoopbackEndpoints()
        }
    }
}
