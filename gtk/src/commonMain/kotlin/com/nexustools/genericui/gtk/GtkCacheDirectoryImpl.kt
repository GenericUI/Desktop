package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.desktop.system.DesktopUserDirectoryNativeImpl

/**
 * Linux GTK4 (`:desktop:gtk`) implementation of [com.nexustools.genericui.nativeimpl.system.UserDirectoryNativeImpl]
 * for [com.nexustools.genericui.util.CacheDirectory], resolving to `$XDG_CACHE_HOME/<appName>` (or `~/.cache/<appName>`).
 */
class GtkCacheDirectoryImpl(
    appName: String = "GenericUI",
    subdirectory: String = ""
) : DesktopUserDirectoryNativeImpl(
    appName = appName,
    subdirectory = subdirectory,
    baseDirectoryPath = DesktopConfigDirectoryHelper.resolveUnixAppCacheDir(appName),
    fileSeparator = '/'
)
