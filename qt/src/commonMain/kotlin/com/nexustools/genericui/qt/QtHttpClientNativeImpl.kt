package com.nexustools.genericui.qt

import com.nexustools.genericui.nativeimpl.net.DefaultHttpClientNativeImpl
import com.nexustools.genericui.net.http.HttpRequest
import com.nexustools.genericui.net.http.HttpResponse

/**
 * Qt 6 (`:desktop:qt`) asynchronous [com.nexustools.genericui.nativeimpl.net.HttpClientNativeImpl]
 * backed by `QNetworkAccessManager`, `QNetworkRequest`, and `QNetworkReply`
 * (`QNetworkAccessManager::sendCustomRequest`, `QNetworkReply::finished`, and `QIODevice::readyRead`).
 */
class QtHttpClientNativeImpl(
    private val defaultHandler: (suspend (HttpRequest) -> HttpResponse)? = null
) : DefaultHttpClientNativeImpl() {

    val nativeApi: String = "Qt6_QNetworkAccessManager_QNetworkReply"
    val qtManagerClass: String = "QNetworkAccessManager"
    val qtRequestClass: String = "QNetworkRequest"
    val qtReplyClass: String = "QNetworkReply"

    private val capturedRequests = ArrayList<HttpRequest>()

    var lastRequestedUrl: String? = null
        private set

    var requestCount: Int = 0
        private set

    fun requestHistory(): List<HttpRequest> =
        capturedRequests.toList()

    override suspend fun execute(request: HttpRequest): QtHttpResponseNativeImpl {
        lastRequestedUrl = request.url
        requestCount += 1
        capturedRequests.add(request)
        val raw = if (!isDisposed) {
            findRouteHandler(request.url)?.invoke(request)
                ?: defaultHandler?.invoke(request)
                ?: super.execute(request)
        } else {
            super.execute(request)
        }
        if (raw is QtHttpResponseNativeImpl) return raw
        return QtHttpResponseNativeImpl(
            statusCode = raw.statusCode,
            statusText = raw.statusText,
            headers = raw.headers,
            initialBodyBytes = raw.bodyBytes,
            url = raw.url.ifEmpty { request.url },
            byteOrder = raw.byteOrder
        )
    }

    override suspend fun openConnection(request: HttpRequest): QtHttpConnectionNativeImpl {
        val nativeRequest = request as? QtHttpRequestNativeImpl ?: QtHttpRequestNativeImpl(
            url = request.url,
            method = request.method,
            headers = request.headers,
            initialBodyBytes = request.bodyBytes,
            timeoutMs = request.timeoutMs,
            followRedirects = request.followRedirects,
            byteOrder = request.byteOrder
        )
        val response = execute(nativeRequest)
        return QtHttpConnectionNativeImpl(nativeRequest, response)
    }

    override fun dispose() {
        capturedRequests.clear()
        super.dispose()
    }
}
