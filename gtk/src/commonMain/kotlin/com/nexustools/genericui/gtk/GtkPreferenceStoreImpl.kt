package com.nexustools.genericui.gtk

import com.nexustools.genericui.linux.LinuxXdgPreferenceStoreImpl

/**
 * Linux GTK4 (`:desktop:gtk`) implementation of [com.nexustools.genericui.nativeimpl.system.PreferenceStoreNativeImpl]
 * backed by [com.nexustools.genericui.util.ConfigDirectory] ([GtkConfigDirectoryImpl]).
 */
class GtkPreferenceStoreImpl(
    appName: String = "GenericUI"
) : LinuxXdgPreferenceStoreImpl(
    appName = appName,
    configDirectoryImpl = GtkConfigDirectoryImpl(appName)
)
