package com.nexustools.genericui.swing

import com.nexustools.genericui.jvm.JvmHttpConnectionNativeImpl
import com.nexustools.genericui.net.http.HttpRequest
import com.nexustools.genericui.net.http.HttpResponse

/**
 * Java Swing / JVM duplex [com.nexustools.genericui.nativeimpl.net.HttpConnectionNativeImpl]
 * extending [JvmHttpConnectionNativeImpl].
 */
class SwingHttpConnectionNativeImpl(
    request: HttpRequest,
    response: HttpResponse
) : JvmHttpConnectionNativeImpl(
    request = request,
    response = response
) {
    override val nativeApi: String = "Java_Net_HttpClient_HttpConnection"
}
