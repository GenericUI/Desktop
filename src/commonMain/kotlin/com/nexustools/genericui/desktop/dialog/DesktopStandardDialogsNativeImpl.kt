package com.nexustools.genericui.desktop.dialog

import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.nativeimpl.dialog.StandardDialogsNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.style.color.RgbaColor

/**
 * Shared desktop base implementation of [StandardDialogsNativeImpl].
 *
 * Platform desktop backends must override dialog presentation methods rather than falling back to
 * silent non-interactive stubs.
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
        throw UnsupportedOperationException("$backendToolkit alert dialogs require a platform OS dialog implementation.")
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
        throw UnsupportedOperationException("$backendToolkit confirm dialogs require a platform OS dialog implementation.")
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
        throw UnsupportedOperationException("$backendToolkit prompt dialogs require a platform OS dialog implementation.")
    }

    override fun showChoice(
        owner: WindowNativeImpl<*>?,
        title: String,
        message: String,
        options: List<String>,
        selectedIndex: Int,
        onResult: (Int?) -> Unit
    ) {
        throw UnsupportedOperationException("$backendToolkit choice dialogs require a platform OS dialog implementation.")
    }

    override fun showColorChooser(
        owner: WindowNativeImpl<*>?,
        title: String,
        initialColor: RgbaColor,
        onResult: (RgbaColor?) -> Unit
    ) {
        throw UnsupportedOperationException("$backendToolkit color chooser dialogs require a platform OS dialog implementation.")
    }

    override fun dispose() {
        isDisposed = true
    }
}
