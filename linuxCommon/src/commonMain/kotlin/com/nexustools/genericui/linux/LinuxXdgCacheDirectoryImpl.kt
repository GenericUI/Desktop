package com.nexustools.genericui.linux

import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.desktop.system.DesktopUserDirectoryNativeImpl

/**
 * Shared Linux XDG (`:desktop:linuxCommon`) implementation of [com.nexustools.genericui.nativeimpl.system.UserDirectoryNativeImpl]
 * for [com.nexustools.genericui.util.CacheDirectory], resolving to `$XDG_CACHE_HOME/<appName>` (or `~/.cache/<appName>`).
 */
open class LinuxXdgCacheDirectoryImpl(
    appName: String = "GenericUI",
    subdirectory: String = ""
) : DesktopUserDirectoryNativeImpl(
    appName = appName,
    subdirectory = subdirectory,
    baseDirectoryPath = DesktopConfigDirectoryHelper.resolveUnixAppCacheDir(appName),
    fileSeparator = '/'
)
