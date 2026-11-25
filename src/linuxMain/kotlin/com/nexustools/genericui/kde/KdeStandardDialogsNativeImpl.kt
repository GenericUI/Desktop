@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.dialog.DesktopStandardDialogsNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.threading.GenericUiThreading

/**
 * KDE (`QMessageBox`, `QColorDialog`, and modal Qt 6 prompt/choice `QDialog`s) implementation
 * of [com.nexustools.genericui.nativeimpl.dialog.StandardDialogsNativeImpl].
 */
class KdeStandardDialogsNativeImpl : DesktopStandardDialogsNativeImpl(
    backendToolkit = "KDE_Qt6 (QMessageBox / QColorDialog)"
) {
    override fun showAlert(
        owner: WindowNativeImpl<*>?,
        title: String,
        message: String,
        type: BootstrapType,
        okButtonText: String,
        onDismissed: () -> Unit
    ) {
        if (!KdeApplicationNativeImpl.interactiveKdeGuiEnabled) {
            super.showAlert(owner, title, message, type, okButtonText, onDismissed)
            return
        }
        val ownerPtr = resolveOwnerPtr(owner)
        val kind = when (type) {
            BootstrapType.WARNING -> "warning"
            BootstrapType.DANGER -> "error"
            else -> "info"
        }
        GenericUiThreading.runOnUi {
            KdeDynamicBindings.showMessageBox(
                kind = kind,
                owner = ownerPtr,
                title = title.ifBlank { "Notice" },
                message = message,
                buttonsMask = 0x00000400, // QMessageBox::Ok
                defaultBtn = 0x00000400
            )
            GenericUiThreading.runOnLogic { onDismissed() }
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
        if (!KdeApplicationNativeImpl.interactiveKdeGuiEnabled) {
            super.showConfirm(owner, title, message, confirmText, cancelText, type, onResult)
            return
        }
        val ownerPtr = resolveOwnerPtr(owner)
        GenericUiThreading.runOnUi {
            // QMessageBox::Yes (0x00004000) | QMessageBox::No (0x00010000)
            val clicked = KdeDynamicBindings.showMessageBox(
                kind = "question",
                owner = ownerPtr,
                title = title.ifBlank { "Confirm" },
                message = message,
                buttonsMask = 0x00014000,
                defaultBtn = 0x00004000
            )
            val accepted = clicked == 0x00004000 || clicked == 0x00000400
            GenericUiThreading.runOnLogic { onResult(accepted) }
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
        if (!KdeApplicationNativeImpl.interactiveKdeGuiEnabled) {
            super.showPrompt(owner, title, message, defaultValue, placeholder, confirmText, cancelText, onResult)
            return
        }
        val ownerPtr = resolveOwnerPtr(owner)
        GenericUiThreading.runOnUi {
            val dlg = KdeDynamicBindings.createQDialog(ownerPtr, KdeDynamicBindings.WINDOW_DIALOG)
            KdeDynamicBindings.setWindowTitle(dlg, title.ifBlank { "Input" })
            KdeDynamicBindings.setDialogModal(dlg, true)
            KdeDynamicBindings.resizeWidget(dlg, 400, if (message.isNotBlank()) 170 else 130)

            var y = 14
            if (message.isNotBlank()) {
                val lbl = KdeDynamicBindings.createQLabel(dlg)
                KdeDynamicBindings.setLabelState(lbl, message, wrap = true)
                KdeDynamicBindings.setWidgetGeometry(lbl, 16, y, 368, 36)
                KdeDynamicBindings.setWidgetVisible(lbl, true)
                y += 42
            }
            val edit = KdeDynamicBindings.createQLineEdit(dlg)
            KdeDynamicBindings.setLineEditState(edit, defaultValue, placeholder, readOnly = false, password = false)
            KdeDynamicBindings.setWidgetGeometry(edit, 16, y, 368, 32)
            KdeDynamicBindings.setWidgetVisible(edit, true)
            var currentText = defaultValue
            KdeVTableBridge.connectSignal(edit, "textEdited(QString)") { args ->
                currentText = KdeDynamicBindings.readBorrowedQString(KdeVTableBridge.readSignalArgPtr(args, 1))
            }

            val okBtn = KdeDynamicBindings.createQPushButton(dlg)
            KdeDynamicBindings.setAbstractButtonText(okBtn, confirmText.ifBlank { "OK" })
            KdeDynamicBindings.setPushButtonDefault(okBtn, true)
            KdeDynamicBindings.setWidgetGeometry(okBtn, 294, y + 46, 90, 32)
            KdeDynamicBindings.setWidgetVisible(okBtn, true)

            val cancelBtn = KdeDynamicBindings.createQPushButton(dlg)
            KdeDynamicBindings.setAbstractButtonText(cancelBtn, cancelText.ifBlank { "Cancel" })
            KdeDynamicBindings.setWidgetGeometry(cancelBtn, 196, y + 46, 90, 32)
            KdeDynamicBindings.setWidgetVisible(cancelBtn, true)

            var accepted = false
            KdeVTableBridge.connectSignal(okBtn, "clicked(bool)") {
                accepted = true
                KdeDynamicBindings.closeWidget(dlg)
            }
            KdeVTableBridge.connectSignal(edit, "returnPressed()") {
                accepted = true
                KdeDynamicBindings.closeWidget(dlg)
            }
            KdeVTableBridge.connectSignal(cancelBtn, "clicked(bool)") {
                accepted = false
                KdeDynamicBindings.closeWidget(dlg)
            }

            KdeDynamicBindings.setWidgetFocus(edit, true)
            KdeDynamicBindings.execDialog(dlg)
            val result = if (accepted) currentText else null
            KdeVTableBridge.destroyObject(dlg)
            GenericUiThreading.runOnLogic { onResult(result) }
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
        if (!KdeApplicationNativeImpl.interactiveKdeGuiEnabled || options.isEmpty()) {
            super.showChoice(owner, title, message, options, selectedIndex, onResult)
            return
        }
        val ownerPtr = resolveOwnerPtr(owner)
        GenericUiThreading.runOnUi {
            val dlg = KdeDynamicBindings.createQDialog(ownerPtr, KdeDynamicBindings.WINDOW_DIALOG)
            KdeDynamicBindings.setWindowTitle(dlg, title.ifBlank { "Choose Option" })
            KdeDynamicBindings.setDialogModal(dlg, true)
            KdeDynamicBindings.resizeWidget(dlg, 380, if (message.isNotBlank()) 170 else 130)

            var y = 14
            if (message.isNotBlank()) {
                val lbl = KdeDynamicBindings.createQLabel(dlg)
                KdeDynamicBindings.setLabelState(lbl, message, wrap = true)
                KdeDynamicBindings.setWidgetGeometry(lbl, 16, y, 348, 36)
                KdeDynamicBindings.setWidgetVisible(lbl, true)
                y += 42
            }
            val combo = KdeDynamicBindings.createQComboBox(dlg)
            val pairs = options.mapIndexed { i, opt -> i.toString() to opt }
            val initIdx = selectedIndex.coerceIn(0, options.lastIndex)
            KdeDynamicBindings.setComboBoxOptions(combo, pairs, editable = false, selectedIndex = initIdx)
            KdeDynamicBindings.setWidgetGeometry(combo, 16, y, 348, 32)
            KdeDynamicBindings.setWidgetVisible(combo, true)

            val okBtn = KdeDynamicBindings.createQPushButton(dlg)
            KdeDynamicBindings.setAbstractButtonText(okBtn, "OK")
            KdeDynamicBindings.setPushButtonDefault(okBtn, true)
            KdeDynamicBindings.setWidgetGeometry(okBtn, 274, y + 46, 90, 32)
            KdeDynamicBindings.setWidgetVisible(okBtn, true)

            val cancelBtn = KdeDynamicBindings.createQPushButton(dlg)
            KdeDynamicBindings.setAbstractButtonText(cancelBtn, "Cancel")
            KdeDynamicBindings.setWidgetGeometry(cancelBtn, 176, y + 46, 90, 32)
            KdeDynamicBindings.setWidgetVisible(cancelBtn, true)

            var accepted = false
            KdeVTableBridge.connectSignal(okBtn, "clicked(bool)") {
                accepted = true
                KdeDynamicBindings.closeWidget(dlg)
            }
            KdeVTableBridge.connectSignal(cancelBtn, "clicked(bool)") {
                accepted = false
                KdeDynamicBindings.closeWidget(dlg)
            }

            KdeDynamicBindings.execDialog(dlg)
            val chosen = if (accepted) {
                KdeDynamicBindings.getComboBoxCurrentIndex(combo).takeIf { it in options.indices }
            } else null
            KdeVTableBridge.destroyObject(dlg)
            GenericUiThreading.runOnLogic { onResult(chosen) }
        }
    }

    override fun showColorChooser(
        owner: WindowNativeImpl<*>?,
        title: String,
        initialColor: RgbaColor,
        onResult: (RgbaColor?) -> Unit
    ) {
        if (!KdeApplicationNativeImpl.interactiveKdeGuiEnabled) {
            super.showColorChooser(owner, title, initialColor, onResult)
            return
        }
        val ownerPtr = resolveOwnerPtr(owner)
        GenericUiThreading.runOnUi {
            val dlg = KdeDynamicBindings.createQColorDialog(ownerPtr, initialColor)
            KdeDynamicBindings.setWindowTitle(dlg, title.ifBlank { "Select Color" })
            var chosenColor: RgbaColor? = null
            KdeVTableBridge.connectSignal(dlg, "colorSelected(QColor)") { args ->
                chosenColor = KdeDynamicBindings.readQColorRgba(KdeVTableBridge.readSignalArgPtr(args, 1))
            }
            val accepted = KdeDynamicBindings.execDialog(dlg) == 1
            KdeVTableBridge.destroyObject(dlg)
            GenericUiThreading.runOnLogic { onResult(if (accepted) chosenColor else null) }
        }
    }

    private fun resolveOwnerPtr(owner: WindowNativeImpl<*>?) =
        (owner as? KdeWindowNativeImpl)?.nativeWidget?.qWidgetPtr
            ?: KdeNativeWindowHost.activeQWidgetPtr()
}
