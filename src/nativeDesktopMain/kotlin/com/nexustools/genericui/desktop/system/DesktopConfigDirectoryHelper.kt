@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.desktop.system

import com.nexustools.genericui.system.SystemStorageKeyHelper
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.fwrite
import platform.posix.getenv
import platform.posix.opendir
import platform.posix.remove

/**
 * Shared desktop helper for resolving the user's OS configuration directory (`<userConfigDir>/<appName>`)
 * and OS cache directory (`<userCacheDir>/<appName>`), reading/writing files via POSIX `fopen`/`fread`/`fwrite`,
 * and reading bundled resource files or downloading remote HTTP assets.
 */
object DesktopConfigDirectoryHelper {

    private const val KEYS_INDEX_FILE = ".preference_keys"
    private val ensuredDirectories = HashSet<String>()

    fun sanitizeAppDirectoryName(appName: String): String =
        SystemStorageKeyHelper.sanitizeAppDirectoryName(appName)

    fun sanitizeKeyFileName(key: String): String =
        SystemStorageKeyHelper.sanitizeKey(key, fallback = "default")

    fun appendSubdirectory(baseDir: String, subdirectory: String, separator: Char): String =
        SystemStorageKeyHelper.joinPath(baseDir, subdirectory, separator)

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

    fun resolveWindowsAppCacheDir(appName: String): String {
        val cleanApp = sanitizeAppDirectoryName(appName)
        val localAppData = getenv("LOCALAPPDATA")?.toKString()?.trim()?.takeIf { it.isNotEmpty() }
        if (localAppData != null) {
            return "${localAppData.trimEnd('\\', '/')}\\$cleanApp\\Cache"
        }
        val userProfile = getenv("USERPROFILE")?.toKString()?.trim()?.takeIf { it.isNotEmpty() }
        if (userProfile != null) {
            return "${userProfile.trimEnd('\\', '/')}\\AppData\\Local\\$cleanApp\\Cache"
        }
        return "$cleanApp\\Cache"
    }

    fun resolveMacosAppConfigDir(appName: String): String {
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

    fun resolveMacosAppCacheDir(appName: String): String {
        val cleanApp = sanitizeAppDirectoryName(appName)
        val home = getenv("HOME")?.toKString()?.trim()?.takeIf { it.isNotEmpty() }
        if (home != null) {
            return "${home.trimEnd('/')}/Library/Caches/$cleanApp"
        }
        return "Library/Caches/$cleanApp"
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

    fun resolveUnixAppCacheDir(appName: String): String {
        val cleanApp = sanitizeAppDirectoryName(appName)
        val xdg = getenv("XDG_CACHE_HOME")?.toKString()?.trim()?.takeIf { it.isNotEmpty() }
        if (xdg != null) {
            return "${xdg.trimEnd('/')}/$cleanApp"
        }
        val home = getenv("HOME")?.toKString()?.trim()?.takeIf { it.isNotEmpty() }
        if (home != null) {
            return "${home.trimEnd('/')}/.cache/$cleanApp"
        }
        return ".cache/$cleanApp"
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

    fun fileSize(filePath: String): Long {
        val file = fopen(filePath, "rb") ?: return 0L
        return try {
            if (fseek(file, 0, SEEK_END) != 0) return 0L
            val length = ftell(file).toLong()
            if (length < 0L) 0L else length
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

    fun ensureDirectoryExists(path: String): Boolean {
        val clean = path.replace("\"", "").trim()
        if (clean.isEmpty()) return false
        if (clean in ensuredDirectories) return true
        val existingDir = opendir(clean)
        if (existingDir != null) {
            closedir(existingDir)
            ensuredDirectories.add(clean)
            return true
        }
        if (clean.contains('\\') || (clean.length >= 2 && clean[1] == ':')) {
            platform.posix.system("mkdir \"${clean.replace('/', '\\')}\" >nul 2>nul")
        } else {
            platform.posix.system("mkdir -p \"$clean\" >/dev/null 2>&1")
        }
        ensuredDirectories.add(clean)
        return true
    }

    fun loadPersistedKeySet(configDir: String, separator: Char): LinkedHashSet<String> {
        val indexFile = SystemStorageKeyHelper.joinPath(configDir, KEYS_INDEX_FILE, separator)
        val bytes = readBytesFromFile(indexFile) ?: return LinkedHashSet()
        return SystemStorageKeyHelper.decodeKeySet(bytes)
    }

    fun savePersistedKeySet(configDir: String, separator: Char, keys: Set<String>) {
        val indexFile = SystemStorageKeyHelper.joinPath(configDir, KEYS_INDEX_FILE, separator)
        val content = SystemStorageKeyHelper.encodeKeySet(keys)
        writeBytesToFile(indexFile, content)
    }

    /**
     * Resolves a local file path or relative resource path (such as `"logo.png?2"` or `"res://drawable/logo.png"`)
     * by checking the direct path and standard KMP resource directories (`src/commonMain/resources/<path>`, etc.).
     */
    fun readResourceOrLocalFile(uriOrPath: String): ByteArray? {
        val raw = uriOrPath.trim()
        if (raw.isEmpty()) return null
        val withoutScheme = when {
            raw.startsWith("file://") -> raw.removePrefix("file://").trimStart('/')
            raw.startsWith("res://drawable/") -> raw.removePrefix("res://drawable/")
            raw.startsWith("res://") -> raw.removePrefix("res://")
            else -> raw
        }
        val cleanPath = withoutScheme.substringBefore('?').substringBefore('#').trim()
        if (cleanPath.isEmpty()) return null

        val winClean = cleanPath.replace('/', '\\')
        val candidates = listOf(
            cleanPath,
            winClean,
            "src/commonMain/resources/$cleanPath",
            "src\\commonMain\\resources\\$winClean",
            "resources/$cleanPath",
            "resources\\$winClean",
            "../src/commonMain/resources/$cleanPath",
            "..\\src\\commonMain\\resources\\$winClean",
            "../../src/commonMain/resources/$cleanPath",
            "..\\..\\src\\commonMain\\resources\\$winClean",
            "../../../src/commonMain/resources/$cleanPath",
            "..\\..\\..\\src\\commonMain\\resources\\$winClean",
            "../../../../src/commonMain/resources/$cleanPath",
            "..\\..\\..\\..\\src\\commonMain\\resources\\$winClean"
        )
        for (candidate in candidates) {
            val bytes = readBytesFromFile(candidate)
            if (bytes != null && bytes.isNotEmpty()) {
                return bytes
            }
        }
        return null
    }

    /**
     * Downloads remote `http://` or `https://` bytes using the OS `curl` utility into [tempFilePath]
     * and returns the downloaded bytes.
     */
    fun fetchHttpUrlBytes(url: String, tempFilePath: String): ByteArray? {
        val safeUrl = url.replace("\"", "")
        val safeTemp = tempFilePath.replace("\"", "")
        val isWindows = safeTemp.contains('\\') || (safeTemp.length >= 2 && safeTemp[1] == ':')
        val cmd = if (isWindows) {
            "curl -sL -f --max-time 15 -o \"$safeTemp\" \"$safeUrl\" >nul 2>nul"
        } else {
            "curl -sL -f --max-time 15 -o \"$safeTemp\" \"$safeUrl\" >/dev/null 2>&1"
        }
        val exitCode = platform.posix.system(cmd)
        if (exitCode != 0) {
            deleteFile(safeTemp)
            return null
        }
        val bytes = readBytesFromFile(safeTemp)
        deleteFile(safeTemp)
        return bytes?.takeIf { it.isNotEmpty() }
    }
}
