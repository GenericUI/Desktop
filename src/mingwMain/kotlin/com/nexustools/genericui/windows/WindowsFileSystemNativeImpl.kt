@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.component.dialog.FileTypeFilter
import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.FileOpenMode
import com.nexustools.genericui.io.FilePermissionStatus
import com.nexustools.genericui.io.FileStat
import com.nexustools.genericui.io.FileSystemAvailability
import com.nexustools.genericui.nativeimpl.io.FileReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileSystemNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.windows.CREATE_ALWAYS
import platform.windows.CloseHandle
import platform.windows.CopyFileW
import platform.windows.CreateFileW
import platform.windows.DeleteFileW
import platform.windows.FILE_ATTRIBUTE_DIRECTORY
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_ATTRIBUTE_READONLY
import platform.windows.FILE_ATTRIBUTE_REPARSE_POINT
import platform.windows.FILE_END
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.FindClose
import platform.windows.FindFirstFileW
import platform.windows.FindNextFileW
import platform.windows.FlushFileBuffers
import platform.windows.GENERIC_READ
import platform.windows.GENERIC_WRITE
import platform.windows.GET_FILEEX_INFO_LEVELS
import platform.windows.GetFileAttributesExW
import platform.windows.GetFileAttributesW
import platform.windows.GetFileSizeEx
import platform.windows.INVALID_FILE_ATTRIBUTES
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.LARGE_INTEGER
import platform.windows.MOVEFILE_COPY_ALLOWED
import platform.windows.MOVEFILE_REPLACE_EXISTING
import platform.windows.MoveFileExW
import platform.windows.OPEN_ALWAYS
import platform.windows.OPEN_EXISTING
import platform.windows.ReadFile
import platform.windows.RemoveDirectoryW
import platform.windows.SetFilePointerEx
import platform.windows.WIN32_FILE_ATTRIBUTE_DATA
import platform.windows.WIN32_FIND_DATAW
import platform.windows.WriteFile

/**
 * Windows Win32 (`CreateFileW` / `ReadFile` / `WriteFile` / `GetFileAttributesExW` / `FindFirstFileW`)
 * implementation of [FileSystemNativeImpl].
 */
class WindowsFileSystemNativeImpl : FileSystemNativeImpl {

    val nativeApi: String = "Win32_CreateFileW_ReadFile_WriteFile_GetFileAttributesExW_FindFirstFileW"

    private val lock = WindowsSpinLock()
    private var disposedState: Boolean = false

    override val fileSeparator: Char = '\\'

    override val isDisposed: Boolean
        get() = lock.withLock { disposedState }

    override fun isAvailable(): FileSystemAvailability = FileSystemAvailability.Always

    override fun hasPermission(path: String?, write: Boolean): FilePermissionStatus {
        if (isDisposed) return FilePermissionStatus.UNSUPPORTED
        if (path.isNullOrBlank()) return FilePermissionStatus.GRANTED
        val norm = WindowsIoHelper.normalizeWin32Path(path)
        val attrs = GetFileAttributesW(norm)
        if (!write) return FilePermissionStatus.GRANTED
        if (attrs != INVALID_FILE_ATTRIBUTES && (attrs.toInt() and FILE_ATTRIBUTE_READONLY) != 0) {
            return FilePermissionStatus.DENIED
        }
        return FilePermissionStatus.GRANTED
    }

    override fun resolve(vararg segments: String): String {
        if (segments.isEmpty()) return ""
        val cleaned = ArrayList<String>(segments.size)
        var preserveLeadingUnc = false
        for (seg in segments) {
            val trimmed = seg.trim()
            if (trimmed.isEmpty()) continue
            if (cleaned.isEmpty() && (trimmed.startsWith("\\\\") || trimmed.startsWith("//"))) {
                preserveLeadingUnc = true
            }
            val normalizedSeg = trimmed.replace('/', '\\')
            for (part in normalizedSeg.split('\\')) {
                if (part.isNotEmpty() && part != ".") {
                    cleaned.add(part)
                }
            }
        }
        if (cleaned.isEmpty()) return ""
        val joined = cleaned.joinToString("\\")
        return if (preserveLeadingUnc) "\\\\$joined" else joined
    }

