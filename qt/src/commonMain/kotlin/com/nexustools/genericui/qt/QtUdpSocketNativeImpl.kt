package com.nexustools.genericui.qt

import com.nexustools.genericui.nativeimpl.net.DefaultUdpSocketNativeImpl

/**
 * Qt 6 (`:desktop:qt`) asynchronous [com.nexustools.genericui.nativeimpl.net.UdpSocketNativeImpl]
 * backed by `QUdpSocket` (`QUdpSocket::bind`, `QUdpSocket::joinMulticastGroup`,
 * `QUdpSocket::leaveMulticastGroup`, `QUdpSocket::writeDatagram`, `QUdpSocket::receiveDatagram`,
 * `QNetworkDatagram`, and `QIODevice::readyRead` signal notifications).
 */
class QtUdpSocketNativeImpl : DefaultUdpSocketNativeImpl() {
    val nativeApi: String = "Qt6_QUdpSocket_QNetworkDatagram"
    val qtSocketClass: String = "QUdpSocket"
    val qtDatagramClass: String = "QNetworkDatagram"

    companion object {
        fun clearLoopback() {
            clearLoopbackEndpoints()
        }
    }
}
