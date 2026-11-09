package com.nexustools.genericui.gtk

import com.nexustools.genericui.component.dialog.DialogWindow
import com.nexustools.genericui.component.layout.Container
import com.nexustools.genericui.component.shell.Window
import com.nexustools.genericui.core.GenericUI
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

class GtkAsyncIoDatabaseAndNetworkingTest {

    @AfterTest
    fun tearDown() {
        DefaultTcpSocketNativeImpl.clearLoopbackServers()
        DefaultUdpSocketNativeImpl.clearLoopbackEndpoints()
        DefaultHttpClientNativeImpl.clearStaticRoutes()
    }

    @Test
    fun testGtkFileSystemUserDirectoryAndResourceStream() = runSuspend {
        val factory = GtkNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.activeFactory = factory
            val fs = factory.createFileSystem()
            assertTrue(fs is GtkFileSystemNativeImpl)
            assertEquals(FileSystemAvailability.Always, fs.isAvailable())

            val path = fs.resolve("/tmp/gtk_test", "docs", "readme.txt")
            val writeStream = fs.openWritable(path)
            assertTrue(writeStream is GtkFileWritableNativeImpl)
            assertEquals("GFileOutputStream", writeStream.gioStreamType)
            writeStream.writeText("GTK GIO GFile Content")
            writeStream.end()
            assertEquals("GTK GIO GFile Content", fs.read(path)?.toString())

            val readStream = fs.openReadable(path)
            assertTrue(readStream is GtkFileReadableNativeImpl)
            assertEquals("GFileInputStream", readStream.gioStreamType)
            assertEquals("GTK GIO GFile Content", readStream.read()?.toString())
            readStream.close()

            val configDirImpl = factory.createConfigDirectory("GtkTestSuiteApp", "ui")
            val cacheDirImpl = factory.createCacheDirectory("GtkTestSuiteApp", "thumbnails")
            assertTrue(configDirImpl.usesFileSystemBasePath)
            assertTrue(cacheDirImpl.usesFileSystemBasePath)

            val configDir = ConfigDirectory(appName = "GtkTestSuiteApp", subdirectory = "ui", factory = factory)
            val cacheDir = CacheDirectory(appName = "GtkTestSuiteApp", subdirectory = "thumbnails", factory = factory)
            assertTrue(configDir.write("layout.json", ByteBuffer.fromString("{\"dock\":true}")))
            assertEquals("{\"dock\":true}", configDir.read("layout.json")?.toString())

            val rawResId = ResourceID(0x7f070111, ResourceType.RAW, "gtk_css", "raw/gtk_style.css")
            Resources.activeTable.registerRawText(rawResId, "window { background: #1e1e2e; }")
            val resolver = factory.createResourceResolver()
            assertTrue(resolver is GtkResourceResolverImpl)
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
    fun testGtkDatabaseSqliteTranslatorAndStreams() = runSuspend {
        val factory = GtkNativeFactory()
        val db = factory.createDatabase("GtkCatalog", "/tmp/gtk_catalog.db", 2)
        assertTrue(db is GtkDatabaseNativeImpl)
        assertEquals("sqlite3_open_v2", db.sqliteOpenSymbol)

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
                DatabaseRecord("w1").withString("name", "GtkHeaderBar").withLong("priority", 10L)
            )
        )
        assertTrue(
            db.putRecord(
                "widgets",
                DatabaseRecord("w2").withString("name", "GtkGLArea").withLong("priority", 20L)
            )
        )

        val query = DatabaseQuery.from("widgets")
            .where(DatabaseFilter.gte("priority", 10L))
            .orderBy("priority", DatabaseSortDirection.DESCENDING)
        val rows = db.query(query)
        assertEquals(2, rows.size)
        assertEquals("w2", rows.first().key)
        assertNotNull(db.lastPreparedSql)
        assertTrue(db.lastSqliteBindCalls.any { it.startsWith("sqlite3_bind_int64") })

        val writable = db.openWritable("blobs", "icon")
        assertTrue(writable is DatabaseWritableNativeImpl)
        writable.write(byteArrayOf(9, 8, 7))
        writable.end()

