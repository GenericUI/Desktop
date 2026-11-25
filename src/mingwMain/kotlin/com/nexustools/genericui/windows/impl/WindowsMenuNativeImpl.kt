@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.shell.DesktopMenuNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle

/**
 * Retained Win32 `HMENU` submenu container descriptor for [WindowsMenuBarNativeImpl] and
 * [WindowsContextMenuNativeImpl].
 */
class WindowsMenuNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("Win32HMenu")
) : DesktopMenuNativeImpl<Win32HwndHandle>(widget) {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }
}
