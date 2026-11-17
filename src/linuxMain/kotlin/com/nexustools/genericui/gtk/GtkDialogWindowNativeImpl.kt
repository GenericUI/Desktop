package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl

open class GtkDialogWindowNativeImpl(
    nativeWidget: GtkWidgetHandle = GtkWidgetHandle("GtkWindow_Modal").apply {
        attributes["isSeparateNativeWindow"] = true
        attributes["inWindowOverlay"] = false
        attributes["attachedToMainWindow"] = true
    }
) : DesktopDialogWindowNativeImpl<GtkWidgetHandle>(nativeWidget) {

    val isSeparateNativeWindow: Boolean
        get() = nativeWidget.attributes["isSeparateNativeWindow"] as? Boolean ?: true

    val inWindowOverlay: Boolean
        get() = nativeWidget.attributes["inWindowOverlay"] as? Boolean ?: false

    val attachedToMainWindow: Boolean
        get() = nativeWidget.attributes["attachedToMainWindow"] as? Boolean ?: (ownerWindow != null)
}
