package com.nexustools.genericui.macos

import com.nexustools.genericui.nativeimpl.net.DefaultHttpConnectionNativeImpl
import com.nexustools.genericui.net.http.HttpRequest
import com.nexustools.genericui.net.http.HttpResponse

/**
 * macOS Foundation (`NSURLSessionDataTask`) duplex [com.nexustools.genericui.nativeimpl.net.HttpConnectionNativeImpl].
 */
class MacosHttpConnectionNativeImpl(
    request: HttpRequest,
    response: HttpResponse
) : DefaultHttpConnectionNativeImpl(
    request = request,
    response = response
) {
    val nativeApi: String = "Foundation_NSURLSessionDataTask"
}
