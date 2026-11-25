package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.form.TextInputNativeImpl
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

/**
 * Java Swing implementation of [TextInputNativeImpl] supporting single-line [JTextField], masked
 * [JPasswordField], scrollable multiline [JTextArea], optional field label header, translucent
 * placeholder painting, and live [DocumentListener] change callbacks.
 */
class SwingTextInputImpl(
    initialMultiline: Boolean = false
) : SwingBaseNativeImpl(JPanel(BorderLayout(0, 4)).apply { isOpaque = false }, "form-control"), TextInputNativeImpl<JComponent> {

    private val rootPanel: JPanel = nativeWidget as JPanel
    private val headerLabel: JLabel = JLabel().apply { isVisible = false }

    private var placeholderVal: String = ""
    private var labelText: String? = null
    private var readOnlyState: Boolean = false
    private var passwordModeState: Boolean = false
    private var multilineState: Boolean = initialMultiline
    private var focusedStateVal: Boolean = false
    private var suppressDocumentEvents: Boolean = false
    private var lastObservedText: String = ""
    private var onValueChangedListener: ((String) -> Unit)? = null
    private var onSubmitListener: ((String) -> Unit)? = null

    var activeEditor: JTextComponent = createEditor(multiline = initialMultiline, password = false)
        private set

    private val documentListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent?) = dispatchDocumentChange()
        override fun removeUpdate(e: DocumentEvent?) = dispatchDocumentChange()
        override fun changedUpdate(e: DocumentEvent?) = dispatchDocumentChange()
    }

    init {
        rootPanel.add(headerLabel, BorderLayout.NORTH)
        mountEditor(activeEditor)
    }

    override fun setValue(value: String) {
        if (getValue() == value) return
        lastObservedText = value
        suppressDocumentEvents = true
        try {
            activeEditor.text = value
        } finally {
            suppressDocumentEvents = false
        }
        onValueChangedListener?.invoke(value)
    }

    override fun getValue(): String = activeEditor.text ?: ""

    override fun setPlaceholder(placeholder: String) {
        placeholderVal = placeholder
        nativeWidget.putClientProperty("placeholder", placeholder)
        activeEditor.repaint()
    }

    override fun getPlaceholder(): String = placeholderVal

    override fun setLabel(label: String?) {
        val normalized = label?.takeIf { it.isNotBlank() }
        labelText = normalized
        nativeWidget.putClientProperty("label", normalized)
        headerLabel.text = normalized ?: ""
        headerLabel.isVisible = normalized != null
        rootPanel.revalidate()
        rootPanel.repaint()
    }

    override fun getLabel(): String? = labelText

    override fun setReadOnly(readOnly: Boolean) {
        readOnlyState = readOnly
        activeEditor.isEditable = !readOnly
    }

    override fun setPasswordMode(password: Boolean) {
        if (passwordModeState == password) return
        passwordModeState = password
        nativeWidget.putClientProperty("passwordMode", password)
        if (multilineState) {
            // Password masking only applies cleanly to single-line fields; retain state for mode switches
            return
        }
        val currentEditor = activeEditor
        if (currentEditor is JPasswordField) {
            currentEditor.echoChar = if (password) '\u2022' else 0.toChar()
            currentEditor.repaint()
        } else {
            rebuildEditor()
        }
    }

    override fun setMultiline(multiline: Boolean) {
        if (multilineState == multiline) return
        multilineState = multiline
        nativeWidget.putClientProperty("multiline", multiline)
        rebuildEditor()
    }

    override fun setOnValueChanged(listener: ((String) -> Unit)?) {
        onValueChangedListener = listener
    }

    override fun setOnSubmit(listener: ((String) -> Unit)?) {
        onSubmitListener = listener
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        activeEditor.isEnabled = enabled
        headerLabel.isEnabled = enabled
    }

    override fun setFocusedState(focused: Boolean) {
        val effective = focused && isEnabled() && isEffectivelyFocusableInSwing()
        focusedStateVal = effective
        com.nexustools.genericui.event.InputFocusManager.isNativeTextInputFocused = effective
        if (effective && !activeEditor.isFocusOwner) {
            activeEditor.requestFocusInWindow()
        }
    }

    override fun isFocusedState(): Boolean = focusedStateVal || activeEditor.isFocusOwner

    fun requestFocus(): Boolean = isEffectivelyFocusableInSwing() && isEnabled() && activeEditor.requestFocusInWindow()

    private fun dispatchDocumentChange() {
        if (suppressDocumentEvents) return
        val current = activeEditor.text ?: ""
        if (current != lastObservedText) {
            lastObservedText = current
            onValueChangedListener?.invoke(current)
        }
    }

    private fun rebuildEditor() {
        val preservedText = getValue()
        val preservedEnabled = activeEditor.isEnabled
        activeEditor.document.removeDocumentListener(documentListener)
        val centerComponent = (rootPanel.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)
        if (centerComponent != null) {
            rootPanel.remove(centerComponent)
        }
        val next = createEditor(multiline = multilineState, password = passwordModeState)
        next.isEditable = !readOnlyState
        next.isEnabled = preservedEnabled
        suppressDocumentEvents = true
        try {
            next.text = preservedText
        } finally {
            suppressDocumentEvents = false
        }
        activeEditor = next
        mountEditor(next)
        if (!isEffectivelyFocusableInSwing()) {
            applyAwtFocusableRecursive(rootPanel, false)
        }
        rootPanel.revalidate()
        rootPanel.repaint()
    }

    private fun mountEditor(editor: JTextComponent) {
        editor.document.addDocumentListener(documentListener)
        editor.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent?) {
                if (!isEffectivelyFocusableInSwing()) {
                    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner()
                    setFocusedState(false)
                    return
                }
                com.nexustools.genericui.event.InputFocusManager.isNativeTextInputFocused = true
                setFocusedState(true)
            }

            override fun focusLost(e: FocusEvent?) {
                com.nexustools.genericui.event.InputFocusManager.isNativeTextInputFocused = false
                setFocusedState(false)
            }
        })
        if (editor is JTextField) {
            editor.addActionListener {
                onSubmitListener?.invoke(editor.text ?: "")
            }
        }
        val content: JComponent = if (editor is JTextArea) {
            JScrollPane(editor).apply {
                border = BorderFactory.createEmptyBorder()
            }
        } else {
            editor
        }
        rootPanel.add(content, BorderLayout.CENTER)
    }

    private fun createEditor(multiline: Boolean, password: Boolean): JTextComponent = when {
        multiline -> object : JTextArea(3, 20) {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                paintPlaceholderIfEmpty(this, g)
            }
        }.apply {
            lineWrap = true
            wrapStyleWord = true
        }
        password -> object : JPasswordField(20) {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                paintPlaceholderIfEmpty(this, g)
            }
        }.apply {
            echoChar = '\u2022'
        }
        else -> object : JTextField(20) {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                paintPlaceholderIfEmpty(this, g)
            }
        }
    }

    private fun paintPlaceholderIfEmpty(comp: JTextComponent, g: Graphics) {
        val hint = placeholderVal
        if (hint.isEmpty() || !comp.text.isNullOrEmpty()) return
        val g2 = g.create() as? Graphics2D ?: return
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val fg = comp.foreground ?: Color.DARK_GRAY
            g2.color = Color(fg.red, fg.green, fg.blue, 128)
            g2.font = comp.font
            val insets = comp.insets
            val fm = g2.fontMetrics
            val baselineY = if (comp is JTextArea) {
                insets.top + fm.ascent
            } else {
                val innerH = (comp.height - insets.top - insets.bottom).coerceAtLeast(fm.height)
                insets.top + ((innerH - fm.height) / 2) + fm.ascent
            }
            g2.drawString(hint, insets.left + 2, baselineY)
        } finally {
            g2.dispose()
        }
    }
}
