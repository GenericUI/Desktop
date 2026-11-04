package com.nexustools.genericui.swing

import com.nexustools.genericui.component.dialog.FileTypeFilter
import com.nexustools.genericui.io.FileOpenMode
import com.nexustools.genericui.io.FilePermissionStatus
import com.nexustools.genericui.io.FileStat
import com.nexustools.genericui.io.FileSystemAvailability
import com.nexustools.genericui.nativeimpl.io.DefaultFileSystemNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import javax.swing.SwingUtilities
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext

/**
 * Java Swing / JVM (`:desktop:swing`) implementation of [com.nexustools.genericui.nativeimpl.io.FileSystemNativeImpl]
 * backed by `java.nio.file.Files`, [SwingFileReadableNativeImpl], and [SwingFileWritableNativeImpl] on [Dispatchers.IO],
 * with an automatic in-memory fallback ([DefaultFileSystemNativeImpl]) for virtual unit-test paths.
 */
class SwingFileSystemNativeImpl : DefaultFileSystemNativeImpl() {

    override fun isAvailable(): FileSystemAvailability = FileSystemAvailability.Always

    override fun hasPermission(path: String?, write: Boolean): FilePermissionStatus =
        FilePermissionStatus.GRANTED

    override suspend fun openReadable(path: String): SwingFileReadableNativeImpl? =
        createReadStream(path)

    override suspend fun openWritable(path: String, append: Boolean): SwingFileWritableNativeImpl =
        createWriteStream(path, if (append) FileOpenMode.WRITE_APPEND else FileOpenMode.WRITE_TRUNCATE)

    override suspend fun createReadStream(path: String): SwingFileReadableNativeImpl? {
        if (isDisposed) return null
        val nioPath = resolveRealNioPath(path, mustExist = true)
        if (nioPath != null) {
            return SwingFileReadableNativeImpl(nioPath)
        }
        val fallbackBytes = super.readFile(path) ?: return null
        return SwingFileReadableNativeImpl(initialBytes = fallbackBytes, filePath = normalizeKey(path))
    }

    override suspend fun createWriteStream(path: String, mode: FileOpenMode): SwingFileWritableNativeImpl {
        val append = mode == FileOpenMode.WRITE_APPEND
        val nioPath = resolveRealNioPath(path, mustExist = false)
        if (nioPath != null && !isVirtualPath(path)) {
            return SwingFileWritableNativeImpl(nioPath, append = append)
        }
        val norm = normalizeKey(path)
        val initial = if (append) files[norm] ?: ByteArray(0) else ByteArray(0)
        val bufferOut = ByteArrayOutputStream(maxOf(initial.size, 64))
        if (initial.isNotEmpty()) {
            bufferOut.write(initial)
        }
        return SwingFileWritableNativeImpl(bufferOut, filePath = norm, appendMode = append) {
            val committed = bufferOut.toByteArray()
            super.writeFile(norm, committed)
        }
    }

    override suspend fun openReadableFromFileDialog(
        title: String,
        initialDirectory: String?,
        filters: List<FileTypeFilter>
    ): Pair<String, FileReadableNativeImpl>? {
        val fallback = super.openReadableFromFileDialog(title, initialDirectory, filters)
        if (fallback != null) {
            val bytes = super.readFile(fallback.first)
            if (bytes != null) {
                return fallback.first to SwingFileReadableNativeImpl(initialBytes = bytes, filePath = fallback.first)
            }
        }
        return fallback
    }

    override suspend fun openWritableFromFileDialog(
        title: String,
        initialDirectory: String?,
        suggestedFileName: String,
        filters: List<FileTypeFilter>
    ): Pair<String, FileWritableNativeImpl>? {
        val target = resolve(initialDirectory ?: "/", suggestedFileName.ifEmpty { "untitled.bin" })
        return target to createWriteStream(target, FileOpenMode.WRITE_TRUNCATE)
    }

