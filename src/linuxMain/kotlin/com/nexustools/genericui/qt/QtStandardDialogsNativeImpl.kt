@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.dialog.DesktopStandardDialogsNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer

/**
 * Qt 6 (`QMessageBox`, `QInputDialog`, `QDialog` + `QComboBox`, `QColorDialog`) implementation of
 * [com.nexustools.genericui.nativeimpl.dialog.StandardDialogsNativeImpl].
 */
class QtStandardDialogsNativeImpl : DesktopStandardDialogsNativeImpl(
    backendToolkit = "Qt6 (QMessageBox / QInputDialog / QColorDialog)"
) {
    override fun showAlert(
        owner: WindowNativeImpl<*>?,
        title: String,
        message: String,
        type: BootstrapType,
        okButtonText: String,
        onDismissed: () -> Unit
    ) {
        if (!QtApplicationNativeImpl.interactiveQtGuiEnabled || !Qt6DynamicBindings.ensureApplication()) {
            super.showAlert(owner, title, message, type, okButtonText, onDismissed)
            return
        }
        val parentPtr = resolveParentQWidget(owner)
        val icon = mapBootstrapToQtIcon(type)
        GenericUiThreading.runOnUi {
            Qt6DynamicBindings.execMessageBox(
                parent = parentPtr,
                title = title.ifBlank { "Notice" },
                text = message.ifBlank { title.ifBlank { "Notice" } },
                informativeText = "",
                icon = icon,
                buttonsMask = Qt6DynamicBindings.QMSG_BTN_OK,
                defaultButton = Qt6DynamicBindings.QMSG_BTN_OK
            )
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
        if (!QtApplicationNativeImpl.interactiveQtGuiEnabled || !Qt6DynamicBindings.ensureApplication()) {
            super.showConfirm(owner, title, message, confirmText, cancelText, type, onResult)
            return
        }
        val parentPtr = resolveParentQWidget(owner)
        val icon = mapBootstrapToQtIcon(type)
        GenericUiThreading.runOnUi {
            val res = Qt6DynamicBindings.execMessageBox(
                parent = parentPtr,
                title = title.ifBlank { "Confirm" },
                text = message.ifBlank { title.ifBlank { "Confirm" } },
                informativeText = if (confirmText.isNotBlank() || cancelText.isNotBlank()) {
                    "${confirmText.ifBlank { "OK" }} / ${cancelText.ifBlank { "Cancel" }}"
                } else {
                    ""
                },
                icon = icon,
                buttonsMask = Qt6DynamicBindings.QMSG_BTN_OK or Qt6DynamicBindings.QMSG_BTN_CANCEL,
                defaultButton = Qt6DynamicBindings.QMSG_BTN_OK
            )
            val confirmed = res == Qt6DynamicBindings.QMSG_BTN_OK || res == Qt6DynamicBindings.QMSG_BTN_YES
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
        if (!QtApplicationNativeImpl.interactiveQtGuiEnabled || !Qt6DynamicBindings.ensureApplication()) {
            super.showPrompt(owner, title, message, defaultValue, placeholder, confirmText, cancelText, onResult)
            return
        }
        val parentPtr = resolveParentQWidget(owner)
        val promptLabel = message.ifBlank { placeholder.ifBlank { title.ifBlank { "Value:" } } }
        GenericUiThreading.runOnUi {
            val value = Qt6DynamicBindings.execInputPrompt(
                parent = parentPtr,
                title = title.ifBlank { "Input" },
                label = promptLabel,
                defaultValue = defaultValue
            )
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
        if (!QtApplicationNativeImpl.interactiveQtGuiEnabled || !Qt6DynamicBindings.ensureApplication()) {
            super.showChoice(owner, title, message, options, selectedIndex, onResult)
            return
        }
        val parentPtr = resolveParentQWidget(owner)
        GenericUiThreading.runOnUi {
            val chosen = Qt6DynamicBindings.execChoiceDialog(
                parent = parentPtr,
                title = title.ifBlank { "Choose Option" },
                message = message,
                options = options,
                selectedIndex = selectedIndex
            )
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
        if (!QtApplicationNativeImpl.interactiveQtGuiEnabled || !Qt6DynamicBindings.ensureApplication()) {
            super.showColorChooser(owner, title, initialColor, onResult)
            return
        }
        val parentPtr = resolveParentQWidget(owner)
        GenericUiThreading.runOnUi {
            val chosen = Qt6DynamicBindings.execColorDialog(
                parent = parentPtr,
                title = title.ifBlank { "Choose Color" },
                initial = initialColor
            )
            GenericUiThreading.runOnLogic {
                onResult(chosen)
            }
        }
    }

    private fun resolveParentQWidget(owner: WindowNativeImpl<*>?): COpaquePointer? =
        (owner as? QtWindowNativeImpl)?.let { QtNativeWindowHost.sessionForWindow(it)?.qWidget }
            ?: QtNativeWindowHost.activeQWidgetPtr()

    private fun mapBootstrapToQtIcon(type: BootstrapType): Int = when (type) {
        BootstrapType.DANGER -> Qt6DynamicBindings.QMSG_ICON_CRITICAL
        BootstrapType.WARNING -> Qt6DynamicBindings.QMSG_ICON_WARNING
        BootstrapType.INFO, BootstrapType.PRIMARY, BootstrapType.SUCCESS -> Qt6DynamicBindings.QMSG_ICON_INFO
        else -> Qt6DynamicBindings.QMSG_ICON_NO_ICON
    }
}
