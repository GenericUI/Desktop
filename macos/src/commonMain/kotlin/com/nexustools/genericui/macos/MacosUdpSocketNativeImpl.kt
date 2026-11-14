@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.nativeimpl.net.DefaultUdpSocketNativeImpl
import com.nexustools.genericui.net.UdpDatagram
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.INADDR_ANY
import platform.posix.IPPROTO_UDP
import platform.posix.O_NONBLOCK
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_BROADCAST
import platform.posix.SO_REUSEADDR
import platform.posix.addrinfo
import platform.posix.bind
import platform.posix.fcntl
import platform.posix.fd_set
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getsockname
import platform.posix.memcpy
import platform.posix.memset
import platform.posix.posix_FD_SET
import platform.posix.posix_FD_ZERO
import platform.posix.posix_htons
import platform.posix.recvfrom
import platform.posix.select
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval

/**
 * macOS Network.framework / POSIX BSD `sendto` & `recvfrom` implementation of
 * [com.nexustools.genericui.nativeimpl.net.UdpSocketNativeImpl] with automatic loopback delegation
 * when [MacosApplicationNativeImpl.interactiveMacosGuiEnabled] is `false`.
 */
class MacosUdpSocketNativeImpl : DefaultUdpSocketNativeImpl() {
    val nativeApi: String = "NWConnection_UDP_BSD_recvfrom"

    private var udpFd: Int = -1

    override suspend fun bind(localHost: String, localPort: Int): Boolean {
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            return super.bind(localHost, localPort)
        }
        if (isDisposed || localPort !in 0..65535) return false

        val boundPort = withContext(Dispatchers.Default) {
            memScoped {
                val fd = if (udpFd >= 0) udpFd else socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
                if (fd < 0) return@memScoped -1

                val reuse = alloc<IntVar>().apply { value = 1 }
                setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, reuse.ptr, sizeOf<IntVar>().convert())
                if (broadcastEnabled) {
                    setsockopt(fd, SOL_SOCKET, SO_BROADCAST, reuse.ptr, sizeOf<IntVar>().convert())
                }
                val flags = fcntl(fd, F_GETFL, 0)
                fcntl(fd, F_SETFL, flags or O_NONBLOCK)

                val addr = alloc<sockaddr_in>()
                memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
                addr.sin_len = sizeOf<sockaddr_in>().toUByte()
                addr.sin_family = AF_INET.convert()
                addr.sin_port = posix_htons(localPort.toShort()).toUShort()
                addr.sin_addr.s_addr = INADDR_ANY

                if (bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) != 0) {
                    if (udpFd < 0) platform.posix.close(fd)
                    return@memScoped -1
                }
                udpFd = fd

                val actualAddr = alloc<sockaddr_in>()
                val lenVar = alloc<platform.posix.socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
                if (getsockname(fd, actualAddr.ptr.reinterpret(), lenVar.ptr) == 0) {
                    networkToHostU16(actualAddr.sin_port)
                } else {
                    localPort
                }
            }
        }
        if (boundPort < 0) return false
        this.localAddress = localHost
        this.localPort = boundPort
        this.isBound = true
        return true
    }

    override suspend fun send(bytes: ByteArray, host: String, port: Int): Int {
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            return super.send(bytes, host, port)
        }
        if (isDisposed || port !in 1..65535) return -1
        if (!isBound) {
            if (!bind("0.0.0.0", 0)) return -1
        }
        val fd = udpFd
        if (fd < 0) return -1

        return withContext(Dispatchers.Default) {
            memScoped {
                if (broadcastEnabled) {
                    val bcast = alloc<IntVar>().apply { value = 1 }
                    setsockopt(fd, SOL_SOCKET, SO_BROADCAST, bcast.ptr, sizeOf<IntVar>().convert())
                }
                val hints = alloc<addrinfo>()
                memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
                hints.ai_family = AF_INET
                hints.ai_socktype = SOCK_DGRAM
                hints.ai_protocol = IPPROTO_UDP

                val resVar = alloc<CPointerVar<addrinfo>>()
                if (getaddrinfo(host, port.toString(), hints.ptr, resVar.ptr) != 0) {
                    return@memScoped -1
                }
                val res = resVar.value ?: return@memScoped -1
                try {
                    val dstAddr = res.pointed.ai_addr ?: return@memScoped -1
                    val dstLen = res.pointed.ai_addrlen
                    if (bytes.isEmpty()) {
                        sendto(fd, null, 0u, 0, dstAddr, dstLen).toInt()
                    } else {
                        bytes.usePinned { pinned ->
                            sendto(fd, pinned.addressOf(0), bytes.size.convert(), 0, dstAddr, dstLen).toInt()
                        }
                    }
                } finally {
                    freeaddrinfo(res)
                }
            }
        }
    }

    override suspend fun receive(maxBytes: Int, timeoutMs: Long): UdpDatagram? {
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            return super.receive(maxBytes, timeoutMs)
        }
        val fd = udpFd
        if (!isBound || isDisposed || fd < 0) return null

        return withContext(Dispatchers.Default) {
            memScoped {
                val readSet = alloc<fd_set>()
                posix_FD_ZERO(readSet.ptr)
                posix_FD_SET(fd, readSet.ptr)
                val waitMs = timeoutMs.coerceIn(0L, 60_000L)
                val tv = alloc<timeval>()
                tv.tv_sec = (waitMs / 1000L).convert()
                tv.tv_usec = ((waitMs % 1000L) * 1000L).convert()

                val ready = select(fd + 1, readSet.ptr, null, null, tv.ptr)
                if (ready <= 0) return@memScoped null

                val cap = maxBytes.coerceIn(1, 65536)
                val rawBuf = allocArray<ByteVar>(cap)
                val srcAddr = alloc<sockaddr_in>()
                val srcLen = alloc<platform.posix.socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
                val n = recvfrom(fd, rawBuf, cap.convert(), 0, srcAddr.ptr.reinterpret(), srcLen.ptr).toInt()
                if (n < 0) return@memScoped null

                val payload = ByteArray(n)
                if (n > 0) {
                    payload.usePinned { pinned ->
                        memcpy(pinned.addressOf(0), rawBuf, n.convert())
                    }
                }
                val rawIp = srcAddr.sin_addr.s_addr.toInt()
                val ipStr = "${rawIp and 0xFF}.${(rawIp ushr 8) and 0xFF}.${(rawIp ushr 16) and 0xFF}.${(rawIp ushr 24) and 0xFF}"
                val srcPort = networkToHostU16(srcAddr.sin_port)
                UdpDatagram(data = payload, remoteAddress = ipStr, remotePort = srcPort)
            }
        }
    }

    override fun dispose() {
        val fd = udpFd
        udpFd = -1
        if (fd >= 0) {
            platform.posix.close(fd)
        }
        super.dispose()
    }

    private fun networkToHostU16(netPort: UShort): Int =
        posix_htons(netPort.toShort()).toInt() and 0xFFFF
}
