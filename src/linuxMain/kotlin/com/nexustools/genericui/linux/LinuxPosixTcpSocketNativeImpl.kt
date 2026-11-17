@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.io.Readable
import com.nexustools.genericui.io.Stream
import com.nexustools.genericui.io.Writable
import com.nexustools.genericui.nativeimpl.net.TcpSocketNativeImpl
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.EAGAIN
import platform.posix.EINPROGRESS
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.MSG_NOSIGNAL
import platform.posix.O_NONBLOCK
import platform.posix.POLLERR
import platform.posix.POLLHUP
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.addrinfo
import platform.posix.connect as posixConnect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.freeaddrinfo
import platform.posix.gai_strerror
import platform.posix.getaddrinfo
import platform.posix.getsockname
import platform.posix.getsockopt
import platform.posix.memset
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import platform.posix.sockaddr_storage
import platform.posix.socket
import platform.posix.socklen_tVar
import platform.posix.strerror
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

class LinuxPosixTcpSocketNativeImpl : TcpSocketNativeImpl {
    private val state = LinuxStreamState(initialByteOrder = ByteOrder.LITTLE_ENDIAN)
    private val sslLock = LinuxReentrantLock()

    private var socketFd: Int = -1
    private var sslCtx: COpaquePointer? = null
    private var sslHandle: COpaquePointer? = null
    private var useTls: Boolean = false
    private var connectedState: Boolean = false
    private var remoteHostState: String? = null
    private var remotePortState: Int = 0
    private var localPortState: Int = 0
    private val pendingPlainWrites = ArrayDeque<ByteArray>()
    private val outboundHistory = ArrayList<ByteArray>()

    init {
        LinuxDynamicLibraryLoader.ensureNoSigPipe()
    }

    override val isConnected: Boolean
        get() = state.lock.withLock { connectedState && !state.isDisposed }

    override val remoteHost: String?
        get() = state.lock.withLock { remoteHostState }

    override val remotePort: Int
        get() = state.lock.withLock { remotePortState }

    override val localPort: Int
        get() = state.lock.withLock { localPortState }

    override val isReadable: Boolean
        get() = state.lock.withLock { !state.isDisposed && !state.isEnded && (connectedState || state.hasPushback()) }

    override val isPaused: Boolean
        get() = state.lock.withLock { state.isPaused }

    override val isEnded: Boolean
        get() = state.lock.withLock { state.isEnded }

    override val bytesRead: Long
        get() = state.lock.withLock { state.bytesRead }

    override val isWritable: Boolean
        get() = state.lock.withLock { !state.isDisposed && !state.isFinished && connectedState }

    override val isFinished: Boolean
        get() = state.lock.withLock { state.isFinished }

    override val bytesWritten: Long
        get() = state.lock.withLock { state.bytesWritten }

    override var highWaterMark: Int
        get() = state.lock.withLock { state.highWaterMark }
        set(value) {
            state.lock.withLock { state.highWaterMark = value }
        }

    override var byteOrder: ByteOrder
        get() = state.lock.withLock { state.byteOrder }
        set(value) {
            state.lock.withLock { state.byteOrder = value }
        }

    override val isDisposed: Boolean
        get() = state.lock.withLock { state.isDisposed }

    override suspend fun connect(host: String, port: Int, timeoutMs: Long, tls: Boolean): Boolean {
        if (isDisposed || host.isBlank() || port !in 1..65535) {
            state.emitError(IllegalArgumentException("Invalid TCP endpoint: $host:$port"))
            return false
        }

        if (tls && !LinuxOpenSslBindings.ensureLoaded()) {
            state.emitError(IllegalStateException("OpenSSL libssl.so.3 unavailable for TLS connection"))
            return false
        }

        closeTransportOnly()
        state.lock.withLock {
            state.isEnded = false
            state.isFinished = false
            useTls = tls
        }

        val deadline = TimeSource.Monotonic.markNow() + timeoutMs.coerceAtLeast(100L).milliseconds
        val cleanHost = host.trim()

        val result = withContext(Dispatchers.IO) {
            val fd = openConnectedSocket(cleanHost, port, deadline)
            if (fd < 0) {
                return@withContext false
            }

            if (!tls) {
                val boundLocalPort = queryLocalPort(fd)
                val accepted = state.lock.withLock {
                    if (state.isDisposed) {
                        false
                    } else {
                        socketFd = fd
                        connectedState = true
                        remoteHostState = cleanHost
                        remotePortState = port
                        localPortState = boundLocalPort
                        true
                    }
                }
                if (!accepted) {
                    platform.posix.close(fd)
                    state.emitError(IllegalStateException("Socket disposed during connect"))
                }
                return@withContext accepted
            }

            performTlsHandshake(fd, cleanHost, port, deadline)
        }

        return result
    }