    override suspend fun requestDirectoryAccess(initialPath: String?): String? {
        if (isDisposed || !WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) return null
        val hwnd = Win32NativeWindowHost.activeHwnd ?: return null
        return WindowsFileBrowserDialogNativeImpl.showNativeWin32FolderPicker(
            hwndOwner = hwnd,
            title = "Select Folder"
        )
    }

    override suspend fun read(path: String): ByteBuffer? {
        val bytes = readFile(path) ?: return null
        return ByteBuffer.wrap(bytes)
    }

    override suspend fun write(path: String, buffer: ByteBuffer, append: Boolean): Boolean {
        val bytes = buffer.remainingByteArray()
        return if (append) appendFile(path, bytes) else writeFile(path, bytes)
    }

    override suspend fun openReadable(path: String): WindowsFileReadableNativeImpl? =
        createReadStream(path)

    override suspend fun openWritable(path: String, append: Boolean): WindowsFileWritableNativeImpl =
        createWriteStream(path, if (append) FileOpenMode.WRITE_APPEND else FileOpenMode.WRITE_TRUNCATE)

    override suspend fun createReadStream(path: String): WindowsFileReadableNativeImpl? {
        if (isDisposed || !exists(path)) return null
        return WindowsFileReadableNativeImpl(WindowsIoHelper.normalizeWin32Path(path))
    }

    override suspend fun createWriteStream(path: String, mode: FileOpenMode): WindowsFileWritableNativeImpl {
        require(mode != FileOpenMode.READ) { "createWriteStream cannot open in READ-only mode for $path" }
        val normalized = WindowsIoHelper.normalizeWin32Path(path)
        WindowsIoHelper.ensureParentDirectories(normalized)
        return WindowsFileWritableNativeImpl(
            filePath = normalized,
            appendMode = mode == FileOpenMode.WRITE_APPEND
        )
    }

    override suspend fun openReadableFromFileDialog(
        title: String,
        initialDirectory: String?,
        filters: List<FileTypeFilter>
    ): Pair<String, FileReadableNativeImpl>? {
        if (isDisposed || !WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) return null
        val hwnd = Win32NativeWindowHost.activeHwnd ?: return null
        val chosen = WindowsFileBrowserDialogNativeImpl.showNativeWin32FileDialog(
            hwndOwner = hwnd,
            title = title,
            initialDirectory = initialDirectory,
            isSave = false,
            allowMultiple = false,
            filters = filters
        ).firstOrNull() ?: return null
        val stream = createReadStream(chosen) ?: return null
        return chosen to stream
    }

    override suspend fun openReadablesFromFileDialog(
        title: String,
        initialDirectory: String?,
        filters: List<FileTypeFilter>
    ): List<Pair<String, FileReadableNativeImpl>> {
        if (isDisposed || !WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) return emptyList()
        val hwnd = Win32NativeWindowHost.activeHwnd ?: return emptyList()
        val chosenPaths = WindowsFileBrowserDialogNativeImpl.showNativeWin32FileDialog(
            hwndOwner = hwnd,
            title = title,
            initialDirectory = initialDirectory,
            isSave = false,
            allowMultiple = true,
            filters = filters
        )
        val out = ArrayList<Pair<String, FileReadableNativeImpl>>(chosenPaths.size)
        for (path in chosenPaths) {
            val stream = createReadStream(path) ?: continue
            out.add(path to stream)
        }
        return out
    }

