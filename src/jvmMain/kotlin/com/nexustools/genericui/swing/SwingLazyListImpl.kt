package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.ui.Orientation
import com.nexustools.genericui.nativeimpl.lazy.LazyListNativeImpl
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
import javax.swing.SwingConstants
import kotlin.math.max

/**
 * Java Swing virtualized viewport `JScrollPane` implementation of [LazyListNativeImpl] with dynamic
 * item measurement, gaps, viewport-tracking sizing, and responsive item re-layout.
 */
class SwingLazyListImpl : SwingBaseContainerNativeImpl(JScrollPane(), "list-group overflow-auto"), LazyListNativeImpl<JComponent> {
    override val nativeWidget: JScrollPane get() = super.nativeWidget as JScrollPane
    private val canvasPanel: LazyListCanvasPanel = LazyListCanvasPanel()

    private var orientationVal: Orientation = Orientation.VERTICAL
    private var totalItems: Int = 0
    private var estimatedItemSizePx: Int = 44
    private var overscanCount: Int = 3
    private var itemGapPx: Int = 4
    private var scrollOffsetPx: Int = 0
    private var itemFactory: ((Int) -> NativeImpl<*>)? = null
    private val mountedItems = linkedMapOf<Int, JComponent>()
    private val measuredExtents = HashMap<Int, Int>()
    private var scrollListener: ((Float, Float) -> Unit)? = null

    init {
        canvasPanel.layout = null
        canvasPanel.isOpaque = false
        nativeWidget.setViewportView(canvasPanel)
        nativeWidget.isOpaque = false
        nativeWidget.viewport?.isOpaque = false
        nativeWidget.border = BorderFactory.createEmptyBorder()
        nativeWidget.viewportBorder = null
        nativeWidget.verticalScrollBar.unitIncrement = 16
        nativeWidget.horizontalScrollBar.unitIncrement = 16

        nativeWidget.viewport.addChangeListener {
            val pos = nativeWidget.viewport.viewPosition
            scrollOffsetPx = if (orientationVal == Orientation.VERTICAL) pos.y else pos.x
            recomputeVisibleViewport()
            val viewportExt = if (orientationVal == Orientation.VERTICAL) {
                nativeWidget.viewport.height.coerceAtLeast(1)
            } else {
                nativeWidget.viewport.width.coerceAtLeast(1)
            }
            scrollListener?.invoke(scrollOffsetPx.toFloat(), viewportExt.toFloat())
        }
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
        if (orientationVal == Orientation.VERTICAL) {
            nativeWidget.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            nativeWidget.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        } else {
            nativeWidget.horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            nativeWidget.verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
        }
    }

    override fun setOrientation(orientation: Orientation) {
        orientationVal = orientation
        nativeWidget.putClientProperty("orientation", orientation.name)
        syncSwingAppearance()
        recomputeVisibleViewport()
    }

    override fun setEstimatedItemExtent(extentPx: Float, overscanItems: Int) {
        estimatedItemSizePx = extentPx.toInt().coerceAtLeast(16)
        overscanCount = overscanItems.coerceAtLeast(1)
        recomputeVisibleViewport()
    }

    override fun setOnViewportScrollChanged(listener: ((Float, Float) -> Unit)?) {
        scrollListener = listener
    }

    override fun setItemCount(count: Int, itemFactory: (index: Int) -> NativeImpl<*>) {
        totalItems = count.coerceAtLeast(0)
        this.itemFactory = itemFactory
        clearMountedItems()
        measuredExtents.clear()
        recomputeVisibleViewport()
    }

    override fun notifyItemRangeChanged(startIndex: Int, count: Int) {
        for (i in startIndex until (startIndex + count)) {
            measuredExtents.remove(i)
            mountedItems.remove(i)?.let { canvasPanel.remove(it) }
        }
        recomputeVisibleViewport()
    }

    override fun scrollToIndex(index: Int) {
        if (totalItems <= 0) return
        val clamped = index.coerceIn(0, totalItems - 1)
        val offset = computeOffsetForIndex(clamped)
        scrollOffsetPx = offset
        val pos = if (orientationVal == Orientation.VERTICAL) Point(0, offset) else Point(offset, 0)
        nativeWidget.viewport.viewPosition = pos
        recomputeVisibleViewport()
    }

    private fun resolveGapPx(): Int {
        val styleGap = rawLayoutComponentStyle?.toCssPropertyMap()?.get("gap")?.first
            ?: rawLayoutComputedStyle?.gap?.toCssString()
        val parsed = com.nexustools.genericui.style.css.CssLength.parse(styleGap)?.resolvePx()?.toInt()
        return parsed ?: (attributesMap["gapPx"]?.toIntOrNull()) ?: itemGapPx
    }