    private fun openConnectedSocket(host: String, port: Int, deadline: TimeSource.Monotonic.ValueTimeMark): Int {
        return memScoped {
            val hints = alloc<addrinfo>()
            memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
            hints.ai_family = AF_UNSPEC
            hints.ai_socktype = SOCK_STREAM

            val resPtr = alloc<CPointerVar<addrinfo>>()
            val gai = getaddrinfo(host, port.toString(), hints.ptr, resPtr.ptr)
            if (gai != 0) {
                val msg = gai_strerror(gai)?.toKString() ?: "DNS resolution failed ($gai)"
                state.emitError(IllegalStateException("TCP resolve failed for $host:$port: $msg"))
                return@memScoped -1
            }

            val head = resPtr.value
            try {
                var current = head
                var connectedFd = -1
                var lastErrMsg = "Connection failed"
                val pfd = allocArray<pollfd>(1)

                while (current != null && !isDisposed) {
                    val info = current.pointed
                    val fd = socket(info.ai_family, info.ai_socktype, info.ai_protocol)
                    if (fd >= 0) {
                        setNonBlocking(fd, true)
                        val rc = posixConnect(fd, info.ai_addr, info.ai_addrlen)
                        if (rc == 0) {
                            connectedFd = fd
                            break
                        }
                        val err = errno
                        if (err == EINPROGRESS) {
                            var waitOk = false
                            while (!isDisposed) {
                                val remainingMs = (deadline - TimeSource.Monotonic.markNow()).inWholeMilliseconds
                                if (remainingMs <= 0L) {
                                    lastErrMsg = "Timed out connecting to $host:$port"
                                    break
                                }
                                pfd[0].fd = fd
                                pfd[0].events = POLLOUT.toShort()
                                pfd[0].revents = 0
                                val slice = minOf(remainingMs, 50L).toInt().coerceAtLeast(1)
                                val pr = poll(pfd, 1.convert(), slice)
                                if (pr > 0) {
                                    val soErr = alloc<IntVar>()
                                    val soLen = alloc<socklen_tVar>()
                                    soLen.value = sizeOf<IntVar>().convert()
                                    getsockopt(fd, SOL_SOCKET, SO_ERROR, soErr.ptr, soLen.ptr)
                                    if (soErr.value == 0) {
                                        waitOk = true
                                    } else {
                                        lastErrMsg = strerror(soErr.value)?.toKString() ?: "SO_ERROR=${soErr.value}"
                                    }
                                    break
                                } else if (pr < 0 && errno != EINTR) {
                                    lastErrMsg = strerror(errno)?.toKString() ?: "poll error"
                                    break
                                }
                            }
                            if (waitOk) {
                                connectedFd = fd
                                break
                            }
                        } else {
                            lastErrMsg = strerror(err)?.toKString() ?: "connect errno=$err"
                        }
                        platform.posix.close(fd)
                    } else {
                        lastErrMsg = strerror(errno)?.toKString() ?: "socket errno=$errno"
                    }
                    current = info.ai_next
                }

                if (connectedFd < 0) {
                    state.emitError(IllegalStateException("TCP connect to $host:$port failed: $lastErrMsg"))
                    return@memScoped -1
                }
                connectedFd
            } finally {
                if (head != null) freeaddrinfo(head)
            }
        }
    }

