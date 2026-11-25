@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockWindowNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.window.TitleBar

/**
 * Win32 floating utility dock window (`WS_EX_TOOLWINDOW` `HWND`) implementation of
 * [com.nexustools.genericui.nativeimpl.dock.DockWindowNativeImpl].
 *
 * Uses [Win32TitleBarKind.TOOL_WINDOW] (`SM_CYSMCAPTION`, `WP_SMALLCAPTION`, `WP_SMALLCLOSEBUTTON`,
 * compact tool caption height, and no Minimize/Maximize buttons) while supporting embedded
 * [TitleBar] components (`leadingComponents`, `centerComponents`, `trailingComponents`) via
 * [Win32NativeTitleBarRenderer.renderEmbeddedTitleBarComponents].
 */
class WindowsDockWindowNativeImpl : DesktopDockWindowNativeImpl<Win32HwndHandle>(
    nativeWidget = Win32HwndHandle(
        win32ClassName = "GenericUIDockToolWindowW",
        dwStyle = Win32HwndHandle.WS_POPUP or Win32HwndHandle.WS_THICKFRAME or Win32HwndHandle.WS_VISIBLE,
        dwExStyle = Win32HwndHandle.WS_EX_TOOLWINDOW
    )
) {
    val titleBarKind: Win32TitleBarKind = Win32TitleBarKind.TOOL_WINDOW

    private var titleBarInstructions: TitleBar? = TitleBar(
        customTitleBar = true,
        showWindowIcon = false,
        showMinimizeButton = false,
        showMaximizeButton = false,
        showCloseButton = true
    )

    init {
        Win32WinMain.ensureWindowsControlsInitialized()
        nativeWidget.attributes["nativeImpl"] = this
        nativeWidget.attributes["win32ToolWindow"] = "true"
        nativeWidget.attributes["win32TitleBarKind"] = titleBarKind.name
        layoutController = DesktopContainerLayoutController(this).apply {
            onZOrderUpdated = { sorted ->
                nativeWidget.hwnd?.let { hostHwnd ->
                    Win32ControlMessageRouter.layoutChildrenBatch(hostHwnd, sorted)
                }
            }
        }
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        val hostHwnd = nativeWidget.hwnd ?: return
        val base = childImpl as? DesktopBaseNativeImpl<*> ?: return
        val h = base.nativeWidget as? Win32HwndHandle ?: return
        Win32ControlMessageRouter.attachChildHwnd(h, hostHwnd)
        Win32NativeWindowHost.layoutAndSyncWindowChildren(hostHwnd)
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        super.onChildRemovedNative(childImpl)
        val h = childImpl.nativeWidget as? Win32HwndHandle ?: return
        Win32ControlMessageRouter.detachChildHwnd(h)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        super.onNativeBoundsChanged(rect)
        if (rect.width > 0 && rect.height > 0) {
            layoutChildrenInBounds(rect.width.toFloat(), rect.height.toFloat())
        }
    }

    fun getTitleBarInstructions(): TitleBar? = titleBarInstructions

    fun applyTitleBarInstructions(titleBar: TitleBar?) {
        val resolved = (titleBar ?: TitleBar(
            customTitleBar = true,
            showWindowIcon = false,
            showMinimizeButton = false,
            showMaximizeButton = false,
            showCloseButton = true
        )).apply {
            showWindowIcon = false
            showMinimizeButton = false
            showMaximizeButton = false
        }
        titleBarInstructions = resolved
        nativeWidget.attributes["titleBar"] = resolved
        nativeWidget.attributes["win32TitleBarKind"] = titleBarKind.name
    }
}
