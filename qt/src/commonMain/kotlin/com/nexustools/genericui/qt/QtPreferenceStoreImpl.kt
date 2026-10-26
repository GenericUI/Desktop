package com.nexustools.genericui.qt

import com.nexustools.genericui.linux.LinuxXdgPreferenceStoreImpl

/**
 * Linux Qt6 (`:desktop:qt`) implementation of [com.nexustools.genericui.nativeimpl.system.PreferenceStoreNativeImpl]
 * backed by [com.nexustools.genericui.util.ConfigDirectory] ([QtConfigDirectoryImpl]).
 */
class QtPreferenceStoreImpl(
    appName: String = "GenericUI"
) : LinuxXdgPreferenceStoreImpl(
    appName = appName,
    configDirectoryImpl = QtConfigDirectoryImpl(appName)
)
