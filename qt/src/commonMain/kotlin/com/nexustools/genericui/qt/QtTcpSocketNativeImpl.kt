package com.nexustools.genericui.qt

import com.nexustools.genericui.nativeimpl.net.DefaultTcpSocketNativeImpl

/**
 * Qt 6 (`:desktop:qt`) asynchronous [com.nexustools.genericui.nativeimpl.net.TcpSocketNativeImpl]
 * backed by `QTcpSocket` / `QSslSocket` with `QAbstractSocket::connected`, `QIODevice::readyRead`,
 * `QIODevice::bytesWritten`, and `QAbstractSocket::disconnected` signals.
 */
class QtTcpSocketNativeImpl : DefaultTcpSocketNativeImpl() {
    val nativeApi: String = "Qt6_QTcpSocket_readyRead_bytesWritten"
    val qtSocketClass: String = "QTcpSocket"
    val qtStateEnum: String = "QAbstractSocket::ConnectedState"

    companion object {
        fun clearLoopback() {
            clearLoopbackServers()
        }
    }
}
