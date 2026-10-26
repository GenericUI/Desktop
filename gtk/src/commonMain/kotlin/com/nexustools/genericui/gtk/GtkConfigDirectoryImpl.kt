package com.nexustools.genericui.gtk

import com.nexustools.genericui.linux.LinuxXdgConfigDirectoryImpl

/**
 * Linux GTK4 (`:desktop:gtk`) implementation of [com.nexustools.genericui.nativeimpl.system.UserDirectoryNativeImpl]
 * for [com.nexustools.genericui.util.ConfigDirectory], resolving to `$XDG_CONFIG_HOME/<appName>` (or `~/.config/<appName>`).
 */
class GtkConfigDirectoryImpl(
    appName: String = "GenericUI",
    subdirectory: String = ""
) : LinuxXdgConfigDirectoryImpl(
    appName = appName,
    subdirectory = subdirectory
)
