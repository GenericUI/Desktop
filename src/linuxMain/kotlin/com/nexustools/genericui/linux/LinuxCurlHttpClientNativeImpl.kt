@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.nativeimpl.net.HttpClientNativeImpl
import com.nexustools.genericui.nativeimpl.net.HttpConnectionNativeImpl
import com.nexustools.genericui.net.http.HttpMethod
import com.nexustools.genericui.net.http.HttpRequest
import com.nexustools.genericui.net.http.HttpResponse
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.memcpy

class LinuxCurlHttpClientNativeImpl : HttpClientNativeImpl {
    private val lock = LinuxReentrantLock()
    private val routes = LinkedHashMap<String, suspend (HttpRequest) -> HttpResponse>()
    private var disposedState = false

    override val isDisposed: Boolean
        get() = lock.withLock { disposedState }

    override fun registerRoute(urlPattern: String, handler: suspend (HttpRequest) -> HttpResponse) {
        lock.withLock {
            if (!disposedState) {
                routes[urlPattern] = handler
            }
        }
    }

    override fun unregisterRoute(urlPattern: String) {
        lock.withLock {
            routes.remove(urlPattern)
        }
    }

    override fun clearRoutes() {
        lock.withLock {
            routes.clear()
        }
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        if (isDisposed) {
            return LinuxHttpResponseNativeImpl(
                statusCode = 0,
                statusText = "HttpClient is disposed",
                url = request.url,
                byteOrder = request.byteOrder
            )
        }

        findRouteHandler(request.url)?.let { handler ->
            return handler(request)
        }

        return withContext(Dispatchers.IO) {
            executeViaLibCurl(request)
        }
    }

    override suspend fun openConnection(request: HttpRequest): HttpConnectionNativeImpl {
        val linuxReq = (request as? LinuxHttpRequestNativeImpl) ?: LinuxHttpRequestNativeImpl(
            url = request.url,
            method = request.method,
            headers = request.headers,
            initialBodyBytes = request.bodyBytes,
            timeoutMs = request.timeoutMs,
            followRedirects = request.followRedirects,
            byteOrder = request.byteOrder
        )
        val response = execute(linuxReq)
        return LinuxHttpConnectionNativeImpl(linuxReq, response)
    }

    override fun dispose() {
        lock.withLock {
            disposedState = true
            routes.clear()
        }
    }

    private fun findRouteHandler(url: String): (suspend (HttpRequest) -> HttpResponse)? {
        return lock.withLock {
            routes[url]
                ?: routes[url.substringBefore('?')]
                ?: routes.entries.firstOrNull { (pattern, _) -> matchesPattern(pattern, url) }?.value
        }
    }

    private fun matchesPattern(pattern: String, url: String): Boolean {
        if (pattern == "*") return true
        if (pattern.endsWith("*")) {
            return url.startsWith(pattern.dropLast(1))
        }
        return url == pattern || url.substringBefore('?') == pattern
    }

