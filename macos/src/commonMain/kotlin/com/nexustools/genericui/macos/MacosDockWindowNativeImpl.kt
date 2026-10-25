package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.dock.DesktopDockWindowNativeImpl

/**
 * macOS AppKit `NSPanel` utility window (`NSWindowStyleMaskUtilityWindow`, no miniaturize button) implementation of
 * [com.nexustools.genericui.nativeimpl.dock.DockWindowNativeImpl].
 */
class MacosDockWindowNativeImpl : DesktopDockWindowNativeImpl<MacosViewHandle>(
    nativeWidget = MacosViewHandle(
        objcClassName = "NSPanel",
        styleMask = MacosViewHandle.NSWindowStyleMaskTitled or
            MacosViewHandle.NSWindowStyleMaskClosable or
            MacosViewHandle.NSWindowStyleMaskResizable or
            MacosViewHandle.NSWindowStyleMaskUtilityWindow
    )
) {
    init {
        nativeWidget.attributes["floatingPanel"] = true
        nativeWidget.attributes["hidesOnDeactivate"] = false
        nativeWidget.attributes["level"] = "NSFloatingWindowLevel"
    }
}
