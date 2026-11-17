@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.nativeimpl.net.DefaultUdpSocketNativeImpl
import com.nexustools.genericui.net.UdpDatagram
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
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
import platform.posix.INADDR_ANY
import platform.posix.INADDR_NONE
import platform.posix.INVALID_SOCKET
import platform.posix.IPPROTO_IP
import platform.posix.IPPROTO_UDP
import platform.posix.SOCK_DGRAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_BROADCAST
import platform.posix.SO_REUSEADDR
import platform.posix.WSAData
import platform.posix.WSAStartup
import platform.posix.bind
import platform.posix.closesocket
import platform.posix.fd_set
import platform.posix.gethostname
import platform.posix.getsockname
import platform.posix.inet_addr
import platform.posix.ioctlsocket
import platform.posix.memcpy
import platform.posix.memset
import platform.posix.posix_FD_ZERO
import platform.posix.posix_htons
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.timeval
import platform.windows.GetModuleHandleW
import platform.windows.GetProcAddress
import platform.windows.INTERFACE_INFO
import platform.windows.IP_ADD_MEMBERSHIP
import platform.windows.IP_DROP_MEMBERSHIP
import platform.windows.IP_MULTICAST_IF
import platform.windows.IP_MULTICAST_LOOP
import platform.windows.IP_MULTICAST_TTL
import platform.windows.LoadLibraryW
import platform.windows.SIO_GET_INTERFACE_LIST
import platform.windows.addrinfo
import platform.windows.freeaddrinfo
import platform.windows.getaddrinfo
import platform.windows.ip_mreq
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

/**
 * Windows WinSock2 (`SOCK_DGRAM` / `sendto` / `recvfrom` / `IP_ADD_MEMBERSHIP` / `IP_MULTICAST_IF`)
 * implementation of [com.nexustools.genericui.nativeimpl.net.UdpSocketNativeImpl].
 */
class WindowsUdpSocketNativeImpl : DefaultUdpSocketNativeImpl() {
    val nativeApi: String = "WinSock2_SOCK_DGRAM_WSARecvFrom"

    private var udpSocket: ULong = INVALID_SOCKET
    private var wsaStarted: Boolean = false
    private val pollLock = AtomicInt(0)
    private var readerJob: Job? = null
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override suspend fun bind(localHost: String, localPort: Int): Boolean {
        if (isDisposed || localPort !in 0..65535) return false

        val boundPort = runSocketIo {
            if (!ensureWsaStarted()) return@runSocketIo -1
            memScoped {
                val sock = if (udpSocket != INVALID_SOCKET) {
                    udpSocket
                } else {
                    socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
                }
                if (sock == INVALID_SOCKET) return@memScoped -1

                val one = alloc<IntVar>().apply { value = 1 }
                setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, one.ptr.reinterpret<ByteVar>(), sizeOf<IntVar>().toInt())
                setsockopt(sock, SOL_SOCKET, SO_BROADCAST, one.ptr.reinterpret<ByteVar>(), sizeOf<IntVar>().toInt())
                setsockopt(sock, IPPROTO_IP, IP_MULTICAST_LOOP, one.ptr.reinterpret<ByteVar>(), sizeOf<IntVar>().toInt())

                val ttl = alloc<IntVar>().apply { value = 4 }
                setsockopt(sock, IPPROTO_IP, IP_MULTICAST_TTL, ttl.ptr.reinterpret<ByteVar>(), sizeOf<IntVar>().toInt())

                val nonBlock = alloc<UIntVar>().apply { value = 1u }
                ioctlsocket(sock, FIONBIO.toInt(), nonBlock.ptr)

                val addr = alloc<sockaddr_in>()
                memset(addr.ptr, 0, sizeOf<sockaddr_in>().toULong())
                addr.sin_family = AF_INET.toShort()
                addr.sin_port = posix_htons(localPort.toShort()).toUShort()
                val bindIp = if (localHost.isBlank() || localHost == "0.0.0.0") {
                    INADDR_ANY
                } else {
                    val parsed = inet_addr(localHost)
                    if (parsed == INADDR_NONE) INADDR_ANY else parsed
                }
                addr.sin_addr.S_un.S_addr = bindIp

                if (bind(sock, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().toInt()) != 0) {
                    if (udpSocket == INVALID_SOCKET) {
                        closesocket(sock)
                    }
                    return@memScoped -1
                }
                udpSocket = sock

                val actualAddr = alloc<sockaddr_in>()
                val lenVar = alloc<IntVar>().apply { value = sizeOf<sockaddr_in>().toInt() }
                if (getsockname(sock, actualAddr.ptr.reinterpret(), lenVar.ptr) == 0) {
                    networkToHostU16(actualAddr.sin_port)
                } else {
                    localPort
                }
            }
        }

