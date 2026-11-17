package com.nexustools.genericui.swing

import com.nexustools.genericui.component.dock.ToolPaletteItem
import com.nexustools.genericui.nativeimpl.dock.ToolPaletteNativeImpl
import com.nexustools.genericui.style.color.RgbaColor
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JToggleButton

/**
 * Java Swing studio tool palette implementation of [ToolPaletteNativeImpl], rendering a tool button
 * grid plus interactive foreground/background color swatches and swap button.
 */
class SwingToolPaletteImpl : SwingBaseContainerNativeImpl(
    JPanel(BorderLayout(0, 6)),
    "tool-palette"
), ToolPaletteNativeImpl<JComponent> {

    private var columnCount: Int = 2
    private val toolsPanel = JPanel(GridLayout(0, 2, 4, 4)).apply { isOpaque = false }
    private val toolGroup = ButtonGroup()
    private val toolButtons = linkedMapOf<String, JToggleButton>()

    private var fgColor: RgbaColor = RgbaColor(0, 0, 0, 255)
    private var bgColor: RgbaColor = RgbaColor(255, 255, 255, 255)

    val foregroundSwatchButton: JButton = createSwatchButton(fgColor)
    val backgroundSwatchButton: JButton = createSwatchButton(bgColor)
    val swapColorsButton: JButton = JButton("⇄").apply {
        margin = Insets(2, 6, 2, 6)
        isFocusable = false
    }

    private var activeToolIdVal: String? = null
    private var onToolSelectedListener: ((String) -> Unit)? = null
    private var onColorsSwappedListener: ((RgbaColor, RgbaColor) -> Unit)? = null

    init {
        val root = nativeWidget as JPanel
        root.add(toolsPanel, BorderLayout.CENTER)
        val swatchBar = JPanel(FlowLayout(FlowLayout.CENTER, 6, 2)).apply { isOpaque = false }
        swatchBar.add(foregroundSwatchButton)
        swatchBar.add(swapColorsButton)
        swatchBar.add(backgroundSwatchButton)
        root.add(swatchBar, BorderLayout.SOUTH)

        swapColorsButton.addActionListener {
            swapColors()
        }
        foregroundSwatchButton.addActionListener {
            swapColors()
        }
        backgroundSwatchButton.addActionListener {
            swapColors()
        }
    }

    override fun setColumns(columns: Int) {
        columnCount = columns.coerceAtLeast(1)
        toolsPanel.layout = GridLayout(0, columnCount, 4, 4)
        toolsPanel.revalidate()
        toolsPanel.repaint()
    }

    override fun setTools(tools: List<ToolPaletteItem>, activeToolId: String?) {
        toolsPanel.removeAll()
        for (btn in toolButtons.values) {
            toolGroup.remove(btn)
        }
        toolButtons.clear()

        activeToolIdVal = activeToolId ?: tools.firstOrNull()?.id
        for (tool in tools) {
            val toggle = JToggleButton(tool.name).apply {
                isSelected = (tool.id == activeToolIdVal)
                margin = Insets(4, 6, 4, 6)
                tool.tooltip?.takeIf { it.isNotEmpty() }?.let { toolTipText = it }
                addActionListener {
                    if (isSelected) {
                        activeToolIdVal = tool.id
                        onToolSelectedListener?.invoke(tool.id)
                    }
                }
            }
            loadToolIcon(toggle, tool.iconSvgOrPath)
            toolGroup.add(toggle)
            toolButtons[tool.id] = toggle
            toolsPanel.add(toggle)
        }
        toolsPanel.revalidate()
        toolsPanel.repaint()
    }

    override fun setActiveToolId(toolId: String?) {
        activeToolIdVal = toolId
        if (toolId != null) {
            toolButtons[toolId]?.isSelected = true
            onToolSelectedListener?.invoke(toolId)
        } else {
            toolGroup.clearSelection()
        }
    }

    override fun setForegroundColor(color: RgbaColor) {
        fgColor = color
        applySwatchColor(foregroundSwatchButton, color)
    }

    override fun setBackgroundColor(color: RgbaColor) {
        bgColor = color
        applySwatchColor(backgroundSwatchButton, color)
    }

    override fun setOnToolSelected(listener: ((String) -> Unit)?) {
        onToolSelectedListener = listener
    }

    override fun setOnColorsSwapped(listener: ((RgbaColor, RgbaColor) -> Unit)?) {
        onColorsSwappedListener = listener
    }

    fun toolButtonForId(toolId: String): JToggleButton? = toolButtons[toolId]

    fun getForegroundColor(): RgbaColor = fgColor

    fun getBackgroundColor(): RgbaColor = bgColor

    private fun swapColors() {
        val prevFg = fgColor
        fgColor = bgColor
        bgColor = prevFg
        applySwatchColor(foregroundSwatchButton, fgColor)
        applySwatchColor(backgroundSwatchButton, bgColor)
        onColorsSwappedListener?.invoke(fgColor, bgColor)
    }

    private fun createSwatchButton(initial: RgbaColor): JButton = JButton().apply {
        preferredSize = Dimension(24, 24)
        minimumSize = Dimension(24, 24)
        isOpaque = true
        isContentAreaFilled = true
        border = BorderFactory.createLineBorder(Color.DARK_GRAY)
        background = Color(initial.r, initial.g, initial.b, initial.a)
    }

    private fun applySwatchColor(button: JButton, color: RgbaColor) {
        button.background = Color(
            color.r.coerceIn(0, 255),
            color.g.coerceIn(0, 255),
            color.b.coerceIn(0, 255),
            color.a.coerceIn(0, 255)
        )
        button.repaint()
    }

    private fun loadToolIcon(button: JToggleButton, iconSpec: String) {
        val trimmed = iconSpec.trim()
        if (trimmed.isEmpty()) return
        if (!trimmed.contains("://") && !trimmed.contains("/") && !trimmed.endsWith(".png", true) &&
            !trimmed.endsWith(".jpg", true) && !trimmed.endsWith(".svg", true)
        ) {
            return
        }
        SwingControlTintRenderUtil.loadScaledIconAsync(trimmed, 18) { loaded ->
            button.icon = loaded
            button.revalidate()
            button.repaint()
        }
    }
}
