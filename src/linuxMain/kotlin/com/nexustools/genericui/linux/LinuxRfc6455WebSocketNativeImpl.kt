@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.nativeimpl.net.WebSocketNativeImpl
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.posix.O_RDONLY
import platform.posix.close as posixClose
import platform.posix.open as posixOpen
import platform.posix.read as posixRead
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

@OptIn(ExperimentalEncodingApi::class)
class LinuxRfc6455WebSocketNativeImpl(
    private val connectTimeoutMs: Long = 15_000L
) : WebSocketNativeImpl {
    private val lock = LinuxReentrantLock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var onOpenListener: (() -> Unit)? = null
    private var onTextMessageListener: ((String) -> Unit)? = null
    private var onBinaryMessageListener: ((ByteArray) -> Unit)? = null
    private var onErrorListener: ((Throwable) -> Unit)? = null
    private var onCloseListener: ((code: Int, reason: String) -> Unit)? = null

    private var socket: LinuxPosixTcpSocketNativeImpl? = null
    private var readerJob: Job? = null
    private var connectedState = false
    private var disposedState = false
    private var closeNotified = false
    private var currentUrl: String? = null

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
            lock.withLock { onErrorListener }?.invoke(IllegalArgumentException("Unsupported WebSocket URL: $url"))
            return false
        }

        close(1000, "Reconnecting")

        val tcp = LinuxPosixTcpSocketNativeImpl()
        tcp.onError { err ->
            lock.withLock { onErrorListener }?.invoke(err)
        }
        val ok = tcp.connect(parsed.host, parsed.port, connectTimeoutMs, tls = parsed.tls)
        if (!ok) {
            tcp.dispose()
            return false
        }

        val nonce = readCryptoRandomBytes(16)
        val wsKey = Base64.encode(nonce)
        val hostHeader = if (
            (parsed.tls && parsed.port == 443) ||
            (!parsed.tls && parsed.port == 80)
        ) {
            if (parsed.host.contains(':')) "[${parsed.host}]" else parsed.host
        } else {
            val hostPart = if (parsed.host.contains(':')) "[${parsed.host}]" else parsed.host
            "$hostPart:${parsed.port}"
        }

        val reqBuilder = StringBuilder()
        reqBuilder.append("GET ").append(parsed.pathAndQuery).append(" HTTP/1.1\r\n")
        reqBuilder.append("Host: ").append(hostHeader).append("\r\n")
        reqBuilder.append("Upgrade: websocket\r\n")
        reqBuilder.append("Connection: Upgrade\r\n")
        reqBuilder.append("Sec-WebSocket-Key: ").append(wsKey).append("\r\n")
        reqBuilder.append("Sec-WebSocket-Version: 13\r\n")
        for ((k, v) in headers) {
            if (!k.equals("Host", ignoreCase = true) &&
                !k.equals("Upgrade", ignoreCase = true) &&
                !k.equals("Connection", ignoreCase = true) &&
                !k.equals("Sec-WebSocket-Key", ignoreCase = true) &&
                !k.equals("Sec-WebSocket-Version", ignoreCase = true)
            ) {
                reqBuilder.append(k).append(": ").append(v).append("\r\n")
            }
        }
        reqBuilder.append("\r\n")

        tcp.write(reqBuilder.toString().encodeToByteArray())
        tcp.flush()

        val handshakeError = readAndValidateUpgradeResponse(tcp, wsKey)
        if (handshakeError != null) {
            tcp.dispose()
            lock.withLock { onErrorListener }?.invoke(IllegalStateException(handshakeError))
            return false
        }

        val openListener = lock.withLock {
            if (disposedState) {
                null
            } else {
                socket = tcp
                currentUrl = url
                connectedState = true
                closeNotified = false
                readerJob = scope.launch { runFrameReaderLoop(tcp) }
                onOpenListener
            }
        }
        if (isDisposed) {
            tcp.dispose()
            return false
        }
        openListener?.invoke()
        return true
    }

    private suspend fun readAndValidateUpgradeResponse(
        tcp: LinuxPosixTcpSocketNativeImpl,
        wsKey: String
    ): String? {
        val acc = ArrayList<Byte>(1024)
        var headerEndIndex = -1
        while (headerEndIndex < 0 && acc.size < 32 * 1024) {
            val chunk = tcp.read(4096) ?: return "WebSocket handshake EOF before HTTP 101"
            val bytes = chunk.remainingByteArray()
            for (b in bytes) acc.add(b)
            headerEndIndex = findHeaderEnd(acc)
        }
        if (headerEndIndex < 0) {
            return "WebSocket handshake headers exceeded 32KB"
        }

        val allBytes = ByteArray(acc.size) { acc[it] }
        val headerBytes = allBytes.copyOfRange(0, headerEndIndex)
        val trailingCount = allBytes.size - (headerEndIndex + 4)
        if (trailingCount > 0) {
            tcp.unshift(ByteBuffer.wrap(allBytes, headerEndIndex + 4, trailingCount))
        }

        val lines = headerBytes.decodeToString().split("\r\n")
        val statusLine = lines.firstOrNull() ?: return "Empty WebSocket HTTP response"
        if (!statusLine.contains(" 101")) {
            return "Expected HTTP 101 Switching Protocols, got: $statusLine"
        }

        var acceptHeader: String? = null
        var upgradeOk = false
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()
            if (name == "sec-websocket-accept") {
                acceptHeader = value
            } else if (name == "upgrade" && value.lowercase() == "websocket") {
                upgradeOk = true
            }
        }
        if (!upgradeOk) {
            return "Missing Upgrade: websocket header"
        }
        val expectedAccept = Base64.encode(sha1((wsKey + WS_GUID).encodeToByteArray()))
        if (acceptHeader != expectedAccept) {
            return "Invalid Sec-WebSocket-Accept: expected $expectedAccept, got $acceptHeader"
        }
        return null
    }

    private suspend fun runFrameReaderLoop(tcp: LinuxPosixTcpSocketNativeImpl) {
        var fragmentOpcode = -1
        val fragmentPayload = ArrayList<Byte>()
        var closeCode = 1006
        var closeReason = "Connection closed"

        try {
            while (isConnected && socket === tcp) {
                val hdr = tcp.tryReadFully(2)?.remainingByteArray() ?: break
                val b0 = hdr[0].toInt() and 0xFF
                val b1 = hdr[1].toInt() and 0xFF
                val fin = (b0 and 0x80) != 0
                val opcode = b0 and 0x0F
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()

                if (len == 126L) {
                    val ext = tcp.tryReadFully(2)?.remainingByteArray() ?: break
                    len = (((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)).toLong()
                } else if (len == 127L) {
                    val ext = tcp.tryReadFully(8)?.remainingByteArray() ?: break
                    var v = 0L
                    for (i in 0 until 8) {
                        v = (v shl 8) or (ext[i].toLong() and 0xFFL)
                    }
                    len = v
                }

                if (len < 0 || len > 32L * 1024L * 1024L) {
                    closeCode = 1009
                    closeReason = "Frame too large: $len"
                    break
                }

                val maskKey = if (masked) {
                    tcp.tryReadFully(4)?.remainingByteArray() ?: break
                } else {
                    null
                }

                val payload = if (len > 0L) {
                    tcp.tryReadFully(len.toInt())?.remainingByteArray() ?: break
                } else {
                    ByteArray(0)
                }

                if (maskKey != null) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor (maskKey[i and 3].toInt() and 0xFF)).toByte()
                    }
                }

                when (opcode) {
                    0x9 -> {
                        sendControlFrame(tcp, 0xA, payload)
                    }
                    0xA -> Unit
                    0x8 -> {
                        if (payload.size >= 2) {
                            closeCode = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                            closeReason = if (payload.size > 2) payload.copyOfRange(2, payload.size).decodeToString() else ""
                        } else {
                            closeCode = 1000
                            closeReason = ""
                        }
                        sendControlFrame(tcp, 0x8, payload)
                        break
                    }
                    0x1, 0x2 -> {
                        if (fin) {
                            dispatchDataMessage(opcode, payload)
                        } else {
                            fragmentOpcode = opcode
                            fragmentPayload.clear()
                            for (b in payload) fragmentPayload.add(b)
                        }
                    }
                    0x0 -> {
                        if (fragmentOpcode == -1) continue
                        for (b in payload) fragmentPayload.add(b)
                        if (fin) {
                            val combined = ByteArray(fragmentPayload.size) { fragmentPayload[it] }
                            val op = fragmentOpcode
                            fragmentOpcode = -1
                            fragmentPayload.clear()
                            dispatchDataMessage(op, combined)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            lock.withLock { onErrorListener }?.invoke(t)
        } finally {
            val notifyClose = lock.withLock {
                if (socket === tcp) {
                    socket = null
                    connectedState = false
                }
                if (!closeNotified) {
                    closeNotified = true
                    onCloseListener
                } else {
                    null
                }
            }
            tcp.dispose()
            notifyClose?.invoke(closeCode, closeReason)
        }
    }

    private fun dispatchDataMessage(opcode: Int, payload: ByteArray) {
        if (opcode == 0x1) {
            val text = payload.decodeToString()
            lock.withLock { onTextMessageListener }?.invoke(text)
        } else if (opcode == 0x2) {
            lock.withLock { onBinaryMessageListener }?.invoke(payload)
        }
    }

    override suspend fun sendText(text: String): Boolean {
        return sendFrame(0x1, text.encodeToByteArray())
    }

    override suspend fun sendBinary(bytes: ByteArray): Boolean {
        return sendFrame(0x2, bytes)
    }

    private suspend fun sendFrame(opcode: Int, payload: ByteArray): Boolean {
        val tcp = lock.withLock { if (!connectedState || disposedState) null else socket } ?: return false
        return writeMaskedFrame(tcp, opcode, payload)
    }

    private suspend fun sendControlFrame(tcp: LinuxPosixTcpSocketNativeImpl, opcode: Int, payload: ByteArray): Boolean {
        val bounded = if (payload.size > 125) payload.copyOf(125) else payload
        return writeMaskedFrame(tcp, opcode, bounded)
    }

    private suspend fun writeMaskedFrame(tcp: LinuxPosixTcpSocketNativeImpl, opcode: Int, payload: ByteArray): Boolean {
        if (tcp.isDisposed || !tcp.isConnected) return false
        val len = payload.size
        val headerSize = 2 + when {
            len <= 125 -> 0
            len <= 65535 -> 2
            else -> 8
        } + 4

        val frame = ByteArray(headerSize + len)
        frame[0] = (0x80 or (opcode and 0x0F)).toByte()
        var pos = 2
        when {
            len <= 125 -> {
                frame[1] = (0x80 or len).toByte()
            }
            len <= 65535 -> {
                frame[1] = (0x80 or 126).toByte()
                frame[2] = ((len ushr 8) and 0xFF).toByte()
                frame[3] = (len and 0xFF).toByte()
                pos = 4
            }
            else -> {
                frame[1] = (0x80 or 127).toByte()
                val l64 = len.toLong()
                for (i in 0 until 8) {
                    frame[2 + i] = ((l64 ushr ((7 - i) * 8)) and 0xFFL).toByte()
                }
                pos = 10
            }
        }

        val mask = readCryptoRandomBytes(4)
        for (i in 0 until 4) {
            frame[pos + i] = mask[i]
        }
        val dataStart = pos + 4
        for (i in 0 until len) {
            frame[dataStart + i] = (payload[i].toInt() xor (mask[i and 3].toInt() and 0xFF)).toByte()
        }

        val written = tcp.write(frame)
        tcp.flush()
        return written || tcp.isConnected
    }

    override suspend fun close(code: Int, reason: String) {
        val (tcp, job, listener) = lock.withLock {
            val t = socket
            val j = readerJob
            socket = null
            readerJob = null
            connectedState = false
            val l = if (!closeNotified && t != null) {
                closeNotified = true
                onCloseListener
            } else {
                null
            }
            Triple(t, j, l)
        }
        if (tcp != null && tcp.isConnected) {
            val reasonBytes = reason.encodeToByteArray()
            val maxReason = minOf(reasonBytes.size, 123)
            val closePayload = ByteArray(2 + maxReason)
            closePayload[0] = ((code ushr 8) and 0xFF).toByte()
            closePayload[1] = (code and 0xFF).toByte()
            reasonBytes.copyInto(closePayload, 2, 0, maxReason)
            runCatching { writeMaskedFrame(tcp, 0x8, closePayload) }
            runCatching { tcp.close() }
        } else {
            tcp?.dispose()
        }
        job?.cancel()
        listener?.invoke(code, reason)
    }

    override fun dispose() {
        val (tcp, job, listener) = lock.withLock {
            if (disposedState) return
            disposedState = true
            connectedState = false
            val t = socket
            val j = readerJob
            socket = null
            readerJob = null
            val l = if (!closeNotified && t != null) {
                closeNotified = true
                onCloseListener
            } else {
                null
            }
            Triple(t, j, l)
        }
        job?.cancel()
        tcp?.dispose()
        scope.cancel()
        listener?.invoke(1001, "Disposed")
    }

    private fun readCryptoRandomBytes(count: Int): ByteArray {
        val out = ByteArray(count)
        val fd = posixOpen("/dev/urandom", O_RDONLY)
        if (fd >= 0) {
            try {
                var off = 0
                while (off < count) {
                    val n = out.usePinned { pinned ->
                        posixRead(fd, pinned.addressOf(off), (count - off).convert()).toInt()
                    }
                    if (n <= 0) break
                    off += n
                }
                if (off == count) return out
            } finally {
                posixClose(fd)
            }
        }
        Random.nextBytes(out)
        return out
    }

    private fun findHeaderEnd(bytes: List<Byte>): Int {
        for (i in 0..(bytes.size - 4)) {
            if (bytes[i] == 13.toByte() &&
                bytes[i + 1] == 10.toByte() &&
                bytes[i + 2] == 13.toByte() &&
                bytes[i + 3] == 10.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private fun sha1(input: ByteArray): ByteArray {
        val ml = input.size.toLong() * 8L
        val paddedLen = ((input.size + 9 + 63) / 64) * 64
        val msg = ByteArray(paddedLen)
        input.copyInto(msg)
        msg[input.size] = 0x80.toByte()
        for (i in 0 until 8) {
            msg[paddedLen - 8 + i] = ((ml ushr ((7 - i) * 8)) and 0xFFL).toByte()
        }

        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()
        val w = IntArray(80)

        var offset = 0
        while (offset < paddedLen) {
            for (i in 0 until 16) {
                val base = offset + i * 4
                w[i] = ((msg[base].toInt() and 0xFF) shl 24) or
                    ((msg[base + 1].toInt() and 0xFF) shl 16) or
                    ((msg[base + 2].toInt() and 0xFF) shl 8) or
                    (msg[base + 3].toInt() and 0xFF)
            }
            for (i in 16 until 80) {
                val v = w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]
                w[i] = v.rotateLeft(1)
            }

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4

            for (i in 0 until 80) {
                val f: Int
                val k: Int
                when {
                    i < 20 -> {
                        f = (b and c) or (b.inv() and d)
                        k = 0x5A827999
                    }
                    i < 40 -> {
                        f = b xor c xor d
                        k = 0x6ED9EBA1
                    }
                    i < 60 -> {
                        f = (b and c) or (b and d) or (c and d)
                        k = 0x8F1BBCDC.toInt()
                    }
                    else -> {
                        f = b xor c xor d
                        k = 0xCA62C1D6.toInt()
                    }
                }
                val temp = a.rotateLeft(5) + f + e + k + w[i]
                e = d
                d = c
                c = b.rotateLeft(30)
                b = a
                a = temp
            }

            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            offset += 64
        }

        val digest = ByteArray(20)
        val hs = intArrayOf(h0, h1, h2, h3, h4)
        for (i in hs.indices) {
            val v = hs[i]
            digest[i * 4] = ((v ushr 24) and 0xFF).toByte()
            digest[i * 4 + 1] = ((v ushr 16) and 0xFF).toByte()
            digest[i * 4 + 2] = ((v ushr 8) and 0xFF).toByte()
            digest[i * 4 + 3] = (v and 0xFF).toByte()
        }
        return digest
    }

    private data class ParsedWsUrl(
        val tls: Boolean,
        val host: String,
        val port: Int,
        val pathAndQuery: String
    )

    private fun parseWebSocketUrl(raw: String): ParsedWsUrl? {
        val trimmed = raw.trim()
        val schemeSep = trimmed.indexOf("://")
        if (schemeSep <= 0) return null
        val scheme = trimmed.substring(0, schemeSep).lowercase()
        val tls = when (scheme) {
            "ws", "http" -> false
            "wss", "https" -> true
            else -> return null
        }
        val rest = trimmed.substring(schemeSep + 3)
        val slashIdx = rest.indexOf('/')
        val queryIdx = rest.indexOf('?')
        val authorityEnd = listOf(slashIdx, queryIdx).filter { it >= 0 }.minOrNull() ?: rest.length
        val authority = rest.substring(0, authorityEnd)
        val pathPart = if (authorityEnd < rest.length) {
            val suffix = rest.substring(authorityEnd)
            if (suffix.startsWith("/")) suffix else "/$suffix"
        } else {
            "/"
        }
        if (authority.isBlank()) return null

        val host: String
        val port: Int
        if (authority.startsWith("[")) {
            val closeBracket = authority.indexOf(']')
            if (closeBracket <= 1) return null
            host = authority.substring(1, closeBracket)
            val after = authority.substring(closeBracket + 1)
            port = if (after.startsWith(":")) after.substring(1).toIntOrNull() ?: return null
            else if (tls) 443 else 80
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon > 0) {
                host = authority.substring(0, colon)
                port = authority.substring(colon + 1).toIntOrNull() ?: return null
            } else {
                host = authority
                port = if (tls) 443 else 80
            }
        }
        if (host.isBlank() || port !in 1..65535) return null
        return ParsedWsUrl(tls = tls, host = host, port = port, pathAndQuery = pathPart)
    }

    private companion object {
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
