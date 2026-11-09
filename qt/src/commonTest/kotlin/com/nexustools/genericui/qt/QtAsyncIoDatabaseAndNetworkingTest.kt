package com.nexustools.genericui.qt

import com.nexustools.genericui.component.dialog.DialogWindow
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.core.GenericUI
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.FontWeight
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
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
import com.nexustools.genericui.net.socketio.SocketIoClient
import com.nexustools.genericui.qt.impl.QtCheckboxNativeImpl
import com.nexustools.genericui.qt.impl.QtDropdownNativeImpl
import com.nexustools.genericui.qt.impl.QtLazyGridNativeImpl
import com.nexustools.genericui.qt.impl.QtMarkdownViewNativeImpl
import com.nexustools.genericui.qt.impl.QtProgressBarNativeImpl
import com.nexustools.genericui.qt.impl.QtRangeSliderNativeImpl
import com.nexustools.genericui.qt.impl.QtSelectNativeImpl
import com.nexustools.genericui.qt.impl.QtSpinnerNativeImpl
import com.nexustools.genericui.qt.impl.QtTabNativeImpl
import com.nexustools.genericui.qt.impl.QtTableNativeImpl
import com.nexustools.genericui.qt.impl.QtTextInputNativeImpl
import com.nexustools.genericui.qt.impl.QtTextNativeImpl
import com.nexustools.genericui.resources.ResourceID
import com.nexustools.genericui.resources.ResourceType
import com.nexustools.genericui.res.Resources
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class QtAsyncIoDatabaseAndNetworkingTest {

    @AfterTest
    fun tearDown() {
        DefaultTcpSocketNativeImpl.clearLoopbackServers()
        DefaultUdpSocketNativeImpl.clearLoopbackEndpoints()
        DefaultHttpClientNativeImpl.clearStaticRoutes()
    }

    @Test
    fun testQtFileSystemUserDirectoryAndResourceStream() = runSuspend {
        val factory = QtNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.activeFactory = factory
            val fs = factory.createFileSystem()
            assertTrue(fs is QtFileSystemNativeImpl)
            assertEquals(FileSystemAvailability.Always, fs.isAvailable())

            val path = fs.resolve("/tmp/qt_test", "docs", "readme.txt")
            val writeStream = fs.openWritable(path)
            assertTrue(writeStream is QtFileWritableNativeImpl)
            assertEquals("QSaveFile", writeStream.qtDeviceClass)
            writeStream.writeText("Qt6 QFile / QSaveFile Content")
            writeStream.end()
            assertEquals("Qt6 QFile / QSaveFile Content", fs.read(path)?.toString())

            val readStream = fs.openReadable(path)
            assertTrue(readStream is QtFileReadableNativeImpl)
            assertEquals("QFile", readStream.qtDeviceClass)
            assertEquals("Qt6 QFile / QSaveFile Content", readStream.read()?.toString())
            readStream.close()

            val configDirImpl = factory.createConfigDirectory("QtTestSuiteApp", "ui")
            val cacheDirImpl = factory.createCacheDirectory("QtTestSuiteApp", "thumbnails")
            assertTrue(configDirImpl.usesFileSystemBasePath)
            assertTrue(cacheDirImpl.usesFileSystemBasePath)

            val configDir = ConfigDirectory(appName = "QtTestSuiteApp", subdirectory = "ui", factory = factory)
            val cacheDir = CacheDirectory(appName = "QtTestSuiteApp", subdirectory = "thumbnails", factory = factory)
            assertTrue(configDir.write("layout.json", ByteBuffer.fromString("{\"dock\":true}")))
            assertEquals("{\"dock\":true}", configDir.read("layout.json")?.toString())

            val rawResId = ResourceID(0x7f070112, ResourceType.RAW, "qt_qss", "raw/qt_style.qss")
            Resources.activeTable.registerRawText(rawResId, "QMainWindow { background: #1e1e2e; }")
            val resolver = factory.createResourceResolver()
            assertTrue(resolver is QtResourceResolverImpl)
            val resStream = Resources.openStream(rawResId)
            assertTrue(resStream is ReadableByteBuffer)
            assertEquals("QMainWindow { background: #1e1e2e; }", resStream.read()?.toString())
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
    fun testQtDatabaseQSqliteTranslatorAndStreams() = runSuspend {
        val factory = QtNativeFactory()
        val db = factory.createDatabase("QtCatalog", "/tmp/qt_catalog.db", 2)
        assertTrue(db is QtDatabaseNativeImpl)
        assertEquals("QSQLITE", db.driverName)

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
        assertTrue(db.preparedSqlHistory().any { it.sql.startsWith("CREATE TABLE IF NOT EXISTS \"widgets\"") })

        assertTrue(
            db.putRecord(
                "widgets",
                DatabaseRecord("w1").withString("name", "QMainWindow").withLong("priority", 10L)
            )
        )
        assertTrue(
            db.putRecord(
                "widgets",
                DatabaseRecord("w2").withString("name", "QOpenGLWidget").withLong("priority", 20L)
            )
        )

        val query = DatabaseQuery.from("widgets")
            .where(DatabaseFilter.gte("priority", 10L))
            .orderBy("priority", DatabaseSortDirection.DESCENDING)
        val rows = db.query(query)
        assertEquals(2, rows.size)
        assertEquals("w2", rows.first().key)
        assertNotNull(db.lastPreparedSql)
        assertTrue(db.lastBoundQVariants.any { it.startsWith("QVariant(qlonglong(") })

        val writable = db.openWritable("blobs", "icon")
        assertTrue(writable is DatabaseWritableNativeImpl)
        writable.write(byteArrayOf(4, 5, 6))
        writable.end()

        val readable = db.openReadable("blobs", "icon")
        assertTrue(readable is DatabaseReadableNativeImpl)
        assertEquals(listOf<Byte>(4, 5, 6), readable.read()?.toByteArray()?.toList())
        readable.close()
        db.dispose()
    }

    @Test
    fun testQtNetworkingTcpUdpHttpAndWebSocket() = runSuspend {
        val factory = QtNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.activeFactory = factory

            DefaultTcpSocketNativeImpl.registerLoopbackServer(9201) { payload -> payload }
            val tcp = Network.openTcpSocket()
            assertTrue(tcp is QtTcpSocketNativeImpl)
            assertTrue(tcp.connect("127.0.0.1", 9201))
            assertTrue(tcp.writeText("qt-tcp"))
            assertEquals("qt-tcp", tcp.read()?.toString())
            tcp.close()
            tcp.dispose()

            val udpRecv = Network.openUdpSocket()
            val udpSend = Network.openUdpSocket()
            assertTrue(udpRecv is QtUdpSocketNativeImpl)
            assertTrue(udpRecv.bind("127.0.0.1", 0))
            assertTrue(udpSend.bind("127.0.0.1", 0))
            assertEquals(6, udpSend.send("qt-udp".encodeToByteArray(), "127.0.0.1", udpRecv.localPort))
            assertEquals("qt-udp", udpRecv.receive()?.text)
            udpSend.dispose()
            udpRecv.dispose()

            val http = HttpClient()
            http.registerRoute("https://qt.example.org/status") { req ->
                HttpResponse(statusCode = 200, statusText = "OK", bodyBytes = "qnam-ok".encodeToByteArray(), url = req.url)
            }
            val conn = http.get("https://qt.example.org/status")
            assertTrue(conn is QtHttpConnectionNativeImpl)
            assertTrue(conn.request is QtHttpRequestNativeImpl)
            assertTrue(conn.response is QtHttpResponseNativeImpl)
            assertEquals(200, conn.statusCode)
            assertEquals("qnam-ok", conn.bodyText)
            assertEquals("qnam-ok", conn.read()?.toString())
            conn.close()
            http.dispose()

            val ws = factory.createWebSocket()
            assertTrue(ws is QtWebSocketNativeImpl)
            ws.dispose()

            val sio = SocketIoClient("http://localhost:4001")
            assertTrue(sio.connect())
            assertTrue(sio.isConnected)
            assertEquals(listOf("ok"), sio.emitWithAck("ping", "qt"))
            sio.disconnect()
            sio.dispose()
        } finally {
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun testQtAttachedNativeDialogWindowLifecycleAndZOrder() {
        val factory = QtNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.initialize(factory)
            val mainWindow = Window(title = "Qt Main Window")
            mainWindow.setContent(Container())
            mainWindow.show(factory)
            val mainImpl = mainWindow.ensureNativeImpl() as QtWindowNativeImpl

            val dialog = DialogWindow(title = "Qt Preferences", modal = true)
            dialog.setContent(Container())
            dialog.setWindowSize(Size(440, 300))
            dialog.open(mainWindow)

            assertTrue(dialog.isWindowShown)
            assertTrue(mainWindow.attachedDialogWindows.contains(dialog))
            val dialogImpl = dialog.ensureNativeImpl() as QtDialogWindowNativeImpl
            assertTrue(dialogImpl.isSeparateNativeWindow)
            assertFalse(dialogImpl.inWindowOverlay)
            assertTrue(dialogImpl.attachedToMainWindow)
            assertEquals(true, dialogImpl.nativeWidget.attributes["isSeparateNativeWindow"])
            assertEquals(false, dialogImpl.nativeWidget.attributes["inWindowOverlay"])
            assertEquals(true, dialogImpl.nativeWidget.attributes["attachedToMainWindow"])

            mainWindow.bringToFront()
            assertTrue(dialogImpl.zOrderLayer > mainImpl.zOrderLayer)

            dialog.close()
            assertFalse(dialog.isWindowShown)
            assertFalse(mainWindow.attachedDialogWindows.contains(dialog))
        } finally {
            GenericUI.activeFactory = prevFactory
        }
    }

    @Test
    fun testQtPerComponentNativeImplsAndControlRenderUtil() {
        val factory = QtNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.initialize(factory)

            val buttonImpl = factory.createButton() as QtButtonNativeImpl
            buttonImpl.setText("Save")
            buttonImpl.applyComponentStyle(
                ComponentStyle().apply {
                    backgroundColor = RgbaColor(255, 0, 0)
                    color = RgbaColor(0, 255, 0)
                    fontSize = CssLength(24f)
                }
            )
            assertFalse(buttonImpl.acceptsAestheticSkinStyles())
            assertFalse(buttonImpl.nativeWidget.attributes.containsKey("backgroundColor"))
            assertFalse(buttonImpl.nativeWidget.attributes.containsKey("textColor"))
            assertEquals("true", buttonImpl.nativeWidget.attributes["useSystemControlAppearance"])
            assertEquals("unmodified_native_control", buttonImpl.nativeWidget.attributes["nativeControlRenderMode"])

            val nativePixels = IntArray(96 * 28)
            buttonImpl.renderDesktopNode(nativePixels, Rect(0, 0, 96, 28))
            assertEquals(false, buttonImpl.nativeWidget.attributes["renderedOffscreenEmptyTextSprite"])
            assertTrue(nativePixels.any { ((it ushr 24) and 0xFF) > 0 })

            buttonImpl.setType(BootstrapType.PRIMARY)
            assertEquals("false", buttonImpl.nativeWidget.attributes["useSystemControlAppearance"])
            assertEquals("offscreen_empty_text_sprite_tint", buttonImpl.nativeWidget.attributes["nativeControlRenderMode"])

            val tintedPixels = IntArray(96 * 28)
            buttonImpl.renderDesktopNode(tintedPixels, Rect(0, 0, 96, 28))
            assertEquals(true, buttonImpl.nativeWidget.attributes["renderedOffscreenEmptyTextSprite"])
            assertEquals(true, buttonImpl.nativeWidget.attributes["contrastingTextRenderedAfterTint"])
            assertTrue(tintedPixels.any { ((it ushr 24) and 0xFF) > 0 })

            val textImpl = factory.createText() as QtTextNativeImpl
            textImpl.setText("Hello Qt6")
            textImpl.applyComponentStyle(
                ComponentStyle().apply {
                    color = RgbaColor(0x11, 0x22, 0x33)
                    fontSize = CssLength(18f)
                    fontWeight = FontWeight.BOLD
                }
            )
            assertEquals(RgbaColor(0x11, 0x22, 0x33), textImpl.nativeWidget.attributes["textColor"])
            assertEquals(18f, textImpl.nativeWidget.attributes["fontSizePx"])
            assertEquals("BOLD", textImpl.nativeWidget.attributes["fontWeight"])

            val markdownImpl = factory.createMarkdownView() as QtMarkdownViewNativeImpl
            markdownImpl.setMarkdown("# Heading")
            markdownImpl.applyComponentStyle(ComponentStyle().apply { color = RgbaColor(0x22, 0x44, 0x66) })
            assertEquals(RgbaColor(0x22, 0x44, 0x66), markdownImpl.nativeWidget.attributes["textColor"])

            val textInputImpl = factory.createTextInput() as QtTextInputNativeImpl
            assertFalse(textInputImpl.acceptsAestheticSkinStyles())

            val checkboxImpl = factory.createCheckbox() as QtCheckboxNativeImpl
            assertFalse(checkboxImpl.acceptsAestheticSkinStyles())

            val selectImpl = factory.createSelect() as QtSelectNativeImpl
            assertFalse(selectImpl.acceptsAestheticSkinStyles())

            val dropdownImpl = factory.createDropdown() as QtDropdownNativeImpl
            assertFalse(dropdownImpl.acceptsAestheticSkinStyles())

            val sliderImpl = factory.createRangeSlider() as QtRangeSliderNativeImpl
            assertFalse(sliderImpl.acceptsAestheticSkinStyles())

            val progressImpl = factory.createProgressBar() as QtProgressBarNativeImpl
            assertFalse(progressImpl.acceptsAestheticSkinStyles())

            val spinnerImpl = factory.createSpinner() as QtSpinnerNativeImpl
            assertFalse(spinnerImpl.acceptsAestheticSkinStyles())

            val containerImpl = factory.createContainer() as QtContainerNativeImpl
            assertFalse(containerImpl.acceptsAestheticSkinStyles())

            val tabContainerImpl = factory.createTabContainer() as QtTabContainerNativeImpl
            assertFalse(tabContainerImpl.acceptsAestheticSkinStyles())

            val tabImpl = factory.createTab() as QtTabNativeImpl
            assertFalse(tabImpl.acceptsAestheticSkinStyles())

            val tableImpl = factory.createTable() as QtTableNativeImpl
            tableImpl.setColumnWeights(floatArrayOf(2f, 1f, 1f))
            assertEquals(listOf(2f, 1f, 1f), tableImpl.getColumnWeights())

            val lazyGridImpl = factory.createLazyGrid() as QtLazyGridNativeImpl
            lazyGridImpl.setColumns(4)
            lazyGridImpl.setMinColumnWidthPx(180)
            assertEquals(180, lazyGridImpl.getMinColumnWidthPx())
            assertEquals(4, lazyGridImpl.resolveEffectiveColumns(760))
            assertEquals(2, lazyGridImpl.resolveEffectiveColumns(400))
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
