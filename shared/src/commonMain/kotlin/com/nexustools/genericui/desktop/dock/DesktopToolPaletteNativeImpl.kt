package com.nexustools.genericui.desktop.dock

import com.nexustools.genericui.component.dock.ToolPaletteItem
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.dock.ToolPaletteNativeImpl
import com.nexustools.genericui.style.RgbaColor

/**
 * Shared desktop base implementation of [ToolPaletteNativeImpl].
 */
open class DesktopToolPaletteNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "tool-palette"), ToolPaletteNativeImpl<W> {

    protected var columnCount: Int = 2
    protected var toolsList: List<ToolPaletteItem> = emptyList()
    protected var currentToolId: String? = null
    protected var fgColor: RgbaColor = RgbaColor.BLACK
    protected var bgColor: RgbaColor = RgbaColor.WHITE
    protected var onToolSelectedListener: ((String) -> Unit)? = null
    protected var onColorsSwappedListener: ((RgbaColor, RgbaColor) -> Unit)? = null

    override fun setColumns(columns: Int) {
        columnCount = columns.coerceAtLeast(1)
        nativeWidget.attributes["columns"] = columnCount
    }

    override fun setTools(tools: List<ToolPaletteItem>, activeToolId: String?) {
        toolsList = tools
        currentToolId = activeToolId
        nativeWidget.attributes["tools"] = tools
    }

    override fun setActiveToolId(toolId: String?) {
        currentToolId = toolId
        if (toolId != null) {
            onToolSelectedListener?.invoke(toolId)
        }
    }

    override fun setForegroundColor(color: RgbaColor) {
        fgColor = color
    }

    override fun setBackgroundColor(color: RgbaColor) {
        bgColor = color
    }

    override fun setOnToolSelected(listener: ((String) -> Unit)?) {
        onToolSelectedListener = listener
    }

    override fun setOnColorsSwapped(listener: ((RgbaColor, RgbaColor) -> Unit)?) {
        onColorsSwappedListener = listener
    }
}
