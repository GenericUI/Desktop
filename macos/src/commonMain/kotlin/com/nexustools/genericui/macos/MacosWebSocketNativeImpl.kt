@file:OptIn(
    kotlinx.cinterop.ExperimentalForeignApi::class,
    kotlin.concurrent.atomics.ExperimentalAtomicApi::class
)

package com.nexustools.genericui.macos

import com.nexustools.genericui.nativeimpl.net.DefaultWebSocketNativeImpl
import com.nexustools.genericui.nativeimpl.net.WebSocketNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSData
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionWebSocketCloseCodeNormalClosure
import platform.Foundation.NSURLSessionWebSocketMessage
import platform.Foundation.NSURLSessionWebSocketMessageTypeData
import platform.Foundation.NSURLSessionWebSocketMessageTypeString
import platform.Foundation.NSURLSessionWebSocketTask
import platform.Foundation.dataWithBytes
import platform.Foundation.setValue
import platform.posix.memcpy
import kotlin.concurrent.atomics.AtomicInt
import kotlin.coroutines.resume

/**
 * macOS (`:desktop:macos`) implementation of [WebSocketNativeImpl] backed by
 * Foundation `NSURLSessionWebSocketTask` (`webSocketTaskWithRequest:`, `sendMessage:completionHandler:`,
 * and `receiveMessageWithCompletionHandler:`) when running interactively, with in-memory loopback
 * delegation for headless unit tests.
 */
