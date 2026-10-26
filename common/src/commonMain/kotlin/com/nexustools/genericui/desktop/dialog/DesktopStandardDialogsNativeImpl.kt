package com.nexustools.genericui.desktop.dialog

import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.nativeimpl.dialog.StandardDialogsNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.style.RgbaColor

/**
 * Shared desktop base implementation of [StandardDialogsNativeImpl] for native OS message boxes,
 * prompts, choice dialogs, and color choosers (`ChooseColorW` on Windows, `NSColorPanel` on macOS,
 * `GtkColorDialog` on GTK4, `QColorDialog` on Qt6).
 */
open class DesktopStandardDialogsNativeImpl(
    val backendToolkit: String = "Desktop"
) : StandardDialogsNativeImpl {

    override var isDisposed: Boolean = false
        protected set

    override fun showAlert(
        owner: WindowNativeImpl<*>?,
        title: String,
        message: String,
        type: BootstrapType,
        okButtonText: String,
        onDismissed: () -> Unit
    ) {
        onDismissed()
    }

    override fun showConfirm(
        owner: WindowNativeImpl<*>?,
        title: String,
        message: String,
        confirmText: String,
        cancelText: String,
        type: BootstrapType,
        onResult: (Boolean) -> Unit
    ) {
        onResult(true)
    }

    override fun showPrompt(
        owner: WindowNativeImpl<*>?,
        title: String,
        message: String,
        defaultValue: String,
        placeholder: String,
        confirmText: String,
        cancelText: String,
        onResult: (String?) -> Unit
    ) {
        onResult(defaultValue)
    }

    override fun showChoice(
        owner: WindowNativeImpl<*>?,
        title: String,
        message: String,
        options: List<String>,
        selectedIndex: Int,
        onResult: (Int?) -> Unit
    ) {
        onResult(selectedIndex.takeIf { it in options.indices })
    }

    override fun showColorChooser(
        owner: WindowNativeImpl<*>?,
        title: String,
        initialColor: RgbaColor,
        onResult: (RgbaColor?) -> Unit
    ) {
        onResult(initialColor)
    }

    override fun dispose() {
        isDisposed = true
    }
}
