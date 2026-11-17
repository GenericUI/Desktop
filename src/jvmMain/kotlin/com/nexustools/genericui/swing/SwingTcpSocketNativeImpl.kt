package com.nexustools.genericui.swing

import com.nexustools.genericui.jvm.JvmTcpSocketNativeImpl
import javax.net.ssl.TrustManager
import javax.swing.SwingUtilities

/**
 * Java Swing implementation of [com.nexustools.genericui.nativeimpl.net.TcpSocketNativeImpl]
 * backed by [JvmTcpSocketNativeImpl] and [SwingPermissiveX509TrustManager].
 */
class SwingTcpSocketNativeImpl : JvmTcpSocketNativeImpl(
    isOnMainThread = { SwingUtilities.isEventDispatchThread() }
) {
    override val nativeApi: String = "Java_NIO_SocketChannel"

    override fun createTrustManager(): TrustManager = SwingPermissiveX509TrustManager()

    companion object {
        fun clearLoopback() {
            clearLoopbackServers()
        }
    }
}
