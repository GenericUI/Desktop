package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.net.DefaultHttpConnectionNativeImpl
import com.nexustools.genericui.net.http.HttpRequest
import com.nexustools.genericui.net.http.HttpResponse

/**
 * Java Swing / JVM (`java.net.http.HttpClient` + `HttpURLConnection`) duplex
 * [com.nexustools.genericui.nativeimpl.net.HttpConnectionNativeImpl].
 */
class SwingHttpConnectionNativeImpl(
    request: HttpRequest,
    response: HttpResponse
) : DefaultHttpConnectionNativeImpl(
    request = request,
    response = response
) {
    val nativeApi: String = "Java_Net_HttpClient_HttpConnection"
}