        val readable = db.openReadable("blobs", "icon")
        assertTrue(readable is DatabaseReadableNativeImpl)
        assertEquals(listOf<Byte>(9, 8, 7), readable.read()?.toByteArray()?.toList())
        readable.close()
        db.dispose()
    }

    @Test
    fun testGtkNetworkingTcpUdpHttpAndWebSocket() = runSuspend {
        val factory = GtkNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.activeFactory = factory

            DefaultTcpSocketNativeImpl.registerLoopbackServer(9101) { payload -> payload }
            val tcp = Network.openTcpSocket()
            assertTrue(tcp is GtkTcpSocketNativeImpl)
            assertTrue(tcp.connect("127.0.0.1", 9101))
            assertTrue(tcp.writeText("gtk-tcp"))
            assertEquals("gtk-tcp", tcp.read()?.toString())
            tcp.close()
            tcp.dispose()

            val udpRecv = Network.openUdpSocket()
            val udpSend = Network.openUdpSocket()
            assertTrue(udpRecv is GtkUdpSocketNativeImpl)
            assertTrue(udpRecv.bind("127.0.0.1", 0))
            assertTrue(udpSend.bind("127.0.0.1", 0))
            assertEquals(7, udpSend.send("gtk-udp".encodeToByteArray(), "127.0.0.1", udpRecv.localPort))
            assertEquals("gtk-udp", udpRecv.receive()?.text)
            udpSend.dispose()
            udpRecv.dispose()

            val http = HttpClient()
            http.registerRoute("https://gtk.example.org/status") { req ->
                HttpResponse(statusCode = 200, statusText = "OK", bodyBytes = "soup-ok".encodeToByteArray(), url = req.url)
            }
            val conn = http.get("https://gtk.example.org/status")
            assertTrue(conn is GtkHttpConnectionNativeImpl)
            assertTrue(conn.request is GtkHttpRequestNativeImpl)
            assertTrue(conn.response is GtkHttpResponseNativeImpl)
            assertEquals(200, conn.statusCode)
            assertEquals("soup-ok", conn.bodyText)
            assertEquals("soup-ok", conn.read()?.toString())
            conn.close()
            http.dispose()

            val ws = factory.createWebSocket()
            assertTrue(ws is GtkWebSocketNativeImpl)
            ws.dispose()

            val sio = SocketIoClient("http://localhost:4000")
            assertTrue(sio.connect())
            assertTrue(sio.isConnected)
            assertEquals(listOf("ok"), sio.emitWithAck("ping", "gtk"))
            sio.disconnect()
            sio.dispose()
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
            val mainImpl = mainWindow.ensureNativeImpl() as GtkWindowNativeImpl

            val dialog = DialogWindow(title = "GTK Preferences", modal = true)
            dialog.setContent(Container())
            dialog.setWindowSize(Size(440, 300))
            dialog.open(mainWindow)

            assertTrue(dialog.isWindowShown)
            assertTrue(mainWindow.attachedDialogWindows.contains(dialog))
            val dialogImpl = dialog.ensureNativeImpl() as GtkDialogWindowNativeImpl
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
    fun testGtkPerComponentNativeImplsAndControlRenderUtil() {
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

            val textImpl = factory.createText() as GtkTextNativeImpl
            textImpl.setText("Hello GTK4")
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

            val markdownImpl = factory.createMarkdownView() as GtkMarkdownViewNativeImpl
            markdownImpl.setMarkdown("# Heading")
            markdownImpl.applyComponentStyle(ComponentStyle().apply { color = RgbaColor(0x22, 0x44, 0x66) })
            assertEquals(RgbaColor(0x22, 0x44, 0x66), markdownImpl.nativeWidget.attributes["textColor"])

            val textInputImpl = factory.createTextInput() as GtkTextInputNativeImpl
            assertFalse(textInputImpl.acceptsAestheticSkinStyles())

            val checkboxImpl = factory.createCheckbox() as GtkCheckboxNativeImpl
            assertFalse(checkboxImpl.acceptsAestheticSkinStyles())

            val selectImpl = factory.createSelect() as GtkSelectNativeImpl
            assertFalse(selectImpl.acceptsAestheticSkinStyles())

            val dropdownImpl = factory.createDropdown() as GtkDropdownNativeImpl
            assertFalse(dropdownImpl.acceptsAestheticSkinStyles())

            val sliderImpl = factory.createRangeSlider() as GtkRangeSliderNativeImpl
            assertFalse(sliderImpl.acceptsAestheticSkinStyles())

            val progressImpl = factory.createProgressBar() as GtkProgressBarNativeImpl
            assertFalse(progressImpl.acceptsAestheticSkinStyles())

            val spinnerImpl = factory.createSpinner() as GtkSpinnerNativeImpl
            assertFalse(spinnerImpl.acceptsAestheticSkinStyles())

            val containerImpl = factory.createContainer() as GtkContainerNativeImpl
            assertFalse(containerImpl.acceptsAestheticSkinStyles())

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
