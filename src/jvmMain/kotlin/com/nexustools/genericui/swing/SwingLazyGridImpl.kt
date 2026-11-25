package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.nativeimpl.lazy.LazyGridNativeImpl
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import kotlin.math.max

/**
 * Java Swing virtualized grid `JScrollPane` implementation of [LazyGridNativeImpl] with dynamic
 * column widths stretched across the actual viewport width, row-height measurement, and gaps.
 */
class SwingLazyGridImpl : SwingBaseContainerNativeImpl(JScrollPane(), "d-grid overflow-auto"), LazyGridNativeImpl<JComponent> {
    override val nativeWidget: JScrollPane get() = super.nativeWidget as JScrollPane
    private val canvasPanel: LazyGridCanvasPanel = LazyGridCanvasPanel()

    private var columnCount: Int = 3
    private var minColumnWidthPx: Int = 0
    private var totalItems: Int = 0
    private var estimatedRowHeightPx: Int = 120
    private var overscanRows: Int = 2
    private var gapPx: Int = 8
    private var scrollOffsetYPx: Int = 0
    private var itemFactory: ((Int) -> NativeImpl<*>)? = null
    private val mountedCells = linkedMapOf<Int, JComponent>()
    private val measuredRowHeights = HashMap<Int, Int>()
    private var scrollListener: ((Float, Float) -> Unit)? = null

