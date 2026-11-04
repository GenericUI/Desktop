package com.nexustools.genericui.windows

import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.FileSystem
import com.nexustools.genericui.io.FileSystemAvailability
import com.nexustools.genericui.io.ReadableByteBuffer
import com.nexustools.genericui.nativeimpl.io.DatabaseReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.DatabaseWritableNativeImpl
import com.nexustools.genericui.nativeimpl.net.DefaultHttpClientNativeImpl
import com.nexustools.genericui.nativeimpl.net.DefaultTcpSocketNativeImpl
import com.nexustools.genericui.nativeimpl.net.DefaultUdpSocketNativeImpl
import com.nexustools.genericui.net.Network
import com.nexustools.genericui.net.http.HttpClient
import com.nexustools.genericui.net.http.HttpResponse
import com.nexustools.genericui.resources.ResourceID
import com.nexustools.genericui.resources.ResourceType
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.util.CacheDirectory
import com.nexustools.genericui.util.ConfigDirectory
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WindowsAsyncIoDatabaseAndNetworkingTest {

    @AfterTest
    fun tearDown() {
        DefaultTcpSocketNativeImpl.clearLoopbackServers()
        DefaultUdpSocketNativeImpl.clearLoopbackEndpoints()
        DefaultHttpClientNativeImpl.clearStaticRoutes()
    }

    @Test
    fun testWindowsStreamsDatabaseResourcesAndNetworking() = runSuspend {
        val factory = WindowsNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.activeFactory = factory
            val fs = factory.createFileSystem()
            assertTrue(fs is WindowsFileSystemNativeImpl)
            assertEquals(FileSystemAvailability.Always, fs.isAvailable())

            val path = fs.resolve("C:\\Temp\\win_test", "docs", "readme.txt")
            val writable = fs.openWritable(path)
            assertTrue(writable is WindowsFileWritableNativeImpl)
            writable.writeText("Win32 OVERLAPPED Content")
            writable.end()
            assertEquals("Win32 OVERLAPPED Content", fs.read(path)?.toString())

            val readable = fs.openReadable(path)
            assertTrue(readable is WindowsFileReadableNativeImpl)
            assertEquals("Win32 OVERLAPPED Content", readable.read()?.toString())
            readable.close()

            val configDir = ConfigDirectory(appName = "WinTestSuiteApp", subdirectory = "ui", factory = factory)
            val cacheDir = CacheDirectory(appName = "WinTestSuiteApp", subdirectory = "thumbnails", factory = factory)
            assertTrue(configDir.write("layout.json", ByteBuffer.fromString("{\"dock\":true}")))
            assertEquals("{\"dock\":true}", configDir.read("layout.json")?.toString())

            val rawResId = ResourceID(0x7f070113, ResourceType.RAW, "win_rc", "raw/win_manifest.xml")
            Resources.activeTable.registerRawText(rawResId, "<assembly/>")
            val resStream = Resources.openStream(rawResId)
            assertTrue(resStream is ReadableByteBuffer)
            assertEquals("<assembly/>", resStream.read()?.toString())
            resStream.close()

            val db = factory.createDatabase("WinCatalog", "C:\\Temp\\win_catalog.db", 1)
            assertTrue(db is WindowsDatabaseNativeImpl)
            val dbWritable = db.openWritable("blobs", "icon")
            assertTrue(dbWritable is DatabaseWritableNativeImpl)
            dbWritable.write(byteArrayOf(1, 2, 3))
            dbWritable.end()
            val dbReadable = db.openReadable("blobs", "icon")
            assertTrue(dbReadable is DatabaseReadableNativeImpl)
            assertEquals(listOf<Byte>(1, 2, 3), dbReadable.read()?.toByteArray()?.toList())
            dbReadable.close()
            db.dispose()

            DefaultTcpSocketNativeImpl.registerLoopbackServer(9301) { payload -> payload }
            val tcp = Network.openTcpSocket()
            assertTrue(tcp is WindowsTcpSocketNativeImpl)
            assertTrue(tcp.connect("127.0.0.1", 9301))
            assertTrue(tcp.writeText("win-tcp"))
            assertEquals("win-tcp", tcp.read()?.toString())
            tcp.close()
            tcp.dispose()

            val udpRecv = Network.openUdpSocket()
            val udpSend = Network.openUdpSocket()
            assertTrue(udpRecv is WindowsUdpSocketNativeImpl)
            assertTrue(udpRecv.bind("127.0.0.1", 0))
            assertTrue(udpSend.bind("127.0.0.1", 0))
            assertEquals(7, udpSend.send("win-udp".encodeToByteArray(), "127.0.0.1", udpRecv.localPort))
            assertEquals("win-udp", udpRecv.receive()?.text)
            udpSend.dispose()
            udpRecv.dispose()

            val http = HttpClient()
            http.registerRoute("https://win.example.org/status") { req ->
                HttpResponse(statusCode = 200, statusText = "OK", bodyBytes = "winhttp-ok".encodeToByteArray(), url = req.url)
            }
            val conn = http.get("https://win.example.org/status")
            assertTrue(conn is WindowsHttpConnectionNativeImpl)
            assertTrue(conn.request is WindowsHttpRequestNativeImpl)
            assertTrue(conn.response is WindowsHttpResponseNativeImpl)
            assertEquals(200, conn.statusCode)
            assertEquals("winhttp-ok", conn.bodyText)
            assertEquals("winhttp-ok", conn.read()?.toString())
            conn.close()
            http.dispose()

            configDir.clear()
            cacheDir.clear()
            configDir.dispose()
            cacheDir.dispose()
            fs.dispose()
        } finally {
            GenericUI.activeFactory = prevFactory
            FileSystem.resetFallback()
        }
    }

    private fun runSuspend(block: suspend () -> Unit) {
        var failure: Throwable? = null
        var completed = false
        block.startCoroutine(
            Continuation(EmptyCoroutineContext) { result ->
                completed = true
                failure = result.exceptionOrNull()
            }
        )
        failure?.let { throw it }
        assertTrue(completed)
    }
}
