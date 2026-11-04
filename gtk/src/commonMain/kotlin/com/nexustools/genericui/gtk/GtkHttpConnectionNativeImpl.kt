package com.nexustools.genericui.gtk

import com.nexustools.genericui.nativeimpl.net.DefaultHttpConnectionNativeImpl
import com.nexustools.genericui.net.http.HttpRequest
import com.nexustools.genericui.net.http.HttpResponse

/**
 * Linux GTK4 / `libsoup-3.0` (`SoupSession` + `SoupMessage`) duplex
 * [com.nexustools.genericui.nativeimpl.net.HttpConnectionNativeImpl].
 */
class GtkHttpConnectionNativeImpl(
    request: HttpRequest,
    response: HttpResponse
) : DefaultHttpConnectionNativeImpl(
    request = request,
    response = response
) {
    val nativeApi: String = "libsoup3_SoupSession_SoupMessage_Exchange"
}