    init {
        canvasPanel.layout = null
        canvasPanel.isOpaque = false
        nativeWidget.setViewportView(canvasPanel)
        nativeWidget.isOpaque = false
        nativeWidget.viewport?.isOpaque = false
        nativeWidget.border = BorderFactory.createEmptyBorder()
        nativeWidget.viewportBorder = null
        nativeWidget.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        nativeWidget.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        nativeWidget.verticalScrollBar.unitIncrement = 16

        nativeWidget.viewport.addChangeListener {
            scrollOffsetYPx = nativeWidget.viewport.viewPosition.y
            recomputeVisibleViewport()
            scrollListener?.invoke(
                scrollOffsetYPx.toFloat(),
                nativeWidget.viewport.height.coerceAtLeast(1).toFloat()
            )
        }
        nativeWidget.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                recomputeVisibleViewport()
            }
        })
        nativeWidget.viewport.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                recomputeVisibleViewport()
            }
        })
        syncSwingAppearance()
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        nativeWidget.isOpaque = false
        nativeWidget.viewport?.isOpaque = false
    }

    override fun setColumns(columns: Int) {
        columnCount = columns.coerceAtLeast(1)
        nativeWidget.putClientProperty("columns", columnCount)
        measuredRowHeights.clear()
        recomputeVisibleViewport()
    }

    override fun setMinColumnWidthPx(minWidthPx: Int) {
        minColumnWidthPx = minWidthPx.coerceAtLeast(0)
        nativeWidget.putClientProperty("minColumnWidthPx", minColumnWidthPx)
        measuredRowHeights.clear()
        recomputeVisibleViewport()
    }

    override fun setEstimatedRowExtent(rowHeightPx: Float, overscanRows: Int) {
        estimatedRowHeightPx = rowHeightPx.toInt().coerceAtLeast(24)
        this.overscanRows = overscanRows.coerceAtLeast(1)
        recomputeVisibleViewport()
    }

    override fun setOnViewportScrollChanged(listener: ((Float, Float) -> Unit)?) {
        scrollListener = listener
    }

    fun getMinColumnWidthPx(): Int = minColumnWidthPx

    fun resolveEffectiveColumns(
        availableWidthPx: Int = nativeWidget.viewport?.width?.takeIf { it > 0 }
            ?: nativeWidget.width.takeIf { it > 0 }
            ?: getBounds().width.coerceAtLeast(360)
    ): Int {
        if (minColumnWidthPx <= 0) return columnCount
        val fitCols = ((availableWidthPx + gapPx) / (minColumnWidthPx + gapPx)).coerceAtLeast(1)
        return fitCols.coerceAtMost(columnCount)
    }

    override fun setItemCount(count: Int, itemFactory: (index: Int) -> NativeImpl<*>) {
        totalItems = count.coerceAtLeast(0)
        this.itemFactory = itemFactory
        mountedCells.clear()
        measuredRowHeights.clear()
        canvasPanel.removeAll()
        recomputeVisibleViewport()
    }

    override fun scrollToIndex(index: Int) {
        val effectiveCols = resolveEffectiveColumns()
        val row = index.coerceAtLeast(0) / effectiveCols
        var yPos = 0
        for (r in 0 until row) {
            yPos += (measuredRowHeights[r] ?: estimatedRowHeightPx) + gapPx
        }
        scrollOffsetYPx = yPos
        nativeWidget.viewport.viewPosition = Point(0, scrollOffsetYPx)
        recomputeVisibleViewport()
    }

    private fun resolveEffectiveGap(): Int {
        val styleGap = rawLayoutComponentStyle?.toCssPropertyMap()?.get("gap")?.first
            ?: rawLayoutComputedStyle?.gap?.toCssString()
        val parsed = com.nexustools.genericui.style.css.CssLength.parse(styleGap)?.resolvePx()?.toInt()
        return parsed ?: (attributesMap["gapPx"]?.toIntOrNull()) ?: gapPx
    }

    private fun recomputeVisibleViewport() {
        val factory = itemFactory ?: return
        val effectiveGap = resolveEffectiveGap()
        val availWidth = (nativeWidget.viewport?.width?.takeIf { it > 0 }
            ?: nativeWidget.width.takeIf { it > 0 }
            ?: getBounds().width.takeIf { it > 0 }
            ?: 360)
        val availHeight = (nativeWidget.viewport?.height?.takeIf { it > 0 }
            ?: nativeWidget.height.takeIf { it > 0 }
            ?: getBounds().height.takeIf { it > 0 }
            ?: 360)

        val effectiveCols = resolveEffectiveColumns(availWidth)
        val totalGapsW = (effectiveCols - 1).coerceAtLeast(0) * effectiveGap
        val cellWidth = ((availWidth - totalGapsW) / effectiveCols.coerceAtLeast(1)).coerceAtLeast(
            minColumnWidthPx.takeIf { it > 0 } ?: 40
        )
        val contentTotalW = max(availWidth, effectiveCols * cellWidth + totalGapsW)

        if (totalItems == 0) {
            mountedCells.clear()
            canvasPanel.removeAll()
            canvasPanel.preferredSize = Dimension(contentTotalW, 1)
            canvasPanel.setSize(contentTotalW, 1)
            canvasPanel.revalidate()
            return
        }

        val totalRows = (totalItems + effectiveCols - 1) / effectiveCols
        val estPitch = (estimatedRowHeightPx + effectiveGap).coerceAtLeast(1)
        val firstRow = ((scrollOffsetYPx / estPitch) - overscanRows).coerceAtLeast(0)
        val lastRow = (((scrollOffsetYPx + availHeight) / estPitch) + overscanRows).coerceAtMost((totalRows - 1).coerceAtLeast(0))
        val firstVisible = (firstRow * effectiveCols).coerceIn(0, totalItems - 1)
        val lastVisible = (((lastRow + 1) * effectiveCols) - 1).coerceIn(firstVisible, totalItems - 1)

        val toEvict = mountedCells.keys.filter { it < firstVisible || it > lastVisible }
        for (idx in toEvict) {
            mountedCells.remove(idx)?.let { canvasPanel.remove(it) }
        }

        for (r in firstRow..lastRow) {
            var maxRowH = estimatedRowHeightPx.coerceAtMost(24)
            val startIdx = r * effectiveCols
            val endIdx = minOf(startIdx + effectiveCols - 1, totalItems - 1)
            for (idx in startIdx..endIdx) {
                val comp = mountedCells.getOrPut(idx) {
                    val impl = factory(idx)
                    val created = (impl.nativeWidget as? JComponent) ?: JPanel()
                    (impl as? SwingBaseNativeImpl)?.refreshThemeAppearance()
                    canvasPanel.add(created)
                    created
                }
                val measured = SwingIntrinsicSizeMeasurer.measureIntrinsicSize(comp, cellWidth, estimatedRowHeightPx * 2)
                maxRowH = max(maxRowH, measured.height.coerceAtLeast(28))
            }
            measuredRowHeights[r] = maxRowH
        }

        var yCursor = 0
        for (r in 0 until firstRow) {
            yCursor += (measuredRowHeights[r] ?: estimatedRowHeightPx) + effectiveGap
        }
        for (r in firstRow..lastRow) {
            val rowH = measuredRowHeights[r] ?: estimatedRowHeightPx
            val startIdx = r * effectiveCols
            val endIdx = minOf(startIdx + effectiveCols - 1, totalItems - 1)
            for (idx in startIdx..endIdx) {
                val comp = mountedCells[idx] ?: continue
                val colIdx = idx % effectiveCols
                val x = colIdx * (cellWidth + effectiveGap)
                comp.setBounds(x, yCursor, cellWidth, rowH)
                SwingFlexLayoutManager.layoutTreeNow(comp)
            }
            yCursor += rowH + effectiveGap
        }

        var totalH = 0
        for (r in 0 until totalRows) {
            totalH += measuredRowHeights[r] ?: estimatedRowHeightPx
            if (r < totalRows - 1) totalH += effectiveGap
        }
        canvasPanel.preferredSize = Dimension(contentTotalW, totalH.coerceAtLeast(1))
        canvasPanel.setSize(contentTotalW, totalH.coerceAtLeast(1))
        canvasPanel.revalidate()
        canvasPanel.repaint()
    }

    private inner class LazyGridCanvasPanel : JPanel(null), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int = 16
        override fun getScrollableBlockIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int = 64
        override fun getScrollableTracksViewportWidth(): Boolean = minColumnWidthPx <= 0
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }
}
