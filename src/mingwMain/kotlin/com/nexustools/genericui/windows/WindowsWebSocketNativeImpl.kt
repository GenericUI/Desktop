@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.nativeimpl.net.WebSocketNativeImpl
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.windows.GetLastError
import platform.winhttp.HINTERNET
import platform.winhttp.WINHTTP_ACCESS_TYPE_DEFAULT_PROXY
import platform.winhttp.WINHTTP_ADDREQ_FLAG_ADD
import platform.winhttp.WINHTTP_ADDREQ_FLAG_REPLACE
import platform.winhttp.WINHTTP_FLAG_SECURE
import platform.winhttp.WINHTTP_OPTION_UPGRADE_TO_WEB_SOCKET
import platform.winhttp.WINHTTP_WEB_SOCKET_BINARY_FRAGMENT_BUFFER_TYPE
import platform.winhttp.WINHTTP_WEB_SOCKET_BINARY_MESSAGE_BUFFER_TYPE
import platform.winhttp.WINHTTP_WEB_SOCKET_CLOSE_BUFFER_TYPE
import platform.winhttp.WINHTTP_WEB_SOCKET_UTF8_FRAGMENT_BUFFER_TYPE
import platform.winhttp.WINHTTP_WEB_SOCKET_UTF8_MESSAGE_BUFFER_TYPE
import platform.winhttp.WinHttpAddRequestHeaders
import platform.winhttp.WinHttpCloseHandle
import platform.winhttp.WinHttpConnect
import platform.winhttp.WinHttpOpen
import platform.winhttp.WinHttpOpenRequest
import platform.winhttp.WinHttpReceiveResponse
import platform.winhttp.WinHttpSendRequest
import platform.winhttp.WinHttpSetOption
import platform.winhttp.WinHttpWebSocketClose
import platform.winhttp.WinHttpWebSocketCompleteUpgrade
import platform.winhttp.WinHttpWebSocketQueryCloseStatus
import platform.winhttp.WinHttpWebSocketReceive
import platform.winhttp.WinHttpWebSocketSend

/**
 * Windows WinHTTP (`WinHttpOpen` / `WINHTTP_OPTION_UPGRADE_TO_WEB_SOCKET` /
 * `WinHttpWebSocketCompleteUpgrade` / `WinHttpWebSocketSend` / `WinHttpWebSocketReceive` /
 * `WinHttpWebSocketClose`) implementation of [WebSocketNativeImpl].
 */
class WindowsWebSocketNativeImpl : WebSocketNativeImpl {

    val nativeApi: String = "WinHTTP_WinHttpWebSocketCompleteUpgrade_WinHttpWebSocketSend_WinHttpWebSocketReceive"

    private val lock = WindowsSpinLock()
    private val wsScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var hSession: HINTERNET? = null
    private var hConnect: HINTERNET? = null
    private var hWebSocket: HINTERNET? = null
    private var receiveJob: Job? = null

    private var connectedState: Boolean = false
    private var disposedState: Boolean = false
    private var currentUrl: String? = null
    private var closeNotified: Boolean = false

    private val requestHeadersMap = LinkedHashMap<String, String>()
    val requestHeaders: Map<String, String>
        get() = lock.withLock { requestHeadersMap.toMap() }

    private var onOpenListener: (() -> Unit)? = null
    private var onTextMessageListener: ((String) -> Unit)? = null
    private var onBinaryMessageListener: ((ByteArray) -> Unit)? = null
    private var onErrorListener: ((Throwable) -> Unit)? = null
    private var onCloseListener: ((code: Int, reason: String) -> Unit)? = null

    override val isConnected: Boolean
        get() = lock.withLock { connectedState && !disposedState }

    override val url: String?
        get() = lock.withLock { currentUrl }

    override val isDisposed: Boolean
        get() = lock.withLock { disposedState }

    override fun setOnOpen(listener: (() -> Unit)?) {
        lock.withLock { onOpenListener = listener }
    }

