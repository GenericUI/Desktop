package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.dialog.DesktopStandardDialogsNativeImpl

/**
 * Windows native standard dialogs (`TaskDialogIndirect`, `MessageBoxW`, and `ChooseColorW`)
 * implementation of [com.nexustools.genericui.nativeimpl.dialog.StandardDialogsNativeImpl].
 */
class WindowsStandardDialogsNativeImpl : DesktopStandardDialogsNativeImpl(
    backendToolkit = "Win32 (TaskDialogIndirect / ChooseColorW)"
)
