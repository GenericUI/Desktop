@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.resources.DefaultResourceResolverNativeImpl
import com.nexustools.genericui.resources.ResourceID
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toCPointer
import platform.windows.FindResourceW
import platform.windows.GetModuleHandleW
import platform.windows.LoadResource
import platform.windows.LockResource
import platform.windows.SizeofResource

/**
 * Windows (`:desktop:windows`) implementation of [com.nexustools.genericui.resources.ResourceResolverNativeImpl]
 * that loads compiled `RCDATA` resources directly from the running Win32 `.exe` module (`GetModuleHandleW(null)`)
 * via `FindResourceW` / `LoadResource` / `LockResource` / `SizeofResource`, falling back to
 * [DefaultResourceResolverNativeImpl] when running in unlinked unit tests.
 */
class WindowsResourceResolverImpl : DefaultResourceResolverNativeImpl() {

    override fun resolveBytes(resId: ResourceID): ByteArray? {
        loadFromExeModule(resId.win32ResourceId)?.let { return it }
        return super.resolveBytes(resId)
    }

    override fun resolveUrl(resId: ResourceID): String {
        if (hasEmbeddedWin32Resource(resId.win32ResourceId)) {
            return "rc://${resId.win32ResourceId}/${resId.name}"
        }
        return super.resolveUrl(resId)
    }

    private fun hasEmbeddedWin32Resource(win32Id: Int): Boolean = try {
        val hModule = GetModuleHandleW(null)
        val namePtr = (win32Id.toLong() and 0xFFFFL).toCPointer<UShortVar>()
        val typePtr = WIN32_RT_RCDATA.toCPointer<UShortVar>()
        FindResourceW(hModule, namePtr, typePtr) != null
    } catch (_: Throwable) {
        false
    }

    private fun loadFromExeModule(win32Id: Int): ByteArray? = try {
        val hModule = GetModuleHandleW(null)
        val namePtr = (win32Id.toLong() and 0xFFFFL).toCPointer<UShortVar>()
        val typePtr = WIN32_RT_RCDATA.toCPointer<UShortVar>()
        val hResInfo = FindResourceW(hModule, namePtr, typePtr) ?: return null
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