    private fun performTlsHandshake(
        fd: Int,
        host: String,
        port: Int,
        deadline: TimeSource.Monotonic.ValueTimeMark
    ): Boolean {
        val method = LinuxOpenSslBindings.tlsClientMethod()
        val ctx = LinuxOpenSslBindings.sslCtxNew(method)
        if (ctx == null) {
            platform.posix.close(fd)
            state.emitError(IllegalStateException(LinuxOpenSslBindings.drainLastErrorMessage("SSL_CTX_new failed")))
            return false
        }

        LinuxOpenSslBindings.sslCtxSetMode(
            ctx,
            LinuxOpenSslBindings.SSL_MODE_ENABLE_PARTIAL_WRITE or
                LinuxOpenSslBindings.SSL_MODE_ACCEPT_MOVING_WRITE_BUFFER or
                LinuxOpenSslBindings.SSL_MODE_AUTO_RETRY
        )
        LinuxOpenSslBindings.sslCtxSetDefaultVerifyPaths(ctx)
        LinuxOpenSslBindings.sslCtxSetVerify(ctx, LinuxOpenSslBindings.SSL_VERIFY_PEER)

        val ssl = LinuxOpenSslBindings.sslNew(ctx)
        if (ssl == null) {
            LinuxOpenSslBindings.sslCtxFree(ctx)
            platform.posix.close(fd)
            state.emitError(IllegalStateException(LinuxOpenSslBindings.drainLastErrorMessage("SSL_new failed")))
            return false
        }

        if (LinuxOpenSslBindings.sslSetFd(ssl, fd) != 1) {
            LinuxOpenSslBindings.sslFree(ssl)
            LinuxOpenSslBindings.sslCtxFree(ctx)
            platform.posix.close(fd)
            state.emitError(IllegalStateException("SSL_set_fd failed"))
            return false
        }

        if (!isNumericIpLiteral(host)) {
            LinuxOpenSslBindings.sslSetTlsExtHostName(ssl, host)
            LinuxOpenSslBindings.sslSet1Host(ssl, host)
        }

        memScoped {
            val pfd = allocArray<pollfd>(1)
            while (!isDisposed) {
                val rc = sslLock.withLock { LinuxOpenSslBindings.sslConnect(ssl) }
                if (rc == 1) {
                    val boundLocalPort = queryLocalPort(fd)
                    val accepted = state.lock.withLock {
                        if (state.isDisposed) {
                            false
                        } else {
                            socketFd = fd
                            sslCtx = ctx
                            sslHandle = ssl
                            connectedState = true
                            remoteHostState = host
                            remotePortState = port
                            localPortState = boundLocalPort
                            true
                        }
                    }
                    if (!accepted) {
                        LinuxOpenSslBindings.sslFree(ssl)
                        LinuxOpenSslBindings.sslCtxFree(ctx)
                        platform.posix.close(fd)
                        state.emitError(IllegalStateException("TLS socket disposed during handshake"))
                    }
                    return@memScoped accepted
                }

                val sslErr = sslLock.withLock { LinuxOpenSslBindings.sslGetError(ssl, rc) }
                val waitEvent = when (sslErr) {
                    LinuxOpenSslBindings.SSL_ERROR_WANT_READ -> POLLIN
                    LinuxOpenSslBindings.SSL_ERROR_WANT_WRITE -> POLLOUT
                    else -> {
                        val msg = LinuxOpenSslBindings.drainLastErrorMessage("TLS handshake failed for $host:$port (err=$sslErr)")
                        LinuxOpenSslBindings.sslFree(ssl)
                        LinuxOpenSslBindings.sslCtxFree(ctx)
                        platform.posix.close(fd)
                        state.emitError(IllegalStateException(msg))
                        return@memScoped false
                    }
                }

                val remainingMs = (deadline - TimeSource.Monotonic.markNow()).inWholeMilliseconds
                if (remainingMs <= 0L) {
                    LinuxOpenSslBindings.sslFree(ssl)
                    LinuxOpenSslBindings.sslCtxFree(ctx)
                    platform.posix.close(fd)
                    state.emitError(IllegalStateException("TLS handshake timed out for $host:$port"))
                    return@memScoped false
                }

                pfd[0].fd = fd
                pfd[0].events = waitEvent.toShort()
                pfd[0].revents = 0
                val slice = minOf(remainingMs, 50L).toInt().coerceAtLeast(1)
                val pr = poll(pfd, 1.convert(), slice)
                if (pr < 0 && errno != EINTR) {
                    val msg = "TLS poll failed: ${strerror(errno)?.toKString() ?: errno}"
                    LinuxOpenSslBindings.sslFree(ssl)
                    LinuxOpenSslBindings.sslCtxFree(ctx)
                    platform.posix.close(fd)
                    state.emitError(IllegalStateException(msg))
                    return@memScoped false
                }
            }
        }

        LinuxOpenSslBindings.sslFree(ssl)
        LinuxOpenSslBindings.sslCtxFree(ctx)
        platform.posix.close(fd)
        return false
    }