    override fun setOnTextMessage(listener: ((String) -> Unit)?) {
        lock.withLock { onTextMessageListener = listener }
    }

    override fun setOnBinaryMessage(listener: ((ByteArray) -> Unit)?) {
        lock.withLock { onBinaryMessageListener = listener }
    }

    override fun setOnError(listener: ((Throwable) -> Unit)?) {
        lock.withLock { onErrorListener = listener }
    }

    override fun setOnClose(listener: ((code: Int, reason: String) -> Unit)?) {
        lock.withLock { onCloseListener = listener }
    }

    override suspend fun connect(url: String, headers: Map<String, String>): Boolean {
        if (isDisposed) return false
        val parsed = parseWebSocketUrl(url)
        if (parsed == null) {
            emitError(IllegalArgumentException("Invalid WebSocket URL: '$url'"))
            return false
        }

        closeHandlesOnly()
        lock.withLock {
            currentUrl = url
            connectedState = false
            closeNotified = false
            requestHeadersMap.clear()
            requestHeadersMap.putAll(headers)
        }

        val upgradeResult = WindowsIoHelper.runIo {
            connectWinHttpWebSocketSync(parsed, headers)
        }
        val upgradedSocket = upgradeResult.webSocket
        if (upgradedSocket == null) {
            emitError(IllegalStateException(upgradeResult.errorMessage ?: "WinHTTP WebSocket connection failed"))
            return false
        }

        lock.withLock {
            if (disposedState) {
                WinHttpCloseHandle(upgradedSocket)
                upgradeResult.connect?.let { WinHttpCloseHandle(it) }
                upgradeResult.session?.let { WinHttpCloseHandle(it) }
                return false
            }
            hSession = upgradeResult.session
            hConnect = upgradeResult.connect
            hWebSocket = upgradedSocket
            connectedState = true
        }

        lock.withLock { onOpenListener }?.invoke()
        startReceiveLoop(upgradedSocket)
        return true
    }

    override suspend fun sendText(text: String): Boolean {
        val sock = lock.withLock { if (!connectedState || disposedState) null else hWebSocket } ?: return false
        val utf8 = text.encodeToByteArray()
        val rc = WindowsIoHelper.runIo {
            sendFrameSync(sock, WINHTTP_WEB_SOCKET_UTF8_MESSAGE_BUFFER_TYPE, utf8)
        }
        if (rc != NO_ERROR_U) {
            emitError(IllegalStateException("WinHttpWebSocketSend(UTF8) failed: error=$rc"))
            return false
        }
        return true
    }

    override suspend fun sendBinary(bytes: ByteArray): Boolean {
        val sock = lock.withLock { if (!connectedState || disposedState) null else hWebSocket } ?: return false
        val payload = bytes.copyOf()
        val rc = WindowsIoHelper.runIo {
            sendFrameSync(sock, WINHTTP_WEB_SOCKET_BINARY_MESSAGE_BUFFER_TYPE, payload)
        }
        if (rc != NO_ERROR_U) {
            emitError(IllegalStateException("WinHttpWebSocketSend(BINARY) failed: error=$rc"))
            return false
        }
        return true
    }

    override suspend fun close(code: Int, reason: String) {
        val sock = lock.withLock {
            connectedState = false
            hWebSocket
        }
        if (sock != null) {
            val rawReason = reason.encodeToByteArray()
            val boundedReason = if (rawReason.size > 123) rawReason.copyOf(123) else rawReason
            WindowsIoHelper.runIo {
                if (boundedReason.isEmpty()) {
                    WinHttpWebSocketClose(sock, code.coerceIn(1000, 4999).toUShort(), null, 0u)
                } else {
                    boundedReason.usePinned { pinned ->
                        WinHttpWebSocketClose(
                            sock,
                            code.coerceIn(1000, 4999).toUShort(),
                            pinned.addressOf(0),
                            boundedReason.size.toUInt()
                        )
                    }
                }
            }
        }
        closeHandlesOnly()
        notifyCloseOnce(code, reason)
    }

