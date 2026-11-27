@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.desktop.system

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.readlink

internal actual fun resolveNativeExecutableDirectory(): String? = memScoped {
    val buf = allocArray<ByteVar>(4096)
    val n = readlink("/proc/self/exe", buf, 4095.convert()).toInt()
    if (n <= 0) return@memScoped null
    buf[n] = 0
    val exePath = buf.toKString()
    val sepIdx = exePath.lastIndexOf('/')
    if (sepIdx < 0) return@memScoped null
    if (sepIdx == 0) "/" else exePath.substring(0, sepIdx)
}