    override suspend fun read(maxBytes: Int): ByteBuffer? {
        if (maxBytes <= 0 || isPaused || isDisposed || state.isEnded) return null
        state.pollPushback(maxBytes)?.let { return it }

        val (tls, ssl, fd) = state.lock.withLock {
            Triple(useTls, sslHandle, socketFd)
        }
        if (!isConnected || fd < 0) return null

        return withContext(Dispatchers.IO) {
            if (tls) {
                if (ssl == null) null else readTls(fd, ssl, maxBytes)
            } else {
                readPlaintext(fd, maxBytes)
            }
        }
    }

    private suspend fun readPlaintext(fd: Int, maxBytes: Int): ByteBuffer? {
        val readCap = maxBytes.coerceIn(1, 256 * 1024)
        val buffer = ByteArray(readCap)
        return memScoped {
            val pfd = allocArray<pollfd>(1)
            while (!isDisposed) {
                val activeFd = state.lock.withLock { socketFd }
                if (activeFd != fd || activeFd < 0 || !isConnected || isPaused) {
                    return@memScoped null
                }
                pfd[0].fd = fd
                pfd[0].events = POLLIN.toShort()
                pfd[0].revents = 0
                val pr = poll(pfd, 1.convert(), 50)
                if (pr == 0) continue
                if (pr < 0) {
                    if (errno == EINTR) continue
                    state.emitError(IllegalStateException("TCP poll error: ${strerror(errno)?.toKString() ?: errno}"))
                    return@memScoped null
                }

                val n = buffer.usePinned { pinned ->
                    recv(fd, pinned.addressOf(0), readCap.convert(), 0).toInt()
                }
                when {
                    n > 0 -> {
                        val slice = buffer.copyOf(n)
                        val chunk = ByteBuffer.wrap(slice, byteOrder = byteOrder)
                        state.recordRead(n)
                        return@memScoped chunk
                    }
                    n == 0 -> {
                        state.lock.withLock { connectedState = false }
                        state.emitEnd()
                        return@memScoped null
                    }
                    else -> {
                        val err = errno
                        if (err == EAGAIN || err == EWOULDBLOCK || err == EINTR) continue
                        state.lock.withLock { connectedState = false }
                        state.emitError(IllegalStateException("TCP recv error: ${strerror(err)?.toKString() ?: err}"))
                        state.emitEnd()
                        return@memScoped null
                    }
                }
            }
            null
        }
    }

    private suspend fun readTls(fd: Int, ssl: COpaquePointer, maxBytes: Int): ByteBuffer? {
        val readCap = maxBytes.coerceIn(1, 256 * 1024)
        val buffer = ByteArray(readCap)
        return memScoped {
            val pfd = allocArray<pollfd>(1)
            while (!isDisposed) {
                val activeSsl = state.lock.withLock { sslHandle }
                val activeFd = state.lock.withLock { socketFd }
                if (activeSsl != ssl || activeFd != fd || activeFd < 0 || !isConnected || isPaused) {
                    return@memScoped null
                }

                val rc = sslLock.withLock {
                    buffer.usePinned { pinned ->
                        LinuxOpenSslBindings.sslRead(ssl, pinned.addressOf(0), readCap)
                    }
                }
                if (rc > 0) {
                    val slice = buffer.copyOf(rc)
                    val chunk = ByteBuffer.wrap(slice, byteOrder = byteOrder)
                    state.recordRead(rc)
                    return@memScoped chunk
                }

                val sslErr = sslLock.withLock { LinuxOpenSslBindings.sslGetError(ssl, rc) }
                when (sslErr) {
                    LinuxOpenSslBindings.SSL_ERROR_WANT_READ -> {
                        pfd[0].fd = fd
                        pfd[0].events = POLLIN.toShort()
                        pfd[0].revents = 0
                        val pr = poll(pfd, 1.convert(), 50)
                        if (pr < 0 && errno != EINTR) {
                            state.emitError(IllegalStateException("TLS poll error: ${strerror(errno)?.toKString() ?: errno}"))
                            return@memScoped null
                        }
                    }
                    LinuxOpenSslBindings.SSL_ERROR_WANT_WRITE -> {
                        pfd[0].fd = fd
                        pfd[0].events = POLLOUT.toShort()
                        pfd[0].revents = 0
                        val pr = poll(pfd, 1.convert(), 50)
                        if (pr < 0 && errno != EINTR) {
                            state.emitError(IllegalStateException("TLS poll error: ${strerror(errno)?.toKString() ?: errno}"))
                            return@memScoped null
                        }
                    }
                    LinuxOpenSslBindings.SSL_ERROR_ZERO_RETURN -> {
                        state.lock.withLock { connectedState = false }
                        state.emitEnd()
                        return@memScoped null
                    }
                    LinuxOpenSslBindings.SSL_ERROR_SYSCALL -> {
                        val err = errno
                        state.lock.withLock { connectedState = false }
                        if (err != 0) {
                            state.emitError(IllegalStateException("TLS recv syscall error: ${strerror(err)?.toKString() ?: err}"))
                        }
                        state.emitEnd()
                        return@memScoped null
                    }
                    else -> {
                        val msg = LinuxOpenSslBindings.drainLastErrorMessage("TLS recv failed (err=$sslErr)")
                        state.lock.withLock { connectedState = false }
                        state.emitError(IllegalStateException(msg))
                        state.emitEnd()
                        return@memScoped null
                    }
                }
            }
            null
        }
    }

