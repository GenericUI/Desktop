package com.nexustools.genericui.swing

import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Permissive [X509TrustManager] used by [SwingTcpSocketNativeImpl] when connecting to local LAN
 * TLS endpoints that present self-signed device certificates (such as Google Cast V2 on port 8009).
 */
class SwingPermissiveX509TrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
