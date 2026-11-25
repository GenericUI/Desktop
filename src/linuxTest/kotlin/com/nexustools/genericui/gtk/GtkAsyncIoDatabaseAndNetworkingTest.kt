@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.dialog.DialogWindow
import com.nexustools.genericui.component.form.CheckBox
import com.nexustools.genericui.component.form.TextField
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.surface.StyledComponent
import com.nexustools.genericui.component.layout.StyledContainer
import com.nexustools.genericui.component.layout.VStack
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.FontWeight
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.gtk.impl.GtkCheckboxNativeImpl
import com.nexustools.genericui.gtk.impl.GtkDropdownNativeImpl
import com.nexustools.genericui.gtk.impl.GtkLazyGridNativeImpl
import com.nexustools.genericui.gtk.impl.GtkMarkdownViewNativeImpl
import com.nexustools.genericui.gtk.impl.GtkProgressBarNativeImpl
import com.nexustools.genericui.gtk.impl.GtkRangeSliderNativeImpl
import com.nexustools.genericui.gtk.impl.GtkSelectNativeImpl
import com.nexustools.genericui.gtk.impl.GtkSpinnerNativeImpl
import com.nexustools.genericui.gtk.impl.GtkTabNativeImpl
import com.nexustools.genericui.gtk.impl.GtkTableNativeImpl
import com.nexustools.genericui.gtk.impl.GtkTextInputNativeImpl
import com.nexustools.genericui.gtk.impl.GtkTextNativeImpl
import com.nexustools.genericui.gtk.widget.GtkSurfacePaintSpec
import com.nexustools.genericui.gtk.widget.GtkSurfaceRenderHelper
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.linux.style.LinuxBackdropFilterController
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssShadow
import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.FileSystem
import com.nexustools.genericui.io.FileSystemAvailability
import com.nexustools.genericui.io.ReadableByteBuffer
import com.nexustools.genericui.linux.LinuxDatabaseReadableNativeImpl
import com.nexustools.genericui.linux.LinuxDatabaseWritableNativeImpl
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.linux.LinuxHttpConnectionNativeImpl
import com.nexustools.genericui.linux.LinuxHttpRequestNativeImpl
import com.nexustools.genericui.linux.LinuxHttpResponseNativeImpl
import com.nexustools.genericui.linux.LinuxPosixFileReadableNativeImpl
import com.nexustools.genericui.linux.LinuxPosixFileSystemNativeImpl
import com.nexustools.genericui.linux.LinuxPosixFileWritableNativeImpl
import com.nexustools.genericui.linux.LinuxPosixTcpSocketNativeImpl
import com.nexustools.genericui.linux.LinuxPosixUdpSocketNativeImpl
import com.nexustools.genericui.linux.LinuxResourceResolverImpl
import com.nexustools.genericui.linux.LinuxRfc6455WebSocketNativeImpl
import com.nexustools.genericui.linux.LinuxSqliteDatabaseNativeImpl
import com.nexustools.genericui.linux.LinuxXdgCacheDirectoryImpl
import com.nexustools.genericui.linux.LinuxXdgConfigDirectoryImpl
import com.nexustools.genericui.nativeimpl.net.DefaultHttpClientNativeImpl
import com.nexustools.genericui.nativeimpl.net.DefaultTcpSocketNativeImpl
import com.nexustools.genericui.nativeimpl.net.DefaultUdpSocketNativeImpl
import com.nexustools.genericui.net.Network
import com.nexustools.genericui.net.http.HttpClient
import com.nexustools.genericui.net.http.HttpResponse
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.resources.ResourceID
import com.nexustools.genericui.resources.ResourceType
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.util.CacheDirectory
import com.nexustools.genericui.util.ConfigDirectory
import com.nexustools.genericui.util.db.DatabaseColumnSpec
import com.nexustools.genericui.util.db.DatabaseColumnType
import com.nexustools.genericui.util.db.DatabaseFilter
import com.nexustools.genericui.util.db.DatabaseQuery
import com.nexustools.genericui.util.db.DatabaseRecord
import com.nexustools.genericui.util.db.DatabaseSortDirection
import com.nexustools.genericui.util.db.DatabaseTableSchema
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.AI_PASSIVE
import platform.posix.NI_NUMERICHOST
import platform.posix.NI_NUMERICSERV
import platform.posix.POLLIN
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.addrinfo
import platform.posix.bind as posixBind
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getenv
import platform.posix.getnameinfo
import platform.posix.getpid
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.memset
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr
import platform.posix.sockaddr_storage
import platform.posix.socket
import platform.posix.socklen_tVar
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class GtkAsyncIoDatabaseAndNetworkingTest {

    @AfterTest
    fun tearDown() {
        DefaultTcpSocketNativeImpl.clearLoopbackServers()
        DefaultUdpSocketNativeImpl.clearLoopbackEndpoints()
        DefaultHttpClientNativeImpl.clearStaticRoutes()
    }

    @Test
    fun testGtkFileSystemUserDirectoryAndResourceStream() = runBlocking {
        val factory = GtkNativeFactory()
        val prevFactory = GenericUI.activeFactory
        val rootDir = "/tmp/genericui_gtk_fs_test_${getpid()}"
        try {
            GenericUI.activeFactory = factory
            val fs = factory.createFileSystem()
            assertTrue(fs is LinuxPosixFileSystemNativeImpl)
            assertEquals(FileSystemAvailability.Always, fs.isAvailable())

            val docsDir = fs.resolve(rootDir, "docs")
            assertTrue(fs.mkdir(docsDir, recursive = true))
            val path = fs.resolve(docsDir, "readme.txt")
            val copiedPath = fs.resolve(docsDir, "readme_copy.txt")

            val writeStream = fs.openWritable(path)
            assertTrue(writeStream is LinuxPosixFileWritableNativeImpl)
            writeStream.writeText("GTK POSIX Content")
            writeStream.end()
            assertEquals("GTK POSIX Content", fs.read(path)?.toString())

            val stat = fs.stat(path)
            assertNotNull(stat)
            assertTrue(stat.isFile)
            assertEquals("GTK POSIX Content".length.toLong(), stat.size)

            assertTrue(fs.copyFile(path, copiedPath, overwrite = true))
            val entries = fs.readdir(docsDir)
            assertTrue(entries.contains("readme.txt"))
            assertTrue(entries.contains("readme_copy.txt"))

            val readStream = fs.openReadable(copiedPath)
            assertTrue(readStream is LinuxPosixFileReadableNativeImpl)
            assertEquals("GTK POSIX Content", readStream.read()?.toString())
            readStream.close()

            assertTrue(fs.unlink(copiedPath))
            assertTrue(fs.rmdir(rootDir, recursive = true))

            val configDirImpl = factory.createConfigDirectory("GtkTestSuiteApp_${getpid()}", "ui")
            val cacheDirImpl = factory.createCacheDirectory("GtkTestSuiteApp_${getpid()}", "thumbnails")
            assertTrue(configDirImpl is LinuxXdgConfigDirectoryImpl)
            assertTrue(cacheDirImpl is LinuxXdgCacheDirectoryImpl)

            val configDir = ConfigDirectory(
                appName = "GtkTestSuiteApp_${getpid()}",
                subdirectory = "ui",
                factory = factory
            )
            val cacheDir = CacheDirectory(
                appName = "GtkTestSuiteApp_${getpid()}",
                subdirectory = "thumbnails",
                factory = factory
            )
            assertTrue(configDir.write("layout.json", ByteBuffer.fromString("{\"dock\":true}")))
            assertEquals("{\"dock\":true}", configDir.read("layout.json")?.toString())

            val rawResId = ResourceID(0x7f070111, ResourceType.RAW, "gtk_css", "raw/gtk_style.css")
            Resources.activeTable.registerRawText(rawResId, "window { background: #1e1e2e; }")
            val resolver = factory.createResourceResolver()
            assertTrue(resolver is LinuxResourceResolverImpl)
            val resStream = Resources.openStream(rawResId)
            assertTrue(resStream is ReadableByteBuffer)
            assertEquals("window { background: #1e1e2e; }", resStream.read()?.toString())
            resStream.close()

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
    fun testGtkDatabaseSqliteTransactionsAndStreams() = runBlocking {
        val factory = GtkNativeFactory()
        val fs = factory.createFileSystem()
        val dbPath = "/tmp/genericui_gtk_sqlite_${getpid()}.db"
        fs.unlink(dbPath)

        val db = factory.createDatabase("GtkCatalog", dbPath, 2)
        assertTrue(db is LinuxSqliteDatabaseNativeImpl)

        val schema = DatabaseTableSchema(
            tableName = "widgets",
            primaryKeyColumn = "key",
            columns = listOf(
                DatabaseColumnSpec("key", DatabaseColumnType.STRING, primaryKey = true),
                DatabaseColumnSpec("name", DatabaseColumnType.STRING, nullable = false, indexed = true),
                DatabaseColumnSpec("priority", DatabaseColumnType.INT64, nullable = false, indexed = true)
            )
        )
        assertTrue(db.defineTable(schema))
        assertTrue(
            db.putRecord(
                "widgets",
                DatabaseRecord("w1").withString("name", "GtkHeaderBar").withLong("priority", 10L)
            )
        )
        assertTrue(
            db.putRecord(
                "widgets",
                DatabaseRecord("w2").withString("name", "GtkDrawingArea").withLong("priority", 20L)
            )
        )

        val query = DatabaseQuery.from("widgets")
            .where(DatabaseFilter.gte("priority", 10L))
            .orderBy("priority", DatabaseSortDirection.DESCENDING)
        val rows = db.query(query)
        assertEquals(2, rows.size)
        assertEquals("w2", rows.first().key)
        assertEquals("GtkDrawingArea", rows.first().getString("name"))

        val writable = db.openWritable("blobs", "icon")
        assertTrue(writable is LinuxDatabaseWritableNativeImpl)
        writable.write(byteArrayOf(9, 8, 7))
        writable.end()

        val readable = db.openReadable("blobs", "icon")
        assertTrue(readable is LinuxDatabaseReadableNativeImpl)
        assertEquals(listOf<Byte>(9, 8, 7), readable.read()?.toByteArray()?.toList())
        readable.close()
        db.dispose()

        // Reopen the SQLite database file from disk to verify real libsqlite3.so.0 persistence.
        val reopened = factory.createDatabase("GtkCatalog", dbPath, 2)
        val persisted = reopened.getRecord("widgets", "w1")
        assertNotNull(persisted)
        assertEquals("GtkHeaderBar", persisted.getString("name"))
        reopened.dispose()

        fs.unlink(dbPath)
        fs.unlink("$dbPath-wal")
        fs.unlink("$dbPath-shm")
        fs.dispose()
    }

    @Test
    fun testGtkNetworkingLoopbackTcpUdpHttpAndWebSocket() = runBlocking {
        val factory = GtkNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.activeFactory = factory

            // 1. Real POSIX TCP socket loopback echo
            val tcpServer = LoopbackTcpServer()
            val tcpPort = tcpServer.bindLoopback()
            val tcpEchoJob = launch(Dispatchers.IO) {
                val clientFd = tcpServer.acceptOne(timeoutMs = 5_000)
                if (clientFd >= 0) {
                    val req = tcpServer.recvUpTo(clientFd, 1024)
                    if (req.isNotEmpty()) {
                        tcpServer.sendAll(clientFd, req)
                    }
                    platform.posix.close(clientFd)
                }
            }

            val tcp = Network.openTcpSocket()
            assertTrue(tcp is LinuxPosixTcpSocketNativeImpl)
            assertTrue(tcp.connect("127.0.0.1", tcpPort))
            assertTrue(tcp.writeText("gtk-tcp"))
            assertEquals("gtk-tcp", tcp.read()?.toString())
            tcp.close()
            tcp.dispose()
            tcpEchoJob.join()
            tcpServer.close()

            // 2. Real POSIX UDP loopback send/receive
            val udpRecv = Network.openUdpSocket()
            val udpSend = Network.openUdpSocket()
            assertTrue(udpRecv is LinuxPosixUdpSocketNativeImpl)
            assertTrue(udpRecv.bind("127.0.0.1", 0))
            assertTrue(udpSend.bind("127.0.0.1", 0))
            assertEquals(7, udpSend.send("gtk-udp".encodeToByteArray(), "127.0.0.1", udpRecv.localPort))
            val datagram = withTimeout(3_000L) { udpRecv.receive() }
            assertEquals("gtk-udp", datagram?.text)
            udpSend.dispose()
            udpRecv.dispose()

            // 3. Real libcurl.so.4 HTTP GET against loopback server + route override
            val httpServer = LoopbackTcpServer()
            val httpPort = httpServer.bindLoopback()
            val httpJob = launch(Dispatchers.IO) {
                val clientFd = httpServer.acceptOne(timeoutMs = 5_000)
                if (clientFd >= 0) {
                    httpServer.readHttpHeaders(clientFd)
                    val body = "curl-ok"
                    val resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    httpServer.sendAll(clientFd, resp.encodeToByteArray())
                    platform.posix.close(clientFd)
                }
            }

            val http = HttpClient()
            val liveConn = http.get("http://127.0.0.1:$httpPort/status")
            assertTrue(liveConn is LinuxHttpConnectionNativeImpl)
            assertTrue(liveConn.request is LinuxHttpRequestNativeImpl)
            assertTrue(liveConn.response is LinuxHttpResponseNativeImpl)
            assertEquals(200, liveConn.statusCode)
            assertEquals("curl-ok", liveConn.bodyText)
            liveConn.close()
            httpJob.join()
            httpServer.close()

            http.registerRoute("https://gtk.example.org/status") { req ->
                HttpResponse(statusCode = 200, statusText = "OK", bodyBytes = "route-ok".encodeToByteArray(), url = req.url)
            }
            val routeConn = http.get("https://gtk.example.org/status")
            assertEquals(200, routeConn.statusCode)
            assertEquals("route-ok", routeConn.read()?.toString())
            routeConn.close()
            http.dispose()

            // 4. Real RFC 6455 WebSocket handshake + masked text frame loopback
            val wsServer = LoopbackTcpServer()
            val wsPort = wsServer.bindLoopback()
            val wsServerJob = launch(Dispatchers.IO) {
                val clientFd = wsServer.acceptOne(timeoutMs = 5_000)
                if (clientFd >= 0) {
                    val reqText = wsServer.readHttpHeaders(clientFd)
                    val wsKey = reqText.lines()
                        .firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.trim()
                        .orEmpty()
                    val acceptKey = Base64.encode(sha1((wsKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encodeToByteArray()))
                    val upgrade = "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $acceptKey\r\n\r\n"
                    wsServer.sendAll(clientFd, upgrade.encodeToByteArray())

                    val incomingText = wsServer.readMaskedTextFrame(clientFd)
                    if (incomingText != null) {
                        val payload = incomingText.encodeToByteArray()
                        val frame = ByteArray(2 + payload.size)
                        frame[0] = 0x81.toByte()
                        frame[1] = payload.size.toByte()
                        payload.copyInto(frame, destinationOffset = 2)
                        wsServer.sendAll(clientFd, frame)
                    }
                    platform.posix.poll(null, 0.convert(), 50)
                    platform.posix.close(clientFd)
                }
            }

            val ws = factory.createWebSocket()
            assertTrue(ws is LinuxRfc6455WebSocketNativeImpl)
            val receivedText = CompletableDeferred<String>()
            ws.setOnTextMessage { msg ->
                if (!receivedText.isCompleted) receivedText.complete(msg)
            }
            assertTrue(ws.connect("ws://127.0.0.1:$wsPort/chat"))
            assertTrue(ws.sendText("gtk-ws"))
            assertEquals("gtk-ws", withTimeout(5_000L) { receivedText.await() })
            ws.close(1000, "Done")
            ws.dispose()
            wsServerJob.join()
            wsServer.close()
        } finally {
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun testGtkAttachedNativeDialogWindowLifecycleAndZOrder() {
        val factory = GtkNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.initialize(factory)
            val mainWindow = Window(title = "GTK Main Window")
            mainWindow.setContent(Container())
            mainWindow.show(factory)
            val mainImpl = GtkWindowNativeImpl.activeWindows.lastOrNull()
            assertNotNull(mainImpl)

            val dialog = DialogWindow(title = "GTK Preferences", modal = true)
            dialog.setContent(Container())
            dialog.setWindowSize(Size(440, 300))
            dialog.open(mainWindow)

            assertTrue(dialog.isWindowShown)
            assertTrue(mainWindow.attachedDialogWindows.contains(dialog))
            val dialogImpl = DesktopDialogWindowNativeImpl.activeDialogs.lastOrNull() as? GtkDialogWindowNativeImpl
            assertNotNull(dialogImpl)
            assertTrue(dialogImpl.isSeparateNativeWindow)
            assertFalse(dialogImpl.inWindowOverlay)
            assertTrue(dialogImpl.attachedToMainWindow)
            assertEquals(true, dialogImpl.nativeWidget.attributes["isSeparateNativeWindow"])
            assertEquals(false, dialogImpl.nativeWidget.attributes["inWindowOverlay"])
            assertEquals(true, dialogImpl.nativeWidget.attributes["attachedToMainWindow"])

            mainWindow.bringToFront()
            assertTrue(dialogImpl.zOrderLayer > mainImpl.zOrderLayer)

            dialog.close()
            mainWindow.close()
            assertFalse(dialog.isWindowShown)
            assertFalse(mainWindow.attachedDialogWindows.contains(dialog))
        } finally {
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun testGtkPerComponentNativeImplsRetainedWidgetsAndSkinBoundaries() {
        val factory = GtkNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.initialize(factory)

            val buttonImpl = factory.createButton() as GtkButtonNativeImpl
            buttonImpl.setText("Save")
            buttonImpl.applyComponentStyle(
                ComponentStyle().apply {
                    backgroundColor = RgbaColor(255, 0, 0)
                    color = RgbaColor(0, 255, 0)
                    fontSize = CssLength(24f)
                }
            )
            assertFalse(buttonImpl.acceptsAestheticSkinStyles())
            assertTrue(buttonImpl.isNativeDefaultButtonMode())
            assertEquals("GtkButton", buttonImpl.nativeWidget.activeGtkClassName)
            assertFalse(buttonImpl.nativeWidget.attributes.containsKey("backgroundColor"))
            assertFalse(buttonImpl.nativeWidget.attributes.containsKey("textColor"))
            assertEquals("true", buttonImpl.nativeWidget.attributes["useSystemControlAppearance"])

            buttonImpl.setType(BootstrapType.PRIMARY)
            assertFalse(buttonImpl.isNativeDefaultButtonMode())
            assertEquals("GtkButton", buttonImpl.nativeWidget.activeGtkClassName)
            assertEquals("false", buttonImpl.nativeWidget.attributes["useSystemControlAppearance"])
            val primaryPtr = assertNotNull(buttonImpl.nativeWidget.ensureLiveWidgetPtr())
            assertTrue(Gtk4DynamicBindings.gtkWidgetHasCssClass(primaryPtr, "gui-btn-primary"))
            assertTrue(Gtk4DynamicBindings.gtkWidgetHasCssClass(primaryPtr, "suggested-action"))

            val textImpl = factory.createText() as GtkTextNativeImpl
            textImpl.setText("Hello GTK4")
            textImpl.applyComponentStyle(
                ComponentStyle().apply {
                    color = RgbaColor(0x11, 0x22, 0x33)
                    fontSize = CssLength(18f)
                    fontWeight = FontWeight.BOLD
                }
            )
            assertEquals("GtkLabel", textImpl.nativeWidget.activeGtkClassName)
            assertEquals(RgbaColor(0x11, 0x22, 0x33), textImpl.nativeWidget.attributes["textColor"])
            assertEquals(18f, textImpl.nativeWidget.attributes["fontSizePx"])
            assertEquals("BOLD", textImpl.nativeWidget.attributes["fontWeight"])

            val markdownImpl = factory.createMarkdownView() as GtkMarkdownViewNativeImpl
            markdownImpl.setMarkdown("# Heading")
            markdownImpl.applyComponentStyle(ComponentStyle().apply { color = RgbaColor(0x22, 0x44, 0x66) })
            assertEquals(RgbaColor(0x22, 0x44, 0x66), markdownImpl.nativeWidget.attributes["textColor"])

            val textInputImpl = factory.createTextInput() as GtkTextInputNativeImpl
            textInputImpl.setValue("alice")
            textInputImpl.setPlaceholder("User")
            textInputImpl.setLabel("Username")
            assertFalse(textInputImpl.acceptsAestheticSkinStyles())
            assertEquals("GtkEntry", textInputImpl.nativeWidget.activeGtkClassName)
            assertEquals("Username", textInputImpl.getLabel())

            val checkboxImpl = factory.createCheckbox() as GtkCheckboxNativeImpl
            assertFalse(checkboxImpl.acceptsAestheticSkinStyles())
            checkboxImpl.setLabel("Agree")
            checkboxImpl.setType(BootstrapType.PRIMARY)
            assertEquals("GtkCheckButton", checkboxImpl.nativeWidget.activeGtkClassName)
            assertEquals(null, checkboxImpl.nativeWidget.attributes["bootstrapTintColor"])

            val selectImpl = factory.createSelect() as GtkSelectNativeImpl
            assertFalse(selectImpl.acceptsAestheticSkinStyles())
            assertEquals("GtkDropDown", selectImpl.nativeWidget.activeGtkClassName)

            val dropdownImpl = factory.createDropdown() as GtkDropdownNativeImpl
            assertFalse(dropdownImpl.acceptsAestheticSkinStyles())
            assertEquals("GtkMenuButton", dropdownImpl.nativeWidget.activeGtkClassName)

            val sliderImpl = factory.createRangeSlider() as GtkRangeSliderNativeImpl
            assertFalse(sliderImpl.acceptsAestheticSkinStyles())

            val progressImpl = factory.createProgressBar() as GtkProgressBarNativeImpl
            assertFalse(progressImpl.acceptsAestheticSkinStyles())
            progressImpl.setProgress(0.6f)
            progressImpl.setType(BootstrapType.PRIMARY)
            assertEquals(null, progressImpl.nativeWidget.attributes["bootstrapTintColor"])

            val spinnerImpl = factory.createSpinner() as GtkSpinnerNativeImpl
            assertFalse(spinnerImpl.acceptsAestheticSkinStyles())

            val containerImpl = factory.createContainer() as GtkContainerNativeImpl
            assertFalse(containerImpl.acceptsAestheticSkinStyles())

            val layeredImpl = factory.createContainer("gui-layered-container", "genericui:layered-container") as GtkContainerNativeImpl
            assertEquals("GtkOverlay", layeredImpl.nativeWidget.activeGtkClassName)

            val tabContainerImpl = factory.createTabContainer() as GtkTabContainerNativeImpl
            assertFalse(tabContainerImpl.acceptsAestheticSkinStyles())

            val tabImpl = factory.createTab() as GtkTabNativeImpl
            assertFalse(tabImpl.acceptsAestheticSkinStyles())

            val tableImpl = factory.createTable() as GtkTableNativeImpl
            tableImpl.setColumnWeights(floatArrayOf(2f, 1f, 1f))
            assertEquals(listOf(2f, 1f, 1f), tableImpl.getColumnWeights())

            val lazyGridImpl = factory.createLazyGrid() as GtkLazyGridNativeImpl
            lazyGridImpl.setColumns(4)
            lazyGridImpl.setMinColumnWidthPx(180)
            assertEquals(180, lazyGridImpl.getMinColumnWidthPx())
            assertEquals(4, lazyGridImpl.resolveEffectiveColumns(760))
            assertEquals(2, lazyGridImpl.resolveEffectiveColumns(400))
        } finally {
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun testGenuineGtkButtonVsBootstrapButtonWidgetAndLiveModeSwap() {
        val factory = GtkNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.initialize(factory)
            val btn = factory.createButton() as GtkButtonNativeImpl
            btn.setText("Execute")
            btn.setIcon("★")
            btn.setEnabled(false)
            btn.setBounds(Rect(12, 24, 140, 36))

            // Plain un-tinted button defaults cleanly to genuine GtkButton
            assertTrue(btn.isNativeDefaultButtonMode())
            assertEquals("GtkButton", btn.nativeWidget.activeGtkClassName)
            assertEquals("true", btn.nativeWidget.attributes["useSystemControlAppearance"])

            // Bootstrap type styles genuine GtkButton natively via Libadwaita/GTK CSS classes
            btn.setType(BootstrapType.SUCCESS)
            assertFalse(btn.isNativeDefaultButtonMode())
            assertEquals("GtkButton", btn.nativeWidget.activeGtkClassName)
            assertEquals("false", btn.nativeWidget.attributes["useSystemControlAppearance"])
            assertEquals("Execute", btn.getText())
            assertEquals("★", btn.getIcon())
            assertFalse(btn.isEnabled())
            assertEquals(Rect(12, 24, 140, 36), btn.getBounds())

            // Outline mode also keeps genuine GtkButton with gui-btn-outline CSS class
            btn.setType(null)
            btn.setOutline(true)
            assertEquals("GtkButton", btn.nativeWidget.activeGtkClassName)

            // Reverting back to default clears semantic styling on genuine GtkButton
            btn.setOutline(false)
            assertTrue(btn.isNativeDefaultButtonMode())
            assertEquals("GtkButton", btn.nativeWidget.activeGtkClassName)
            assertEquals("Execute", btn.getText())
            assertEquals("★", btn.getIcon())
            assertFalse(btn.isEnabled())
            assertEquals(Rect(12, 24, 140, 36), btn.getBounds())
        } finally {
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun testAllTenBackdropFilterOperationsInGtkSurfaceRenderHelper() {
        assertTrue(Gtk4DynamicBindings.ensureLibrariesLoaded())
        val width = 48
        val height = 48

        fun makePatternBuffer(): IntArray = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (x !in 6 until width - 6 || y !in 6 until height - 6) {
                0
            } else {
                val r = if (x < width / 2) 220 else 30
                val g = if (y < height / 2) 190 else 45
                val b = if ((x + y) % 2 == 0) 210 else 60
                (255 shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val ops = listOf(
            "blur" to CssFilter(blur = CssLength.px(4f)),
            "brightness" to CssFilter(brightness = 1.45f),
            "contrast" to CssFilter(contrast = 1.6f),
            "grayscale" to CssFilter(grayscale = 1f),
            "hue-rotate" to CssFilter(hueRotateDeg = 120f),
            "invert" to CssFilter(invert = 1f),
            "opacity" to CssFilter(opacity = 0.45f),
            "saturate" to CssFilter(saturate = 2.2f),
            "sepia" to CssFilter(sepia = 1f),
            "drop-shadow" to CssFilter(dropShadow = CssShadow.px(2f, 2f, 3f, color = RgbaColor(0, 0, 0, 180)))
        )

        val baseline = makePatternBuffer()
        for ((name, filter) in ops) {
            assertTrue(LinuxBackdropFilterController.shared.hasActiveEffects(filter), "Expected active filter for $name")
            val words = makePatternBuffer()
            LinuxBackdropFilterController.shared.applyToPremulBgraBuffer(
                filter = filter,
                pixels = words,
                strideWords = width,
                bufferWidth = width,
                bufferHeight = height,
                regionX = 0,
                regionY = 0,
                regionWidth = width,
                regionHeight = height
            )
            assertFalse(
                words.contentEquals(baseline),
                "Backdrop filter '$name' must deterministically alter premultiplied BGRA pixels"
            )
        }

        val combinedFilter = CssFilter(
            blur = CssLength.px(3f),
            brightness = 1.15f,
            contrast = 1.2f,
            grayscale = 0.2f,
            hueRotateDeg = 45f,
            invert = 0.1f,
            opacity = 0.85f,
            saturate = 1.3f,
            sepia = 0.15f,
            dropShadow = CssShadow.px(1f, 2f, 3f, color = RgbaColor(10, 20, 30, 160))
        )
        val surface = Gtk4DynamicBindings.cairoImageSurfaceCreate(width, height)
        assertNotNull(surface)
        val cr = Gtk4DynamicBindings.cairoCreate(surface)
        assertNotNull(cr)
        try {
            GtkSurfaceRenderHelper.paintSurface(
                cr = cr,
                width = width,
                height = height,
                spec = GtkSurfacePaintSpec(
                    backgroundColor = RgbaColor(255, 255, 255, 70),
                    fallbackRadiusPx = 10f,
                    backdropFilter = combinedFilter
                ),
                behindBgraCapture = { makePatternBuffer() }
            )
            Gtk4DynamicBindings.cairoDestroy(cr)
            val resultWords = GtkSurfaceRenderHelper.cairoSurfaceToPremulBgraWords(surface, width, height)
            assertTrue(resultWords.any { ((it ushr 24) and 0xFF) > 0 })
            assertFalse(resultWords.contentEquals(baseline))
        } finally {
            Gtk4DynamicBindings.cairoSurfaceDestroy(surface)
        }
    }

    @Test
    fun testHeadlessWindowScreenshotCapturesStyledRetainedHierarchy() {
        assertTrue(Gtk4DynamicBindings.ensureLibrariesLoaded())
        val prevInteractive = GtkApplicationNativeImpl.interactiveGtkGuiEnabled
        val prevFactory = GenericUI.activeFactory
        val factory = GtkNativeFactory()
        try {
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = false
            GenericUI.initialize(factory)

            val heroCard = StyledComponent().apply {
                getStyle().apply {
                    width = CssLength.px(300f)
                    height = CssLength.px(70f)
                }
                backgroundColor = RgbaColor(24, 110, 220, 255)
                borderRadiusPx = 12f
            }
            val glassOverlay = StyledContainer().apply {
                getStyle().apply {
                    width = CssLength.px(280f)
                    height = CssLength.px(44f)
                }
                backgroundColor = RgbaColor(255, 255, 255, 96)
                borderRadiusPx = 8f
                backdropFilter = CssFilter(blur = CssLength.px(4f), saturate = 1.25f)
                addChild(Text("Frosted Overlay"))
            }
            val nativeBtn = Button(text = "Native OK")
            val bootstrapBtn = Button(text = "Delete", type = BootstrapType.DANGER)

            val root = VStack().apply {
                addChild(heroCard)
                addChild(glassOverlay)
                addChild(nativeBtn)
                addChild(bootstrapBtn)
            }
            val window = Window(title = "Headless Screenshot").apply {
                setWindowSize(Size(360, 260))
                setContent(root)
            }
            window.show(factory)

            val winImpl = GtkWindowNativeImpl.activeWindows.lastOrNull()
            assertNotNull(winImpl)
            val shot = GtkNativeWindowHost.captureWindowScreenshot(winImpl, 360, 260)
            assertNotNull(shot)
            assertEquals(360, shot.width)
            assertEquals(260, shot.height)
            assertEquals(PixelFormat.RGBA_8888, shot.pixelFormat)

            val distinctRgbColors = HashSet<Int>()
            for (y in 0 until shot.height step 4) {
                for (x in 0 until shot.width step 4) {
                    val idx = (y * shot.width + x) * 4
                    val key = ((shot.bytes[idx].toInt() and 0xFF) shl 16) or
                        ((shot.bytes[idx + 1].toInt() and 0xFF) shl 8) or
                        (shot.bytes[idx + 2].toInt() and 0xFF)
                    distinctRgbColors.add(key)
                }
            }
            assertTrue(distinctRgbColors.size > 4, "Expected multi-widget screenshot pixels, saw ${distinctRgbColors.size}")
            window.close()
        } finally {
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun testGtkInteractiveWindowRetainedRenderUnderDisplayOrOffscreen() {
        assertTrue(LinuxGlibGioBindings.ensureLoaded())
        assertTrue(Gtk4DynamicBindings.ensureLibrariesLoaded())

        val hasDisplay = getenv("DISPLAY")?.toKString()?.isNotBlank() == true ||
            getenv("WAYLAND_DISPLAY")?.toKString()?.isNotBlank() == true
        if (!hasDisplay) {
            return
        }

        val prevInteractive = GtkApplicationNativeImpl.interactiveGtkGuiEnabled
        val prevVisualTest = GtkNativeWindowHost.visualTestMode
        val prevFactory = GenericUI.activeFactory
        val factory = GtkNativeFactory()
        try {
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = true
            GtkNativeWindowHost.visualTestMode = true
            GenericUI.initialize(factory)

            val labelField = TextField(value = "gtk4@example.org", label = "LabelField")
            val primaryBtn = Button(text = "Submit", type = BootstrapType.PRIMARY)
            val check = CheckBox(label = "Enable GTK4", checked = true)

            val root = Container()
            root.addChild(Text("GTK4 Retained Widget Window Test"))
            root.addChild(labelField)
            root.addChild(primaryBtn)
            root.addChild(check)

            val window = Window(title = "GTK4 Xvfb Test Window")
            window.setWindowSize(Size(640, 480))
            window.setContent(root)
            window.show(factory)

            GtkNativeWindowHost.runEventLoop()

            val windowImpl = GtkWindowNativeImpl.activeWindows.lastOrNull()
            assertNotNull(windowImpl)
            val session = GtkNativeWindowHost.sessionForWindow(windowImpl)
            assertNotNull(session)
            assertNotNull(session.rootVBox)
            assertNotNull(session.contentLayoutBox)
            assertTrue(session.paintedFrameCount >= 1)
            assertTrue(session.lastPaintedWidth > 0)
            assertTrue(session.lastPaintedHeight > 0)
            val realizedField = findWidgetNativeImpl<GtkTextInputNativeImpl>(windowImpl.nativeWidget)
            assertEquals("LabelField", realizedField?.getLabel())

            val shot = GtkNativeWindowHost.captureWindowScreenshot(windowImpl, 640, 480)
            assertNotNull(shot)
            assertEquals(640, shot.width)
            assertEquals(480, shot.height)

            window.close()
        } finally {
            GtkNativeWindowHost.terminateEventLoop()
            GtkNativeWindowHost.visualTestMode = prevVisualTest
            GtkApplicationNativeImpl.interactiveGtkGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }

    private inline fun <reified T> findWidgetNativeImpl(root: DesktopNativeWidget): T? {
        val queue = ArrayDeque<DesktopNativeWidget>()
        queue.addLast(root)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            val impl = cur.attributes["nativeImpl"]
            if (impl is T) return impl
            for (child in cur.children) {
                queue.addLast(child)
            }
        }
        return null
    }

    private class LoopbackTcpServer {
        private var listenFd: Int = -1

        fun bindLoopback(): Int = memScoped {
            val hints = alloc<addrinfo>()
            memset(hints.ptr, 0, sizeOf<addrinfo>().convert())
            hints.ai_family = AF_INET
            hints.ai_socktype = SOCK_STREAM
            hints.ai_flags = AI_PASSIVE

            val resPtr = alloc<CPointerVar<addrinfo>>()
            assertEquals(0, getaddrinfo("127.0.0.1", "0", hints.ptr, resPtr.ptr))
            val head = resPtr.value!!
            try {
                val info = head.pointed
                val fd = socket(info.ai_family, info.ai_socktype, info.ai_protocol)
                assertTrue(fd >= 0)
                val yes = alloc<kotlinx.cinterop.IntVar>()
                yes.value = 1
                setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, yes.ptr, sizeOf<kotlinx.cinterop.IntVar>().convert())
                assertEquals(0, posixBind(fd, info.ai_addr, info.ai_addrlen))
                assertEquals(0, listen(fd, 4))
                listenFd = fd

                val sa = alloc<sockaddr_storage>()
                val saLen = alloc<socklen_tVar>()
                saLen.value = sizeOf<sockaddr_storage>().convert()
                assertEquals(0, getsockname(fd, sa.ptr.reinterpret<sockaddr>(), saLen.ptr))
                val hostBuf = allocArray<ByteVar>(64)
                val servBuf = allocArray<ByteVar>(32)
                assertEquals(
                    0,
                    getnameinfo(
                        sa.ptr.reinterpret<sockaddr>(),
                        saLen.value,
                        hostBuf,
                        64u.convert(),
                        servBuf,
                        32u.convert(),
                        NI_NUMERICHOST or NI_NUMERICSERV
                    )
                )
                servBuf.toKString().toInt()
            } finally {
                freeaddrinfo(head)
            }
        }

        fun acceptOne(timeoutMs: Int): Int = memScoped {
            if (listenFd < 0) return -1
            val pfd = allocArray<pollfd>(1)
            pfd[0].fd = listenFd
            pfd[0].events = POLLIN.toShort()
            if (poll(pfd, 1.convert(), timeoutMs) <= 0) return -1
            accept(listenFd, null, null)
        }

        fun recvUpTo(fd: Int, maxBytes: Int): ByteArray {
            val buf = ByteArray(maxBytes)
            val n = buf.usePinned { pinned ->
                recv(fd, pinned.addressOf(0), buf.size.convert(), 0).toInt()
            }
            return if (n > 0) buf.copyOf(n) else ByteArray(0)
        }

        fun readHttpHeaders(fd: Int): String {
            val out = ArrayList<Byte>()
            val single = ByteArray(1)
            while (out.size < 16384) {
                val n = single.usePinned { pinned -> recv(fd, pinned.addressOf(0), 1u, 0).toInt() }
                if (n <= 0) break
                out.add(single[0])
                val s = out.size
                if (s >= 4 &&
                    out[s - 4] == '\r'.code.toByte() &&
                    out[s - 3] == '\n'.code.toByte() &&
                    out[s - 2] == '\r'.code.toByte() &&
                    out[s - 1] == '\n'.code.toByte()
                ) {
                    break
                }
            }
            return out.toByteArray().decodeToString()
        }

        fun readMaskedTextFrame(fd: Int): String? {
            val hdr = readExact(fd, 2) ?: return null
            val len = hdr[1].toInt() and 0x7F
            val masked = (hdr[1].toInt() and 0x80) != 0
            val mask = if (masked) (readExact(fd, 4) ?: return null) else null
            val payload = if (len > 0) (readExact(fd, len) ?: return null) else ByteArray(0)
            if (mask != null) {
                for (i in payload.indices) {
                    payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                }
            }
            return payload.decodeToString()
        }

        private fun readExact(fd: Int, count: Int): ByteArray? {
            val buf = ByteArray(count)
            var off = 0
            while (off < count) {
                val n = buf.usePinned { pinned -> recv(fd, pinned.addressOf(off), (count - off).convert(), 0).toInt() }
                if (n <= 0) return null
                off += n
            }
            return buf
        }

        fun sendAll(fd: Int, data: ByteArray) {
            var off = 0
            while (off < data.size) {
                val n = data.usePinned { pinned ->
                    send(fd, pinned.addressOf(off), (data.size - off).convert(), 0).toInt()
                }
                if (n <= 0) break
                off += n
            }
        }

        fun close() {
            if (listenFd >= 0) {
                platform.posix.close(listenFd)
                listenFd = -1
            }
        }
    }

    private fun sha1(input: ByteArray): ByteArray {
        val bitLen = input.size.toLong() * 8L
        val padZeroes = ((56 - (input.size + 1) % 64) + 64) % 64
        val padded = ByteArray(input.size + 1 + padZeroes + 8)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.size - 1 - i] = ((bitLen ushr (i * 8)) and 0xFFL).toByte()
        }
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()
        val w = IntArray(80)
        var off = 0
        while (off < padded.size) {
            for (i in 0 until 16) {
                val j = off + i * 4
                w[i] = ((padded[j].toInt() and 0xFF) shl 24) or
                    ((padded[j + 1].toInt() and 0xFF) shl 16) or
                    ((padded[j + 2].toInt() and 0xFF) shl 8) or
                    (padded[j + 3].toInt() and 0xFF)
            }
            for (i in 16 until 80) w[i] = (w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]).rotateLeft(1)
            var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
            for (i in 0 until 80) {
                val f: Int; val k: Int
                when {
                    i < 20 -> { f = (b and c) or (b.inv() and d); k = 0x5A827999 }
                    i < 40 -> { f = b xor c xor d; k = 0x6ED9EBA1 }
                    i < 60 -> { f = (b and c) or (b and d) or (c and d); k = 0x8F1BBCDC.toInt() }
                    else -> { f = b xor c xor d; k = 0xCA62C1D6.toInt() }
                }
                val temp = a.rotateLeft(5) + f + e + k + w[i]
                e = d; d = c; c = b.rotateLeft(30); b = a; a = temp
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
            off += 64
        }
        val out = ByteArray(20)
        val hs = intArrayOf(h0, h1, h2, h3, h4)
        for (i in hs.indices) {
            val v = hs[i]
            out[i * 4] = ((v ushr 24) and 0xFF).toByte()
            out[i * 4 + 1] = ((v ushr 16) and 0xFF).toByte()
            out[i * 4 + 2] = ((v ushr 8) and 0xFF).toByte()
            out[i * 4 + 3] = (v and 0xFF).toByte()
        }
        return out
    }
}
