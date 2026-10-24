package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.ui.Orientation
import com.nexustools.genericui.nativeimpl.lazy.LazyListNativeImpl
import java.awt.Dimension
import java.awt.Point
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane

/**
 * Java Swing `JScrollPane` viewport-windowed implementation of [LazyListNativeImpl].
 *
 * Dynamically mounts only visible child Swing [JComponent]s inside the viewport and unmounts
 * off-screen components.
 */
class SwingLazyListImpl : SwingBaseContainerNativeImpl(JScrollPane(JPanel(null)), "list-group overflow-auto"), LazyListNativeImpl<JComponent> {
    override val nativeWidget: JScrollPane = super.nativeWidget as JScrollPane
    private val canvasPanel: JPanel = nativeWidget.viewport.view as JPanel

    private var orientationVal: Orientation = Orientation.VERTICAL
    private var totalItems: Int = 0
    private var estimatedItemSizePx: Int = 44
    private var scrollOffsetPx: Int = 0
    private var itemFactory: ((Int) -> NativeImpl<*>)? = null
    private val mountedItems = linkedMapOf<Int, JComponent>()

    init {
        nativeWidget.viewport.addChangeListener {
            val pos = nativeWidget.viewport.viewPosition
            scrollOffsetPx = if (orientationVal == Orientation.VERTICAL) pos.y else pos.x
            recomputeVisibleViewport()
        }
    }

    override fun setOrientation(orientation: Orientation) {
        orientationVal = orientation
        recomputeVisibleViewport()
    }

    override fun setItemCount(count: Int, itemFactory: (index: Int) -> NativeImpl<*>) {
        totalItems = count.coerceAtLeast(0)
        this.itemFactory = itemFactory
        recomputeVisibleViewport()
    }

    override fun notifyItemRangeChanged(startIndex: Int, count: Int) {
        for (idx in startIndex until (startIndex + count)) {
            mountedItems.remove(idx)?.let { canvasPanel.remove(it) }
        }
        recomputeVisibleViewport()
    }

    override fun scrollToIndex(index: Int) {
        scrollOffsetPx = index.coerceAtLeast(0) * estimatedItemSizePx
        nativeWidget.viewport.viewPosition = if (orientationVal == Orientation.VERTICAL) {
            Point(0, scrollOffsetPx)
        } else {
            Point(scrollOffsetPx, 0)
        }
        recomputeVisibleViewport()
    }

    private fun recomputeVisibleViewport() {
        val factory = itemFactory ?: return
        val vertical = orientationVal == Orientation.VERTICAL
        val totalExtent = totalItems * estimatedItemSizePx
        canvasPanel.preferredSize = if (vertical) Dimension(400, totalExtent) else Dimension(totalExtent, 200)

        if (totalItems <= 0) {
            for ((_, comp) in mountedItems) {
                canvasPanel.remove(comp)
            }
            mountedItems.clear()
            return
        }

        val viewportExtent = if (vertical) getBounds().height.coerceAtLeast(320) else getBounds().width.coerceAtLeast(320)
        val firstVisible = ((scrollOffsetPx / estimatedItemSizePx) - 3).coerceAtLeast(0)
        val lastVisible = (((scrollOffsetPx + viewportExtent) / estimatedItemSizePx) + 3).coerceAtMost(totalItems - 1)

        val toEvict = mountedItems.keys.filter { it < firstVisible || it > lastVisible }
        for (idx in toEvict) {
            mountedItems.remove(idx)?.let { canvasPanel.remove(it) }
        }

        for (idx in firstVisible..lastVisible) {
            if (!mountedItems.containsKey(idx)) {
                val impl = factory(idx)
                val comp = (impl.nativeWidget as? JComponent) ?: JPanel()
                val offset = idx * estimatedItemSizePx
                if (vertical) {
                    comp.setBounds(0, offset, getBounds().width.coerceAtLeast(320), estimatedItemSizePx)
                } else {
                    comp.setBounds(offset, 0, estimatedItemSizePx, getBounds().height.coerceAtLeast(160))
                }
                canvasPanel.add(comp)
                mountedItems[idx] = comp
            }
        }
    }
}
