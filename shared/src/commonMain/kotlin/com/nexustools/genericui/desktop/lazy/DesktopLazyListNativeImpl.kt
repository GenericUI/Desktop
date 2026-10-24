package com.nexustools.genericui.desktop.lazy

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.ui.Orientation
import com.nexustools.genericui.nativeimpl.lazy.LazyListNativeImpl

/**
 * Shared desktop base implementation of [LazyListNativeImpl] backed by [DesktopLazyViewportEngine].
 */
open class DesktopLazyListNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseContainerNativeImpl<W>(nativeWidget, "lazy-list"), LazyListNativeImpl<W> {

    val viewportEngine: DesktopLazyViewportEngine = DesktopLazyViewportEngine(columns = 1, defaultItemExtentPx = 40f)
    protected var orientationState: Orientation = Orientation.VERTICAL
    protected var itemFactoryFn: ((Int) -> NativeImpl<*>)? = null
    protected val materializedItems: MutableMap<Int, NativeImpl<*>> = linkedMapOf()

    override fun setOrientation(orientation: Orientation) {
        orientationState = orientation
        nativeWidget.attributes["orientation"] = orientation
    }

    override fun setItemCount(count: Int, itemFactory: (index: Int) -> NativeImpl<*>) {
        viewportEngine.itemCount = count.coerceAtLeast(0)
        itemFactoryFn = itemFactory
        rematerializeVisibleWindow()
    }

    override fun notifyItemRangeChanged(startIndex: Int, count: Int) {
        for (i in startIndex until (startIndex + count)) {
            materializedItems.remove(i)?.let { removeChildNative(it) }
        }
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
