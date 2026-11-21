@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.io.Readable
import com.nexustools.genericui.nativeimpl.io.MemoryReadableNativeImpl
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.resources.ResourceID
import com.nexustools.genericui.resources.ResourceResolverNativeImpl
import com.nexustools.genericui.style.color.RgbaColor
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.F_OK
import platform.posix.RTLD_LAZY
import platform.posix.R_OK
import platform.posix.access
import platform.posix.dlopen
import platform.posix.dlsym
import platform.posix.getenv
import platform.posix.readlink

private typealias LinuxResLookupFn = CFunction<(CPointer<ByteVar>?, CPointer<IntVar>?) -> CPointer<ByteVar>?>

/**
 * Linux (`:desktop:linux`) [ResourceResolverNativeImpl] that looks up embedded ELF `.rodata`
 * resource tables (`genericui_res_lookup_<group>` / `genericui_res_lookup_app`) via `dlsym`
 * inside `lib<group>.so` or the running ELF binary, falling back to filesystem paths and
 * [Resources.activeTable] in unlinked unit tests.
 */
class LinuxResourceResolverImpl : ResourceResolverNativeImpl {
    private val lock = LinuxReentrantLock()
    private val lookupCache = HashMap<String, CPointer<LinuxResLookupFn>?>()
    private var disposedState = false

    override val isDisposed: Boolean
        get() = lock.withLock { disposedState }

    override fun resolveUrl(resId: ResourceID): String {
        if (isDisposed) return ""
        val key = resId.nativeResourceKey.trim()
        val groupLabel = resId.nativeLibraryGroupId.trim().ifEmpty { "app" }
        if (key.isNotEmpty() && hasEmbeddedSymbolEntry(resId, key)) {
            return "$groupLabel:$key"
        }
        findDiskPath(resId)?.let { return "file://$it" }
        return Resources.activeTable.getUrl(resId)
    }

    override fun resolveBytes(resId: ResourceID): ByteArray? {
        if (isDisposed) return null
        val key = resId.nativeResourceKey.trim()
        if (key.isNotEmpty()) {
            loadEmbeddedBytes(resId, key)?.let { return it }
        }
        findDiskPath(resId)?.let { path ->
            LinuxPosixPaths.readAllBytes(path)?.let { return it }
        }
        return Resources.activeTable.getBytes(resId)
            ?: Resources.activeTable.getRawText(resId)?.encodeToByteArray()
            ?: Resources.activeTable.getDrawable(resId)?.svgSource?.encodeToByteArray()
    }

    override fun resolveString(resId: ResourceID): String? {
        if (isDisposed) return null
        Resources.activeTable.getStringOrNull(resId)?.let { return it }
        Resources.activeTable.getRawText(resId)?.let { return it }
        val key = resId.nativeResourceKey.trim()
        if (key.isNotEmpty()) {
            loadEmbeddedBytes(resId, key)?.let { return it.decodeToString() }
        }
        findDiskPath(resId)?.let { path ->
            LinuxPosixPaths.readAllBytes(path)?.let { return it.decodeToString() }
        }
        return null
    }

    override fun resolveColor(resId: ResourceID): RgbaColor? {
        if (isDisposed) return null
        return Resources.activeTable.getColorOrNull(resId)
    }

    override fun resolveDimenPx(resId: ResourceID): Float? {
        if (isDisposed) return null
        return Resources.activeTable.getDimenOrNull(resId)
    }

    override suspend fun openResourceStream(resId: ResourceID): Readable? = openReadable(resId)

    override suspend fun openReadable(resId: ResourceID): Readable? {
        if (isDisposed) return null
        val key = resId.nativeResourceKey.trim()
        if (key.isNotEmpty()) {
            loadEmbeddedBytes(resId, key)?.let { return MemoryReadableNativeImpl(it) }
        }
        findDiskPath(resId)?.let { path ->
            return LinuxPosixFileReadableNativeImpl(path)
        }
        val fontPath = Resources.activeTable.getFontPath(resId)
        if (!fontPath.isNullOrBlank() && access(fontPath, F_OK) == 0 && !LinuxPosixPaths.isDirectory(fontPath)) {
            return LinuxPosixFileReadableNativeImpl(fontPath)
        }
        val bytes = resolveBytes(resId) ?: return null
        return MemoryReadableNativeImpl(bytes)
    }

    override fun dispose() {
        lock.withLock {
            disposedState = true
            lookupCache.clear()
        }
    }

