@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.nexustools.genericui.desktop.system

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.FileSystem
import com.nexustools.genericui.nativeimpl.io.DefaultFileReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.DefaultFileWritableNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl
import com.nexustools.genericui.nativeimpl.system.UserDirectoryNativeImpl
import kotlin.concurrent.atomics.AtomicInt

/**
 * Shared Desktop (`:desktop:windows`, `:desktop:macos`, `:desktop:gtk`, `:desktop:qt`) implementation
 * of [UserDirectoryNativeImpl] backed by POSIX file I/O ([DesktopConfigDirectoryHelper]), [FileSystem]
 * async stream delegation (`usesFileSystemBasePath == true`), and a persistent `.user_dir_manifest` file index.
 */
open class DesktopUserDirectoryNativeImpl(
    override val appName: String,
    override val subdirectory: String = "",
    baseDirectoryPath: String,
    override val fileSeparator: Char
) : UserDirectoryNativeImpl {

    override val usesFileSystemBasePath: Boolean = true

    override val directoryPath: String =
        DesktopConfigDirectoryHelper.appendSubdirectory(baseDirectoryPath, subdirectory, fileSeparator)

    override var isDisposed: Boolean = false
        protected set

    private val lockState = AtomicInt(0)
    private val memoryCache = LinkedHashMap<String, ByteArray>()
    private val knownFiles = LinkedHashSet<String>()

    init {
        ensureDirectoryExists()
        withLock { loadManifestUnlocked() }
    }

    private inline fun <T> withLock(block: () -> T): T {
        while (!lockState.compareAndSet(0, 1)) {
            // Short spin for thread-safe access across coroutine worker threads
        }
        try {
            return block()
        } finally {
            lockState.store(0)
        }
    }

    private fun putMemoryCacheUnlocked(cleanName: String, bytes: ByteArray) {
        if (memoryCache.size >= MAX_MEMORY_CACHE_ENTRIES && !memoryCache.containsKey(cleanName)) {
            val oldest = memoryCache.keys.firstOrNull()
            if (oldest != null) {
                memoryCache.remove(oldest)
            }
        }
        memoryCache[cleanName] = bytes
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
        val copy = bytes.copyOf()
        val fullPath = resolveFilePath(cleanName)
        val written = DesktopConfigDirectoryHelper.writeBytesToFile(fullPath, copy)
        return withLock {
            putMemoryCacheUnlocked(cleanName, copy)
            val added = knownFiles.add(cleanName)
            if (added) {
                saveManifestUnlocked()
            }
            written || memoryCache.containsKey(cleanName)
        }
    }

    override fun readBytes(fileName: String): ByteArray? {
        if (isDisposed) return null
        val cleanName = sanitizeFileName(fileName)
        withLock {
            memoryCache[cleanName]?.let { return it.copyOf() }
        }
        val diskBytes = DesktopConfigDirectoryHelper.readBytesFromFile(resolveFilePath(cleanName))
        if (diskBytes != null) {
            withLock {
                putMemoryCacheUnlocked(cleanName, diskBytes)
                if (!cleanName.startsWith(".")) {
                    knownFiles.add(cleanName)
                }
            }
            return diskBytes
        }
        return withLock { memoryCache[cleanName]?.copyOf() }
    }

    override fun exists(fileName: String): Boolean {
        if (isDisposed) return false
        val cleanName = sanitizeFileName(fileName)
        val inMemoryOrKnown = withLock {
            cleanName in knownFiles || memoryCache.containsKey(cleanName)
        }
        if (inMemoryOrKnown) return true
        val onDisk = DesktopConfigDirectoryHelper.fileExists(resolveFilePath(cleanName))
        if (onDisk && !cleanName.startsWith(".")) {
            withLock { knownFiles.add(cleanName) }
        }
        return onDisk
    }

    override fun fileSize(fileName: String): Long {
        if (isDisposed) return 0L
        val cleanName = sanitizeFileName(fileName)
        val diskLen = DesktopConfigDirectoryHelper.fileSize(resolveFilePath(cleanName))
        if (diskLen > 0L) return diskLen
        return withLock { memoryCache[cleanName]?.size?.toLong() ?: 0L }
    }

    override fun delete(fileName: String): Boolean {
        if (isDisposed) return false
        val cleanName = sanitizeFileName(fileName)
        val deletedOnDisk = DesktopConfigDirectoryHelper.deleteFile(resolveFilePath(cleanName))
        return withLock {
            val hadInMemory = memoryCache.remove(cleanName) != null
            val hadInKnown = knownFiles.remove(cleanName)
            if (hadInMemory || hadInKnown || deletedOnDisk) {
                saveManifestUnlocked()
                true
            } else {
                false
            }
        }
    }

    override fun listFiles(): List<String> = withLock {
        if (isDisposed) return@withLock emptyList()
        loadManifestUnlocked()
        knownFiles.filter { !it.startsWith(".") }
    }

    override fun clearAll(): Int = withLock {
        if (isDisposed) return@withLock 0
        loadManifestUnlocked()
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
        removedCount
    }

    override fun totalByteSize(): Long {
        if (isDisposed) return 0L
        var total = 0L
        for (name in listFiles()) {
            total += fileSize(name)
        }
        return total
    }

    override suspend fun readAsync(fileName: String): ByteBuffer? {
        if (isDisposed) return null
        val fullPath = resolveFilePath(fileName)
        val fsBuffer = FileSystem.read(fullPath)
        if (fsBuffer != null) {
            return fsBuffer
        }
        return readBytes(fileName)?.let { ByteBuffer.wrap(it) }
    }

    override suspend fun writeAsync(fileName: String, buffer: ByteBuffer, append: Boolean): Boolean {
        if (isDisposed) return false
        ensureDirectoryExists()
        val cleanName = sanitizeFileName(fileName)
        val fullPath = resolveFilePath(cleanName)
        val fsWritten = FileSystem.write(fullPath, buffer, append)
        val updatedBytes = FileSystem.read(fullPath)?.toByteArray() ?: if (append) {
            val existing = readBytes(cleanName)
            val incoming = buffer.toByteArray()
            if (existing == null || existing.isEmpty()) incoming else existing + incoming
        } else {
            buffer.toByteArray()
        }
        val syncWritten = writeBytes(cleanName, updatedBytes)
        return fsWritten || syncWritten
    }

    override suspend fun openReadableAsync(fileName: String): FileReadableNativeImpl? {
        if (isDisposed) return null
        val fullPath = resolveFilePath(fileName)
        val fsStream = FileSystem.openReadable(fullPath) as? FileReadableNativeImpl
        if (fsStream != null) {
            return fsStream
        }
        val bytes = readBytes(fileName) ?: return null
        FileSystem.writeFile(fullPath, bytes)
        return (FileSystem.openReadable(fullPath) as? FileReadableNativeImpl)
            ?: DefaultFileReadableNativeImpl(bytes, fullPath)
    }

    override suspend fun openWritableAsync(fileName: String, append: Boolean): FileWritableNativeImpl {
        ensureDirectoryExists()
        val cleanName = sanitizeFileName(fileName)
        val fullPath = resolveFilePath(cleanName)
        if (append && !FileSystem.exists(fullPath)) {
            readBytes(cleanName)?.let { FileSystem.writeFile(fullPath, it) }
        }
        withLock {
            if (knownFiles.add(cleanName)) {
                saveManifestUnlocked()
            }
        }
        return (FileSystem.openWritable(fullPath, append) as? FileWritableNativeImpl)
            ?: DefaultFileWritableNativeImpl(filePath = fullPath, appendMode = append) { committed ->
                writeBytes(cleanName, committed)
            }
    }

    override fun dispose() = withLock {
        isDisposed = true
        memoryCache.clear()
    }

    private fun manifestFilePath(): String {
        val baseDir = directoryPath.trimEnd('\\', '/')
        return "$baseDir$fileSeparator$MANIFEST_FILE_NAME"
    }

    private fun loadManifestUnlocked() {
        val bytes = DesktopConfigDirectoryHelper.readBytesFromFile(manifestFilePath()) ?: return
        for (line in bytes.decodeToString().lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith(".")) {
                knownFiles.add(trimmed)
            }
        }
    }

    private fun saveManifestUnlocked() {
        val content = knownFiles.filter { !it.startsWith(".") }.joinToString("\n").encodeToByteArray()
        DesktopConfigDirectoryHelper.writeBytesToFile(manifestFilePath(), content)
    }

    private fun sanitizeFileName(raw: String): String {
        val trimmed = raw.trim().trim('/', '\\').ifEmpty { "unnamed" }
        return trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    companion object {
        private const val MANIFEST_FILE_NAME = ".user_dir_manifest"
        private const val MAX_MEMORY_CACHE_ENTRIES = 64
    }
}