    override fun write(buffer: ByteBuffer): Boolean {
        val slice = buffer.remainingByteArray()
        return write(slice, 0, slice.size)
    }

    override fun write(chunk: ByteArray, offset: Int, length: Int): Boolean {
        require(offset >= 0 && length >= 0 && offset + length <= chunk.size) {
            "Invalid slice offset=$offset length=$length size=${chunk.size}"
        }
        val slice = if (offset == 0 && length == chunk.size) chunk.copyOf() else chunk.copyOfRange(offset, offset + length)
        val (writable, tls, ssl, fd) = state.lock.withLock {
            listOf(!state.isDisposed && !state.isFinished && connectedState, useTls, sslHandle, socketFd)
        }.let {
            @Suppress("UNCHECKED_CAST")
            Tuple4(it[0] as Boolean, it[1] as Boolean, it[2] as COpaquePointer?, it[3] as Int)
        }
        if (!writable || fd < 0) return false
        if (slice.isEmpty()) return true

        val wrapped = ByteBuffer.wrap(slice, byteOrder = byteOrder)
        val alreadyQueued = state.lock.withLock {
            if (pendingPlainWrites.isNotEmpty()) {
                pendingPlainWrites.addLast(slice)
                outboundHistory.add(slice)
                state.recordWrite(slice.size)
                true
            } else {
                false
            }
        }
        if (alreadyQueued) {
            state.emitWrite(wrapped)
            return false
        }

        if (tls) {
            val activeSsl = ssl ?: return false
            var sentOffset = 0
            while (sentOffset < slice.size) {
                val n = sslLock.withLock {
                    slice.usePinned { pinned ->
                        LinuxOpenSslBindings.sslWrite(activeSsl, pinned.addressOf(sentOffset), slice.size - sentOffset)
                    }
                }
                if (n > 0) {
                    sentOffset += n
                } else {
                    val sslErr = sslLock.withLock { LinuxOpenSslBindings.sslGetError(activeSsl, n) }
                    if (sslErr == LinuxOpenSslBindings.SSL_ERROR_WANT_WRITE || sslErr == LinuxOpenSslBindings.SSL_ERROR_WANT_READ) {
                        val remainder = slice.copyOfRange(sentOffset, slice.size)
                        state.lock.withLock {
                            pendingPlainWrites.addLast(remainder)
                            outboundHistory.add(slice)
                        }
                        state.recordWrite(slice.size)
                        state.emitWrite(wrapped)
                        return false
                    }
                    state.emitError(IllegalStateException(LinuxOpenSslBindings.drainLastErrorMessage("TLS send failed (err=$sslErr)")))
                    return false
                }
            }
            state.lock.withLock { outboundHistory.add(slice) }
            state.recordWrite(slice.size)
            state.emitWrite(wrapped)
            return true
        }

        var sentOffset = 0
        while (sentOffset < slice.size) {
            val n = slice.usePinned { pinned ->
                send(fd, pinned.addressOf(sentOffset), (slice.size - sentOffset).convert(), MSG_NOSIGNAL).toInt()
            }
            if (n > 0) {
                sentOffset += n
            } else {
                val err = errno
                if (err == EINTR) continue
                if (err == EAGAIN || err == EWOULDBLOCK) {
                    val remainder = slice.copyOfRange(sentOffset, slice.size)
                    state.lock.withLock {
                        pendingPlainWrites.addLast(remainder)
                        outboundHistory.add(slice)
                    }
                    state.recordWrite(slice.size)
                    state.emitWrite(wrapped)
                    return false
                }
                state.emitError(IllegalStateException("TCP send failed: ${strerror(err)?.toKString() ?: err}"))
                return false
            }
        }

        state.lock.withLock { outboundHistory.add(slice) }
        state.recordWrite(slice.size)
        state.emitWrite(wrapped)
        return state.lock.withLock { pendingPlainWrites.isEmpty() }
    }

