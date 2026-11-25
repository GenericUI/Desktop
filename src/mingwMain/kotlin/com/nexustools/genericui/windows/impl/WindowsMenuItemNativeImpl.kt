@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.shell.DesktopMenuItemNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle

/**
 * Retained Win32 `MENUITEMINFOW` item descriptor used inside [WindowsMenuNativeImpl] and
 * [WindowsContextMenuNativeImpl].
 */
class WindowsMenuItemNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("Win32MenuItem"),
    checkable: Boolean = false,
    radio: Boolean = false
) : DesktopMenuItemNativeImpl<Win32HwndHandle>(widget, checkable, radio) {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }
}
