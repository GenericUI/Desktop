package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.net.DefaultUdpSocketNativeImpl

/**
 * Java Swing / JVM (`:desktop:swing`) implementation of [com.nexustools.genericui.nativeimpl.net.UdpSocketNativeImpl]
 * backed by `java.nio.channels.DatagramChannel` and `java.net.MulticastSocket` on `Dispatchers.IO`, with
 * automatic loopback support via [DefaultUdpSocketNativeImpl].
 */
class SwingUdpSocketNativeImpl : DefaultUdpSocketNativeImpl() {
    val nativeApi: String = "Java_NIO_DatagramChannel_MulticastSocket"

    companion object {
        fun clearLoopback() {
            clearLoopbackEndpoints()
        }
    }
}
