@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.component.dialog.FileTypeFilter
import com.nexustools.genericui.component.dialog.SelectedFile
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.closedir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.stat

internal object LinuxSelectedFileHelper {

    fun buildSelectedFile(fullPath: String, includeHidden: Boolean = false): SelectedFile {
        var isDir = false
        var sizeBytes = 0L
        memScoped {
            val st = alloc<stat>()
            if (stat(fullPath, st.ptr) == 0) {
                isDir = (st.st_mode.toInt() and S_IFMT.toInt()) == S_IFDIR.toInt()
                if (!isDir) {
                    sizeBytes = st.st_size.toLong().coerceAtLeast(0L)
                }
            }
        }
        val normalized = LinuxPosixPaths.trimTrailingSeparators(fullPath)
        val name = normalized.substringAfterLast('/', normalized)
        val ext = name.substringAfterLast('.', "")
        return SelectedFile(
            name = name,
            pathOrUri = fullPath,
            sizeBytes = sizeBytes,
            mimeType = if (isDir) "inode/directory" else FileTypeFilter.inferMimeTypeFromExtension(ext),
            isDirectory = isDir,
            bytesReader = if (!isDir) {
                suspend { LinuxPosixPaths.readAllBytes(fullPath) ?: ByteArray(0) }
            } else {
                null
            },
            bytesWriter = if (!isDir) {
                suspend { data -> LinuxPosixPaths.writeAllBytes(fullPath, data) }
            } else {
                null
            },
            childrenLister = if (isDir) {
                suspend { listDirectoryChildren(fullPath, includeHidden) }
            } else {
                null
            },
            readableOpener = if (!isDir) {
                suspend { LinuxPosixFileReadableNativeImpl(fullPath) }
            } else {
                null
            },
            writableOpener = if (!isDir) {
                suspend { append -> LinuxPosixFileWritableNativeImpl(fullPath, append) }
            } else {
                null
            }
        )
    }

    private fun listDirectoryChildren(dirPath: String, includeHidden: Boolean): List<SelectedFile> {
        val dir = opendir(dirPath) ?: return emptyList()
        val names = ArrayList<String>()
        try {
            while (true) {
                val entry = readdir(dir) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                if (!includeHidden && name.startsWith(".")) continue
                names.add(name)
            }
        } finally {
            closedir(dir)
        }
        names.sortWith(String.CASE_INSENSITIVE_ORDER)
        val base = if (dirPath.endsWith("/")) dirPath else "$dirPath/"
        return names.map { buildSelectedFile("$base$it", includeHidden) }
    }
}
