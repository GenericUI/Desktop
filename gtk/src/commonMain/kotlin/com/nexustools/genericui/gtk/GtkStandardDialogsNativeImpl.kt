package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.dialog.DesktopStandardDialogsNativeImpl

/**
 * GTK 4 `GtkAlertDialog`, `AdwAlertDialog`, and `GtkColorDialog` implementation of
 * [com.nexustools.genericui.nativeimpl.dialog.StandardDialogsNativeImpl].
 */
class GtkStandardDialogsNativeImpl : DesktopStandardDialogsNativeImpl(
    backendToolkit = "GTK4 (GtkAlertDialog / GtkColorDialog)"
)