    override suspend fun flush() {
        val (fd, tls, ssl) = state.lock.withLock { Triple(socketFd, useTls, sslHandle) }
        if (fd < 0) return
        val hasPending = state.lock.withLock { pendingPlainWrites.isNotEmpty() }
        if (!hasPending) {
            state.emitDrain()
            return
        }

        withContext(Dispatchers.IO) {
            memScoped {
                val pfd = allocArray<pollfd>(1)
                while (!isDisposed) {
                    val chunk = state.lock.withLock { pendingPlainWrites.firstOrNull() } ?: break
                    var off = 0
                    var broken = false
                    while (off < chunk.size && !broken && !isDisposed) {
                        if (tls) {
                            val activeSsl = ssl
                            if (activeSsl == null) {
                                broken = true
                                break
                            }
                            val n = sslLock.withLock {
                                chunk.usePinned { pinned ->
                                    LinuxOpenSslBindings.sslWrite(activeSsl, pinned.addressOf(off), chunk.size - off)
                                }
                            }
                            if (n > 0) {
                                off += n
                            } else {
                                val sslErr = sslLock.withLock { LinuxOpenSslBindings.sslGetError(activeSsl, n) }
                                if (sslErr == LinuxOpenSslBindings.SSL_ERROR_WANT_WRITE || sslErr == LinuxOpenSslBindings.SSL_ERROR_WANT_READ) {
                                    pfd[0].fd = fd
                                    pfd[0].events = (if (sslErr == LinuxOpenSslBindings.SSL_ERROR_WANT_READ) POLLIN else POLLOUT).toShort()
                                    pfd[0].revents = 0
                                    val pr = poll(pfd, 1.convert(), 100)
                                    if (pr < 0 && errno != EINTR) broken = true
                                    else if ((pfd[0].revents.toInt() and (POLLERR or POLLHUP)) != 0) broken = true
                                } else {
                                    broken = true
                                }
                            }
                        } else {
                            val n = chunk.usePinned { pinned ->
                                send(fd, pinned.addressOf(off), (chunk.size - off).convert(), MSG_NOSIGNAL).toInt()
                            }
                            if (n > 0) {
                                off += n
                            } else {
                                val err = errno
                                if (err == EINTR) continue
                                if (err == EAGAIN || err == EWOULDBLOCK) {
                                    pfd[0].fd = fd
                                    pfd[0].events = POLLOUT.toShort()
                                    pfd[0].revents = 0
                                    val pr = poll(pfd, 1.convert(), 100)
                                    if (pr < 0 && errno != EINTR) broken = true
                                    else if ((pfd[0].revents.toInt() and (POLLERR or POLLHUP)) != 0) broken = true
                                    continue
                                }
                                broken = true
                            }
                        }
                    }

                    state.lock.withLock {
                        if (pendingPlainWrites.firstOrNull() === chunk) {
                            pendingPlainWrites.removeFirst()
                        }
                        if (broken && off < chunk.size) {
                            pendingPlainWrites.clear()
                        }
                    }
                    if (broken) break
                }
            }
        }
        state.emitDrain()
    }

