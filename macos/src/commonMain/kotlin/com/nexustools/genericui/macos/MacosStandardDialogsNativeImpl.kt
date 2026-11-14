@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.dialog.DesktopStandardDialogsNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.threading.GenericUiThreading
import platform.AppKit.NSAlert
import platform.AppKit.NSAlertFirstButtonReturn
import platform.AppKit.NSAlertStyleCritical
import platform.AppKit.NSAlertStyleInformational
import platform.AppKit.NSAlertStyleWarning
import platform.AppKit.NSPopUpButton
import platform.AppKit.NSTextField
import platform.Foundation.NSMakeRect

/**
 * macOS AppKit `NSAlert` and `NSColorPanel` implementation of
 * [com.nexustools.genericui.nativeimpl.dialog.StandardDialogsNativeImpl].
 */
class MacosStandardDialogsNativeImpl : DesktopStandardDialogsNativeImpl(
    backendToolkit = "macOS AppKit (NSAlert / NSColorPanel)"
) {
    override fun showAlert(
        owner: WindowNativeImpl<*>?,
        title: String,
        message: String,
        type: BootstrapType,
        okButtonText: String,
        onDismissed: () -> Unit
    ) {
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            super.showAlert(owner, title, message, type, okButtonText, onDismissed)
            return
        }
        GenericUiThreading.runOnUi {
            val alert = NSAlert()
            alert.messageText = title.ifBlank { "Notice" }
            alert.informativeText = message
            alert.alertStyle = resolveAlertStyle(type)
            alert.addButtonWithTitle(okButtonText.ifBlank { "OK" })
            alert.runModal()
            GenericUiThreading.runOnLogic {
                onDismissed()
            }
        }
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
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            super.showConfirm(owner, title, message, confirmText, cancelText, type, onResult)
            return
        }
        GenericUiThreading.runOnUi {
            val alert = NSAlert()
            alert.messageText = title.ifBlank { "Confirm" }
            alert.informativeText = message
            alert.alertStyle = resolveAlertStyle(type)
            alert.addButtonWithTitle(confirmText.ifBlank { "OK" })
            alert.addButtonWithTitle(cancelText.ifBlank { "Cancel" })
            val confirmed = alert.runModal() == NSAlertFirstButtonReturn
            GenericUiThreading.runOnLogic {
                onResult(confirmed)
            }
        }
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
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            super.showPrompt(owner, title, message, defaultValue, placeholder, confirmText, cancelText, onResult)
            return
        }
        GenericUiThreading.runOnUi {
            val alert = NSAlert()
            alert.messageText = title.ifBlank { "Input" }
            alert.informativeText = message
            alert.alertStyle = NSAlertStyleInformational
            alert.addButtonWithTitle(confirmText.ifBlank { "OK" })
            alert.addButtonWithTitle(cancelText.ifBlank { "Cancel" })
            val inputField = NSTextField(frame = NSMakeRect(0.0, 0.0, 280.0, 24.0))
            inputField.stringValue = defaultValue
            if (placeholder.isNotEmpty()) {
                inputField.placeholderString = placeholder
            }
            alert.accessoryView = inputField
            val response = alert.runModal()
            val value = if (response == NSAlertFirstButtonReturn) inputField.stringValue else null
            GenericUiThreading.runOnLogic {
                onResult(value)
            }
        }
    }

    override fun showChoice(
        owner: WindowNativeImpl<*>?,
        title: String,
        message: String,
        options: List<String>,
        selectedIndex: Int,
        onResult: (Int?) -> Unit
    ) {
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            super.showChoice(owner, title, message, options, selectedIndex, onResult)
            return
        }
        GenericUiThreading.runOnUi {
            if (options.isEmpty()) {
                GenericUiThreading.runOnLogic { onResult(null) }
                return@runOnUi
            }
            val alert = NSAlert()
            alert.messageText = title.ifBlank { "Choose Option" }
            alert.informativeText = message
            alert.alertStyle = NSAlertStyleInformational
            alert.addButtonWithTitle("OK")
            alert.addButtonWithTitle("Cancel")
            val popup = NSPopUpButton(frame = NSMakeRect(0.0, 0.0, 260.0, 26.0), pullsDown = false)
            for (opt in options) {
                popup.addItemWithTitle(opt)
            }
            if (selectedIndex in options.indices) {
                popup.selectItemAtIndex(selectedIndex.toLong())
            }
            alert.accessoryView = popup
            val response = alert.runModal()
            val chosen = if (response == NSAlertFirstButtonReturn) {
                popup.indexOfSelectedItem.toInt().takeIf { it in options.indices }
            } else {
                null
            }
            GenericUiThreading.runOnLogic {
                onResult(chosen)
            }
        }
    }

    override fun showColorChooser(
        owner: WindowNativeImpl<*>?,
        title: String,
        initialColor: RgbaColor,
        onResult: (RgbaColor?) -> Unit
    ) {
        super.showColorChooser(owner, title, initialColor, onResult)
    }

    private fun resolveAlertStyle(type: BootstrapType) = when (type) {
        BootstrapType.DANGER -> NSAlertStyleCritical
        BootstrapType.WARNING -> NSAlertStyleWarning
        else -> NSAlertStyleInformational
    }
}
