@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.desktop.system

import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.remove

/**
 * Shared desktop helper for resolving the user's OS configuration directory (`<userConfigDir>/<appName>`)
 * and reading/writing per-key binary preference files (`.bson` via [com.nexustools.genericui.util.BinaryJson]
 * on Windows/Linux, `.plist` via [com.nexustools.genericui.util.BinaryPlist] on macOS).
 */
object DesktopConfigDirectoryHelper {

    private const val KEYS_INDEX_FILE = ".preference_keys"

    fun sanitizeAppDirectoryName(appName: String): String {
        val trimmed = appName.trim().ifEmpty { "GenericUI" }
        return trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    fun sanitizeKeyFileName(key: String): String {
        val trimmed = key.trim().ifEmpty { "default" }
        return trimmed.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    fun resolveWindowsAppConfigDir(appName: String): String {
        val cleanApp = sanitizeAppDirectoryName(appName)
        val appData = getenv("APPDATA")?.toKString()?.trim()?.takeIf { it.isNotEmpty() }
        if (appData != null) {
            return "${appData.trimEnd('\\', '/')}\\$cleanApp"
        }
        val userProfile = getenv("USERPROFILE")?.toKString()?.trim()?.takeIf { it.isNotEmpty() }
        if (userProfile != null) {
            return "${userProfile.trimEnd('\\', '/')}\\AppData\\Roaming\\$cleanApp"
        }
        return cleanApp
    }

    fun resolveUnixAppConfigDir(appName: String): String {
        val cleanApp = sanitizeAppDirectoryName(appName)
        val xdg = getenv("XDG_CONFIG_HOME")?.toKString()?.trim()?.takeIf { it.isNotEmpty() }
        if (xdg != null) {
            return "${xdg.trimEnd('/')}/$cleanApp"
        }
        val home = getenv("HOME")?.toKString()?.trim()?.takeIf { it.isNotEmpty() }
        if (home != null) {
            return "${home.trimEnd('/')}/.config/$cleanApp"
        }
        return ".config/$cleanApp"
    }

    fun resolveKeyFilePath(
        configDir: String,
        key: String,
        extensionWithDot: String,
        separator: Char
    ): String {
        val cleanKey = sanitizeKeyFileName(key)
        val baseDir = configDir.trimEnd('\\', '/')
        return "$baseDir$separator$cleanKey$extensionWithDot"
    }

    fun writeBytesToFile(filePath: String, bytes: ByteArray): Boolean {
        val file = fopen(filePath, "wb") ?: return false
        return try {
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned ->
                    fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file)
                }
            }
            true
        } finally {
            fclose(file)
        }
    }

    fun readBytesFromFile(filePath: String): ByteArray? {
        val file = fopen(filePath, "rb") ?: return null
        return try {
            if (fseek(file, 0, SEEK_END) != 0) return null
            val length = ftell(file).toInt()
            if (fseek(file, 0, SEEK_SET) != 0) return null
            if (length < 0) return null
            if (length == 0) return ByteArray(0)
            val buffer = ByteArray(length)
            buffer.usePinned { pinned ->
                fread(pinned.addressOf(0), 1u, length.toULong(), file)
            }
            buffer
        } finally {
            fclose(file)
        }
    }

    fun fileExists(filePath: String): Boolean {
        val file = fopen(filePath, "rb") ?: return false
        fclose(file)
        return true
    }

    fun deleteFile(filePath: String): Boolean =
        remove(filePath) == 0

    fun ensureDirectoryExists(path: String) {
        val clean = path.replace("\"", "")
        if (clean.contains('\\') || (clean.length >= 2 && clean[1] == ':')) {
            platform.posix.system("mkdir \"${clean.replace('/', '\\')}\" >nul 2>nul")
        } else {
            platform.posix.system("mkdir -p \"$clean\" >/dev/null 2>&1")
        }
    }

    fun loadPersistedKeySet(configDir: String, separator: Char): LinkedHashSet<String> {
        val indexFile = "${configDir.trimEnd('\\', '/')}$separator$KEYS_INDEX_FILE"
        val bytes = readBytesFromFile(indexFile) ?: return LinkedHashSet()
        val text = bytes.decodeToString()
        val set = LinkedHashSet<String>()
        for (line in text.lineSequence()) {
            val k = line.trim()
            if (k.isNotEmpty()) {
                set.add(k)
            }
        }
        return set
    }

    fun savePersistedKeySet(configDir: String, separator: Char, keys: Set<String>) {
        val indexFile = "${configDir.trimEnd('\\', '/')}$separator$KEYS_INDEX_FILE"
        val content = keys.joinToString("\n").encodeToByteArray()
        writeBytesToFile(indexFile, content)
    }
}
