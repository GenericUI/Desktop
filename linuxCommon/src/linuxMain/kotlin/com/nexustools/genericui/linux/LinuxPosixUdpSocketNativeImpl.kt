@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.nativeimpl.net.DefaultUdpSocketNativeImpl
import com.nexustools.genericui.net.UdpDatagram
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
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
import platform.posix.INADDR_NONE
import platform.posix.IPPROTO_IP
import platform.posix.IPPROTO_UDP
import platform.posix.IP_ADD_MEMBERSHIP
import platform.posix.IP_DROP_MEMBERSHIP
import platform.posix.IP_MULTICAST_IF
import platform.posix.IP_MULTICAST_LOOP
import platform.posix.IP_MULTICAST_TTL
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
import platform.posix.gethostname
import platform.posix.getsockname
import platform.posix.in_addr
import platform.posix.ip_mreq
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
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

actual open class LinuxPosixUdpSocketNativeImpl actual constructor() : DefaultUdpSocketNativeImpl() {

    private var udpFd: Int = -1

    override suspend fun bind(localHost: String, localPort: Int): Boolean {
        if (isDisposed || localPort !in 0..65535) return false

        val boundPort = runSocketIo {
            memScoped {
                val fd = if (udpFd >= 0) udpFd else socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
                if (fd < 0) return@memScoped -1

                val one = alloc<IntVar>().apply { value = 1 }
                setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, one.ptr, sizeOf<IntVar>().convert())
                setsockopt(fd, SOL_SOCKET, SO_BROADCAST, one.ptr, sizeOf<IntVar>().convert())
                setsockopt(fd, IPPROTO_IP, IP_MULTICAST_LOOP, one.ptr, sizeOf<IntVar>().convert())

                val ttl = alloc<IntVar>().apply { value = 4 }
                setsockopt(fd, IPPROTO_IP, IP_MULTICAST_TTL, ttl.ptr, sizeOf<IntVar>().convert())

                val flags = fcntl(fd, F_GETFL, 0)
                fcntl(fd, F_SETFL, flags or O_NONBLOCK)

                val addr = alloc<sockaddr_in>()
                memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
                addr.sin_family = AF_INET.convert()
                addr.sin_port = posix_htons(localPort.toShort()).toUShort()
                val bindIp = if (localHost.isBlank() || localHost == "0.0.0.0") {
                    INADDR_ANY
                } else {
                    val parsed = parseIpv4Addr(localHost)
                    if (parsed == INADDR_NONE) INADDR_ANY else parsed
                }
                addr.sin_addr.s_addr = bindIp

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
        registerBoundLoopbackPort(localHost, boundPort)
        return true
    }

    override suspend fun joinMulticastGroup(multicastAddress: String, interfaceName: String?): Boolean {
        if (!supportsMulticast() || isDisposed || multicastAddress.isBlank()) return false
        if (!isBound) {
            if (!bind("0.0.0.0", 0)) return false
        }
        val fd = udpFd
        if (fd < 0) return false

        runSocketIo {
            memScoped {
                val groupIp = parseIpv4Addr(multicastAddress.trim())
                if (groupIp == INADDR_NONE) return@memScoped
                val interfaces = LinkedHashSet<UInt>()
                interfaces.add(INADDR_ANY)
                interfaces.addAll(discoverLocalIpv4Addresses())
                val lo = parseIpv4Addr("127.0.0.1")
                if (lo != INADDR_NONE) interfaces.add(lo)

                for (ifaceIp in interfaces) {
                    val mreq = alloc<ip_mreq>()
                    mreq.imr_multiaddr.s_addr = groupIp
                    mreq.imr_interface.s_addr = ifaceIp
                    setsockopt(fd, IPPROTO_IP, IP_ADD_MEMBERSHIP, mreq.ptr, sizeOf<ip_mreq>().convert())
                }
            }
        }
        recordJoinedMulticastGroup(multicastAddress)
        return true
    }

    override suspend fun leaveMulticastGroup(multicastAddress: String, interfaceName: String?): Boolean {
        val fd = udpFd
        if (fd >= 0 && !isDisposed) {
            runSocketIo {
                memScoped {
                    val groupIp = parseIpv4Addr(multicastAddress.trim())
                    if (groupIp != INADDR_NONE) {
                        val interfaces = LinkedHashSet<UInt>()
                        interfaces.add(INADDR_ANY)
                        interfaces.addAll(discoverLocalIpv4Addresses())
                        for (ifaceIp in interfaces) {
                            val mreq = alloc<ip_mreq>()
                            mreq.imr_multiaddr.s_addr = groupIp
                            mreq.imr_interface.s_addr = ifaceIp
                            setsockopt(fd, IPPROTO_IP, IP_DROP_MEMBERSHIP, mreq.ptr, sizeOf<ip_mreq>().convert())
                        }
                    }
                }
            }
        }
        return recordLeftMulticastGroup(multicastAddress)
    }

    override suspend fun send(bytes: ByteArray, host: String, port: Int): Int {
        if (isDisposed || port !in 1..65535) return -1
        if (!isBound) {
            if (!bind("0.0.0.0", 0)) return -1
        }
        val fd = udpFd
        if (fd < 0) return -1

        val sent = runSocketIo {
            memScoped {
                val bcast = alloc<IntVar>().apply { value = 1 }
                setsockopt(fd, SOL_SOCKET, SO_BROADCAST, bcast.ptr, sizeOf<IntVar>().convert())

                val hints = alloc<addrinfo>()
                memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
                hints.ai_family = AF_INET
                hints.ai_socktype = SOCK_DGRAM
                hints.ai_protocol = IPPROTO_UDP

                val resVar = alloc<CPointerVar<addrinfo>>()
                if (getaddrinfo(host.trim(), port.toString(), hints.ptr, resVar.ptr) != 0) {
                    return@memScoped -1
                }
                val res = resVar.value ?: return@memScoped -1
                try {
                    val dstAddr = res.pointed.ai_addr ?: return@memScoped -1
                    val dstLen = res.pointed.ai_addrlen
                    val targetIp = dstAddr.reinterpret<sockaddr_in>().pointed.sin_addr.s_addr

                    fun sendOnce(): Int = if (bytes.isEmpty()) {
                        sendto(fd, null, 0u, 0, dstAddr, dstLen).toInt()
                    } else {
                        bytes.usePinned { pinned ->
                            sendto(fd, pinned.addressOf(0), bytes.size.convert(), 0, dstAddr, dstLen).toInt()
                        }
                    }

                    val primary = sendOnce()
                    val firstOctet = (targetIp and 0xFFu).toInt()
                    if (firstOctet in 224..239) {
                        val ifaceAddr = alloc<in_addr>()
                        for (ifaceIp in discoverLocalIpv4Addresses()) {
                            ifaceAddr.s_addr = ifaceIp
                            setsockopt(fd, IPPROTO_IP, IP_MULTICAST_IF, ifaceAddr.ptr, sizeOf<in_addr>().convert())
                            sendOnce()
                        }
                        ifaceAddr.s_addr = INADDR_ANY
                        setsockopt(fd, IPPROTO_IP, IP_MULTICAST_IF, ifaceAddr.ptr, sizeOf<in_addr>().convert())
                    }
                    if (primary >= 0) bytes.size else -1
                } finally {
                    freeaddrinfo(res)
                }
            }
        }
        if (sent >= 0) {
            recordOutboundWrite(bytes)
        }
        return sent
    }

    override suspend fun receive(maxBytes: Int, timeoutMs: Long): UdpDatagram? {
        val fd = udpFd
        if (!isBound || isDisposed) return null
        if (fd >= 0) {
            runSocketIo {
                pollSocketDatagrams(fd, waitMs = 0L)
            }
        }
        dequeueBufferedDatagram(maxBytes)?.let { return it }
        if (fd < 0) return null

        val waitMs = if (timeoutMs > 0L) timeoutMs.coerceIn(1L, 60_000L) else 100L
        runSocketIo {
            pollSocketDatagrams(fd, waitMs = waitMs)
        }
        return dequeueBufferedDatagram(maxBytes)
    }

    override fun dispose() {
        val fd = udpFd
        udpFd = -1
        if (fd >= 0) {
            platform.posix.close(fd)
        }
        super.dispose()
    }

    private fun pollSocketDatagrams(fd: Int, waitMs: Long) = memScoped {
        val readSet = alloc<fd_set>()
        posix_FD_ZERO(readSet.ptr)
        posix_FD_SET(fd, readSet.ptr)
        val tv = alloc<timeval>()
        tv.tv_sec = (waitMs / 1000L).convert()
        tv.tv_usec = ((waitMs % 1000L) * 1000L).convert()

        val ready = select(fd + 1, readSet.ptr, null, null, tv.ptr)
        if (ready <= 0) return@memScoped

        val cap = 65536
        val rawBuf = allocArray<ByteVar>(cap)
        while (true) {
            val srcAddr = alloc<sockaddr_in>()
            val srcLen = alloc<platform.posix.socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
            val n = recvfrom(fd, rawBuf, cap.convert(), 0, srcAddr.ptr.reinterpret(), srcLen.ptr).toInt()
            if (n < 0) break

            val payload = ByteArray(n)
            if (n > 0) {
                payload.usePinned { pinned ->
                    memcpy(pinned.addressOf(0), rawBuf, n.convert())
                }
            }
            val rawIp = srcAddr.sin_addr.s_addr.toInt()
            val ipStr = "${rawIp and 0xFF}.${(rawIp ushr 8) and 0xFF}.${(rawIp ushr 16) and 0xFF}.${(rawIp ushr 24) and 0xFF}"
            val srcPort = networkToHostU16(srcAddr.sin_port)
            enqueueDatagram(UdpDatagram(data = payload, remoteAddress = ipStr, remotePort = srcPort))
        }
    }

    private fun discoverLocalIpv4Addresses(): List<UInt> = memScoped {
        val list = LinkedHashSet<UInt>()
        val hostBuf = allocArray<ByteVar>(256)
        if (gethostname(hostBuf, 255u) == 0) {
            val hints = alloc<addrinfo>()
            memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
            hints.ai_family = AF_INET
            val resVar = alloc<CPointerVar<addrinfo>>()
            if (getaddrinfo(hostBuf, null, hints.ptr, resVar.ptr) == 0) {
                val res = resVar.value
                if (res != null) {
                    try {
                        var cur: CPointer<addrinfo>? = res
                        while (cur != null) {
                            val ai = cur.pointed
                            val addrPtr = ai.ai_addr
                            if (ai.ai_family == AF_INET && addrPtr != null) {
                                val ip = addrPtr.reinterpret<sockaddr_in>().pointed.sin_addr.s_addr
                                val firstOctet = (ip and 0xFFu).toInt()
                                if (ip != 0u && ip != INADDR_NONE && firstOctet != 127) {
                                    list.add(ip)
                                }
                            }
                            cur = ai.ai_next
                        }
                    } finally {
                        freeaddrinfo(res)
                    }
                }
            }
        }
        list.toList()
    }

    private fun parseIpv4Addr(ip: String): UInt {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return INADDR_NONE
        val b0 = parts[0].toIntOrNull()?.takeIf { it in 0..255 } ?: return INADDR_NONE
        val b1 = parts[1].toIntOrNull()?.takeIf { it in 0..255 } ?: return INADDR_NONE
        val b2 = parts[2].toIntOrNull()?.takeIf { it in 0..255 } ?: return INADDR_NONE
        val b3 = parts[3].toIntOrNull()?.takeIf { it in 0..255 } ?: return INADDR_NONE
        return b0.toUInt() or
            (b1.toUInt() shl 8) or
            (b2.toUInt() shl 16) or
            (b3.toUInt() shl 24)
    }

    private fun networkToHostU16(netPort: UShort): Int =
        posix_htons(netPort.toShort()).toInt() and 0xFFFF

    private suspend inline fun <T> runSocketIo(crossinline block: () -> T): T {
        return if (coroutineContext[ContinuationInterceptor] == null) {
            block()
        } else {
            withContext(Dispatchers.Default) { block() }
        }
    }
}
