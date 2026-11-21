@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.nexustools.genericui.desktop.system

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.FileSystem
import com.nexustools.genericui.nativeimpl.io.DefaultFileReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.DefaultFileWritableNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl
import com.nexustools.genericui.nativeimpl.system.UserDirectoryNativeImpl
import com.nexustools.genericui.util.UserDirectory
import kotlin.concurrent.atomics.AtomicInt

/**
 * Shared Desktop (`:desktop:windows`, `:desktop:macos`, `:desktop:gtk`, `:desktop:qt`) implementation
 * of [UserDirectoryNativeImpl] backed by POSIX file I/O ([DesktopConfigDirectoryHelper]), [FileSystem]
 * async stream delegation (`usesFileSystemBasePath == true`), and direct POSIX `readdir` directory enumeration.
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
    private val diskBackedFiles = LinkedHashSet<String>()

    init {
        ensureDirectoryExists()
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
            if (written) {
                diskBackedFiles.add(cleanName)
            } else {
                diskBackedFiles.remove(cleanName)
            }
            written || memoryCache.containsKey(cleanName)
        }
    }

    override fun readBytes(fileName: String): ByteArray? {
        if (isDisposed) return null
        val cleanName = sanitizeFileName(fileName)
        val diskBytes = DesktopConfigDirectoryHelper.readBytesFromFile(resolveFilePath(cleanName))
        if (diskBytes != null) {
            withLock {
                diskBackedFiles.add(cleanName)
                putMemoryCacheUnlocked(cleanName, diskBytes)
            }
            return diskBytes
        }
        return withLock {
            if (cleanName in diskBackedFiles) {
                diskBackedFiles.remove(cleanName)
                memoryCache.remove(cleanName)
                null
            } else {
                memoryCache[cleanName]?.copyOf()
            }
        }
    }

    override fun exists(fileName: String): Boolean {
        if (isDisposed) return false
        val cleanName = sanitizeFileName(fileName)
        val onDisk = DesktopConfigDirectoryHelper.fileExists(resolveFilePath(cleanName))
        if (onDisk) {
            withLock {
                diskBackedFiles.add(cleanName)
            }
            return true
        }
        return withLock {
            if (cleanName in diskBackedFiles) {
                diskBackedFiles.remove(cleanName)
                memoryCache.remove(cleanName)
                false
            } else {
                memoryCache.containsKey(cleanName)
            }
        }
    }

    override fun fileSize(fileName: String): Long {
        if (isDisposed) return 0L
        val cleanName = sanitizeFileName(fileName)
        val fullPath = resolveFilePath(cleanName)
        if (DesktopConfigDirectoryHelper.fileExists(fullPath)) {
            return DesktopConfigDirectoryHelper.fileSize(fullPath)
        }
        return withLock {
            if (cleanName in diskBackedFiles) 0L else (memoryCache[cleanName]?.size?.toLong() ?: 0L)
        }
    }

    override fun delete(fileName: String): Boolean {
        if (isDisposed) return false
        val cleanName = sanitizeFileName(fileName)
        val deletedOnDisk = DesktopConfigDirectoryHelper.deleteFile(resolveFilePath(cleanName))
        return withLock {
            val hadInDisk = diskBackedFiles.remove(cleanName)
            val hadInMemory = memoryCache.remove(cleanName) != null
            hadInDisk || hadInMemory || deletedOnDisk
        }
    }

    fun rename(fromFileName: String, toFileName: String): Boolean {
        if (isDisposed) return false
        val fromClean = sanitizeFileName(fromFileName)
        val toClean = sanitizeFileName(toFileName)
        if (fromClean == toClean) return exists(fromClean)
        ensureDirectoryExists()
        val fromPath = resolveFilePath(fromClean)
        val toPath = resolveFilePath(toClean)
        val diskRenamed = DesktopConfigDirectoryHelper.renameFile(fromPath, toPath)
        return withLock {
            val cached = memoryCache.remove(fromClean)
            val wasDisk = diskBackedFiles.remove(fromClean)
            if (!diskRenamed && cached == null) {
                return@withLock false
            }
            if (cached != null) {
                putMemoryCacheUnlocked(toClean, cached)
            }
            if (diskRenamed || wasDisk) {
                diskBackedFiles.add(toClean)
            }
            true
        }
    }

    override fun listFiles(): List<String> = withLock {
        if (isDisposed) return@withLock emptyList()
        val diskEntries = DesktopConfigDirectoryHelper.listDirectoryFiles(directoryPath)
            .filter { !it.startsWith(".") }
        val diskSet = diskEntries.toHashSet()
        for (name in diskEntries) {
            diskBackedFiles.add(name)
        }
        val staleDisk = diskBackedFiles.filter { it !in diskSet }
        for (stale in staleDisk) {
            diskBackedFiles.remove(stale)
            memoryCache.remove(stale)
        }
        val combined = LinkedHashSet<String>(diskEntries)
        for (memName in memoryCache.keys) {
            if (!memName.startsWith(".") && memName !in diskBackedFiles) {
                combined.add(memName)
            }
        }
        combined.sorted()
    }

    override fun clearAll(): Int {
        if (isDisposed) return 0
        val diskEntries = DesktopConfigDirectoryHelper.listDirectoryFiles(directoryPath)
            .filter { !it.startsWith(".") }
        return withLock {
            val allNames = LinkedHashSet<String>(diskEntries)
            allNames.addAll(memoryCache.keys)
            var removedCount = 0
            for (name in allNames) {
                if (name.startsWith(".")) continue
                val fullPath = resolveFilePath(name)
                val diskRemoved = DesktopConfigDirectoryHelper.deleteFile(fullPath)
                val memRemoved = memoryCache.remove(name) != null
                diskBackedFiles.remove(name)
                if (diskRemoved || memRemoved) {
                    removedCount++
                }
            }
            memoryCache.clear()
            diskBackedFiles.clear()
            val baseDir = directoryPath.trimEnd('\\', '/')
            DesktopConfigDirectoryHelper.deleteFile("$baseDir$fileSeparator.user_dir_manifest")
            removedCount
        }
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
        return (FileSystem.openWritable(fullPath, append) as? FileWritableNativeImpl)
            ?: DefaultFileWritableNativeImpl(filePath = fullPath, appendMode = append) { committed ->
                writeBytes(cleanName, committed)
            }
    }

    override fun dispose() = withLock {
        isDisposed = true
        memoryCache.clear()
    }

    private fun sanitizeFileName(raw: String): String =
        UserDirectory.sanitizeFileName(raw)

    companion object {
        private const val MAX_MEMORY_CACHE_ENTRIES = 64
    }
}
