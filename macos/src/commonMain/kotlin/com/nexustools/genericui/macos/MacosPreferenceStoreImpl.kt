@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.component.system.PreferenceChangeListener
import com.nexustools.genericui.component.system.PreferenceElement
import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.nativeimpl.system.PreferenceStoreNativeImpl
import com.nexustools.genericui.util.BinaryPlist
import com.nexustools.genericui.util.ConfigDirectory

/**
 * macOS (`:desktop:macos`) implementation of [PreferenceStoreNativeImpl] backed by [ConfigDirectory]
 * (`MacosConfigDirectoryImpl`).
 *
 * Stores each preference key in its own binary property list file (`~/.config/<appName>/<key>.plist`)
 * encoded and decoded via [BinaryPlist] (`bplist00`), and dispatches [PreferenceChangeListener] notifications.
 */
class MacosPreferenceStoreImpl(
    override val appName: String = "GenericUI"
) : PreferenceStoreNativeImpl {

    val configDir: ConfigDirectory = ConfigDirectory(
        appName = appName,
        nativeImplOverride = MacosConfigDirectoryImpl(appName)
    )

    val configDirectory: String
        get() = configDir.directoryPath

    override val storageLocationDescription: String
        get() = "$configDirectory/*.plist (BinaryPlist bplist00)"

    override val usesNativeChangeListener: Boolean = true

    override var isDisposed: Boolean = false
        private set

    private val memoryCache = LinkedHashMap<String, ByteArray>()
    private val knownKeys = LinkedHashSet<String>()
    private val listeners = mutableListOf<PreferenceChangeListener>()

    init {
        configDir.ensureExists()
        knownKeys.addAll(DesktopConfigDirectoryHelper.loadPersistedKeySet(configDirectory, '/'))
        knownKeys.addAll(configDir.keysForExtension(".plist"))
    }

    fun filePathForKey(key: String): String =
        configDir.resolveKeyPath(key, ".plist")

    override fun writeElement(key: String, element: PreferenceElement) {
        if (element.isNull) {
            remove(key)
            return
        }
        configDir.ensureExists()
        val encoded = BinaryPlist.encodeElement(element)
        memoryCache[key] = encoded
        knownKeys.add(key)
        configDir.writeKeyBytes(key, ".plist", encoded)
        DesktopConfigDirectoryHelper.savePersistedKeySet(configDirectory, '/', knownKeys)
        notifyListeners(key, element.toPlainValue())
    }

    override fun readElement(key: String): PreferenceElement? {
        val fileBytes = configDir.readKeyBytes(key, ".plist")
        if (fileBytes != null && fileBytes.isNotEmpty()) {
            memoryCache[key] = fileBytes
            knownKeys.add(key)
            return BinaryPlist.decodeElement(fileBytes)
        }
        val cached = memoryCache[key] ?: return null
        return BinaryPlist.decodeElement(cached)
    }

    override fun contains(key: String): Boolean =
        key in knownKeys || memoryCache.containsKey(key) || configDir.containsKey(key, ".plist")

    override fun keys(): Set<String> = LinkedHashSet(knownKeys)

    override fun remove(key: String) {
        val existed = contains(key)
        memoryCache.remove(key)
        knownKeys.remove(key)
        configDir.removeKey(key, ".plist")
        DesktopConfigDirectoryHelper.savePersistedKeySet(configDirectory, '/', knownKeys)
        if (existed) {
            notifyListeners(key, null)
        }
    }

    override fun clear() {
        val snapshotKeys = knownKeys.toList()
        for (k in snapshotKeys) {
            configDir.removeKey(k, ".plist")
        }
        configDir.clearKeysWithExtension(".plist")
        memoryCache.clear()
        knownKeys.clear()
        DesktopConfigDirectoryHelper.savePersistedKeySet(configDirectory, '/', knownKeys)
        if (snapshotKeys.isNotEmpty()) {
            notifyListeners(null, null)
        }
    }

    override fun flush(): Boolean {
        if (isDisposed) return false
        configDir.ensureExists()
        for ((k, bytes) in memoryCache) {
            configDir.writeKeyBytes(k, ".plist", bytes)
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
        configDir.dispose()
    }

    private fun notifyListeners(key: String?, newValue: Any?) {
        val snapshot = listeners.toList()
        for (listener in snapshot) {
            listener.onPreferenceChanged(appName, key, newValue)
        }
    }
}
