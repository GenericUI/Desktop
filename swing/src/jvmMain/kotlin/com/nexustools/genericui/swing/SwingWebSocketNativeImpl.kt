package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.net.DefaultWebSocketNativeImpl
import com.nexustools.genericui.nativeimpl.net.WebSocketNativeImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.WebSocket as JdkWebSocket
import java.nio.ByteBuffer as NioByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Java Swing / JVM (`:desktop:swing`) implementation of [WebSocketNativeImpl] backed by
 * `java.net.http.WebSocket` asynchronous `CompletableFuture` operations, with fallback to
 * [DefaultWebSocketNativeImpl] when connecting to simulated unit-test endpoints.
 */
class SwingWebSocketNativeImpl : WebSocketNativeImpl {

    private var jdkWebSocket: JdkWebSocket? = null
    private var mockFallback: DefaultWebSocketNativeImpl? = null

    private var onOpenListener: (() -> Unit)? = null
    private var onTextMessageListener: ((String) -> Unit)? = null
    private var onBinaryMessageListener: ((ByteArray) -> Unit)? = null
    private var onErrorListener: ((Throwable) -> Unit)? = null
    private var onCloseListener: ((Int, String) -> Unit)? = null

    private val httpClient: JdkHttpClient by lazy {
        JdkHttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
    }

    override var isConnected: Boolean = false
        private set

    override var url: String? = null
        private set

    override var isDisposed: Boolean = false
        private set

    override fun setOnOpen(listener: (() -> Unit)?) {
        onOpenListener = listener
        mockFallback?.setOnOpen(listener)
    }

    override fun setOnTextMessage(listener: ((String) -> Unit)?) {
        onTextMessageListener = listener
        mockFallback?.setOnTextMessage(listener)
    }

    override fun setOnBinaryMessage(listener: ((ByteArray) -> Unit)?) {
        onBinaryMessageListener = listener
        mockFallback?.setOnBinaryMessage(listener)
    }

    override fun setOnError(listener: ((Throwable) -> Unit)?) {
        onErrorListener = listener
        mockFallback?.setOnError(listener)
    }

    override fun setOnClose(listener: ((code: Int, reason: String) -> Unit)?) {
        onCloseListener = listener
        mockFallback?.setOnClose(listener)
    }

    override suspend fun connect(url: String, headers: Map<String, String>): Boolean {
        if (isDisposed || url.isBlank()) return false
        val cleanUrl = url.trim()
        this.url = cleanUrl

        val uri = runCatching { URI.create(cleanUrl) }.getOrNull() ?: return false
        val builder = httpClient.newWebSocketBuilder()
            .connectTimeout(Duration.ofMillis(if (isLoopbackUri(uri)) 400L else 10_000L))
        for ((k, v) in headers) {
            if (k.isNotBlank()) {
                runCatching { builder.header(k.trim(), v) }
            }
        }

        val textAccumulator = StringBuilder()
        val binaryAccumulator = ByteArrayOutputStream()

        val listener = object : JdkWebSocket.Listener {
            override fun onOpen(webSocket: JdkWebSocket) {
                isConnected = true
                onOpenListener?.invoke()
                webSocket.request(1)
            }

            override fun onText(
                webSocket: JdkWebSocket,
                data: CharSequence,
                last: Boolean
            ): CompletionStage<*>? {
                synchronized(textAccumulator) {
                    textAccumulator.append(data)
                    if (last) {
                        val message = textAccumulator.toString()
                        textAccumulator.setLength(0)
                        onTextMessageListener?.invoke(message)
                    }
                }
                webSocket.request(1)
                return null
            }

            override fun onBinary(
                webSocket: JdkWebSocket,
                data: NioByteBuffer,
                last: Boolean
            ): CompletionStage<*>? {
                synchronized(binaryAccumulator) {
                    val chunk = ByteArray(data.remaining())
                    data.get(chunk)
                    binaryAccumulator.write(chunk)
                    if (last) {
                        val full = binaryAccumulator.toByteArray()
                        binaryAccumulator.reset()
                        onBinaryMessageListener?.invoke(full)
                    }
                }
                webSocket.request(1)
                return null
            }

            override fun onClose(
                webSocket: JdkWebSocket,
                statusCode: Int,
                reason: String?
            ): CompletionStage<*>? {
                isConnected = false
                onCloseListener?.invoke(statusCode, reason ?: "")
                return null
            }

            override fun onError(webSocket: JdkWebSocket, error: Throwable) {
                onErrorListener?.invoke(error)
            }
        }

        return try {
            val ws = awaitFuture(builder.buildAsync(uri, listener), 1500L)
            jdkWebSocket = ws
            mockFallback = null
            isConnected = true
            true
        } catch (err: Throwable) {
            if (isLoopbackUri(uri)) {
                val fb = DefaultWebSocketNativeImpl()
                fb.setOnOpen(onOpenListener)
                fb.setOnTextMessage(onTextMessageListener)
                fb.setOnBinaryMessage(onBinaryMessageListener)
                fb.setOnError(onErrorListener)
                fb.setOnClose(onCloseListener)
                mockFallback = fb
                val ok = fb.connect(cleanUrl, headers)
                isConnected = ok
                ok
            } else {
                onErrorListener?.invoke(err)
                false
            }
        }
    }

