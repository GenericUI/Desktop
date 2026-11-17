@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.nativeimpl.net.DefaultTcpSocketNativeImpl
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.FIONBIO
import platform.posix.INADDR_NONE
import platform.posix.INVALID_SOCKET
import platform.posix.IPPROTO_TCP
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.WSAData
import platform.posix.WSAGetLastError
import platform.posix.WSAStartup
import platform.posix.__WSAFDIsSet
import platform.posix.closesocket
import platform.posix.connect
import platform.posix.fd_set
import platform.posix.getsockname
import platform.posix.getsockopt
import platform.posix.inet_addr
import platform.posix.ioctlsocket
import platform.posix.memcpy
import platform.posix.memset
import platform.posix.posix_FD_ZERO
import platform.posix.posix_htons
import platform.posix.recv
import platform.posix.send
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval
import platform.windows.addrinfo
import platform.windows.freeaddrinfo
import platform.windows.getaddrinfo
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

/**
 * Windows WinSock2 (`SOCK_STREAM` / `connect` / `send` / `recv` + Schannel SSPI TLS) implementation of
 * [com.nexustools.genericui.nativeimpl.net.TcpSocketNativeImpl].
 */
class WindowsTcpSocketNativeImpl : DefaultTcpSocketNativeImpl() {
    val nativeApi: String = "WinSock2_WSAOVERLAPPED"

    private var tcpSocket: ULong = INVALID_SOCKET
    private var tlsClient: Win32SchannelTlsClient? = null
    private var wsaStarted: Boolean = false
    private var lastTimeoutMs: Long = 10_000L
    private val readLock = AtomicInt(0)
    private var readerJob: Job? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun connect(
        host: String,
        port: Int,
        timeoutMs: Long,
        tls: Boolean
    ): Boolean {
        if (isDisposed || host.isBlank() || port !in 1..65535) return false

        val isLoopbackHost = host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)
        if (isLoopbackHost && hasLoopbackServer(port)) {
            return super.connect(host, port, timeoutMs, tls)
        }

        lastTimeoutMs = timeoutMs.coerceIn(500L, 60_000L)
        closeNativeSocket()

        val connectedLocalPort = runSocketIo {
            if (!ensureWsaStarted()) return@runSocketIo -1
            val sock = openWinSockTcpConnection(host.trim(), port, lastTimeoutMs)
            if (sock == INVALID_SOCKET) return@runSocketIo -1

            if (tls) {
                val schannel = Win32SchannelTlsClient(sock, host.trim())
                if (!schannel.performHandshake(lastTimeoutMs)) {
                    schannel.dispose()
                    closesocket(sock)
                    return@runSocketIo -1
                }
                tlsClient = schannel
            }

            tcpSocket = sock
            queryLocalBoundPort(sock)
        }

        if (connectedLocalPort < 0) {
            return false
        }

