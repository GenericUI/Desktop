@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.system.PreferenceChangeListener
import com.nexustools.genericui.component.system.PreferenceElement
import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.nativeimpl.system.PreferenceStoreNativeImpl
import com.nexustools.genericui.util.BinaryJson
import com.nexustools.genericui.util.ConfigDirectory

/**
 * Windows (`:desktop:windows`) implementation of [PreferenceStoreNativeImpl] backed by [ConfigDirectory]
 * (`WindowsConfigDirectoryImpl`).
 *
 * Stores each preference key in its own binary JSON file (`%APPDATA%\<appName>\<key>.bson`)
 * encoded and decoded via [BinaryJson], and dispatches [PreferenceChangeListener] notifications.
 */
class WindowsPreferenceStoreImpl(
    override val appName: String = "GenericUI"
) : PreferenceStoreNativeImpl {

    val configDir: ConfigDirectory = ConfigDirectory(
        appName = appName,
        factory = WindowsNativeFactory()
    )

    val configDirectory: String
        get() = configDir.directoryPath

    override val storageLocationDescription: String
        get() = "$configDirectory\\*.bson (BinaryJson)"

    override val usesNativeChangeListener: Boolean = true

    override var isDisposed: Boolean = false
        private set

    private val memoryCache = LinkedHashMap<String, ByteArray>()
    private val dirtyKeys = LinkedHashSet<String>()
    private val listeners = mutableListOf<PreferenceChangeListener>()

    init {
        configDir.ensureExists()
    }

    fun filePathForKey(key: String): String =
        configDir.resolveKeyPath(key, ".bson")

    fun backupFilePathForKey(key: String): String =
        configDir.resolveKeyPath(key, ".bson.old")

    private fun isValidBsonBytes(bytes: ByteArray): Boolean =
        BinaryJson.decodeElementOrNull(bytes) != null

    private fun scanDiskKeys(): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        val base = configDirectory.trimEnd('\\', '/')
        for (fileName in DesktopConfigDirectoryHelper.listDirectoryFiles(configDirectory)) {
            if (fileName.startsWith(".")) continue
            when {
                fileName.endsWith(".bson.old") && fileName.length > ".bson.old".length -> {
                    val bytes = DesktopConfigDirectoryHelper.readBytesFromFile("$base\\$fileName")
                    if (bytes != null && isValidBsonBytes(bytes)) {
                        out.add(DesktopConfigDirectoryHelper.decodeKeyFileName(fileName.removeSuffix(".bson.old")))
                    }
                }
                fileName.endsWith(".bson") && fileName.length > ".bson".length -> {
                    val bytes = DesktopConfigDirectoryHelper.readBytesFromFile("$base\\$fileName")
                    if (bytes != null && isValidBsonBytes(bytes)) {
                        out.add(DesktopConfigDirectoryHelper.decodeKeyFileName(fileName.removeSuffix(".bson")))
                    }
                }
            }
        }
        return out
    }

    override fun writeElement(key: String, element: PreferenceElement) {
        if (isDisposed) return
        if (element.isNull) {
            remove(key)
            return
        }
        configDir.ensureExists()
        val encoded = BinaryJson.encodeElement(element)
        val written = DesktopConfigDirectoryHelper.writeBytesWithOldBackup(
            filePath = filePathForKey(key),
            bytes = encoded,
            validator = ::isValidBsonBytes
        )
        memoryCache[key] = encoded
        if (written) {
            dirtyKeys.remove(key)
        } else {
            dirtyKeys.add(key)
        }
        notifyListeners(key, element.toPlainValue())
    }

    override fun readElement(key: String): PreferenceElement? {
        if (isDisposed) return null
        val primaryPath = filePathForKey(key)
        val backupPath = backupFilePathForKey(key)
        val primaryExists = DesktopConfigDirectoryHelper.fileExists(primaryPath)
        val backupExists = DesktopConfigDirectoryHelper.fileExists(backupPath)

        if (primaryExists || backupExists) {
            val recoveredBytes = DesktopConfigDirectoryHelper.readBytesWithOldRecovery(
                filePath = primaryPath,
                validator = ::isValidBsonBytes
            )
            if (recoveredBytes == null) {
                memoryCache.remove(key)
                return null
            }
            val decoded = BinaryJson.decodeElementOrNull(recoveredBytes) ?: return null
            memoryCache[key] = recoveredBytes
            return decoded
        }

        if (key in dirtyKeys) {
            val cached = memoryCache[key] ?: return null
            return BinaryJson.decodeElementOrNull(cached)
        }
        memoryCache.remove(key)
        return null
    }

    override fun contains(key: String): Boolean {
        if (isDisposed) return false
        val primaryBytes = DesktopConfigDirectoryHelper.readBytesFromFile(filePathForKey(key))
        if (primaryBytes != null && isValidBsonBytes(primaryBytes)) {
            return true
        }
        val backupBytes = DesktopConfigDirectoryHelper.readBytesFromFile(backupFilePathForKey(key))
        if (backupBytes != null && isValidBsonBytes(backupBytes)) {
            return true
        }
        return key in dirtyKeys && memoryCache.containsKey(key)
    }

    override fun keys(): Set<String> {
        if (isDisposed) return emptySet()
        val out = LinkedHashSet<String>()
        out.addAll(scanDiskKeys())
        out.addAll(dirtyKeys)
        return out.sorted().toCollection(LinkedHashSet())
    }

    override fun remove(key: String) {
        if (isDisposed) return
        val primaryPath = filePathForKey(key)
        val backupPath = backupFilePathForKey(key)
        val existed = contains(key) ||
            DesktopConfigDirectoryHelper.fileExists(primaryPath) ||
            DesktopConfigDirectoryHelper.fileExists(backupPath)
        memoryCache.remove(key)
        dirtyKeys.remove(key)
        DesktopConfigDirectoryHelper.deleteFile(primaryPath)
        DesktopConfigDirectoryHelper.deleteFile(backupPath)
        if (existed) {
            notifyListeners(key, null)
        }
    }

    override fun clear() {
        if (isDisposed) return
        val snapshotKeys = keys().toList()
        val diskFiles = DesktopConfigDirectoryHelper.listDirectoryFiles(configDirectory)
        val base = configDirectory.trimEnd('\\', '/')
        var deletedAny = false
        for (fileName in diskFiles) {
            if (fileName.startsWith(".")) continue
            if (fileName.endsWith(".bson") || fileName.endsWith(".bson.old")) {
                if (DesktopConfigDirectoryHelper.deleteFile("$base\\$fileName")) {
                    deletedAny = true
                }
            }
        }
        for (k in snapshotKeys) {
            if (DesktopConfigDirectoryHelper.deleteFile(filePathForKey(k))) deletedAny = true
            if (DesktopConfigDirectoryHelper.deleteFile(backupFilePathForKey(k))) deletedAny = true
        }
        DesktopConfigDirectoryHelper.deleteFile("$base\\.preference_keys")
        DesktopConfigDirectoryHelper.deleteFile("$base\\.user_dir_manifest")
        val hadAny = snapshotKeys.isNotEmpty() || deletedAny
        memoryCache.clear()
        dirtyKeys.clear()
        if (hadAny) {
            notifyListeners(null, null)
        }
    }

    override fun flush(): Boolean {
        if (isDisposed) return false
        configDir.ensureExists()
        for (k in dirtyKeys.toList()) {
            val bytes = memoryCache[k] ?: continue
            val flushed = DesktopConfigDirectoryHelper.writeBytesWithOldBackup(
                filePath = filePathForKey(k),
                bytes = bytes,
                validator = ::isValidBsonBytes
            )
            if (flushed) {
                dirtyKeys.remove(k)
            }
        }
        return dirtyKeys.isEmpty()
    }

    override fun addChangeListener(listener: PreferenceChangeListener) {
        if (listener !in listeners) {
            listeners.add(listener)
        }
    }

    override fun removeChangeListener(listener: PreferenceChangeListener) {
        listeners.remove(listener)
    }

    override fun dispose() {
        isDisposed = true
        listeners.clear()
        configDir.dispose()
    }

    private fun notifyListeners(key: String?, newValue: Any?) {
        val snapshot = listeners.toList()
        for (listener in snapshot) {
            listener.onPreferenceChanged(appName, key, newValue)
        }
    }
}
