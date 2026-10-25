package com.nexustools.genericui.swing

import com.nexustools.genericui.component.system.PreferenceChangeListener
import com.nexustools.genericui.component.system.PreferenceElement
import com.nexustools.genericui.nativeimpl.system.PreferenceStoreNativeImpl
import com.nexustools.genericui.util.BinaryJson
import com.nexustools.genericui.util.BinaryPlist
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService

/**
 * Java Swing / JVM desktop (`:desktop:swing`) implementation of [PreferenceStoreNativeImpl].
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

    val configDirectory: File = (customDirectory ?: resolveHostAppConfigDirectory(appName)).apply {
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

    fun fileForKey(key: String): File {
        val safeKey = sanitizeName(key)
        return File(configDirectory, "$safeKey$fileExtension")
    }

    override fun writeElement(key: String, element: PreferenceElement) {
        if (element.isNull) {
            remove(key)
            return
        }
        configDirectory.mkdirs()
        val bytes = if (isMacOsHost) {
            BinaryPlist.encodeElement(element)
        } else {
            BinaryJson.encodeElement(element)
        }
        fileForKey(key).writeBytes(bytes)
        notifyListeners(key, element.toPlainValue())
    }

    override fun readElement(key: String): PreferenceElement? {
        val file = fileForKey(key)
        if (!file.exists() || !file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return if (isMacOsHost || BinaryPlist.isBinaryPlist(bytes)) {
            BinaryPlist.decodeElement(bytes)
        } else {
            BinaryJson.decodeElement(bytes)
        }
    }

    override fun contains(key: String): Boolean = fileForKey(key).isFile

    override fun keys(): Set<String> {
        val files = configDirectory.listFiles() ?: return emptySet()
        val result = LinkedHashSet<String>()
        for (f in files) {
            if (f.isFile && f.name.endsWith(fileExtension)) {
                val keyName = f.name.removeSuffix(fileExtension)
                if (keyName.isNotEmpty() && !keyName.startsWith(".")) {
                    result.add(keyName)
                }
            }
        }
        return result
    }

    override fun remove(key: String) {
        val file = fileForKey(key)
        if (file.exists()) {
            file.delete()
            notifyListeners(key, null)
        }
    }

    override fun clear() {
        val files = configDirectory.listFiles() ?: emptyArray()
        var deletedAny = false
        for (f in files) {
            if (f.isFile && f.name.endsWith(fileExtension)) {
                if (f.delete()) {
                    deletedAny = true
                }
            }
        }
        if (deletedAny) {
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
    }

    private fun notifyListeners(key: String?, newValue: Any?) {
        val snapshot = listeners.toList()
        for (listener in snapshot) {
            listener.onPreferenceChanged(appName, key, newValue)
        }
    }

    companion object {
        fun sanitizeName(raw: String): String {
            val trimmed = raw.trim().ifEmpty { "GenericUI" }
            return trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        }

        fun resolveHostAppConfigDirectory(appName: String): File {
            val safeApp = sanitizeName(appName)
            val osName = System.getProperty("os.name", "").lowercase()
            val userHome = System.getProperty("user.home", ".")
            if (osName.contains("win")) {
                val appData = System.getenv("APPDATA")?.trim()?.takeIf { it.isNotEmpty() }
                val baseDir = if (appData != null) File(appData) else File(userHome, "AppData/Roaming")
                return File(baseDir, safeApp)
            }
            val xdg = System.getenv("XDG_CONFIG_HOME")?.trim()?.takeIf { it.isNotEmpty() }
            val baseDir = if (xdg != null) File(xdg) else File(userHome, ".config")
            return File(baseDir, safeApp)
        }
    }
}
