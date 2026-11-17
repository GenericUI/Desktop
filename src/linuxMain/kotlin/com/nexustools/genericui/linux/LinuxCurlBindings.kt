@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value

internal object LinuxCurlBindings {
    const val CURL_GLOBAL_ALL = 3L

    const val CURLOPT_WRITEDATA = 10001
    const val CURLOPT_URL = 10002
    const val CURLOPT_POSTFIELDS = 10015
    const val CURLOPT_HTTPHEADER = 10023
    const val CURLOPT_HEADERDATA = 10029
    const val CURLOPT_CUSTOMREQUEST = 10036
    const val CURLOPT_NOBODY = 44
    const val CURLOPT_FOLLOWLOCATION = 52
    const val CURLOPT_POSTFIELDSIZE = 60
    const val CURLOPT_SSL_VERIFYPEER = 64
    const val CURLOPT_SSL_VERIFYHOST = 81
    const val CURLOPT_NOSIGNAL = 99
    const val CURLOPT_TIMEOUT_MS = 155
    const val CURLOPT_CONNECTTIMEOUT_MS = 156
    const val CURLOPT_WRITEFUNCTION = 20011
    const val CURLOPT_HEADERFUNCTION = 20079
    const val CURLOPT_POSTFIELDSIZE_LARGE = 30120

    const val CURLINFO_EFFECTIVE_URL = 1048577
    const val CURLINFO_RESPONSE_CODE = 2097154

    private val lock = LinuxReentrantLock()
    private var loaded = false
    private var available = false

    private var fnGlobalInit: CPointer<CFunction<(Long) -> Int>>? = null
    private var fnEasyInit: CPointer<CFunction<() -> COpaquePointer?>>? = null
    private var fnEasySetOptPtr: CPointer<CFunction<(COpaquePointer?, Int, COpaquePointer?) -> Int>>? = null
    private var fnEasySetOptLong: CPointer<CFunction<(COpaquePointer?, Int, Long) -> Int>>? = null
    private var fnEasyPerform: CPointer<CFunction<(COpaquePointer?) -> Int>>? = null
    private var fnEasyGetInfoPtr: CPointer<CFunction<(COpaquePointer?, Int, COpaquePointer?) -> Int>>? = null
    private var fnEasyCleanup: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null
    private var fnEasyStrerror: CPointer<CFunction<(Int) -> CPointer<ByteVar>?>>? = null
    private var fnSlistAppend: CPointer<CFunction<(COpaquePointer?, CPointer<ByteVar>?) -> COpaquePointer?>>? = null
    private var fnSlistFreeAll: CPointer<CFunction<(COpaquePointer?) -> Unit>>? = null

    fun ensureLoaded(): Boolean = lock.withLock {
        if (loaded) return available
        loaded = true
        LinuxDynamicLibraryLoader.ensureNoSigPipe()

        val handle = LinuxDynamicLibraryLoader.openFirst("libcurl.so.4", "libcurl.so", global = true)
        if (handle == null) {
            available = false
            return false
        }

        fnGlobalInit = LinuxDynamicLibraryLoader.symbol(handle, "curl_global_init")?.reinterpret()
        fnEasyInit = LinuxDynamicLibraryLoader.symbol(handle, "curl_easy_init")?.reinterpret()
        val setOptSym = LinuxDynamicLibraryLoader.symbol(handle, "curl_easy_setopt")
        fnEasySetOptPtr = setOptSym?.reinterpret()
        fnEasySetOptLong = setOptSym?.reinterpret()
        fnEasyPerform = LinuxDynamicLibraryLoader.symbol(handle, "curl_easy_perform")?.reinterpret()
        fnEasyGetInfoPtr = LinuxDynamicLibraryLoader.symbol(handle, "curl_easy_getinfo")?.reinterpret()
        fnEasyCleanup = LinuxDynamicLibraryLoader.symbol(handle, "curl_easy_cleanup")?.reinterpret()
        fnEasyStrerror = LinuxDynamicLibraryLoader.symbol(handle, "curl_easy_strerror")?.reinterpret()
        fnSlistAppend = LinuxDynamicLibraryLoader.symbol(handle, "curl_slist_append")?.reinterpret()
        fnSlistFreeAll = LinuxDynamicLibraryLoader.symbol(handle, "curl_slist_free_all")?.reinterpret()

        available = fnGlobalInit != null &&
            fnEasyInit != null &&
            fnEasySetOptPtr != null &&
            fnEasyPerform != null &&
            fnEasyGetInfoPtr != null &&
            fnEasyCleanup != null
        if (available) {
            fnGlobalInit?.invoke(CURL_GLOBAL_ALL)
        }
        available
    }

    fun easyInit(): COpaquePointer? = fnEasyInit?.invoke()
    fun easyCleanup(curl: COpaquePointer?) {
        if (curl != null) fnEasyCleanup?.invoke(curl)
    }

    fun easySetOptString(curl: COpaquePointer, option: Int, value: String): Int = memScoped {
        fnEasySetOptPtr?.invoke(curl, option, value.cstr.ptr) ?: -1
    }

    fun easySetOptPointer(curl: COpaquePointer, option: Int, value: COpaquePointer?): Int {
        return fnEasySetOptPtr?.invoke(curl, option, value) ?: -1
    }

    fun easySetOptLong(curl: COpaquePointer, option: Int, value: Long): Int {
        return fnEasySetOptLong?.invoke(curl, option, value) ?: -1
    }

    fun easyPerform(curl: COpaquePointer): Int = fnEasyPerform?.invoke(curl) ?: -1

    fun easyGetResponseCode(curl: COpaquePointer): Long = memScoped {
        val code = alloc<LongVar>()
        code.value = 0L
        fnEasyGetInfoPtr?.invoke(curl, CURLINFO_RESPONSE_CODE, code.ptr)
        code.value
    }

    fun easyGetEffectiveUrl(curl: COpaquePointer): String? = memScoped {
        val urlPtr = alloc<kotlinx.cinterop.CPointerVar<ByteVar>>()
        urlPtr.value = null
        val rc = fnEasyGetInfoPtr?.invoke(curl, CURLINFO_EFFECTIVE_URL, urlPtr.ptr) ?: -1
        if (rc == 0) urlPtr.value?.toKString() else null
    }

    fun easyStrerror(code: Int): String {
        return fnEasyStrerror?.invoke(code)?.toKString() ?: "CURLcode($code)"
    }

    fun slistAppend(list: COpaquePointer?, headerLine: String): COpaquePointer? = memScoped {
        fnSlistAppend?.invoke(list, headerLine.cstr.ptr)
    }

    fun slistFreeAll(list: COpaquePointer?) {
        if (list != null) fnSlistFreeAll?.invoke(list)
    }
}
