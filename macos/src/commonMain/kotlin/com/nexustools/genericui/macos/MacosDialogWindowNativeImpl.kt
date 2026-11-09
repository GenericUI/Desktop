package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl

/**
 * macOS AppKit `NSPanel` / `NSWindow` sheet or modal dialog implementation of
 * [com.nexustools.genericui.nativeimpl.dialog.DialogWindowNativeImpl].
 */
open class MacosDialogWindowNativeImpl(
    nativeWidget: MacosViewHandle = MacosViewHandle("NSPanel_SheetOrModal").apply {
        attributes["isSeparateNativeWindow"] = true
        attributes["inWindowOverlay"] = false
        attributes["attachedToMainWindow"] = true
    }
) : DesktopDialogWindowNativeImpl<MacosViewHandle>(nativeWidget) {

    val isSeparateNativeWindow: Boolean
        get() = nativeWidget.attributes["isSeparateNativeWindow"] as? Boolean ?: true

    val inWindowOverlay: Boolean
        get() = nativeWidget.attributes["inWindowOverlay"] as? Boolean ?: false

    val attachedToMainWindow: Boolean
        get() = nativeWidget.attributes["attachedToMainWindow"] as? Boolean ?: (ownerWindow != null)
}
