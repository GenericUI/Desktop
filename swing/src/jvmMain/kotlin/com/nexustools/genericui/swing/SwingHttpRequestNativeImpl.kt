package com.nexustools.genericui.swing

import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.net.DefaultHttpRequestNativeImpl
import com.nexustools.genericui.net.http.HttpMethod

/**
 * Java Swing / JVM (`java.net.http.HttpRequest` / `HttpURLConnection.getOutputStream()`) implementation
 * of [com.nexustools.genericui.nativeimpl.net.HttpRequestNativeImpl].
 */
class SwingHttpRequestNativeImpl(
    url: String,
    method: HttpMethod = HttpMethod.GET,
    headers: Map<String, String> = emptyMap(),
    initialBodyBytes: ByteArray? = null,
    timeoutMs: Long = 30_000L,
    followRedirects: Boolean = true,
    byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
) : DefaultHttpRequestNativeImpl(
    url = url,
    method = method,
    headers = headers,
    initialBodyBytes = initialBodyBytes,
    timeoutMs = timeoutMs,
    followRedirects = followRedirects,
    byteOrder = byteOrder
) {
    val nativeApi: String = "Java_Net_HttpClient_HttpRequest"
}
