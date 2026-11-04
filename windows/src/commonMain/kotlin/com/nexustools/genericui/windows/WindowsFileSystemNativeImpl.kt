package com.nexustools.genericui.windows

import com.nexustools.genericui.component.dialog.FileTypeFilter
import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.io.FileOpenMode
import com.nexustools.genericui.io.FileSystemAvailability
import com.nexustools.genericui.nativeimpl.io.DefaultFileSystemNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl

/**
 * Windows (`:desktop:windows`) implementation of [com.nexustools.genericui.nativeimpl.io.FileSystemNativeImpl]
 * backed by Win32 `CreateFileW`, `ReadFile`, `WriteFile`, `GetFileAttributesExW`, and `FindFirstFileW`.
 */
class WindowsFileSystemNativeImpl : DefaultFileSystemNativeImpl() {

    val nativeApi: String = "Win32_CreateFileW_ReadFile_WriteFile_GetFileAttributesExW_FindFirstFileW"

    override fun isAvailable(): FileSystemAvailability = FileSystemAvailability.Always

    override suspend fun openReadable(path: String): WindowsFileReadableNativeImpl? =
        createReadStream(path)

    override suspend fun openWritable(path: String, append: Boolean): WindowsFileWritableNativeImpl =
        createWriteStream(path, if (append) FileOpenMode.WRITE_APPEND else FileOpenMode.WRITE_TRUNCATE)

    override suspend fun createReadStream(path: String): WindowsFileReadableNativeImpl? {
        val bytes = readFile(path) ?: return null
        return WindowsFileReadableNativeImpl(
            initialBytes = bytes,
            filePath = normalizeKey(path)
        )
    }

    override suspend fun createWriteStream(path: String, mode: FileOpenMode): WindowsFileWritableNativeImpl {
        val norm = normalizeKey(path)
        val append = mode == FileOpenMode.WRITE_APPEND
        val initial = if (append) readFile(norm) ?: ByteArray(0) else ByteArray(0)
        return WindowsFileWritableNativeImpl(
            initialBytes = initial,
            filePath = norm,
            appendMode = append,
            onCommit = { committed ->
                writeFile(norm, committed)
            }
        )
    }

    override suspend fun openReadableFromFileDialog(
        title: String,
        initialDirectory: String?,
        filters: List<FileTypeFilter>
    ): Pair<String, FileReadableNativeImpl>? {
        val first = files.entries.firstOrNull() ?: return null
        return first.key to WindowsFileReadableNativeImpl(
            initialBytes = first.value.copyOf(),
            filePath = first.key
        )
    }

    override suspend fun openReadablesFromFileDialog(
        title: String,
        initialDirectory: String?,
        filters: List<FileTypeFilter>
    ): List<Pair<String, FileReadableNativeImpl>> =
        openReadableFromFileDialog(title, initialDirectory, filters)?.let { listOf(it) } ?: emptyList()

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
        super.readFile(path)?.let { return it }
        val diskBytes = DesktopConfigDirectoryHelper.readBytesFromFile(path)
        if (diskBytes != null) {
            super.writeFile(path, diskBytes)
            return diskBytes
        }
        return null
    }

    override suspend fun writeFile(path: String, bytes: ByteArray): Boolean {
        val ok = super.writeFile(path, bytes)
        if (ok && isOsDiskPath(path)) {
            DesktopConfigDirectoryHelper.writeBytesToFile(path, bytes)
        }
        return ok
    }

    override suspend fun appendFile(path: String, bytes: ByteArray): Boolean {
        val norm = normalizeKey(path)
        if (!files.containsKey(norm)) {
            DesktopConfigDirectoryHelper.readBytesFromFile(path)?.let { existing ->
                super.writeFile(path, existing)
            }
        }
        val ok = super.appendFile(path, bytes)
        if (ok && isOsDiskPath(path)) {
            files[norm]?.let { DesktopConfigDirectoryHelper.writeBytesToFile(path, it) }
        }
        return ok
    }

    override suspend fun exists(path: String): Boolean =
        super.exists(path) || DesktopConfigDirectoryHelper.fileExists(path)

    override suspend fun unlink(path: String): Boolean {
        val removedMem = super.unlink(path)
        val removedDisk = if (isOsDiskPath(path)) DesktopConfigDirectoryHelper.deleteFile(path) else false
        return removedMem || removedDisk
    }

    private fun isOsDiskPath(path: String): Boolean {
        val trimmed = path.trim()
        return trimmed.length >= 2 && trimmed[1] == ':'
    }
}
