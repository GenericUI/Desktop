package com.nexustools.genericui.swing

import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.jvm.JvmHttpResponseNativeImpl

/**
 * Java Swing / JVM implementation of [com.nexustools.genericui.nativeimpl.net.HttpResponseNativeImpl]
 * extending [JvmHttpResponseNativeImpl].
 */
class SwingHttpResponseNativeImpl(
    statusCode: Int,
    statusText: String = "",
    headers: Map<String, String> = emptyMap(),
    initialBodyBytes: ByteArray = ByteArray(0),
    url: String = "",
    byteOrder: ByteOrder = ByteOrder.LITTLE_ENDIAN
) : JvmHttpResponseNativeImpl(
    statusCode = statusCode,
    statusText = statusText,
    headers = headers,
    initialBodyBytes = initialBodyBytes,
    url = url,
    byteOrder = byteOrder
) {
    override val nativeApi: String = "Java_Net_HttpClient_HttpResponse"
}
