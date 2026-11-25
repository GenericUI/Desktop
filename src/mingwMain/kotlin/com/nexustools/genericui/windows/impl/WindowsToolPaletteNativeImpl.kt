@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.component.dock.ToolPaletteItem
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.dock.DesktopToolPaletteNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import com.nexustools.genericui.windows.Win32StyledWidgetHelper
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.CreateSolidBrush
import platform.windows.DT_CENTER
import platform.windows.DT_END_ELLIPSIS
import platform.windows.DT_NOPREFIX
import platform.windows.DT_SINGLELINE
import platform.windows.DT_VCENTER
import platform.windows.DeleteObject
import platform.windows.DrawTextW
import platform.windows.FillRect
import platform.windows.FrameRect
import platform.windows.InvalidateRect
import platform.windows.RECT
import platform.windows.SelectObject
import platform.windows.SetBkMode
import platform.windows.SetTextColor
import platform.windows.TRANSPARENT
import platform.windows.WM_LBUTTONUP

/**
 * Retained Win32 tool palette grid (`"GenericUIToolPaletteW"`), rendering selectable tool buttons
 * and foreground/background color swatches with direct hit-testing.
 */
class WindowsToolPaletteNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIToolPaletteW")
) : DesktopToolPaletteNativeImpl<Win32HwndHandle>(widget) {

    init {
        layoutController = Win32RetainedControlSupport.initContainer(
            container = this,
            handle = nativeWidget,
            win32ClassName = "GenericUIToolPaletteW"
        )
        nativeWidget.customPaintCallback = { hwnd, hdc, w, h ->
            paintToolPalette(hwnd, hdc, w, h)
        }
        nativeWidget.customMessageCallback = { _, msg, _, lParam ->
            if (msg.toInt() == WM_LBUTTONUP) {
                val x = (lParam and 0xFFFFL).toShort().toInt()
                val y = ((lParam shr 16) and 0xFFFFL).toShort().toInt()
                handlePaletteClick(x, y)
            }
            null
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    private fun handlePaletteClick(x: Int, y: Int) {
        val cols = columnCount.coerceAtLeast(1)
        val cellSize = 36
        val pad = 6
        if (toolsList.isNotEmpty()) {
            val col = (x - pad) / cellSize
            val row = (y - pad) / cellSize
            if (col in 0 until cols && row >= 0) {
                val idx = row * cols + col
                if (idx in toolsList.indices) {
                    setActiveToolId(toolsList[idx].id)
                    return
                }
            }
        }
        val rows = ((toolsList.size + cols - 1) / cols).coerceAtLeast(1)
        val swatchTop = pad + rows * cellSize + 8
        if (y in swatchTop..(swatchTop + 34)) {
            dispatchSwapColors()
        }
    }

    private fun paintToolPalette(hwnd: platform.windows.HWND, hdc: platform.windows.HDC, w: Int, h: Int) {
        val dark = Win32NativeWindowHost.isEffectivelyDarkTheme()
        Win32StyledWidgetHelper.renderStyledSpecToHdc(
            hwnd = hwnd,
            hdc = hdc,
            width = w,
            height = h,
            spec = StyledVisualSpec(
                backgroundColor = if (dark) RgbaColor(36, 38, 44) else RgbaColor(246, 248, 250),
                borderColor = if (dark) RgbaColor(62, 66, 74) else RgbaColor(218, 222, 228),
                borderWidthPx = 1f,
                borderRadiusPx = 4f
            ),
            isDark = dark
        )

        val cols = columnCount.coerceAtLeast(1)
        val cellSize = 34
        val pad = 6
        val oldFont = nativeWidget.fontHandle?.let { SelectObject(hdc, it) }
        val prevBk = SetBkMode(hdc, TRANSPARENT)
        try {
            memScoped {
                val rc = alloc<RECT>()
                for ((idx, tool) in toolsList.withIndex()) {
                    val col = idx % cols
                    val row = idx / cols
                    val x = pad + col * (cellSize + 2)
                    val y = pad + row * (cellSize + 2)
                    rc.left = x
                    rc.top = y
                    rc.right = x + cellSize
                    rc.bottom = y + cellSize
                    val selected = tool.id == currentToolId
                    val fillBrush = CreateSolidBrush(
                        if (selected) 0x00FD6E0Du else if (dark) 0x0034302Cu else 0x00EAECEEu
                    )
                    if (fillBrush != null) {
                        FillRect(hdc, rc.ptr, fillBrush)
                        DeleteObject(fillBrush)
                    }
                    SetTextColor(
                        hdc,
                        if (selected) 0x00FFFFFFu else if (dark) 0x00EAECEFu else 0x00242830u
                    )
                    val glyph = tool.name.take(3)
                    DrawTextW(hdc, glyph, -1, rc.ptr, (DT_CENTER or DT_VCENTER or DT_SINGLELINE or DT_END_ELLIPSIS or DT_NOPREFIX).toUInt())
                }

                // Draw BG & FG swatch indicator below tools
                val rows = ((toolsList.size + cols - 1) / cols).coerceAtLeast(1)
                val swatchTop = pad + rows * (cellSize + 2) + 8
                if (swatchTop + 26 <= h) {
                    val bgRect = rc
                    bgRect.left = pad + 12
                    bgRect.top = swatchTop + 8
                    bgRect.right = pad + 32
                    bgRect.bottom = swatchTop + 28
                    val bgSwatch = CreateSolidBrush(toColorRef(bgColor))
                    if (bgSwatch != null) {
                        FillRect(hdc, bgRect.ptr, bgSwatch)
                        DeleteObject(bgSwatch)
                    }
                    val fgRect = rc
                    fgRect.left = pad
                    fgRect.top = swatchTop
                    fgRect.right = pad + 20
                    fgRect.bottom = swatchTop + 20
                    val fgSwatch = CreateSolidBrush(toColorRef(fgColor))
                    val rimBrush = CreateSolidBrush(0x00808080u)
                    if (fgSwatch != null) {
                        FillRect(hdc, fgRect.ptr, fgSwatch)
                        DeleteObject(fgSwatch)
                    }
                    if (rimBrush != null) {
                        FrameRect(hdc, fgRect.ptr, rimBrush)
                        DeleteObject(rimBrush)
                    }
                }
            }
        } finally {
            SetBkMode(hdc, prevBk)
            if (oldFont != null) SelectObject(hdc, oldFont)
        }
    }

    private fun toColorRef(c: RgbaColor): UInt =
        ((c.b and 0xFF) shl 16 or ((c.g and 0xFF) shl 8) or (c.r and 0xFF)).toUInt()

    override fun setTools(tools: List<ToolPaletteItem>, activeToolId: String?) {
        super.setTools(tools, activeToolId)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun setActiveToolId(toolId: String?) {
        super.setActiveToolId(toolId)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun setForegroundColor(color: RgbaColor) {
        super.setForegroundColor(color)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun setBackgroundColor(color: RgbaColor) {
        super.setBackgroundColor(color)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val cols = columnCount.coerceAtLeast(1)
        val rows = ((toolsList.size + cols - 1) / cols).coerceAtLeast(1)
        return Size(cols * 36 + 14, rows * 36 + 52)
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        Win32RetainedControlSupport.onContainerChildAdded(this, nativeWidget, childImpl)
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        Win32RetainedControlSupport.onContainerChildRemoved(this, nativeWidget, childImpl)
    }

    override fun onChildrenClearedNative() {
        Win32RetainedControlSupport.onContainerChildrenCleared(nativeWidget, childImpls.toList())
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        Win32RetainedControlSupport.onContainerBoundsChanged(this, nativeWidget, rect)
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        Win32RetainedControlSupport.onVisibilityChanged(nativeWidget, visible)
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        Win32RetainedControlSupport.onEnabledChanged(nativeWidget, enabled)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        Win32RetainedControlSupport.onOpacityChanged(nativeWidget, opacity)
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        Win32RetainedControlSupport.onTooltipChanged(nativeWidget, tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        Win32RetainedControlSupport.onCursorChanged(nativeWidget, cursor)
    }

    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        Win32RetainedControlSupport.onPointerEventsChanged(nativeWidget, pointerEventsNone)
    }

    override fun onNativeZIndexChanged(zIndex: Int) {
        Win32RetainedControlSupport.onZIndexChanged(nativeWidget, zIndex)
    }

    override fun onNativeStyleSynced() {
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
