package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.desktop.system.DesktopUserDirectoryNativeImpl

/**
 * Windows (`:desktop:windows`) implementation of [com.nexustools.genericui.nativeimpl.system.UserDirectoryNativeImpl]
 * for [com.nexustools.genericui.util.ConfigDirectory], resolving to `%APPDATA%\<appName>[\<subdirectory>]`.
 */
class WindowsConfigDirectoryImpl(
    appName: String = "GenericUI",
    subdirectory: String = ""
) : DesktopUserDirectoryNativeImpl(
    appName = appName,
    subdirectory = subdirectory,
    baseDirectoryPath = DesktopConfigDirectoryHelper.resolveWindowsAppConfigDir(appName),
    fileSeparator = '\\'
)
