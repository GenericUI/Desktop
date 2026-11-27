@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.desktop.system

import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.windows.GetModuleFileNameW
import platform.windows.MAX_PATH
import platform.windows.WCHARVar

internal actual fun resolveNativeExecutableDirectory(): String? {
    var capacity = MAX_PATH.coerceAtLeast(260)
    var exePath: String? = null
    while (capacity <= 32768) {
        var truncated = false
        val candidate = memScoped {
            val buf = allocArray<WCHARVar>(capacity)
            val len = GetModuleFileNameW(null, buf, capacity.toUInt()).toInt()
            when {
                len <= 0 -> null
                len >= capacity - 1 && capacity < 32768 -> {
                    truncated = true
                    null
                }
                else -> buf.toKString()
            }
        }
        if (candidate != null) {
            exePath = candidate
            break
        }
        if (!truncated) return null
        capacity = (capacity * 2).coerceAtMost(32768)
    }
    val resolved = exePath ?: return null
    val sepIdx = maxOf(resolved.lastIndexOf('\\'), resolved.lastIndexOf('/'))
    if (sepIdx < 0) return null
    if (sepIdx == 2 && resolved.length >= 3 && resolved[1] == ':') {
        return resolved.substring(0, 3)
    }
    return if (sepIdx == 0) resolved.substring(0, 1) else resolved.substring(0, sepIdx)
}
