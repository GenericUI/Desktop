@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk

import com.nexustools.genericui.component.system.PreferenceChangeListener
import com.nexustools.genericui.component.system.PreferenceElement
import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.nativeimpl.system.PreferenceStoreNativeImpl
import com.nexustools.genericui.util.BinaryJson
import com.nexustools.genericui.util.ConfigDirectory

/**
 * Linux GTK4 (`:desktop:gtk`) implementation of [PreferenceStoreNativeImpl] backed by [ConfigDirectory]
 * (`GtkConfigDirectoryImpl`).
 *
 * Stores each preference key in its own binary JSON file (`~/.config/<appName>/<key>.bson`)
 * encoded and decoded via [BinaryJson], and dispatches [PreferenceChangeListener] notifications.
 */
class GtkPreferenceStoreImpl(
    override val appName: String = "GenericUI"
) : PreferenceStoreNativeImpl {

    val configDir: ConfigDirectory = ConfigDirectory(
        appName = appName,
        nativeImplOverride = GtkConfigDirectoryImpl(appName)
    )

    val configDirectory: String
        get() = configDir.directoryPath

    override val storageLocationDescription: String
        get() = "$configDirectory/*.bson (BinaryJson)"

    override val usesNativeChangeListener: Boolean = true

    override var isDisposed: Boolean = false
        private set

    private val memoryCache = LinkedHashMap<String, ByteArray>()
    private val knownKeys = LinkedHashSet<String>()
    private val listeners = mutableListOf<PreferenceChangeListener>()

    init {
        configDir.ensureExists()
        knownKeys.addAll(DesktopConfigDirectoryHelper.loadPersistedKeySet(configDirectory, '/'))
        knownKeys.addAll(configDir.keysForExtension(".bson"))
    }

    fun filePathForKey(key: String): String =
        configDir.resolveKeyPath(key, ".bson")

    override fun writeElement(key: String, element: PreferenceElement) {
        if (element.isNull) {
            remove(key)
            return
        }
        configDir.ensureExists()
        val encoded = BinaryJson.encodeElement(element)
        memoryCache[key] = encoded
        knownKeys.add(key)
        configDir.writeKeyBytes(key, ".bson", encoded)
        DesktopConfigDirectoryHelper.savePersistedKeySet(configDirectory, '/', knownKeys)
        notifyListeners(key, element.toPlainValue())
    }

    override fun readElement(key: String): PreferenceElement? {
        val fileBytes = configDir.readKeyBytes(key, ".bson")
        if (fileBytes != null && fileBytes.isNotEmpty()) {
            memoryCache[key] = fileBytes
            knownKeys.add(key)
            return BinaryJson.decodeElement(fileBytes)
        }
        val cached = memoryCache[key] ?: return null
        return BinaryJson.decodeElement(cached)
    }

    override fun contains(key: String): Boolean =
        key in knownKeys || memoryCache.containsKey(key) || configDir.containsKey(key, ".bson")

    override fun keys(): Set<String> = LinkedHashSet(knownKeys)

    override fun remove(key: String) {
        val existed = contains(key)
        memoryCache.remove(key)
        knownKeys.remove(key)
        configDir.removeKey(key, ".bson")
        DesktopConfigDirectoryHelper.savePersistedKeySet(configDirectory, '/', knownKeys)
        if (existed) {
            notifyListeners(key, null)
        }
    }

    override fun clear() {
        val snapshotKeys = knownKeys.toList()
        for (k in snapshotKeys) {
            configDir.removeKey(k, ".bson")
        }
        configDir.clearKeysWithExtension(".bson")
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
            configDir.writeKeyBytes(k, ".bson", bytes)
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
