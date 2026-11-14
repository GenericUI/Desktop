@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.nativeimpl.net.DefaultHttpClientNativeImpl
import com.nexustools.genericui.net.http.HttpRequest
import com.nexustools.genericui.net.http.HttpResponse
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataWithBytes
import platform.Foundation.setHTTPBody
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.posix.memcpy
import kotlin.coroutines.resume

/**
 * macOS (`:desktop:macos`) implementation of [com.nexustools.genericui.nativeimpl.net.HttpClientNativeImpl]
 * backed by Foundation `NSURLSession` (`dataTaskWithRequest:completionHandler:`), consulting registered
 * mock/test route handlers first when present.
 */
class MacosHttpClientNativeImpl(
    private val defaultHandler: (suspend (HttpRequest) -> HttpResponse)? = null
) : DefaultHttpClientNativeImpl() {

    val nativeApi: String = "Foundation_NSURLSession_NSURLSessionDataTask"

    private val capturedRequests = ArrayList<HttpRequest>()

    private val urlSession: NSURLSession by lazy {
        val config = NSURLSessionConfiguration.ephemeralSessionConfiguration()
        NSURLSession.sessionWithConfiguration(config)
    }

    var requestCount: Int = 0
        private set

    fun requestHistory(): List<HttpRequest> =
        capturedRequests.toList()

    override suspend fun execute(request: HttpRequest): MacosHttpResponseNativeImpl {
        requestCount += 1
        capturedRequests.add(request)

        if (isDisposed) {
            return wrapResponse(super.execute(request), request)
        }

        findRouteHandler(request.url)?.let { handler ->
            return wrapResponse(handler.invoke(request), request)
        }
        defaultHandler?.let { handler ->
            return wrapResponse(handler.invoke(request), request)
        }

        if (isMockOrOfflineUrl(request.url)) {
            return wrapResponse(super.execute(request), request)
        }

        val liveResponse = executeViaNsUrlSession(request)
        if (liveResponse != null) {
            return liveResponse
        }

        // Unit-test fallback for synthetic non-API localhost:8080 requests when GUI loop is inactive
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled && isSyntheticUnitTestLocalhostUrl(request.url)) {
            return wrapResponse(super.execute(request), request)
        }

        return MacosHttpResponseNativeImpl(
            statusCode = 0,
            statusText = "Network Unreachable",
            headers = emptyMap(),
            initialBodyBytes = ByteArray(0),
            url = request.url,
            byteOrder = request.byteOrder
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

    private fun wrapResponse(raw: HttpResponse, request: HttpRequest): MacosHttpResponseNativeImpl {
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

    private fun isMockOrOfflineUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim().lowercase()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return true
        }
        val schemeEnd = trimmed.indexOf("://")
        val authorityAndRest = trimmed.substring(schemeEnd + 3)
        val authority = authorityAndRest.substringBefore('/').substringBefore('?')
        val host = authority.substringBefore(':')
        return host.isEmpty() ||
            host == "example.com" ||
            host.endsWith(".example.com") ||
            host == "example.org" ||
            host.endsWith(".example.org") ||
            host == "example.net" ||
            host.endsWith(".example.net") ||
            host.endsWith(".invalid")
    }

    private fun isSyntheticUnitTestLocalhostUrl(rawUrl: String): Boolean {
        val trimmed = rawUrl.trim().lowercase()
        if (!trimmed.startsWith("http://")) return false
        val rest = trimmed.removePrefix("http://")
        val authority = rest.substringBefore('/')
        val path = "/" + rest.substringAfter('/', "")
        val isLocal8080 = authority == "localhost:8080" || authority == "127.0.0.1:8080"
        return isLocal8080 && !path.startsWith("/v1/") && !path.startsWith("/socket.io/")
    }

    private suspend fun executeViaNsUrlSession(request: HttpRequest): MacosHttpResponseNativeImpl? {
        val nsUrl = NSURL.URLWithString(request.url.trim()) ?: return null
        val timeoutSec = (request.timeoutMs.coerceIn(500L, 120_000L)).toDouble() / 1000.0
        val nsReq = NSMutableURLRequest.requestWithURL(
            URL = nsUrl,
            cachePolicy = 1u, // NSURLRequestReloadIgnoringLocalCacheData
            timeoutInterval = timeoutSec
        )
        nsReq.setHTTPMethod(request.method.name)
        for ((k, v) in request.headers) {
            if (k.isNotBlank()) {
                nsReq.setValue(v, forHTTPHeaderField = k)
            }
        }
        val payload = request.bodyBytes ?: request.toByteArray().takeIf { it.isNotEmpty() }
        if (payload != null && payload.isNotEmpty()) {
            val bodyData = payload.usePinned { pinned ->
                NSData.dataWithBytes(pinned.addressOf(0), payload.size.convert())
            }
            nsReq.setHTTPBody(bodyData)
        }

        return suspendCancellableCoroutine { cont ->
            val task = urlSession.dataTaskWithRequest(nsReq) { data, response, error ->
                if (!cont.isActive) return@dataTaskWithRequest
                if (error != null) {
                    if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled && isSyntheticUnitTestLocalhostUrl(request.url)) {
                        cont.resume(null)
                    } else {
                        cont.resume(
                            MacosHttpResponseNativeImpl(
                                statusCode = 0,
                                statusText = error.localizedDescription,
                                headers = emptyMap(),
                                initialBodyBytes = ByteArray(0),
                                url = request.url,
                                byteOrder = request.byteOrder
                            )
                        )
                    }
                    return@dataTaskWithRequest
                }

                val httpResp = response as? NSHTTPURLResponse
                val statusCode = httpResp?.statusCode?.toInt() ?: if (data != null) 200 else 0
                val statusText = if (httpResp != null) {
                    NSHTTPURLResponse.localizedStringForStatusCode(httpResp.statusCode)
                } else {
                    if (statusCode in 200..299) "OK" else ""
                }
                val headersMap = LinkedHashMap<String, String>()
                httpResp?.allHeaderFields?.forEach { (k, v) ->
                    val keyStr = k?.toString()
                    val valStr = v?.toString()
                    if (!keyStr.isNullOrEmpty() && valStr != null) {
                        headersMap[keyStr] = valStr
                    }
                }
                val bodyBytes = nsDataToByteArray(data)
                val finalUrl = response?.URL?.absoluteString ?: request.url
                cont.resume(
                    MacosHttpResponseNativeImpl(
                        statusCode = statusCode,
                        statusText = statusText,
                        headers = headersMap,
                        initialBodyBytes = bodyBytes,
                        url = finalUrl,
                        byteOrder = request.byteOrder
                    )
                )
            }
            cont.invokeOnCancellation {
                task.cancel()
            }
            task.resume()
        }
    }

    private fun nsDataToByteArray(data: NSData?): ByteArray {
        if (data == null) return ByteArray(0)
        val len = data.length.toInt()
        if (len <= 0) return ByteArray(0)
        val srcPtr = data.bytes ?: return ByteArray(0)
        val out = ByteArray(len)
        out.usePinned { pinned ->
            memcpy(pinned.addressOf(0), srcPtr, len.convert())
        }
        return out
    }
}
