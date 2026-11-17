@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.dialog.DesktopStandardDialogsNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.ObjCAction
import platform.AppKit.NSAlert
import platform.AppKit.NSAlertFirstButtonReturn
import platform.AppKit.NSAlertStyleCritical
import platform.AppKit.NSAlertStyleInformational
import platform.AppKit.NSAlertStyleWarning
import platform.AppKit.NSApplication
import platform.AppKit.NSButton
import platform.AppKit.NSColor
import platform.AppKit.NSColorPanel
import platform.AppKit.NSColorSpace
import platform.AppKit.NSModalResponseCancel
import platform.AppKit.NSModalResponseOK
import platform.AppKit.NSPopUpButton
import platform.AppKit.NSTextField
import platform.AppKit.NSView
import platform.AppKit.NSWindowDelegateProtocol
import platform.Foundation.NSMakeRect
import platform.Foundation.NSNotification
import platform.Foundation.NSSelectorFromString
import platform.darwin.NSObject
import kotlin.math.roundToInt

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
        if (!MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            super.showColorChooser(owner, title, initialColor, onResult)
            return
        }
        GenericUiThreading.runOnUi {
            val panel = NSColorPanel.sharedColorPanel
            val previousTitle = panel.title
            val previousAccessory = panel.accessoryView
            val previousDelegate = panel.delegate
            val previousShowsAlpha = panel.showsAlpha
            val controller = MacosColorPanelModalController()

            panel.title = title.ifBlank { "Choose Color" }
            panel.showsAlpha = true
            panel.color = NSColor.colorWithSRGBRed(
                red = initialColor.r.toDouble() / 255.0,
                green = initialColor.g.toDouble() / 255.0,
                blue = initialColor.b.toDouble() / 255.0,
                alpha = initialColor.a.toDouble() / 255.0
            )

            val accessory = NSView(frame = NSMakeRect(0.0, 0.0, 236.0, 38.0))
            val cancelButton = NSButton.buttonWithTitle(
                title = "Cancel",
                target = controller,
                action = NSSelectorFromString("onCancel:")
            )
            cancelButton.setFrame(NSMakeRect(42.0, 6.0, 88.0, 26.0))
            val selectButton = NSButton.buttonWithTitle(
                title = "Select",
                target = controller,
                action = NSSelectorFromString("onConfirm:")
            )
            selectButton.setFrame(NSMakeRect(138.0, 6.0, 88.0, 26.0))
            selectButton.keyEquivalent = "\r"
            accessory.addSubview(cancelButton)
            accessory.addSubview(selectButton)

            panel.accessoryView = accessory
            panel.delegate = controller

            val modalResponse = try {
                NSApplication.sharedApplication.runModalForWindow(panel)
            } finally {
                panel.delegate = previousDelegate
                panel.accessoryView = previousAccessory
                panel.showsAlpha = previousShowsAlpha
                panel.title = previousTitle
                panel.close()
            }

            val chosen = if (modalResponse == NSModalResponseOK) {
                nsColorToRgba(panel.color)
            } else {
                null
            }
            GenericUiThreading.runOnLogic {
                onResult(chosen)
            }
        }
    }

    private fun nsColorToRgba(color: NSColor): RgbaColor {
        val srgb = color.colorUsingColorSpace(NSColorSpace.sRGBColorSpace) ?: color
        return RgbaColor(
            r = (srgb.redComponent * 255.0).roundToInt().coerceIn(0, 255),
            g = (srgb.greenComponent * 255.0).roundToInt().coerceIn(0, 255),
            b = (srgb.blueComponent * 255.0).roundToInt().coerceIn(0, 255),
            a = (srgb.alphaComponent * 255.0).roundToInt().coerceIn(0, 255)
        )
    }

    private class MacosColorPanelModalController : NSObject(), NSWindowDelegateProtocol {
        private var finished = false

        @ObjCAction
        fun onConfirm(sender: NSObject?) {
            if (finished) return
            finished = true
            NSApplication.sharedApplication.stopModalWithCode(NSModalResponseOK)
        }

        @ObjCAction
        fun onCancel(sender: NSObject?) {
            if (finished) return
            finished = true
            NSApplication.sharedApplication.stopModalWithCode(NSModalResponseCancel)
        }

        override fun windowWillClose(notification: NSNotification) {
            if (!finished) {
                finished = true
                NSApplication.sharedApplication.stopModalWithCode(NSModalResponseCancel)
            }
        }
    }

    private fun resolveAlertStyle(type: BootstrapType) = when (type) {
        BootstrapType.DANGER -> NSAlertStyleCritical
        BootstrapType.WARNING -> NSAlertStyleWarning
        else -> NSAlertStyleInformational
    }
}