    override fun dispose() {
        val wasConnected = lock.withLock {
            if (disposedState) return
            disposedState = true
            val prev = connectedState
            connectedState = false
            prev
        }
        closeHandlesOnly()
        if (wasConnected) {
            notifyCloseOnce(1000, "Disposed")
        }
        runCatching { wsScope.cancel() }
    }

    private fun startReceiveLoop(webSocketHandle: HINTERNET) {
        receiveJob?.cancel()
        receiveJob = wsScope.launch {
            val chunk = ByteArray(16 * 1024)
            val textFragments = ArrayList<ByteArray>()
            val binaryFragments = ArrayList<ByteArray>()

            while (isActive && !isDisposed) {
                var bytesRead = 0
                var bufType = 0u
                val status = memScoped {
                    val readVar = alloc<UIntVar>()
                    val typeVar = alloc<UIntVar>()
                    val rc = chunk.usePinned { pinned ->
                        WinHttpWebSocketReceive(
                            hWebSocket = webSocketHandle,
                            pvBuffer = pinned.addressOf(0),
                            dwBufferLength = chunk.size.toUInt(),
                            pdwBytesRead = readVar.ptr,
                            peBufferType = typeVar.ptr
                        )
                    }
                    bytesRead = readVar.value.toInt()
                    bufType = typeVar.value
                    rc
                }

                if (status != NO_ERROR_U) {
                    val shouldReportError = lock.withLock { connectedState && !disposedState }
                    if (shouldReportError) {
                        emitError(IllegalStateException("WinHttpWebSocketReceive failed: error=$status"))
                    }
                    closeHandlesOnly()
                    notifyCloseOnce(1006, "Connection closed (error=$status)")
                    break
                }

                val slice = if (bytesRead > 0) chunk.copyOf(bytesRead) else EMPTY_BYTES
                when (bufType) {
                    WINHTTP_WEB_SOCKET_UTF8_FRAGMENT_BUFFER_TYPE -> {
                        if (slice.isNotEmpty()) textFragments.add(slice)
                    }
                    WINHTTP_WEB_SOCKET_UTF8_MESSAGE_BUFFER_TYPE -> {
                        val combined = combineFragments(textFragments, slice)
                        textFragments.clear()
                        lock.withLock { onTextMessageListener }?.invoke(combined.decodeToString())
                    }
                    WINHTTP_WEB_SOCKET_BINARY_FRAGMENT_BUFFER_TYPE -> {
                        if (slice.isNotEmpty()) binaryFragments.add(slice)
                    }
                    WINHTTP_WEB_SOCKET_BINARY_MESSAGE_BUFFER_TYPE -> {
                        val combined = combineFragments(binaryFragments, slice)
                        binaryFragments.clear()
                        lock.withLock { onBinaryMessageListener }?.invoke(combined)
                    }
                    WINHTTP_WEB_SOCKET_CLOSE_BUFFER_TYPE -> {
                        val (closeCode, closeReason) = queryCloseStatusSync(webSocketHandle)
                        closeHandlesOnly()
                        notifyCloseOnce(closeCode, closeReason)
                        break
                    }
                }
            }
        }
    }

    private fun sendFrameSync(sock: HINTERNET, bufferType: UInt, bytes: ByteArray): UInt {
        return if (bytes.isEmpty()) {
            EMPTY_PIN_ANCHOR.usePinned { pinned ->
                WinHttpWebSocketSend(sock, bufferType, pinned.addressOf(0), 0u)
            }
        } else {
            bytes.usePinned { pinned ->
                WinHttpWebSocketSend(sock, bufferType, pinned.addressOf(0), bytes.size.toUInt())
            }
        }
    }

