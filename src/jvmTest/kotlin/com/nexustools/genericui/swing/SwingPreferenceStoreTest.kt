package com.nexustools.genericui.swing

import com.nexustools.genericui.component.system.PreferenceChangeListener
import com.nexustools.genericui.component.system.PreferenceElement
import com.nexustools.genericui.component.system.PreferenceObjectCodec
import com.nexustools.genericui.component.system.PreferenceStore
import com.nexustools.genericui.util.BinaryJson
import com.nexustools.genericui.util.BinaryPlist
import com.nexustools.genericui.util.UserDirectory
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwingPreferenceStoreTest {

    private data class AudioRoutingConfig(
        val outputDevice: String,
        val sampleRateHz: Int,
        val bufferFrames: Int,
        val exclusiveMode: Boolean,
        val channelGains: List<Double>
    )

    @Test
    fun `SwingPreferenceStoreImpl writes per-key binary files and reconstructs arbitrary Kotlin data classes`() {
        val tempDir = Files.createTempDirectory("genericui_swing_prefs_test").toFile()
        val uniqueApp = "SampleDesktopApp_${System.nanoTime()}"
        val defaultHostAppDir = SwingPreferenceStoreImpl.resolveHostAppConfigDirectory(uniqueApp)
        try {
            val impl = SwingPreferenceStoreImpl(appName = uniqueApp, customDirectory = tempDir)
            val store = PreferenceStore(appName = uniqueApp, factory = SwingNativeFactory())

            val config = AudioRoutingConfig(
                outputDevice = "WASAPI Low-Latency Output",
                sampleRateHz = 48000,
                bufferFrames = 256,
                exclusiveMode = true,
                channelGains = listOf(1.0, 1.0, 0.85, 0.85)
            )

            impl.writeElement("audio_routing", PreferenceObjectCodec.toElement(config))
            val keyFile = impl.fileForKey("audio_routing")
            assertTrue(keyFile.exists(), "Per-key preference file should exist on disk at ${keyFile.absolutePath}")
            if (impl.isMacOsHost) {
                assertTrue(keyFile.name.endsWith(".plist"))
                assertTrue(BinaryPlist.isBinaryPlist(keyFile.readBytes()))
            } else {
                assertTrue(keyFile.name.endsWith(".bson"))
                assertEquals(BinaryJson.TYPE_OBJECT, keyFile.readBytes()[0])
            }

            // Verify PreferenceStore facade + SwingNativeFactory end-to-end persistence
            store.put("routing_facade", config)
            assertEquals(config, store.get<AudioRoutingConfig>("routing_facade"))

            // Re-open from the same directory to verify disk persistence and reflection reconstruction
            val reopenedImpl = SwingPreferenceStoreImpl(appName = uniqueApp, customDirectory = tempDir)
            val elem = reopenedImpl.readElement("audio_routing")
            val restored = PreferenceObjectCodec.fromElement(elem, AudioRoutingConfig::class)
            assertEquals(config, restored)
            assertTrue(reopenedImpl.keys().contains("audio_routing"))

            reopenedImpl.remove("audio_routing")
            assertFalse(keyFile.exists())
            reopenedImpl.dispose()
            impl.dispose()
            store.clear()
            store.dispose()
        } finally {
            tempDir.deleteRecursively()
            defaultHostAppDir.deleteRecursively()
        }
    }

    @Test
    fun `detects all 3 OS profiles, defaults unrecognized OSes to Linux XDG, and resolves host config directories`() {
        val tempRoot = Files.createTempDirectory("genericui_os_profiles").toFile()
        try {
            val winAppData = File(tempRoot, "win_appdata").apply { mkdirs() }
            val winUserProfile = File(tempRoot, "win_profile").apply { mkdirs() }
            val xdgDir = File(tempRoot, "xdg_config").apply { mkdirs() }
            val homeDir = File(tempRoot, "unix_home").apply { mkdirs() }
            val fallbackHome = File(tempRoot, "fallback_home").apply { mkdirs() }

            // 1. Windows 11 profile
            val winResolvedFromAppData = SwingPreferenceStoreImpl.resolveHostAppConfigDirectory(
                appName = "Studio:App/1",
                osNameOverride = "Windows 11",
                envProvider = { key -> if (key == "APPDATA") winAppData.absolutePath else null },
                userHomeProvider = { fallbackHome.absolutePath }
            )
            assertEquals(File(winAppData, "Studio_App_1").absolutePath, winResolvedFromAppData.absolutePath)

            val winResolvedFromUserProfile = SwingPreferenceStoreImpl.resolveHostAppConfigDirectory(
                appName = "StudioApp",
                osNameOverride = "Windows 10",
                envProvider = { key -> if (key == "USERPROFILE") winUserProfile.absolutePath else null },
                userHomeProvider = { fallbackHome.absolutePath }
            )
            assertEquals(
                File(File(winUserProfile, "AppData/Roaming"), "StudioApp").absolutePath,
                winResolvedFromUserProfile.absolutePath
            )

            val winStore = SwingPreferenceStoreImpl(
                appName = "StudioApp",
                osNameOverride = "Windows 11",
                envProvider = { key -> if (key == "APPDATA") winAppData.absolutePath else null },
                userHomeProvider = { fallbackHome.absolutePath }
            )
            try {
                assertTrue(winStore.isWindowsHost)
                assertFalse(winStore.isMacOsHost)
                assertFalse(winStore.useBinaryPlist)
                assertEquals(".bson", winStore.fileExtension)
                assertEquals(File(winAppData, "StudioApp").absolutePath, winStore.storageDirectory.absolutePath)

                winStore.writeElement("theme", PreferenceElement.ofString("dark"))
                assertTrue(File(winStore.storageDirectory, "theme.bson").isFile)
                assertTrue(BinaryJson.isBinaryJson(File(winStore.storageDirectory, "theme.bson").readBytes()))
                assertFalse(File(winStore.storageDirectory, ".preference_keys").exists())
                assertFalse(File(winStore.storageDirectory, ".user_dir_manifest").exists())
            } finally {
                winStore.dispose()
            }

            // 2. macOS profile ("Mac OS X" and "Darwin" - ensure Darwin is never misdetected as Windows)
            val macResolvedXdg = SwingPreferenceStoreImpl.resolveHostAppConfigDirectory(
                appName = "/Mac:Studio/",
                osNameOverride = "Mac OS X",
                envProvider = { key -> if (key == "XDG_CONFIG_HOME") xdgDir.absolutePath else null },
                userHomeProvider = { fallbackHome.absolutePath }
            )
            assertEquals(File(xdgDir, "Mac_Studio").absolutePath, macResolvedXdg.absolutePath)

            val darwinResolvedHome = SwingPreferenceStoreImpl.resolveHostAppConfigDirectory(
                appName = "DarwinApp",
                osNameOverride = "Darwin",
                envProvider = { key -> if (key == "HOME") homeDir.absolutePath else null },
                userHomeProvider = { fallbackHome.absolutePath }
            )
            assertEquals(File(File(homeDir, ".config"), "DarwinApp").absolutePath, darwinResolvedHome.absolutePath)

            val macStore = SwingPreferenceStoreImpl(
                appName = "DarwinApp",
                osNameOverride = "Mac OS X",
                envProvider = { key -> if (key == "HOME") homeDir.absolutePath else null },
                userHomeProvider = { fallbackHome.absolutePath }
            )
            try {
                assertFalse(macStore.isWindowsHost)
                assertTrue(macStore.isMacOsHost)
                assertTrue(macStore.useBinaryPlist)
                assertEquals(".plist", macStore.fileExtension)
                assertEquals(File(File(homeDir, ".config"), "DarwinApp").absolutePath, macStore.storageDirectory.absolutePath)

                macStore.writeElement("theme", PreferenceElement.ofString("dark"))
                val plistFile = File(macStore.storageDirectory, "theme.plist")
                assertTrue(plistFile.isFile)
                assertTrue(BinaryPlist.isBinaryPlist(plistFile.readBytes()))
                assertFalse(File(macStore.storageDirectory, ".preference_keys").exists())
                assertFalse(File(macStore.storageDirectory, ".user_dir_manifest").exists())
            } finally {
                macStore.dispose()
            }

            // Also verify "Darwin" osNameOverride produces MACOS store profile
            val darwinStore = SwingPreferenceStoreImpl(
                appName = "DarwinProbe",
                customDirectory = File(tempRoot, "darwin_custom"),
                osNameOverride = "Darwin"
            )
            try {
                assertTrue(darwinStore.isMacOsHost)
                assertFalse(darwinStore.isWindowsHost)
                assertEquals(".plist", darwinStore.fileExtension)
                assertFalse(File(darwinStore.storageDirectory, ".preference_keys").exists())
                assertFalse(File(darwinStore.storageDirectory, ".user_dir_manifest").exists())
            } finally {
                darwinStore.dispose()
            }

            // 3. Linux and unrecognized OSes ("FreeBSD", "Solaris", "") -> default to Linux XDG method
            for (osName in listOf("Linux", "FreeBSD", "Solaris", "")) {
                val safeTag = if (osName.isEmpty()) "BlankOs" else osName
                val resolvedXdg = SwingPreferenceStoreImpl.resolveHostAppConfigDirectory(
                    appName = "UnixApp_$safeTag",
                    osNameOverride = osName,
                    envProvider = { key -> if (key == "XDG_CONFIG_HOME") xdgDir.absolutePath else null },
                    userHomeProvider = { fallbackHome.absolutePath }
                )
                assertEquals(File(xdgDir, "UnixApp_$safeTag").absolutePath, resolvedXdg.absolutePath)

                val resolvedFallbackHome = SwingPreferenceStoreImpl.resolveHostAppConfigDirectory(
                    appName = "UnixApp_$safeTag",
                    osNameOverride = osName,
                    envProvider = { null },
                    userHomeProvider = { fallbackHome.absolutePath }
                )
                assertEquals(
                    File(File(fallbackHome, ".config"), "UnixApp_$safeTag").absolutePath,
                    resolvedFallbackHome.absolutePath
                )

                val unixStore = SwingPreferenceStoreImpl(
                    appName = "UnixApp_$safeTag",
                    osNameOverride = osName,
                    envProvider = { key -> if (key == "XDG_CONFIG_HOME") xdgDir.absolutePath else null },
                    userHomeProvider = { fallbackHome.absolutePath }
                )
                try {
                    assertFalse(unixStore.isWindowsHost, "OS '$osName' must not be Windows")
                    assertFalse(unixStore.isMacOsHost, "OS '$osName' must not be macOS")
                    assertFalse(unixStore.useBinaryPlist, "OS '$osName' must use BinaryJson")
                    assertEquals(".bson", unixStore.fileExtension)

                    unixStore.writeElement("mode", PreferenceElement.ofString("xdg-$safeTag"))
                    val bsonFile = File(unixStore.storageDirectory, "mode.bson")
                    assertTrue(bsonFile.isFile)
                    assertTrue(BinaryJson.isBinaryJson(bsonFile.readBytes()))
                    assertFalse(File(unixStore.storageDirectory, ".preference_keys").exists())
                    assertFalse(File(unixStore.storageDirectory, ".user_dir_manifest").exists())
                } finally {
                    unixStore.dispose()
                }
            }

            // Verify SwingUserDirectoryNativeImpl delegates sanitizeName & resolveHostAppConfigDirectory cleanly
            assertEquals(
                SwingPreferenceStoreImpl.sanitizeName("My/App:Name"),
                SwingUserDirectoryNativeImpl.sanitizeName("My/App:Name")
            )
            assertEquals(
                SwingPreferenceStoreImpl.resolveHostAppConfigDirectory("DelegatedApp").absolutePath,
                SwingUserDirectoryNativeImpl.resolveHostAppConfigDirectory("DelegatedApp").absolutePath
            )
            val cacheDir = SwingUserDirectoryNativeImpl.resolveHostAppCacheDirectory("DelegatedApp")
            assertTrue(cacheDir.name == "Cache" || cacheDir.name == "DelegatedApp")
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `interoperates 1 to 1 with native Windows, Linux XDG, and Apple macOS preference stores`() {
        val tempRoot = Files.createTempDirectory("genericui_native_interop").toFile()
        try {
            // 1. Native Windows / Linux BSON readdir + bijective stem interoperability (no .preference_keys)
            for (osProfile in listOf("Windows 11", "Linux", "FreeBSD")) {
                val dir = File(tempRoot, "bson_${osProfile.replace(" ", "_")}").apply { mkdirs() }
                // Simulate native WindowsPreferenceStoreImpl / LinuxXdgPreferenceStoreImpl files on disk
                File(dir, "theme.bson").writeBytes(BinaryJson.encodeElement(PreferenceElement.ofString("dark")))
                File(dir, "window%3Apos.bson").writeBytes(BinaryJson.encodeElement(PreferenceElement.ofInt(1080)))
                File(dir, "unindexed.bson").writeBytes(BinaryJson.encodeElement(PreferenceElement.ofBoolean(true)))
                File(dir, "legacy_colon.bson").writeBytes(BinaryJson.encodeElement(PreferenceElement.ofString("legacy-ok")))

                val swingStore = SwingPreferenceStoreImpl(
                    appName = "NativeInteropBson",
                    customDirectory = dir,
                    osNameOverride = osProfile
                )
                try {
                    assertEquals("dark", swingStore.readElement("theme")?.asString())
                    assertEquals(1080, swingStore.readElement("window:pos")?.asInt())
                    assertEquals(true, swingStore.readElement("unindexed")?.asBoolean())
                    assertEquals(setOf("legacy_colon", "theme", "unindexed", "window:pos"), swingStore.keys())
                    assertFalse(swingStore.contains("deleted_missing"))

                    // Legacy unencoded file fallback on read/contains/remove without emitting .preference_keys
                    assertTrue(swingStore.contains("legacy:colon"))
                    assertEquals("legacy-ok", swingStore.readElement("legacy:colon")?.asString())
                    swingStore.remove("legacy:colon")
                    assertFalse(File(dir, "legacy_colon.bson").exists())
                    assertFalse(swingStore.contains("legacy:colon"))

                    swingStore.writeElement("fps_cap", PreferenceElement.ofInt(144))
                    val fpsBytes = File(dir, "fps_cap.bson").readBytes()
                    assertEquals(144, BinaryJson.decodeElementOrNull(fpsBytes)?.asInt())
                    assertEquals(setOf("fps_cap", "theme", "unindexed", "window:pos"), swingStore.keys())

                    swingStore.remove("theme")
                    assertEquals(setOf("fps_cap", "unindexed", "window:pos"), swingStore.keys())
                    assertFalse(File(dir, ".preference_keys").exists())
                    assertFalse(File(dir, ".user_dir_manifest").exists())
                } finally {
                    swingStore.dispose()
                }
            }

            // 2. Native Apple macOS per-key bplist00 + legacy monolithic bundle migration
            val macPerKeyDir = File(tempRoot, "mac_per_key").apply { mkdirs() }
            File(macPerKeyDir, "sidebar_width.plist").writeBytes(BinaryPlist.encodeElement(PreferenceElement.ofInt(260)))
            val macPerKeyStore = SwingPreferenceStoreImpl(
                appName = "MacInterop",
                customDirectory = macPerKeyDir,
                osNameOverride = "Mac OS X"
            )
            try {
                assertEquals(260, macPerKeyStore.readElement("sidebar_width")?.asInt())
                macPerKeyStore.writeElement("zoom_level", PreferenceElement.ofDouble(1.25))
                val zoomBytes = File(macPerKeyDir, "zoom_level.plist").readBytes()
                assertTrue(BinaryPlist.isBinaryPlist(zoomBytes))
                assertEquals(1.25, BinaryPlist.decodeElementOrNull(zoomBytes)?.asDouble())
                assertFalse(File(macPerKeyDir, ".preference_keys").exists())
                assertFalse(File(macPerKeyDir, ".user_dir_manifest").exists())
            } finally {
                macPerKeyStore.dispose()
            }

            // Legacy monolithic preferences.plist + settings.plist migration
            val macLegacyDir = File(tempRoot, "mac_legacy").apply { mkdirs() }
            val legacyPrefsPayload = PreferenceElement.ofMap(
                mapOf(
                    "theme" to PreferenceElement.ofString("solarized"),
                    "font_size" to PreferenceElement.ofInt(15),
                    "skip_null" to PreferenceElement.Null
                )
            )
            val legacySettingsPayload = PreferenceElement.ofObject(
                mapOf(
                    "theme" to PreferenceElement.ofString("should_not_overwrite"),
                    "tab:width" to PreferenceElement.ofInt(4)
                )
            )
            File(macLegacyDir, "preferences.plist").writeBytes(BinaryPlist.encodeElement(legacyPrefsPayload))
            File(macLegacyDir, "settings.plist").writeBytes(BinaryPlist.encodeElement(legacySettingsPayload))

            val migratedMacStore = SwingPreferenceStoreImpl(
                appName = "MacLegacyMigration",
                customDirectory = macLegacyDir,
                osNameOverride = "Mac OS X"
            )
            try {
                assertFalse(File(macLegacyDir, "preferences.plist").exists())
                assertFalse(File(macLegacyDir, "settings.plist").exists())
                assertEquals("solarized", migratedMacStore.readElement("theme")?.asString())
                assertEquals(15, migratedMacStore.readElement("font_size")?.asInt())
                assertEquals(4, migratedMacStore.readElement("tab:width")?.asInt())
                assertFalse(migratedMacStore.contains("skip_null"))
                assertTrue(File(macLegacyDir, "theme.plist").isFile)
                assertTrue(File(macLegacyDir, "font_size.plist").isFile)
                assertTrue(File(macLegacyDir, "tab%3Awidth.plist").isFile)
                assertEquals(setOf("font_size", "tab:width", "theme"), migratedMacStore.keys())
                assertFalse(File(macLegacyDir, ".preference_keys").exists())
                assertFalse(File(macLegacyDir, ".user_dir_manifest").exists())
            } finally {
                migratedMacStore.dispose()
            }

            // Cross-format read fallback: .plist on Windows/Linux and .bson on macOS
            val crossBsonDir = File(tempRoot, "cross_to_bson").apply { mkdirs() }
            File(crossBsonDir, "from_mac.plist").writeBytes(BinaryPlist.encodeElement(PreferenceElement.ofString("mac-origin")))
            File(crossBsonDir, "from_mac_backup.plist.old").writeBytes(BinaryPlist.encodeElement(PreferenceElement.ofInt(99)))
            val crossWinStore = SwingPreferenceStoreImpl(
                appName = "CrossWin",
                customDirectory = crossBsonDir,
                osNameOverride = "Windows 11"
            )
            try {
                assertTrue(crossWinStore.keys().contains("from_mac"))
                assertTrue(crossWinStore.keys().contains("from_mac_backup"))
                assertFalse(crossWinStore.keys().any { it.endsWith(".old") })
                assertTrue(crossWinStore.contains("from_mac"))
                assertTrue(crossWinStore.contains("from_mac_backup"))
                assertEquals("mac-origin", crossWinStore.readElement("from_mac")?.asString())
                assertEquals(99, crossWinStore.readElement("from_mac_backup")?.asInt())
                assertFalse(File(crossBsonDir, "from_mac_backup.plist.old").exists())
                assertTrue(File(crossBsonDir, "from_mac_backup.plist").isFile)

                crossWinStore.remove("from_mac")
                assertFalse(File(crossBsonDir, "from_mac.plist").exists())
                assertFalse(crossWinStore.contains("from_mac"))
            } finally {
                crossWinStore.dispose()
            }

            val crossMacDir = File(tempRoot, "cross_to_mac").apply { mkdirs() }
            File(crossMacDir, "from_linux.bson").writeBytes(BinaryJson.encodeElement(PreferenceElement.ofString("linux-origin")))
            val crossMacStore = SwingPreferenceStoreImpl(
                appName = "CrossMac",
                customDirectory = crossMacDir,
                osNameOverride = "Mac OS X"
            )
            try {
                assertTrue(crossMacStore.keys().contains("from_linux"))
                assertTrue(crossMacStore.contains("from_linux"))
                assertEquals("linux-origin", crossMacStore.readElement("from_linux")?.asString())
            } finally {
                crossMacStore.dispose()
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `atomic old backup and corruption recovery work across Windows, Linux, FreeBSD, and macOS`() {
        val tempRoot = Files.createTempDirectory("genericui_old_recovery").toFile()
        try {
            for (osProfile in listOf("Windows 11", "Linux", "FreeBSD", "Mac OS X")) {
                val dir = File(tempRoot, osProfile.replace(" ", "_")).apply { mkdirs() }
                val store = SwingPreferenceStoreImpl(
                    appName = "RecoveryTest_$osProfile",
                    customDirectory = dir,
                    osNameOverride = osProfile
                )
                try {
                    // Clean writes leave zero .old files
                    store.writeElement("theme", PreferenceElement.ofString("dark"))
                    store.writeElement("count", PreferenceElement.ofInt(42))
                    store.writeElement("theme", PreferenceElement.ofString("midnight"))
                    assertEquals("midnight", store.readElement("theme")?.asString())
                    assertFalse(dir.listFiles().orEmpty().any { it.name.endsWith(".old") })

                    val primaryFile = store.fileForKey("theme")
                    val backupFile = store.backupFileForKey("theme")
                    val validBytes = primaryFile.readBytes()

                    // Corrupt primary while .old holds valid previous bytes -> recover + restore primary + delete .old
                    backupFile.writeBytes(validBytes)
                    primaryFile.writeBytes("{CORRUPT}".encodeToByteArray())
                    assertTrue(store.contains("theme"))
                    assertEquals("midnight", store.readElement("theme")?.asString())
                    assertFalse(backupFile.exists(), ".old backup must be deleted after recovery on $osProfile")
                    assertTrue(primaryFile.isFile)
                    if (store.useBinaryPlist) {
                        assertNotNull(BinaryPlist.decodeElementOrNull(primaryFile.readBytes()))
                    } else {
                        assertNotNull(BinaryJson.decodeElementOrNull(primaryFile.readBytes()))
                    }

                    // Corrupt primary when .old is valid and a new write arrives -> preserve valid .old until new write verifies
                    backupFile.writeBytes(validBytes)
                    primaryFile.writeBytes("{CORRUPT}".encodeToByteArray())
                    store.writeElement("theme", PreferenceElement.ofString("daylight"))
                    assertEquals("daylight", store.readElement("theme")?.asString())
                    assertFalse(backupFile.exists())

                    // Corrupt primary with NO .old -> must NOT pretend key exists
                    primaryFile.writeBytes("{CORRUPT}".encodeToByteArray())
                    assertFalse(store.contains("theme"))
                    assertNull(store.readElement("theme"))
                    assertFalse(store.keys().contains("theme"))

                    // Orphaned .old without primary -> appears in keys() without .old, contains() = true, readElement() restores
                    val orphanBytes = if (store.useBinaryPlist) {
                        BinaryPlist.encodeElement(PreferenceElement.ofString("orphan-restored"))
                    } else {
                        BinaryJson.encodeElement(PreferenceElement.ofString("orphan-restored"))
                    }
                    val orphanPrimary = store.fileForKey("orphan")
                    val orphanBackup = store.backupFileForKey("orphan")
                    assertFalse(orphanPrimary.exists())
                    orphanBackup.writeBytes(orphanBytes)

                    assertTrue(store.keys().contains("orphan"))
                    assertFalse(store.keys().any { it.endsWith(".old") })
                    assertTrue(store.contains("orphan"))
                    assertEquals("orphan-restored", store.readElement("orphan")?.asString())
                    assertTrue(orphanPrimary.isFile)
                    assertFalse(orphanBackup.exists())

                    // Removing key deletes both primary and .old; writing Null also removes
                    orphanBackup.writeBytes(orphanBytes)
                    store.remove("orphan")
                    assertFalse(orphanPrimary.exists())
                    assertFalse(orphanBackup.exists())
                    assertFalse(store.contains("orphan"))

                    store.writeElement("count", PreferenceElement.Null)
                    assertFalse(store.fileForKey("count").exists())
                    assertFalse(store.contains("count"))

                    // clear() cleans everything including leftover .old files and legacy index/manifest dotfiles
                    store.backupFileForKey("stray").writeBytes(orphanBytes)
                    File(dir, ".preference_keys").writeText("legacy")
                    File(dir, ".user_dir_manifest").writeText("legacy")
                    store.writeElement("keep_before_clear", PreferenceElement.ofBoolean(true))
                    store.clear()
                    assertTrue(store.keys().isEmpty())
                    assertFalse(dir.listFiles().orEmpty().any {
                        it.name.endsWith(".bson") || it.name.endsWith(".plist") || it.name.endsWith(".old")
                    })
                    assertFalse(File(dir, ".preference_keys").exists())
                    assertFalse(File(dir, ".user_dir_manifest").exists())
                } finally {
                    store.dispose()
                }
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `special character keys round-trip via readdir without preference_keys or user_dir_manifest across Windows, Linux, FreeBSD, and macOS`() {
        val tempRoot = Files.createTempDirectory("genericui_special_keys").toFile()
        val specialKeys = listOf(
            "window:pos",
            "window/pos",
            "window_pos",
            ".hidden",
            "..",
            "100%done",
            "CON",
            "nul.cfg",
            "",
            "~",
            "café🎵"
        )
        try {
            for (osProfile in listOf("Windows 11", "Linux", "FreeBSD", "Mac OS X")) {
                val dir = File(tempRoot, osProfile.replace(" ", "_")).apply { mkdirs() }
                val expectedValues = LinkedHashMap<String, String>()
                val store = SwingPreferenceStoreImpl(
                    appName = "SpecialKeys_$osProfile",
                    customDirectory = dir,
                    osNameOverride = osProfile
                )
                try {
                    specialKeys.forEachIndexed { idx, key ->
                        val value = "val_${idx}_${key}_$osProfile"
                        expectedValues[key] = value
                        store.writeElement(key, PreferenceElement.ofString(value))
                        val file = store.fileForKey(key)
                        assertTrue(file.isFile, "Expected file for '$key' on $osProfile at ${file.name}")
                        assertFalse(file.name.startsWith("."), "File must not be a dotfile for '$key': ${file.name}")
                        assertEquals(UserDirectory.encodePreferenceKeyToFileName(key) + store.fileExtension, file.name)
                    }
                    assertTrue(store.flush())
                    assertFalse(File(dir, ".preference_keys").exists(), ".preference_keys must not exist on $osProfile")
                    assertFalse(File(dir, ".user_dir_manifest").exists(), ".user_dir_manifest must not exist on $osProfile")
                } finally {
                    store.dispose()
                }

                // Reopen a brand new store instance and verify all keys are discovered purely via readdir
                val reopened = SwingPreferenceStoreImpl(
                    appName = "SpecialKeys_$osProfile",
                    customDirectory = dir,
                    osNameOverride = osProfile
                )
                try {
                    assertEquals(specialKeys.toSet(), reopened.keys())
                    for ((key, expected) in expectedValues) {
                        assertTrue(reopened.contains(key), "Expected contains('$key') == true on $osProfile")
                        assertEquals(expected, reopened.readElement(key)?.asString(), "Mismatch for '$key' on $osProfile")
                    }

                    // Removing "window:pos" must not disturb "window/pos" or "window_pos"
                    reopened.remove("window:pos")
                    assertFalse(reopened.fileForKey("window:pos").exists())
                    assertFalse(reopened.contains("window:pos"))
                    assertNull(reopened.readElement("window:pos"))
                    assertFalse("window:pos" in reopened.keys())
                    assertTrue(reopened.contains("window/pos"))
                    assertEquals(expectedValues["window/pos"], reopened.readElement("window/pos")?.asString())
                    assertTrue(reopened.contains("window_pos"))
                    assertEquals(expectedValues["window_pos"], reopened.readElement("window_pos")?.asString())

                    for (key in specialKeys) {
                        if (key == "window:pos") continue
                        reopened.remove(key)
                        assertFalse(reopened.fileForKey(key).exists())
                        assertFalse(reopened.contains(key), "Expected contains('$key') == false after remove on $osProfile")
                        assertNull(reopened.readElement(key))
                    }
                    assertTrue(reopened.keys().isEmpty())
                    assertFalse(File(dir, ".preference_keys").exists())
                    assertFalse(File(dir, ".user_dir_manifest").exists())
                } finally {
                    reopened.dispose()
                }
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `WatchService detects external create, modify, and delete events while ignoring old and dotfiles`() {
        val tempRoot = Files.createTempDirectory("genericui_watch_service").toFile()
        try {
            for (osProfile in listOf("Windows 11", "Mac OS X")) {
                val dir = File(tempRoot, osProfile.replace(" ", "_")).apply { mkdirs() }
                val store = SwingPreferenceStoreImpl(
                    appName = "WatchTest_$osProfile",
                    customDirectory = dir,
                    osNameOverride = osProfile
                )
                try {
                    val events = mutableListOf<Pair<String?, Any?>>()
                    val listener = PreferenceChangeListener { _, key, newValue ->
                        events.add(key to newValue)
                    }
                    store.addChangeListener(listener)

                    // Direct write & remove notifications
                    store.writeElement("direct", PreferenceElement.ofInt(10))
                    store.remove("direct")
                    store.remove("never_existed")
                    assertEquals(listOf<Pair<String?, Any?>>("direct" to 10, "direct" to null), events)
                    events.clear()

                    // Drain any self-write WatchService events before testing external filesystem modifications
                    Thread.sleep(60)
                    store.pollWatchServiceEvents()
                    events.clear()

                    // External write of hidden dotfiles, .old backup, and real preference files (including encoded stems)
                    File(dir, ".hidden_state").writeText("ignored")
                    File(dir, ".preference_keys").writeText("ignored")
                    File(dir, ".user_dir_manifest").writeText("ignored")
                    val ext = store.fileExtension
                    val externalPayload = if (store.useBinaryPlist) {
                        BinaryPlist.encodeElement(PreferenceElement.ofString("external-value"))
                    } else {
                        BinaryJson.encodeElement(PreferenceElement.ofString("external-value"))
                    }
                    val encodedColonPayload = if (store.useBinaryPlist) {
                        BinaryPlist.encodeElement(PreferenceElement.ofString("external-colon"))
                    } else {
                        BinaryJson.encodeElement(PreferenceElement.ofString("external-colon"))
                    }
                    File(dir, "ignored_backup$ext.old").writeBytes(externalPayload)
                    val externalFile = File(dir, "external_key$ext")
                    externalFile.writeBytes(externalPayload)
                    val encodedColonFile = File(dir, "window%3Apos$ext")
                    encodedColonFile.writeBytes(encodedColonPayload)

                    val deadlineMs = System.currentTimeMillis() + 2500L
                    while (System.currentTimeMillis() < deadlineMs &&
                        (events.none { it.first == "external_key" && it.second == "external-value" } ||
                            events.none { it.first == "window:pos" && it.second == "external-colon" })
                    ) {
                        store.flush()
                        Thread.sleep(40)
                    }
                    assertTrue(
                        events.any { it.first == "external_key" && it.second == "external-value" },
                        "Expected WatchService notification for external_key on $osProfile; got $events"
                    )
                    assertTrue(
                        events.any { it.first == "window:pos" && it.second == "external-colon" },
                        "Expected WatchService notification for decoded window:pos on $osProfile; got $events"
                    )
                    assertFalse(
                        events.any { it.first?.startsWith(".") == true || it.first?.contains("ignored_backup") == true },
                        "Hidden dotfiles and .old backups must be ignored by WatchService; got $events"
                    )

                    events.clear()
                    assertTrue(externalFile.delete())
                    assertTrue(encodedColonFile.delete())
                    val deleteDeadlineMs = System.currentTimeMillis() + 2500L
                    while (System.currentTimeMillis() < deleteDeadlineMs &&
                        (events.none { it.first == "external_key" && it.second == null } ||
                            events.none { it.first == "window:pos" && it.second == null })
                    ) {
                        store.pollWatchServiceEvents()
                        Thread.sleep(40)
                    }
                    assertTrue(
                        events.any { it.first == "external_key" && it.second == null },
                        "Expected WatchService delete notification for external_key on $osProfile; got $events"
                    )
                    assertTrue(
                        events.any { it.first == "window:pos" && it.second == null },
                        "Expected WatchService delete notification for window:pos on $osProfile; got $events"
                    )
                } finally {
                    store.dispose()
                }
            }
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
