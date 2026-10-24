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

    val viewportEngine: DesktopLazyViewportEngine = DesktopLazyViewportEngine(columns = 3, defaultItemExtentPx = 120f)
    protected var itemFactoryFn: ((Int) -> NativeImpl<*>)? = null
    protected val materializedItems: MutableMap<Int, NativeImpl<*>> = linkedMapOf()

    override fun setColumns(columns: Int) {
        viewportEngine.columns = columns.coerceAtLeast(1)
        rematerializeVisibleWindow()
    }

    override fun setItemCount(count: Int, itemFactory: (index: Int) -> NativeImpl<*>) {
        viewportEngine.itemCount = count.coerceAtLeast(0)
        itemFactoryFn = itemFactory
        rematerializeVisibleWindow()
    }

    override fun scrollToIndex(index: Int) {
        viewportEngine.scrollOffsetPx = viewportEngine.computeScrollOffsetForIndex(index)
        rematerializeVisibleWindow()
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
