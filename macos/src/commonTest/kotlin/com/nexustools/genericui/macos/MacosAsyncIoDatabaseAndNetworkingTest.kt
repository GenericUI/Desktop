package com.nexustools.genericui.macos

import com.nexustools.genericui.component.dialog.DialogWindow
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.FileSystem
import com.nexustools.genericui.io.FileSystemAvailability
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacosAsyncIoDatabaseAndNetworkingTest {

    @AfterTest
    fun tearDown() {
        DefaultTcpSocketNativeImpl.clearLoopbackServers()
        DefaultUdpSocketNativeImpl.clearLoopbackEndpoints()
        DefaultHttpClientNativeImpl.clearStaticRoutes()
    }

    @Test
    fun testMacosStreamsDatabaseResourcesAndNetworking() = runSuspend {
        val factory = MacosNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.activeFactory = factory
            val fs = factory.createFileSystem()
            assertTrue(fs is MacosFileSystemNativeImpl)
            assertEquals(FileSystemAvailability.Always, fs.isAvailable())

            val path = fs.resolve("/tmp/macos_test", "docs", "readme.txt")
            val writable = fs.openWritable(path)
            assertTrue(writable is MacosFileWritableNativeImpl)
            writable.writeText("Grand Central Dispatch Content")
            writable.end()
            assertEquals("Grand Central Dispatch Content", fs.read(path)?.toString())

            val readable = fs.openReadable(path)
            assertTrue(readable is MacosFileReadableNativeImpl)
            assertEquals("Grand Central Dispatch Content", readable.read()?.toString())
            readable.close()

            val configDir = ConfigDirectory(appName = "MacosTestSuiteApp", subdirectory = "ui", factory = factory)
            val cacheDir = CacheDirectory(appName = "MacosTestSuiteApp", subdirectory = "thumbnails", factory = factory)
            assertTrue(configDir.write("layout.json", ByteBuffer.fromString("{\"dock\":true}")))
            assertEquals("{\"dock\":true}", configDir.read("layout.json")?.toString())

            val rawResId = ResourceID(0x7f070114, ResourceType.RAW, "macos_plist", "raw/Info.plist")
            Resources.activeTable.registerRawText(rawResId, "<plist/>")
            val resStream = Resources.openStream(rawResId)
            assertTrue(resStream is MacosFileReadableNativeImpl)
            assertEquals("<plist/>", resStream.read()?.toString())
            resStream.close()

            val db = factory.createDatabase("MacosCatalog", "/tmp/macos_catalog.db", 1)
            assertTrue(db is MacosDatabaseNativeImpl)
            val dbWritable = db.openWritable("blobs", "icon")
            assertTrue(dbWritable is DatabaseWritableNativeImpl)
            dbWritable.write(byteArrayOf(7, 8, 9))
            dbWritable.end()
            val dbReadable = db.openReadable("blobs", "icon")
            assertTrue(dbReadable is DatabaseReadableNativeImpl)
            assertEquals(listOf<Byte>(7, 8, 9), dbReadable.read()?.toByteArray()?.toList())
            dbReadable.close()
            db.dispose()

            DefaultTcpSocketNativeImpl.registerLoopbackServer(9401) { payload -> payload }
            val tcp = Network.openTcpSocket()
            assertTrue(tcp is MacosTcpSocketNativeImpl)
            assertTrue(tcp.connect("127.0.0.1", 9401))
            assertTrue(tcp.writeText("mac-tcp"))
            assertEquals("mac-tcp", tcp.read()?.toString())
            tcp.close()
            tcp.dispose()

            val udpRecv = Network.openUdpSocket()
            val udpSend = Network.openUdpSocket()
            assertTrue(udpRecv is MacosUdpSocketNativeImpl)
            assertTrue(udpRecv.bind("127.0.0.1", 0))
            assertTrue(udpSend.bind("127.0.0.1", 0))
            assertEquals(7, udpSend.send("mac-udp".encodeToByteArray(), "127.0.0.1", udpRecv.localPort))
            assertEquals("mac-udp", udpRecv.receive()?.text)
            udpSend.dispose()
            udpRecv.dispose()

            val http = HttpClient()
            http.registerRoute("https://macos.example.org/status") { req ->
                HttpResponse(statusCode = 200, statusText = "OK", bodyBytes = "nsurlsession-ok".encodeToByteArray(), url = req.url)
            }
            val conn = http.get("https://macos.example.org/status")
            assertTrue(conn is MacosHttpConnectionNativeImpl)
            assertTrue(conn.request is MacosHttpRequestNativeImpl)
            assertTrue(conn.response is MacosHttpResponseNativeImpl)
            assertEquals(200, conn.statusCode)
            assertEquals("nsurlsession-ok", conn.bodyText)
            assertEquals("nsurlsession-ok", conn.read()?.toString())
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

    @Test
    fun testMacosAttachedNativeDialogWindowLifecycleAndZOrder() {
        val factory = MacosNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.initialize(factory)
            val mainWindow = Window(title = "macOS Main Window")
            mainWindow.show(factory)
            val mainImpl = mainWindow.ensureNativeImpl() as MacosWindowNativeImpl

            val dialog = DialogWindow(title = "macOS Preferences", modal = true, ownerWindow = mainWindow)
            dialog.setWindowSize(440, 300)
            dialog.open(mainWindow)

            assertTrue(dialog.isOpen)
            assertTrue(mainWindow.attachedDialogWindows.contains(dialog))
            val dialogImpl = dialog.ensureNativeImpl() as MacosDialogWindowNativeImpl
            assertTrue(dialogImpl.isSeparateNativeWindow)
            assertFalse(dialogImpl.inWindowOverlay)
            assertTrue(dialogImpl.attachedToMainWindow)
            assertEquals("true", dialogImpl.nativeWidget.attributes["isSeparateNativeWindow"])
            assertEquals("false", dialogImpl.nativeWidget.attributes["inWindowOverlay"])
            assertEquals("true", dialogImpl.nativeWidget.attributes["attachedToMainWindow"])

            mainWindow.bringToFront()
            assertTrue(dialogImpl.zOrderLayer > mainImpl.zOrderLayer)

            dialog.close()
            assertFalse(dialog.isOpen)
            assertFalse(mainWindow.attachedDialogWindows.contains(dialog))
        } finally {
            GenericUI.activeFactory = prevFactory
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