    override suspend fun openWritableFromFileDialog(
        title: String,
        initialDirectory: String?,
        suggestedFileName: String,
        filters: List<FileTypeFilter>
    ): Pair<String, FileWritableNativeImpl>? {
        if (isDisposed || !WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) return null
        val hwnd = Win32NativeWindowHost.activeHwnd ?: return null
        val target = WindowsFileBrowserDialogNativeImpl.showNativeWin32FileDialog(
            hwndOwner = hwnd,
            title = title,
            suggestedFileName = suggestedFileName,
            initialDirectory = initialDirectory,
            isSave = true,
            allowMultiple = false,
            filters = filters
        ).firstOrNull() ?: return null
        return target to createWriteStream(target, FileOpenMode.WRITE_TRUNCATE)
    }

    override suspend fun readFile(path: String): ByteArray? {
        if (isDisposed) return null
        val norm = WindowsIoHelper.normalizeWin32Path(path)
        return WindowsIoHelper.runIo {
            readFileSync(norm)
        }
    }

    override suspend fun writeFile(path: String, bytes: ByteArray): Boolean {
        if (isDisposed) return false
        val norm = WindowsIoHelper.normalizeWin32Path(path)
        return WindowsIoHelper.runIo {
            writeFileSync(norm, bytes, append = false)
        }
    }

    override suspend fun appendFile(path: String, bytes: ByteArray): Boolean {
        if (isDisposed) return false
        val norm = WindowsIoHelper.normalizeWin32Path(path)
        return WindowsIoHelper.runIo {
            writeFileSync(norm, bytes, append = true)
        }
    }

    override suspend fun exists(path: String): Boolean {
        if (isDisposed) return false
        val norm = WindowsIoHelper.normalizeWin32Path(path)
        return WindowsIoHelper.runIo {
            GetFileAttributesW(norm) != INVALID_FILE_ATTRIBUTES
        }
    }

    override suspend fun stat(path: String): FileStat? {
        if (isDisposed) return null
        val norm = WindowsIoHelper.normalizeWin32Path(path)
        return WindowsIoHelper.runIo {
            memScoped {
                val data = alloc<WIN32_FILE_ATTRIBUTE_DATA>()
                val ok = GetFileAttributesExW(norm, GET_FILEEX_INFO_LEVELS.GetFileExInfoStandard, data.ptr)
                if (ok == 0) return@runIo null
                val attrs = data.dwFileAttributes.toInt()
                val isDir = (attrs and FILE_ATTRIBUTE_DIRECTORY) != 0
                val isSymlink = (attrs and FILE_ATTRIBUTE_REPARSE_POINT) != 0
                val isReadOnly = (attrs and FILE_ATTRIBUTE_READONLY) != 0
                val size = if (isDir) {
                    0L
                } else {
                    (data.nFileSizeHigh.toLong() shl 32) or (data.nFileSizeLow.toLong() and 0xFFFF_FFFFL)
                }
                FileStat(
                    path = norm,
                    size = size,
                    isFile = !isDir,
                    isDirectory = isDir,
                    isSymbolicLink = isSymlink,
                    lastModifiedEpochMs = WindowsIoHelper.windowsFileTimeToEpochMs(
                        data.ftLastWriteTime.dwHighDateTime,
                        data.ftLastWriteTime.dwLowDateTime
                    ),
                    createdEpochMs = WindowsIoHelper.windowsFileTimeToEpochMs(
                        data.ftCreationTime.dwHighDateTime,
                        data.ftCreationTime.dwLowDateTime
                    ),
                    isReadable = true,
                    isWritable = !isReadOnly
                )
            }
        }
    }

    override suspend fun readdir(path: String): List<String> {
        if (isDisposed) return emptyList()
        val norm = WindowsIoHelper.normalizeWin32Path(path)
        return WindowsIoHelper.runIo {
            listDirectoryEntriesSync(norm)
        }
    }

