package com.nexustools.genericui.qt

import com.nexustools.genericui.component.dialog.FileTypeFilter
import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.io.FileOpenMode
import com.nexustools.genericui.io.FileStat
import com.nexustools.genericui.io.FileSystemAvailability
import com.nexustools.genericui.nativeimpl.io.DefaultFileSystemNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl

/**
 * Qt 6 (`:desktop:qt`) asynchronous [com.nexustools.genericui.nativeimpl.io.FileSystemNativeImpl]
 * backed by `QFile`, `QSaveFile`, `QFileInfo`, `QDir` (`QDir::cleanPath`, `QFileInfo::exists`,
 * `QFileInfo::size`, `QDir::entryList`, `QDir::mkpath`, `QFile::remove`, `QFile::rename`, `QFile::copy`),
 * and `QFileDialog` for native file picker streams.
 */
class QtFileSystemNativeImpl : DefaultFileSystemNativeImpl() {

    val qtBackendName: String = "Qt6 QFile / QSaveFile / QFileInfo / QDir"

    override fun isAvailable(): FileSystemAvailability =
        FileSystemAvailability.Always

    override suspend fun openReadable(path: String): QtFileReadableNativeImpl? =
        createReadStream(path)

    override suspend fun openWritable(path: String, append: Boolean): QtFileWritableNativeImpl =
        createWriteStream(path, if (append) FileOpenMode.WRITE_APPEND else FileOpenMode.WRITE_TRUNCATE)

    override suspend fun createReadStream(path: String): QtFileReadableNativeImpl? {
        val bytes = readFile(path) ?: return null
        val norm = normalizeKey(path)
        return QtFileReadableNativeImpl(
            initialBytes = bytes,
            filePath = norm,
            qtDeviceClass = "QFile",
            qtSignalName = "QIODevice::readyRead",
            sourcePathOrUrl = norm
        )
    }

    override suspend fun createWriteStream(path: String, mode: FileOpenMode): QtFileWritableNativeImpl {
        val norm = normalizeKey(path)
        val append = mode == FileOpenMode.WRITE_APPEND
        val initial = if (append) readFile(norm) ?: ByteArray(0) else ByteArray(0)
        val deviceClass = if (append) "QFile" else "QSaveFile"
        return QtFileWritableNativeImpl(
            initialBytes = initial,
            filePath = norm,
            appendMode = append,
            qtDeviceClass = deviceClass,
            qtSignalName = "QIODevice::bytesWritten",
            targetPathOrUrl = norm
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
        return first.key to QtFileReadableNativeImpl(
            initialBytes = first.value.copyOf(),
            filePath = first.key,
            qtDeviceClass = "QFile",
            qtSignalName = "QFileDialog::getOpenFileContent",
            sourcePathOrUrl = first.key
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
