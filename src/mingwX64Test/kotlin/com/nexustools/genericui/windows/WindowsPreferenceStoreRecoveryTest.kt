@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.system.PreferenceElement
import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.util.BinaryJson
import platform.posix.getpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindowsPreferenceStoreRecoveryTest {

    @Test
    fun windowsPreferenceStorePersistsPerKeyBsonAndRecoversFromOldBackup() {
        val appName = "GenericUiWinPrefTest_${getpid()}"
        val store1 = WindowsPreferenceStoreImpl(appName)
        try {
            store1.clear()
            store1.writeElement("theme", PreferenceElement.ofString("dark"))
            store1.writeElement("count", PreferenceElement.ofInt(42))
            store1.writeElement("enabled", PreferenceElement.ofBoolean(true))
            assertTrue(store1.flush())

            val themePath = store1.filePathForKey("theme")
            val themeOldPath = store1.backupFilePathForKey("theme")
            assertTrue(DesktopConfigDirectoryHelper.fileExists(themePath))
            assertFalse(DesktopConfigDirectoryHelper.fileExists(themeOldPath))

            val validThemeBytes = DesktopConfigDirectoryHelper.readBytesFromFile(themePath)
            assertNotNull(validThemeBytes)
            assertTrue(BinaryJson.isBinaryJson(validThemeBytes))

            // Corrupt primary .bson while valid .bson.old exists -> must recover even in same store instance
            assertTrue(DesktopConfigDirectoryHelper.writeBytesToFile(themeOldPath, validThemeBytes))
            assertTrue(DesktopConfigDirectoryHelper.writeBytesToFile(themePath, "{CORRUPT}".encodeToByteArray()))
            assertTrue(store1.contains("theme"))
            assertEquals("dark", store1.readElement("theme")?.asString())
            assertFalse(DesktopConfigDirectoryHelper.fileExists(themeOldPath))

            val recoveredDiskBytes = DesktopConfigDirectoryHelper.readBytesFromFile(themePath)
            assertNotNull(recoveredDiskBytes)
            assertTrue(BinaryJson.isBinaryJson(recoveredDiskBytes))

            // Corrupt primary with NO .bson.old must NOT be masked by stale memoryCache
            assertTrue(DesktopConfigDirectoryHelper.writeBytesToFile(themePath, "{CORRUPT}".encodeToByteArray()))
            assertFalse(store1.contains("theme"))
            assertNull(store1.readElement("theme"))
            assertFalse(store1.keys().contains("theme"))

            // Orphaned .bson.old without primary must appear in keys(), contains(), and recover
            val orphanBytes = BinaryJson.encodeElement(PreferenceElement.ofString("orphan-win"))
            assertTrue(DesktopConfigDirectoryHelper.writeBytesToFile(store1.backupFilePathForKey("orphan"), orphanBytes))
            assertTrue(store1.keys().contains("orphan"))
            assertFalse(store1.keys().any { it.endsWith(".old") })
            assertTrue(store1.contains("orphan"))
            assertEquals("orphan-win", store1.readElement("orphan")?.asString())
            assertTrue(DesktopConfigDirectoryHelper.fileExists(store1.filePathForKey("orphan")))
            assertFalse(DesktopConfigDirectoryHelper.fileExists(store1.backupFilePathForKey("orphan")))

            // Special-character keys must round-trip losslessly via readdir without .preference_keys / .user_dir_manifest
            val specialKeys = listOf(
                "window:pos",
                "path/to/key",
                "100%raw",
                ".hidden",
                "CON",
                "~",
                ""
            )
            val specialExpected = LinkedHashMap<String, String>()
            specialKeys.forEachIndexed { idx, key ->
                val value = "win_val_${idx}_$key"
                specialExpected[key] = value
                store1.writeElement(key, PreferenceElement.ofString(value))
                assertTrue(DesktopConfigDirectoryHelper.fileExists(store1.filePathForKey(key)))
            }
            assertTrue(store1.flush())

            val dirEntries1 = DesktopConfigDirectoryHelper.listDirectoryFiles(store1.configDirectory)
            assertFalse(".preference_keys" in dirEntries1)
            assertFalse(".user_dir_manifest" in dirEntries1)
            assertFalse(DesktopConfigDirectoryHelper.fileExists("${store1.configDirectory}\\.preference_keys"))
            assertFalse(DesktopConfigDirectoryHelper.fileExists("${store1.configDirectory}\\.user_dir_manifest"))

            // .bson.old recovery on special-character keys (including dot-prefixed, colon, and empty keys)
            for (recoverKey in listOf("window:pos", ".hidden", "")) {
                val pPath = store1.filePathForKey(recoverKey)
                val bPath = store1.backupFilePathForKey(recoverKey)
                val validBytes = DesktopConfigDirectoryHelper.readBytesFromFile(pPath)
                assertNotNull(validBytes)
                assertTrue(DesktopConfigDirectoryHelper.writeBytesToFile(bPath, validBytes))
                assertTrue(DesktopConfigDirectoryHelper.writeBytesToFile(pPath, "{CORRUPT}".encodeToByteArray()))
                assertTrue(store1.keys().contains(recoverKey))
                assertFalse(store1.keys().any { it.endsWith(".old") })
                assertTrue(store1.contains(recoverKey))
                assertEquals(specialExpected[recoverKey], store1.readElement(recoverKey)?.asString())
                assertFalse(DesktopConfigDirectoryHelper.fileExists(bPath))
                assertTrue(DesktopConfigDirectoryHelper.fileExists(pPath))
            }

            // Reopen fresh instance against disk
            val store2 = WindowsPreferenceStoreImpl(appName)
            try {
                assertEquals(42, store2.readElement("count")?.asInt())
                assertEquals(true, store2.readElement("enabled")?.asBoolean())
                assertEquals("orphan-win", store2.readElement("orphan")?.asString())
                val expectedAllKeys = setOf("count", "enabled", "orphan") + specialKeys
                assertEquals(expectedAllKeys, store2.keys())
                for ((k, expectedVal) in specialExpected) {
                    assertTrue(store2.contains(k), "Expected contains('$k') == true in reopened Windows store")
                    assertEquals(expectedVal, store2.readElement(k)?.asString())
                }
                val dirEntries2 = DesktopConfigDirectoryHelper.listDirectoryFiles(store2.configDirectory)
                assertFalse(".preference_keys" in dirEntries2)
                assertFalse(".user_dir_manifest" in dirEntries2)
            } finally {
                store2.dispose()
            }

            // Verify clear() removes all keys and any leftover legacy metadata files without recreating them
            DesktopConfigDirectoryHelper.writeBytesToFile("${store1.configDirectory}\\.preference_keys", "legacy".encodeToByteArray())
            DesktopConfigDirectoryHelper.writeBytesToFile("${store1.configDirectory}\\.user_dir_manifest", "legacy".encodeToByteArray())
            store1.clear()
            assertTrue(store1.keys().isEmpty())
            assertFalse(DesktopConfigDirectoryHelper.fileExists("${store1.configDirectory}\\.preference_keys"))
            assertFalse(DesktopConfigDirectoryHelper.fileExists("${store1.configDirectory}\\.user_dir_manifest"))
        } finally {
            store1.clear()
            store1.dispose()
        }
    }
}
