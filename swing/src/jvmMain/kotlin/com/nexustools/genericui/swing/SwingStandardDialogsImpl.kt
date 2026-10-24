package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.nativeimpl.dialog.StandardDialogsNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.style.RgbaColor

/**
 * Java Swing `JOptionPane` + `JColorChooser` implementation of [StandardDialogsNativeImpl].
 */
class SwingStandardDialogsImpl : StandardDialogsNativeImpl {
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

    override fun dispose() {}
}
