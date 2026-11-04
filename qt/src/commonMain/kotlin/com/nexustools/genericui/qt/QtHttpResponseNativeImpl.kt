package com.nexustools.genericui.qt

import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.nativeimpl.net.DefaultHttpResponseNativeImpl

/**
 * Qt 6 (`QNetworkReply` / `QIODevice::readyRead`) implementation of
 * [com.nexustools.genericui.nativeimpl.net.HttpResponseNativeImpl].
 */
class QtHttpResponseNativeImpl(
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
    val nativeApi: String = "Qt6_QNetworkReply"
    val qtDeviceClass: String = "QNetworkReply"
}
