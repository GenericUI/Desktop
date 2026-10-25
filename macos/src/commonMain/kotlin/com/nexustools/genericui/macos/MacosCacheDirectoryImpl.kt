package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.desktop.system.DesktopUserDirectoryNativeImpl

/**
 * macOS (`:desktop:macos`) implementation of [com.nexustools.genericui.nativeimpl.system.UserDirectoryNativeImpl]
 * for [com.nexustools.genericui.util.CacheDirectory], resolving to `~/Library/Caches/<appName>[/<subdirectory>]`.
 */
class MacosCacheDirectoryImpl(
    appName: String = "GenericUI",
    subdirectory: String = ""
) : DesktopUserDirectoryNativeImpl(
    appName = appName,
    subdirectory = subdirectory,
    baseDirectoryPath = DesktopConfigDirectoryHelper.resolveMacosAppCacheDir(appName),
    fileSeparator = '/'
)
