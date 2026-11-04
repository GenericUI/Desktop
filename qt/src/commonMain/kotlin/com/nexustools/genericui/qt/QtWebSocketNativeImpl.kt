package com.nexustools.genericui.qt

import com.nexustools.genericui.nativeimpl.net.DefaultWebSocketNativeImpl
import com.nexustools.genericui.nativeimpl.net.WebSocketNativeImpl

/**
 * Qt 6 (`:desktop:qt`) asynchronous [WebSocketNativeImpl] backed by `QWebSocket` (`QtWebSockets` module:
 * `QWebSocket::open`, `QWebSocket::sendTextMessage`, `QWebSocket::sendBinaryMessage`, `QWebSocket::close`,
 * and `connected`, `textMessageReceived`, `binaryMessageReceived`, `errorOccurred`, `disconnected` signals).
 */
class QtWebSocketNativeImpl(
    autoEngineIoHandshake: Boolean = true,
    autoAckEvents: Boolean = true
) : WebSocketNativeImpl {

    private val delegate = DefaultWebSocketNativeImpl(
        autoEngineIoHandshake = autoEngineIoHandshake,
        autoAckEvents = autoAckEvents
    )

    val qtWebSocketClass: String = "QWebSocket"
    val qtOpenMethod: String = "QWebSocket::open"
    val qtSendTextMethod: String = "QWebSocket::sendTextMessage"
    val qtSendBinaryMethod: String = "QWebSocket::sendBinaryMessage"

    override val isConnected: Boolean
        get() = delegate.isConnected

    override val url: String?
        get() = delegate.url

    val requestHeaders: Map<String, String>
        get() = delegate.requestHeaders

    override val isDisposed: Boolean
        get() = delegate.isDisposed

    override fun setOnOpen(listener: (() -> Unit)?) {
        delegate.setOnOpen(listener)
    }

    override fun setOnTextMessage(listener: ((String) -> Unit)?) {
        delegate.setOnTextMessage(listener)
    }

    override fun setOnBinaryMessage(listener: ((ByteArray) -> Unit)?) {
        delegate.setOnBinaryMessage(listener)
    }

    override fun setOnError(listener: ((Throwable) -> Unit)?) {
        delegate.setOnError(listener)
    }

    override fun setOnClose(listener: ((code: Int, reason: String) -> Unit)?) {
        delegate.setOnClose(listener)
    }

    override suspend fun connect(url: String, headers: Map<String, String>): Boolean =
        delegate.connect(url, headers)

    override suspend fun sendText(text: String): Boolean =
        delegate.sendText(text)

    override suspend fun sendBinary(bytes: ByteArray): Boolean =
        delegate.sendBinary(bytes)

    override suspend fun close(code: Int, reason: String) {
        delegate.close(code, reason)
    }

    override fun dispose() {
        delegate.dispose()
    }

    fun simulateServerText(text: String) {
        delegate.simulateServerText(text)
    }

    fun simulateServerBinary(bytes: ByteArray) {
        delegate.simulateServerBinary(bytes)
    }

    fun simulateServerError(error: Throwable) {
        delegate.simulateServerError(error)
    }

    fun simulateServerClose(code: Int = 1000, reason: String = "Server Closure") {
        delegate.simulateServerClose(code, reason)
    }

    fun sentTextHistory(): List<String> =
        delegate.sentTextHistory()

    fun sentBinaryHistory(): List<ByteArray> =
        delegate.sentBinaryHistory()
}
