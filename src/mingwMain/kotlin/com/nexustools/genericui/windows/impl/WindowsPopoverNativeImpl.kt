@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.desktop.widget.DesktopPopoverNativeImpl
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32InteractiveSurfaceHwnd
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.WindowsApplicationNativeImpl
import platform.windows.InvalidateRect
import platform.windows.IsWindow

/**
 * Retained Win32 popup container (`"GenericUIPopoverW"`) delegating anchored top-most native
 * `WS_POPUP` presentation and batched child `HWND` mounting to [Win32NativeWindowHost].
 */
class WindowsPopoverNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIPopoverW")
) : DesktopPopoverNativeImpl<Win32HwndHandle>(widget) {

    init {
        nativeWidget.attributes["nativeImpl"] = this
        layoutController = DesktopContainerLayoutController(
            host = this,
            defaultDisplay = CssDisplay.FLEX,
            defaultFlexDirection = FlexDirection.COLUMN,
            defaultGapPx = 6,
            defaultPadding = EdgeInsets(top = 8, right = 10, bottom = 8, left = 10)
        ).apply {
            onZOrderUpdated = { sorted ->
                val hostHwnd = nativeWidget.hwnd
                if (hostHwnd != null && IsWindow(hostHwnd) != 0) {
                    Win32ControlMessageRouter.layoutChildrenBatch(hostHwnd, sorted)
                }
            }
        }
    }

    override fun showAnchored(anchor: NativeImpl<*>?, x: Int, y: Int) {
        super.showAnchored(anchor, x, y)
        if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
            Win32NativeWindowHost.showPopoverWindow(this, anchor, x, y)
        }
    }

    override fun hide() {
        Win32NativeWindowHost.closePopoverWindow(this)
        super.hide()
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
        Win32NativeWindowHost.closePopoverWindow(this)
        super.dispose()
    }
}
