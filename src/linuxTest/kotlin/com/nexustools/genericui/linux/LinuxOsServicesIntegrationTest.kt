@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.component.dialog.FileTypeFilter
import com.nexustools.genericui.component.dialog.SelectedFile
import com.nexustools.genericui.component.system.PreferenceElement
import com.nexustools.genericui.core.GenericUiAppDescriptor
import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.dialog.FileBrowserMode
import com.nexustools.genericui.gtk.GtkFileBrowserDialogNativeImpl
import com.nexustools.genericui.gtk.GtkLauncher
import com.nexustools.genericui.gtk.GtkNativeFactory
import com.nexustools.genericui.gtk.GtkStandardDialogsNativeImpl
import com.nexustools.genericui.gtk.GtkSystemTrayNativeImpl
import com.nexustools.genericui.gtk.GtkUiTimerNativeImpl
import com.nexustools.genericui.linux.LinuxCurlBindings
import com.nexustools.genericui.linux.LinuxGdkPixbufBindings
import com.nexustools.genericui.linux.LinuxGdkPixbufImageCodecNativeImpl
import com.nexustools.genericui.linux.LinuxGioNetworkMonitorNativeImpl
import com.nexustools.genericui.linux.LinuxGlibGioBindings
import com.nexustools.genericui.linux.LinuxOpenSslBindings
import com.nexustools.genericui.linux.LinuxSqliteDatabaseNativeImpl
import com.nexustools.genericui.linux.LinuxXdgPreferenceStoreImpl
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageColorSpace
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageEncodeOptions
import com.nexustools.genericui.image.ImageFormat
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.PixelFormat
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LinuxOsServicesIntegrationTest {

    @Test
    fun testLinuxXdgPreferenceStoreBsonPersistence() {
        val appName = "GenericUiLinuxPrefTest_${getpid()}"
        val store1 = LinuxXdgPreferenceStoreImpl(appName)
        try {
            store1.clear()
            store1.writeElement("theme", PreferenceElement.ofString("dark"))
            store1.writeElement("count", PreferenceElement.ofInt(42))
            store1.writeElement("enabled", PreferenceElement.ofBoolean(true))
            val specialKeys = listOf("window:pos", "path/to/key", "100%raw", ".hidden", "CON", "~", "")
            for (k in specialKeys) {
                store1.writeElement(k, PreferenceElement.ofString("linux_$k"))
            }
            assertTrue(store1.flush())
            assertFalse(DesktopConfigDirectoryHelper.fileExists("${store1.configDirectory}/.preference_keys"))
            assertFalse(DesktopConfigDirectoryHelper.fileExists("${store1.configDirectory}/.user_dir_manifest"))

            val store2 = LinuxXdgPreferenceStoreImpl(appName)
            try {
                assertEquals("dark", store2.readElement("theme")?.stringValue)
                assertEquals(42, store2.readElement("count")?.asInt())
                assertEquals(true, store2.readElement("enabled")?.asBoolean())
                assertTrue(store2.contains("theme"))
                assertEquals(setOf("theme", "count", "enabled") + specialKeys, store2.keys())
                for (k in specialKeys) {
                    assertTrue(store2.contains(k))
                    assertEquals("linux_$k", store2.readElement(k)?.stringValue)
                }
            } finally {
                store2.clear()
                store2.dispose()
            }
        } finally {
            store1.clear()
            store1.dispose()
        }
    }

    @Test
    fun testLinuxGdkPixbufImageCodecPngJpegAndWebpRoundTrip() {
        val codec = LinuxGdkPixbufImageCodecNativeImpl()
        try {
            val w = 4
            val h = 4
            val rgba = ByteArray(w * h * 4)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = (y * w + x) * 4
                    rgba[idx] = if ((x + y) % 2 == 0) 0xFF.toByte() else 0x20.toByte()
                    rgba[idx + 1] = 0x80.toByte()
                    rgba[idx + 2] = 0x40.toByte()
                    rgba[idx + 3] = 0xFF.toByte()
                }
            }
            val buffer = ImageBuffer(w, h, PixelFormat.RGBA_8888, ImageColorSpace.SRGB, rgba)

            for (format in listOf(ImageFormat.PNG, ImageFormat.JPEG, ImageFormat.WEBP)) {
                val encoded = codec.encode(buffer, ImageEncodeOptions(format = format, quality = 90))
                assertNotNull(encoded, "Expected non-null bytes for $format")
                assertTrue(encoded.isNotEmpty(), "Expected non-empty bytes for $format")

                val source = ImageSource.fromBytes(encoded, format)
                val meta = codec.probeMetadata(source)
                assertNotNull(meta, "Expected non-null metadata for $format")
                assertEquals(w, meta.width)
                assertEquals(h, meta.height)

                val decoded = codec.decode(source, ImageDecodeOptions())
                assertNotNull(decoded, "Expected decoded buffer for $format")
                assertEquals(w, decoded.width)
                assertEquals(h, decoded.height)
            }
        } finally {
            codec.dispose()
        }
    }

    @Test
    fun testLinuxSharedLibraryBindingsAndGioNetworkMonitor() {
        assertTrue(LinuxGlibGioBindings.ensureLoaded())
        assertTrue(LinuxOpenSslBindings.ensureLoaded())
        assertTrue(LinuxCurlBindings.ensureLoaded())
        assertTrue(LinuxGdkPixbufBindings.ensureLoaded())

        val memDb = LinuxSqliteDatabaseNativeImpl("MemProbe", ":memory:", 1)
        memDb.dispose()

        val monitor = LinuxGioNetworkMonitorNativeImpl()
        try {
            monitor.setOnConnectivityChanged { _ -> }
            // Just validate live query executes without error on host/container network stack.
            monitor.isOnline()
        } finally {
            monitor.dispose()
        }
    }

    @Test
    fun testGtkDialogsSystemTrayTimersAndLauncherPrepare() {
        val tray = GtkSystemTrayNativeImpl()
        assertTrue(tray.isSupported)
        tray.setIcon("/usr/share/icons/hicolor/scalable/apps/app.svg")
        tray.setTooltip("GenericUI Tray")
        tray.setBadgeText("3")
        tray.setOnTrayIconClicked {}
        assertEquals("GenericUI Tray", tray.tooltipText)
        assertEquals("3", tray.badgeText)
        tray.dispose()

        val fileDlg = GtkFileBrowserDialogNativeImpl()
        fileDlg.simulatedSelectedPaths = listOf("/tmp/sample_linux.txt")
        var pickedFiles: List<SelectedFile> = emptyList()
        fileDlg.showFileBrowser(
            parentWindow = null,
            request = FileBrowserRequest(
                mode = FileBrowserMode.OPEN_SINGLE_FILE,
                title = "Open File",
                filters = listOf(FileTypeFilter("Text Files", listOf("txt")))
            ),
            callback = object : FileBrowserCallback {
                override fun onFilesSelected(files: List<SelectedFile>, selectedFilter: FileTypeFilter?) {
                    pickedFiles = files
                }

                override fun onCancelled() {}
            }
        )
        assertEquals("gtk_file_dialog_open", fileDlg.gtkEntryPoint)
        assertEquals(listOf("/tmp/sample_linux.txt"), pickedFiles.map { it.pathOrUri })

        val stdDlgs = GtkStandardDialogsNativeImpl()
        assertFailsWith<UnsupportedOperationException> {
            stdDlgs.showAlert(null, "Notice", "Headless check", BootstrapType.INFO, "OK") {}
        }

        val timer = GtkUiTimerNativeImpl()
        val id = timer.setTimeout(50) {}
        assertTrue(id > 0)
        timer.clearTimer(id)
        timer.dispose()

        val factory = GtkNativeFactory()
        val app = GtkLauncher.prepare(
            descriptor = GenericUiAppDescriptor(appId = "org.nexustools.gtk.test", appName = "GtkPrepareTest")
        )
        assertEquals("GTK4 (Libadwaita / Cairo / PangoCairo / GIO / libcurl / sqlite3)", factory.backendName)
        app.dispose()
    }
}
