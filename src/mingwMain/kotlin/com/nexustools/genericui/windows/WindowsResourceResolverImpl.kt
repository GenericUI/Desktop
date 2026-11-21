@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.io.ByteBuffer
import com.nexustools.genericui.io.Readable
import com.nexustools.genericui.io.ReadableByteBuffer
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.resources.DefaultResourceResolverNativeImpl
import com.nexustools.genericui.resources.ResourceID
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.wcstr
import platform.windows.FindResourceW
import platform.windows.GetModuleHandleW
import platform.windows.HMODULE
import platform.windows.LoadResource
import platform.windows.LockResource
import platform.windows.SizeofResource

/**
 * Windows (`:desktop:windows`) implementation of [com.nexustools.genericui.resources.ResourceResolverNativeImpl]
 * that resolves embedded Win32 `RT_RCDATA` resources by prefix-free [ResourceID.nativeResourceKey]
 * from either the owning module DLL (`GetModuleHandleW("<nativeLibraryGroupId>.dll")`) or the host
 * executable (`GetModuleHandleW(null)`), falling back to legacy numeric [ResourceID.win32ResourceId]
 * and [DefaultResourceResolverNativeImpl].
 */
class WindowsResourceResolverImpl : DefaultResourceResolverNativeImpl() {

    override fun resolveBytes(resId: ResourceID): ByteArray? {
        loadEmbeddedResourceBytes(resId)?.let { return it }
        return super.resolveBytes(resId)
    }

    override fun resolveUrl(resId: ResourceID): String {
        val resolvedModuleName = resId.nativeLibraryGroupId.trim().ifEmpty { "app" }
        val key = resId.nativeResourceKey.trim()
        if (key.isNotEmpty() && findStringResourceHandle(resId, key) != null) {
            return "$resolvedModuleName:$key"
        }
        if (findNumericResourceHandle(resId) != null) {
            return "rc://${resId.win32ResourceId}/${resId.name}"
        }
        return super.resolveUrl(resId)
    }

    override suspend fun openResourceStream(resId: ResourceID): Readable? {
        val rawBytes = resolveBytes(resId)
        if (rawBytes != null) {
            return ReadableByteBuffer(ByteBuffer.wrap(rawBytes))
        }
        val rawText = Resources.activeTable.getRawText(resId)
            ?: Resources.activeTable.getStringOrNull(resId)
            ?: Resources.activeTable.getDrawable(resId)?.svgSource
        if (rawText != null) {
            return ReadableByteBuffer(ByteBuffer.wrap(rawText.encodeToByteArray()))
        }
        return null
    }

    private fun candidateModules(resId: ResourceID): List<HMODULE?> {
        val groupId = resId.nativeLibraryGroupId.trim()
        if (groupId.isEmpty()) {
            return listOf(GetModuleHandleW(null))
        }
        val dllHandle = memScoped {
            GetModuleHandleW("$groupId.dll")
        }
        val exeHandle = GetModuleHandleW(null)
        return if (dllHandle != null && dllHandle != exeHandle) {
            listOf(dllHandle, exeHandle)
        } else {
            listOf(exeHandle)
        }
    }

    private fun findStringResourceHandle(resId: ResourceID, key: String): Pair<HMODULE?, platform.windows.HRSRC>? = try {
        val typePtr = WIN32_RT_RCDATA.toCPointer<UShortVar>()
        val candidateKeys = if (key != key.uppercase()) listOf(key, key.uppercase()) else listOf(key)
        for (hModule in candidateModules(resId)) {
            for (k in candidateKeys) {
                val found = memScoped {
                    FindResourceW(hModule, k.wcstr.ptr, typePtr)
                }
                if (found != null) return hModule to found
            }
        }
        null
    } catch (_: Throwable) {
        null
    }

    private fun findNumericResourceHandle(resId: ResourceID): Pair<HMODULE?, platform.windows.HRSRC>? = try {
        val namePtr = (resId.win32ResourceId.toLong() and 0xFFFFL).toCPointer<UShortVar>()
        val typePtr = WIN32_RT_RCDATA.toCPointer<UShortVar>()
        for (hModule in candidateModules(resId)) {
            val found = FindResourceW(hModule, namePtr, typePtr)
            if (found != null) return hModule to found
        }
        null
    } catch (_: Throwable) {
        null
    }

    private fun loadEmbeddedResourceBytes(resId: ResourceID): ByteArray? = try {
        val key = resId.nativeResourceKey.trim()
        val match = (if (key.isNotEmpty()) findStringResourceHandle(resId, key) else null)
            ?: findNumericResourceHandle(resId)
            ?: return null
        val (hModule, hResInfo) = match
        val size = SizeofResource(hModule, hResInfo).toInt()
        if (size <= 0) return null
        val hResData = LoadResource(hModule, hResInfo) ?: return null
        val lockedPtr = LockResource(hResData)?.reinterpret<ByteVar>() ?: return null
        lockedPtr.readBytes(size)
    } catch (_: Throwable) {
        null
    }

    companion object {
        private const val WIN32_RT_RCDATA: Long = 10L
    }
}
