package com.nexustools.genericui.windows

import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.net.DefaultHttpResponseNativeImpl

/**
 * Windows WinHTTP (`WinHttpReceiveResponse` / `WinHttpReadData`) implementation of
 * [com.nexustools.genericui.nativeimpl.net.HttpResponseNativeImpl].
 */
class WindowsHttpResponseNativeImpl(
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
    val nativeApi: String = "WinHTTP_WinHttpReceiveResponse_WinHttpReadData"
}
