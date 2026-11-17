@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.io.Readable
import com.nexustools.genericui.nativeimpl.io.MemoryReadableNativeImpl
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.resources.ResourceID
import com.nexustools.genericui.resources.ResourceResolverNativeImpl
import com.nexustools.genericui.style.color.RgbaColor
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.R_OK
import platform.posix.access
import platform.posix.getenv
import platform.posix.readlink

class LinuxResourceResolverImpl : ResourceResolverNativeImpl {
    private val lock = LinuxReentrantLock()
    private var disposedState = false

    override val isDisposed: Boolean
        get() = lock.withLock { disposedState }

    override fun resolveUrl(resId: ResourceID): String {
        if (isDisposed) return ""
        findDiskPath(resId)?.let { return "file://$it" }
        return Resources.activeTable.getUrl(resId)
    }

    override fun resolveBytes(resId: ResourceID): ByteArray? {
        if (isDisposed) return null
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
        }
    }

    private fun findDiskPath(resId: ResourceID): String? {
        val relCandidates = ArrayList<String>(4)
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
