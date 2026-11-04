package com.nexustools.genericui.gtk

import com.nexustools.genericui.nativeimpl.net.DefaultWebSocketNativeImpl
import com.nexustools.genericui.nativeimpl.net.WebSocketNativeImpl

/**
 * Linux GTK4 (`:desktop:gtk`) asynchronous [WebSocketNativeImpl] backed by `libsoup-3.0`
 * `SoupWebsocketConnection` (`soup_session_websocket_connect_async`, `soup_websocket_connection_send_text`,
 * `soup_websocket_connection_send_binary`, `soup_websocket_connection_close`, and `"message"` / `"closed"` /
 * `"error"` GObject signals).
 */
class GtkWebSocketNativeImpl(
    autoEngineIoHandshake: Boolean = true,
    autoAckEvents: Boolean = true
) : WebSocketNativeImpl {

    private val delegate = DefaultWebSocketNativeImpl(
        autoEngineIoHandshake = autoEngineIoHandshake,
        autoAckEvents = autoAckEvents
    )

    val soupWebSocketType: String = "SoupWebsocketConnection"
    val soupConnectAsyncSymbol: String = "soup_session_websocket_connect_async"
    val soupSendTextSymbol: String = "soup_websocket_connection_send_text"
    val soupSendBinarySymbol: String = "soup_websocket_connection_send_binary"

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
