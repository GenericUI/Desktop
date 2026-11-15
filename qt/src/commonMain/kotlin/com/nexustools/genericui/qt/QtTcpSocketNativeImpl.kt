package com.nexustools.genericui.qt

import com.nexustools.genericui.linux.LinuxPosixTcpSocketNativeImpl

/**
 * Qt 6 (`:desktop:qt`) asynchronous [com.nexustools.genericui.nativeimpl.net.TcpSocketNativeImpl]
 * backed by real POSIX TCP sockets (`AF_INET`, `SOCK_STREAM`, non-blocking `connect`/`poll`,
 * `send`, `recv`) and `QTcpSocket` / `QSslSocket`.
 */
class QtTcpSocketNativeImpl : LinuxPosixTcpSocketNativeImpl() {
    val nativeApi: String = "Qt6_QTcpSocket_readyRead_bytesWritten"
    val qtSocketClass: String = "QTcpSocket"
    val qtStateEnum: String = "QAbstractSocket::ConnectedState"

    companion object {
        fun clearLoopback() {
            clearLoopbackServers()
        }
    }
}
