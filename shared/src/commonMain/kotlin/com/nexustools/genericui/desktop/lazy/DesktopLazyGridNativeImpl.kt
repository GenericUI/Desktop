package com.nexustools.genericui.desktop.lazy

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.lazy.LazyGridNativeImpl

/**
 * Shared desktop base implementation of [LazyGridNativeImpl] backed by [DesktopLazyViewportEngine].
 */
open class DesktopLazyGridNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "lazy-grid"), LazyGridNativeImpl<W> {

    val viewportEngine: DesktopLazyViewportEngine = DesktopLazyViewportEngine(columns = 3, defaultItemExtentPx = 36f)
    protected var itemFactoryFn: ((Int) -> NativeImpl<*>)? = null
    protected val materializedItems: MutableMap<Int, NativeImpl<*>> = linkedMapOf()
    var firstVisibleDataRow: Int = 0

    fun getTotalCellCount(): Int = viewportEngine.totalItemCount

    fun getColumnCount(): Int = viewportEngine.columns.coerceAtLeast(1)

    fun getTotalRowCount(): Int {
        val cols = getColumnCount()
        val cells = getTotalCellCount()
        return if (cells <= 0) 0 else (cells + cols - 1) / cols
    }

    override fun setColumns(columns: Int) {
        viewportEngine.columns = columns.coerceAtLeast(1)
        clearMaterializedItems()
        rematerializeVisibleWindow()
    }

    override fun setItemCount(count: Int, itemFactory: (index: Int) -> NativeImpl<*>) {
        clearMaterializedItems()
        viewportEngine.itemCount = count.coerceAtLeast(0)
        itemFactoryFn = itemFactory
        val totalDataRows = (getTotalRowCount() - 1).coerceAtLeast(0)
        if (firstVisibleDataRow > totalDataRows) {
            firstVisibleDataRow = totalDataRows
        }
        rematerializeVisibleWindow()
    }

    override fun scrollToIndex(index: Int) {
        viewportEngine.scrollOffsetPx = viewportEngine.computeScrollOffsetForIndex(index)
        val cols = getColumnCount()
        val row = if (index <= 0) 0 else (index / cols)
        firstVisibleDataRow = (row - 1).coerceAtLeast(0)
        rematerializeVisibleWindow()
    }

    fun getOrMaterializeCellWidget(index: Int): DesktopNativeWidget? {
        if (index < 0 || index >= viewportEngine.totalItemCount) return null
        val existing = materializedItems[index]
        if (existing != null) {
            return existing.nativeWidget as? DesktopNativeWidget
        }
        val factory = itemFactoryFn ?: return null
        val created = factory(index)
        materializedItems[index] = created
        addChildNative(created)
        return created.nativeWidget as? DesktopNativeWidget
    }

    protected fun clearMaterializedItems() {
        val previous = materializedItems.values.toList()
        materializedItems.clear()
        for (item in previous) {
            removeChildNative(item)
        }
    }

    protected open fun rematerializeVisibleWindow() {
        val factory = itemFactoryFn ?: return
        val visibleRange = viewportEngine.computeVisibleRange()
        val toEvict = materializedItems.keys.filter { it !in visibleRange }
        for (idx in toEvict) {
            materializedItems.remove(idx)?.let { removeChildNative(it) }
        }
        for (idx in visibleRange) {
            if (idx !in materializedItems) {
                val created = factory(idx)
                materializedItems[idx] = created
                addChildNative(created)
            }
        }
    }
}
