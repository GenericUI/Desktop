package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.net.DefaultTcpSocketNativeImpl

/**
 * Java Swing / JVM (`:desktop:swing`) implementation of [com.nexustools.genericui.nativeimpl.net.TcpSocketNativeImpl]
 * backed by `java.nio.channels.SocketChannel` / `javax.net.ssl.SSLSocket` on `Dispatchers.IO`, with
 * automatic loopback support via [DefaultTcpSocketNativeImpl].
 */
class SwingTcpSocketNativeImpl : DefaultTcpSocketNativeImpl() {
    val nativeApi: String = "Java_NIO_SocketChannel"

    companion object {
        fun clearLoopback() {
            clearLoopbackServers()
        }
    }
}
