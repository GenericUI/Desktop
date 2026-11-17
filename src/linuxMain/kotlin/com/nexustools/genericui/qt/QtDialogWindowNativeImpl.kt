package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl

/**
 * Qt 6 modal dialog window (`QDialog`) implementation of
 * [com.nexustools.genericui.nativeimpl.dialog.DialogWindowNativeImpl].
 */
open class QtDialogWindowNativeImpl(
    nativeWidget: QtWidgetHandle = QtWidgetHandle("QDialog_Modal").apply {
        attributes["isSeparateNativeWindow"] = true
        attributes["inWindowOverlay"] = false
        attributes["attachedToMainWindow"] = true
    }
) : DesktopDialogWindowNativeImpl<QtWidgetHandle>(nativeWidget) {

    val isSeparateNativeWindow: Boolean
        get() = nativeWidget.attributes["isSeparateNativeWindow"] as? Boolean ?: true

    val inWindowOverlay: Boolean
        get() = nativeWidget.attributes["inWindowOverlay"] as? Boolean ?: false

    val attachedToMainWindow: Boolean
        get() = nativeWidget.attributes["attachedToMainWindow"] as? Boolean ?: (ownerWindow != null)
}
