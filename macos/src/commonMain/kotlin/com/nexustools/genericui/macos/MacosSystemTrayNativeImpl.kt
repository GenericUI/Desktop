package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.system.DesktopSystemTrayNativeImpl

/**
 * macOS menu bar status item implementation using `NSStatusBar.systemStatusBar` and `NSStatusItem`.
 */
class MacosSystemTrayNativeImpl : DesktopSystemTrayNativeImpl(
    isSupported = true
)
