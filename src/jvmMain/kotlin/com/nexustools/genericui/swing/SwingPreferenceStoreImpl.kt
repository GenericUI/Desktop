package com.nexustools.genericui.swing

import com.nexustools.genericui.component.system.PreferenceChangeListener
import com.nexustools.genericui.component.system.PreferenceElement
import com.nexustools.genericui.nativeimpl.system.PreferenceStoreNativeImpl
import com.nexustools.genericui.system.SystemStorageKeyHelper
import com.nexustools.genericui.util.BinaryJson
import com.nexustools.genericui.util.BinaryPlist
import com.nexustools.genericui.util.UserDirectory
import java.io.File
import java.io.FileOutputStream
import java.nio.file.FileSystems
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService

private enum class DesktopOsProfile {
    WINDOWS,
    MACOS,
    LINUX
}

/**
 * Java Swing / JVM desktop implementation of [PreferenceStoreNativeImpl] with full 1:1 native
 * compatibility across Windows (`%APPDATA%\<appName>\<encodedKey>.bson`), macOS
 * (`$XDG_CONFIG_HOME` / `~/.config/<appName>/<encodedKey>.plist` `bplist00` + legacy bundle migration),
 * and Linux / other Unix-like OSes (`$XDG_CONFIG_HOME` / `~/.config/<appName>/<encodedKey>.bson`),
 * discovering keys purely via filesystem directory listing and bijective stem decoding
 * ([UserDirectory.encodePreferenceKeyToFileName] / [UserDirectory.decodePreferenceKeyFromFileName]),
 * complete with `.old` atomic backup/recovery and Java NIO [WatchService] change notifications.
 */
