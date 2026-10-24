package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.system.DesktopNotificationManagerNativeImpl

/**
 * Windows native notification manager using WinRT `ToastNotificationManager` (`Windows.UI.Notifications`).
 */
class WindowsNotificationManagerNativeImpl : DesktopNotificationManagerNativeImpl(
    backendToolkit = "Windows WinRT ToastNotificationManager"
)
