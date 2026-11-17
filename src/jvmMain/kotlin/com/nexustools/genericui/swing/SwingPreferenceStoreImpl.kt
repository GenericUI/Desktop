package com.nexustools.genericui.swing

import com.nexustools.genericui.jvm.JvmPreferenceStoreBase
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService

/**
 * Java Swing / JVM desktop implementation of
 * [com.nexustools.genericui.nativeimpl.system.PreferenceStoreNativeImpl] extending [JvmPreferenceStoreBase]
 * with a Java NIO [WatchService] to detect external preference file modifications on disk.
 */
class SwingPreferenceStoreImpl(
    appName: String = "GenericUI",
    customDirectory: File? = null
) : JvmPreferenceStoreBase(
    appName = appName,
    customDirectory = customDirectory
) {

    private var watchService: WatchService? = null
    private var watchKey: WatchKey? = null

    init {
        storageDirectory
    }

    override fun onDirectoryInitialized(directory: File) {
        runCatching {
            val ws = FileSystems.getDefault().newWatchService()
            val wk = directory.toPath().register(
                ws,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE
            )
            watchService = ws
            watchKey = wk
        }
    }

    override fun onFlushPoll() {
        pollWatchServiceEvents()
    }

    override fun onDisposeResources() {
        runCatching { watchKey?.cancel() }
        runCatching { watchService?.close() }
        watchKey = null
        watchService = null
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

    companion object {
        fun sanitizeName(raw: String): String = JvmPreferenceStoreBase.sanitizeName(raw)

        fun resolveHostAppConfigDirectory(appName: String): File =
            JvmPreferenceStoreBase.resolveHostAppConfigDirectory(appName)
    }
}
