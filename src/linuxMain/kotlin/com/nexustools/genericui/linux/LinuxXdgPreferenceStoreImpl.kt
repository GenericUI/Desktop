@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.component.system.PreferenceChangeListener
import com.nexustools.genericui.component.system.PreferenceElement
import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.nativeimpl.system.PreferenceStoreNativeImpl
import com.nexustools.genericui.nativeimpl.system.UserDirectoryNativeImpl
import com.nexustools.genericui.util.BinaryJson
import com.nexustools.genericui.util.ConfigDirectory

open class LinuxXdgPreferenceStoreImpl(
    override val appName: String = "GenericUI",
    private val configDirectoryImpl: UserDirectoryNativeImpl = LinuxXdgConfigDirectoryImpl(appName)
) : PreferenceStoreNativeImpl {
    private val lock = LinuxReentrantLock()
    private val useDirectPosixFiles: Boolean = configDirectoryImpl.usesFileSystemBasePath

    val configDir: ConfigDirectory = object : ConfigDirectory(appName = appName) {
        override fun resolveCustomImpl(): UserDirectoryNativeImpl = configDirectoryImpl
    }

    val configDirectory: String
        get() = configDir.directoryPath

    override val storageLocationDescription: String
        get() = "$configDirectory/*.bson (BinaryJson)"

    override val usesNativeChangeListener: Boolean = true

    override var isDisposed: Boolean = false
        protected set

    private val memoryCache = LinkedHashMap<String, ByteArray>()
    private val dirtyKeys = LinkedHashSet<String>()
    private val listeners = ArrayList<PreferenceChangeListener>()

    init {
        configDir.ensureExists()
    }

    fun filePathForKey(key: String): String =
        configDir.resolveKeyPath(key, ".bson")

    fun backupFilePathForKey(key: String): String =
        configDir.resolveKeyPath(key, ".bson.old")

    private fun isValidBsonBytes(bytes: ByteArray): Boolean =
        BinaryJson.decodeElementOrNull(bytes) != null

    private fun readKeyFileBytes(fileName: String): ByteArray? =
        if (useDirectPosixFiles) {
            DesktopConfigDirectoryHelper.readBytesFromFile("${configDirectory.trimEnd('\\', '/')}/$fileName")
        } else {
            configDirectoryImpl.readBytes(fileName)
        }

    private fun keyFileExists(fileName: String): Boolean =
        if (useDirectPosixFiles) {
            DesktopConfigDirectoryHelper.fileExists("${configDirectory.trimEnd('\\', '/')}/$fileName")
        } else {
            configDirectoryImpl.exists(fileName)
        }

    private fun deleteKeyFile(fileName: String): Boolean =
        if (useDirectPosixFiles) {
            DesktopConfigDirectoryHelper.deleteFile("${configDirectory.trimEnd('\\', '/')}/$fileName")
        } else {
            configDirectoryImpl.delete(fileName)
        }

    private fun listKeyDirectoryFiles(): List<String> =
        if (useDirectPosixFiles) {
            DesktopConfigDirectoryHelper.listDirectoryFiles(configDirectory)
        } else {
            configDirectoryImpl.listFiles()
        }

    private fun writeKeyBytesWithBackup(key: String, bytes: ByteArray): Boolean {
        if (useDirectPosixFiles) {
            return DesktopConfigDirectoryHelper.writeBytesWithOldBackup(
                filePath = filePathForKey(key),
                bytes = bytes,
                validator = ::isValidBsonBytes
            )
        }
        val primaryName = configDir.fileNameForKey(key, ".bson")
        val backupName = configDir.fileNameForKey(key, ".bson.old")
        if (configDirectoryImpl.exists(primaryName)) {
            val primaryValid = configDirectoryImpl.readBytes(primaryName)?.let(::isValidBsonBytes) == true
            val backupValid = configDirectoryImpl.exists(backupName) &&
                (configDirectoryImpl.readBytes(backupName)?.let(::isValidBsonBytes) == true)
            if (!primaryValid && backupValid) {
                configDirectoryImpl.delete(primaryName)
            } else {
                configDirectoryImpl.readBytes(primaryName)?.let { oldBytes ->
                    configDirectoryImpl.delete(backupName)
                    configDirectoryImpl.writeBytes(backupName, oldBytes)
                }
                configDirectoryImpl.delete(primaryName)
            }
        }
        if (!configDirectoryImpl.writeBytes(primaryName, bytes)) return false
        val written = configDirectoryImpl.readBytes(primaryName)
        val verified = written != null && written.size == bytes.size && isValidBsonBytes(written)
        if (!verified) return false
        if (configDirectoryImpl.exists(backupName)) {
            configDirectoryImpl.delete(backupName)
        }
        return true
    }

    private fun readKeyBytesWithRecovery(key: String): ByteArray? {
        if (useDirectPosixFiles) {
            return DesktopConfigDirectoryHelper.readBytesWithOldRecovery(
                filePath = filePathForKey(key),
                validator = ::isValidBsonBytes
            )
        }
        val primaryName = configDir.fileNameForKey(key, ".bson")
        val backupName = configDir.fileNameForKey(key, ".bson.old")
        val primaryBytes = configDirectoryImpl.readBytes(primaryName)
        if (primaryBytes != null && primaryBytes.isNotEmpty() && isValidBsonBytes(primaryBytes)) {
            if (configDirectoryImpl.exists(backupName)) {
                configDirectoryImpl.delete(backupName)
            }
            return primaryBytes
        }
        val backupBytes = configDirectoryImpl.readBytes(backupName)
        if (backupBytes != null && backupBytes.isNotEmpty() && isValidBsonBytes(backupBytes)) {
            if (configDirectoryImpl.writeBytes(primaryName, backupBytes)) {
                val restored = configDirectoryImpl.readBytes(primaryName)
                if (restored != null && restored.size == backupBytes.size && isValidBsonBytes(restored)) {
                    configDirectoryImpl.delete(backupName)
                }
            }
            return backupBytes
        }
        return null
    }

    private fun scanDiskKeysUnlocked(): LinkedHashSet<String> {
        val out = LinkedHashSet<String>()
        for (fileName in listKeyDirectoryFiles()) {
            if (fileName.startsWith(".")) continue
            when {
                fileName.endsWith(".bson.old") && fileName.length > ".bson.old".length -> {
                    val bytes = readKeyFileBytes(fileName)
                    if (bytes != null && isValidBsonBytes(bytes)) {
                        out.add(DesktopConfigDirectoryHelper.decodeKeyFileName(fileName.removeSuffix(".bson.old")))
                    }
                }
                fileName.endsWith(".bson") && fileName.length > ".bson".length -> {
                    val bytes = readKeyFileBytes(fileName)
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
        lock.withLock {
            if (isDisposed) return
            val written = writeKeyBytesWithBackup(key, encoded)
            memoryCache[key] = encoded
            if (written) {
                dirtyKeys.remove(key)
            } else {
                dirtyKeys.add(key)
            }
        }
        notifyListeners(key, element.toPlainValue())
    }

    override fun readElement(key: String): PreferenceElement? {
        return lock.withLock {
            if (isDisposed) return@withLock null
            val primaryName = configDir.fileNameForKey(key, ".bson")
            val backupName = configDir.fileNameForKey(key, ".bson.old")
            val primaryExists = keyFileExists(primaryName)
            val backupExists = keyFileExists(backupName)

            if (primaryExists || backupExists) {
                val recoveredBytes = readKeyBytesWithRecovery(key)
                if (recoveredBytes == null) {
                    memoryCache.remove(key)
                    return@withLock null
                }
                val decoded = BinaryJson.decodeElementOrNull(recoveredBytes) ?: return@withLock null
                memoryCache[key] = recoveredBytes
                return@withLock decoded
            }

            if (key in dirtyKeys) {
                val cached = memoryCache[key] ?: return@withLock null
                return@withLock BinaryJson.decodeElementOrNull(cached)
            }
            memoryCache.remove(key)
            null
        }
    }

    override fun contains(key: String): Boolean = lock.withLock {
        if (isDisposed) return@withLock false
        val primaryBytes = readKeyFileBytes(configDir.fileNameForKey(key, ".bson"))
        if (primaryBytes != null && isValidBsonBytes(primaryBytes)) {
            return@withLock true
        }
        val backupBytes = readKeyFileBytes(configDir.fileNameForKey(key, ".bson.old"))
        if (backupBytes != null && isValidBsonBytes(backupBytes)) {
            return@withLock true
        }
        key in dirtyKeys && memoryCache.containsKey(key)
    }

    override fun keys(): Set<String> = lock.withLock {
        if (isDisposed) return@withLock emptySet()
        val out = LinkedHashSet<String>()
        out.addAll(scanDiskKeysUnlocked())
        out.addAll(dirtyKeys)
        out.sorted().toCollection(LinkedHashSet())
    }

    override fun remove(key: String) {
        val existed = lock.withLock {
            if (isDisposed) return
            val primaryName = configDir.fileNameForKey(key, ".bson")
            val backupName = configDir.fileNameForKey(key, ".bson.old")
            val primaryBytes = readKeyFileBytes(primaryName)
            val backupBytes = readKeyFileBytes(backupName)
            val hadValidOrDirty = (primaryBytes != null && isValidBsonBytes(primaryBytes)) ||
                (backupBytes != null && isValidBsonBytes(backupBytes)) ||
                (key in dirtyKeys && memoryCache.containsKey(key)) ||
                keyFileExists(primaryName) ||
                keyFileExists(backupName)
            memoryCache.remove(key)
            dirtyKeys.remove(key)
            deleteKeyFile(primaryName)
            deleteKeyFile(backupName)
            hadValidOrDirty
        }
        if (existed) {
            notifyListeners(key, null)
        }
    }

    override fun clear() {
        val hadAny = lock.withLock {
            if (isDisposed) return
            val snapshotKeys = LinkedHashSet<String>().apply {
                addAll(scanDiskKeysUnlocked())
                addAll(dirtyKeys)
            }
            val diskFiles = listKeyDirectoryFiles()
            var deletedAny = false
            for (fileName in diskFiles) {
                if (fileName.startsWith(".")) continue
                if (fileName.endsWith(".bson") || fileName.endsWith(".bson.old")) {
                    if (deleteKeyFile(fileName)) {
                        deletedAny = true
                    }
                }
            }
            for (k in snapshotKeys) {
                if (deleteKeyFile(configDir.fileNameForKey(k, ".bson"))) deletedAny = true
                if (deleteKeyFile(configDir.fileNameForKey(k, ".bson.old"))) deletedAny = true
            }
            deleteKeyFile(".preference_keys")
            deleteKeyFile(".user_dir_manifest")
            val removed = snapshotKeys.isNotEmpty() || deletedAny
            memoryCache.clear()
            dirtyKeys.clear()
            removed
        }
        if (hadAny) {
            notifyListeners(null, null)
        }
    }

    override fun flush(): Boolean {
        if (isDisposed) return false
        configDir.ensureExists()
        return lock.withLock {
            if (isDisposed) return false
            for (k in dirtyKeys.toList()) {
                val bytes = memoryCache[k] ?: continue
                if (writeKeyBytesWithBackup(k, bytes)) {
                    dirtyKeys.remove(k)
                }
            }
            dirtyKeys.isEmpty()
        }
    }

    override fun addChangeListener(listener: PreferenceChangeListener) {
        lock.withLock {
            if (listener !in listeners) {
                listeners.add(listener)
            }
        }
    }

    override fun removeChangeListener(listener: PreferenceChangeListener) {
        lock.withLock {
            listeners.remove(listener)
        }
    }

    override fun dispose() {
        lock.withLock {
            isDisposed = true
            listeners.clear()
        }
        configDir.dispose()
    }

    private fun notifyListeners(key: String?, newValue: Any?) {
        val snapshot = lock.withLock { listeners.toList() }
        for (listener in snapshot) {
            listener.onPreferenceChanged(appName, key, newValue)
        }
    }
}
