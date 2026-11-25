@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32InteractiveSurfaceHwnd
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.WindowsApplicationNativeImpl
import platform.windows.InvalidateRect
import platform.windows.IsWindow
import platform.windows.SetWindowTextW

/**
 * Retained Win32 modal/modeless dialog `HWND` implementation (`"GenericUIWin32HostWindowW"`)
 * wiring [DesktopContainerLayoutController] and live child `HWND` mounting cleanly.
 */
class WindowsDialogWindowNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle(
        win32ClassName = "GenericUIWin32HostWindowW",
        dwStyle = Win32HwndHandle.WS_POPUP or Win32HwndHandle.WS_CAPTION or Win32HwndHandle.WS_SYSMENU or Win32HwndHandle.WS_THICKFRAME,
        dwExStyle = Win32HwndHandle.WS_EX_DLGMODALFRAME
    )
) : DesktopDialogWindowNativeImpl<Win32HwndHandle>(widget) {

    init {
        nativeWidget.attributes["nativeImpl"] = this
        layoutController = DesktopContainerLayoutController(this).apply {
            onZOrderUpdated = { sorted ->
                val hostHwnd = nativeWidget.hwnd
                if (hostHwnd != null && IsWindow(hostHwnd) != 0) {
                    Win32ControlMessageRouter.layoutChildrenBatch(hostHwnd, sorted)
                }
            }
        }
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
            Win32NativeWindowHost.ensureOverlayInvalidationListenerInstalled()
        }
    }

    override fun setTitle(title: String) {
        super.setTitle(title)
        val hwnd = nativeWidget.hwnd
        if (hwnd != null && IsWindow(hwnd) != 0) {
            SetWindowTextW(hwnd, title)
        }
    }

    override fun showDialog(ownerWindow: WindowNativeImpl<*>?) {
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
            Win32NativeWindowHost.ensureOverlayInvalidationListenerInstalled()
        }
        super.showDialog(ownerWindow)
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
            Win32NativeWindowHost.showNativeDialogWindow(this, ownerWindow as? DesktopWindowNativeImpl<*>)
        }
    }

    override fun closeDialog() {
        Win32NativeWindowHost.closeNativeDialogWindow(this)
        super.closeDialog()
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        val hostHwnd = nativeWidget.hwnd
        if (hostHwnd != null && IsWindow(hostHwnd) != 0) {
            val base = childImpl as? DesktopBaseNativeImpl<*> ?: return
            val childHandle = base.nativeWidget as? Win32HwndHandle ?: return
            Win32InteractiveSurfaceHwnd.ensureSurfaceChildHwnd(childHandle, base)
            Win32ControlMessageRouter.attachChildHwnd(childHandle, hostHwnd)
            Win32NativeWindowHost.layoutAndSyncWindowChildren(hostHwnd)
            InvalidateRect(hostHwnd, null, 0)
        }
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        val childHandle = childImpl.nativeWidget as? Win32HwndHandle
        if (childHandle != null) {
            Win32ControlMessageRouter.detachChildHwnd(childHandle)
        }
        val hostHwnd = nativeWidget.hwnd
        if (hostHwnd != null && IsWindow(hostHwnd) != 0) {
            Win32NativeWindowHost.layoutAndSyncWindowChildren(hostHwnd)
            InvalidateRect(hostHwnd, null, 0)
        }
    }

    override fun onChildrenClearedNative() {
        for (child in childImpls.toList()) {
            val childHandle = child.nativeWidget as? Win32HwndHandle ?: continue
            Win32ControlMessageRouter.detachChildHwnd(childHandle)
        }
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (rect.width > 0 && rect.height > 0) {
            layoutChildrenInBounds(rect.width.toFloat(), rect.height.toFloat())
        }
    }

    override fun dispose() {
        Win32NativeWindowHost.closeNativeDialogWindow(this)
        super.dispose()
    }
}
