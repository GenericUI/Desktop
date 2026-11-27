package com.nexustools.genericui.desktop.lazy

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.ui.Orientation
import com.nexustools.genericui.nativeimpl.lazy.LazyListNativeImpl
import com.nexustools.genericui.threading.PlatformReentrantLock
import com.nexustools.genericui.threading.withLock
import kotlin.math.roundToInt

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
    private val materializedLock: PlatformReentrantLock = PlatformReentrantLock()
    protected var onViewportScrollChangedListener: ((Float, Float) -> Unit)? = null
    protected var explicitViewportWindowProvided: Boolean = false
    private var viewportScrollScopeDepth: Int = 0
    var viewportVersion: Long = 0L
        private set

    private inline fun <T> withMaterializedLock(block: () -> T): T = materializedLock.withLock(block)

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
        nativeWidget.attributes["hostSyncedScrollOffsetPx"] = viewportEngine.scrollOffsetPx.roundToInt()
        nativeWidget.attributes["hostSyncedScrollVer"] = ((nativeWidget.attributes["hostSyncedScrollVer"] as? Long) ?: 0L) + 1L
        viewportVersion++
        if (DesktopNativeWidget.suppressTreeVersionBumpCount.value <= 0) {
            nativeWidget.notifyTreeInvalidated()
        }
    }

    override fun setOnViewportScrollChanged(listener: ((scrollOffsetPx: Float, viewportExtentPx: Float) -> Unit)?) {
        this.onViewportScrollChangedListener = listener
    }

    internal fun attachedChildImplsSnapshot(): List<NativeImpl<*>> = childImpls

    internal fun materializedItemsSnapshot(): Map<Int, NativeImpl<*>> = withMaterializedLock { LinkedHashMap(materializedItems) }

    override fun beginViewportScrollScope() {
        DesktopNativeWidget.activeViewportScrollHost = nativeWidget
        DesktopNativeWidget.activeViewportScrollScopeCount.addAndGet(1)
        viewportScrollScopeDepth++
    }

    override fun endViewportScrollScope() {
        if (viewportScrollScopeDepth > 0) {
            viewportScrollScopeDepth--
        }
        val remaining = DesktopNativeWidget.activeViewportScrollScopeCount.addAndGet(-1)
        if (remaining <= 0) {
            if (remaining < 0) {
                DesktopNativeWidget.activeViewportScrollScopeCount.value = 0
            }
            if (DesktopNativeWidget.activeViewportScrollHost === nativeWidget) {
                DesktopNativeWidget.activeViewportScrollHost = null
            }
        }
    }

    protected fun dispatchLayoutViewportExtent(viewportExtentPx: Float) {
        val clampedExtent = viewportExtentPx.coerceAtLeast(1f)
        if (kotlin.math.abs(viewportEngine.viewportExtentPx - clampedExtent) <= 0.5f &&
            !(viewportEngine.itemCount > 0 && materializedItems.isEmpty() && childImpls.isEmpty() && !explicitViewportWindowProvided)
        ) {
            return
        }
        DesktopNativeWidget.suppressTreeVersionBumpCount.addAndGet(1)
        beginViewportScrollScope()
        try {
            viewportEngine.viewportExtentPx = clampedExtent
            viewportVersion++
            val listener = onViewportScrollChangedListener
            if (listener != null) {
                listener(viewportEngine.scrollOffsetPx, clampedExtent)
            } else {
                rematerializeVisibleWindow()
            }
        } finally {
            endViewportScrollScope()
            val rem = DesktopNativeWidget.suppressTreeVersionBumpCount.addAndGet(-1)
            if (rem < 0) DesktopNativeWidget.suppressTreeVersionBumpCount.value = 0
        }
    }

    fun dispatchHostViewportScroll(scrollOffsetPx: Float, viewportExtentPx: Float) {
        val clampedScroll = scrollOffsetPx.coerceAtLeast(0f)
        val clampedExtent = viewportExtentPx.coerceAtLeast(1f)
        if (kotlin.math.abs(viewportEngine.scrollOffsetPx - clampedScroll) > 0.5f ||
            kotlin.math.abs(viewportEngine.viewportExtentPx - clampedExtent) > 0.5f
        ) {
            beginViewportScrollScope()
            try {
                viewportEngine.scrollOffsetPx = clampedScroll
                viewportEngine.viewportExtentPx = clampedExtent
                viewportVersion++
                if (DesktopNativeWidget.suppressTreeVersionBumpCount.value <= 0) {
                    nativeWidget.bumpSubtreeMutationVersion()
                }
                val listener = onViewportScrollChangedListener
                if (listener != null) {
                    listener(clampedScroll, clampedExtent)
                } else {
                    rematerializeVisibleWindow()
                }
            } finally {
                endViewportScrollScope()
            }
        }
    }

    override fun addChildNative(childImpl: NativeImpl<*>, index: Int) {
        withMaterializedLock {
            super.addChildNative(childImpl, index)
        }
    }

    override fun removeChildNative(childImpl: NativeImpl<*>) {
        withMaterializedLock {
            val iterator = materializedItems.entries.iterator()
            while (iterator.hasNext()) {
                if (iterator.next().value === childImpl) {
                    iterator.remove()
                }
            }
            super.removeChildNative(childImpl)
        }
    }

    override fun clearChildrenNative() {
        withMaterializedLock {
            materializedItems.clear()
            super.clearChildrenNative()
        }
    }

    override fun setItemCount(count: Int, itemFactory: (index: Int) -> NativeImpl<*>) {
        viewportEngine.itemCount = count.coerceAtLeast(0)
        nativeWidget.attributes["totalItemCount"] = viewportEngine.itemCount
        itemFactoryFn = itemFactory
        rematerializeVisibleWindow()
    }

    override fun notifyItemRangeChanged(startIndex: Int, count: Int) {
        val removed = withMaterializedLock {
            val list = ArrayList<NativeImpl<*>>()
            for (i in startIndex until (startIndex + count)) {
                materializedItems.remove(i)?.let { list.add(it) }
            }
            list
        }
        for (item in removed) {
            removeChildNative(item)
        }
        rematerializeVisibleWindow()
    }

    override fun scrollToIndex(index: Int) {
        viewportEngine.scrollOffsetPx = viewportEngine.computeScrollOffsetForIndex(index)
        nativeWidget.attributes["hostSyncedScrollOffsetPx"] = viewportEngine.scrollOffsetPx.roundToInt()
        nativeWidget.attributes["hostSyncedScrollVer"] = ((nativeWidget.attributes["hostSyncedScrollVer"] as? Long) ?: 0L) + 1L
        explicitViewportWindowProvided = false
        rematerializeVisibleWindow()
    }

    protected open fun rematerializeVisibleWindow() {
        viewportVersion++
        val factory = itemFactoryFn ?: return
        val totalCount = viewportEngine.itemCount.coerceAtLeast(0)
        val visibleRange = if (explicitViewportWindowProvided) {
            explicitViewportWindowProvided = false
            if (totalCount > 0 && lastActiveIndex >= firstActiveIndex && lastActiveIndex >= 0 && firstActiveIndex < totalCount) {
                val clampedFirst = firstActiveIndex.coerceIn(0, totalCount - 1)
                val clampedLast = lastActiveIndex.coerceIn(clampedFirst, totalCount - 1)
                firstActiveIndex = clampedFirst
                lastActiveIndex = clampedLast
                clampedFirst..clampedLast
            } else {
                firstActiveIndex = 0
                lastActiveIndex = -1
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

        val builtPairs = ArrayList<Pair<Int, NativeImpl<*>>>(
            if (visibleRange.isEmpty()) 0 else (visibleRange.last - visibleRange.first + 1)
        )
        for (idx in visibleRange) {
            val current = factory(idx)
            if (!current.isDisposed) {
                builtPairs.add(idx to current)
            }
        }

        val childrenAlreadySynced = withMaterializedLock {
            val keysToEvict = materializedItems.entries
                .filter { (idx, impl) -> idx !in 0 until totalCount || idx !in visibleRange || impl.isDisposed }
                .map { it.key }
            for (idx in keysToEvict) {
                materializedItems.remove(idx)
            }

            val desiredOrdered = ArrayList<NativeImpl<*>>(builtPairs.size)
            for ((idx, current) in builtPairs) {
                if (current.isDisposed) continue
                val previous = materializedItems[idx]
                if (previous !== current) {
                    materializedItems[idx] = current
                }
                if (desiredOrdered.none { it === current }) {
                    desiredOrdered.add(current)
                }
            }

            val desiredWidgets = ArrayList<DesktopNativeWidget>(desiredOrdered.size)
            for (item in desiredOrdered) {
                val w = item.nativeWidget as? DesktopNativeWidget
                if (w != null && !w.disposed && desiredWidgets.none { it === w }) {
                    desiredWidgets.add(w)
                }
            }

            val currentChildImpls = childImpls
            val currentWidgets = nativeWidget.children
            val alreadySynced = desiredOrdered.size == currentChildImpls.size &&
                desiredOrdered.indices.all { desiredOrdered[it] === currentChildImpls[it] } &&
                desiredWidgets.size == currentWidgets.size &&
                desiredWidgets.indices.all { desiredWidgets[it] === currentWidgets[it] }
            if (!alreadySynced) {
                val seenImpls = ArrayList<NativeImpl<*>>(desiredOrdered.size)
                for (existing in currentChildImpls) {
                    if (existing.isDisposed || desiredOrdered.none { it === existing } || seenImpls.any { it === existing }) {
                        super.removeChildNative(existing)
                    } else {
                        seenImpls.add(existing)
                    }
                }

                val seenWidgets = ArrayList<DesktopNativeWidget>(desiredWidgets.size)
                for (existingWidget in currentWidgets) {
                    if (existingWidget.disposed || desiredWidgets.none { it === existingWidget } || seenWidgets.any { it === existingWidget }) {
                        nativeWidget.removeChildWidget(existingWidget)
                    } else {
                        seenWidgets.add(existingWidget)
                    }
                }

                for ((targetIdx, item) in desiredOrdered.withIndex()) {
                    val itemWidget = item.nativeWidget as? DesktopNativeWidget
                    val latestChildImpls = childImpls
                    val latestWidgets = nativeWidget.children
                    val childMatches = targetIdx < latestChildImpls.size && latestChildImpls[targetIdx] === item
                    val widgetMatches = itemWidget == null ||
                        (targetIdx < latestWidgets.size && latestWidgets[targetIdx] === itemWidget)
                    if (!childMatches || !widgetMatches) {
                        super.addChildNative(item, targetIdx)
                    }
                }
            }
            alreadySynced
        }

        nativeWidget.notifyTreeInvalidated(visualOnly = childrenAlreadySynced)
    }
}
