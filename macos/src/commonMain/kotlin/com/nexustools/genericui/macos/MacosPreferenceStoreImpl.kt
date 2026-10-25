@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.component.system.PreferenceChangeListener
import com.nexustools.genericui.component.system.PreferenceElement
import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.nativeimpl.system.PreferenceStoreNativeImpl
import com.nexustools.genericui.util.BinaryPlist

/**
 * macOS (`:desktop:macos`) implementation of [PreferenceStoreNativeImpl].
 *
 * Stores each preference key in its own binary property list file (`~/.config/<appName>/<key>.plist`)
 * encoded and decoded via [BinaryPlist] (`bplist00`), and dispatches [PreferenceChangeListener] notifications.
 */
class MacosPreferenceStoreImpl(
    override val appName: String = "GenericUI"
) : PreferenceStoreNativeImpl {

    val configDirectory: String = DesktopConfigDirectoryHelper.resolveUnixAppConfigDir(appName)

    override val storageLocationDescription: String = "$configDirectory/*.plist (BinaryPlist bplist00)"
    override val usesNativeChangeListener: Boolean = true

    override var isDisposed: Boolean = false
        private set

    private val memoryCache = LinkedHashMap<String, ByteArray>()
    private val knownKeys = LinkedHashSet<String>()
    private val listeners = mutableListOf<PreferenceChangeListener>()

    init {
        ensureDirectoryExists(configDirectory)
        knownKeys.addAll(DesktopConfigDirectoryHelper.loadPersistedKeySet(configDirectory, '/'))
    }

    fun filePathForKey(key: String): String =
        DesktopConfigDirectoryHelper.resolveKeyFilePath(configDirectory, key, ".plist", '/')

    override fun writeElement(key: String, element: PreferenceElement) {
        if (element.isNull) {
            remove(key)
            return
        }
        ensureDirectoryExists(configDirectory)
        val encoded = BinaryPlist.encodeElement(element)
        memoryCache[key] = encoded
        knownKeys.add(key)
        DesktopConfigDirectoryHelper.writeBytesToFile(filePathForKey(key), encoded)
        DesktopConfigDirectoryHelper.savePersistedKeySet(configDirectory, '/', knownKeys)
        notifyListeners(key, element.toPlainValue())
    }

    override fun readElement(key: String): PreferenceElement? {
        val fileBytes = DesktopConfigDirectoryHelper.readBytesFromFile(filePathForKey(key))
        if (fileBytes != null && fileBytes.isNotEmpty()) {
            memoryCache[key] = fileBytes
            knownKeys.add(key)
            return BinaryPlist.decodeElement(fileBytes)
        }
        val cached = memoryCache[key] ?: return null
        return BinaryPlist.decodeElement(cached)
    }

    override fun contains(key: String): Boolean =
        key in knownKeys || memoryCache.containsKey(key) ||
            DesktopConfigDirectoryHelper.fileExists(filePathForKey(key))

    override fun keys(): Set<String> = LinkedHashSet(knownKeys)

    override fun remove(key: String) {
        val existed = contains(key)
        memoryCache.remove(key)
        knownKeys.remove(key)
        DesktopConfigDirectoryHelper.deleteFile(filePathForKey(key))
        DesktopConfigDirectoryHelper.savePersistedKeySet(configDirectory, '/', knownKeys)
        if (existed) {
            notifyListeners(key, null)
        }
    }

    override fun clear() {
        val snapshotKeys = knownKeys.toList()
        for (k in snapshotKeys) {
            DesktopConfigDirectoryHelper.deleteFile(filePathForKey(k))
        }
        memoryCache.clear()
        knownKeys.clear()
        DesktopConfigDirectoryHelper.savePersistedKeySet(configDirectory, '/', knownKeys)
        if (snapshotKeys.isNotEmpty()) {
            notifyListeners(null, null)
        }
    }

    override fun flush(): Boolean {
        if (isDisposed) return false
        ensureDirectoryExists(configDirectory)
        for ((k, bytes) in memoryCache) {
            DesktopConfigDirectoryHelper.writeBytesToFile(filePathForKey(k), bytes)
        }
        DesktopConfigDirectoryHelper.savePersistedKeySet(configDirectory, '/', knownKeys)
        return true
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
    }

    private fun ensureDirectoryExists(path: String) {
        DesktopConfigDirectoryHelper.ensureDirectoryExists(path)
    }

    private fun notifyListeners(key: String?, newValue: Any?) {
        val snapshot = listeners.toList()
        for (listener in snapshot) {
            listener.onPreferenceChanged(appName, key, newValue)
        }
    }
}
