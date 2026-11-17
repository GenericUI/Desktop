package com.nexustools.genericui.swing

import com.nexustools.genericui.io.ByteOrder
import com.nexustools.genericui.jvm.JvmHttpUrlConnectionClient
import com.nexustools.genericui.net.http.HttpMethod
import com.nexustools.genericui.net.http.HttpRequest
import com.nexustools.genericui.net.http.HttpResponse
import javax.swing.SwingUtilities

/**
 * Java Swing / JVM implementation of [com.nexustools.genericui.nativeimpl.net.HttpClientNativeImpl]
 * extending [JvmHttpUrlConnectionClient].
 */
class SwingHttpClientNativeImpl(
    defaultHandler: (suspend (HttpRequest) -> HttpResponse)? = null
) : JvmHttpUrlConnectionClient(
    defaultHandler = defaultHandler,
    isOnMainThread = { SwingUtilities.isEventDispatchThread() }
) {
    override val nativeApi: String = "Java_Net_HttpClient_HttpURLConnection"

    override fun createRequest(
        url: String,
        method: HttpMethod,
        headers: Map<String, String>,
        initialBodyBytes: ByteArray?,
        timeoutMs: Long,
        followRedirects: Boolean,
        byteOrder: ByteOrder
    ): SwingHttpRequestNativeImpl = SwingHttpRequestNativeImpl(
        url = url,
        method = method,
        headers = headers,
        initialBodyBytes = initialBodyBytes,
        timeoutMs = timeoutMs,
        followRedirects = followRedirects,
        byteOrder = byteOrder
    )

    override fun createResponse(
        statusCode: Int,
        statusText: String,
        headers: Map<String, String>,
        initialBodyBytes: ByteArray,
        url: String,
        byteOrder: ByteOrder
    ): SwingHttpResponseNativeImpl = SwingHttpResponseNativeImpl(
        statusCode = statusCode,
        statusText = statusText,
        headers = headers,
        initialBodyBytes = initialBodyBytes,
        url = url,
        byteOrder = byteOrder
    )

    override fun createConnection(
        request: HttpRequest,
        response: HttpResponse
    ): SwingHttpConnectionNativeImpl = SwingHttpConnectionNativeImpl(
        request = request,
        response = response
    )

    override fun wrapRequest(request: HttpRequest): SwingHttpRequestNativeImpl =
        request as? SwingHttpRequestNativeImpl ?: createRequest(
            url = request.url,
            method = request.method,
            headers = request.headers,
            initialBodyBytes = request.bodyBytes,
            timeoutMs = request.timeoutMs,
            followRedirects = request.followRedirects,
            byteOrder = request.byteOrder
        )

    override fun wrapResponse(raw: HttpResponse, request: HttpRequest): SwingHttpResponseNativeImpl =
        raw as? SwingHttpResponseNativeImpl ?: createResponse(
            statusCode = raw.statusCode,
            statusText = raw.statusText,
            headers = raw.headers,
            initialBodyBytes = raw.bodyBytes,
            url = raw.url.ifEmpty { request.url },
            byteOrder = raw.byteOrder
        )

    override suspend fun execute(request: HttpRequest): SwingHttpResponseNativeImpl =
        super.execute(request) as SwingHttpResponseNativeImpl

    override suspend fun openConnection(request: HttpRequest): SwingHttpConnectionNativeImpl =
        super.openConnection(request) as SwingHttpConnectionNativeImpl
}
