@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.widget.DesktopTableNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.useContents
import platform.AppKit.NSScrollView
import platform.AppKit.NSTableColumn
import platform.AppKit.NSTableView
import platform.AppKit.NSTableViewUniformColumnAutoresizingStyle
import platform.AppKit.NSTableViewDataSourceProtocol
import platform.AppKit.NSTableViewDelegateProtocol
import platform.AppKit.NSTextField
import platform.AppKit.NSView
import platform.AppKit.labelWithString
import platform.Foundation.NSMakeRect
import platform.darwin.NSObject

internal class MacosTableDataBridge(
    private val rowCountProvider: () -> Int,
    private val cellTextProvider: (colIdx: Int, rowIdx: Int) -> String
) : NSObject(), NSTableViewDataSourceProtocol, NSTableViewDelegateProtocol {

    override fun numberOfRowsInTableView(tableView: NSTableView): Long =
        rowCountProvider().toLong()

    override fun tableView(tableView: NSTableView, viewForTableColumn: NSTableColumn?, row: Long): NSView? {
        val colIdx = viewForTableColumn?.identifier?.toIntOrNull() ?: 0
        val text = cellTextProvider(colIdx, row.toInt())
        return NSTextField.labelWithString(text).apply {
            setLineBreakMode(platform.AppKit.NSLineBreakByTruncatingTail)
        }
    }
}

/**
 * macOS AppKit retained [NSScrollView] + [NSTableView] implementation of [DesktopTableNativeImpl].
 */
class MacosTableNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopTableNativeImpl<MacosViewHandle>(nativeWidget) {

    private var columnWeightsArr: FloatArray = FloatArray(0)
    private var scrollView: NSScrollView? = null
    private var tableView: NSTableView? = null
    private var dataBridge: MacosTableDataBridge? = null

    init {
        nativeWidget.viewMaterializer = {
            ensureTable().also { rebuildColumnsAndReload() }
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureTable()
            rebuildColumnsAndReload()
        }
    }

    private fun ensureTable(): NSScrollView {
        scrollView?.let { return it }
        val bridge = MacosTableDataBridge(
            rowCountProvider = { rowsList.size },
            cellTextProvider = { colIdx, rowIdx ->
                rowsList.getOrNull(rowIdx)?.getOrNull(colIdx) ?: ""
            }
        )
        val tv = NSTableView(frame = NSMakeRect(0.0, 0.0, 480.0, 220.0)).apply {
            setRowHeight(28.0)
            setUsesAlternatingRowBackgroundColors(stripedState)
            setColumnAutoresizingStyle(NSTableViewUniformColumnAutoresizingStyle)
            setDataSource(bridge)
            setDelegate(bridge)
        }
        val sv = NSScrollView(frame = NSMakeRect(0.0, 0.0, 480.0, 220.0)).apply {
            setHasVerticalScroller(true)
            setHasHorizontalScroller(true)
            setAutohidesScrollers(true)
            setDrawsBackground(false)
            setDocumentView(tv)
        }
        dataBridge = bridge
        tableView = tv
        scrollView = sv
        nativeWidget.nsView = sv
        sv.setHidden(!nativeWidget.visible)
        sv.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        sv.setToolTip(nativeWidget.tooltipText)
        return sv
    }

