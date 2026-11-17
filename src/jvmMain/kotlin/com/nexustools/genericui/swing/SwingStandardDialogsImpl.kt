package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.nativeimpl.dialog.StandardDialogsNativeImpl
import com.nexustools.genericui.nativeimpl.shell.WindowNativeImpl
import com.nexustools.genericui.style.color.RgbaColor
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.GraphicsEnvironment
import javax.swing.JColorChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Java Swing `JOptionPane` + `JColorChooser` implementation of [StandardDialogsNativeImpl].
 *
 * Presents native Swing modal dialogs when running in a non-headless AWT environment, or invokes
 * explicitly registered test responders in headless tests; fails fast with [IllegalStateException]
 * when headless without responders rather than returning silent hardcoded defaults.
 */
class SwingStandardDialogsImpl : StandardDialogsNativeImpl {

    override var isDisposed: Boolean = false
        private set

    var alertResponder: ((title: String, message: String, type: BootstrapType, okButtonText: String) -> Unit)? = null
    var confirmResponder: ((title: String, message: String, confirmText: String, cancelText: String, type: BootstrapType) -> Boolean)? = null
    var promptResponder: ((title: String, message: String, defaultValue: String, placeholder: String, confirmText: String, cancelText: String) -> String?)? = null
    var choiceResponder: ((title: String, message: String, options: List<String>, selectedIndex: Int) -> Int?)? = null
    var colorResponder: ((title: String, initialColor: RgbaColor) -> RgbaColor?)? = null

    fun resetTestResponders() {
        alertResponder = null
        confirmResponder = null
        promptResponder = null
        choiceResponder = null
        colorResponder = null
    }