    private fun executeViaLibCurl(request: HttpRequest): HttpResponse {
        if (!LinuxCurlBindings.ensureLoaded()) {
            return LinuxHttpResponseNativeImpl(
                statusCode = 0,
                statusText = "libcurl.so.4 unavailable",
                url = request.url,
                byteOrder = request.byteOrder
            )
        }
        val curl = LinuxCurlBindings.easyInit()
            ?: return LinuxHttpResponseNativeImpl(
                statusCode = 0,
                statusText = "curl_easy_init failed",
                url = request.url,
                byteOrder = request.byteOrder
            )

        val capture = CurlTransferCapture()
        val captureRef = StableRef.create(capture)
        var headerSlist: COpaquePointer? = null

        try {
            LinuxCurlBindings.easySetOptString(curl, LinuxCurlBindings.CURLOPT_URL, request.url)
            LinuxCurlBindings.easySetOptLong(curl, LinuxCurlBindings.CURLOPT_NOSIGNAL, 1L)
            LinuxCurlBindings.easySetOptLong(
                curl,
                LinuxCurlBindings.CURLOPT_FOLLOWLOCATION,
                if (request.followRedirects) 1L else 0L
            )
            val timeoutMs = request.timeoutMs.coerceAtLeast(1L)
            LinuxCurlBindings.easySetOptLong(curl, LinuxCurlBindings.CURLOPT_TIMEOUT_MS, timeoutMs)
            LinuxCurlBindings.easySetOptLong(curl, LinuxCurlBindings.CURLOPT_CONNECTTIMEOUT_MS, minOf(timeoutMs, 15_000L))
            LinuxCurlBindings.easySetOptLong(curl, LinuxCurlBindings.CURLOPT_SSL_VERIFYPEER, 1L)
            LinuxCurlBindings.easySetOptLong(curl, LinuxCurlBindings.CURLOPT_SSL_VERIFYHOST, 2L)

            if (request.method == HttpMethod.HEAD) {
                LinuxCurlBindings.easySetOptLong(curl, LinuxCurlBindings.CURLOPT_NOBODY, 1L)
            } else {
                LinuxCurlBindings.easySetOptString(curl, LinuxCurlBindings.CURLOPT_CUSTOMREQUEST, request.method.name)
            }

            for ((name, value) in request.headers) {
                headerSlist = LinuxCurlBindings.slistAppend(headerSlist, "$name: $value")
            }
            if (headerSlist != null) {
                LinuxCurlBindings.easySetOptPointer(curl, LinuxCurlBindings.CURLOPT_HTTPHEADER, headerSlist)
            }

            val writeCb = staticCFunction { ptr: CPointer<ByteVar>?, size: ULong, nmemb: ULong, userdata: COpaquePointer? ->
                val total = size * nmemb
                val count = total.toInt()
                if (ptr != null && userdata != null && count > 0) {
                    val state = userdata.asStableRef<CurlTransferCapture>().get()
                    val chunk = ByteArray(count)
                    chunk.usePinned { pinned ->
                        memcpy(pinned.addressOf(0), ptr, total.convert())
                    }
                    state.appendBody(chunk)
                }
                total
            }

            val headerCb = staticCFunction { ptr: CPointer<ByteVar>?, size: ULong, nmemb: ULong, userdata: COpaquePointer? ->
                val total = size * nmemb
                val count = total.toInt()
                if (ptr != null && userdata != null && count > 0) {
                    val state = userdata.asStableRef<CurlTransferCapture>().get()
                    val raw = ByteArray(count)
                    raw.usePinned { pinned ->
                        memcpy(pinned.addressOf(0), ptr, total.convert())
                    }
                    state.appendHeaderLine(raw.decodeToString())
                }
                total
            }

            LinuxCurlBindings.easySetOptPointer(curl, LinuxCurlBindings.CURLOPT_WRITEFUNCTION, writeCb)
            LinuxCurlBindings.easySetOptPointer(curl, LinuxCurlBindings.CURLOPT_WRITEDATA, captureRef.asCPointer())
            LinuxCurlBindings.easySetOptPointer(curl, LinuxCurlBindings.CURLOPT_HEADERFUNCTION, headerCb)
            LinuxCurlBindings.easySetOptPointer(curl, LinuxCurlBindings.CURLOPT_HEADERDATA, captureRef.asCPointer())

            val body = request.bodyBytes
            val rc = if (body != null && (body.isNotEmpty() || request.method == HttpMethod.POST || request.method == HttpMethod.PUT || request.method == HttpMethod.PATCH)) {
                body.usePinned { pinned ->
                    val dataPtr = if (body.isNotEmpty()) pinned.addressOf(0) else null
                    LinuxCurlBindings.easySetOptPointer(curl, LinuxCurlBindings.CURLOPT_POSTFIELDS, dataPtr)
                    LinuxCurlBindings.easySetOptLong(curl, LinuxCurlBindings.CURLOPT_POSTFIELDSIZE_LARGE, body.size.toLong())
                    LinuxCurlBindings.easyPerform(curl)
                }
            } else {
                LinuxCurlBindings.easyPerform(curl)
            }

            if (rc != 0) {
                return LinuxHttpResponseNativeImpl(
                    statusCode = 0,
                    statusText = LinuxCurlBindings.easyStrerror(rc),
                    headers = capture.headers,
                    initialBodyBytes = capture.bodyBytes(),
                    url = request.url,
                    byteOrder = request.byteOrder
                )
            }

            val statusCode = LinuxCurlBindings.easyGetResponseCode(curl).toInt()
            val effectiveUrl = LinuxCurlBindings.easyGetEffectiveUrl(curl) ?: request.url
            return LinuxHttpResponseNativeImpl(
                statusCode = statusCode,
                statusText = capture.statusText,
                headers = capture.headers.toMap(),
                initialBodyBytes = capture.bodyBytes(),
                url = effectiveUrl,
                byteOrder = request.byteOrder
            )
        } finally {
            LinuxCurlBindings.slistFreeAll(headerSlist)
            captureRef.dispose()
            LinuxCurlBindings.easyCleanup(curl)
        }
    }

    private class CurlTransferCapture {
        private val bodyChunks = ArrayList<ByteArray>()
        private var totalSize = 0
        var statusText: String = ""
        val headers = LinkedHashMap<String, String>()

        fun appendBody(chunk: ByteArray) {
            bodyChunks.add(chunk)
            totalSize += chunk.size
        }

        fun appendHeaderLine(rawLine: String) {
            val line = rawLine.trimEnd('\r', '\n')
            if (line.isEmpty()) return
            if (line.startsWith("HTTP/", ignoreCase = true)) {
                headers.clear()
                val firstSpace = line.indexOf(' ')
                if (firstSpace > 0) {
                    val afterProto = line.substring(firstSpace + 1).trimStart()
                    val secondSpace = afterProto.indexOf(' ')
                    statusText = if (secondSpace > 0) afterProto.substring(secondSpace + 1).trim() else ""
                } else {
                    statusText = ""
                }
                return
            }
            val colon = line.indexOf(':')
            if (colon > 0) {
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                val existing = headers[key]
                headers[key] = if (existing == null) value else "$existing, $value"
            }
        }

        fun bodyBytes(): ByteArray {
            if (totalSize == 0) return ByteArray(0)
            val result = ByteArray(totalSize)
            var offset = 0
            for (chunk in bodyChunks) {
                chunk.copyInto(result, offset)
                offset += chunk.size
            }
            return result
        }
    }
}
