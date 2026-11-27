@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.component.action.Button
import com.nexustools.genericui.component.dialog.DialogWindow
import com.nexustools.genericui.component.form.CheckBox
import com.nexustools.genericui.component.form.TextField
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.layout.styled.StyledContainer
import com.nexustools.genericui.component.layout.VStack
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.component.surface.styled.StyledComponent
import com.nexustools.genericui.component.typography.Text
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.FontWeight
import com.nexustools.genericui.enums.system.BackendTarget
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.FileSystem
import com.nexustools.genericui.io.FileSystemAvailability
import com.nexustools.genericui.io.ReadableByteBuffer
import com.nexustools.genericui.linux.LinuxDatabaseReadableNativeImpl
import com.nexustools.genericui.linux.LinuxDatabaseWritableNativeImpl
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
import com.nexustools.genericui.linux.style.LinuxBackdropFilterController
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
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.css.CssShadow
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
class KdeAsyncIoDatabaseAndNetworkingTest {

    @AfterTest
    fun tearDown() {
        DefaultTcpSocketNativeImpl.clearLoopbackServers()
        DefaultUdpSocketNativeImpl.clearLoopbackEndpoints()
        DefaultHttpClientNativeImpl.clearStaticRoutes()
    }

    @Test
    fun testKdeFileSystemUserDirectoryAndResourceStream() = runBlocking {
        val factory = KdeNativeFactory()
        assertEquals(BackendTarget.Kde, factory.backendTarget)
        val prevFactory = GenericUI.activeFactory
        val rootDir = "/tmp/genericui_kde_fs_test_${getpid()}"
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
            writeStream.writeText("KDE POSIX Content")
            writeStream.end()
            assertEquals("KDE POSIX Content", fs.read(path)?.toString())

            val stat = fs.stat(path)
            assertNotNull(stat)
            assertTrue(stat.isFile)
            assertEquals("KDE POSIX Content".length.toLong(), stat.size)

            assertTrue(fs.copyFile(path, copiedPath, overwrite = true))
            val entries = fs.readdir(docsDir)
            assertTrue(entries.contains("readme.txt"))
            assertTrue(entries.contains("readme_copy.txt"))

            val readStream = fs.openReadable(copiedPath)
            assertTrue(readStream is LinuxPosixFileReadableNativeImpl)
            assertEquals("KDE POSIX Content", readStream.read()?.toString())
            readStream.close()

            assertTrue(fs.unlink(copiedPath))
            assertTrue(fs.rmdir(rootDir, recursive = true))

            val configDirImpl = factory.createConfigDirectory("KdeTestSuiteApp_${getpid()}", "ui")
            val cacheDirImpl = factory.createCacheDirectory("KdeTestSuiteApp_${getpid()}", "thumbnails")
            assertTrue(configDirImpl is LinuxXdgConfigDirectoryImpl)
            assertTrue(cacheDirImpl is LinuxXdgCacheDirectoryImpl)

            val configDir = ConfigDirectory(
                appName = "KdeTestSuiteApp_${getpid()}",
                subdirectory = "ui",
                factory = factory
            )
            val cacheDir = CacheDirectory(
                appName = "KdeTestSuiteApp_${getpid()}",
                subdirectory = "thumbnails",
                factory = factory
            )
            assertTrue(configDir.write("layout.json", ByteBuffer.fromString("{\"dock\":true}")))
            assertEquals("{\"dock\":true}", configDir.read("layout.json")?.toString())

            val rawResId = ResourceID(0x7f070222, ResourceType.RAW, "kde_qss", "raw/kde_style.txt")
            Resources.activeTable.registerRawText(rawResId, "QWidget { color: #eff0f1; }")
            val resolver = factory.createResourceResolver()
            assertTrue(resolver is LinuxResourceResolverImpl)
            val resStream = Resources.openStream(rawResId)
            assertTrue(resStream is ReadableByteBuffer)
            assertEquals("QWidget { color: #eff0f1; }", resStream.read()?.toString())
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
    fun testKdeDatabaseSqliteTransactionsAndStreams() = runBlocking {
        val factory = KdeNativeFactory()
        val fs = factory.createFileSystem()
        val dbPath = "/tmp/genericui_kde_sqlite_${getpid()}.db"
        fs.unlink(dbPath)

        val db = factory.createDatabase("KdeCatalog", dbPath, 2)
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
                DatabaseRecord("w1").withString("name", "KStatusNotifierItem").withLong("priority", 10L)
            )
        )
        assertTrue(
            db.putRecord(
                "widgets",
                DatabaseRecord("w2").withString("name", "KBusyIndicatorWidget").withLong("priority", 20L)
            )
        )

