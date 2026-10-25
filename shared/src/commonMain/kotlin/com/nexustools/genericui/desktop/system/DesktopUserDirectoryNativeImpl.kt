package com.nexustools.genericui.desktop.system

import com.nexustools.genericui.nativeimpl.system.UserDirectoryNativeImpl

/**
 * Shared Desktop (`:desktop:windows`, `:desktop:macos`, `:desktop:gtk`, `:desktop:qt`) implementation
 * of [UserDirectoryNativeImpl] backed by POSIX file I/O ([DesktopConfigDirectoryHelper]) and a
 * persistent `.user_dir_manifest` file index.
 */
open class DesktopUserDirectoryNativeImpl(
    override val appName: String,
    override val subdirectory: String = "",
    baseDirectoryPath: String,
    override val fileSeparator: Char
) : UserDirectoryNativeImpl {

    override val directoryPath: String =
        DesktopConfigDirectoryHelper.appendSubdirectory(baseDirectoryPath, subdirectory, fileSeparator)

    override var isDisposed: Boolean = false
        protected set

    private val memoryCache = LinkedHashMap<String, ByteArray>()
    private val knownFiles = LinkedHashSet<String>()

    init {
        ensureDirectoryExists()
        loadManifest()
    }

    override fun ensureDirectoryExists(): Boolean {
        if (isDisposed) return false
        return DesktopConfigDirectoryHelper.ensureDirectoryExists(directoryPath)
    }

    override fun resolveFilePath(fileName: String): String {
        val cleanName = sanitizeFileName(fileName)
        val baseDir = directoryPath.trimEnd('\\', '/')
        return "$baseDir$fileSeparator$cleanName"
    }

    override fun writeBytes(fileName: String, bytes: ByteArray): Boolean {
        if (isDisposed) return false
        val cleanName = sanitizeFileName(fileName)
        ensureDirectoryExists()
        memoryCache[cleanName] = bytes.copyOf()
        knownFiles.add(cleanName)
        val written = DesktopConfigDirectoryHelper.writeBytesToFile(resolveFilePath(cleanName), bytes)
        saveManifest()
        return written || memoryCache.containsKey(cleanName)
    }

    override fun readBytes(fileName: String): ByteArray? {
        if (isDisposed) return null
        val cleanName = sanitizeFileName(fileName)
        val diskBytes = DesktopConfigDirectoryHelper.readBytesFromFile(resolveFilePath(cleanName))
        if (diskBytes != null) {
            memoryCache[cleanName] = diskBytes
            if (!cleanName.startsWith(".")) {
                knownFiles.add(cleanName)
            }
            return diskBytes
        }
        return memoryCache[cleanName]?.copyOf()
    }

    override fun exists(fileName: String): Boolean {
        if (isDisposed) return false
        val cleanName = sanitizeFileName(fileName)
        return cleanName in knownFiles ||
            memoryCache.containsKey(cleanName) ||
            DesktopConfigDirectoryHelper.fileExists(resolveFilePath(cleanName))
    }

    override fun fileSize(fileName: String): Long {
        if (isDisposed) return 0L
        val cleanName = sanitizeFileName(fileName)
        val diskLen = DesktopConfigDirectoryHelper.fileSize(resolveFilePath(cleanName))
        if (diskLen > 0L) return diskLen
        return memoryCache[cleanName]?.size?.toLong() ?: 0L
    }

    override fun delete(fileName: String): Boolean {
        if (isDisposed) return false
        val cleanName = sanitizeFileName(fileName)
        val hadInMemory = memoryCache.remove(cleanName) != null
        val hadInKnown = knownFiles.remove(cleanName)
        val deletedOnDisk = DesktopConfigDirectoryHelper.deleteFile(resolveFilePath(cleanName))
        if (hadInMemory || hadInKnown || deletedOnDisk) {
            saveManifest()
            return true
        }
        return false
    }

    override fun listFiles(): List<String> {
        if (isDisposed) return emptyList()
        loadManifest()
        return knownFiles.filter { !it.startsWith(".") }
    }

    override fun clearAll(): Int {
        if (isDisposed) return 0
        loadManifest()
        val snapshot = knownFiles.toList()
        var removedCount = 0
        for (name in snapshot) {
            val fullPath = resolveFilePath(name)
            val diskRemoved = DesktopConfigDirectoryHelper.deleteFile(fullPath)
            val memRemoved = memoryCache.remove(name) != null
            if (diskRemoved || memRemoved || name in knownFiles) {
                removedCount++
            }
        }
        memoryCache.clear()
        knownFiles.clear()
        DesktopConfigDirectoryHelper.deleteFile(manifestFilePath())
        return removedCount
    }

    override fun totalByteSize(): Long {
        if (isDisposed) return 0L
        var total = 0L
        for (name in listFiles()) {
            total += fileSize(name)
        }
        return total
    }

    override fun dispose() {
        isDisposed = true
        memoryCache.clear()
    }

    private fun manifestFilePath(): String {
        val baseDir = directoryPath.trimEnd('\\', '/')
        return "$baseDir$fileSeparator$MANIFEST_FILE_NAME"
    }

    private fun loadManifest() {
        val bytes = DesktopConfigDirectoryHelper.readBytesFromFile(manifestFilePath()) ?: return
        for (line in bytes.decodeToString().lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith(".")) {
                knownFiles.add(trimmed)
            }
        }
    }

    private fun saveManifest() {
        val content = knownFiles.filter { !it.startsWith(".") }.joinToString("\n").encodeToByteArray()
        DesktopConfigDirectoryHelper.writeBytesToFile(manifestFilePath(), content)
    }

    private fun sanitizeFileName(raw: String): String {
        val trimmed = raw.trim().trim('/', '\\').ifEmpty { "unnamed" }
        return trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    companion object {
        private const val MANIFEST_FILE_NAME = ".user_dir_manifest"
    }
}
