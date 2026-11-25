@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopTableNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toLong
import kotlinx.cinterop.wcstr
import platform.windows.InvalidateRect
import platform.windows.LVCF_TEXT
import platform.windows.LVCF_WIDTH
import platform.windows.LVCOLUMNW
import platform.windows.LVIF_TEXT
import platform.windows.LVITEMW
import platform.windows.LVM_DELETEALLITEMS
import platform.windows.LVM_DELETECOLUMN
import platform.windows.LVM_INSERTCOLUMNW
import platform.windows.LVM_INSERTITEMW
import platform.windows.LVM_SETBKCOLOR
import platform.windows.LVM_SETCOLUMNWIDTH
import platform.windows.LVM_SETEXTENDEDLISTVIEWSTYLE
import platform.windows.LVM_SETITEMTEXTW
import platform.windows.LVM_SETTEXTBKCOLOR
import platform.windows.LVM_SETTEXTCOLOR
import platform.windows.LVS_EX_DOUBLEBUFFER
import platform.windows.LVS_EX_FULLROWSELECT
import platform.windows.LVS_EX_GRIDLINES
import platform.windows.LVS_REPORT
import platform.windows.LVS_SHOWSELALWAYS
import platform.windows.LVS_SINGLESEL
import platform.windows.SendMessageW
import platform.windows.WS_BORDER
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE
import kotlin.math.roundToInt

/**
 * Retained Win32 ComCtl32 `"SysListView32"` (`LVS_REPORT`) implementation for `Table` / `DataGrid`,
 * supporting proportional `columnWeights`, extended double-buffered row selection, and gridlines.
 */
class WindowsTableNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("SysListView32")
) : DesktopTableNativeImpl<Win32HwndHandle>(widget) {

    private var columnWeights: FloatArray = FloatArray(0)

    init {
        nativeWidget.attributes["nativeImpl"] = this
        val hwnd = Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "SysListView32",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_BORDER or WS_CLIPSIBLINGS or
                LVS_REPORT or LVS_SINGLESEL or LVS_SHOWSELALWAYS,
            initialWidth = 420,
            initialHeight = 220,
            installSubclass = true
        )
        val exFlags = (LVS_EX_FULLROWSELECT or LVS_EX_GRIDLINES or LVS_EX_DOUBLEBUFFER).toLong()
        SendMessageW(hwnd, LVM_SETEXTENDEDLISTVIEWSTYLE.toUInt(), exFlags.toULong(), exFlags)
        syncThemeColors()
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    override fun setColumnWeights(weights: FloatArray) {
        columnWeights = weights.copyOf()
        nativeWidget.attributes["columnWeights"] = columnWeights
        updateColumnWidths()
    }

    fun getColumnWeights(): FloatArray = columnWeights.copyOf()

    override fun setHeaders(headers: List<String>) {
        super.setHeaders(headers)
        rebuildTableColumnsAndRows()
    }

    override fun setRows(rows: List<List<String>>) {
        super.setRows(rows)
        rebuildRowsOnly()
    }

    private fun computeColumnWidths(colCount: Int, totalWidth: Int): IntArray {
        if (colCount <= 0) return IntArray(0)
        val avail = (totalWidth - 8).coerceAtLeast(colCount * 48)
        val out = IntArray(colCount)
        if (columnWeights.size >= colCount) {
            val sum = (0 until colCount).sumOf { columnWeights[it].coerceAtLeast(0.05f).toDouble() }.toFloat()
            var assigned = 0
            for (c in 0 until colCount) {
                val w = if (c == colCount - 1) {
                    (avail - assigned).coerceAtLeast(40)
                } else {
                    ((avail * columnWeights[c].coerceAtLeast(0.05f)) / sum).roundToInt().coerceAtLeast(40)
                }
                out[c] = w
                assigned += w
            }
        } else {
            val even = (avail / colCount).coerceAtLeast(48)
            for (c in 0 until colCount) {
                out[c] = if (c == colCount - 1) (avail - even * (colCount - 1)).coerceAtLeast(48) else even
            }
        }
        return out
    }

    private fun effectiveColCount(): Int =
        maxOf(headersList.size, rowsList.maxOfOrNull { it.size } ?: 0)

    private fun rebuildTableColumnsAndRows() {
        val hwnd = nativeWidget.hwnd ?: return
        SendMessageW(hwnd, LVM_DELETEALLITEMS.toUInt(), 0uL, 0L)
        while (SendMessageW(hwnd, LVM_DELETECOLUMN.toUInt(), 0uL, 0L) != 0L) {
            // Remove all existing columns before re-inserting updated headers
        }
        val colCount = effectiveColCount()
        if (colCount <= 0) return
        val totalW = nativeWidget.bounds.width.takeIf { it > 40 } ?: 420
        val widths = computeColumnWidths(colCount, totalW)

        memScoped {
            val lvc = alloc<LVCOLUMNW>()
            lvc.mask = (LVCF_TEXT or LVCF_WIDTH).toUInt()
            for (c in 0 until colCount) {
                val title = headersList.getOrNull(c) ?: "Column ${c + 1}"
                lvc.cx = widths[c]
                lvc.pszText = title.wcstr.ptr
                SendMessageW(hwnd, LVM_INSERTCOLUMNW.toUInt(), c.toULong(), lvc.ptr.toLong())
            }
        }
        rebuildRowsOnly()
    }

    private fun rebuildRowsOnly() {
        val hwnd = nativeWidget.hwnd ?: return
        SendMessageW(hwnd, LVM_DELETEALLITEMS.toUInt(), 0uL, 0L)
        if (rowsList.isEmpty()) return
        if (effectiveColCount() > 0 && headersList.isEmpty()) {
            val colCount = effectiveColCount()
            val totalW = nativeWidget.bounds.width.takeIf { it > 40 } ?: 420
            val widths = computeColumnWidths(colCount, totalW)
            memScoped {
                val lvc = alloc<LVCOLUMNW>()
                lvc.mask = (LVCF_TEXT or LVCF_WIDTH).toUInt()
                for (c in 0 until colCount) {
                    lvc.cx = widths[c]
                    lvc.pszText = "".wcstr.ptr
                    SendMessageW(hwnd, LVM_INSERTCOLUMNW.toUInt(), c.toULong(), lvc.ptr.toLong())
                }
            }
        }
        memScoped {
            val lvi = alloc<LVITEMW>()
            for ((rowIdx, row) in rowsList.withIndex()) {
                val firstText = row.firstOrNull().orEmpty()
                lvi.mask = LVIF_TEXT.toUInt()
                lvi.iItem = rowIdx
                lvi.iSubItem = 0
                lvi.pszText = firstText.wcstr.ptr
                val insertedIdx = SendMessageW(hwnd, LVM_INSERTITEMW.toUInt(), 0uL, lvi.ptr.toLong()).toInt()
                if (insertedIdx >= 0) {
                    for (c in 1 until row.size) {
                        lvi.iItem = insertedIdx
                        lvi.iSubItem = c
                        lvi.pszText = row[c].wcstr.ptr
                        SendMessageW(hwnd, LVM_SETITEMTEXTW.toUInt(), insertedIdx.toULong(), lvi.ptr.toLong())
                    }
                }
            }
        }
    }

    private fun updateColumnWidths() {
        val hwnd = nativeWidget.hwnd ?: return
        val colCount = effectiveColCount()
        if (colCount <= 0) return
        val totalW = nativeWidget.bounds.width.takeIf { it > 40 } ?: 420
        val widths = computeColumnWidths(colCount, totalW)
        for (c in 0 until colCount) {
            SendMessageW(hwnd, LVM_SETCOLUMNWIDTH.toUInt(), c.toULong(), widths[c].toLong())
        }
    }

    private fun syncThemeColors() {
        val hwnd = nativeWidget.hwnd ?: return
        val dark = Win32NativeWindowHost.isEffectivelyDarkTheme()
        val bgRef = if (dark) 0x00262220L else 0x00FFFFFFL
        val fgRef = if (dark) 0x00F2F0ECL else 0x0024201EL
        SendMessageW(hwnd, LVM_SETBKCOLOR.toUInt(), 0uL, bgRef)
        SendMessageW(hwnd, LVM_SETTEXTBKCOLOR.toUInt(), 0uL, bgRef)
        SendMessageW(hwnd, LVM_SETTEXTCOLOR.toUInt(), 0uL, fgRef)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val colCount = effectiveColCount().coerceAtLeast(1)
        val estW = (colCount * 110).coerceAtLeast(240)
        val estH = (32 + rowsList.size * 24).coerceIn(100, 420)
        return Size(estW, estH)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        Win32RetainedControlSupport.onLeafBoundsChanged(nativeWidget, rect)
        if (rect.width > 0) {
            updateColumnWidths()
        }
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
        syncThemeColors()
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
