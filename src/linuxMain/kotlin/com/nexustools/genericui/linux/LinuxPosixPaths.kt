@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.close as posixClose
import platform.posix.errno
import platform.posix.fsync
import platform.posix.mkdir
import platform.posix.open as posixOpen
import platform.posix.read as posixRead
import platform.posix.stat
import platform.posix.write as posixWrite

internal object LinuxPosixPaths {
    fun parentOf(path: String): String {
        val normalized = trimTrailingSeparators(path)
        val slash = normalized.lastIndexOf('/')
        if (slash < 0) return ""
        if (slash == 0) return "/"
        return normalized.substring(0, slash)
    }

    fun isDirectory(path: String): Boolean = memScoped {
        val st = alloc<stat>()
        if (stat(path, st.ptr) != 0) return@memScoped false
        (st.st_mode.toInt() and S_IFMT.toInt()) == S_IFDIR.toInt()
    }

    fun ensureDirectoryHierarchy(dirPath: String): Boolean {
        val trimmed = trimTrailingSeparators(dirPath)
        if (trimmed.isEmpty() || trimmed == "/") return true
        if (isDirectory(trimmed)) return true

        val parts = trimmed.split('/').filter { it.isNotEmpty() }
        val builder = StringBuilder()
        if (trimmed.startsWith("/")) {
            builder.append('/')
        }
        for (i in parts.indices) {
            if (i > 0) builder.append('/')
            builder.append(parts[i])
            val current = builder.toString()
            if (isDirectory(current)) continue
            val rc = mkdir(current, 0b111_101_101.convert())
            if (rc != 0 && errno != EEXIST && !isDirectory(current)) {
                return false
            }
        }
        return isDirectory(trimmed)
    }

    fun trimTrailingSeparators(path: String): String {
        if (path == "/") return path
        var end = path.length
        while (end > 1 && path[end - 1] == '/') {
            end--
        }
        return if (end == path.length) path else path.substring(0, end)
    }

    fun readAllBytes(path: String): ByteArray? {
        if (path.isBlank()) return null
        val fd = posixOpen(path, O_RDONLY)
        if (fd < 0) return null
        return try {
            val chunks = ArrayList<ByteArray>()
            var total = 0
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = buf.usePinned { pinned ->
                    posixRead(fd, pinned.addressOf(0), buf.size.convert()).toInt()
                }
                if (n > 0) {
                    chunks.add(if (n == buf.size) buf.copyOf() else buf.copyOf(n))
                    total += n
                } else if (n == 0) {
                    break
                } else {
                    if (errno == EINTR) continue
                    return null
                }
            }
            if (total == 0) return ByteArray(0)
            val result = ByteArray(total)
            var off = 0
            for (c in chunks) {
                c.copyInto(result, off)
                off += c.size
            }
            result
        } finally {
            posixClose(fd)
        }
    }

    fun writeAllBytes(path: String, bytes: ByteArray, append: Boolean = false): Boolean {
        if (path.isBlank()) return false
        val parent = parentOf(path)
        if (parent.isNotEmpty() && !ensureDirectoryHierarchy(parent)) {
            return false
        }
        val flags = O_WRONLY or O_CREAT or (if (append) O_APPEND else O_TRUNC)
        val fd = posixOpen(path, flags, 0b110_100_100)
        if (fd < 0) return false
        return try {
            var off = 0
            while (off < bytes.size) {
                val n = bytes.usePinned { pinned ->
                    posixWrite(fd, pinned.addressOf(off), (bytes.size - off).convert()).toInt()
                }
                if (n > 0) {
                    off += n
                } else if (errno == EINTR) {
                    continue
                } else {
                    return false
                }
            }
            fsync(fd)
            true
        } finally {
            posixClose(fd)
        }
    }
}