    override suspend fun mkdir(path: String, recursive: Boolean): Boolean {
        if (isDisposed) return false
        val norm = WindowsIoHelper.normalizeWin32Path(path)
        return WindowsIoHelper.runIo {
            if (recursive) {
                WindowsIoHelper.ensureDirectoryExists(norm)
                GetFileAttributesW(norm) != INVALID_FILE_ATTRIBUTES
            } else {
                platform.windows.CreateDirectoryW(norm, null) != 0 ||
                    GetFileAttributesW(norm) != INVALID_FILE_ATTRIBUTES
            }
        }
    }

    override suspend fun unlink(path: String): Boolean {
        if (isDisposed) return false
        val norm = WindowsIoHelper.normalizeWin32Path(path)
        return WindowsIoHelper.runIo {
            DeleteFileW(norm) != 0
        }
    }

    override suspend fun rmdir(path: String, recursive: Boolean): Boolean {
        if (isDisposed) return false
        val norm = WindowsIoHelper.normalizeWin32Path(path)
        return WindowsIoHelper.runIo {
            removeDirectorySync(norm, recursive)
        }
    }

    override suspend fun rename(oldPath: String, newPath: String): Boolean {
        if (isDisposed) return false
        val src = WindowsIoHelper.normalizeWin32Path(oldPath)
        val dst = WindowsIoHelper.normalizeWin32Path(newPath)
        return WindowsIoHelper.runIo {
            WindowsIoHelper.ensureParentDirectories(dst)
            MoveFileExW(src, dst, (MOVEFILE_REPLACE_EXISTING or MOVEFILE_COPY_ALLOWED).toUInt()) != 0
        }
    }

    override suspend fun copyFile(srcPath: String, dstPath: String, overwrite: Boolean): Boolean {
        if (isDisposed) return false
        val src = WindowsIoHelper.normalizeWin32Path(srcPath)
        val dst = WindowsIoHelper.normalizeWin32Path(dstPath)
        return WindowsIoHelper.runIo {
            WindowsIoHelper.ensureParentDirectories(dst)
            CopyFileW(src, dst, if (overwrite) 0 else 1) != 0
        }
    }

    override fun dispose() {
        lock.withLock {
            disposedState = true
        }
    }

    private fun readFileSync(normPath: String): ByteArray? {
        val handle = CreateFileW(
            lpFileName = normPath,
            dwDesiredAccess = GENERIC_READ,
            dwShareMode = (FILE_SHARE_READ or FILE_SHARE_WRITE).toUInt(),
            lpSecurityAttributes = null,
            dwCreationDisposition = OPEN_EXISTING.toUInt(),
            dwFlagsAndAttributes = FILE_ATTRIBUTE_NORMAL.toUInt(),
            hTemplateFile = null
        )
        if (handle == null || handle == INVALID_HANDLE_VALUE) return null
        try {
            val fileSize = memScoped {
                val sz = alloc<LARGE_INTEGER>()
                if (GetFileSizeEx(handle, sz.ptr) == 0) return@memScoped -1L
                sz.QuadPart
            }
            if (fileSize < 0L) return null
            if (fileSize == 0L) return ByteArray(0)
            val totalSize = fileSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val out = ByteArray(totalSize)
            var totalRead = 0
            memScoped {
                val readVar = alloc<UIntVar>()
                while (totalRead < totalSize) {
                    readVar.value = 0u
                    val remaining = totalSize - totalRead
                    val ok = out.usePinned { pinned ->
                        ReadFile(
                            hFile = handle,
                            lpBuffer = pinned.addressOf(totalRead),
                            nNumberOfBytesToRead = remaining.toUInt(),
                            lpNumberOfBytesRead = readVar.ptr,
                            lpOverlapped = null
                        )
                    }
                    val count = readVar.value.toInt()
                    if (ok == 0 || count <= 0) break
                    totalRead += count
                }
            }
            return if (totalRead == totalSize) out else out.copyOf(totalRead)
        } finally {
            CloseHandle(handle)
        }
    }

