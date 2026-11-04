package com.nexustools.genericui.macos

import com.nexustools.genericui.nativeimpl.net.DefaultTcpSocketNativeImpl

/**
 * macOS Network.framework (`nw_connection_t` / `NWConnection`) implementation of
 * [com.nexustools.genericui.nativeimpl.net.TcpSocketNativeImpl].
 */
class MacosTcpSocketNativeImpl : DefaultTcpSocketNativeImpl() {
    val nativeApi: String = "NWConnection_NetworkFramework"

    companion object {
        fun clearLoopback() {
            clearLoopbackServers()
        }
    }
}
