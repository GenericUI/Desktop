package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.net.DefaultUdpSocketNativeImpl
import com.nexustools.genericui.net.UdpDatagram
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import javax.swing.SwingUtilities
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

/**
 * Java Swing / JVM (`:desktop:swing`) implementation of [com.nexustools.genericui.nativeimpl.net.UdpSocketNativeImpl]
 * backed by `java.net.MulticastSocket` / `java.net.DatagramSocket` with multi-interface multicast join and send.
 */
class SwingUdpSocketNativeImpl : DefaultUdpSocketNativeImpl() {
    val nativeApi: String = "Java_NIO_DatagramChannel_MulticastSocket"

    @Volatile
    private var multicastSocket: MulticastSocket? = null
    private val ioLock = Any()

    override suspend fun bind(localHost: String, localPort: Int): Boolean {
        if (isDisposed || localPort !in 0..65535) return false

        val boundPort = runOnJvmIo {
            runCatching {
                val existing = multicastSocket
                if (existing != null && existing.isBound && !existing.isClosed) {
                    return@runCatching existing.localPort
                }
                val sock = MulticastSocket(null as SocketAddress?)
                sock.reuseAddress = true
                sock.broadcast = true
                runCatching { sock.timeToLive = 4 }
                sock.soTimeout = 50
                val bindAddr = if (localHost.isBlank() || localHost == "0.0.0.0") {
                    InetSocketAddress(localPort)
                } else {
                    InetSocketAddress(InetAddress.getByName(localHost), localPort)
                }
                sock.bind(bindAddr)
                multicastSocket = sock
                sock.localPort
            }.getOrDefault(-1)
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
        val sock = multicastSocket ?: return false

        runOnJvmIo {
            runCatching {
                val groupAddr = InetAddress.getByName(multicastAddress.trim())
                val groupSockAddr = InetSocketAddress(groupAddr, localPort.coerceAtLeast(0))
                val interfaces = eligibleMulticastInterfaces(interfaceName)
                var joinedAny = false
                for (iface in interfaces) {
                    if (runCatching { sock.joinGroup(groupSockAddr, iface) }.isSuccess) {
                        joinedAny = true
                    }
                }
                if (!joinedAny) {
                    @Suppress("DEPRECATION")
                    runCatching { sock.joinGroup(groupAddr) }
                }
                runCatching {
                    val loIface = NetworkInterface.getByInetAddress(InetAddress.getLoopbackAddress())
                    if (loIface != null) {
                        sock.joinGroup(groupSockAddr, loIface)
                    }
                }
            }
        }
        recordJoinedMulticastGroup(multicastAddress)
        return true
    }

    override suspend fun leaveMulticastGroup(multicastAddress: String, interfaceName: String?): Boolean {
        val sock = multicastSocket
        if (sock != null && !sock.isClosed && !isDisposed) {
            runOnJvmIo {
                runCatching {
                    val groupAddr = InetAddress.getByName(multicastAddress.trim())
                    val groupSockAddr = InetSocketAddress(groupAddr, localPort.coerceAtLeast(0))
                    for (iface in eligibleMulticastInterfaces(interfaceName)) {
                        runCatching { sock.leaveGroup(groupSockAddr, iface) }
                    }
                    @Suppress("DEPRECATION")
                    runCatching { sock.leaveGroup(groupAddr) }
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
        val sock = multicastSocket ?: return -1

        val sentOk = runOnJvmIo {
            runCatching {
                sock.broadcast = true
                val targetAddr = InetAddress.getByName(host.trim())
                val packet = DatagramPacket(bytes, bytes.size, targetAddr, port)
                sock.send(packet)

                if (targetAddr.isMulticastAddress) {
                    for (iface in eligibleMulticastInterfaces(null)) {
                        runCatching {
                            sock.networkInterface = iface
                            sock.send(packet)
                        }
                    }
                }
                true
            }.getOrDefault(false)
        }

        if (!sentOk) return -1
        recordOutboundWrite(bytes)
        return bytes.size
    }

    override suspend fun receive(maxBytes: Int, timeoutMs: Long): UdpDatagram? {
        if (!isBound || isDisposed) return null
        val sock = multicastSocket
        if (sock != null && !sock.isClosed) {
            runOnJvmIo {
                pollSocketDatagrams(sock, waitMs = 1)
            }
        }
        dequeueBufferedDatagram(maxBytes)?.let { return it }
        if (sock == null || sock.isClosed) return null

        val waitMs = if (timeoutMs > 0L) timeoutMs.coerceIn(1L, 60_000L).toInt() else 100
        runOnJvmIo {
            pollSocketDatagrams(sock, waitMs = waitMs)
        }
        return dequeueBufferedDatagram(maxBytes)
    }

    override fun dispose() {
        val sock = multicastSocket
        multicastSocket = null
        if (sock != null) {
            runCatching { sock.close() }
        }
        super.dispose()
    }

    private fun pollSocketDatagrams(sock: MulticastSocket, waitMs: Int) {
        synchronized(ioLock) {
            if (sock.isClosed || isDisposed) return
            val buf = ByteArray(65536)
            var currentTimeout = waitMs.coerceAtLeast(1)
            while (!sock.isClosed && !isDisposed) {
                try {
                    sock.soTimeout = currentTimeout
                    val packet = DatagramPacket(buf, buf.size)
                    sock.receive(packet)
                    val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    val senderIp = packet.address?.hostAddress ?: "127.0.0.1"
                    enqueueDatagram(UdpDatagram(data = data, remoteAddress = senderIp, remotePort = packet.port))
                    currentTimeout = 1
                } catch (_: SocketTimeoutException) {
                    break
                } catch (_: Throwable) {
                    break
                }
            }
        }
    }

    private fun eligibleMulticastInterfaces(interfaceName: String?): List<NetworkInterface> {
        val result = ArrayList<NetworkInterface>()
        val enumeration = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return result
        while (enumeration.hasMoreElements()) {
            val iface = enumeration.nextElement() ?: continue
            val matchesFilter = interfaceName.isNullOrBlank() ||
                iface.name.equals(interfaceName, ignoreCase = true) ||
                iface.displayName.equals(interfaceName, ignoreCase = true)
            if (!matchesFilter) continue
            val ok = runCatching {
                iface.isUp && !iface.isLoopback && iface.supportsMulticast() &&
                    iface.inetAddresses.toList().any { it is Inet4Address }
            }.getOrDefault(false)
            if (ok) {
                result.add(iface)
            }
        }
        return result
    }

    private suspend inline fun <T> runOnJvmIo(crossinline block: () -> T): T {
        val hasInterceptor = coroutineContext[ContinuationInterceptor] != null
        return if (hasInterceptor || SwingUtilities.isEventDispatchThread()) {
            withContext(Dispatchers.IO) { block() }
        } else {
            block()
        }
    }

    companion object {
        fun clearLoopback() {
            clearLoopbackEndpoints()
        }
    }
}
