package com.nexustools.genericui.macos

import com.nexustools.genericui.nativeimpl.net.DefaultHttpClientNativeImpl
import com.nexustools.genericui.net.http.HttpRequest
import com.nexustools.genericui.net.http.HttpResponse

/**
 * macOS (`:desktop:macos`) implementation of [com.nexustools.genericui.nativeimpl.net.HttpClientNativeImpl]
 * backed by Foundation `NSURLSession` (`dataTaskWithRequest:completionHandler:` and streaming `NSURLSessionDataDelegate`).
 */
class MacosHttpClientNativeImpl(
    private val defaultHandler: (suspend (HttpRequest) -> HttpResponse)? = null
) : DefaultHttpClientNativeImpl() {

    val nativeApi: String = "Foundation_NSURLSession_NSURLSessionDataTask"

    private val capturedRequests = ArrayList<HttpRequest>()

    var requestCount: Int = 0
        private set

    fun requestHistory(): List<HttpRequest> =
        capturedRequests.toList()

    override suspend fun execute(request: HttpRequest): MacosHttpResponseNativeImpl {
        requestCount += 1
        capturedRequests.add(request)
        val raw = if (!isDisposed) {
            findRouteHandler(request.url)?.invoke(request)
                ?: defaultHandler?.invoke(request)
                ?: super.execute(request)
        } else {
            super.execute(request)
        }
        if (raw is MacosHttpResponseNativeImpl) return raw
        return MacosHttpResponseNativeImpl(
            statusCode = raw.statusCode,
            statusText = raw.statusText,
            headers = raw.headers,
            initialBodyBytes = raw.bodyBytes,
            url = raw.url.ifEmpty { request.url },
            byteOrder = raw.byteOrder
        )
    }

    override suspend fun openConnection(request: HttpRequest): MacosHttpConnectionNativeImpl {
        val nativeRequest = request as? MacosHttpRequestNativeImpl ?: MacosHttpRequestNativeImpl(
            url = request.url,
            method = request.method,
            headers = request.headers,
            initialBodyBytes = request.bodyBytes,
            timeoutMs = request.timeoutMs,
            followRedirects = request.followRedirects,
            byteOrder = request.byteOrder
        )
        val response = execute(nativeRequest)
        return MacosHttpConnectionNativeImpl(nativeRequest, response)
    }

    override fun dispose() {
        capturedRequests.clear()
        super.dispose()
    }
}
