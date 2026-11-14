@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.nativeimpl.net.DefaultTcpSocketNativeImpl
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
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
import platform.posix.EAGAIN
import platform.posix.EINPROGRESS
import platform.posix.EWOULDBLOCK
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.IPPROTO_TCP
import platform.posix.O_NONBLOCK
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_ERROR
import platform.posix.addrinfo
import platform.posix.connect
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fd_set
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getsockopt
import platform.posix.memset
import platform.posix.posix_FD_SET
import platform.posix.posix_FD_ZERO
import platform.posix.recv
import platform.posix.select
import platform.posix.send
import platform.posix.socket
import platform.posix.timeval

/**
 * macOS POSIX BSD / Network.framework implementation of
 * [com.nexustools.genericui.nativeimpl.net.TcpSocketNativeImpl] with automatic loopback delegation
 * for unit tests when [MacosApplicationNativeImpl.interactiveMacosGuiEnabled] is `false`.
 */
class MacosTcpSocketNativeImpl : DefaultTcpSocketNativeImpl() {
    val nativeApi: String = "NWConnection_NetworkFramework"

    private var socketFd: Int = -1
    private var readerJob: Job? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun connect(
        host: String,
        port: Int,
        timeoutMs: Long,
        tls: Boolean
    ): Boolean {
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            return super.connect(host, port, timeoutMs, tls)
        }
        if (isDisposed || host.isBlank() || port !in 1..65535) return false

        val fd = withContext(Dispatchers.Default) {
            openPosixTcpSocket(host, port, timeoutMs.coerceIn(250L, 60_000L))
        }
        if (fd < 0) return false

        socketFd = fd
        remoteHost = host
        remotePort = port
        isConnected = true
        startPosixReaderLoop(fd)
        return true
    }

    override fun write(chunk: ByteArray, offset: Int, length: Int): Boolean {
        val fd = socketFd
        if (fd < 0 || !MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            return super.write(chunk, offset, length)
        }
        if (!isWritable) return false
        require(offset >= 0 && length >= 0 && offset + length <= chunk.size) {
            "Invalid slice (offset=$offset, length=$length, size=${chunk.size})"
        }
        if (length == 0) return true
        val slice = chunk.copyOfRange(offset, offset + length)
        val sentOk = slice.usePinned { pinned ->
            var total = 0
            while (total < slice.size) {
                val n = send(fd, pinned.addressOf(total), (slice.size - total).convert(), 0).toInt()
                if (n > 0) {
                    total += n
                } else if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
                    continue
                } else {
                    break
                }
            }
            total == slice.size
        }
        if (sentOk && writeListeners.isNotEmpty()) {
            val buf = ByteBuffer.wrap(slice, byteOrder = byteOrder)
            writeListeners.toList().forEach { it(buf.duplicate()) }
        }
        return sentOk
    }

    override fun dispose() {
        readerJob?.cancel()
        readerJob = null
        val fd = socketFd
        socketFd = -1
        if (fd >= 0) {
            platform.posix.close(fd)
        }
        super.dispose()
    }

    private fun openPosixTcpSocket(host: String, port: Int, timeoutMs: Long): Int = memScoped {
        val hints = alloc<addrinfo>()
        memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_STREAM
        hints.ai_protocol = IPPROTO_TCP

        val resVar = alloc<CPointerVar<addrinfo>>()
        if (getaddrinfo(host, port.toString(), hints.ptr, resVar.ptr) != 0) {
            return -1
        }
        val res = resVar.value ?: return -1
        try {
            val addr = res.pointed.ai_addr ?: return -1
            val addrLen = res.pointed.ai_addrlen
            val fd = socket(res.pointed.ai_family, res.pointed.ai_socktype, res.pointed.ai_protocol)
            if (fd < 0) return -1

            val flags = fcntl(fd, F_GETFL, 0)
            fcntl(fd, F_SETFL, flags or O_NONBLOCK)

            val rc = connect(fd, addr, addrLen)
            if (rc == 0) {
                return fd
            }
            if (errno != EINPROGRESS) {
                platform.posix.close(fd)
                return -1
            }

            val writeSet = alloc<fd_set>()
            posix_FD_ZERO(writeSet.ptr)
            posix_FD_SET(fd, writeSet.ptr)
            val tv = alloc<timeval>()
            tv.tv_sec = (timeoutMs / 1000L).convert()
            tv.tv_usec = ((timeoutMs % 1000L) * 1000L).convert()

            val sel = select(fd + 1, null, writeSet.ptr, null, tv.ptr)
            if (sel <= 0) {
                platform.posix.close(fd)
                return -1
            }

            val soErr = alloc<IntVar>()
            val optLen = alloc<platform.posix.socklen_tVar>()
            optLen.value = sizeOf<IntVar>().convert()
            getsockopt(fd, SOL_SOCKET, SO_ERROR, soErr.ptr, optLen.ptr)
            if (soErr.value != 0) {
                platform.posix.close(fd)
                return -1
            }
            return fd
        } finally {
            freeaddrinfo(res)
        }
    }

    private fun startPosixReaderLoop(fd: Int) {
        readerJob?.cancel()
        readerJob = ioScope.launch {
            memScoped {
                val bufSize = 8192
                val rawBuf = allocArray<ByteVar>(bufSize)
                while (isActive && socketFd == fd && !isDisposed) {
                    val readSet = alloc<fd_set>()
                    posix_FD_ZERO(readSet.ptr)
                    posix_FD_SET(fd, readSet.ptr)
                    val tv = alloc<timeval>()
                    tv.tv_sec = 0
                    tv.tv_usec = 100_000 // 100 ms
                    val ready = select(fd + 1, readSet.ptr, null, null, tv.ptr)
                    if (ready < 0) break
                    if (ready == 0 || isPaused) continue

                    val n = recv(fd, rawBuf, bufSize.convert(), 0).toInt()
                    if (n > 0) {
                        val chunk = ByteArray(n)
                        chunk.usePinned { pinned ->
                            platform.posix.memcpy(pinned.addressOf(0), rawBuf, n.convert())
                        }
                        pushInboundBytes(chunk)
                        if (dataListeners.isNotEmpty()) {
                            val wrapped = ByteBuffer.wrap(chunk, byteOrder = byteOrder)
                            GenericUiThreading.runOnLogicAsync {
                                dataListeners.toList().forEach { it(wrapped.duplicate()) }
                            }
                        }
                    } else if (n == 0) {
                        isConnected = false
                        GenericUiThreading.runOnLogicAsync {
                            endListeners.toList().forEach { it() }
                        }
                        break
                    } else if (errno != EAGAIN && errno != EWOULDBLOCK) {
                        isConnected = false
                        break
                    }
                }
            }
        }
    }

    companion object {
        fun clearLoopback() {
            clearLoopbackServers()
        }
    }
}
