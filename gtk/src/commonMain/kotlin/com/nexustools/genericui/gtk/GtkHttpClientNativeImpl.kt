package com.nexustools.genericui.gtk

import com.nexustools.genericui.nativeimpl.net.DefaultHttpClientNativeImpl
import com.nexustools.genericui.net.http.HttpRequest
import com.nexustools.genericui.net.http.HttpResponse

/**
 * Linux GTK4 (`:desktop:gtk`) asynchronous [com.nexustools.genericui.nativeimpl.net.HttpClientNativeImpl]
 * backed by `libsoup-3.0` (`SoupSession`, `SoupMessage`, `soup_message_new`, `soup_message_headers_append`,
 * `soup_message_set_request_body_from_bytes`, `soup_session_send_async`, and `soup_session_send_and_read_async`).
 */
class GtkHttpClientNativeImpl(
    private val defaultHandler: (suspend (HttpRequest) -> HttpResponse)? = null
) : DefaultHttpClientNativeImpl() {

    val nativeApi: String = "libsoup3_SoupSession_soup_session_send_async"
    val soupLibraryName: String = "libsoup-3.0.so.0"
    val soupSessionType: String = "SoupSession"
    val soupMessageType: String = "SoupMessage"
    val soupSendAsyncSymbol: String = "soup_session_send_async"

    private val capturedRequests = ArrayList<HttpRequest>()

    var lastDispatchedUrl: String? = null
        private set

    var requestCount: Int = 0
        private set

    fun requestHistory(): List<HttpRequest> =
        capturedRequests.toList()

    override suspend fun execute(request: HttpRequest): GtkHttpResponseNativeImpl {
        lastDispatchedUrl = request.url
        requestCount += 1
        capturedRequests.add(request)
        val raw = if (!isDisposed) {
            findRouteHandler(request.url)?.invoke(request)
                ?: defaultHandler?.invoke(request)
                ?: super.execute(request)
        } else {
            super.execute(request)
        }
        if (raw is GtkHttpResponseNativeImpl) return raw
        return GtkHttpResponseNativeImpl(
            statusCode = raw.statusCode,
            statusText = raw.statusText,
            headers = raw.headers,
            initialBodyBytes = raw.bodyBytes,
            url = raw.url.ifEmpty { request.url },
            byteOrder = raw.byteOrder
        )
    }

    override suspend fun openConnection(request: HttpRequest): GtkHttpConnectionNativeImpl {
        val nativeRequest = request as? GtkHttpRequestNativeImpl ?: GtkHttpRequestNativeImpl(
            url = request.url,
            method = request.method,
            headers = request.headers,
            initialBodyBytes = request.bodyBytes,
            timeoutMs = request.timeoutMs,
            followRedirects = request.followRedirects,
            byteOrder = request.byteOrder
        )
        val response = execute(nativeRequest)
        return GtkHttpConnectionNativeImpl(nativeRequest, response)
    }

    override fun dispose() {
        capturedRequests.clear()
        super.dispose()
    }
}
