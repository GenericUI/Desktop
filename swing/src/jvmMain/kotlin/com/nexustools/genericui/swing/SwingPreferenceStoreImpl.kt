package com.nexustools.genericui.swing

import com.nexustools.genericui.component.system.PreferenceChangeListener
import com.nexustools.genericui.component.system.PreferenceElement
import com.nexustools.genericui.nativeimpl.system.PreferenceStoreNativeImpl
import com.nexustools.genericui.util.BinaryJson
import com.nexustools.genericui.util.BinaryPlist
import com.nexustools.genericui.util.ConfigDirectory
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService

/**
 * Java Swing / JVM desktop (`:desktop:swing`) implementation of [PreferenceStoreNativeImpl] backed by
 * [ConfigDirectory] ([SwingUserDirectoryNativeImpl]).
 *
 * Stores each preference `key` as a separate binary file in `<userConfigDir>/<appName>/`:
 * - On macOS: `<configDir>/<appName>/<key>.plist` encoded with [BinaryPlist] (`bplist00`).
 * - On Windows (`%APPDATA%\<appName>\<key>.bson`) and Linux (`~/.config/<appName>/<key>.bson`):
 *   encoded with [BinaryJson].
 *
 * Registers a native Java NIO [WatchService] on the directory to detect external file changes
 * in addition to immediate in-process [PreferenceChangeListener] dispatch.
 */
class SwingPreferenceStoreImpl(
    override val appName: String = "GenericUI",
    customDirectory: File? = null
) : PreferenceStoreNativeImpl {

    val isMacOsHost: Boolean =
        System.getProperty("os.name", "").lowercase().let { it.contains("mac") || it.contains("darwin") }

    val fileExtension: String = if (isMacOsHost) ".plist" else ".bson"

    val configDir: ConfigDirectory = ConfigDirectory(
        appName = appName,
        nativeImplOverride = SwingUserDirectoryNativeImpl(
            appName = appName,
            subdirectory = "",
            isCache = false,
            customDirectory = customDirectory
        )
    )

    val configDirectory: File = File(configDir.directoryPath).apply {
        mkdirs()
    }

    override val storageLocationDescription: String =
        "${configDirectory.absolutePath}${File.separator}*$fileExtension (${if (isMacOsHost) "BinaryPlist" else "BinaryJson"})"

    override val usesNativeChangeListener: Boolean = true

    override var isDisposed: Boolean = false
        private set

    private val listeners = mutableListOf<PreferenceChangeListener>()
    private var watchService: WatchService? = null
    private var watchKey: WatchKey? = null

    init {
        runCatching {
            val ws = FileSystems.getDefault().newWatchService()
            val wk = configDirectory.toPath().register(
                ws,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE
            )
            watchService = ws
            watchKey = wk
        }
    }

    fun fileForKey(key: String): File =
        File(configDir.resolveKeyPath(key, fileExtension))

    override fun writeElement(key: String, element: PreferenceElement) {
        if (element.isNull) {
            remove(key)
            return
        }
        configDir.ensureExists()
        val bytes = if (isMacOsHost) {
            BinaryPlist.encodeElement(element)
        } else {
            BinaryJson.encodeElement(element)
        }
        configDir.writeKeyBytes(key, fileExtension, bytes)
        notifyListeners(key, element.toPlainValue())
    }

    override fun readElement(key: String): PreferenceElement? {
        val bytes = configDir.readKeyBytes(key, fileExtension) ?: return null
        if (bytes.isEmpty()) return null
        return if (isMacOsHost || BinaryPlist.isBinaryPlist(bytes)) {
            BinaryPlist.decodeElement(bytes)
        } else {
            BinaryJson.decodeElement(bytes)
        }
    }

    override fun contains(key: String): Boolean = configDir.containsKey(key, fileExtension)

    override fun keys(): Set<String> = configDir.keysForExtension(fileExtension)

    override fun remove(key: String) {
        if (configDir.removeKey(key, fileExtension)) {
            notifyListeners(key, null)
        }
    }

    override fun clear() {
        val removedCount = configDir.clearKeysWithExtension(fileExtension)
        if (removedCount > 0) {
            notifyListeners(null, null)
        }
    }

    override fun flush(): Boolean {
        pollWatchServiceEvents()
        return !isDisposed
    }

    /**
     * Polls any pending OS directory [WatchService] events and dispatches them to registered listeners.
     */
    fun pollWatchServiceEvents() {
        val wk = watchKey ?: return
        val events = wk.pollEvents()
        for (ev in events) {
            val changedPath = ev.context()?.toString() ?: continue
            if (!changedPath.endsWith(fileExtension)) continue
            val key = changedPath.removeSuffix(fileExtension)
            if (ev.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                notifyListeners(key, null)
            } else {
                val updated = readElement(key)?.toPlainValue()
                notifyListeners(key, updated)
            }
        }
        wk.reset()
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
        runCatching { watchKey?.cancel() }
        runCatching { watchService?.close() }
        watchKey = null
        watchService = null
        configDir.dispose()
    }

    private fun notifyListeners(key: String?, newValue: Any?) {
        val snapshot = listeners.toList()
        for (listener in snapshot) {
            listener.onPreferenceChanged(appName, key, newValue)
        }
    }

    companion object {
        fun sanitizeName(raw: String): String = SwingUserDirectoryNativeImpl.sanitizeName(raw)

        fun resolveHostAppConfigDirectory(appName: String): File =
            SwingUserDirectoryNativeImpl.resolveHostAppConfigDirectory(appName)
    }
}
