package com.nexustools.genericui.desktop.system

import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo

internal actual fun resolveNativeExecutableDirectory(): String? {
    val exePath = NSBundle.mainBundle.executablePath?.takeIf { it.isNotBlank() }
        ?: (NSProcessInfo.processInfo.arguments.firstOrNull() as? String)
            ?.takeIf { it.isNotBlank() && '/' in it }
        ?: return null
    val sepIdx = exePath.lastIndexOf('/')
    if (sepIdx < 0) return null
    return if (sepIdx == 0) "/" else exePath.substring(0, sepIdx)
}
