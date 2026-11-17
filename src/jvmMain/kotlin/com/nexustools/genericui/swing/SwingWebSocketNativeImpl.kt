package com.nexustools.genericui.swing

import com.nexustools.genericui.jvm.JvmRfc6455WebSocketNativeImpl
import javax.swing.SwingUtilities

/**
 * Java Swing implementation of [com.nexustools.genericui.nativeimpl.net.WebSocketNativeImpl]
 * backed by [JvmRfc6455WebSocketNativeImpl].
 */
class SwingWebSocketNativeImpl : JvmRfc6455WebSocketNativeImpl(
    isOnMainThread = { SwingUtilities.isEventDispatchThread() }
)
