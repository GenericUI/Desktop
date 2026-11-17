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
    configDirectoryImpl: UserDirectoryNativeImpl = LinuxXdgConfigDirectoryImpl(appName)
) : PreferenceStoreNativeImpl {
    private val lock = LinuxReentrantLock()

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
    private val knownKeys = LinkedHashSet<String>()
    private val listeners = ArrayList<PreferenceChangeListener>()

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
        lock.withLock {
            memoryCache[key] = encoded
            knownKeys.add(key)
        }
        configDir.writeKeyBytes(key, ".bson", encoded)
        DesktopConfigDirectoryHelper.savePersistedKeySet(configDirectory, '/', lock.withLock { knownKeys.toSet() })
        notifyListeners(key, element.toPlainValue())
    }

    override fun readElement(key: String): PreferenceElement? {
        val fileBytes = configDir.readKeyBytes(key, ".bson")
        if (fileBytes != null && fileBytes.isNotEmpty()) {
            lock.withLock {
                memoryCache[key] = fileBytes
                knownKeys.add(key)
            }
            return BinaryJson.decodeElement(fileBytes)
        }
        val cached = lock.withLock { memoryCache[key] } ?: return null
        return BinaryJson.decodeElement(cached)
    }

    override fun contains(key: String): Boolean =
        lock.withLock { key in knownKeys || memoryCache.containsKey(key) } || configDir.containsKey(key, ".bson")

    override fun keys(): Set<String> = lock.withLock { LinkedHashSet(knownKeys) }

    override fun remove(key: String) {
        val existed = contains(key)
        val keysSnapshot = lock.withLock {
            memoryCache.remove(key)
            knownKeys.remove(key)
            knownKeys.toSet()
        }
        configDir.removeKey(key, ".bson")
        DesktopConfigDirectoryHelper.savePersistedKeySet(configDirectory, '/', keysSnapshot)
        if (existed) {
            notifyListeners(key, null)
        }
    }

    override fun clear() {
        val snapshotKeys = lock.withLock { knownKeys.toList() }
        for (k in snapshotKeys) {
            configDir.removeKey(k, ".bson")
        }
        configDir.clearKeysWithExtension(".bson")
        lock.withLock {
            memoryCache.clear()
            knownKeys.clear()
        }
        DesktopConfigDirectoryHelper.savePersistedKeySet(configDirectory, '/', emptySet())
        if (snapshotKeys.isNotEmpty()) {
            notifyListeners(null, null)
        }
    }

    override fun flush(): Boolean {
        if (isDisposed) return false
        configDir.ensureExists()
        val (cacheCopy, keysCopy) = lock.withLock { memoryCache.toMap() to knownKeys.toSet() }
        for ((k, bytes) in cacheCopy) {
            configDir.writeKeyBytes(k, ".bson", bytes)
        }
        DesktopConfigDirectoryHelper.savePersistedKeySet(configDirectory, '/', keysCopy)
        return true
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
