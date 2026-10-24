package com.nexustools.genericui.swing

import com.nexustools.genericui.component.dock.ToolPaletteItem
import com.nexustools.genericui.nativeimpl.dock.ToolPaletteNativeImpl
import com.nexustools.genericui.style.RgbaColor
import java.awt.GridLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Java Swing `GridLayout` implementation of [ToolPaletteNativeImpl].
 */
class SwingToolPaletteImpl : SwingBaseContainerNativeImpl(JPanel(GridLayout(0, 2, 4, 4)), "tool-palette"), ToolPaletteNativeImpl<JComponent> {
    private var onToolSelectedListener: ((String) -> Unit)? = null

    override fun setColumns(columns: Int) {
        nativeWidget.layout = GridLayout(0, columns.coerceAtLeast(1), 4, 4)
    }

    override fun setTools(tools: List<ToolPaletteItem>, activeToolId: String?) {}
    override fun setActiveToolId(toolId: String?) {
        if (toolId != null) onToolSelectedListener?.invoke(toolId)
    }
    override fun setForegroundColor(color: RgbaColor) {}
    override fun setBackgroundColor(color: RgbaColor) {}
    override fun setOnToolSelected(listener: ((String) -> Unit)?) { onToolSelectedListener = listener }
    override fun setOnColorsSwapped(listener: ((RgbaColor, RgbaColor) -> Unit)?) {}
}
