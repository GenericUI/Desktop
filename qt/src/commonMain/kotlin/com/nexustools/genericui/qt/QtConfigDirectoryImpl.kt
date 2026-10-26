package com.nexustools.genericui.qt

import com.nexustools.genericui.linux.LinuxXdgConfigDirectoryImpl

/**
 * Linux Qt6 (`:desktop:qt`) implementation of [com.nexustools.genericui.nativeimpl.system.UserDirectoryNativeImpl]
 * for [com.nexustools.genericui.util.ConfigDirectory], resolving to `$XDG_CONFIG_HOME/<appName>` (or `~/.config/<appName>`).
 */
class QtConfigDirectoryImpl(
    appName: String = "GenericUI",
    subdirectory: String = ""
) : LinuxXdgConfigDirectoryImpl(
    appName = appName,
    subdirectory = subdirectory
)
