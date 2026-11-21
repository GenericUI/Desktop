@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.apple.AppleDataBridge
import com.nexustools.genericui.apple.AppleFileReadableNativeImpl
import com.nexustools.genericui.apple.AppleReentrantLock
import com.nexustools.genericui.io.Readable
import com.nexustools.genericui.nativeimpl.io.MemoryReadableNativeImpl
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.resources.ResourceID
import com.nexustools.genericui.resources.ResourceResolverNativeImpl
import com.nexustools.genericui.style.color.RgbaColor
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.posix.RTLD_DEFAULT
import platform.posix.RTLD_LAZY
import platform.posix.RTLD_NOLOAD
import platform.posix.dlopen
import platform.posix.dlsym

private typealias NativeResLookupFn = CFunction<(CPointer<ByteVar>?, CPointer<IntVar>?) -> CPointer<ByteVar>?>

/**
 * macOS (`:desktop:macos`) [ResourceResolverNativeImpl] that looks up embedded Mach-O
 * `__TEXT,__const` resource tables (`genericui_res_lookup_<group>` / `genericui_res_lookup_app`)
 * via `dlsym` inside `lib<group>.dylib` or the host Mach-O binary, falling back to
 * [Resources.activeTable] when running in unlinked unit tests.
 */
class MacosResourceResolverImpl(
    private val bundle: NSBundle = NSBundle.mainBundle
) : ResourceResolverNativeImpl {
    private val lock = AppleReentrantLock()
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
        findBundlePath(resId)?.let { path ->
            return "file://$path"
        }
        return Resources.activeTable.getUrl(resId)
    }

    override fun resolveBytes(resId: ResourceID): ByteArray? {
        if (isDisposed) return null
        val key = resId.nativeResourceKey.trim()
        if (key.isNotEmpty()) {
            loadEmbeddedBytes(resId, key)?.let { return it }
        }
        findBundlePath(resId)?.let { path ->
            val data = NSData.dataWithContentsOfFile(path)
            if (data != null) {
                return AppleDataBridge.nsDataToByteArray(data)
            }
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
        findBundlePath(resId)?.let { path ->
            val data = NSData.dataWithContentsOfFile(path)
            if (data != null) {
                return AppleDataBridge.nsDataToByteArray(data).decodeToString()
            }
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
        findBundlePath(resId)?.let { path ->
            return AppleFileReadableNativeImpl(path)
        }
        val fontPath = Resources.activeTable.getFontPath(resId)
        if (!fontPath.isNullOrBlank() && NSFileManager.defaultManager.fileExistsAtPath(fontPath)) {
            return AppleFileReadableNativeImpl(fontPath)
        }
        val bytes = resolveBytes(resId) ?: return null
        return MemoryReadableNativeImpl(bytes)
    }

    override fun dispose() {
        lock.withLock {
            disposedState = true
        }
    }

    private fun resolveLookupSymbol(resId: ResourceID): CPointer<NativeResLookupFn>? {
        val symName = resId.nativeGroupLookupSymbol
        val defaultPtr = dlsym(RTLD_DEFAULT, symName)
        if (defaultPtr != null) {
            return defaultPtr.reinterpret()
        }
        val groupId = resId.nativeLibraryGroupId.trim()
        if (groupId.isNotEmpty()) {
            val dylibName = "lib$groupId.dylib"
            val candidates = buildList {
                bundle.privateFrameworksPath?.let { add("$it/$dylibName") }
                bundle.bundlePath.let { add("$it/Contents/Frameworks/$dylibName") }
                add("@rpath/$dylibName")
                add(dylibName)
            }
            for (path in candidates) {
                val handle = dlopen(path, RTLD_LAZY or RTLD_NOLOAD) ?: dlopen(path, RTLD_LAZY)
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

    internal fun lookupEmbeddedAppBytes(key: String): ByteArray? {
        val normalizedKey = key.trim()
        if (normalizedKey.isEmpty()) return null
        val fnPtr = dlsym(RTLD_DEFAULT, "genericui_res_lookup_app")
            ?: dlopen(null, RTLD_LAZY)?.let { dlsym(it, "genericui_res_lookup_app") }
            ?: return null
        val lookupFn: CPointer<NativeResLookupFn> = fnPtr.reinterpret()
        return memScoped {
            val outSize = alloc<IntVar>()
            outSize.value = 0
            val dataPtr = lookupFn.invoke(normalizedKey.cstr.ptr, outSize.ptr) ?: return@memScoped null
            val size = outSize.value
            if (size <= 0) ByteArray(0) else dataPtr.readBytes(size)
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

    private fun findBundlePath(resId: ResourceID): String? {
        val candidates = linkedSetOf<String>()
        val canonical = resId.canonicalRelativePath.replace('\\', '/').trimStart('/')
        if (canonical.isNotBlank()) {
            candidates.add(canonical)
            candidates.add("resources/$canonical")
        }
        val rel = resId.relativePath.replace('\\', '/').trimStart('/')
        if (rel.isNotBlank()) {
            candidates.add(rel)
            candidates.add("resources/$rel")
        }
        val typeDir = resId.type.directoryName
        if (typeDir.isNotBlank() && resId.name.isNotBlank()) {
            candidates.add("$typeDir/${resId.name}")
            candidates.add("resources/$typeDir/${resId.name}")
        }
        val bundleRoot = bundle.resourcePath?.trimEnd('/')
        if (!bundleRoot.isNullOrBlank()) {
            val fm = NSFileManager.defaultManager
            for (candidate in candidates) {
                val full = "$bundleRoot/$candidate"
                if (fm.fileExistsAtPath(full)) return full
            }
        }
        return null
    }
}