    private fun resolveLookupSymbol(resId: ResourceID): CPointer<LinuxResLookupFn>? {
        val symName = resId.nativeGroupLookupSymbol
        lock.withLock {
            if (lookupCache.containsKey(symName)) {
                return lookupCache[symName]
            }
        }
        val resolved = findLookupSymbolUncached(resId.nativeLibraryGroupId.trim(), symName)
        lock.withLock {
            if (!disposedState) {
                lookupCache[symName] = resolved
            }
        }
        return resolved
    }

    private fun findLookupSymbolUncached(
        groupId: String,
        symName: String
    ): CPointer<LinuxResLookupFn>? {
        val mainHandle = dlopen(null, RTLD_LAZY)
        if (mainHandle != null) {
            dlsym(mainHandle, symName)?.let { return it.reinterpret() }
        }
        if (groupId.isNotEmpty()) {
            val soName = "lib$groupId.so"
            val exeDir = executableDirectory()
            val candidates = buildList {
                if (!exeDir.isNullOrBlank()) {
                    add("$exeDir/$soName")
                    add("$exeDir/lib/$soName")
                }
                add(soName)
            }
            for (path in candidates) {
                val handle = dlopen(path, RTLD_LAZY)
                if (handle != null) {
                    val fnPtr = dlsym(handle, symName)
                    if (fnPtr != null) {
                        return fnPtr.reinterpret()
                    }
                }
            }
        }
        return null
    }

    private fun hasEmbeddedSymbolEntry(resId: ResourceID, key: String): Boolean {
        val lookupFn = resolveLookupSymbol(resId) ?: return false
        return memScoped {
            val outSize = alloc<IntVar>()
            outSize.value = 0
            lookupFn.invoke(key.cstr.ptr, outSize.ptr) != null && outSize.value >= 0
        }
    }

    private fun loadEmbeddedBytes(resId: ResourceID, key: String): ByteArray? {
        val lookupFn = resolveLookupSymbol(resId) ?: return null
        return memScoped {
            val outSize = alloc<IntVar>()
            outSize.value = 0
            val dataPtr = lookupFn.invoke(key.cstr.ptr, outSize.ptr) ?: return@memScoped null
            val size = outSize.value
            if (size <= 0) ByteArray(0) else dataPtr.readBytes(size)
        }
    }

    private fun findDiskPath(resId: ResourceID): String? {
        val relCandidates = LinkedHashSet<String>()
        val canonical = resId.canonicalRelativePath.replace('\\', '/').trimStart('/')
        if (canonical.isNotBlank()) {
            relCandidates.add(canonical)
            relCandidates.add("resources/$canonical")
        }
        if (resId.relativePath.isNotBlank()) {
            val cleanRel = resId.relativePath.trimStart('/')
            relCandidates.add(cleanRel)
            relCandidates.add("resources/$cleanRel")
        }
        val typeDir = resId.type.directoryName
        if (typeDir.isNotBlank() && resId.name.isNotBlank()) {
            relCandidates.add("$typeDir/${resId.name}")
            relCandidates.add("resources/$typeDir/${resId.name}")
        }

        val searchRoots = buildSearchRoots()
        for (root in searchRoots) {
            for (rel in relCandidates) {
                val full = "$root/$rel"
                if (access(full, R_OK) == 0 && !LinuxPosixPaths.isDirectory(full)) {
                    return full
                }
            }
        }
        for (rel in relCandidates) {
            if (access(rel, R_OK) == 0 && !LinuxPosixPaths.isDirectory(rel)) {
                return rel
            }
        }
        return null
    }

    private fun buildSearchRoots(): List<String> {
        val roots = LinkedHashSet<String>()
        executableDirectory()?.let { exeDir ->
            roots.add(exeDir)
            roots.add("$exeDir/resources")
            roots.add("$exeDir/../share/genericui")
        }
        roots.add("src/commonMain/resources")
        roots.add("resources")
        roots.add("../src/commonMain/resources")
        roots.add("../../src/commonMain/resources")

        getenv("XDG_DATA_HOME")?.toKString()?.trim()?.takeIf { it.isNotEmpty() }?.let { dataHome ->
            roots.add("${dataHome.trimEnd('/')}/genericui")
        }
        getenv("XDG_DATA_DIRS")?.toKString()?.split(':')?.forEach { entry ->
            val trimmed = entry.trim().trimEnd('/')
            if (trimmed.isNotEmpty()) {
                roots.add("$trimmed/genericui")
            }
        }
        return roots.toList()
    }

    private fun executableDirectory(): String? = memScoped {
        val buf = allocArray<ByteVar>(4096)
        val n = readlink("/proc/self/exe", buf, 4095.convert()).toInt()
        if (n <= 0) return@memScoped null
        buf[n] = 0
        val exePath = buf.toKString()
        val slash = exePath.lastIndexOf('/')
        if (slash > 0) exePath.substring(0, slash) else null
    }
}