    private fun rebuildColumnsAndReload() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { rebuildColumnsAndReload() }
            return
        }
        val sv = ensureTable()
        val tv = tableView ?: return
        for (existing in tv.tableColumns.filterIsInstance<NSTableColumn>()) {
            tv.removeTableColumn(existing)
        }

        val colCount = maxOf(headersList.size, rowsList.maxOfOrNull { it.size } ?: 0)
        val totalW = sv.bounds.useContents { size.width }.coerceAtLeast(320.0)
        val totalWeight = if (columnWeightsArr.isNotEmpty()) {
            (0 until colCount).sumOf { columnWeightsArr.getOrElse(it) { 1f }.toDouble().coerceAtLeast(0.1) }
        } else {
            colCount.coerceAtLeast(1).toDouble()
        }

        for (i in 0 until colCount) {
            val col = NSTableColumn(identifier = i.toString())
            col.setTitle(headersList.getOrElse(i) { "" })
            val w = if (columnWeightsArr.isNotEmpty()) {
                val weight = columnWeightsArr.getOrElse(i) { 1f }.toDouble().coerceAtLeast(0.1)
                (totalW * (weight / totalWeight)).coerceAtLeast(48.0)
            } else {
                (totalW / colCount.coerceAtLeast(1)).coerceAtLeast(60.0)
            }
            col.setWidth(w)
            tv.addTableColumn(col)
        }
        tv.setUsesAlternatingRowBackgroundColors(stripedState)
        tv.reloadData()
        sv.tile()
        sv.reflectScrolledClipView(sv.contentView())
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val cols = maxOf(headersList.size, 1)
        val rows = rowsList.size
        val w = (maxWidth?.toInt() ?: (cols * 120)).coerceAtLeast(200)
        val h = (32 + rows * 28).coerceIn(80, maxHeight?.toInt() ?: 420)
        return Size(w, h)
    }

    override fun setHeaders(headers: List<String>) {
        super.setHeaders(headers)
        rebuildColumnsAndReload()
    }

    override fun setRows(rows: List<List<String>>) {
        super.setRows(rows)
        rebuildColumnsAndReload()
    }

    override fun setStriped(striped: Boolean) {
        super.setStriped(striped)
        tableView?.setUsesAlternatingRowBackgroundColors(striped)
    }

    override fun setColumnWeights(weights: FloatArray) {
        columnWeightsArr = weights.copyOf()
        nativeWidget.attributes["columnWeights"] = columnWeightsArr
        rebuildColumnsAndReload()
    }

    fun getColumnWeights(): List<Float> = columnWeightsArr.toList()

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onNativeBoundsChanged(nativeWidget.bounds) }
            return
        }
        val sv = (nativeWidget.ensureNsViewOnMain() as? NSScrollView) ?: scrollView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(sv, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
        val tv = tableView ?: return
        val colCount = maxOf(headersList.size, rowsList.maxOfOrNull { it.size } ?: 0)
        val existingCols = tv.tableColumns.filterIsInstance<NSTableColumn>()
        if (existingCols.size != colCount) {
            rebuildColumnsAndReload()
            return
        }
        val totalW = sv.bounds.useContents { size.width }.coerceAtLeast(320.0)
        val totalWeight = if (columnWeightsArr.isNotEmpty()) {
            (0 until colCount).sumOf { columnWeightsArr.getOrElse(it) { 1f }.toDouble().coerceAtLeast(0.1) }
        } else {
            colCount.coerceAtLeast(1).toDouble()
        }
        for (i in 0 until colCount) {
            val col = existingCols[i]
            col.setTitle(headersList.getOrElse(i) { "" })
            val w = if (columnWeightsArr.isNotEmpty()) {
                val weight = columnWeightsArr.getOrElse(i) { 1f }.toDouble().coerceAtLeast(0.1)
                (totalW * (weight / totalWeight)).coerceAtLeast(48.0)
            } else {
                (totalW / colCount.coerceAtLeast(1)).coerceAtLeast(60.0)
            }
            col.setWidth(w)
        }
        sv.tile()
        sv.reflectScrolledClipView(sv.contentView())
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        scrollView?.setHidden(!visible)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        scrollView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        scrollView?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        scrollView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val sv = scrollView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(sv, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
    }

    override fun dispose() {
        val tv = tableView
        val sv = scrollView
        tableView = null
        scrollView = null
        dataBridge = null
        MacosAppKitDispatch.onMainOrDefer {
            tv?.setDataSource(null)
            tv?.setDelegate(null)
            sv?.removeFromSuperview()
        }
        super.dispose()
    }
}
