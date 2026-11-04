package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.desktop.system.DesktopUserDirectoryNativeImpl
import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.FileSystem

/**
 * macOS (`:desktop:macos`) implementation of [com.nexustools.genericui.nativeimpl.system.UserDirectoryNativeImpl]
 * for [com.nexustools.genericui.util.CacheDirectory], resolving to `~/Library/Caches/<appName>[/<subdirectory>]`.
 */
class MacosCacheDirectoryImpl(
    appName: String = "GenericUI",
    subdirectory: String = ""
) : DesktopUserDirectoryNativeImpl(
    appName = appName,
    subdirectory = subdirectory,
    baseDirectoryPath = DesktopConfigDirectoryHelper.resolveMacosAppCacheDir(appName),
    fileSeparator = '/'
) {
    override val usesFileSystemBasePath: Boolean = true

    override suspend fun readAsync(fileName: String): ByteBuffer? =
        FileSystem.read(resolveFilePath(fileName)) ?: readBytes(fileName)?.let { ByteBuffer.wrap(it) }

    override suspend fun writeAsync(fileName: String, buffer: ByteBuffer, append: Boolean): Boolean {
        ensureDirectoryExists()
        val incoming = buffer.toByteArray()
        val merged = if (append) {
            val existing = readBytes(fileName)
            if (existing == null || existing.isEmpty()) incoming else existing + incoming
        } else {
            incoming
        }
        val wroteLocal = writeBytes(fileName, merged)
        val wroteFs = FileSystem.write(resolveFilePath(fileName), ByteBuffer.wrap(merged), append = false)
        return wroteLocal || wroteFs
    }

    override suspend fun openReadableAsync(fileName: String): MacosFileReadableNativeImpl? {
        val bytes = readAsync(fileName)?.toByteArray() ?: return null
        return MacosFileReadableNativeImpl(initialBytes = bytes, filePath = resolveFilePath(fileName))
    }

    override suspend fun openWritableAsync(fileName: String, append: Boolean): MacosFileWritableNativeImpl {
        ensureDirectoryExists()
        val initial = if (append) readAsync(fileName)?.toByteArray() ?: ByteArray(0) else ByteArray(0)
        return MacosFileWritableNativeImpl(
            initialBytes = initial,
            filePath = resolveFilePath(fileName),
            appendMode = append,
            onCommit = { committed ->
                writeBytes(fileName, committed)
                FileSystem.write(resolveFilePath(fileName), ByteBuffer.wrap(committed), append = false)
            }
        )
    }
}
