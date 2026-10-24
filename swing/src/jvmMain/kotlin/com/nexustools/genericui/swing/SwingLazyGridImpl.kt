package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.nativeimpl.lazy.LazyGridNativeImpl
import java.awt.Dimension
import java.awt.Point
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Java Swing `JScrollPane` viewport-windowed implementation of [LazyGridNativeImpl].
 */
class SwingLazyGridImpl : SwingBaseContainerNativeImpl(JScrollPane(JPanel(null)), "d-grid overflow-auto"), LazyGridNativeImpl<JComponent> {
    override val nativeWidget: JScrollPane = super.nativeWidget as JScrollPane
    private val canvasPanel: JPanel = nativeWidget.viewport.view as JPanel

    private var columnCount: Int = 3
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
    }

    override fun setColumns(columns: Int) {
        columnCount = columns.coerceAtLeast(1)
        recomputeVisibleViewport()
    }

    override fun setItemCount(count: Int, itemFactory: (index: Int) -> NativeImpl<*>) {
        totalItems = count.coerceAtLeast(0)
        this.itemFactory = itemFactory
        recomputeVisibleViewport()
    }

    override fun scrollToIndex(index: Int) {
        val row = index.coerceAtLeast(0) / columnCount
        scrollOffsetYPx = row * (cellHeightPx + gapPx)
        nativeWidget.viewport.viewPosition = Point(0, scrollOffsetYPx)
        recomputeVisibleViewport()
    }

    private fun recomputeVisibleViewport() {
        val factory = itemFactory ?: return
        val totalRows = (totalItems + columnCount - 1) / columnCount
        val rowPitch = cellHeightPx + gapPx
        canvasPanel.preferredSize = Dimension(columnCount * (cellWidthPx + gapPx), totalRows * rowPitch)

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
        val firstVisible = (firstRow * columnCount).coerceIn(0, totalItems - 1)
        val lastVisible = (((lastRow + 1) * columnCount) - 1).coerceIn(firstVisible, totalItems - 1)

        val toEvict = mountedCells.keys.filter { it < firstVisible || it > lastVisible }
        for (idx in toEvict) {
            mountedCells.remove(idx)?.let { canvasPanel.remove(it) }
        }

        for (idx in firstVisible..lastVisible) {
            if (!mountedCells.containsKey(idx)) {
                val impl = factory(idx)
                val comp = (impl.nativeWidget as? JComponent) ?: JPanel()
                val r = idx / columnCount
                val c = idx % columnCount
                comp.setBounds(c * (cellWidthPx + gapPx), r * rowPitch, cellWidthPx, cellHeightPx)
                canvasPanel.add(comp)
                mountedCells[idx] = comp
            }
        }
    }
}
