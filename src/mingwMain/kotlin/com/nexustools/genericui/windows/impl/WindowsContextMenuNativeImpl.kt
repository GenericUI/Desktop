@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.shell.DesktopContextMenuNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.WindowsApplicationNativeImpl
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.ClientToScreen
import platform.windows.CreatePopupMenu
import platform.windows.DestroyMenu
import platform.windows.IsWindow
import platform.windows.POINT
import platform.windows.TPM_LEFTALIGN
import platform.windows.TPM_NONOTIFY
import platform.windows.TPM_RETURNCMD
import platform.windows.TPM_TOPALIGN
import platform.windows.TrackPopupMenuEx

/**
 * Retained Win32 popup context menu (`HMENU` + `TrackPopupMenuEx`) implementation of
 * `ContextMenuNativeImpl`.
 */
class WindowsContextMenuNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("Win32PopupMenu")
) : DesktopContextMenuNativeImpl<Win32HwndHandle>(widget) {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun showAt(anchor: NativeImpl<*>?, x: Int, y: Int) {
        super.showAt(anchor, x, y)
        if (!WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) return
        val ownerHwnd = (anchor?.nativeWidget as? Win32HwndHandle)?.hwnd
            ?: Win32NativeWindowHost.activeHwnd
            ?: return
        if (IsWindow(ownerHwnd) == 0) return

        val hMenu = CreatePopupMenu() ?: return
        val cmdHandlers = HashMap<Int, () -> Unit>()
        try {
            Win32NativeWindowHost.populateWin32PopupMenu(hMenu, nativeWidget, cmdHandlers)
            val screenPt = memScoped {
                val pt = alloc<POINT>()
                pt.x = x
                pt.y = y
                ClientToScreen(ownerHwnd, pt.ptr)
                pt.x to pt.y
            }
            val cmd = TrackPopupMenuEx(
                hMenu,
                (TPM_LEFTALIGN or TPM_TOPALIGN or TPM_RETURNCMD or TPM_NONOTIFY).toUInt(),
                screenPt.first,
                screenPt.second,
                ownerHwnd,
                null
            )
            if (cmd > 0) {
                cmdHandlers[cmd]?.invoke()
            }
            hide()
        } finally {
            DestroyMenu(hMenu)
        }
    }
}
