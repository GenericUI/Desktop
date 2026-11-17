@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import kotlinx.cinterop.COpaquePointer
import platform.posix.RTLD_GLOBAL
import platform.posix.RTLD_NOW
import platform.posix.SIGPIPE
import platform.posix.SIG_IGN
import platform.posix.dlopen
import platform.posix.dlsym
import platform.posix.signal

internal object LinuxDynamicLibraryLoader {
    private val initLock = LinuxReentrantLock()
    private var sigPipeIgnored = false

    fun ensureNoSigPipe() {
        initLock.withLock {
            if (!sigPipeIgnored) {
                signal(SIGPIPE, SIG_IGN)
                sigPipeIgnored = true
            }
        }
    }

    fun openFirst(vararg candidates: String, global: Boolean = false): COpaquePointer? {
        val flags = if (global) (RTLD_NOW or RTLD_GLOBAL) else RTLD_NOW
        for (name in candidates) {
            val handle = dlopen(name, flags)
            if (handle != null) return handle
        }
        return null
    }

    fun symbol(handle: COpaquePointer?, name: String): COpaquePointer? {
        if (handle == null) return null
        return dlsym(handle, name)
    }
}