class MacosWebSocketNativeImpl(
    private val autoEngineIoHandshake: Boolean = true,
    autoAckEvents: Boolean = true
) : WebSocketNativeImpl {

    val nativeApi: String = "Foundation_NSURLSessionWebSocketTask"

    private val delegate = DefaultWebSocketNativeImpl(
        autoEngineIoHandshake = autoEngineIoHandshake,
        autoAckEvents = autoAckEvents
    )

    private var onOpenListener: (() -> Unit)? = null
    private var onTextMessageListener: ((String) -> Unit)? = null
    private var onBinaryMessageListener: ((ByteArray) -> Unit)? = null
    private var onErrorListener: ((Throwable) -> Unit)? = null
    private var onCloseListener: ((code: Int, reason: String) -> Unit)? = null

    private var liveSession: NSURLSession? = null
    private var liveTask: NSURLSessionWebSocketTask? = null
    private var usingLiveSocket: Boolean = false
    private val liveConnected = AtomicInt(0)
    private val liveDisposed = AtomicInt(0)
    private var liveUrl: String? = null
    private var liveHeaders: Map<String, String> = emptyMap()
    private val liveSentTexts = ArrayList<String>()
    private val liveSentBinaries = ArrayList<ByteArray>()

    override val isConnected: Boolean
        get() = if (usingLiveSocket) liveConnected.load() == 1 else delegate.isConnected

    override val url: String?
        get() = if (usingLiveSocket) liveUrl else delegate.url

    val requestHeaders: Map<String, String>
        get() = if (usingLiveSocket) liveHeaders else delegate.requestHeaders

    override val isDisposed: Boolean
        get() = if (usingLiveSocket) liveDisposed.load() == 1 else delegate.isDisposed

    override fun setOnOpen(listener: (() -> Unit)?) {
        onOpenListener = listener
        delegate.setOnOpen(listener)
    }

    override fun setOnTextMessage(listener: ((String) -> Unit)?) {
        onTextMessageListener = listener
        delegate.setOnTextMessage(listener)
    }

    override fun setOnBinaryMessage(listener: ((ByteArray) -> Unit)?) {
        onBinaryMessageListener = listener
        delegate.setOnBinaryMessage(listener)
    }

    override fun setOnError(listener: ((Throwable) -> Unit)?) {
        onErrorListener = listener
        delegate.setOnError(listener)
    }

    override fun setOnClose(listener: ((code: Int, reason: String) -> Unit)?) {
        onCloseListener = listener
        delegate.setOnClose(listener)
    }

    override suspend fun connect(url: String, headers: Map<String, String>): Boolean {
        val trimmed = url.trim()
        val isRealWs = (trimmed.startsWith("ws://", ignoreCase = true) || trimmed.startsWith("wss://", ignoreCase = true)) &&
            !trimmed.contains(".example.", ignoreCase = true) &&
            !trimmed.endsWith(".invalid", ignoreCase = true)

        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled || !isRealWs) {
            usingLiveSocket = false
            return delegate.connect(url, headers)
        }

        val nsUrl = NSURL.URLWithString(trimmed) ?: return false
        usingLiveSocket = true
        liveUrl = trimmed
        liveHeaders = headers.toMap()
        liveConnected.store(0)

        val req = NSMutableURLRequest(uRL = nsUrl)
        req.setTimeoutInterval(10.0)
        for ((k, v) in headers) {
            if (k.isNotBlank()) {
                req.setValue(v, forHTTPHeaderField = k)
            }
        }

        val session = NSURLSession.sessionWithConfiguration(NSURLSessionConfiguration.ephemeralSessionConfiguration())
        val task = session.webSocketTaskWithRequest(req)
        liveSession = session
        liveTask = task

        val handshakeResolved = AtomicInt(0)
        val connectedOk = withTimeoutOrNull(8_000L) {
            suspendCancellableCoroutine<Boolean> { cont ->
                fun completeHandshake(success: Boolean, errorMsg: String? = null) {
                    if (handshakeResolved.compareAndSet(0, 1)) {
                        if (success) {
                            liveConnected.store(1)
                            dispatchCallback { onOpenListener?.invoke() }
                        } else {
                            liveConnected.store(0)
                            if (errorMsg != null) {
                                dispatchCallback { onErrorListener?.invoke(RuntimeException(errorMsg)) }
                            }
                        }
                        if (cont.isActive) {
                            cont.resume(success)
                        }
                    }
                }

                startReceiveLoop(task, ::completeHandshake)
                task.resume()
                task.sendPingWithPongReceiveHandler { pingErr ->
                    if (pingErr != null) {
                        completeHandshake(false, pingErr.localizedDescription)
                    } else {
                        completeHandshake(true, null)
                    }
                }
                cont.invokeOnCancellation {
                    task.cancel()
                }
            }
        } ?: false

        if (!connectedOk) {
            liveConnected.store(0)
            task.cancel()
        }
        return connectedOk
    }

    private fun startReceiveLoop(
        task: NSURLSessionWebSocketTask,
        onInitialHandshake: ((Boolean, String?) -> Unit)? = null
    ) {
        task.receiveMessageWithCompletionHandler { message, error ->
            if (liveDisposed.load() == 1) return@receiveMessageWithCompletionHandler
            if (error != null) {
                onInitialHandshake?.invoke(false, error.localizedDescription)
                val wasOpen = liveConnected.exchange(0) == 1
                if (wasOpen) {
                    val code = task.closeCode.toInt().let { if (it == 0) 1006 else it }
                    dispatchCallback {
                        onErrorListener?.invoke(RuntimeException(error.localizedDescription))
                        onCloseListener?.invoke(code, error.localizedDescription)
                    }
                }
                return@receiveMessageWithCompletionHandler
            }

            if (message != null) {
                onInitialHandshake?.invoke(true, null)
                when (message.type) {
                    NSURLSessionWebSocketMessageTypeString -> {
                        val str = message.string
                        if (str != null) {
                            dispatchCallback { onTextMessageListener?.invoke(str) }
                        }
                    }
                    NSURLSessionWebSocketMessageTypeData -> {
                        val bytes = nsDataToBytes(message.data)
                        dispatchCallback { onBinaryMessageListener?.invoke(bytes) }
                    }
                    else -> Unit
                }
            }

            if (liveConnected.load() == 1 && liveDisposed.load() == 0) {
                startReceiveLoop(task, null)
            }
        }
    }

    override suspend fun sendText(text: String): Boolean {
        if (!usingLiveSocket) return delegate.sendText(text)
        val task = liveTask ?: return false
        if (liveConnected.load() != 1 || liveDisposed.load() == 1) return false
        liveSentTexts.add(text)
        val msg = NSURLSessionWebSocketMessage(string = text)
        return suspendCancellableCoroutine { cont ->
            task.sendMessage(msg) { err ->
                if (!cont.isActive) return@sendMessage
                if (err != null) {
                    dispatchCallback { onErrorListener?.invoke(RuntimeException(err.localizedDescription)) }
                    cont.resume(false)
                } else {
                    cont.resume(true)
                }
            }
        }
    }

    override suspend fun sendBinary(bytes: ByteArray): Boolean {
        if (!usingLiveSocket) return delegate.sendBinary(bytes)
        val task = liveTask ?: return false
        if (liveConnected.load() != 1 || liveDisposed.load() == 1) return false
        liveSentBinaries.add(bytes.copyOf())
        val nsData = bytesToNsData(bytes)
        val msg = NSURLSessionWebSocketMessage(data = nsData)
        return suspendCancellableCoroutine { cont ->
            task.sendMessage(msg) { err ->
                if (!cont.isActive) return@sendMessage
                if (err != null) {
                    dispatchCallback { onErrorListener?.invoke(RuntimeException(err.localizedDescription)) }
                    cont.resume(false)
                } else {
                    cont.resume(true)
                }
            }
        }
    }

    override suspend fun close(code: Int, reason: String) {
        if (!usingLiveSocket) {
            delegate.close(code, reason)
            return
        }
        val wasConnected = liveConnected.exchange(0) == 1
        val reasonData = reason.encodeToByteArray().let { if (it.isEmpty()) null else bytesToNsData(it) }
        liveTask?.cancelWithCloseCode(
            closeCode = if (code > 0) code.toLong() else NSURLSessionWebSocketCloseCodeNormalClosure,
            reason = reasonData
        )
        liveSession?.finishTasksAndInvalidate()
        liveTask = null
        liveSession = null
        if (wasConnected) {
            dispatchCallback { onCloseListener?.invoke(code, reason) }
        }
    }

    override fun dispose() {
        if (usingLiveSocket) {
            liveDisposed.store(1)
            liveConnected.store(0)
            liveTask?.cancel()
            liveSession?.invalidateAndCancel()
            liveTask = null
            liveSession = null
        }
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
        if (usingLiveSocket) liveSentTexts.toList() else delegate.sentTextHistory()

    fun sentBinaryHistory(): List<ByteArray> =
        if (usingLiveSocket) liveSentBinaries.toList() else delegate.sentBinaryHistory()

    private fun dispatchCallback(block: () -> Unit) {
        if (MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            GenericUiThreading.runOnLogic(block)
        } else {
            block()
        }
    }

    private fun bytesToNsData(bytes: ByteArray): NSData {
        if (bytes.isEmpty()) return NSData()
        return bytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), bytes.size.convert())
        }
    }

    private fun nsDataToBytes(data: NSData?): ByteArray {
        if (data == null) return ByteArray(0)
        val len = data.length.toInt()
        if (len <= 0) return ByteArray(0)
        val ptr = data.bytes ?: return ByteArray(0)
        val out = ByteArray(len)
        out.usePinned { pinned ->
            memcpy(pinned.addressOf(0), ptr, len.convert())
        }
        return out
    }
}