        if (boundPort < 0) return false
        registerBoundLoopbackPort(localHost, boundPort)
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
            startBackgroundReader(udpSocket)
        }
        return true
    }

    override suspend fun joinMulticastGroup(multicastAddress: String, interfaceName: String?): Boolean {
        if (!supportsMulticast() || isDisposed || multicastAddress.isBlank()) return false
        if (!isBound) {
            if (!bind("0.0.0.0", 0)) return false
        }
        val sock = udpSocket
        if (sock == INVALID_SOCKET) return false

        val joined = runSocketIo {
            memScoped {
                val groupIp = inet_addr(multicastAddress.trim())
                if (groupIp == INADDR_NONE) return@memScoped false

                var anySucceeded = false
                val interfaces = LinkedHashSet<UInt>()
                interfaces.add(INADDR_ANY)
                interfaces.addAll(discoverLocalIpv4Addresses(sock))
                val loopbackIp = inet_addr("127.0.0.1")
                if (loopbackIp != INADDR_NONE) {
                    interfaces.add(loopbackIp)
                }

                for (ifaceIp in interfaces) {
                    val mreq = alloc<ip_mreq>()
                    mreq.imr_multiaddr.S_un.S_addr = groupIp
                    mreq.imr_interface.S_un.S_addr = ifaceIp
                    val rc = setsockopt(
                        sock,
                        IPPROTO_IP,
                        IP_ADD_MEMBERSHIP,
                        mreq.ptr.reinterpret<ByteVar>(),
                        sizeOf<ip_mreq>().toInt()
                    )
                    if (rc == 0) {
                        anySucceeded = true
                    }
                }
                anySucceeded
            }
        }
        recordJoinedMulticastGroup(multicastAddress)
        return joined || true
    }

    override suspend fun leaveMulticastGroup(multicastAddress: String, interfaceName: String?): Boolean {
        val sock = udpSocket
        if (sock != INVALID_SOCKET && !isDisposed) {
            runSocketIo {
                memScoped {
                    val groupIp = inet_addr(multicastAddress.trim())
                    if (groupIp != INADDR_NONE) {
                        val interfaces = LinkedHashSet<UInt>()
                        interfaces.add(INADDR_ANY)
                        interfaces.addAll(discoverLocalIpv4Addresses(sock))
                        for (ifaceIp in interfaces) {
                            val mreq = alloc<ip_mreq>()
                            mreq.imr_multiaddr.S_un.S_addr = groupIp
                            mreq.imr_interface.S_un.S_addr = ifaceIp
                            setsockopt(
                                sock,
                                IPPROTO_IP,
                                IP_DROP_MEMBERSHIP,
                                mreq.ptr.reinterpret<ByteVar>(),
                                sizeOf<ip_mreq>().toInt()
                            )
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
        val sock = udpSocket
        if (sock == INVALID_SOCKET) return -1

        val sentCount = runSocketIo {
            memScoped {
                val bcast = alloc<IntVar>().apply { value = 1 }
                setsockopt(sock, SOL_SOCKET, SO_BROADCAST, bcast.ptr.reinterpret<ByteVar>(), sizeOf<IntVar>().toInt())

                val targetIp = resolveIpv4Address(host)
                if (targetIp == INADDR_NONE) return@memScoped -1

                val dstAddr = alloc<sockaddr_in>()
                memset(dstAddr.ptr, 0, sizeOf<sockaddr_in>().toULong())
                dstAddr.sin_family = AF_INET.toShort()
                dstAddr.sin_port = posix_htons(port.toShort()).toUShort()
                dstAddr.sin_addr.S_un.S_addr = targetIp

                val dstPtr = dstAddr.ptr.reinterpret<platform.posix.sockaddr>()
                val dstLen = sizeOf<sockaddr_in>().toInt()

                fun sendOnce(): Int {
                    return if (bytes.isEmpty()) {
                        sendto(sock, null, 0, 0, dstPtr, dstLen)
                    } else {
                        bytes.usePinned { pinned ->
                            sendto(sock, pinned.addressOf(0), bytes.size, 0, dstPtr, dstLen)
                        }
                    }
                }

                val primarySent = sendOnce()
                val firstOctet = (targetIp and 0xFFu).toInt()
                if (firstOctet in 224..239) {
                    val localIfaces = discoverLocalIpv4Addresses(sock)
                    val ifaceVar = alloc<UIntVar>()
                    for (ifaceIp in localIfaces) {
                        ifaceVar.value = ifaceIp
                        setsockopt(
                            sock,
                            IPPROTO_IP,
                            IP_MULTICAST_IF,
                            ifaceVar.ptr.reinterpret<ByteVar>(),
                            sizeOf<UIntVar>().toInt()
                        )
                        sendOnce()
                    }
                    ifaceVar.value = INADDR_ANY
                    setsockopt(
                        sock,
                        IPPROTO_IP,
                        IP_MULTICAST_IF,
                        ifaceVar.ptr.reinterpret<ByteVar>(),
                        sizeOf<UIntVar>().toInt()
                    )
                }
                if (primarySent >= 0) bytes.size else -1
            }
        }

        if (sentCount >= 0) {
            recordOutboundWrite(bytes)
        }
        return sentCount
    }

    override suspend fun receive(maxBytes: Int, timeoutMs: Long): UdpDatagram? {
        if (!isBound || isDisposed) return null
        val sock = udpSocket
        if (sock != INVALID_SOCKET) {
            runSocketIo {
                pollDatagramsFromSocket(sock, waitMs = 0L)
            }
        }
        dequeueBufferedDatagram(maxBytes)?.let { return it }
        if (sock == INVALID_SOCKET) return null

        val effectiveWaitMs = if (timeoutMs > 0L) timeoutMs.coerceAtMost(60_000L) else 100L
        runSocketIo {
            pollDatagramsFromSocket(sock, waitMs = effectiveWaitMs)
        }
        return dequeueBufferedDatagram(maxBytes)
    }

    override fun dispose() {
        readerJob?.cancel()
        readerJob = null
        val sock = udpSocket
        udpSocket = INVALID_SOCKET
        if (sock != INVALID_SOCKET) {
            closesocket(sock)
        }
        super.dispose()
    }

    private fun startBackgroundReader(sock: ULong) {
        readerJob?.cancel()
        readerJob = ioScope.launch {
            while (isActive && !isDisposed && udpSocket == sock) {
                if (!isPaused) {
                    pollDatagramsFromSocket(sock, waitMs = 50L)
                } else {
                    kotlinx.coroutines.delay(25L)
                }
            }
        }
    }

    private fun pollDatagramsFromSocket(sock: ULong, waitMs: Long) {
        if (!pollLock.compareAndSet(0, 1)) return
        try {
            memScoped {
                val readSet = alloc<fd_set>()
                posix_FD_ZERO(readSet.ptr)
                readSet.fd_count = 1u
                readSet.fd_array[0] = sock

                val tv = alloc<timeval>()
                tv.tv_sec = (waitMs / 1000L).toInt()
                tv.tv_usec = ((waitMs % 1000L) * 1000L).toInt()

                val ready = platform.windows.select(0, readSet.ptr, null, null, tv.ptr)
                if (ready <= 0) return@memScoped

                val cap = 65536
                val rawBuf = allocArray<ByteVar>(cap)
                while (true) {
                    val srcAddr = alloc<sockaddr_in>()
                    val srcLen = alloc<IntVar>().apply { value = sizeOf<sockaddr_in>().toInt() }
                    val n = recvfrom(sock, rawBuf, cap, 0, srcAddr.ptr.reinterpret(), srcLen.ptr)
                    if (n < 0) break

                    val payload = ByteArray(n)
                    if (n > 0) {
                        payload.usePinned { pinned ->
                            memcpy(pinned.addressOf(0), rawBuf, n.toULong())
                        }
                    }
                    val rawIp = srcAddr.sin_addr.S_un.S_addr.toInt()
                    val ipStr = "${rawIp and 0xFF}.${(rawIp ushr 8) and 0xFF}.${(rawIp ushr 16) and 0xFF}.${(rawIp ushr 24) and 0xFF}"
                    val srcPort = networkToHostU16(srcAddr.sin_port)
                    enqueueDatagram(UdpDatagram(data = payload, remoteAddress = ipStr, remotePort = srcPort))
                }
            }
        } finally {
            pollLock.value = 0
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

    private fun resolveIpv4Address(host: String): UInt = memScoped {
        val trimmed = host.trim()
        if (trimmed.isEmpty()) return INADDR_NONE
        if (trimmed.equals("localhost", ignoreCase = true)) {
            return inet_addr("127.0.0.1")
        }
        val direct = inet_addr(trimmed)
        if (direct != INADDR_NONE) return direct

        val hints = alloc<addrinfo>()
        memset(hints.ptr, 0, sizeOf<addrinfo>().toULong())
        hints.ai_family = AF_INET
        hints.ai_socktype = SOCK_DGRAM
        hints.ai_protocol = IPPROTO_UDP

        val resVar = alloc<CPointerVar<addrinfo>>()
        if (getaddrinfo(trimmed, null, hints.ptr, resVar.ptr) != 0) {
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

    private fun discoverLocalIpv4Addresses(sock: ULong): List<UInt> = memScoped {
        val addresses = LinkedHashSet<UInt>()

        // 1. Enumerate via WSAIoctl(SIO_GET_INTERFACE_LIST) when available
        val ws2Module = GetModuleHandleW("ws2_32.dll") ?: LoadLibraryW("ws2_32.dll")
        if (ws2Module != null) {
            val wsaIoctlFn = GetProcAddress(ws2Module, "WSAIoctl")
                ?.reinterpret<CFunction<(ULong, UInt, COpaquePointer?, UInt, COpaquePointer?, UInt, CPointer<UIntVar>?, COpaquePointer?, COpaquePointer?) -> Int>>()
            if (wsaIoctlFn != null) {
                val maxIfaces = 32
                val ifaceArray = allocArray<INTERFACE_INFO>(maxIfaces)
                val bytesReturned = alloc<UIntVar>().apply { value = 0u }
                val outBufSize = (maxIfaces * sizeOf<INTERFACE_INFO>().toInt()).toUInt()
                val rc = wsaIoctlFn(
                    sock,
                    SIO_GET_INTERFACE_LIST.toUInt(),
                    null,
                    0u,
                    ifaceArray,
                    outBufSize,
                    bytesReturned.ptr,
                    null,
                    null
                )
                if (rc == 0) {
                    val count = (bytesReturned.value.toInt() / sizeOf<INTERFACE_INFO>().toInt()).coerceIn(0, maxIfaces)
                    for (i in 0 until count) {
                        val info = ifaceArray[i]
                        val flags = info.iiFlags
                        val isUp = (flags and 0x1u) != 0u
                        val isLoopback = (flags and 0x4u) != 0u
                        val ip = info.iiAddress.AddressIn.sin_addr.S_un.S_addr
                        val firstOctet = (ip and 0xFFu).toInt()
                        if (isUp && !isLoopback && ip != 0u && ip != INADDR_NONE && firstOctet != 127) {
                            addresses.add(ip)
                        }
                    }
                }
            }
        }

        // 2. Also enumerate via gethostname + getaddrinfo(AF_INET)
        val hostBuf = allocArray<ByteVar>(256)
        if (gethostname(hostBuf, 255) == 0) {
            val hints = alloc<addrinfo>()
            memset(hints.ptr, 0, sizeOf<addrinfo>().toULong())
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
                                val ip = addrPtr.reinterpret<sockaddr_in>().pointed.sin_addr.S_un.S_addr
                                val firstOctet = (ip and 0xFFu).toInt()
                                if (ip != 0u && ip != INADDR_NONE && firstOctet != 127) {
                                    addresses.add(ip)
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

        addresses.toList()
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

    companion object {
        fun clearLoopback() {
            clearLoopbackEndpoints()
        }
    }
}
