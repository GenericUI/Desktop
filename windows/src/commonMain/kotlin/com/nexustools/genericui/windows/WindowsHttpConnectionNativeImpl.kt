package com.nexustools.genericui.windows

import com.nexustools.genericui.nativeimpl.net.DefaultHttpConnectionNativeImpl
import com.nexustools.genericui.net.http.HttpRequest
import com.nexustools.genericui.net.http.HttpResponse

/**
 * Windows WinHTTP duplex [com.nexustools.genericui.nativeimpl.net.HttpConnectionNativeImpl].
 */
class WindowsHttpConnectionNativeImpl(
    request: HttpRequest,
    response: HttpResponse
) : DefaultHttpConnectionNativeImpl(
    request = request,
    response = response
) {
    val nativeApi: String = "WinHTTP_HINTERNET_RequestResponseExchange"
}
