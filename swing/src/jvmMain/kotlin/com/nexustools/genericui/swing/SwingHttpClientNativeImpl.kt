package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.net.DefaultHttpClientNativeImpl
import com.nexustools.genericui.net.http.HttpRequest
import com.nexustools.genericui.net.http.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse as JdkHttpResponse
import java.time.Duration
import javax.swing.SwingUtilities
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Java Swing / JVM (`:desktop:swing`) implementation of [com.nexustools.genericui.nativeimpl.net.HttpClientNativeImpl]
 * backed by `java.net.http.HttpClient.sendAsync(...)` awaited non-blockingly via coroutines, consulting
 * [DefaultHttpClientNativeImpl] mock routes first when registered in unit tests.
 */
class SwingHttpClientNativeImpl(
    private val defaultHandler: (suspend (HttpRequest) -> HttpResponse)? = null
) : DefaultHttpClientNativeImpl() {

    val nativeApi: String = "Java_Net_HttpClient_HttpURLConnection"

    private val capturedRequests = ArrayList<HttpRequest>()

    var requestCount: Int = 0
        private set

    fun requestHistory(): List<HttpRequest> =
        capturedRequests.toList()

    private val followingJdkClient: JdkHttpClient by lazy {
        JdkHttpClient.newBuilder()
            .followRedirects(JdkHttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }

    private val nonFollowingJdkClient: JdkHttpClient by lazy {
        JdkHttpClient.newBuilder()
            .followRedirects(JdkHttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }

    override suspend fun execute(request: HttpRequest): SwingHttpResponseNativeImpl {
        requestCount += 1
        capturedRequests.add(request)
        if (isDisposed) {
            return SwingHttpResponseNativeImpl(
                statusCode = 499,
                statusText = "Client Closed Request",
                url = request.url,
                byteOrder = request.byteOrder
            )
        }

        val matchedRoute = findRouteHandler(request.url) ?: defaultHandler
        if (matchedRoute != null || isMockOrOfflineUrl(request.url)) {
            val raw = matchedRoute?.invoke(request) ?: super.execute(request)
            if (raw is SwingHttpResponseNativeImpl) return raw
            return SwingHttpResponseNativeImpl(
                statusCode = raw.statusCode,
                statusText = raw.statusText,
                headers = raw.headers,
                initialBodyBytes = raw.bodyBytes,
                url = raw.url.ifEmpty { request.url },
                byteOrder = raw.byteOrder
            )
        }

        val jdkRequest = buildJdkRequest(request) ?: return wrapSwingResponse(super.execute(request), request)
        val client = if (request.followRedirects) followingJdkClient else nonFollowingJdkClient

        return try {
            val jdkResponse = awaitJdkSendAsync(
                client = client,
                jdkRequest = jdkRequest,
                bodyHandler = JdkHttpResponse.BodyHandlers.ofByteArray()
            )
            val headersMap = LinkedHashMap<String, String>()
            for ((k, values) in jdkResponse.headers().map()) {
                if (values.isNotEmpty()) {
                    headersMap[k] = values.joinToString(", ")
                }
            }
            val bodyBytes = jdkResponse.body() ?: ByteArray(0)
            SwingHttpResponseNativeImpl(
                statusCode = jdkResponse.statusCode(),
                statusText = statusTextForCode(jdkResponse.statusCode()),
                headers = headersMap,
                initialBodyBytes = bodyBytes,
                url = jdkResponse.uri()?.toString() ?: request.url,
                byteOrder = request.byteOrder
            )
        } catch (e: Throwable) {
            if (request.headers.containsKey("X-Episode-Caster-Client-Id") || request.url.contains("/v1/")) {
                return SwingHttpResponseNativeImpl(
                    statusCode = 0,
                    statusText = e.message ?: "Connection failed",
                    headers = emptyMap(),
                    initialBodyBytes = ByteArray(0),
                    url = request.url,
                    byteOrder = request.byteOrder
                )
            }
            wrapSwingResponse(super.execute(request), request)
        }
    }

    override suspend fun openConnection(request: HttpRequest): SwingHttpConnectionNativeImpl {
        val nativeRequest = request as? SwingHttpRequestNativeImpl ?: SwingHttpRequestNativeImpl(
            url = request.url,
            method = request.method,
            headers = request.headers,
            initialBodyBytes = request.bodyBytes,
            timeoutMs = request.timeoutMs,
            followRedirects = request.followRedirects,
            byteOrder = request.byteOrder
        )
        val response = execute(nativeRequest)
        return SwingHttpConnectionNativeImpl(nativeRequest, response)
    }

    override fun dispose() {
        capturedRequests.clear()
        super.dispose()
    }

    private fun wrapSwingResponse(raw: HttpResponse, request: HttpRequest): SwingHttpResponseNativeImpl {
        if (raw is SwingHttpResponseNativeImpl) return raw
        return SwingHttpResponseNativeImpl(
            statusCode = raw.statusCode,
            statusText = raw.statusText,
            headers = raw.headers,
            initialBodyBytes = raw.bodyBytes,
            url = raw.url.ifEmpty { request.url },
            byteOrder = raw.byteOrder
        )
    }

    private fun buildJdkRequest(request: HttpRequest): JdkHttpRequest? = runCatching {
        val uri = URI.create(request.url.trim())
        val builder = JdkHttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(request.timeoutMs.coerceIn(100L, 120_000L)))
        for ((k, v) in request.headers) {
            if (k.isNotBlank()) {
                builder.header(k.trim(), v)
            }
        }
        val bodyBytes = request.bodyBytes
        val publisher = if (bodyBytes != null && bodyBytes.isNotEmpty()) {
            JdkHttpRequest.BodyPublishers.ofByteArray(bodyBytes)
        } else {
            JdkHttpRequest.BodyPublishers.noBody()
        }
        builder.method(request.method.name, publisher).build()
    }.getOrNull()

    private suspend fun <T> awaitJdkSendAsync(
        client: JdkHttpClient,
        jdkRequest: JdkHttpRequest,
        bodyHandler: JdkHttpResponse.BodyHandler<T>
    ): JdkHttpResponse<T> {
        val hasInterceptor = coroutineContext[ContinuationInterceptor] != null
        return if (hasInterceptor || SwingUtilities.isEventDispatchThread()) {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { cont ->
                    val future = client.sendAsync(jdkRequest, bodyHandler)
                    cont.invokeOnCancellation { future.cancel(true) }
                    future.whenComplete { resp, err ->
                        if (err != null) {
                            if (cont.isActive) cont.resumeWithException(err)
                        } else if (resp != null) {
                            if (cont.isActive) cont.resume(resp)
                        }
                    }
                }
            }
        } else {
            client.send(jdkRequest, bodyHandler)
        }
    }

    private fun isMockOrOfflineUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".example.com") || lower.contains(".example.org") || lower.contains(".invalid")
    }

    private fun statusTextForCode(code: Int): String = when (code) {
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        204 -> "No Content"
        301 -> "Moved Permanently"
        302 -> "Found"
        304 -> "Not Modified"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> ""
    }
}
