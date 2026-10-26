package com.nexustools.genericui.qt

import com.nexustools.genericui.linux.LinuxXdgCacheDirectoryImpl

/**
 * Linux Qt6 (`:desktop:qt`) implementation of [com.nexustools.genericui.nativeimpl.system.UserDirectoryNativeImpl]
 * for [com.nexustools.genericui.util.CacheDirectory], resolving to `$XDG_CACHE_HOME/<appName>` (or `~/.cache/<appName>`).
 */
class QtCacheDirectoryImpl(
    appName: String = "GenericUI",
    subdirectory: String = ""
) : LinuxXdgCacheDirectoryImpl(
    appName = appName,
    subdirectory = subdirectory
)
