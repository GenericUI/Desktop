package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.dialog.DesktopStandardDialogsNativeImpl

/**
 * Qt 6 `QMessageBox`, `QInputDialog`, and `QColorDialog` implementation of
 * [com.nexustools.genericui.nativeimpl.dialog.StandardDialogsNativeImpl].
 */
class QtStandardDialogsNativeImpl : DesktopStandardDialogsNativeImpl(
    backendToolkit = "Qt6 (QMessageBox / QInputDialog / QColorDialog)"
)
