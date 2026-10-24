package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.dialog.DesktopStandardDialogsNativeImpl

/**
 * macOS AppKit `NSAlert` and `NSColorPanel` implementation of
 * [com.nexustools.genericui.nativeimpl.dialog.StandardDialogsNativeImpl].
 */
class MacosStandardDialogsNativeImpl : DesktopStandardDialogsNativeImpl(
    backendToolkit = "macOS AppKit (NSAlert / NSColorPanel)"
)
