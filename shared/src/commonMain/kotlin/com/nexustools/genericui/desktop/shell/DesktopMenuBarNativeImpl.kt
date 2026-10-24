package com.nexustools.genericui.desktop.shell

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.shell.MenuBarNativeImpl

/**
 * Shared desktop base implementation of [MenuBarNativeImpl] for Win32 `HMENU`, macOS `NSMenu`,
 * GTK4 `GMenu`/`GtkPopoverMenuBar`, and Qt6 `QMenuBar`.
 */
open class DesktopMenuBarNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "menubar"), MenuBarNativeImpl<W>
