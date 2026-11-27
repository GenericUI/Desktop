package com.nexustools.genericui.windows

import com.nexustools.genericui.nativeimpl.net.DefaultHttpClientNativeImpl
import com.nexustools.genericui.net.http.HttpMethod
import com.nexustools.genericui.net.http.HttpRequest
import com.nexustools.genericui.net.http.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsHttpClientRedirectAndTlsTest {

    @AfterTest
    fun tearDown() {
        DefaultHttpClientNativeImpl.clearStaticRoutes()
    }

    @Test
    fun testRedirectUrlResolutionForRelativeProtocolAndAbsoluteLocations() {
        assertEquals(
            "https://www.youtube.com/watch?v=1",
            WindowsHttpClientNativeImpl.resolveRedirectUrl(
                "https://youtube.com/",
                "https://www.youtube.com/watch?v=1"
            )
        )
        assertEquals(
            "https://cdn.youtube.com/player.js",
            WindowsHttpClientNativeImpl.resolveRedirectUrl(
                "https://youtube.com/watch?v=1",
                "//cdn.youtube.com/player.js"
            )
        )
        assertEquals(
            "https://youtube.com/watch?v=abc",
            WindowsHttpClientNativeImpl.resolveRedirectUrl(
                "https://youtube.com/old/path?old=1",
                "/watch?v=abc"
            )
        )
        assertEquals(
            "https://youtube.com/watch?v=next",
            WindowsHttpClientNativeImpl.resolveRedirectUrl(
                "https://youtube.com/watch?v=old",
                "?v=next"
            )
        )
        assertEquals(
            "https://youtube.com/v2/items?a=1",
            WindowsHttpClientNativeImpl.resolveRedirectUrl(
                "https://youtube.com/v1/nested/page?x=9",
                "../../v2/items?a=1"
            )
        )
    }

    @Test
    fun testCrossOriginSensitiveHeaderStrippingAndRedirectMethodConversion() {
        assertFalse(
            WindowsHttpClientNativeImpl.shouldStripSensitiveHeaders(
                "https://youtube.com/a",
                "https://youtube.com/b"
            )
        )
        assertTrue(
            WindowsHttpClientNativeImpl.shouldStripSensitiveHeaders(
                "https://youtube.com/a",
                "https://www.youtube.com/b"
            )
        )
        assertTrue(
            WindowsHttpClientNativeImpl.shouldStripSensitiveHeaders(
                "https://youtube.com/a",
                "http://youtube.com/b"
            )
        )
        assertTrue(
            WindowsHttpClientNativeImpl.shouldStripSensitiveHeaders(
                "https://youtube.com/a",
                "https://youtube.com:8443/b"
            )
        )

        val postHeaders = mapOf(
            "Authorization" to "Bearer secret-token",
            "Cookie" to "sid=abc123",
            "Proxy-Authorization" to "Basic xyz",
            "Content-Type" to "application/json",
            "Content-Length" to "11",
            "Accept" to "application/json"
        )
        val postReq = HttpRequest(
            url = "https://auth.example.com/submit",
            method = HttpMethod.POST,
            headers = postHeaders,
            bodyBytes = "{\"x\":1}".encodeToByteArray()
        )

        // 302 POST -> GET cross-host strips Authorization/Cookie/Proxy-Authorization and drops Content-* headers
        val r302Cross = WindowsHttpClientNativeImpl.nextRedirectRequest(
            postReq,
            HttpResponse(statusCode = 302, url = postReq.url),
            "https://other.example.net/done"
        )
        assertNotNull(r302Cross)
        assertEquals(HttpMethod.GET, r302Cross.method)
        assertNull(r302Cross.bodyBytes)
        assertEquals("https://other.example.net/done", r302Cross.url)
        assertFalse(r302Cross.headers.keys.any { it.equals("Authorization", ignoreCase = true) })
        assertFalse(r302Cross.headers.keys.any { it.equals("Cookie", ignoreCase = true) })
        assertFalse(r302Cross.headers.keys.any { it.equals("Proxy-Authorization", ignoreCase = true) })
        assertFalse(r302Cross.headers.keys.any { it.equals("Content-Type", ignoreCase = true) })
        assertEquals("application/json", r302Cross.headers["Accept"])

        // 308 POST same-origin preserves method, body, Authorization, and Cookie
        val r308Same = WindowsHttpClientNativeImpl.nextRedirectRequest(
            postReq,
            HttpResponse(statusCode = 308, url = postReq.url),
            "/submit-v2"
        )
        assertNotNull(r308Same)
        assertEquals(HttpMethod.POST, r308Same.method)
        assertEquals("{\"x\":1}", r308Same.bodyBytes?.decodeToString())
        assertEquals("https://auth.example.com/submit-v2", r308Same.url)
        assertEquals("Bearer secret-token", r308Same.headers["Authorization"])
        assertEquals("sid=abc123", r308Same.headers["Cookie"])
    }

    @Test
    fun testWireSerializationUserAgentChunkedParsingAndMaxBodyLimit() {
        val parsedUrl = WindowsHttpClientNativeImpl.parseHttpUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ#t=42")
        assertNotNull(parsedUrl)
        assertEquals("https", parsedUrl.scheme)
        assertEquals("www.youtube.com", parsedUrl.host)
        assertEquals(443, parsedUrl.port)
        assertEquals("/watch?v=dQw4w9WgXcQ", parsedUrl.wireRequestTarget)

        val defaultReq = HttpRequest(url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ#t=42")
        val wireText = WindowsHttpClientNativeImpl.buildHttp1WireHeaderBytes(defaultReq, parsedUrl).decodeToString()
        assertTrue(wireText.startsWith("GET /watch?v=dQw4w9WgXcQ HTTP/1.1\r\n"))
        assertTrue(wireText.contains("Host: www.youtube.com\r\n"))
        assertTrue(wireText.contains("User-Agent: ${WindowsHttpClientNativeImpl.DEFAULT_USER_AGENT}\r\n"))

        val customReq = HttpRequest(
            url = "https://www.youtube.com/",
            headers = mapOf("User-Agent" to "CustomBrowser/2.0")
        )
        val customWire = WindowsHttpClientNativeImpl.buildHttp1WireHeaderBytes(customReq, parsedUrl).decodeToString()
        assertTrue(customWire.contains("User-Agent: CustomBrowser/2.0\r\n"))
        assertFalse(customWire.contains(WindowsHttpClientNativeImpl.DEFAULT_USER_AGENT))

        val chunkedRaw = (
            "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Type: text/plain\r\n\r\n" +
                "5;ext=1\r\nHello\r\n" +
                "7\r\nWinSock\r\n" +
                "0\r\n\r\n"
            ).encodeToByteArray()
        val bodyStart = chunkedRaw.decodeToString().indexOf("\r\n\r\n") + 4
        assertTrue(WindowsHttpClientNativeImpl.isChunkedStreamComplete(chunkedRaw, bodyStart, chunkedRaw.size))
        assertFalse(WindowsHttpClientNativeImpl.isChunkedStreamComplete(chunkedRaw, bodyStart, chunkedRaw.size - 5))

        val parsedResp = WindowsHttpClientNativeImpl.parseHttpResponseBytes(chunkedRaw, chunkedRaw.size, defaultReq)
        assertNotNull(parsedResp)
        assertEquals(200, parsedResp.statusCode)
        assertEquals("HelloWinSock", parsedResp.bodyBytes.decodeToString())

        val oversizedHeader = (
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: ${WindowsHttpClientNativeImpl.MAX_RESPONSE_BODY_BYTES + 1}\r\n\r\n"
            ).encodeToByteArray()
        val rejected = WindowsHttpClientNativeImpl.parseHttpResponseBytes(oversizedHeader, oversizedHeader.size, defaultReq)
        assertNotNull(rejected)
        assertEquals(0, rejected.statusCode)
        assertTrue(rejected.statusText.contains("exceeds max size", ignoreCase = true))
    }

    @Test
    fun testExecuteMockRedirectsMaxHopsAndRealHttpsRouting() = runBlocking {
        val observedHops = ArrayList<HttpRequest>()
        val client = WindowsHttpClientNativeImpl { req ->
            observedHops.add(req)
            when (req.url) {
                "http://shop.example.com/start" -> HttpResponse(
                    statusCode = 302,
                    statusText = "Found",
                    headers = mapOf("Location" to "/step2"),
                    url = req.url
                )
                "http://shop.example.com/step2" -> HttpResponse(
                    statusCode = 301,
                    statusText = "Moved Permanently",
                    headers = mapOf("Location" to "https://cdn.example.net/final"),
                    url = req.url
                )
                "https://cdn.example.net/final" -> HttpResponse(
                    statusCode = 200,
                    statusText = "OK",
                    bodyBytes = "redirect-ok".encodeToByteArray(),
                    url = req.url
                )
                else -> HttpResponse(
                    statusCode = 302,
                    statusText = "Loop",
                    headers = mapOf("Location" to req.url),
                    url = req.url
                )
            }
        }
        try {
            // 1. Multi-hop mock redirect preserving auth same-origin then stripping cross-origin & converting POST->GET
            val resp = client.execute(
                HttpRequest(
                    url = "http://shop.example.com/start",
                    method = HttpMethod.POST,
                    headers = mapOf(
                        "Authorization" to "Bearer secret",
                        "Cookie" to "a=1",
                        "X-Keep" to "yes"
                    ),
                    bodyBytes = "payload".encodeToByteArray()
                )
            )
            assertEquals(200, resp.statusCode)
            assertEquals("redirect-ok", resp.bodyBytes.decodeToString())
            assertEquals("https://cdn.example.net/final", resp.url)
            assertEquals(3, observedHops.size)
            assertEquals(HttpMethod.GET, observedHops[1].method)
            assertEquals("Bearer secret", observedHops[1].headers["Authorization"])
            assertFalse(observedHops[2].headers.keys.any { it.equals("Authorization", ignoreCase = true) })
            assertFalse(observedHops[2].headers.keys.any { it.equals("Cookie", ignoreCase = true) })
            assertEquals("yes", observedHops[2].headers["X-Keep"])

            // 2. Enforces max 5 redirect hops
            val loopResp = client.execute(HttpRequest(url = "http://loop.test/cycle", timeoutMs = 1_000L))
            // Loop URL doesn't match isMockOrOfflineUrl, but defaultHandler intercepts first and redirects 5 times then aborts.
            assertEquals(0, loopResp.statusCode)
            assertTrue(loopResp.statusText.contains("Too many redirects", ignoreCase = true))

            // 3. Respects followRedirects = false
            val noFollow = client.execute(
                HttpRequest(url = "http://shop.example.com/start", followRedirects = false)
            )
            assertEquals(302, noFollow.statusCode)
        } finally {
            client.dispose()
        }

        // 4. Real non-/v1/ HTTPS URLs must route through WinSock+Schannel (not fake 200 OK from DefaultHttpClientNativeImpl)
        val realHttpsClient = WindowsHttpClientNativeImpl()
        try {
            val closedPortHttps = realHttpsClient.execute(
                HttpRequest(url = "https://127.0.0.1:49199/watch", timeoutMs = 1_500L)
            )
            assertEquals(0, closedPortHttps.statusCode, "All https:// URLs must go through real Schannel/WinSock transport")
            assertTrue(closedPortHttps.statusText.isNotBlank())
        } finally {
            realHttpsClient.dispose()
        }
    }

    @Test
    fun testWin32SchannelTlsClientCloseNotifyAndEofSemantics() {
        val tls = Win32SchannelTlsClient(sock = 0uL, host = "close-notify.test")
        try {
            assertFalse(tls.isPeerClosed)
            tls.processDecryptOutcomeForTest(
                rc = Win32SchannelTlsClient.SEC_E_OK,
                plaintextChunk = "HTTP/1.1 200 OK\r\n\r\nHello".encodeToByteArray()
            )
            assertFalse(tls.isPeerClosed)

            tls.processDecryptOutcomeForTest(
                rc = Win32SchannelTlsClient.SEC_I_CONTEXT_EXPIRED,
                plaintextChunk = "!".encodeToByteArray()
            )
            assertTrue(tls.isPeerClosed)

            val firstBuf = ByteArray(16)
            val n1 = tls.read(firstBuf, 0, 16, waitMs = 100L)
            assertEquals(16, n1)
            assertEquals("HTTP/1.1 200 OK\r", firstBuf.decodeToString(0, n1))

            val secondBuf = ByteArray(16)
            val n2 = tls.read(secondBuf, 0, 16, waitMs = 100L)
            assertEquals(9, n2)
            assertEquals("\n\r\nHello!", secondBuf.decodeToString(0, n2))

            // Once queued plaintext is drained after SEC_I_CONTEXT_EXPIRED, read() returns -1 cleanly.
            val eofRead = tls.read(secondBuf, 0, 16, waitMs = 100L)
            assertEquals(-1, eofRead)
            assertNull(tls.receiveAndDecrypt(100L))
        } finally {
            tls.dispose()
        }
    }
}
