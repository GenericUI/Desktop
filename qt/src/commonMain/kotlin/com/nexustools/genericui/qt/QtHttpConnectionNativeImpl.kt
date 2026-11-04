package com.nexustools.genericui.qt

import com.nexustools.genericui.nativeimpl.net.DefaultHttpConnectionNativeImpl
import com.nexustools.genericui.net.http.HttpRequest
import com.nexustools.genericui.net.http.HttpResponse

/**
 * Qt 6 (`QNetworkAccessManager` + `QNetworkRequest` + `QNetworkReply`) duplex
 * [com.nexustools.genericui.nativeimpl.net.HttpConnectionNativeImpl].
 */
class QtHttpConnectionNativeImpl(
    request: HttpRequest,
    response: HttpResponse
) : DefaultHttpConnectionNativeImpl(
    request = request,
    response = response
) {
    val nativeApi: String = "Qt6_QNetworkAccessManager_Exchange"
}
