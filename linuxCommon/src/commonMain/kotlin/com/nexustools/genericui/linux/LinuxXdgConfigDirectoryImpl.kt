package com.nexustools.genericui.linux

import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.desktop.system.DesktopUserDirectoryNativeImpl

/**
 * Shared Linux XDG (`:desktop:linuxCommon`) implementation of [com.nexustools.genericui.nativeimpl.system.UserDirectoryNativeImpl]
 * for [com.nexustools.genericui.util.ConfigDirectory], resolving to `$XDG_CONFIG_HOME/<appName>` (or `~/.config/<appName>`).
 */
open class LinuxXdgConfigDirectoryImpl(
    appName: String = "GenericUI",
    subdirectory: String = ""
) : DesktopUserDirectoryNativeImpl(
    appName = appName,
    subdirectory = subdirectory,
    baseDirectoryPath = DesktopConfigDirectoryHelper.resolveUnixAppConfigDir(appName),
    fileSeparator = '/'
)