    override suspend fun readFile(path: String): ByteArray? {
        if (isDisposed) return null
        val norm = normalizeKey(path)
        if (files.containsKey(norm)) {
            return super.readFile(path)
        }
        val nioPath = resolveRealNioPath(path, mustExist = true)
        if (nioPath != null) {
            return runOnSwingIo {
                runCatching {
                    if (Files.isRegularFile(nioPath)) Files.readAllBytes(nioPath) else null
                }.getOrNull()
            }
        }
        return super.readFile(path)
    }

    override suspend fun writeFile(path: String, bytes: ByteArray): Boolean {
        if (isDisposed) return false
        val nioPath = resolveRealNioPath(path, mustExist = false)
        if (nioPath != null && !isVirtualPath(path)) {
            val written = runOnSwingIo {
                runCatching {
                    nioPath.parent?.let { Files.createDirectories(it) }
                    Files.write(
                        nioPath,
                        bytes,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                    )
                    true
                }.getOrDefault(false)
            }
            if (written) return true
        }
        return super.writeFile(path, bytes)
    }

    override suspend fun appendFile(path: String, bytes: ByteArray): Boolean {
        if (isDisposed) return false
        val nioPath = resolveRealNioPath(path, mustExist = false)
        if (nioPath != null && !isVirtualPath(path)) {
            val appended = runOnSwingIo {
                runCatching {
                    nioPath.parent?.let { Files.createDirectories(it) }
                    Files.write(
                        nioPath,
                        bytes,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND
                    )
                    true
                }.getOrDefault(false)
            }
            if (appended) return true
        }
        return super.appendFile(path, bytes)
    }

    override suspend fun exists(path: String): Boolean {
        if (isDisposed) return false
        if (super.exists(path)) return true
        val nioPath = resolveRealNioPath(path, mustExist = true)
        return nioPath != null
    }

    override suspend fun stat(path: String): FileStat? {
        if (isDisposed) return null
        super.stat(path)?.let { return it }
        val nioPath = resolveRealNioPath(path, mustExist = true) ?: return null
        return runOnSwingIo {
            runCatching {
                val attrs = Files.readAttributes(nioPath, BasicFileAttributes::class.java)
                FileStat(
                    path = nioPath.toString(),
                    name = nioPath.fileName?.toString() ?: path,
                    size = if (attrs.isRegularFile) attrs.size() else 0L,
                    isFile = attrs.isRegularFile,
                    isDirectory = attrs.isDirectory,
                    isSymbolicLink = attrs.isSymbolicLink,
                    lastModifiedEpochMs = attrs.lastModifiedTime().toMillis(),
                    createdEpochMs = attrs.creationTime().toMillis(),
                    isReadable = Files.isReadable(nioPath),
                    isWritable = Files.isWritable(nioPath)
                )
            }.getOrNull()
        }
    }

    override suspend fun readdir(path: String): List<String> {
        if (isDisposed) return emptyList()
        val combined = LinkedHashSet<String>()
        combined.addAll(super.readdir(path))
        val nioPath = resolveRealNioPath(path, mustExist = true)
        if (nioPath != null) {
            val diskEntries = runOnSwingIo {
                runCatching {
                    if (Files.isDirectory(nioPath)) {
                        Files.list(nioPath).use { stream ->
                            val out = ArrayList<String>()
                            val iterator = stream.iterator()
                            while (iterator.hasNext()) {
                                val child = iterator.next().fileName?.toString()
                                if (!child.isNullOrEmpty()) {
                                    out.add(child)
                                }
                            }
                            out.sorted()
                        }
                    } else {
                        emptyList()
                    }
                }.getOrDefault(emptyList())
            }
            combined.addAll(diskEntries)
        }
        return combined.toList()
    }

    override suspend fun mkdir(path: String, recursive: Boolean): Boolean {
        if (isDisposed) return false
        val nioPath = resolveRealNioPath(path, mustExist = false)
        if (nioPath != null && !isVirtualPath(path)) {
            val created = runOnSwingIo {
                runCatching {
                    if (recursive) {
                        Files.createDirectories(nioPath)
                    } else {
                        if (!Files.exists(nioPath)) Files.createDirectory(nioPath)
                    }
                    true
                }.getOrDefault(false)
            }
            if (created) return true
        }
        return super.mkdir(path, recursive)
    }

