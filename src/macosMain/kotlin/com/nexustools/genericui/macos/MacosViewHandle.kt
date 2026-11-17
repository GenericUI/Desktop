package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.core.DesktopNativeWidget

/**
 * macOS AppKit Objective-C object handle (`NSView*`, `NSWindow*`, `NSPanel*`, `NSMenu*`, `NSApplication*`)
 * descriptor wrapping an AppKit class name and `NSWindowStyleMask` flags.
 */
class MacosViewHandle(
    objcClassName: String,
    var styleMask: Long = NSWindowStyleMaskTitled or NSWindowStyleMaskClosable or NSWindowStyleMaskMiniaturizable or NSWindowStyleMaskResizable,
    objcPointer: Long = nextPointerId()
) : DesktopNativeWidget(
    backendToolkit = "macOS_AppKit",
    nativeClassName = objcClassName,
    nativePointer = objcPointer
) {
    companion object {
        const val NSWindowStyleMaskBorderless: Long = 0L
        const val NSWindowStyleMaskTitled: Long = 1L shl 0
        const val NSWindowStyleMaskClosable: Long = 1L shl 1
        const val NSWindowStyleMaskMiniaturizable: Long = 1L shl 2
        const val NSWindowStyleMaskResizable: Long = 1L shl 3
        const val NSWindowStyleMaskUtilityWindow: Long = 1L shl 4
        const val NSWindowStyleMaskNonactivatingPanel: Long = 1L shl 7
        const val NSWindowStyleMaskFullSizeContentView: Long = 1L shl 15
    }
}
