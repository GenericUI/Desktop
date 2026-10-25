package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.nativeimpl.shell.MenuBarNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.window.TitleBar

/**
 * macOS AppKit `NSWindow` implementation of [com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl].
 *
 * - `supportsCustomTitleBar = true`: Uses `NSWindowStyleMaskFullSizeContentView` +
 *   `titlebarAppearsTransparent = YES` + `titleVisibility = NSWindowTitleHidden` with native traffic light buttons.
 * - `supportsEmbeddedMenuBarInTitleBar = false`: macOS always places the application menu bar at the top of the
 *   screen via `NSApplication.mainMenu` (`NSMenu`) rather than embedding it inside the window title bar.
 */
class MacosWindowNativeImpl : DesktopWindowNativeImpl<MacosViewHandle>(
    nativeWidget = MacosViewHandle("NSWindow"),
    supportsCustomTitleBar = true,
    supportsEmbeddedMenuBarInTitleBar = false,
    supportsMultiWindow = true
) {
    override fun configureAsToolModalWindow(
        ownerWindow: WindowNativeImpl<*>?,
        showInTaskbar: Boolean,
        minimizable: Boolean,
        alwaysOnTopOfOwner: Boolean
    ) {
        super.configureAsToolModalWindow(ownerWindow, showInTaskbar, minimizable, alwaysOnTopOfOwner)
        nativeWidget.styleMask = (nativeWidget.styleMask or MacosViewHandle.NSWindowStyleMaskUtilityWindow) and
            (if (!minimizable) MacosViewHandle.NSWindowStyleMaskMiniaturizable.inv() else -1L)
        nativeWidget.attributes["floatingPanel"] = alwaysOnTopOfOwner
        nativeWidget.attributes["hidesOnDeactivate"] = false
        nativeWidget.attributes["level"] = if (alwaysOnTopOfOwner) "NSFloatingWindowLevel" else "NSNormalWindowLevel"
    }

    override fun applyTitleBarInstructions(titleBar: TitleBar?) {
        super.applyTitleBarInstructions(titleBar)
        val custom = titleBar?.customTitleBar == true
        if (custom) {
            nativeWidget.styleMask = nativeWidget.styleMask or MacosViewHandle.NSWindowStyleMaskFullSizeContentView
        }
        val allowMinimize = if (isToolModalWindow) minimizable else (titleBar?.showMinimizeButton ?: true)
        if (!allowMinimize) {
            nativeWidget.styleMask = nativeWidget.styleMask and MacosViewHandle.NSWindowStyleMaskMiniaturizable.inv()
        } else {
            nativeWidget.styleMask = nativeWidget.styleMask or MacosViewHandle.NSWindowStyleMaskMiniaturizable
        }
        nativeWidget.attributes["titlebarAppearsTransparent"] = custom
        nativeWidget.attributes["titleVisibility"] = if (custom) "NSWindowTitleHidden" else "NSWindowTitleVisible"
    }

    override fun setMenuBarNative(menuBarImpl: MenuBarNativeImpl<*>?) {
        super.setMenuBarNative(menuBarImpl)
        // Always routed to NSApplication.sharedApplication.mainMenu on macOS
        nativeWidget.attributes["nsApplicationMainMenu"] = menuBarImpl
        nativeWidget.attributes["embedMenuBarInTitleBar"] = false
    }
}