        markConnected(host, port, connectedLocalPort)
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
            startBackgroundReader(tcpSocket)
        }
        return true
    }

    override suspend fun read(maxBytes: Int): ByteBuffer? {
        if (isDisposed || isEnded) return null
        val sock = tcpSocket
        if (sock != INVALID_SOCKET && !hasBufferedInbound()) {
            runSocketIo {
                pollInboundFromSocket(sock, waitMs = 250L)
            }
        }
        return super.read(maxBytes)
    }

    override fun write(chunk: ByteArray, offset: Int, length: Int): Boolean {
        val sock = tcpSocket
        if (sock == INVALID_SOCKET) {
            return super.write(chunk, offset, length)
        }
        if (!isWritable) return false
        require(offset >= 0 && length >= 0 && offset + length <= chunk.size) {
            "Invalid slice (offset=$offset, length=$length, size=${chunk.size})"
        }
        if (length == 0) return true

        val slice = chunk.copyOfRange(offset, offset + length)
        val schannel = tlsClient
        val sentOk = if (schannel != null) {
            schannel.encryptAndSend(slice, lastTimeoutMs)
        } else {
            sendRawBytes(sock, slice, lastTimeoutMs)
        }
        if (!sentOk) {
            return false
        }
        return recordOutboundWrite(slice)
    }

    override fun dispose() {
        readerJob?.cancel()
        readerJob = null
        closeNativeSocket()
        super.dispose()
    }

    private fun closeNativeSocket() {
        val schannel = tlsClient
        tlsClient = null
        schannel?.dispose()

        val sock = tcpSocket
        tcpSocket = INVALID_SOCKET
        if (sock != INVALID_SOCKET) {
            closesocket(sock)
        }
    }

    private fun startBackgroundReader(sock: ULong) {
        readerJob?.cancel()
        readerJob = ioScope.launch {
            while (isActive && !isDisposed && isConnected && tcpSocket == sock) {
                if (!isPaused) {
                    pollInboundFromSocket(sock, waitMs = 50L)
                } else {
                    kotlinx.coroutines.delay(25L)
                }
            }
        }
    }

    private fun pollInboundFromSocket(sock: ULong, waitMs: Long) {
        if (!readLock.compareAndSet(0, 1)) return
        try {
            val schannel = tlsClient
            if (schannel != null) {
                val plain = schannel.receiveAndDecrypt(waitMs)
                if (plain != null && plain.isNotEmpty()) {
                    notifyInboundBytes(plain)
                }
                return
            }

            memScoped {
                val readSet = alloc<fd_set>()
                posix_FD_ZERO(readSet.ptr)
                readSet.fd_count = 1u
                readSet.fd_array[0] = sock

                val tv = alloc<timeval>()
                val clamped = waitMs.coerceIn(0L, 30_000L)
                tv.tv_sec = (clamped / 1000L).toInt()
                tv.tv_usec = ((clamped % 1000L) * 1000L).toInt()

                val ready = platform.windows.select(0, readSet.ptr, null, null, tv.ptr)
                if (ready <= 0) return@memScoped

                val cap = 16384
                val buf = allocArray<ByteVar>(cap)
                val n = recv(sock, buf, cap, 0)
                if (n > 0) {
                    val chunk = ByteArray(n)
                    chunk.usePinned { pinned ->
                        memcpy(pinned.addressOf(0), buf, n.toULong())
                    }
                    notifyInboundBytes(chunk)
                } else if (n == 0) {
                    markDisconnectedByPeer()
                } else {
                    val err = WSAGetLastError()
                    if (err != 10035) {
                        markDisconnectedByPeer()
                    }
                }
            }
        } finally {
            readLock.value = 0
        }
    }

    private fun sendRawBytes(sock: ULong, bytes: ByteArray, timeoutMs: Long): Boolean = memScoped {
        if (bytes.isEmpty()) return true
        bytes.usePinned { pinned ->
            val basePtr = pinned.addressOf(0)
            var sentTotal = 0
            while (sentTotal < bytes.size) {
                val writeSet = alloc<fd_set>()
                posix_FD_ZERO(writeSet.ptr)
                writeSet.fd_count = 1u
                writeSet.fd_array[0] = sock

                val tv = alloc<timeval>()
                val wait = timeoutMs.coerceIn(250L, 15_000L)
                tv.tv_sec = (wait / 1000L).toInt()
                tv.tv_usec = ((wait % 1000L) * 1000L).toInt()

                val sel = platform.windows.select(0, null, writeSet.ptr, null, tv.ptr)
                if (sel <= 0) return@memScoped false

                val n = send(sock, basePtr + sentTotal, bytes.size - sentTotal, 0)
                if (n > 0) {
                    sentTotal += n
                } else {
                    val err = WSAGetLastError()
                    if (err == 10035) continue
                    return@memScoped false
                }
            }
            true
        }
    }

    private fun openWinSockTcpConnection(host: String, port: Int, timeoutMs: Long): ULong = memScoped {
        val targetIp = resolveIpv4Address(host)
        if (targetIp == INADDR_NONE) return INVALID_SOCKET

        val sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        if (sock == INVALID_SOCKET) return INVALID_SOCKET

        val nonBlock = alloc<UIntVar>().apply { value = 1u }
        ioctlsocket(sock, FIONBIO.toInt(), nonBlock.ptr)

        val addr = alloc<sockaddr_in>()
        memset(addr.ptr, 0, sizeOf<sockaddr_in>().toULong())
        addr.sin_family = AF_INET.toShort()
        addr.sin_port = posix_htons(port.toShort()).toUShort()
        addr.sin_addr.S_un.S_addr = targetIp

        val rc = connect(sock, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().toInt())
        if (rc != 0) {
            val wsaErr = WSAGetLastError()
            if (wsaErr != 10035 && wsaErr != 10036) {
                closesocket(sock)
                return INVALID_SOCKET
            }

            val writeSet = alloc<fd_set>()
            val errSet = alloc<fd_set>()
            posix_FD_ZERO(writeSet.ptr)
            posix_FD_ZERO(errSet.ptr)
            writeSet.fd_count = 1u
            writeSet.fd_array[0] = sock
            errSet.fd_count = 1u
            errSet.fd_array[0] = sock

            val tv = alloc<timeval>()
            tv.tv_sec = (timeoutMs / 1000L).toInt()
            tv.tv_usec = ((timeoutMs % 1000L) * 1000L).toInt()

            val sel = platform.windows.select(0, null, writeSet.ptr, errSet.ptr, tv.ptr)
            if (sel <= 0) {
                closesocket(sock)
                return INVALID_SOCKET
            }

            val soErr = alloc<IntVar>().apply { value = 0 }
            val soErrLen = alloc<IntVar>().apply { value = sizeOf<IntVar>().toInt() }
            getsockopt(sock, SOL_SOCKET, SO_ERROR, soErr.ptr.reinterpret(), soErrLen.ptr)
            if (soErr.value != 0 || __WSAFDIsSet(sock, errSet.ptr) != 0 || __WSAFDIsSet(sock, writeSet.ptr) == 0) {
                closesocket(sock)
                return INVALID_SOCKET
            }
        }
        sock
    }

    private fun queryLocalBoundPort(sock: ULong): Int = memScoped {
        val actualAddr = alloc<sockaddr_in>()
        val lenVar = alloc<IntVar>().apply { value = sizeOf<sockaddr_in>().toInt() }
        if (getsockname(sock, actualAddr.ptr.reinterpret(), lenVar.ptr) == 0) {
            posix_htons(actualAddr.sin_port.toShort()).toInt() and 0xFFFF
        } else {
            0
        }
    }

    private fun resolveIpv4Address(host: String): UInt = memScoped {
        if (host.equals("localhost", ignoreCase = true)) {
            return inet_addr("127.0.0.1")
        }
        val direct = inet_addr(host)
        if (direct != INADDR_NONE) return direct

        val hints = alloc<addrinfo>()
        memset(hints.ptr, 0, sizeOf<addrinfo>().toULong())
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_STREAM
        hints.ai_protocol = IPPROTO_TCP

        val resVar = alloc<CPointerVar<addrinfo>>()
        if (getaddrinfo(host, null, hints.ptr, resVar.ptr) != 0) {
            return INADDR_NONE
        }
        val res = resVar.value ?: return INADDR_NONE
        try {
            var cur: CPointer<addrinfo>? = res
            while (cur != null) {
                val ai = cur.pointed
                val addrPtr = ai.ai_addr
                if (ai.ai_family == AF_INET && addrPtr != null) {
                    return addrPtr.reinterpret<sockaddr_in>().pointed.sin_addr.S_un.S_addr
                }
                cur = ai.ai_next
            }
            INADDR_NONE
        } finally {
            freeaddrinfo(res)
        }
    }

    private fun ensureWsaStarted(): Boolean {
        if (wsaStarted) return true
        return memScoped {
            val wsaData = alloc<WSAData>()
            if (WSAStartup(0x0202u, wsaData.ptr) == 0) {
                wsaStarted = true
                true
            } else {
                false
            }
        }
    }

    private suspend inline fun <T> runSocketIo(crossinline block: () -> T): T {
        return if (coroutineContext[ContinuationInterceptor] == null) {
            block()
        } else {
            withContext(Dispatchers.Default) { block() }
        }
    }

    companion object {
        fun clearLoopback() {
            clearLoopbackServers()
        }
    }
}