    private fun writeFileSync(normPath: String, bytes: ByteArray, append: Boolean): Boolean {
        WindowsIoHelper.ensureParentDirectories(normPath)
        val handle = CreateFileW(
            lpFileName = normPath,
            dwDesiredAccess = GENERIC_WRITE.toUInt(),
            dwShareMode = (FILE_SHARE_READ or FILE_SHARE_WRITE).toUInt(),
            lpSecurityAttributes = null,
            dwCreationDisposition = (if (append) OPEN_ALWAYS else CREATE_ALWAYS).toUInt(),
            dwFlagsAndAttributes = FILE_ATTRIBUTE_NORMAL.toUInt(),
            hTemplateFile = null
        )
        if (handle == null || handle == INVALID_HANDLE_VALUE) return false
        try {
            if (append) {
                val zero = cValue<LARGE_INTEGER> { QuadPart = 0L }
                SetFilePointerEx(handle, zero, null, FILE_END.toUInt())
            }
            var offset = 0
            memScoped {
                val writtenVar = alloc<UIntVar>()
                while (offset < bytes.size) {
                    writtenVar.value = 0u
                    val remaining = bytes.size - offset
                    val ok = bytes.usePinned { pinned ->
                        WriteFile(
                            hFile = handle,
                            lpBuffer = pinned.addressOf(offset),
                            nNumberOfBytesToWrite = remaining.toUInt(),
                            lpNumberOfBytesWritten = writtenVar.ptr,
                            lpOverlapped = null
                        )
                    }
                    val written = writtenVar.value.toInt()
                    if (ok == 0 || written <= 0) return false
                    offset += written
                }
            }
            FlushFileBuffers(handle)
            return true
        } finally {
            CloseHandle(handle)
        }
    }

    private fun listDirectoryEntriesSync(normDir: String): List<String> {
        val pattern = "${normDir.trimEnd('\\')}\\*"
        return memScoped {
            val findData = alloc<WIN32_FIND_DATAW>()
            val findHandle = FindFirstFileW(pattern, findData.ptr)
            if (findHandle == null || findHandle == INVALID_HANDLE_VALUE) {
                return@memScoped emptyList()
            }
            val names = ArrayList<String>()
            try {
                do {
                    val name = findData.cFileName.toKString()
                    if (name != "." && name != "..") {
                        names.add(name)
                    }
                } while (FindNextFileW(findHandle, findData.ptr) != 0)
            } finally {
                FindClose(findHandle)
            }
            names
        }
    }

    private fun removeDirectorySync(normDir: String, recursive: Boolean): Boolean {
        if (!recursive) {
            return RemoveDirectoryW(normDir) != 0
        }
        val attrs = GetFileAttributesW(normDir)
        if (attrs == INVALID_FILE_ATTRIBUTES) return false
        if ((attrs.toInt() and FILE_ATTRIBUTE_DIRECTORY) == 0) {
            return DeleteFileW(normDir) != 0
        }
        val pattern = "${normDir.trimEnd('\\')}\\*"
        memScoped {
            val findData = alloc<WIN32_FIND_DATAW>()
            val findHandle = FindFirstFileW(pattern, findData.ptr)
            if (findHandle != null && findHandle != INVALID_HANDLE_VALUE) {
                try {
                    do {
                        val entry = findData.cFileName.toKString()
                        if (entry == "." || entry == "..") continue
                        val childPath = "${normDir.trimEnd('\\')}\\$entry"
                        val childIsDir = (findData.dwFileAttributes.toInt() and FILE_ATTRIBUTE_DIRECTORY) != 0
                        if (childIsDir) {
                            removeDirectorySync(childPath, recursive = true)
                        } else {
                            DeleteFileW(childPath)
                        }
                    } while (FindNextFileW(findHandle, findData.ptr) != 0)
                } finally {
                    FindClose(findHandle)
                }
            }
        }
        return RemoveDirectoryW(normDir) != 0
    }
}