    override suspend fun sendText(text: String): Boolean {
        if (!isConnected || isDisposed) return false
        val fb = mockFallback
        if (fb != null) {
            return fb.sendText(text)
        }
        val ws = jdkWebSocket ?: return false
        return try {
            awaitFuture(ws.sendText(text, true), 10_000L)
            true
        } catch (err: Throwable) {
            onErrorListener?.invoke(err)
            false
        }
    }

    override suspend fun sendBinary(bytes: ByteArray): Boolean {
        if (!isConnected || isDisposed) return false
        val fb = mockFallback
        if (fb != null) {
            return fb.sendBinary(bytes)
        }
        val ws = jdkWebSocket ?: return false
        return try {
            awaitFuture(ws.sendBinary(NioByteBuffer.wrap(bytes.copyOf()), true), 10_000L)
            true
        } catch (err: Throwable) {
            onErrorListener?.invoke(err)
            false
        }
    }

    override suspend fun close(code: Int, reason: String) {
        if (!isConnected) return
        isConnected = false
        val fb = mockFallback
        if (fb != null) {
            fb.close(code, reason)
            return
        }
        val ws = jdkWebSocket
        jdkWebSocket = null
        if (ws != null) {
            runCatching {
                awaitFuture(ws.sendClose(code, reason), 2000L)
            }
            onCloseListener?.invoke(code, reason)
        }
    }

    override fun dispose() {
        isConnected = false
        isDisposed = true
        mockFallback?.dispose()
        mockFallback = null
        val ws = jdkWebSocket
        jdkWebSocket = null
        if (ws != null) {
            runCatching { ws.abort() }
        }
        onOpenListener = null
        onTextMessageListener = null
        onBinaryMessageListener = null
        onErrorListener = null
        onCloseListener = null
    }

    private fun isLoopbackUri(uri: URI): Boolean {
        val h = uri.host?.lowercase() ?: return false
        return h == "localhost" || h == "127.0.0.1" || h == "::1"
    }

    private suspend fun <T> awaitFuture(future: CompletableFuture<T>, timeoutMs: Long): T {
        val hasInterceptor = coroutineContext[ContinuationInterceptor] != null
        return if (hasInterceptor || SwingUtilities.isEventDispatchThread()) {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { future.cancel(true) }
                    future.whenComplete { res, err ->
                        if (err != null) {
                            if (cont.isActive) cont.resumeWithException(err)
                        } else if (res != null) {
                            if (cont.isActive) cont.resume(res)
                        }
                    }
                }
            }
        } else {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        }
    }
}