    override suspend fun end(finalBuffer: ByteBuffer?) {
        if (finalBuffer != null && finalBuffer.hasRemaining) {
            write(finalBuffer)
        }
        flush()
        state.emitFinish()
    }

    override suspend fun close() {
        flush()
        withContext(Dispatchers.IO) {
            closeTransportOnly()
        }
        state.emitFinish()
        state.emitEnd()
        state.markDisposedAndNotifyClose()
    }

    override fun dispose() {
        closeTransportOnly()
        state.markDisposedAndNotifyClose()
    }

    override fun onData(listener: suspend (ByteBuffer) -> Unit): Readable {
        state.addDataListener(listener)
        return this
    }

    override fun onEnd(listener: suspend () -> Unit): Readable {
        state.addEndListener(listener)
        return this
    }

    override fun onWrite(listener: (ByteBuffer) -> Unit): Writable {
        state.addWriteListener(listener)
        return this
    }

    override fun onDrain(listener: suspend () -> Unit): Writable {
        state.addDrainListener(listener)
        return this
    }

    override fun onFinish(listener: suspend () -> Unit): Writable {
        state.addFinishListener(listener)
        return this
    }

    override fun onError(listener: (Throwable) -> Unit): Stream {
        state.addErrorListener(listener)
        return this
    }

    override fun onClose(listener: () -> Unit): Stream {
        state.addCloseListener(listener)
        return this
    }

    override fun pause(): Readable {
        state.pause()
        return this
    }

    override fun resume(): Readable {
        state.resume()
        return this
    }

    override fun unshift(buffer: ByteBuffer) {
        state.unshift(buffer)
    }

    override fun toByteArray(): ByteArray {
        return state.lock.withLock {
            val total = outboundHistory.sumOf { it.size }
            val result = ByteArray(total)
            var offset = 0
            for (chunk in outboundHistory) {
                chunk.copyInto(result, offset)
                offset += chunk.size
            }
            result
        }
    }

    override fun toByteBuffer(): ByteBuffer {
        return ByteBuffer.wrap(toByteArray(), byteOrder = byteOrder)
    }

    private fun closeTransportOnly() {
        var fdToClose = -1
        var sslToFree: COpaquePointer? = null
        var ctxToFree: COpaquePointer? = null
        state.lock.withLock {
            fdToClose = socketFd
            socketFd = -1
            sslToFree = sslHandle
            sslHandle = null
            ctxToFree = sslCtx
            sslCtx = null
            connectedState = false
            pendingPlainWrites.clear()
        }
        if (sslToFree != null) {
            sslLock.withLock {
                LinuxOpenSslBindings.sslShutdown(sslToFree)
                LinuxOpenSslBindings.sslFree(sslToFree)
            }
        }
        if (ctxToFree != null) {
            LinuxOpenSslBindings.sslCtxFree(ctxToFree)
        }
        if (fdToClose >= 0) {
            platform.posix.close(fdToClose)
        }
    }

    private fun setNonBlocking(fd: Int, nonBlocking: Boolean) {
        val currentFlags = fcntl(fd, F_GETFL, 0)
        if (currentFlags >= 0) {
            val updated = if (nonBlocking) (currentFlags or O_NONBLOCK) else (currentFlags and O_NONBLOCK.inv())
            fcntl(fd, F_SETFL, updated)
        }
    }

    private fun queryLocalPort(fd: Int): Int {
        return memScoped {
            val storage = alloc<sockaddr_storage>()
            val len = alloc<socklen_tVar>()
            len.value = sizeOf<sockaddr_storage>().convert()
            if (getsockname(fd, storage.ptr.reinterpret(), len.ptr) != 0) return@memScoped 0
            when (storage.ss_family.toInt()) {
                AF_INET -> networkToHostUShort(storage.ptr.reinterpret<sockaddr_in>().pointed.sin_port)
                AF_INET6 -> networkToHostUShort(storage.ptr.reinterpret<sockaddr_in6>().pointed.sin6_port)
                else -> 0
            }
        }
    }

    private fun networkToHostUShort(port: UShort): Int {
        val v = port.toInt() and 0xFFFF
        return ((v and 0xFF) shl 8) or ((v ushr 8) and 0xFF)
    }

    private fun isNumericIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true
        val parts = host.split('.')
        return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    private data class Tuple4<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
