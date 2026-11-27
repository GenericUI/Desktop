@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.shell.DesktopContextMenuNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32WinMain
import com.nexustools.genericui.windows.WindowsApplicationNativeImpl
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import platform.windows.ClientToScreen
import platform.windows.CreatePopupMenu
import platform.windows.DestroyMenu
import platform.windows.GA_ROOT
import platform.windows.GetAncestor
import platform.windows.GetClientRect
import platform.windows.GetMonitorInfoW
import platform.windows.IsWindow
import platform.windows.MONITORINFO
import platform.windows.MONITOR_DEFAULTTONEAREST
import platform.windows.MonitorFromWindow
import platform.windows.POINT
import platform.windows.PostMessageW
import platform.windows.RECT
import platform.windows.SetForegroundWindow
import platform.windows.TPM_LEFTALIGN
import platform.windows.TPM_NONOTIFY
import platform.windows.TPM_RETURNCMD
import platform.windows.TPM_RIGHTALIGN
import platform.windows.TPM_TOPALIGN
import platform.windows.TrackPopupMenuEx
import platform.windows.WM_NULL
import kotlin.math.min

/**
 * Retained Win32 popup context menu (`HMENU` + `TrackPopupMenuEx`) implementation of
 * `ContextMenuNativeImpl`.
 */
class WindowsContextMenuNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("Win32PopupMenu")
) : DesktopContextMenuNativeImpl<Win32HwndHandle>(widget) {

    internal data class PopupMenuPlacement(
        val trackX: Int,
        val trackY: Int,
        val alignFlags: Int
    )

    companion object {
        internal fun computePopupMenuPlacement(
            screenX: Int,
            screenY: Int,
            clientRightScreen: Int,
            monitorWorkRight: Int,
            anchorWidth: Int = 0,
            anchorScreenRight: Int = screenX,
            anchorLocalX: Int = 0
        ): PopupMenuPlacement {
            val nearRightEdge = screenX > clientRightScreen - 180 || screenX > monitorWorkRight - 220
            val alignFlags = if (nearRightEdge) {
                TPM_RIGHTALIGN or TPM_TOPALIGN
            } else {
                TPM_LEFTALIGN or TPM_TOPALIGN
            }
            val trackX = if (nearRightEdge && anchorWidth in 1..160 && anchorLocalX <= 12) {
                min(anchorScreenRight, monitorWorkRight)
            } else {
                screenX.coerceAtMost(monitorWorkRight)
            }
            return PopupMenuPlacement(
                trackX = trackX,
                trackY = screenY,
                alignFlags = alignFlags
            )
        }
    }

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun showAt(anchor: NativeImpl<*>?, x: Int, y: Int) {
        super.showAt(anchor, x, y)
        if (!WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) return
        val anchorHwnd = (anchor?.nativeWidget as? Win32HwndHandle)?.hwnd
            ?.takeIf { IsWindow(it) != 0 }
        val activeFallback = Win32NativeWindowHost.activeHwnd?.takeIf { IsWindow(it) != 0 }
        val clientOriginHwnd = anchorHwnd ?: activeFallback ?: return
        val trackHwnd = GetAncestor(clientOriginHwnd, GA_ROOT.toUInt())
            ?.takeIf { IsWindow(it) != 0 }
            ?: clientOriginHwnd

        val dark = Win32NativeWindowHost.isEffectivelyDarkTheme()
        Win32WinMain.allowDarkModeForWindow(trackHwnd, dark)
        Win32WinMain.syncUxThemeMenuDarkMode(dark)

        val hMenu = CreatePopupMenu() ?: return
        val cmdHandlers = HashMap<Int, () -> Unit>()
        try {
            Win32NativeWindowHost.populateWin32PopupMenu(hMenu, nativeWidget, cmdHandlers)
            val placement = memScoped {
                val pt = alloc<POINT>()
                pt.x = x
                pt.y = y
                ClientToScreen(clientOriginHwnd, pt.ptr)
                val screenX = pt.x
                val screenY = pt.y

                val trackRc = alloc<RECT>()
                GetClientRect(trackHwnd, trackRc.ptr)
                val rightPt = alloc<POINT>()
                rightPt.x = trackRc.right
                rightPt.y = trackRc.top
                ClientToScreen(trackHwnd, rightPt.ptr)
                val clientRightScreen = rightPt.x

                var anchorW = 0
                var anchorScreenRight = screenX
                if (anchorHwnd != null) {
                    val arc = alloc<RECT>()
                    GetClientRect(anchorHwnd, arc.ptr)
                    anchorW = (arc.right - arc.left).coerceAtLeast(0)
                    val apt = alloc<POINT>()
                    apt.x = arc.right
                    apt.y = arc.top
                    ClientToScreen(anchorHwnd, apt.ptr)
                    anchorScreenRight = apt.x
                }

                var monitorWorkRight = clientRightScreen
                val hMon = MonitorFromWindow(trackHwnd, MONITOR_DEFAULTTONEAREST.toUInt())
                if (hMon != null) {
                    val mi = alloc<MONITORINFO>()
                    mi.cbSize = sizeOf<MONITORINFO>().toUInt()
                    if (GetMonitorInfoW(hMon, mi.ptr) != 0) {
                        monitorWorkRight = mi.rcWork.right
                    }
                }

                computePopupMenuPlacement(
                    screenX = screenX,
                    screenY = screenY,
                    clientRightScreen = clientRightScreen,
                    monitorWorkRight = monitorWorkRight,
                    anchorWidth = anchorW,
                    anchorScreenRight = anchorScreenRight,
                    anchorLocalX = x
                )
            }

            SetForegroundWindow(trackHwnd)
            val cmd = TrackPopupMenuEx(
                hMenu,
                (placement.alignFlags or TPM_RETURNCMD or TPM_NONOTIFY).toUInt(),
                placement.trackX,
                placement.trackY,
                trackHwnd,
                null
            )
            PostMessageW(trackHwnd, WM_NULL.toUInt(), 0uL, 0L)
            if (cmd > 0) {
                cmdHandlers[cmd]?.invoke()
            }
            hide()
        } finally {
            DestroyMenu(hMenu)
        }
    }
}
