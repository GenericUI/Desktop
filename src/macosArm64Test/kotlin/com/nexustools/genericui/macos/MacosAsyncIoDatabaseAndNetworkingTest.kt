@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.apple.AppleDatabaseNativeImpl
import com.nexustools.genericui.apple.AppleFileReadableNativeImpl
import com.nexustools.genericui.apple.AppleFileWritableNativeImpl
import com.nexustools.genericui.apple.AppleHttpConnectionNativeImpl
import com.nexustools.genericui.apple.AppleHttpRequestNativeImpl
import com.nexustools.genericui.apple.AppleHttpResponseNativeImpl
import com.nexustools.genericui.apple.AppleTcpSocketNativeImpl
import com.nexustools.genericui.apple.AppleUdpSocketNativeImpl
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
import com.nexustools.genericui.nativeimpl.io.MemoryReadableNativeImpl
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
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.runBlocking
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.posix.AF_INET
import platform.posix.QOS_CLASS_USER_INITIATED
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_REUSEADDR
import platform.posix.accept
import platform.posix.bind
import platform.posix.close as posixClose
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.memset
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.sockaddr_in
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MacosAsyncIoDatabaseAndNetworkingTest {

    private fun startLoopbackTcpEchoServer(): Int {
        return memScoped {
            val serverFd = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(serverFd >= 0)
            val reuse = alloc<IntVar>()
            reuse.value = 1
            setsockopt(serverFd, SOL_SOCKET, SO_REUSEADDR, reuse.ptr, sizeOf<IntVar>().toUInt())

            val addr = alloc<sockaddr_in>()
            memset(addr.ptr, 0, sizeOf<sockaddr_in>().toULong())
            addr.sin_len = sizeOf<sockaddr_in>().toUByte()
            addr.sin_family = AF_INET.toUByte()
            addr.sin_addr.s_addr = 0x0100007Fu
            addr.sin_port = 0u
            assertEquals(0, bind(serverFd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().toUInt()))
            assertEquals(0, listen(serverFd, 4))

            val bound = alloc<sockaddr_in>()
            val boundLen = alloc<UIntVar>()
            boundLen.value = sizeOf<sockaddr_in>().toUInt()
            assertEquals(0, getsockname(serverFd, bound.ptr.reinterpret(), boundLen.ptr))
            val portRaw = bound.sin_port.toInt() and 0xFFFF
            val port = ((portRaw and 0xFF) shl 8) or ((portRaw ushr 8) and 0xFF)

            val readySem = dispatch_semaphore_create(0)
            dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED.toLong(), 0uL)) {
                dispatch_semaphore_signal(readySem!!)
                val clientFd = accept(serverFd, null, null)
                if (clientFd >= 0) {
                    try {
                        val rx = ByteArray(128)
                        val count = rx.usePinned { pinned ->
                            recv(clientFd, pinned.addressOf(0), rx.size.toULong(), 0).toInt()
                        }
                        if (count > 0) {
                            rx.usePinned { pinned ->
                                send(clientFd, pinned.addressOf(0), count.toULong(), 0)
                            }
                        }
                    } finally {
                        posixClose(clientFd)
                    }
                }
                posixClose(serverFd)
            }
            dispatch_semaphore_wait(readySem!!, DISPATCH_TIME_FOREVER)
            port
        }
    }

    @Test
    fun testMacosStreamsDatabaseResourcesAndNetworking() = runBlocking {
        val factory = MacosNativeFactory()
        val prevFactory = GenericUI.activeFactory
        val tempRoot = "${NSTemporaryDirectory().trimEnd('/')}/macos_suite_${NSUUID().UUIDString}"
        val dbPath = "$tempRoot/macos_catalog.db"
        try {
            GenericUI.activeFactory = factory
            val fs = factory.createFileSystem()
            assertTrue(fs is MacosFileSystemNativeImpl)
            assertEquals(FileSystemAvailability.Always, fs.isAvailable())

            val path = fs.resolve(tempRoot, "docs", "readme.txt")
            val writable = fs.openWritable(path)
            assertTrue(writable is AppleFileWritableNativeImpl)
            writable.writeText("Grand Central Dispatch Content")
            writable.end()
            assertEquals("Grand Central Dispatch Content", fs.read(path)?.toString())

            val readable = fs.openReadable(path)
            assertTrue(readable is AppleFileReadableNativeImpl)
            assertNotNull(readable)
            assertEquals("Grand Central Dispatch Content", readable.read()?.toString())
            readable.close()

            val configDir = ConfigDirectory(appName = "MacosTestSuiteApp", subdirectory = "ui", factory = factory)
            val cacheDir = CacheDirectory(appName = "MacosTestSuiteApp", subdirectory = "thumbnails", factory = factory)
            assertTrue(configDir.write("layout.json", ByteBuffer.fromString("{\"dock\":true}")))
            assertEquals("{\"dock\":true}", configDir.read("layout.json")?.toString())

            val rawResId = ResourceID(0x7f070114, ResourceType.RAW, "macos_plist", "raw/Info.plist")
            Resources.activeTable.registerRawText(rawResId, "<plist/>")
            val resStream = Resources.openStream(rawResId)
            assertTrue(resStream is MemoryReadableNativeImpl)
            assertEquals("<plist/>", resStream.read()?.toString())
            resStream.close()

            val db = factory.createDatabase("MacosCatalog", dbPath, 1)
            assertTrue(db is AppleDatabaseNativeImpl)
            val dbWritable = db.openWritable("blobs", "icon")
            assertTrue(dbWritable is DatabaseWritableNativeImpl)
            dbWritable.write(byteArrayOf(7, 8, 9))
            dbWritable.end()
            val dbReadable = db.openReadable("blobs", "icon")
            assertTrue(dbReadable is DatabaseReadableNativeImpl)
            assertEquals(listOf<Byte>(7, 8, 9), dbReadable.read()?.toByteArray()?.toList())
            dbReadable.close()
            db.dispose()

            val tcpPort = startLoopbackTcpEchoServer()
            val tcp = Network.openTcpSocket()
            assertTrue(tcp is AppleTcpSocketNativeImpl)
            assertTrue(tcp.connect("127.0.0.1", tcpPort))
            assertTrue(tcp.writeText("mac-tcp"))
            assertEquals("mac-tcp", tcp.read()?.toString())
            tcp.close()
            tcp.dispose()

            val udpRecv = Network.openUdpSocket()
            val udpSend = Network.openUdpSocket()
            assertTrue(udpRecv is AppleUdpSocketNativeImpl)
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
            assertTrue(conn is AppleHttpConnectionNativeImpl)
            assertEquals(200, conn.statusCode)
            assertEquals("nsurlsession-ok", conn.bodyText)
            assertEquals("nsurlsession-ok", conn.read()?.toString())
            conn.close()

            val appleClient = factory.createHttpClient()
            val errResp = appleClient.execute(AppleHttpRequestNativeImpl("http://127.0.0.1:1/unreachable", timeoutMs = 600L))
            assertTrue(errResp is AppleHttpResponseNativeImpl)
            assertEquals(0, errResp.statusCode)
            appleClient.dispose()
            http.dispose()

            configDir.clear()
            cacheDir.clear()
            configDir.dispose()
            cacheDir.dispose()
            fs.rmdir(tempRoot, recursive = true)
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
}
