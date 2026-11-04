package com.nexustools.genericui.macos

import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.net.DefaultHttpRequestNativeImpl
import com.nexustools.genericui.net.http.HttpMethod

/**
 * macOS Foundation (`NSMutableURLRequest` / `HTTPBodyStream`) implementation of
 * [com.nexustools.genericui.nativeimpl.net.HttpRequestNativeImpl].
 */
class MacosHttpRequestNativeImpl(
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
    val nativeApi: String = "Foundation_NSMutableURLRequest"
}