    private fun queryCloseStatusSync(sock: HINTERNET): Pair<Int, String> = memScoped {
        val statusVar = alloc<UShortVar>()
        val lenConsumedVar = alloc<UIntVar>()
        val reasonBuf = ByteArray(128)
        val rc = reasonBuf.usePinned { pinned ->
            WinHttpWebSocketQueryCloseStatus(
                hWebSocket = sock,
                pusStatus = statusVar.ptr,
                pvReason = pinned.addressOf(0),
                dwReasonLength = reasonBuf.size.toUInt(),
                pdwReasonLengthConsumed = lenConsumedVar.ptr
            )
        }
        if (rc != NO_ERROR_U) {
            return@memScoped 1000 to ""
        }
        val reasonLen = lenConsumedVar.value.toInt().coerceIn(0, reasonBuf.size)
        val reasonText = if (reasonLen > 0) reasonBuf.copyOf(reasonLen).decodeToString() else ""
        statusVar.value.toInt() to reasonText
    }

    private class UpgradeResult(
        val session: HINTERNET? = null,
        val connect: HINTERNET? = null,
        val webSocket: HINTERNET? = null,
        val errorMessage: String? = null
    )

    private fun connectWinHttpWebSocketSync(
        parsed: ParsedWsUrl,
        headers: Map<String, String>
    ): UpgradeResult {
        val session = WinHttpOpen(
            "GenericUI-WinHTTP/1.0",
            WINHTTP_ACCESS_TYPE_DEFAULT_PROXY.toUInt(),
            null,
            null,
            0u
        ) ?: return UpgradeResult(errorMessage = "WinHttpOpen failed: error=${GetLastError()}")

        val connect = WinHttpConnect(session, parsed.host, parsed.port.toUShort(), 0u)
        if (connect == null) {
            val err = GetLastError()
            WinHttpCloseHandle(session)
            return UpgradeResult(errorMessage = "WinHttpConnect(${parsed.host}:${parsed.port}) failed: error=$err")
        }

        val reqFlags = if (parsed.secure) WINHTTP_FLAG_SECURE.toUInt() else 0u
        val request = WinHttpOpenRequest(
            connect,
            "GET",
            parsed.pathAndQuery,
            null,
            null,
            null,
            reqFlags
        )
        if (request == null) {
            val err = GetLastError()
            WinHttpCloseHandle(connect)
            WinHttpCloseHandle(session)
            return UpgradeResult(errorMessage = "WinHttpOpenRequest failed: error=$err")
        }

        if (WinHttpSetOption(request, WINHTTP_OPTION_UPGRADE_TO_WEB_SOCKET.toUInt(), null, 0u) == 0) {
            val err = GetLastError()
            WinHttpCloseHandle(request)
            WinHttpCloseHandle(connect)
            WinHttpCloseHandle(session)
            return UpgradeResult(errorMessage = "WINHTTP_OPTION_UPGRADE_TO_WEB_SOCKET failed: error=$err")
        }

        for ((k, v) in headers) {
            if (k.isBlank()) continue
            val headerLine = "${k.trim()}: ${v.trim()}\r\n"
            WinHttpAddRequestHeaders(
                request,
                headerLine,
                headerLine.length.toUInt(),
                (WINHTTP_ADDREQ_FLAG_ADD.toUInt() or WINHTTP_ADDREQ_FLAG_REPLACE)
            )
        }

        if (WinHttpSendRequest(request, null, 0u, null, 0u, 0u, 0uL) == 0) {
            val err = GetLastError()
            WinHttpCloseHandle(request)
            WinHttpCloseHandle(connect)
            WinHttpCloseHandle(session)
            return UpgradeResult(errorMessage = "WinHttpSendRequest failed: error=$err")
        }

        if (WinHttpReceiveResponse(request, null) == 0) {
            val err = GetLastError()
            WinHttpCloseHandle(request)
            WinHttpCloseHandle(connect)
            WinHttpCloseHandle(session)
            return UpgradeResult(errorMessage = "WinHttpReceiveResponse failed: error=$err")
        }

        val upgraded = WinHttpWebSocketCompleteUpgrade(request, 0uL)
        WinHttpCloseHandle(request)
        if (upgraded == null) {
            val err = GetLastError()
            WinHttpCloseHandle(connect)
            WinHttpCloseHandle(session)
            return UpgradeResult(errorMessage = "WinHttpWebSocketCompleteUpgrade failed: error=$err")
        }

        return UpgradeResult(session = session, connect = connect, webSocket = upgraded)
    }

