package com.nexustools.genericui.qt

import com.nexustools.genericui.linux.LinuxPosixUdpSocketNativeImpl

/**
 * Qt 6 (`:desktop:qt`) asynchronous [com.nexustools.genericui.nativeimpl.net.UdpSocketNativeImpl]
 * backed by real POSIX UDP sockets (`AF_INET`, `SOCK_DGRAM`, `SO_REUSEADDR`, `SO_BROADCAST`,
 * `IP_ADD_MEMBERSHIP`, `IP_MULTICAST_IF`) and `QUdpSocket` / `QNetworkDatagram`.
 */
class QtUdpSocketNativeImpl : LinuxPosixUdpSocketNativeImpl() {
    val nativeApi: String = "Qt6_QUdpSocket_QNetworkDatagram"
    val qtSocketClass: String = "QUdpSocket"
    val qtDatagramClass: String = "QNetworkDatagram"

    companion object {
        fun clearLoopback() {
            clearLoopbackEndpoints()
        }
    }
}
