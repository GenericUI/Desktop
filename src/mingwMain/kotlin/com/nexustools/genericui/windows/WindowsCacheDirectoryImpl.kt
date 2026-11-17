package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.desktop.system.DesktopUserDirectoryNativeImpl
import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.FileSystem

/**
 * Windows (`:desktop:windows`) implementation of [com.nexustools.genericui.nativeimpl.system.UserDirectoryNativeImpl]
 * for [com.nexustools.genericui.util.CacheDirectory], resolving to `%LOCALAPPDATA%\<appName>\Cache[\<subdirectory>]`.
 */
class WindowsCacheDirectoryImpl(
    appName: String = "GenericUI",
    subdirectory: String = ""
) : DesktopUserDirectoryNativeImpl(
    appName = appName,
    subdirectory = subdirectory,
    baseDirectoryPath = DesktopConfigDirectoryHelper.resolveWindowsAppCacheDir(appName),
    fileSeparator = '\\'
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

    override suspend fun openReadableAsync(fileName: String): WindowsFileReadableNativeImpl? {
        if (isDisposed || !exists(fileName)) return null
        return WindowsFileReadableNativeImpl(filePath = resolveFilePath(fileName))
    }

    override suspend fun openWritableAsync(fileName: String, append: Boolean): WindowsFileWritableNativeImpl {
        ensureDirectoryExists()
        return WindowsFileWritableNativeImpl(
            filePath = resolveFilePath(fileName),
            appendMode = append
        )
    }
}