class SwingPreferenceStoreImpl(
    override val appName: String = "GenericUI",
    private val customDirectory: File? = null,
    osNameOverride: String? = null,
    private val envProvider: (String) -> String? = { System.getenv(it) },
    private val userHomeProvider: () -> String = { System.getProperty("user.home", ".") }
) : PreferenceStoreNativeImpl {

    private val lock = Any()
    private val osProfile: DesktopOsProfile = detectOsProfile(osNameOverride)

    val isWindowsHost: Boolean = osProfile == DesktopOsProfile.WINDOWS
    val isMacOsHost: Boolean = osProfile == DesktopOsProfile.MACOS
    val useBinaryPlist: Boolean = osProfile == DesktopOsProfile.MACOS
    val fileExtension: String = if (useBinaryPlist) ".plist" else ".bson"

    val storageDirectory: File = (customDirectory ?: resolveHostAppConfigDirectory(
        appName = appName,
        osNameOverride = osNameOverride,
        envProvider = envProvider,
        userHomeProvider = userHomeProvider
    )).apply {
        mkdirs()
    }

    private val removedCanonicalKeys = HashSet<String>()
    private val listeners = ArrayList<PreferenceChangeListener>()
    private var watchService: WatchService? = null
    private var watchKey: WatchKey? = null

    override val storageLocationDescription: String
        get() = "${storageDirectory.absolutePath}${File.separator}*$fileExtension (${if (useBinaryPlist) "BinaryPlist" else "BinaryJson"})"

    override val usesNativeChangeListener: Boolean = true

    @Volatile
    override var isDisposed: Boolean = false
        private set

    init {
        synchronized(lock) {
            if (isMacOsHost) {
                migrateLegacyMacosBundlesUnlocked()
            }
        }
        initWatchService(storageDirectory)
    }

    fun fileForKey(key: String): File =
        File(storageDirectory, "${sanitizeKeyForHost(key)}$fileExtension")

    fun backupFileForKey(key: String): File =
        File(storageDirectory, "${sanitizeKeyForHost(key)}$fileExtension.old")

    override fun writeElement(key: String, element: PreferenceElement) {
        if (isDisposed) return
        if (element.isNull) {
            remove(key)
            return
        }
        val encoded = encodeForHost(element)
        synchronized(lock) {
            if (isDisposed) return
            storageDirectory.mkdirs()
            removedCanonicalKeys.remove(key)
            val primaryFile = fileForKey(key)
            val backupFile = backupFileForKey(key)

            if (primaryFile.exists()) {
                val primaryBytes = readBytesOrNull(primaryFile)
                val primaryValid = primaryBytes != null && decodeForHostOrNull(primaryBytes) != null
                val backupBytes = if (backupFile.exists()) readBytesOrNull(backupFile) else null
                val backupValid = backupBytes != null && decodeForHostOrNull(backupBytes) != null

                if (!primaryValid && backupValid) {
                    primaryFile.delete()
                } else {
                    if (backupFile.exists()) {
                        backupFile.delete()
                    }
                    moveOrCopyDelete(primaryFile, backupFile)
                }
            }

            if (writeFileSynced(primaryFile, encoded)) {
                val writtenBytes = readBytesOrNull(primaryFile)
                val verified = primaryFile.isFile &&
                    primaryFile.length() == encoded.size.toLong() &&
                    writtenBytes != null &&
                    writtenBytes.size == encoded.size &&
                    decodeForHostOrNull(writtenBytes) != null
                if (verified && backupFile.exists()) {
                    backupFile.delete()
                }
            }
        }
        notifyListeners(key, element.toPlainValue())
    }

    override fun readElement(key: String): PreferenceElement? {
        synchronized(lock) {
            if (isDisposed) return null
            return withMatchedPairUnlocked(
                key = key,
                onFound = { primary, backup, isBinaryPlist ->
                    readAndRecoverPairUnlocked(primary, backup, isBinaryPlist)
                },
                onMissing = { null }
            )
        }
    }

    override fun contains(key: String): Boolean {
        synchronized(lock) {
            if (isDisposed) return false
            return withMatchedPairUnlocked(
                key = key,
                onFound = { primary, backup, isBinaryPlist ->
                    hasValidPairUnlocked(primary, backup, isBinaryPlist)
                },
                onMissing = { false }
            )
        }
    }

    override fun keys(): Set<String> {
        synchronized(lock) {
            if (isDisposed) return emptySet()
            return computeKeysUnlocked()
        }
    }

    override fun remove(key: String) {
        var existed = false
        synchronized(lock) {
            if (isDisposed) return
            val canonicalFiles = collectFilesForStem(sanitizeKeyForHost(key))
            var foundCanonical = false
            for (candidate in canonicalFiles) {
                if (candidate.exists()) {
                    foundCanonical = true
                    existed = true
                    candidate.delete()
                }
            }
            if (foundCanonical) {
                removedCanonicalKeys.add(key)
            } else if (key !in removedCanonicalKeys) {
                for (legacyStem in legacyUnencodedStems(key, sanitizeKeyForHost(key))) {
                    for (candidate in collectFilesForStem(legacyStem)) {
                        if (candidate.exists()) {
                            existed = true
                            candidate.delete()
                        }
                    }
                }
            }
        }
        if (existed) {
            notifyListeners(key, null)
        }
    }

    override fun clear() {
        var hadAny = false
        synchronized(lock) {
            if (isDisposed) return
            val children = storageDirectory.listFiles()
            if (children != null) {
                for (child in children) {
                    if (!child.isFile) continue
                    val name = child.name
                    if (name.startsWith(".")) continue
                    if (name.endsWith(".bson") ||
                        name.endsWith(".bson.old") ||
                        name.endsWith(".plist") ||
                        name.endsWith(".plist.old")
                    ) {
                        if (child.delete()) {
                            hadAny = true
                        }
                    }
                }
            }
            File(storageDirectory, LEGACY_KEYS_INDEX_FILE).takeIf { it.exists() }?.delete()
            File(storageDirectory, LEGACY_DIR_MANIFEST_FILE).takeIf { it.exists() }?.delete()
            removedCanonicalKeys.clear()
        }
        if (hadAny) {
            notifyListeners(null, null)
        }
    }

    override fun flush(): Boolean {
        if (isDisposed) return false
        pollWatchServiceEvents()
        return !isDisposed
    }

    override fun addChangeListener(listener: PreferenceChangeListener) {
        synchronized(lock) {
            if (!isDisposed && listener !in listeners) {
                listeners.add(listener)
            }
        }
    }

    override fun removeChangeListener(listener: PreferenceChangeListener) {
        synchronized(lock) {
            listeners.remove(listener)
        }
    }

    override fun dispose() {
        val wk: WatchKey?
        val ws: WatchService?
        synchronized(lock) {
            if (isDisposed) return
            isDisposed = true
            removedCanonicalKeys.clear()
            listeners.clear()
            wk = watchKey
            ws = watchService
            watchKey = null
            watchService = null
        }
        runCatching { wk?.cancel() }
        runCatching { ws?.close() }
    }

    /**
     * Polls any pending OS directory [WatchService] events and dispatches them to registered listeners,
     * ignoring `.old` backup files and hidden dotfiles.
     */
    fun pollWatchServiceEvents() {
        val wk = synchronized(lock) {
            if (isDisposed) return
            watchKey
        } ?: return
        val events = runCatching { wk.pollEvents() }.getOrNull() ?: return
        for (ev in events) {
            val changedName = ev.context()?.toString() ?: continue
            if (changedName.startsWith(".")) continue
            if (changedName.endsWith(".old")) continue
            val ext = when {
                changedName.endsWith(fileExtension) && changedName.length > fileExtension.length -> fileExtension
                changedName.endsWith(".bson") && changedName.length > ".bson".length -> ".bson"
                changedName.endsWith(".plist") && changedName.length > ".plist".length -> ".plist"
                else -> continue
            }
            val stem = changedName.removeSuffix(ext)
            val key = UserDirectory.decodePreferenceKeyFromFileName(stem)
            if (ev.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                val stillExists = synchronized(lock) {
                    if (isDisposed) return
                    val encodedStem = sanitizeKeyForHost(key)
                    if (encodedStem == stem) {
                        val hasCanonical = hasValidCanonicalPairUnlocked(key)
                        if (!hasCanonical) {
                            removedCanonicalKeys.add(key)
                        }
                        hasCanonical
                    } else {
                        hasValidPairUnlocked(
                            File(storageDirectory, "$stem$ext"),
                            File(storageDirectory, "$stem$ext.old"),
                            ext == ".plist"
                        )
                    }
                }
                if (!stillExists) {
                    notifyListeners(key, null)
                }
            } else {
                synchronized(lock) {
                    if (!isDisposed) {
                        removedCanonicalKeys.remove(key)
                    }
                }
                val updated = readElement(key)?.toPlainValue()
                notifyListeners(key, updated)
            }
        }
        runCatching { wk.reset() }
    }

    private fun initWatchService(directory: File) {
        runCatching {
            val ws = FileSystems.getDefault().newWatchService()
            val wk = directory.toPath().register(
                ws,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE
            )
            synchronized(lock) {
                if (isDisposed) {
                    wk.cancel()
                    ws.close()
                } else {
                    watchService = ws
                    watchKey = wk
                }
            }
        }
    }

    private fun sanitizeKeyForHost(key: String): String =
        UserDirectory.encodePreferenceKeyToFileName(key)

    private fun legacyUnencodedStems(key: String, encodedStem: String): List<String> {
        if (key.isBlank()) return emptyList()
        val sysSanitized = SystemStorageKeyHelper.sanitizeKey(key, fallback = "default")
        val userSanitized = UserDirectory.sanitizeFileName(key.trim().ifEmpty { "default" })
        val stems = LinkedHashSet<String>(2)
        for (stem in listOf(sysSanitized, userSanitized)) {
            if (stem == encodedStem) continue
            if (stem.startsWith(".") || stem == "~") continue
            if (UserDirectory.decodePreferenceKeyFromFileName(stem) != stem) continue
            if (stem.substringBefore('.').uppercase() in WINDOWS_RESERVED_STEMS) continue
            stems.add(stem)
        }
        return stems.toList()
    }

    private fun collectFilesForStem(stem: String): List<File> = listOf(
        File(storageDirectory, "$stem.bson"),
        File(storageDirectory, "$stem.bson.old"),
        File(storageDirectory, "$stem.plist"),
        File(storageDirectory, "$stem.plist.old")
    )

    private inline fun <T> withMatchedPairUnlocked(
        key: String,
        onFound: (primary: File, backup: File, isBinaryPlist: Boolean) -> T,
        onMissing: () -> T
    ): T {
        val encodedStem = sanitizeKeyForHost(key)
        val altExtension = if (useBinaryPlist) ".bson" else ".plist"
        val altIsBinaryPlist = !useBinaryPlist

        val primaryFile = File(storageDirectory, "$encodedStem$fileExtension")
        val backupFile = File(storageDirectory, "$encodedStem$fileExtension.old")
        if (primaryFile.exists() || backupFile.exists()) {
            return onFound(primaryFile, backupFile, useBinaryPlist)
        }

        val altPrimaryFile = File(storageDirectory, "$encodedStem$altExtension")
        val altBackupFile = File(storageDirectory, "$encodedStem$altExtension.old")
        if (altPrimaryFile.exists() || altBackupFile.exists()) {
            return onFound(altPrimaryFile, altBackupFile, altIsBinaryPlist)
        }

        if (key !in removedCanonicalKeys) {
            for (legacyStem in legacyUnencodedStems(key, encodedStem)) {
                val legacyHostPrimary = File(storageDirectory, "$legacyStem$fileExtension")
                val legacyHostBackup = File(storageDirectory, "$legacyStem$fileExtension.old")
                if (legacyHostPrimary.exists() || legacyHostBackup.exists()) {
                    return onFound(legacyHostPrimary, legacyHostBackup, useBinaryPlist)
                }

                val legacyAltPrimary = File(storageDirectory, "$legacyStem$altExtension")
                val legacyAltBackup = File(storageDirectory, "$legacyStem$altExtension.old")
                if (legacyAltPrimary.exists() || legacyAltBackup.exists()) {
                    return onFound(legacyAltPrimary, legacyAltBackup, altIsBinaryPlist)
                }
            }
        }

        return onMissing()
    }

    private fun hasValidCanonicalPairUnlocked(key: String): Boolean {
        val encodedStem = sanitizeKeyForHost(key)
        val altExtension = if (useBinaryPlist) ".bson" else ".plist"
        return hasValidPairUnlocked(
            File(storageDirectory, "$encodedStem$fileExtension"),
            File(storageDirectory, "$encodedStem$fileExtension.old"),
            useBinaryPlist
        ) || hasValidPairUnlocked(
            File(storageDirectory, "$encodedStem$altExtension"),
            File(storageDirectory, "$encodedStem$altExtension.old"),
            !useBinaryPlist
        )
    }

    private fun encodeForHost(element: PreferenceElement): ByteArray =
        if (useBinaryPlist) BinaryPlist.encodeElement(element) else BinaryJson.encodeElement(element)

    private fun decodeForHostOrNull(bytes: ByteArray): PreferenceElement? =
        decodeForExtensionOrNull(bytes, useBinaryPlist)

    private fun decodeForExtensionOrNull(bytes: ByteArray, binaryPlist: Boolean): PreferenceElement? =
        if (binaryPlist) BinaryPlist.decodeElementOrNull(bytes) else BinaryJson.decodeElementOrNull(bytes)

    private fun readBytesOrNull(file: File): ByteArray? =
        if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null

    private fun writeFileSynced(file: File, bytes: ByteArray): Boolean = runCatching {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
        true
    }.getOrDefault(false)

    private fun moveOrCopyDelete(source: File, target: File): Boolean {
        if (!source.exists()) return false
        if (target.exists()) {
            target.delete()
        }
        if (source.renameTo(target)) {
            return true
        }
        val bytes = readBytesOrNull(source) ?: return false
        if (!writeFileSynced(target, bytes)) {
            return false
        }
        source.delete()
        return true
    }

    private fun readAndRecoverPairUnlocked(
        primaryFile: File,
        backupFile: File,
        isBinaryPlist: Boolean
    ): PreferenceElement? {
        if (primaryFile.isFile) {
            val primaryBytes = readBytesOrNull(primaryFile)
            val decodedPrimary = primaryBytes?.let { decodeForExtensionOrNull(it, isBinaryPlist) }
            if (decodedPrimary != null) {
                if (backupFile.exists()) {
                    backupFile.delete()
                }
                return decodedPrimary
            }
        }
        if (backupFile.isFile) {
            val backupBytes = readBytesOrNull(backupFile)
            val decodedBackup = backupBytes?.let { decodeForExtensionOrNull(it, isBinaryPlist) }
            if (decodedBackup != null) {
                if (writeFileSynced(primaryFile, backupBytes)) {
                    val restoredBytes = readBytesOrNull(primaryFile)
                    if (primaryFile.isFile &&
                        primaryFile.length() == backupBytes.size.toLong() &&
                        restoredBytes != null &&
                        restoredBytes.size == backupBytes.size &&
                        decodeForExtensionOrNull(restoredBytes, isBinaryPlist) != null
                    ) {
                        backupFile.delete()
                    }
                }
                return decodedBackup
            }
        }
        return null
    }

    private fun hasValidPairUnlocked(
        primaryFile: File,
        backupFile: File,
        isBinaryPlist: Boolean
    ): Boolean {
        if (primaryFile.isFile) {
            val primaryBytes = readBytesOrNull(primaryFile)
            if (primaryBytes != null && decodeForExtensionOrNull(primaryBytes, isBinaryPlist) != null) {
                return true
            }
        }
        if (backupFile.isFile) {
            val backupBytes = readBytesOrNull(backupFile)
            if (backupBytes != null && decodeForExtensionOrNull(backupBytes, isBinaryPlist) != null) {
                return true
            }
        }
        return false
    }

    private fun scanDiskStemsUnlocked(): LinkedHashSet<String> {
        val children = storageDirectory.listFiles() ?: return LinkedHashSet()
        val hostPrimaryValid = LinkedHashSet<String>()
        val hostBackupValid = LinkedHashSet<String>()
        val hostFilePresent = LinkedHashSet<String>()
        val altPrimaryValid = LinkedHashSet<String>()
        val altBackupValid = LinkedHashSet<String>()
        val altFilePresent = LinkedHashSet<String>()

        val hostExt = fileExtension
        val hostOldExt = "$fileExtension.old"
        val altExt = if (useBinaryPlist) ".bson" else ".plist"
        val altOldExt = "$altExt.old"

        for (child in children) {
            if (!child.isFile) continue
            val name = child.name
            if (name.startsWith(".")) continue
            when {
                name.endsWith(hostOldExt) && name.length > hostOldExt.length -> {
                    val stem = name.removeSuffix(hostOldExt)
                    hostFilePresent.add(stem)
                    val bytes = readBytesOrNull(child)
                    if (bytes != null && decodeForExtensionOrNull(bytes, useBinaryPlist) != null) {
                        hostBackupValid.add(stem)
                    }
                }
                name.endsWith(hostExt) && name.length > hostExt.length -> {
                    val stem = name.removeSuffix(hostExt)
                    hostFilePresent.add(stem)
                    val bytes = readBytesOrNull(child)
                    if (bytes != null && decodeForExtensionOrNull(bytes, useBinaryPlist) != null) {
                        hostPrimaryValid.add(stem)
                    }
                }
                name.endsWith(altOldExt) && name.length > altOldExt.length -> {
                    val stem = name.removeSuffix(altOldExt)
                    altFilePresent.add(stem)
                    val bytes = readBytesOrNull(child)
                    if (bytes != null && decodeForExtensionOrNull(bytes, !useBinaryPlist) != null) {
                        altBackupValid.add(stem)
                    }
                }
                name.endsWith(altExt) && name.length > altExt.length -> {
                    val stem = name.removeSuffix(altExt)
                    altFilePresent.add(stem)
                    val bytes = readBytesOrNull(child)
                    if (bytes != null && decodeForExtensionOrNull(bytes, !useBinaryPlist) != null) {
                        altPrimaryValid.add(stem)
                    }
                }
            }
        }

        val stems = LinkedHashSet<String>()
        val allCandidates = LinkedHashSet<String>().apply {
            addAll(hostFilePresent)
            addAll(altFilePresent)
        }
        for (stem in allCandidates) {
            if (stem in hostFilePresent) {
                if (stem in hostPrimaryValid || stem in hostBackupValid) {
                    stems.add(stem)
                }
            } else if (stem in altPrimaryValid || stem in altBackupValid) {
                stems.add(stem)
            }
        }
        return stems
    }

    private fun computeKeysUnlocked(): Set<String> {
        val diskStems = scanDiskStemsUnlocked()
        val out = LinkedHashSet<String>(diskStems.size)
        for (stem in diskStems) {
            out.add(UserDirectory.decodePreferenceKeyFromFileName(stem))
        }
        return out.sorted().toCollection(LinkedHashSet())
    }

    private fun migrateLegacyMacosBundlesUnlocked() {
        val existingFiles = storageDirectory.listFiles()?.map { it.name }.orEmpty()
        val hasOtherPerKeyPlists = existingFiles.any {
            it.endsWith(".plist") && it != "preferences.plist" && it != "settings.plist"
        }
        if (hasOtherPerKeyPlists) return

        val legacyNames = listOf("preferences.plist", "settings.plist")
        for (legacyName in legacyNames) {
            val legacyFile = File(storageDirectory, legacyName)
            if (!legacyFile.isFile) continue
            val bytes = readBytesOrNull(legacyFile) ?: continue
            val decoded = BinaryPlist.decodeElementOrNull(bytes) ?: continue
            if (!(decoded.isMap || decoded.isObject) || decoded.mapEntries.isEmpty()) continue
            val legacyStem = legacyName.removeSuffix(".plist")
            val containsSelfKey = decoded.mapEntries.containsKey(legacyStem)
            if (containsSelfKey && decoded.mapEntries.size == 1) continue

            val extracted = LinkedHashMap<String, PreferenceElement>(decoded.mapEntries)
            for ((k, v) in extracted) {
                if (v.isNull || k == legacyStem) continue
                val targetFile = fileForKey(k)
                if (!targetFile.exists()) {
                    writeFileSynced(targetFile, BinaryPlist.encodeElement(v))
                }
            }
            legacyFile.delete()
            val selfValue = extracted[legacyStem]
            if (selfValue != null && !selfValue.isNull) {
                val selfTarget = fileForKey(legacyStem)
                if (!selfTarget.exists()) {
                    writeFileSynced(selfTarget, BinaryPlist.encodeElement(selfValue))
                }
            }
        }
    }

    private fun notifyListeners(key: String?, newValue: Any?) {
        val snapshot = synchronized(lock) {
            if (isDisposed) return
            listeners.toList()
        }
        for (listener in snapshot) {
            listener.onPreferenceChanged(appName, key, newValue)
        }
    }

    companion object {
        private const val LEGACY_KEYS_INDEX_FILE = ".preference_keys"
        private const val LEGACY_DIR_MANIFEST_FILE = ".user_dir_manifest"
        private val WINDOWS_RESERVED_STEMS = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT0", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
        )

        private fun detectOsProfile(osNameOverride: String?): DesktopOsProfile {
            val osName = (osNameOverride ?: System.getProperty("os.name", "")).lowercase()
            return when {
                osName.contains("mac") || osName.contains("darwin") -> DesktopOsProfile.MACOS
                osName.contains("win") -> DesktopOsProfile.WINDOWS
                else -> DesktopOsProfile.LINUX
            }
        }

        fun sanitizeName(raw: String): String =
            SystemStorageKeyHelper.sanitizeAppDirectoryName(raw)

        fun sanitizeKey(raw: String): String =
            UserDirectory.encodePreferenceKeyToFileName(raw)

        fun resolveHostAppConfigDirectory(
            appName: String,
            osNameOverride: String? = null,
            envProvider: (String) -> String? = { System.getenv(it) },
            userHomeProvider: () -> String = { System.getProperty("user.home", ".") }
        ): File {
            return when (detectOsProfile(osNameOverride)) {
                DesktopOsProfile.WINDOWS -> {
                    val safeApp = SystemStorageKeyHelper.sanitizeAppDirectoryName(appName)
                    val appData = envProvider("APPDATA")?.trim()?.takeIf { it.isNotEmpty() }
                    val baseDir = if (appData != null) {
                        File(appData)
                    } else {
                        val userHomeOrProfile = envProvider("USERPROFILE")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: userHomeProvider().trim().ifEmpty { "." }
                        File(userHomeOrProfile, "AppData/Roaming")
                    }
                    File(baseDir, safeApp)
                }
                DesktopOsProfile.MACOS -> {
                    val safeApp = UserDirectory.sanitizeFileName(appName)
                    val xdg = envProvider("XDG_CONFIG_HOME")?.trim()?.takeIf { it.isNotEmpty() }
                    val baseDir = if (xdg != null) {
                        File(xdg)
                    } else {
                        val home = envProvider("HOME")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: userHomeProvider().trim().ifEmpty { "." }
                        File(home, ".config")
                    }
                    File(baseDir, safeApp)
                }
                DesktopOsProfile.LINUX -> {
                    val safeApp = SystemStorageKeyHelper.sanitizeAppDirectoryName(appName)
                    val xdg = envProvider("XDG_CONFIG_HOME")?.trim()?.takeIf { it.isNotEmpty() }
                    val baseDir = if (xdg != null) {
                        File(xdg)
                    } else {
                        val home = envProvider("HOME")?.trim()?.takeIf { it.isNotEmpty() }
                            ?: userHomeProvider().trim().ifEmpty { "." }
                        File(home, ".config")
                    }
                    File(baseDir, safeApp)
                }
            }
        }
    }
}
