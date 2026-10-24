package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.system.DesktopSystemTrayNativeImpl

/**
 * Windows system tray icon implementation using `Shell_NotifyIconW` (`NOTIFYICONDATAW` / `NIF_ICON` / `NIF_TIP`).
 */
class WindowsSystemTrayNativeImpl : DesktopSystemTrayNativeImpl(
    isSupported = true
)
