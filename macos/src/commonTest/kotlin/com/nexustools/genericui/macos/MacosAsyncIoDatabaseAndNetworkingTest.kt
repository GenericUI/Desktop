package com.nexustools.genericui.macos

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
import com.nexustools.genericui.macos.impl.MacosCheckboxNativeImpl
import com.nexustools.genericui.macos.impl.MacosDropdownNativeImpl
import com.nexustools.genericui.macos.impl.MacosLazyGridNativeImpl
import com.nexustools.genericui.macos.impl.MacosMarkdownViewNativeImpl
import com.nexustools.genericui.macos.impl.MacosProgressBarNativeImpl
import com.nexustools.genericui.macos.impl.MacosRangeSliderNativeImpl
import com.nexustools.genericui.macos.impl.MacosSelectNativeImpl
import com.nexustools.genericui.macos.impl.MacosSpinnerNativeImpl
import com.nexustools.genericui.macos.impl.MacosTabNativeImpl
import com.nexustools.genericui.macos.impl.MacosTableNativeImpl
import com.nexustools.genericui.macos.impl.MacosTextInputNativeImpl
import com.nexustools.genericui.macos.impl.MacosTextNativeImpl
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
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.CssLength
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
            mainWindow.setContent(Container())
            mainWindow.show(factory)
            val mainImpl = mainWindow.ensureNativeImpl() as MacosWindowNativeImpl

            val dialog = DialogWindow(title = "macOS Preferences", modal = true)
            dialog.setContent(Container())
            dialog.setWindowSize(Size(440, 300))
            dialog.open(mainWindow)

            assertTrue(dialog.isWindowShown)
            assertTrue(mainWindow.attachedDialogWindows.contains(dialog))
            val dialogImpl = dialog.ensureNativeImpl() as MacosDialogWindowNativeImpl
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
    fun testMacosPerComponentNativeImplsAndControlRenderUtil() {
        val factory = MacosNativeFactory()
        val prevFactory = GenericUI.activeFactory
        try {
            GenericUI.initialize(factory)

            val buttonImpl = factory.createButton() as MacosButtonNativeImpl
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

            val textImpl = factory.createText() as MacosTextNativeImpl
            textImpl.setText("Hello macOS")
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

            val markdownImpl = factory.createMarkdownView() as MacosMarkdownViewNativeImpl
            markdownImpl.setMarkdown("# Heading")
            markdownImpl.applyComponentStyle(ComponentStyle().apply { color = RgbaColor(0x22, 0x44, 0x66) })
            assertEquals(RgbaColor(0x22, 0x44, 0x66), markdownImpl.nativeWidget.attributes["textColor"])

            val textInputImpl = factory.createTextInput() as MacosTextInputNativeImpl
            assertFalse(textInputImpl.acceptsAestheticSkinStyles())

            val checkboxImpl = factory.createCheckbox() as MacosCheckboxNativeImpl
            assertFalse(checkboxImpl.acceptsAestheticSkinStyles())

            val selectImpl = factory.createSelect() as MacosSelectNativeImpl
            assertFalse(selectImpl.acceptsAestheticSkinStyles())

            val dropdownImpl = factory.createDropdown() as MacosDropdownNativeImpl
            assertFalse(dropdownImpl.acceptsAestheticSkinStyles())

            val sliderImpl = factory.createRangeSlider() as MacosRangeSliderNativeImpl
            assertFalse(sliderImpl.acceptsAestheticSkinStyles())

            val progressImpl = factory.createProgressBar() as MacosProgressBarNativeImpl
            assertFalse(progressImpl.acceptsAestheticSkinStyles())

            val spinnerImpl = factory.createSpinner() as MacosSpinnerNativeImpl
            assertFalse(spinnerImpl.acceptsAestheticSkinStyles())

            val containerImpl = factory.createContainer() as MacosContainerNativeImpl
            assertFalse(containerImpl.acceptsAestheticSkinStyles())

            val tabContainerImpl = factory.createTabContainer() as MacosTabContainerNativeImpl
            assertFalse(tabContainerImpl.acceptsAestheticSkinStyles())

            val tabImpl = factory.createTab() as MacosTabNativeImpl
            assertFalse(tabImpl.acceptsAestheticSkinStyles())

            val tableImpl = factory.createTable() as MacosTableNativeImpl
            tableImpl.setColumnWeights(floatArrayOf(2f, 1f, 1f))
            assertEquals(listOf(2f, 1f, 1f), tableImpl.getColumnWeights())

            val lazyGridImpl = factory.createLazyGrid() as MacosLazyGridNativeImpl
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