    override suspend fun unlink(path: String): Boolean {
        if (isDisposed) return false
        val removedMem = super.unlink(path)
        val nioPath = resolveRealNioPath(path, mustExist = true)
        if (nioPath != null) {
            val deletedDisk = runOnSwingIo {
                runCatching {
                    Files.isRegularFile(nioPath) && Files.deleteIfExists(nioPath)
                }.getOrDefault(false)
            }
            return removedMem || deletedDisk
        }
        return removedMem
    }

    override suspend fun rmdir(path: String, recursive: Boolean): Boolean {
        if (isDisposed) return false
        val removedMem = super.rmdir(path, recursive)
        val nioPath = resolveRealNioPath(path, mustExist = true)
        if (nioPath != null) {
            val deletedDisk = runOnSwingIo {
                runCatching {
                    val file = nioPath.toFile()
                    if (!file.isDirectory) return@runCatching false
                    if (recursive) file.deleteRecursively() else file.delete()
                }.getOrDefault(false)
            }
            return removedMem || deletedDisk
        }
        return removedMem
    }

    override suspend fun rename(oldPath: String, newPath: String): Boolean {
        if (isDisposed) return false
        val oldNio = resolveRealNioPath(oldPath, mustExist = true)
        val newNio = resolveRealNioPath(newPath, mustExist = false)
        if (oldNio != null && newNio != null && !isVirtualPath(newPath)) {
            val moved = runOnSwingIo {
                runCatching {
                    newNio.parent?.let { Files.createDirectories(it) }
                    Files.move(oldNio, newNio, StandardCopyOption.REPLACE_EXISTING)
                    true
                }.getOrDefault(false)
            }
            if (moved) return true
        }
        return super.rename(oldPath, newPath)
    }

    override suspend fun copyFile(srcPath: String, dstPath: String, overwrite: Boolean): Boolean {
        if (isDisposed) return false
        val srcNio = resolveRealNioPath(srcPath, mustExist = true)
        val dstNio = resolveRealNioPath(dstPath, mustExist = false)
        if (srcNio != null && dstNio != null && !isVirtualPath(dstPath)) {
            val copied = runOnSwingIo {
                runCatching {
                    if (!overwrite && Files.exists(dstNio)) return@runCatching false
                    dstNio.parent?.let { Files.createDirectories(it) }
                    if (overwrite) {
                        Files.copy(srcNio, dstNio, StandardCopyOption.REPLACE_EXISTING)
                    } else {
                        Files.copy(srcNio, dstNio)
                    }
                    true
                }.getOrDefault(false)
            }
            if (copied) return true
        }
        return super.copyFile(srcPath, dstPath, overwrite)
    }

    private fun isVirtualPath(rawPath: String): Boolean {
        val trimmed = rawPath.trim()
        if (trimmed.contains("://")) return true
        val norm = normalizeKey(trimmed)
        if (files.containsKey(norm) || (norm != "/" && directories.contains(norm))) {
            return true
        }
        if (File.separatorChar == '\\') {
            if (trimmed.startsWith("/") && !(trimmed.length >= 3 && trimmed[2] == ':')) {
                val candidate = File(trimmed)
                return !candidate.exists()
            }
        } else {
            if (trimmed.startsWith("/projects/") || trimmed == "/projects") {
                val candidate = File(trimmed)
                return !candidate.exists()
            }
        }
        return false
    }

    private fun resolveRealNioPath(rawPath: String, mustExist: Boolean): Path? {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty() || trimmed.contains("://")) return null
        return try {
            val path = Paths.get(trimmed)
            if (mustExist) {
                if (Files.exists(path)) path else null
            } else {
                path
            }
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun <T> runOnSwingIo(block: () -> T): T {
        val hasInterceptor = coroutineContext[ContinuationInterceptor] != null
        return if (hasInterceptor || SwingUtilities.isEventDispatchThread()) {
            withContext(Dispatchers.IO) { block() }
        } else {
            block()
        }
    }
}
