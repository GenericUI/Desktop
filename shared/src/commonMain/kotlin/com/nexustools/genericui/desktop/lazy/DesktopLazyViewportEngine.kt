package com.nexustools.genericui.desktop.lazy

import com.nexustools.genericui.core.NativeImpl
import kotlin.math.max
import kotlin.math.min

/**
 * Shared Desktop lazy viewport window engine for `LazyListNativeImpl`, `LazyGridNativeImpl`, and `TreeViewNativeImpl`
 * across `:windows`, `:macos`, `:gtk`, and `:qt`.
 *
 * Tracks scroll offset, viewport extent, item count, and column count, and genuinely instantiates/binds
 * visible items when they enter the viewport + overscan window while unloading/disposing items that scroll out.
 */
class DesktopLazyViewportEngine(
    var estimatedItemExtentPx: Float = 40f,
    var overscanItems: Int = 4,
    columns: Int = 1,
    defaultItemExtentPx: Float = estimatedItemExtentPx
) {
    init {
        this.estimatedItemExtentPx = defaultItemExtentPx
    }

    var totalItemCount: Int = 0
        set(value) {
            field = max(0, value)
        }

    var itemCount: Int
        get() = totalItemCount
        set(value) {
            totalItemCount = value
        }

    var columns: Int = max(1, columns)
        set(value) {
            field = max(1, value)
        }

    var viewportExtentPx: Float = 600f
        set(value) {
            field = max(1f, value)
        }

    var scrollOffsetPx: Float = 0f
        set(value) {
            field = max(0f, value)
        }

    var visibleRange: IntRange = IntRange.EMPTY
        private set

    private var itemFactory: ((Int) -> NativeImpl<*>)? = null
    private val mountedItems = linkedMapOf<Int, NativeImpl<*>>()

    val mountedIndices: Set<Int>
        get() = mountedItems.keys.toSet()

    fun getMountedItem(index: Int): NativeImpl<*>? = mountedItems[index]

    fun computeScrollOffsetForIndex(index: Int): Float {
        if (totalItemCount <= 0) return 0f
        val clamped = index.coerceIn(0, totalItemCount - 1)
        val row = clamped / columns
        return row * max(1f, estimatedItemExtentPx)
    }

    fun computeVisibleRange(): IntRange {
        if (totalItemCount <= 0) {
            visibleRange = IntRange.EMPTY
            return visibleRange
        }
        val itemExtent = max(1f, estimatedItemExtentPx)
        val firstRow = max(0, (scrollOffsetPx / itemExtent).toInt() - overscanItems)
        val visibleRows = max(1, ((viewportExtentPx / itemExtent).toInt()) + 1 + overscanItems * 2)
        val startIdx = (firstRow * columns).coerceIn(0, totalItemCount - 1)
        val endIdx = min(totalItemCount - 1, ((firstRow + visibleRows) * columns) - 1)
        visibleRange = if (startIdx <= endIdx) startIdx..endIdx else IntRange.EMPTY
        return visibleRange
    }

    fun configureItems(
        count: Int,
        factory: (Int) -> NativeImpl<*>,
        onMountItem: (Int, NativeImpl<*>) -> Unit = { _, _ -> },
        onUnmountItem: (Int, NativeImpl<*>) -> Unit = { _, _ -> }
    ) {
        unmountAll(onUnmountItem)
        totalItemCount = max(0, count)
        itemFactory = factory
        recomputeViewport(onMountItem, onUnmountItem)
    }

    fun updateScrollAndViewport(
        newScrollOffsetPx: Float,
        newViewportExtentPx: Float = viewportExtentPx,
        onMountItem: (Int, NativeImpl<*>) -> Unit = { _, _ -> },
        onUnmountItem: (Int, NativeImpl<*>) -> Unit = { _, _ -> }
    ) {
        scrollOffsetPx = max(0f, newScrollOffsetPx)
        viewportExtentPx = max(1f, newViewportExtentPx)
        recomputeViewport(onMountItem, onUnmountItem)
    }

    fun scrollToIndex(
        index: Int,
        onMountItem: (Int, NativeImpl<*>) -> Unit = { _, _ -> },
        onUnmountItem: (Int, NativeImpl<*>) -> Unit = { _, _ -> }
    ) {
        scrollOffsetPx = computeScrollOffsetForIndex(index)
        recomputeViewport(onMountItem, onUnmountItem)
    }

    fun notifyRangeChanged(
        startIndex: Int,
        count: Int,
        onMountItem: (Int, NativeImpl<*>) -> Unit = { _, _ -> },
        onUnmountItem: (Int, NativeImpl<*>) -> Unit = { _, _ -> }
    ) {
        val factory = itemFactory ?: return
        val endExclusive = min(totalItemCount, startIndex + max(0, count))
        for (idx in max(0, startIndex) until endExclusive) {
            val existing = mountedItems.remove(idx)
            if (existing != null) {
                onUnmountItem(idx, existing)
                existing.dispose()
            }
            if (idx in visibleRange) {
                val created = factory(idx)
                mountedItems[idx] = created
                onMountItem(idx, created)
            }
        }
    }

    private fun recomputeViewport(
        onMountItem: (Int, NativeImpl<*>) -> Unit,
        onUnmountItem: (Int, NativeImpl<*>) -> Unit
    ) {
        val factory = itemFactory
        if (totalItemCount <= 0 || factory == null) {
            unmountAll(onUnmountItem)
            visibleRange = IntRange.EMPTY
            return
        }

        val newRange = computeVisibleRange()

        val toRemove = mountedItems.keys.filter { it !in newRange }
        for (idx in toRemove) {
            val old = mountedItems.remove(idx)
            if (old != null) {
                onUnmountItem(idx, old)
                old.dispose()
            }
        }

        for (idx in newRange) {
            if (!mountedItems.containsKey(idx)) {
                val created = factory(idx)
                mountedItems[idx] = created
                onMountItem(idx, created)
            }
        }
    }

    fun unmountAll(onUnmountItem: (Int, NativeImpl<*>) -> Unit = { _, _ -> }) {
        for ((idx, impl) in mountedItems) {
            onUnmountItem(idx, impl)
            impl.dispose()
        }
        mountedItems.clear()
        visibleRange = IntRange.EMPTY
    }
}
