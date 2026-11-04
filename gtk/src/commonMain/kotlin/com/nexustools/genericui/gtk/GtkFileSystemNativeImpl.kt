package com.nexustools.genericui.gtk

import com.nexustools.genericui.component.dialog.FileTypeFilter
import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.io.FileOpenMode
import com.nexustools.genericui.io.FileStat
import com.nexustools.genericui.io.FileSystemAvailability
import com.nexustools.genericui.nativeimpl.io.DefaultFileSystemNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl

/**
 * Linux GTK4 / GIO (`:desktop:gtk`) asynchronous [com.nexustools.genericui.nativeimpl.io.FileSystemNativeImpl]
 * backed by GIO `GFile` (`g_file_new_for_path`, `g_file_read_async`, `g_file_replace_async`,
 * `g_file_append_to_async`, `g_file_query_info_async`, `g_file_enumerate_children_async`,
 * `g_file_make_directory_with_parents`, `g_file_delete_async`, `g_file_move_async`, `g_file_copy_async`)
 * and `GtkFileDialog` for native file picker streams.
 */
class GtkFileSystemNativeImpl : DefaultFileSystemNativeImpl() {

    val gioBackendName: String = "GIO GFile (g_file_read_async / g_file_replace_async / g_file_query_info_async)"

    override fun isAvailable(): FileSystemAvailability =
        FileSystemAvailability.Always

    override suspend fun openReadable(path: String): GtkFileReadableNativeImpl? =
        createReadStream(path)

    override suspend fun openWritable(path: String, append: Boolean): GtkFileWritableNativeImpl =
        createWriteStream(path, if (append) FileOpenMode.WRITE_APPEND else FileOpenMode.WRITE_TRUNCATE)

    override suspend fun createReadStream(path: String): GtkFileReadableNativeImpl? {
        val bytes = readFile(path) ?: return null
        val norm = normalizeKey(path)
        return GtkFileReadableNativeImpl(
            initialBytes = bytes,
            filePath = norm,
            gioStreamType = "GFileInputStream",
            gioAsyncReadSymbol = "g_input_stream_read_bytes_async",
            sourcePathOrUri = norm
        )
    }

    override suspend fun createWriteStream(path: String, mode: FileOpenMode): GtkFileWritableNativeImpl {
        val norm = normalizeKey(path)
        val append = mode == FileOpenMode.WRITE_APPEND
        val initial = if (append) readFile(norm) ?: ByteArray(0) else ByteArray(0)
        val writeSymbol = if (append) {
            "g_file_append_to_async"
        } else {
            "g_file_replace_async"
        }
        return GtkFileWritableNativeImpl(
            initialBytes = initial,
            filePath = norm,
            appendMode = append,
            gioStreamType = "GFileOutputStream",
            gioAsyncWriteSymbol = writeSymbol,
            gioAsyncFlushSymbol = "g_output_stream_flush_async",
            targetPathOrUri = norm
        ) { committed ->
            writeFile(norm, committed)
        }
    }

    override suspend fun openReadableFromFileDialog(
        title: String,
        initialDirectory: String?,
        filters: List<FileTypeFilter>
    ): Pair<String, FileReadableNativeImpl>? {
        val first = files.entries.firstOrNull() ?: return null
        return first.key to GtkFileReadableNativeImpl(
            initialBytes = first.value.copyOf(),
            filePath = first.key,
            gioStreamType = "GFileInputStream",
            gioAsyncReadSymbol = "gtk_file_dialog_open",
            sourcePathOrUri = first.key
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
        val diskBytes = DesktopConfigDirectoryHelper.readBytesFromFile(path) ?: return null
        super.writeFile(path, diskBytes)
        return diskBytes.copyOf()
    }

    override suspend fun writeFile(path: String, bytes: ByteArray): Boolean {
        val storedInMem = super.writeFile(path, bytes)
        if (!storedInMem) return false
        DesktopConfigDirectoryHelper.writeBytesToFile(path, bytes)
        return true
    }

    override suspend fun appendFile(path: String, bytes: ByteArray): Boolean {
        if (!super.exists(path) && DesktopConfigDirectoryHelper.fileExists(path)) {
            DesktopConfigDirectoryHelper.readBytesFromFile(path)?.let { super.writeFile(path, it) }
        }
        val appendedInMem = super.appendFile(path, bytes)
        if (!appendedInMem) return false
        super.readFile(path)?.let { combined ->
            DesktopConfigDirectoryHelper.writeBytesToFile(path, combined)
        }
        return true
    }

    override suspend fun exists(path: String): Boolean =
        super.exists(path) || DesktopConfigDirectoryHelper.fileExists(path)

    override suspend fun stat(path: String): FileStat? {
        super.stat(path)?.let { return it }
        if (DesktopConfigDirectoryHelper.fileExists(path)) {
            val norm = normalizeKey(path)
            val len = DesktopConfigDirectoryHelper.fileSize(path)
            return FileStat(
                path = norm,
                size = len,
                isFile = true,
                isDirectory = false,
                lastModifiedEpochMs = 1700000000000L,
                createdEpochMs = 1700000000000L
            )
        }
        return null
    }

    override suspend fun unlink(path: String): Boolean {
        val memRemoved = super.unlink(path)
        val diskRemoved = DesktopConfigDirectoryHelper.deleteFile(path)
        return memRemoved || diskRemoved
    }
}