    private fun closeHandlesOnly() {
        receiveJob?.cancel()
        receiveJob = null
        val (ws, conn, sess) = lock.withLock {
            val triple = Triple(hWebSocket, hConnect, hSession)
            hWebSocket = null
            hConnect = null
            hSession = null
            connectedState = false
            triple
        }
        ws?.let { WinHttpCloseHandle(it) }
        conn?.let { WinHttpCloseHandle(it) }
        sess?.let { WinHttpCloseHandle(it) }
    }

    private fun emitError(error: Throwable) {
        lock.withLock { onErrorListener }?.invoke(error)
    }

    private fun notifyCloseOnce(code: Int, reason: String) {
        val listener = lock.withLock {
            if (closeNotified) return
            closeNotified = true
            onCloseListener
        }
        listener?.invoke(code, reason)
    }

    private fun combineFragments(fragments: List<ByteArray>, tail: ByteArray): ByteArray {
        if (fragments.isEmpty()) return tail
        var total = tail.size
        for (f in fragments) total += f.size
        val out = ByteArray(total)
        var offset = 0
        for (f in fragments) {
            f.copyInto(out, offset)
            offset += f.size
        }
        tail.copyInto(out, offset)
        return out
    }

    private data class ParsedWsUrl(
        val secure: Boolean,
        val host: String,
        val port: Int,
        val pathAndQuery: String
    )

    private fun parseWebSocketUrl(raw: String): ParsedWsUrl? {
        val trimmed = raw.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        val secure = when (scheme) {
            "wss", "https" -> true
            "ws", "http" -> false
            else -> return null
        }
        val afterScheme = trimmed.substring(schemeEnd + 3)
        val slashIdx = afterScheme.indexOf('/')
        val queryIdx = afterScheme.indexOf('?')
        val authorityEnd = listOf(slashIdx, queryIdx).filter { it >= 0 }.minOrNull() ?: afterScheme.length
        val authority = afterScheme.substring(0, authorityEnd)
        val pathAndQuery = if (authorityEnd < afterScheme.length) {
            afterScheme.substring(authorityEnd).let { p -> if (p.startsWith("/")) p else "/$p" }
        } else {
            "/"
        }
        val hostPort = authority.substringAfterLast('@')
        if (hostPort.isEmpty()) return null
        val (host, port) = if (hostPort.startsWith("[")) {
            val closeBracket = hostPort.indexOf(']')
            if (closeBracket <= 1) return null
            val h = hostPort.substring(1, closeBracket)
            val portStr = hostPort.substring(closeBracket + 1).removePrefix(":")
            val p = if (portStr.isEmpty()) (if (secure) 443 else 80) else (portStr.toIntOrNull() ?: return null)
            h to p
        } else {
            val colonIdx = hostPort.lastIndexOf(':')
            if (colonIdx > 0) {
                val h = hostPort.substring(0, colonIdx)
                val p = hostPort.substring(colonIdx + 1).toIntOrNull() ?: return null
                h to p
            } else {
                hostPort to (if (secure) 443 else 80)
            }
        }
        if (host.isBlank() || port !in 1..65535) return null
        return ParsedWsUrl(secure, host, port, pathAndQuery)
    }

    private companion object {
        const val NO_ERROR_U: UInt = 0u
        val EMPTY_BYTES = ByteArray(0)
        val EMPTY_PIN_ANCHOR = ByteArray(1)
    }
}
