package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.system.UserDirectoryNativeImpl
import java.io.File

/**
 * Java Swing / JVM desktop (`:desktop:swing`) implementation of [UserDirectoryNativeImpl]
 * backed by `java.io.File` for both [com.nexustools.genericui.util.ConfigDirectory] (`isCache = false`)
 * and [com.nexustools.genericui.util.CacheDirectory] (`isCache = true`).
 */
class SwingUserDirectoryNativeImpl(
    override val appName: String = "GenericUI",
    override val subdirectory: String = "",
    val isCache: Boolean = false,
    customDirectory: File? = null
) : UserDirectoryNativeImpl {

    override val fileSeparator: Char = File.separatorChar

    override val usesFileSystemBasePath: Boolean = true

    val directory: File = resolveTargetDirectory(appName, subdirectory, isCache, customDirectory).apply {
        mkdirs()
    }

    override val directoryPath: String
        get() = directory.absolutePath

    override var isDisposed: Boolean = false
        private set

    override suspend fun readAsync(fileName: String): com.nexustools.genericui.io.ByteBuffer? {
        if (com.nexustools.genericui.core.GenericUI.activeFactory is SwingNativeFactory) {
            return com.nexustools.genericui.io.FileSystem.read(resolveFilePath(fileName))
        }
        return readBytes(fileName)?.let { com.nexustools.genericui.io.ByteBuffer.wrap(it) }
    }

    override suspend fun writeAsync(
        fileName: String,
        buffer: com.nexustools.genericui.io.ByteBuffer,
        append: Boolean
    ): Boolean {
        ensureDirectoryExists()
        if (com.nexustools.genericui.core.GenericUI.activeFactory is SwingNativeFactory) {
            return com.nexustools.genericui.io.FileSystem.write(resolveFilePath(fileName), buffer, append)
        }
        val file = File(directory, sanitizeName(fileName))
        return runCatching {
            if (append && file.exists()) {
                file.appendBytes(buffer.toByteArray())
            } else {
                file.writeBytes(buffer.toByteArray())
            }
            true
        }.getOrDefault(false)
    }

    override suspend fun openReadableAsync(fileName: String): com.nexustools.genericui.nativeimpl.io.FileReadableNativeImpl? {
        if (com.nexustools.genericui.core.GenericUI.activeFactory is SwingNativeFactory) {
            return com.nexustools.genericui.io.FileSystem.openReadable(resolveFilePath(fileName))
                as? com.nexustools.genericui.nativeimpl.io.FileReadableNativeImpl
        }
        val file = File(directory, sanitizeName(fileName))
        if (!file.isFile || !file.exists()) return null
        return SwingFileReadableNativeImpl(file)
    }

    override suspend fun openWritableAsync(
        fileName: String,
        append: Boolean
    ): com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl {
        ensureDirectoryExists()
        if (com.nexustools.genericui.core.GenericUI.activeFactory is SwingNativeFactory) {
            val stream = com.nexustools.genericui.io.FileSystem.openWritable(resolveFilePath(fileName), append)
                as? com.nexustools.genericui.nativeimpl.io.FileWritableNativeImpl
            if (stream != null) return stream
        }
        val file = File(directory, sanitizeName(fileName))
        return SwingFileWritableNativeImpl(file, append = append)
    }

    override fun ensureDirectoryExists(): Boolean {
        if (isDisposed) return false
        return directory.exists() || directory.mkdirs()
    }

    override fun resolveFilePath(fileName: String): String =
        File(directory, sanitizeName(fileName)).absolutePath

    override fun writeBytes(fileName: String, bytes: ByteArray): Boolean {
        if (isDisposed) return false
        return runCatching {
            ensureDirectoryExists()
            File(directory, sanitizeName(fileName)).writeBytes(bytes)
            true
        }.getOrDefault(false)
    }

    override fun readBytes(fileName: String): ByteArray? {
        if (isDisposed) return null
        val file = File(directory, sanitizeName(fileName))
        if (!file.isFile || !file.exists()) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    override fun exists(fileName: String): Boolean {
        if (isDisposed) return false
        val file = File(directory, sanitizeName(fileName))
        return file.isFile && file.exists()
    }

    override fun fileSize(fileName: String): Long {
        if (isDisposed) return 0L
        val file = File(directory, sanitizeName(fileName))
        return if (file.isFile && file.exists()) file.length() else 0L
    }

    override fun delete(fileName: String): Boolean {
        if (isDisposed) return false
        val file = File(directory, sanitizeName(fileName))
        return file.exists() && file.delete()
    }

    override fun listFiles(): List<String> {
        if (isDisposed) return emptyList()
        val children = directory.listFiles() ?: return emptyList()
        return children
            .filter { it.isFile && !it.name.startsWith(".") }
            .map { it.name }
            .sorted()
    }

    override fun clearAll(): Int {
        if (isDisposed) return 0
        val children = directory.listFiles() ?: return 0
        var deleted = 0
        for (child in children) {
            if (child.isFile && !child.name.startsWith(".")) {
                if (child.delete()) {
                    deleted++
                }
            }
        }
        return deleted
    }

    override fun totalByteSize(): Long {
        if (isDisposed) return 0L
        val children = directory.listFiles() ?: return 0L
        var total = 0L
        for (child in children) {
            if (child.isFile && !child.name.startsWith(".")) {
                total += child.length()
            }
        }
        return total
    }

    override fun dispose() {
        isDisposed = true
    }

    companion object {
        fun sanitizeName(raw: String): String {
            val trimmed = raw.trim().trim('/', '\\').ifEmpty { "GenericUI" }
            return trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        }

        fun resolveTargetDirectory(
            appName: String,
            subdirectory: String,
            isCache: Boolean,
            customDirectory: File?
        ): File {
            val base = customDirectory ?: if (isCache) {
                resolveHostAppCacheDirectory(appName)
            } else {
                resolveHostAppConfigDirectory(appName)
            }
            val cleanSub = subdirectory.trim().trim('/', '\\')
            return if (cleanSub.isEmpty()) base else File(base, cleanSub)
        }

        fun resolveHostAppConfigDirectory(appName: String): File {
            val safeApp = sanitizeName(appName)
            val osName = System.getProperty("os.name", "").lowercase()
            val userHome = System.getProperty("user.home", ".")
            if (osName.contains("win")) {
                val appData = System.getenv("APPDATA")?.trim()?.takeIf { it.isNotEmpty() }
                val baseDir = if (appData != null) File(appData) else File(userHome, "AppData/Roaming")
                return File(baseDir, safeApp)
            }
            val xdg = System.getenv("XDG_CONFIG_HOME")?.trim()?.takeIf { it.isNotEmpty() }
            val baseDir = if (xdg != null) File(xdg) else File(userHome, ".config")
            return File(baseDir, safeApp)
        }

        fun resolveHostAppCacheDirectory(appName: String): File {
            val safeApp = sanitizeName(appName)
            val osName = System.getProperty("os.name", "").lowercase()
            val userHome = System.getProperty("user.home", ".")
            if (osName.contains("win")) {
                val localAppData = System.getenv("LOCALAPPDATA")?.trim()?.takeIf { it.isNotEmpty() }
                val baseDir = if (localAppData != null) File(localAppData) else File(userHome, "AppData/Local")
                return File(File(baseDir, safeApp), "Cache")
            }
            if (osName.contains("mac") || osName.contains("darwin")) {
                return File(File(userHome, "Library/Caches"), safeApp)
            }
            val xdg = System.getenv("XDG_CACHE_HOME")?.trim()?.takeIf { it.isNotEmpty() }
            val baseDir = if (xdg != null) File(xdg) else File(userHome, ".cache")
            return File(baseDir, safeApp)
        }
    }
}