    override fun showAlert(
        owner: WindowNativeImpl<*>?,
        title: String,
        message: String,
        type: BootstrapType,
        okButtonText: String,
        onDismissed: () -> Unit
    ) {
        check(!isDisposed) { "SwingStandardDialogsImpl has been disposed." }
        alertResponder?.let { responder ->
            responder(title, message, type, okButtonText)
            onDismissed()
            return
        }
        requireInteractiveEnvironment("alert")
        runOnSwingEdt {
            val parent = owner?.nativeWidget as? Component
            val dialogTitle = title.ifBlank { "Notice" }
            val msgType = resolveMessageType(type)
            val okLabel = okButtonText.ifBlank { "OK" }
            if (okLabel == "OK") {
                JOptionPane.showMessageDialog(parent, message, dialogTitle, msgType)
            } else {
                JOptionPane.showOptionDialog(
                    parent,
                    message,
                    dialogTitle,
                    JOptionPane.DEFAULT_OPTION,
                    msgType,
                    null,
                    arrayOf(okLabel),
                    okLabel
                )
            }
            onDismissed()
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
        check(!isDisposed) { "SwingStandardDialogsImpl has been disposed." }
        confirmResponder?.let { responder ->
            onResult(responder(title, message, confirmText, cancelText, type))
            return
        }
        requireInteractiveEnvironment("confirm")
        runOnSwingEdt {
            val parent = owner?.nativeWidget as? Component
            val dialogTitle = title.ifBlank { "Confirm" }
            val msgType = resolveMessageType(type)
            val okLabel = confirmText.ifBlank { "OK" }
            val cancelLabel = cancelText.ifBlank { "Cancel" }
            val confirmed = if (okLabel == "OK" && cancelLabel == "Cancel") {
                JOptionPane.showConfirmDialog(
                    parent,
                    message,
                    dialogTitle,
                    JOptionPane.OK_CANCEL_OPTION,
                    msgType
                ) == JOptionPane.OK_OPTION
            } else {
                val options = arrayOf(okLabel, cancelLabel)
                JOptionPane.showOptionDialog(
                    parent,
                    message,
                    dialogTitle,
                    JOptionPane.OK_CANCEL_OPTION,
                    msgType,
                    null,
                    options,
                    options[0]
                ) == 0
            }
            onResult(confirmed)
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
        check(!isDisposed) { "SwingStandardDialogsImpl has been disposed." }
        promptResponder?.let { responder ->
            onResult(responder(title, message, defaultValue, placeholder, confirmText, cancelText))
            return
        }
        requireInteractiveEnvironment("prompt")
        runOnSwingEdt {
            val parent = owner?.nativeWidget as? Component
            val dialogTitle = title.ifBlank { "Input" }
            val okLabel = confirmText.ifBlank { "OK" }
            val cancelLabel = cancelText.ifBlank { "Cancel" }
            val entered: String? = if (okLabel == "OK" && cancelLabel == "Cancel" && placeholder.isEmpty()) {
                JOptionPane.showInputDialog(
                    parent,
                    message,
                    dialogTitle,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    null,
                    defaultValue
                ) as? String
            } else {
                val panel = JPanel(BorderLayout(0, 6))
                if (message.isNotEmpty()) {
                    panel.add(JLabel(message), BorderLayout.NORTH)
                }
                val field = JTextField(defaultValue, 26)
                panel.add(field, BorderLayout.CENTER)
                val choice = JOptionPane.showOptionDialog(
                    parent,
                    panel,
                    dialogTitle,
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    arrayOf(okLabel, cancelLabel),
                    okLabel
                )
                if (choice == 0) field.text else null
            }
            onResult(entered)
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
        check(!isDisposed) { "SwingStandardDialogsImpl has been disposed." }
        choiceResponder?.let { responder ->
            onResult(responder(title, message, options, selectedIndex))
            return
        }
        requireInteractiveEnvironment("choice")
        if (options.isEmpty()) {
            onResult(null)
            return
        }
        runOnSwingEdt {
            val parent = owner?.nativeWidget as? Component
            val dialogTitle = title.ifBlank { "Select" }
            val optionArray = options.toTypedArray()
            val initial = optionArray.getOrNull(selectedIndex) ?: optionArray[0]
            val pickedIndex = JOptionPane.showOptionDialog(
                parent,
                message,
                dialogTitle,
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                optionArray,
                initial
            )
            onResult(pickedIndex.takeIf { it in options.indices })
        }
    }

    override fun showColorChooser(
        owner: WindowNativeImpl<*>?,
        title: String,
        initialColor: RgbaColor,
        onResult: (RgbaColor?) -> Unit
    ) {
        check(!isDisposed) { "SwingStandardDialogsImpl has been disposed." }
        colorResponder?.let { responder ->
            onResult(responder(title, initialColor))
            return
        }
        requireInteractiveEnvironment("color chooser")
        runOnSwingEdt {
            val parent = owner?.nativeWidget as? Component
            val initialAwt = Color(initialColor.r, initialColor.g, initialColor.b, initialColor.a)
            val chosen = JColorChooser.showDialog(parent, title.ifBlank { "Choose Color" }, initialAwt)
            onResult(chosen?.let { RgbaColor(it.red, it.green, it.blue, it.alpha) })
        }
    }

    override fun dispose() {
        isDisposed = true
        resetTestResponders()
    }

    private fun requireInteractiveEnvironment(kind: String) {
        if (GraphicsEnvironment.isHeadless()) {
            throw IllegalStateException(
                "Swing $kind dialog cannot be displayed in a headless environment without an explicit test responder."
            )
        }
    }

    private fun runOnSwingEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            block()
        } else {
            SwingUtilities.invokeLater(block)
        }
    }

    private fun resolveMessageType(type: BootstrapType): Int = when (type) {
        BootstrapType.DANGER -> JOptionPane.ERROR_MESSAGE
        BootstrapType.WARNING -> JOptionPane.WARNING_MESSAGE
        BootstrapType.INFO,
        BootstrapType.SUCCESS,
        BootstrapType.PRIMARY -> JOptionPane.INFORMATION_MESSAGE
        else -> JOptionPane.PLAIN_MESSAGE
    }
}
