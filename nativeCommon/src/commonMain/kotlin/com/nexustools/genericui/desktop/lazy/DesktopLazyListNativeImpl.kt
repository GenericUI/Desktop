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

    val viewportEngine: DesktopLazyViewportEngine = DesktopLazyViewportEngine(columns = 1, defaultItemExtentPx = 56f)
    protected var orientationState: Orientation = Orientation.VERTICAL
    protected var itemFactoryFn: ((Int) -> NativeImpl<*>)? = null
    protected val materializedItems: MutableMap<Int, NativeImpl<*>> = linkedMapOf()
    protected var onViewportScrollChangedListener: ((Float, Float) -> Unit)? = null
    protected var explicitViewportWindowProvided: Boolean = false

    var firstActiveIndex: Int = 0
        private set
    var lastActiveIndex: Int = -1
        private set
    var leadingSpacerPx: Float = 0f
        private set
    var trailingSpacerPx: Float = 0f
        private set
    var totalContentExtentPx: Float = 0f
        private set

    init {
        nativeWidget.attributes["isLazyList"] = true
        nativeWidget.attributes["leadingSpacerPx"] = 0f
        nativeWidget.attributes["trailingSpacerPx"] = 0f
        nativeWidget.attributes["totalContentExtentPx"] = 0f
        val scrollCallback: (Float, Float) -> Unit = { scrollOffsetPx, viewportExtentPx ->
            dispatchHostViewportScroll(scrollOffsetPx, viewportExtentPx)
        }
        nativeWidget.attributes["onHostViewportScroll"] = scrollCallback
    }

    override fun setOrientation(orientation: Orientation) {
        orientationState = orientation
        nativeWidget.attributes["orientation"] = orientation
    }

    override fun setEstimatedItemExtent(extentPx: Float, overscanItems: Int) {
        viewportEngine.estimatedItemExtentPx = extentPx.coerceAtLeast(1f)
        viewportEngine.overscanItems = overscanItems.coerceAtLeast(0)
        nativeWidget.attributes["estimatedItemExtentPx"] = viewportEngine.estimatedItemExtentPx
    }

    override fun updateViewportMetrics(
        scrollOffsetPx: Float,
        viewportExtentPx: Float,
        firstActiveIndex: Int,
        lastActiveIndex: Int,
        leadingSpacerPx: Float,
        trailingSpacerPx: Float,
        totalContentExtentPx: Float
    ) {
        explicitViewportWindowProvided = true
        viewportEngine.scrollOffsetPx = scrollOffsetPx.coerceAtLeast(0f)
        viewportEngine.viewportExtentPx = viewportExtentPx.coerceAtLeast(1f)
        this.firstActiveIndex = firstActiveIndex
        this.lastActiveIndex = lastActiveIndex
        this.leadingSpacerPx = leadingSpacerPx.coerceAtLeast(0f)
        this.trailingSpacerPx = trailingSpacerPx.coerceAtLeast(0f)
        this.totalContentExtentPx = totalContentExtentPx.coerceAtLeast(0f)
        nativeWidget.attributes["firstActiveIndex"] = firstActiveIndex
        nativeWidget.attributes["lastActiveIndex"] = lastActiveIndex
        nativeWidget.attributes["leadingSpacerPx"] = this.leadingSpacerPx
        nativeWidget.attributes["trailingSpacerPx"] = this.trailingSpacerPx
        nativeWidget.attributes["totalContentExtentPx"] = this.totalContentExtentPx
    }

    override fun setOnViewportScrollChanged(listener: ((scrollOffsetPx: Float, viewportExtentPx: Float) -> Unit)?) {
        this.onViewportScrollChangedListener = listener
    }

    fun dispatchHostViewportScroll(scrollOffsetPx: Float, viewportExtentPx: Float) {
        val clampedScroll = scrollOffsetPx.coerceAtLeast(0f)
        val clampedExtent = viewportExtentPx.coerceAtLeast(1f)
        if (kotlin.math.abs(viewportEngine.scrollOffsetPx - clampedScroll) > 0.5f ||
            kotlin.math.abs(viewportEngine.viewportExtentPx - clampedExtent) > 0.5f
        ) {
            viewportEngine.scrollOffsetPx = clampedScroll
            viewportEngine.viewportExtentPx = clampedExtent
            val listener = onViewportScrollChangedListener
            if (listener != null) {
                listener(clampedScroll, clampedExtent)
            } else {
                rematerializeVisibleWindow()
            }
        }
    }

    override fun setItemCount(count: Int, itemFactory: (index: Int) -> NativeImpl<*>) {
        viewportEngine.itemCount = count.coerceAtLeast(0)
        nativeWidget.attributes["totalItemCount"] = viewportEngine.itemCount
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
        explicitViewportWindowProvided = false
        rematerializeVisibleWindow()
    }

    protected open fun rematerializeVisibleWindow() {
        val factory = itemFactoryFn ?: return
        val visibleRange = if (explicitViewportWindowProvided) {
            explicitViewportWindowProvided = false
            if (lastActiveIndex >= firstActiveIndex && viewportEngine.itemCount > 0) {
                firstActiveIndex..lastActiveIndex
            } else {
                IntRange.EMPTY
            }
        } else {
            val computed = viewportEngine.computeVisibleRange()
            firstActiveIndex = if (computed.isEmpty()) 0 else computed.first
            lastActiveIndex = if (computed.isEmpty()) -1 else computed.last
            leadingSpacerPx = firstActiveIndex * viewportEngine.estimatedItemExtentPx
            trailingSpacerPx = if (viewportEngine.itemCount > 0 && lastActiveIndex >= firstActiveIndex) {
                ((viewportEngine.itemCount - 1 - lastActiveIndex).coerceAtLeast(0)) * viewportEngine.estimatedItemExtentPx
            } else {
                0f
            }
            totalContentExtentPx = viewportEngine.totalContentExtentPx
            nativeWidget.attributes["firstActiveIndex"] = firstActiveIndex
            nativeWidget.attributes["lastActiveIndex"] = lastActiveIndex
            nativeWidget.attributes["leadingSpacerPx"] = leadingSpacerPx
            nativeWidget.attributes["trailingSpacerPx"] = trailingSpacerPx
            nativeWidget.attributes["totalContentExtentPx"] = totalContentExtentPx
            computed
        }

        val toEvict = materializedItems.keys.filter { it !in visibleRange }
        for (idx in toEvict) {
            materializedItems.remove(idx)?.let { removeChildNative(it) }
        }
        for (idx in visibleRange) {
            if (idx !in materializedItems) {
                val created = factory(idx)
                materializedItems[idx] = created
                val insertAt = (idx - visibleRange.first).coerceAtLeast(0)
                addChildNative(created, insertAt)
            }
        }
    }
}
