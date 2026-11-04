package com.nexustools.genericui.gtk

import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.net.DefaultHttpResponseNativeImpl

/**
 * Linux GTK4 / `libsoup-3.0` (`SoupMessage` / `GInputStream` from `soup_session_send_finish`)
 * implementation of [com.nexustools.genericui.nativeimpl.net.HttpResponseNativeImpl].
 */
class GtkHttpResponseNativeImpl(
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
    val nativeApi: String = "libsoup3_SoupMessage_GInputStream_Response"
    val gioStreamType: String = "SoupBodyInputStream"
}
