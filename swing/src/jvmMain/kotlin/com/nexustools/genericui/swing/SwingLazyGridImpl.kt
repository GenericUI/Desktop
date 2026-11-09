package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.nativeimpl.lazy.LazyGridNativeImpl
import java.awt.Dimension
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Java Swing `JScrollPane` viewport-windowed implementation of [LazyGridNativeImpl].
 */
class SwingLazyGridImpl : SwingBaseContainerNativeImpl(JScrollPane(JPanel(null)), "d-grid overflow-auto"), LazyGridNativeImpl<JComponent> {
    override val nativeWidget: JScrollPane get() = super.nativeWidget as JScrollPane
    private val canvasPanel: JPanel = nativeWidget.viewport.view as JPanel

    private var columnCount: Int = 3
    private var minColumnWidthPx: Int = 0
    private var totalItems: Int = 0
    private val cellWidthPx: Int = 120
    private val cellHeightPx: Int = 120
    private val gapPx: Int = 8
    private var scrollOffsetYPx: Int = 0
    private var itemFactory: ((Int) -> NativeImpl<*>)? = null
    private val mountedCells = linkedMapOf<Int, JComponent>()

    init {
        nativeWidget.viewport.addChangeListener {
            scrollOffsetYPx = nativeWidget.viewport.viewPosition.y
            recomputeVisibleViewport()
        }
        nativeWidget.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                recomputeVisibleViewport()
            }
        })
    }

    override fun setColumns(columns: Int) {
        columnCount = columns.coerceAtLeast(1)
        nativeWidget.putClientProperty("columns", columnCount)
        recomputeVisibleViewport()
    }

    override fun setMinColumnWidthPx(minWidthPx: Int) {
        minColumnWidthPx = minWidthPx.coerceAtLeast(0)
        nativeWidget.putClientProperty("minColumnWidthPx", minColumnWidthPx)
        recomputeVisibleViewport()
    }

    fun getMinColumnWidthPx(): Int = minColumnWidthPx

    fun resolveEffectiveColumns(
        availableWidthPx: Int = nativeWidget.viewport.width
            .takeIf { it > 0 }
            ?: nativeWidget.width.takeIf { it > 0 }
            ?: getBounds().width.takeIf { it > 0 }
            ?: (columnCount * (cellWidthPx + gapPx))
    ): Int {
        if (minColumnWidthPx <= 0 || availableWidthPx <= 0) {
            return columnCount.coerceAtLeast(1)
        }
        val fitCols = ((availableWidthPx + gapPx) / (minColumnWidthPx + gapPx)).coerceAtLeast(1)
        return minOf(columnCount.coerceAtLeast(1), fitCols)
    }

    override fun setItemCount(count: Int, itemFactory: (index: Int) -> NativeImpl<*>) {
        totalItems = count.coerceAtLeast(0)
        this.itemFactory = itemFactory
        recomputeVisibleViewport()
    }

    override fun scrollToIndex(index: Int) {
        val effectiveCols = resolveEffectiveColumns()
        val row = index.coerceAtLeast(0) / effectiveCols
        scrollOffsetYPx = row * (cellHeightPx + gapPx)
        nativeWidget.viewport.viewPosition = Point(0, scrollOffsetYPx)
        recomputeVisibleViewport()
    }

    private fun recomputeVisibleViewport() {
        val factory = itemFactory ?: return
        val effectiveCols = resolveEffectiveColumns()
        val effectiveCellWidth = if (minColumnWidthPx > 0) maxOf(cellWidthPx, minColumnWidthPx) else cellWidthPx
        val totalRows = (totalItems + effectiveCols - 1) / effectiveCols
        val rowPitch = cellHeightPx + gapPx
        canvasPanel.preferredSize = Dimension(effectiveCols * (effectiveCellWidth + gapPx), totalRows * rowPitch)

        if (totalItems <= 0) {
            for ((_, comp) in mountedCells) {
                canvasPanel.remove(comp)
            }
            mountedCells.clear()
            return
        }

        val viewportHeight = getBounds().height.coerceAtLeast(360)
        val firstRow = ((scrollOffsetYPx / rowPitch) - 2).coerceAtLeast(0)
        val lastRow = (((scrollOffsetYPx + viewportHeight) / rowPitch) + 2).coerceAtMost((totalRows - 1).coerceAtLeast(0))
        val firstVisible = (firstRow * effectiveCols).coerceIn(0, totalItems - 1)
        val lastVisible = (((lastRow + 1) * effectiveCols) - 1).coerceIn(firstVisible, totalItems - 1)

        val toEvict = mountedCells.keys.filter { it < firstVisible || it > lastVisible }
        for (idx in toEvict) {
            mountedCells.remove(idx)?.let { canvasPanel.remove(it) }
        }

        for (idx in firstVisible..lastVisible) {
            val comp = mountedCells.getOrPut(idx) {
                val impl = factory(idx)
                val created = (impl.nativeWidget as? JComponent) ?: JPanel()
                canvasPanel.add(created)
                created
            }
            val r = idx / effectiveCols
            val c = idx % effectiveCols
            comp.setBounds(c * (effectiveCellWidth + gapPx), r * rowPitch, effectiveCellWidth, cellHeightPx)
        }
    }
}
