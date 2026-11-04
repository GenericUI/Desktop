package com.nexustools.genericui.swing

import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.net.DefaultHttpResponseNativeImpl

/**
 * Java Swing / JVM (`java.net.http.HttpResponse` / `HttpURLConnection.getInputStream()`) implementation
 * of [com.nexustools.genericui.nativeimpl.net.HttpResponseNativeImpl].
 */
class SwingHttpResponseNativeImpl(
    statusCode: Int,
    statusText: String = "",
    headers: Map<String, String> = emptyMap(),
    initialBodyBytes: ByteArray = ByteArray(0),
    url: String = "",
    byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
) : DefaultHttpResponseNativeImpl(
    statusCode = statusCode,
    statusText = statusText,
    headers = headers,
    initialBodyBytes = initialBodyBytes,
    url = url,
    byteOrder = byteOrder
) {
    val nativeApi: String = "Java_Net_HttpClient_HttpResponse"
}
