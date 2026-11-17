@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.component.dialog.FileBrowserCallback
import com.nexustools.genericui.component.dialog.FileBrowserRequest
import com.nexustools.genericui.component.dialog.FileTypeFilter
import com.nexustools.genericui.component.dialog.SelectedFile
import com.nexustools.genericui.enums.dialog.FileBrowserMode
import com.nexustools.genericui.io.FileOpenMode
import com.nexustools.genericui.io.FilePermissionStatus
import com.nexustools.genericui.io.FileStat
import com.nexustools.genericui.io.FileSystemAvailability
import com.nexustools.genericui.nativeimpl.dialog.FileBrowserDialogNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileReadableNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileSystemNativeImpl
import com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import platform.posix.EEXIST
import platform.posix.F_OK
import platform.posix.R_OK
import platform.posix.S_IFDIR
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.W_OK
import platform.posix.access
import platform.posix.closedir
import platform.posix.errno
import platform.posix.lstat
import platform.posix.mkdir as posixMkdir
import platform.posix.opendir
import platform.posix.readdir as posixReaddir
import platform.posix.rename as posixRename
import platform.posix.rmdir as posixRmdir
import platform.posix.stat
import platform.posix.unlink as posixUnlink

open class LinuxPosixFileSystemNativeImpl(
    private val dialogProvider: (() -> FileBrowserDialogNativeImpl)? = null
) : FileSystemNativeImpl {
    private val lock = LinuxReentrantLock()
    private var disposedState = false

    override val fileSeparator: Char = '/'

    override val isDisposed: Boolean
        get() = lock.withLock { disposedState }

    override fun isAvailable(): FileSystemAvailability = FileSystemAvailability.Always
    override fun supports(): Boolean = true
    override fun supportsReading(): Boolean = true
    override fun supportsWriting(): Boolean = true
    override fun supportsDirectoryAccess(): Boolean = true

    override fun hasPermission(path: String?, write: Boolean): FilePermissionStatus {
        if (isDisposed) return FilePermissionStatus.UNSUPPORTED
        if (path.isNullOrBlank()) return FilePermissionStatus.GRANTED
        val existsNow = access(path, F_OK) == 0
        return if (!write) {
            if (!existsNow || access(path, R_OK) == 0) FilePermissionStatus.GRANTED else FilePermissionStatus.DENIED
        } else {
            if (existsNow) {
                if (access(path, W_OK) == 0) FilePermissionStatus.GRANTED else FilePermissionStatus.DENIED
            } else {
                val parent = LinuxPosixPaths.parentOf(path)
                if (parent.isEmpty() || access(parent, F_OK) != 0 || access(parent, W_OK) == 0) {
                    FilePermissionStatus.GRANTED
                } else {
                    FilePermissionStatus.DENIED
                }
            }
        }
    }

    override fun resolve(vararg segments: String): String {
        if (segments.isEmpty()) return ""
        val firstNonEmpty = segments.firstOrNull { it.isNotEmpty() } ?: return ""
        val leadingSlash = firstNonEmpty.startsWith('/')
        val parts = ArrayList<String>()
        for (segment in segments) {
            for (part in segment.split('/')) {
                if (part.isNotEmpty() && part != ".") {
                    parts.add(part)
                }
            }
        }
        val joined = parts.joinToString("/")
        return if (leadingSlash) "/$joined" else joined
    }

    override suspend fun createReadStream(path: String): FileReadableNativeImpl? {
        if (isDisposed || !exists(path)) return null
        return LinuxPosixFileReadableNativeImpl(path)
    }

    override suspend fun createWriteStream(path: String, mode: FileOpenMode): FileWritableNativeImpl {
        require(mode != FileOpenMode.READ) { "createWriteStream cannot open in READ-only mode for $path" }
        val append = mode == FileOpenMode.WRITE_APPEND || mode == FileOpenMode.READ_WRITE
        return LinuxPosixFileWritableNativeImpl(path, appendMode = append)
    }

    private suspend fun awaitSelectedPaths(
        mode: FileBrowserMode,
        title: String,
        initialDirectory: String?,
        suggestedFileName: String? = null,
        filters: List<FileTypeFilter> = emptyList()
    ): List<String> {
        val dlg = dialogProvider?.invoke() ?: return emptyList()
        return try {
            suspendCancellableCoroutine { cont ->
                dlg.showFileBrowser(
                    parentWindow = null,
                    request = FileBrowserRequest(
                        mode = mode,
                        title = title,
                        initialDirectory = initialDirectory,
                        defaultFileName = suggestedFileName,
                        filters = filters
                    ),
                    callback = object : FileBrowserCallback {
                        override fun onFilesSelected(files: List<SelectedFile>, selectedFilter: FileTypeFilter?) {
                            if (cont.isActive) cont.resume(files.map { it.pathOrUri })
                        }

                        override fun onCancelled() {
                            if (cont.isActive) cont.resume(emptyList())
                        }
                    }
                )
            }
        } finally {
            dlg.dispose()
        }
    }

    override suspend fun openReadableFromFileDialog(
        title: String,
        initialDirectory: String?,
        filters: List<FileTypeFilter>
    ): Pair<String, FileReadableNativeImpl>? {
        val selected = awaitSelectedPaths(FileBrowserMode.OPEN_SINGLE_FILE, title, initialDirectory, null, filters)
            .firstOrNull() ?: return null
        val stream = createReadStream(selected) ?: return null
        return selected to stream
    }

    override suspend fun openReadablesFromFileDialog(
        title: String,
        initialDirectory: String?,
        filters: List<FileTypeFilter>
    ): List<Pair<String, FileReadableNativeImpl>> {
        val selected = awaitSelectedPaths(FileBrowserMode.OPEN_MULTIPLE_FILES, title, initialDirectory, null, filters)
        val result = ArrayList<Pair<String, FileReadableNativeImpl>>(selected.size)
        for (path in selected) {
            val stream = createReadStream(path)
            if (stream != null) result.add(path to stream)
        }
        return result
    }

    override suspend fun openWritableFromFileDialog(
        title: String,
        initialDirectory: String?,
        suggestedFileName: String,
        filters: List<FileTypeFilter>
    ): Pair<String, FileWritableNativeImpl>? {
        val selected = awaitSelectedPaths(
            FileBrowserMode.SAVE_FILE,
            title,
            initialDirectory,
            suggestedFileName,
            filters
        ).firstOrNull() ?: return null
        return selected to createWriteStream(selected, FileOpenMode.WRITE_TRUNCATE)
    }

    override suspend fun readFile(path: String): ByteArray? {
        if (isDisposed || path.isBlank()) return null
        return withContext(Dispatchers.IO) {
            LinuxPosixPaths.readAllBytes(path)
        }
    }

    override suspend fun writeFile(path: String, bytes: ByteArray): Boolean {
        if (isDisposed || path.isBlank()) return false
        val copy = bytes.copyOf()
        return withContext(Dispatchers.IO) {
            LinuxPosixPaths.writeAllBytes(path, copy, append = false)
        }
    }

    override suspend fun appendFile(path: String, bytes: ByteArray): Boolean {
        if (isDisposed || path.isBlank()) return false
        val copy = bytes.copyOf()
        return withContext(Dispatchers.IO) {
            LinuxPosixPaths.writeAllBytes(path, copy, append = true)
        }
    }

    override suspend fun exists(path: String): Boolean {
        if (isDisposed || path.isBlank()) return false
        return withContext(Dispatchers.IO) {
            memScoped {
                val st = alloc<stat>()
                lstat(path, st.ptr) == 0
            }
        }
    }

    override suspend fun stat(path: String): FileStat? {
        if (isDisposed || path.isBlank()) return null
        return withContext(Dispatchers.IO) {
            memScoped {
                val st = alloc<stat>()
                if (lstat(path, st.ptr) != 0) return@memScoped null
                val kind = st.st_mode.toInt() and S_IFMT.toInt()
                val isDir = kind == S_IFDIR.toInt()
                val isLink = kind == S_IFLNK.toInt()
                val modMs = st.st_mtim.tv_sec.toLong() * 1000L + st.st_mtim.tv_nsec.toLong() / 1_000_000L
                val createMs = st.st_ctim.tv_sec.toLong() * 1000L + st.st_ctim.tv_nsec.toLong() / 1_000_000L
                val clean = LinuxPosixPaths.trimTrailingSeparators(path)
                val name = clean.substringAfterLast('/')

                FileStat(
                    path = path,
                    name = name,
                    size = if (isDir) 0L else st.st_size.toLong(),
                    isFile = !isDir && !isLink,
                    isDirectory = isDir,
                    isSymbolicLink = isLink,
                    lastModifiedEpochMs = modMs,
                    createdEpochMs = createMs,
                    isReadable = access(path, R_OK) == 0,
                    isWritable = access(path, W_OK) == 0
                )
            }
        }
    }

    override suspend fun readdir(path: String): List<String> {
        if (isDisposed || path.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
            readdirSync(path)
        }
    }

    override suspend fun mkdir(path: String, recursive: Boolean): Boolean {
        if (isDisposed || path.isBlank()) return false
        return withContext(Dispatchers.IO) {
            if (recursive) {
                LinuxPosixPaths.ensureDirectoryHierarchy(path)
            } else {
                val clean = LinuxPosixPaths.trimTrailingSeparators(path)
                if (LinuxPosixPaths.isDirectory(clean)) {
                    true
                } else {
                    val rc = posixMkdir(clean, 0b111_101_101.convert())
                    rc == 0 || (errno == EEXIST && LinuxPosixPaths.isDirectory(clean))
                }
            }
        }
    }

    override suspend fun unlink(path: String): Boolean {
        if (isDisposed || path.isBlank()) return false
        return withContext(Dispatchers.IO) {
            if (LinuxPosixPaths.isDirectory(path)) false else posixUnlink(path) == 0
        }
    }

    override suspend fun rmdir(path: String, recursive: Boolean): Boolean {
        if (isDisposed || path.isBlank()) return false
        return withContext(Dispatchers.IO) {
            rmdirSync(path, recursive)
        }
    }

    override suspend fun rename(oldPath: String, newPath: String): Boolean {
        if (isDisposed || oldPath.isBlank() || newPath.isBlank()) return false
        return withContext(Dispatchers.IO) {
            val parent = LinuxPosixPaths.parentOf(newPath)
            if (parent.isNotEmpty()) {
                LinuxPosixPaths.ensureDirectoryHierarchy(parent)
            }
            posixRename(oldPath, newPath) == 0
        }
    }

    override suspend fun copyFile(srcPath: String, dstPath: String, overwrite: Boolean): Boolean {
        if (isDisposed || srcPath.isBlank() || dstPath.isBlank()) return false
        return withContext(Dispatchers.IO) {
            if (!overwrite && access(dstPath, F_OK) == 0) {
                return@withContext false
            }
            val bytes = LinuxPosixPaths.readAllBytes(srcPath) ?: return@withContext false
            LinuxPosixPaths.writeAllBytes(dstPath, bytes, append = false)
        }
    }

    override fun dispose() {
        lock.withLock {
            disposedState = true
        }
    }

    private fun readdirSync(path: String): List<String> {
        val dir = opendir(path) ?: return emptyList()
        return try {
            val entries = ArrayList<String>()
            while (true) {
                val entry = posixReaddir(dir) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name != "." && name != "..") {
                    entries.add(name)
                }
            }
            entries
        } finally {
            closedir(dir)
        }
    }

    private fun rmdirSync(path: String, recursive: Boolean): Boolean {
        val clean = LinuxPosixPaths.trimTrailingSeparators(path)
        if (!LinuxPosixPaths.isDirectory(clean)) return false
        if (recursive) {
            for (childName in readdirSync(clean)) {
                val childPath = if (clean == "/") "/$childName" else "$clean/$childName"
                val isSubDir = memScoped {
                    val st = alloc<stat>()
                    lstat(childPath, st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT.toInt()) == S_IFDIR.toInt()
                }
                val ok = if (isSubDir) rmdirSync(childPath, recursive = true) else (posixUnlink(childPath) == 0)
                if (!ok) return false
            }
        }
        return posixRmdir(clean) == 0
    }
}
