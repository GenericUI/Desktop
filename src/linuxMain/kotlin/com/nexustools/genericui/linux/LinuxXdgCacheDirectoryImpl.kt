package com.nexustools.genericui.linux

import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.desktop.system.DesktopUserDirectoryNativeImpl

open class LinuxXdgCacheDirectoryImpl(
    appName: String = "GenericUI",
    subdirectory: String = ""
) : DesktopUserDirectoryNativeImpl(
    appName = appName,
    subdirectory = subdirectory,
    baseDirectoryPath = DesktopConfigDirectoryHelper.resolveUnixAppCacheDir(appName),
    fileSeparator = '/'
)
