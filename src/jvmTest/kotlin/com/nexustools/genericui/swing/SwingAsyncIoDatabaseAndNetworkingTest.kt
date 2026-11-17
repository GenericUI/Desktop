package com.nexustools.genericui.swing

import com.nexustools.genericui.component.dialog.DialogWindow
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.io.FileSystem
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SwingAsyncIoDatabaseAndNetworkingTest {

    @AfterTest
    fun tearDown() {
        DefaultTcpSocketNativeImpl.clearLoopbackServers()
        DefaultUdpSocketNativeImpl.clearLoopbackEndpoints()
        DefaultHttpClientNativeImpl.clearStaticRoutes()
    }

    @Test
    fun testSwingStreamsDatabaseResourcesAndNetworking() = runSuspend {
        val factory = SwingNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.activeFactory = factory

            val rawResId = ResourceID(0x7f070115, ResourceType.RAW, "swing_props", "raw/swing_theme.properties")
            Resources.activeTable.registerRawText(rawResId, "laf=system")
            val resStream = Resources.openStream(rawResId)
            assertTrue(resStream is ReadableByteBuffer)
            assertEquals("laf=system", resStream.read()?.toString())
            resStream.close()

            val db = factory.createDatabase("SwingCatalog", "", 1)
            assertTrue(db is SwingDatabaseNativeImpl)
            val dbWritable = db.openWritable("blobs", "icon")
            assertTrue(dbWritable is DatabaseWritableNativeImpl)
            dbWritable.write(byteArrayOf(5, 6, 7))
            dbWritable.end()
            val dbReadable = db.openReadable("blobs", "icon")
            assertTrue(dbReadable is DatabaseReadableNativeImpl)
            assertEquals(listOf<Byte>(5, 6, 7), dbReadable.read()?.toByteArray()?.toList())
            dbReadable.close()
            db.dispose()

            DefaultTcpSocketNativeImpl.registerLoopbackServer(9501) { payload -> payload }
            val tcp = Network.openTcpSocket()
            assertTrue(tcp is SwingTcpSocketNativeImpl)
            assertTrue(tcp.connect("127.0.0.1", 9501))
            assertTrue(tcp.writeText("swing-tcp"))
            assertEquals("swing-tcp", tcp.read()?.toString())
            tcp.close()
            tcp.dispose()

            val udpRecv = Network.openUdpSocket()
            val udpSend = Network.openUdpSocket()
            assertTrue(udpRecv is SwingUdpSocketNativeImpl)
            assertTrue(udpRecv.bind("127.0.0.1", 0))
            assertTrue(udpSend.bind("127.0.0.1", 0))
            assertEquals(9, udpSend.send("swing-udp".encodeToByteArray(), "127.0.0.1", udpRecv.localPort))
            assertEquals("swing-udp", udpRecv.receive()?.text)
            udpSend.dispose()
            udpRecv.dispose()

            val http = HttpClient()
            http.registerRoute("https://swing.example.org/status") { req ->
                HttpResponse(statusCode = 200, statusText = "OK", bodyBytes = "jdkhttp-ok".encodeToByteArray(), url = req.url)
            }
            val conn = http.get("https://swing.example.org/status")
            assertTrue(conn is SwingHttpConnectionNativeImpl)
            assertTrue(conn.request is SwingHttpRequestNativeImpl)
            assertTrue(conn.response is SwingHttpResponseNativeImpl)
            assertEquals(200, conn.statusCode)
            assertEquals("jdkhttp-ok", conn.bodyText)
            assertEquals("jdkhttp-ok", conn.read()?.toString())
            conn.close()
            http.dispose()
        } finally {
            GenericUI.activeFactory = prevFactory
            FileSystem.resetFallback()
        }
    }

    @Test
    fun testSwingAttachedNativeDialogWindowLifecycleAndZOrder() {
        val factory = SwingNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.initialize(factory)
            val mainWindow = Window(title = "Swing Main Window")
            mainWindow.setContent(com.nexustools.genericui.component.layout.Container())
            mainWindow.show(factory)
            val mainImpl = mainWindow.ensureNativeImpl() as SwingWindowImpl

            val dialog = DialogWindow(title = "Swing Settings", modal = true)
            dialog.setWindowSize(com.nexustools.genericui.geometry.Size(420, 280))
            dialog.open(mainWindow)

            assertTrue(dialog.isWindowShown)
            assertTrue(mainWindow.attachedDialogWindows.contains(dialog))
            val dialogImpl = dialog.ensureNativeImpl() as SwingDialogWindowImpl
            assertEquals(true, dialogImpl.nativeWidget.rootPane.getClientProperty("isSeparateNativeWindow"))
            assertEquals(false, dialogImpl.nativeWidget.rootPane.getClientProperty("inWindowOverlay"))
            assertEquals(true, dialogImpl.nativeWidget.rootPane.getClientProperty("attachedToMainWindow"))

            mainWindow.bringToFront()
            assertTrue(dialogImpl.zOrderLayer > mainImpl.zOrderLayer)

            dialog.close()
            assertFalse(dialog.isWindowShown)
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