    private fun itemExtent(idx: Int): Int =
        measuredExtents[idx] ?: estimatedItemSizePx

    private fun computeOffsetForIndex(index: Int): Int {
        val gap = resolveGapPx()
        var pos = 0
        for (i in 0 until index) {
            pos += itemExtent(i) + gap
        }
        return pos
    }

    private fun computeTotalExtent(gap: Int): Int {
        if (totalItems <= 0) return 0
        var sum = 0
        for (i in 0 until totalItems) {
            sum += itemExtent(i)
            if (i < totalItems - 1) sum += gap
        }
        return sum
    }

    private fun clearMountedItems() {
        mountedItems.clear()
        canvasPanel.removeAll()
        canvasPanel.revalidate()
        canvasPanel.repaint()
    }

    private fun recomputeVisibleViewport() {
        val factory = itemFactory ?: return
        val vertical = orientationVal == Orientation.VERTICAL
        val gap = resolveGapPx()
        val viewW = nativeWidget.viewport?.width?.takeIf { it > 0 }
            ?: nativeWidget.width.takeIf { it > 0 }
            ?: getBounds().width.takeIf { it > 0 }
            ?: 360
        val viewH = nativeWidget.viewport?.height?.takeIf { it > 0 }
            ?: nativeWidget.height.takeIf { it > 0 }
            ?: getBounds().height.takeIf { it > 0 }
            ?: 360

        if (totalItems == 0) {
            clearMountedItems()
            canvasPanel.preferredSize = Dimension(viewW, 1)
            canvasPanel.setSize(viewW, 1)
            canvasPanel.revalidate()
            return
        }

        val viewportMain = if (vertical) viewH else viewW
        val step = (estimatedItemSizePx + gap).coerceAtLeast(1)
        val firstVisible = ((scrollOffsetPx / step) - overscanCount).coerceAtLeast(0)
        val lastVisible = (((scrollOffsetPx + viewportMain) / step) + overscanCount).coerceAtMost(totalItems - 1)

        val toEvict = mountedItems.keys.filter { it < firstVisible || it > lastVisible }
        for (idx in toEvict) {
            mountedItems.remove(idx)?.let { canvasPanel.remove(it) }
        }

        for (idx in firstVisible..lastVisible) {
            val comp = mountedItems.getOrPut(idx) {
                val impl = factory(idx)
                val c = (impl.nativeWidget as? JComponent) ?: JPanel()
                (impl as? SwingBaseNativeImpl)?.refreshThemeAppearance()
                canvasPanel.add(c)
                c
            }
            val measured = SwingIntrinsicSizeMeasurer.measureIntrinsicSize(
                node = comp,
                availableWidthPx = if (vertical) viewW else estimatedItemSizePx,
                availableHeightPx = if (vertical) estimatedItemSizePx else viewH
            )
            val ext = if (vertical) {
                max(measured.height, estimatedItemSizePx.coerceAtMost(measured.height).coerceAtLeast(24))
            } else {
                max(measured.width, estimatedItemSizePx.coerceAtMost(measured.width).coerceAtLeast(24))
            }
            measuredExtents[idx] = ext
        }

        var cursor = 0
        for (i in 0 until firstVisible) {
            cursor += itemExtent(i) + gap
        }
        for (idx in firstVisible..lastVisible) {
            val comp = mountedItems[idx] ?: continue
            val ext = itemExtent(idx)
            if (vertical) {
                comp.setBounds(0, cursor, viewW, ext)
            } else {
                comp.setBounds(cursor, 0, ext, viewH)
            }
            SwingFlexLayoutManager.layoutTreeNow(comp)
            cursor += ext + gap
        }

        val totalExtent = computeTotalExtent(gap).coerceAtLeast(1)
        val pref = if (vertical) Dimension(viewW, totalExtent) else Dimension(totalExtent, viewH)
        canvasPanel.preferredSize = pref
        canvasPanel.setSize(pref)
        canvasPanel.revalidate()
        canvasPanel.repaint()
    }

    private inner class LazyListCanvasPanel : JPanel(null), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int = 16
        override fun getScrollableBlockIncrement(visibleRect: Rectangle?, orientation: Int, direction: Int): Int = 64
        override fun getScrollableTracksViewportWidth(): Boolean = orientationVal == Orientation.VERTICAL
        override fun getScrollableTracksViewportHeight(): Boolean = orientationVal == Orientation.HORIZONTAL
    }
}
