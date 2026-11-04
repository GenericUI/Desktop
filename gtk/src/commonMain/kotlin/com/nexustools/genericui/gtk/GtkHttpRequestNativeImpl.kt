package com.nexustools.genericui.gtk

import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.net.DefaultHttpRequestNativeImpl
import com.nexustools.genericui.net.http.HttpMethod

/**
 * Linux GTK4 / `libsoup-3.0` (`SoupMessage` / `soup_message_set_request_body`) implementation of
 * [com.nexustools.genericui.nativeimpl.net.HttpRequestNativeImpl].
 */
class GtkHttpRequestNativeImpl(
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
    val nativeApi: String = "libsoup3_SoupMessage_Request"
    val soupMessageType: String = "SoupMessage"
}
