package com.nexustools.genericui.swing

import com.nexustools.genericui.jvm.JvmPermissiveX509TrustManager

/**
 * Permissive [javax.net.ssl.X509TrustManager] used by [SwingTcpSocketNativeImpl] when connecting
 * to local LAN TLS endpoints presenting self-signed certificates.
 */
class SwingPermissiveX509TrustManager : JvmPermissiveX509TrustManager()
