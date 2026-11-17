package com.nexustools.genericui.swing

import com.nexustools.genericui.jvm.JvmUdpSocketNativeImpl
import javax.swing.SwingUtilities

/**
 * Java Swing implementation of [com.nexustools.genericui.nativeimpl.net.UdpSocketNativeImpl]
 * backed by [JvmUdpSocketNativeImpl].
 */
class SwingUdpSocketNativeImpl : JvmUdpSocketNativeImpl(
    isOnMainThread = { SwingUtilities.isEventDispatchThread() }
) {
    override val nativeApi: String = "Java_NIO_DatagramChannel_MulticastSocket"

    companion object {
        fun clearLoopback() {
            clearLoopbackEndpoints()
        }
    }
}