        val query = DatabaseQuery.from("widgets")
            .where(DatabaseFilter.gte("priority", 10L))
            .orderBy("priority", DatabaseSortDirection.DESCENDING)
        val rows = db.query(query)
        assertEquals(2, rows.size)
        assertEquals("w2", rows.first().key)
        assertEquals("KBusyIndicatorWidget", rows.first().getString("name"))

        val writable = db.openWritable("blobs", "icon")
        assertTrue(writable is LinuxDatabaseWritableNativeImpl)
        writable.write(byteArrayOf(4, 5, 6))
        writable.end()

        val readable = db.openReadable("blobs", "icon")
        assertTrue(readable is LinuxDatabaseReadableNativeImpl)
        assertEquals(listOf<Byte>(4, 5, 6), readable.read()?.toByteArray()?.toList())
        readable.close()
        db.dispose()

        val reopened = factory.createDatabase("KdeCatalog", dbPath, 2)
        val persisted = reopened.getRecord("widgets", "w1")
        assertNotNull(persisted)
        assertEquals("KStatusNotifierItem", persisted.getString("name"))
        reopened.dispose()

        fs.unlink(dbPath)
        fs.unlink("$dbPath-wal")
        fs.unlink("$dbPath-shm")
        fs.dispose()
    }

    @Test
    fun testKdeNetworkingLoopbackTcpUdpHttpAndWebSocket() = runBlocking {
        val factory = KdeNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.activeFactory = factory

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
            assertTrue(tcp.writeText("kde-tcp"))
            assertEquals("kde-tcp", tcp.read()?.toString())
            tcp.close()
            tcp.dispose()
            tcpEchoJob.join()
            tcpServer.close()

            val udpRecv = Network.openUdpSocket()
            val udpSend = Network.openUdpSocket()
            assertTrue(udpRecv is LinuxPosixUdpSocketNativeImpl)
            assertTrue(udpRecv.bind("127.0.0.1", 0))
            assertTrue(udpSend.bind("127.0.0.1", 0))
            assertEquals(7, udpSend.send("kde-udp".encodeToByteArray(), "127.0.0.1", udpRecv.localPort))
            val datagram = withTimeout(3_000L) { udpRecv.receive() }
            assertEquals("kde-udp", datagram?.text)
            udpSend.dispose()
            udpRecv.dispose()

            val httpServer = LoopbackTcpServer()
            val httpPort = httpServer.bindLoopback()
            val httpJob = launch(Dispatchers.IO) {
                val clientFd = httpServer.acceptOne(timeoutMs = 5_000)
                if (clientFd >= 0) {
                    httpServer.readHttpHeaders(clientFd)
                    val body = "kde-curl-ok"
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
            assertEquals("kde-curl-ok", liveConn.bodyText)
            liveConn.close()
            httpJob.join()
            httpServer.close()

            http.registerRoute("https://kde.example.org/status") { req ->
                HttpResponse(statusCode = 200, statusText = "OK", bodyBytes = "kde-route-ok".encodeToByteArray(), url = req.url)
            }
            val routeConn = http.get("https://kde.example.org/status")
            assertEquals(200, routeConn.statusCode)
            assertEquals("kde-route-ok", routeConn.read()?.toString())
            routeConn.close()
            http.dispose()

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
            assertTrue(ws.sendText("kde-ws"))
            assertEquals("kde-ws", withTimeout(5_000L) { receivedText.await() })
            ws.close(1000, "Done")
            ws.dispose()
            wsServerJob.join()
            wsServer.close()
        } finally {
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun testKdeAttachedNativeDialogWindowLifecycleAndZOrder() {
        val factory = KdeNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.initialize(factory)
            val mainWindow = Window(title = "KDE Main Window")
            mainWindow.setContent(Container())
            mainWindow.show(factory)
            val mainImpl = KdeWindowNativeImpl.activeWindows.lastOrNull()
            assertNotNull(mainImpl)

            val dialog = DialogWindow(title = "KDE Preferences", modal = true)
            dialog.setContent(Container())
            dialog.setWindowSize(Size(440, 300))
            dialog.open(mainWindow)

            assertTrue(dialog.isWindowShown)
            assertTrue(mainWindow.attachedDialogWindows.contains(dialog))
            val dialogImpl = DesktopDialogWindowNativeImpl.activeDialogs.lastOrNull() as? KdeDialogWindowNativeImpl
            assertNotNull(dialogImpl)
            assertTrue(dialogImpl.isModal)
            assertTrue(dialogImpl.isSeparateNativeWindow)
            assertFalse(dialogImpl.inWindowOverlay)
            assertTrue(dialogImpl.attachedToMainWindow)
            assertEquals("QDialog", dialogImpl.nativeWidget.activeKdeWidgetType)
            assertNotNull(dialogImpl.nativeWidget.qWidgetPtr)

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
    fun testKdePerComponentNativeImplsRetainedWidgetsAndSkinBoundaries() {
        val factory = KdeNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.initialize(factory)

            val buttonImpl = factory.createButton() as KdeButtonNativeImpl
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
            assertEquals("QPushButton", buttonImpl.nativeWidget.activeKdeWidgetType)
            assertFalse(buttonImpl.nativeWidget.attributes.containsKey("backgroundColor"))
            assertFalse(buttonImpl.nativeWidget.attributes.containsKey("textColor"))
            assertEquals("true", buttonImpl.nativeWidget.attributes["useSystemControlAppearance"])

            buttonImpl.setType(BootstrapType.PRIMARY)
            assertFalse(buttonImpl.isNativeDefaultButtonMode())
            assertEquals("QPushButton", buttonImpl.nativeWidget.activeKdeWidgetType)
            assertEquals("false", buttonImpl.nativeWidget.attributes["useSystemControlAppearance"])

            val textImpl = factory.createText() as KdeTextNativeImpl
            textImpl.setText("Hello KDE KF6")
            textImpl.applyComponentStyle(
                ComponentStyle().apply {
                    color = RgbaColor(0x11, 0x22, 0x33)
                    fontSize = CssLength(18f)
                    fontWeight = FontWeight.BOLD
                }
            )
            assertEquals("QLabel", textImpl.nativeWidget.activeKdeWidgetType)

            val badgeImpl = factory.createText("span", "badge") as KdeTextNativeImpl
            badgeImpl.setAttribute("role", "badge")
            badgeImpl.setText("99+")
            assertEquals("KdeBadgeWidget", badgeImpl.nativeWidget.activeKdeWidgetType)

            val markdownImpl = factory.createMarkdownView() as KdeMarkdownViewNativeImpl
            markdownImpl.setMarkdown("# Breeze Markdown")
            markdownImpl.applyComponentStyle(ComponentStyle().apply { color = RgbaColor(0x22, 0x44, 0x66) })
            assertNotNull(markdownImpl.nativeWidget.qWidgetPtr)

            val textInputImpl = factory.createTextInput() as KdeTextInputNativeImpl
            textInputImpl.setValue("alice")
            textInputImpl.setPlaceholder("User")
            textInputImpl.setLabel("Username")
            assertFalse(textInputImpl.acceptsAestheticSkinStyles())
            assertEquals("QLineEdit", textInputImpl.nativeWidget.activeKdeWidgetType)
            assertEquals("Username", textInputImpl.getLabel())

            val dateImpl = factory.createTextInput() as KdeTextInputNativeImpl
            dateImpl.setAttribute("data-control-variant", "date")
            assertTrue(
                dateImpl.nativeWidget.activeKdeWidgetType == "KDateComboBox" ||
                    dateImpl.nativeWidget.activeKdeWidgetType == "QDateEdit"
            )

            val timeImpl = factory.createTextInput() as KdeTextInputNativeImpl
            timeImpl.setAttribute("data-control-variant", "time")
            assertTrue(
                timeImpl.nativeWidget.activeKdeWidgetType == "KTimeComboBox" ||
                    timeImpl.nativeWidget.activeKdeWidgetType == "QTimeEdit"
            )

            val colorImpl = factory.createTextInput() as KdeTextInputNativeImpl
            colorImpl.setAttribute("data-control-variant", "color")
            assertTrue(
                colorImpl.nativeWidget.activeKdeWidgetType == "KColorButton" ||
                    colorImpl.nativeWidget.activeKdeWidgetType == "QPushButton_ColorPicker"
            )

            val numberImpl = factory.createTextInput() as KdeTextInputNativeImpl
            numberImpl.setAttribute("data-control-variant", "number")
            assertEquals("QDoubleSpinBox", numberImpl.nativeWidget.activeKdeWidgetType)

            val checkboxImpl = factory.createCheckbox() as KdeCheckboxNativeImpl
            assertFalse(checkboxImpl.acceptsAestheticSkinStyles())
            checkboxImpl.setLabel("Agree")
            assertEquals("QCheckBox", checkboxImpl.nativeWidget.activeKdeWidgetType)

            val switchImpl = factory.createCheckbox(switchStyle = true) as KdeCheckboxNativeImpl
            assertEquals("KdeSwitchWidget", switchImpl.nativeWidget.activeKdeWidgetType)

            val radioImpl = factory.createCheckbox(switchStyle = false, radioStyle = true) as KdeCheckboxNativeImpl
            assertEquals("QRadioButton", radioImpl.nativeWidget.activeKdeWidgetType)

            val selectImpl = factory.createSelect() as KdeSelectNativeImpl
            assertFalse(selectImpl.acceptsAestheticSkinStyles())
            assertEquals("QComboBox", selectImpl.nativeWidget.activeKdeWidgetType)

            val dropdownImpl = factory.createDropdown() as KdeDropdownNativeImpl
            assertFalse(dropdownImpl.acceptsAestheticSkinStyles())
            assertTrue(dropdownImpl.nativeWidget.activeKdeWidgetType.contains("ToolButton"))

            val sliderImpl = factory.createRangeSlider() as KdeRangeSliderNativeImpl
            assertFalse(sliderImpl.acceptsAestheticSkinStyles())
            assertEquals("QSlider", sliderImpl.nativeWidget.activeKdeWidgetType)

            val progressImpl = factory.createProgressBar() as KdeProgressBarNativeImpl
            assertFalse(progressImpl.acceptsAestheticSkinStyles())
            progressImpl.setProgress(0.6f)
            assertEquals("QProgressBar", progressImpl.nativeWidget.activeKdeWidgetType)

            val spinnerImpl = factory.createSpinner() as KdeSpinnerNativeImpl
            assertFalse(spinnerImpl.acceptsAestheticSkinStyles())
            assertNotEquals("QProgressIndicator", spinnerImpl.nativeWidget.activeKdeWidgetType)
            assertTrue(
                spinnerImpl.nativeWidget.activeKdeWidgetType == "KBusyIndicatorWidget" ||
                    spinnerImpl.nativeWidget.activeKdeWidgetType == "QProgressBar_IndeterminateFallback"
            )

            val containerImpl = factory.createContainer() as KdeContainerNativeImpl
            assertFalse(containerImpl.acceptsAestheticSkinStyles())

            val layeredImpl = factory.createContainer("gui-layered-container", "genericui:layered-container") as KdeContainerNativeImpl
            assertEquals("QWidget_LayeredContainer", layeredImpl.nativeWidget.activeKdeWidgetType)

            val tabContainerImpl = factory.createTabContainer() as KdeTabContainerNativeImpl
            assertFalse(tabContainerImpl.acceptsAestheticSkinStyles())
            assertFalse(tabContainerImpl.supportsNativeFadeTransition)

            val tabImpl = factory.createTab() as KdeTabNativeImpl
            assertFalse(tabImpl.acceptsAestheticSkinStyles())

            val trayImpl = factory.createSystemTray() as KdeSystemTrayNativeImpl
            assertTrue(trayImpl.isSupported)
            trayImpl.dispose()

            val tableImpl = factory.createTable() as KdeTableNativeImpl
            tableImpl.setHeaders(listOf("Col A", "Col B"))
            tableImpl.setRows(listOf(listOf("One", "Two")))
            assertEquals("QTableWidget", tableImpl.nativeWidget.activeKdeWidgetType)

            val lazyGridImpl = factory.createLazyGrid() as KdeLazyGridNativeImpl
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
    fun testGenuineQPushButtonVsBootstrapButtonWidgetAndLiveModeSwap() {
        val factory = KdeNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.initialize(factory)
            val btn = factory.createButton() as KdeButtonNativeImpl
            btn.setText("Execute")
            btn.setIcon("★")
            btn.setEnabled(false)
            btn.setBounds(Rect(12, 24, 140, 36))

            assertTrue(btn.isNativeDefaultButtonMode())
            assertEquals("QPushButton", btn.nativeWidget.activeKdeWidgetType)
            assertEquals("true", btn.nativeWidget.attributes["useSystemControlAppearance"])
            val originalQWidgetPtr = btn.nativeWidget.qWidgetPtr
            assertNotNull(originalQWidgetPtr)

            btn.setType(BootstrapType.SUCCESS)
            assertFalse(btn.isNativeDefaultButtonMode())
            assertEquals("QPushButton", btn.nativeWidget.activeKdeWidgetType)
            assertEquals("false", btn.nativeWidget.attributes["useSystemControlAppearance"])
            assertEquals("Execute", btn.getText())
            assertEquals("★", btn.getIcon())
            assertFalse(btn.isEnabled())
            assertEquals(Rect(12, 24, 140, 36), btn.getBounds())

            btn.setType(null)
            btn.setOutline(true)
            assertEquals("QPushButton", btn.nativeWidget.activeKdeWidgetType)

            btn.setOutline(false)
            assertTrue(btn.isNativeDefaultButtonMode())
            assertEquals("QPushButton", btn.nativeWidget.activeKdeWidgetType)
            assertEquals("Execute", btn.getText())
            assertEquals("★", btn.getIcon())
            assertFalse(btn.isEnabled())
            assertEquals(Rect(12, 24, 140, 36), btn.getBounds())
        } finally {
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun testAllTenBackdropFilterOperationsInKdeAndScreenshotCapture() {
        assertTrue(KdeDynamicBindings.ensureLoaded())
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
                "KDE backdrop filter '$name' must deterministically alter premultiplied BGRA pixels"
            )
        }
    }

    @Test
    fun testHeadlessWindowScreenshotCapturesStyledRetainedHierarchyInKde() {
        assertTrue(KdeDynamicBindings.ensureLoaded())
        val prevInteractive = KdeApplicationNativeImpl.interactiveKdeGuiEnabled
        val prevFactory = GenericUI.activeFactory
        val factory = KdeNativeFactory()
        try {
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = false
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
                addChild(Text("Frosted KDE Overlay"))
            }
            val nativeBtn = Button(text = "Native OK")
            val bootstrapBtn = Button(text = "Delete", type = BootstrapType.DANGER)

            val root = VStack().apply {
                addChild(heroCard)
                addChild(glassOverlay)
                addChild(nativeBtn)
                addChild(bootstrapBtn)
            }
            val window = Window(title = "KDE Headless Screenshot").apply {
                setWindowSize(Size(360, 260))
                setContent(root)
            }
            window.show(factory)

            val winImpl = KdeWindowNativeImpl.activeWindows.lastOrNull()
            assertNotNull(winImpl)
            val shot = KdeNativeWindowHost.captureWindowScreenshot(winImpl, 360, 260)
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
            assertTrue(distinctRgbColors.size > 4, "Expected multi-widget KDE screenshot pixels, saw ${distinctRgbColors.size}")
            window.close()
        } finally {
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = prevInteractive
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun testKdeInteractiveWindowRetainedRenderUnderOffscreenOrDisplay() {
        assertTrue(KdeDynamicBindings.ensureLoaded())
        val prevInteractive = KdeApplicationNativeImpl.interactiveKdeGuiEnabled
        val prevVisualTest = KdeNativeWindowHost.visualTestMode
        val prevFactory = GenericUI.activeFactory
        val factory = KdeNativeFactory()
        try {
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = true
            KdeNativeWindowHost.visualTestMode = true
            GenericUI.initialize(factory)

            val labelField = TextField(value = "kde@example.org", label = "LabelField")
            val primaryBtn = Button(text = "Submit", type = BootstrapType.PRIMARY)
            val check = CheckBox(label = "Enable KF6", checked = true)

            val root = Container()
            root.addChild(Text("KDE Retained Widget Window Test"))
            root.addChild(labelField)
            root.addChild(primaryBtn)
            root.addChild(check)

            val window = Window(title = "KDE Qt6 Offscreen Test Window")
            window.setWindowSize(Size(640, 480))
            window.setContent(root)
            window.show(factory)

            KdeNativeWindowHost.pumpEvents(maxIterations = 6, sleepMsPerStep = 2)

            val windowImpl = KdeWindowNativeImpl.activeWindows.lastOrNull()
            assertNotNull(windowImpl)
            val session = KdeNativeWindowHost.sessionForWindow(windowImpl)
            assertNotNull(session)
            assertTrue(session.shown)
            val realizedField = findWidgetNativeImpl<KdeTextInputNativeImpl>(windowImpl.nativeWidget)
            assertEquals("LabelField", realizedField?.getLabel())

            val shot = KdeNativeWindowHost.captureWindowScreenshot(windowImpl, 640, 480)
            assertNotNull(shot)
            assertEquals(640, shot.width)
            assertEquals(480, shot.height)

            window.close()
        } finally {
            KdeNativeWindowHost.visualTestMode = prevVisualTest
            KdeApplicationNativeImpl.interactiveKdeGuiEnabled = prevInteractive
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
                recv(fd, pinned.addressOf(0), maxBytes.convert(), 0).toInt()
            }
            return if (n <= 0) ByteArray(0) else buf.copyOf(n)
        }

        fun readHttpHeaders(fd: Int): String {
            val out = mutableListOf<Byte>()
            val one = ByteArray(1)
            while (out.size < 8192) {
                val n = one.usePinned { pinned -> recv(fd, pinned.addressOf(0), 1.convert(), 0).toInt() }
                if (n <= 0) break
                out.add(one[0])
                val s = out.size
                if (s >= 4 && out[s - 4] == '\r'.code.toByte() && out[s - 3] == '\n'.code.toByte() &&
                    out[s - 2] == '\r'.code.toByte() && out[s - 1] == '\n'.code.toByte()
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
