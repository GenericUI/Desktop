package com.nexustools.genericui.desktop.layout

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockWindowNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.bootstrap.AlignItems
import com.nexustools.genericui.enums.bootstrap.AlignSelf
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.bootstrap.FlexWrap
import com.nexustools.genericui.enums.bootstrap.JustifyContent
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.enums.css.CssPosition
import com.nexustools.genericui.enums.css.CssUnit
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssEdgeLengths
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.layout.BrowserLayoutEngine
import com.nexustools.genericui.style.layout.ComputedLayoutBox
import com.nexustools.genericui.style.layout.IntrinsicSizeMeasurer
import com.nexustools.genericui.style.layout.LayoutNodeAdapter
import com.nexustools.genericui.style.layout.LayoutStyleSnapshot
import com.nexustools.genericui.style.transition.CssTransform2D
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared retained-widget layout controller for native desktop containers (`:windows`, `:macos`,
 * `:gtk`, `:kde`).
 *
 * Maintains DOM-ordered [logicalChildren] independently of native Z-order sorting, delegates
 * box-model, flexbox, grid, aspect-ratio, and layered sizing to `:system`'s [BrowserLayoutEngine],
 * resolves `CssPosition.ABSOLUTE` / `FIXED` against the proximal positioned ancestor (or root
 * window viewport for `FIXED`), resolves 2D [CssTransform] (`translate` px/`%`, `scaleX`/`scaleY`,
 * `rotateDeg`), and applies child `setBounds(...)` cleanly.
 */
class DesktopContainerLayoutController(
    private val host: DesktopBaseContainerNativeImpl<*>,
    private val defaultDisplay: CssDisplay? = null,
    private val defaultFlexDirection: FlexDirection? = null,
    private val defaultGapPx: Int = 0,
    private val defaultPadding: EdgeInsets = EdgeInsets.ZERO,
    private val isLayeredHost: Boolean = false,
    private val applyScaleToBounds: Boolean = false
) {
    private class MeasureCacheEntry(
        val availW: Int,
        val availH: Int,
        val hasMaxWidth: Boolean,
        val hasMaxHeight: Boolean,
        val ignoreRootExplicitHeight: Boolean,
        val heightIndependent: Boolean,
        val widthIndependentAbove: Boolean,
        val treeVer: Long,
        val attrVer: Long,
        val size: Size
    )

    private class SnapshotCacheEntry(
        val viewportW: Int,
        val viewportH: Int,
        val isLayoutRoot: Boolean,
        val indefiniteCol: Boolean,
        val layeredHost: Boolean,
        val singleContainerFill: Boolean,
        val measureMode: Boolean,
        val attrVer: Long,
        val compStyle: ComponentStyle?,
        val computed: ComputedStyle?,
        val snapshot: LayoutStyleSnapshot
    )

    @kotlin.concurrent.Volatile
    private var logicalChildrenList: List<NativeImpl<*>> = emptyList()
    private val logicalChildrenLock = kotlin.concurrent.AtomicInt(0)
    private val activeMeasuringHosts: HashSet<DesktopBaseNativeImpl<*>> = HashSet()
    private var isLayingOut: Boolean = false
    private var measureIndefiniteColumnHeight: Boolean = false
    private var lastSortedByZOrder: List<DesktopBaseNativeImpl<*>> = emptyList()
    private var lastSortedZAttrVer: Long = -1L
    private val measureCache = arrayOfNulls<MeasureCacheEntry>(16)
    private var measureCacheCursor: Int = 0
    private val snapshotCache = HashMap<DesktopBaseNativeImpl<*>, Array<SnapshotCacheEntry?>>()
    private var snapshotSlotCursor: Int = 0
    private var lastLayoutW: Int = -1
    private var lastLayoutH: Int = -1
    private var lastLayoutSubtreeVer: Long = -1L
    private var lastLayoutSubtreeAttrVer: Long = -1L
    private var cachedHostLayeredFlag: Boolean = false
    private var cachedHostLayeredVer: Long = -1L

    var onZOrderUpdated: ((List<DesktopBaseNativeImpl<*>>) -> Unit)? = null
    var onLayoutCompleted: ((List<DesktopBaseNativeImpl<*>>) -> Unit)? = null

    private inline fun <T> withLogicalChildrenLock(block: () -> T): T {
        while (!logicalChildrenLock.compareAndSet(0, 1)) {
        }
        return try {
            block()
        } finally {
            logicalChildrenLock.value = 0
        }
    }

    init {
        logicalChildrenList = host.rawChildImplsSnapshot()
    }

    fun addLogicalChild(childImpl: NativeImpl<*>, index: Int = -1) {
        withLogicalChildrenLock {
            val current = logicalChildrenList
            val next = ArrayList<NativeImpl<*>>(current.size + 1)
            for (item in current) {
                if (item !== childImpl) {
                    next.add(item)
                }
            }
            if (index < 0 || index >= next.size) {
                next.add(childImpl)
            } else {
                next.add(index, childImpl)
            }
            logicalChildrenList = next
        }
        invalidateZOrder()
    }

    fun removeLogicalChild(childImpl: NativeImpl<*>) {
        withLogicalChildrenLock {
            val current = logicalChildrenList
            if (current.any { it === childImpl }) {
                val next = ArrayList<NativeImpl<*>>(current.size)
                for (item in current) {
                    if (item !== childImpl) {
                        next.add(item)
                    }
                }
                logicalChildrenList = next
            }
        }
        invalidateZOrder()
        if (childImpl is DesktopBaseNativeImpl<*>) {
            snapshotCache.remove(childImpl)
        }
    }

    fun clearLogicalChildren() {
        withLogicalChildrenLock {
            logicalChildrenList = emptyList()
        }
        invalidateZOrder()
        snapshotCache.clear()
    }

    fun getLogicalChildren(): List<NativeImpl<*>> = logicalChildrenList

    fun invalidateZOrder() {
        lastSortedByZOrder = emptyList()
        lastSortedZAttrVer = -1L
        measureCache.fill(null)
        lastLayoutW = -1
    }

    fun resolveChildrenSortedByZIndex(): List<DesktopBaseNativeImpl<*>> {
        if (logicalChildrenList.isEmpty()) return emptyList()
        val curZAttrVer = host.nativeWidget.subtreeAttrMutationVersion
        if (lastSortedByZOrder.isNotEmpty() &&
            lastSortedZAttrVer == curZAttrVer &&
            lastSortedByZOrder.size == logicalChildrenList.size
        ) {
            return lastSortedByZOrder
        }
        val children = logicalChildrenList.filterIsInstance<DesktopBaseNativeImpl<*>>()
        val sorted = if (children.size <= 1) {
            children
        } else {
            val firstZ = children[0].nativeWidget.effectiveZIndex()
            var needsSort = false
            for (i in 1 until children.size) {
                if (children[i].nativeWidget.effectiveZIndex() != firstZ) {
                    needsSort = true
                    break
                }
            }
            if (!needsSort) {
                children
            } else {
                children.withIndex()
                    .sortedWith(compareBy({ it.value.nativeWidget.effectiveZIndex() }, { it.index }))
                    .map { it.value }
            }
        }
        lastSortedByZOrder = sorted
        lastSortedZAttrVer = curZAttrVer
        return sorted
    }

    fun measurePreferredSize(maxWidth: Float?, maxHeight: Float?): Size =
        measurePreferredSizeInternal(maxWidth, maxHeight, ignoreRootExplicitHeight = false)

    internal fun measureScrollableContentSize(maxWidth: Float?): Size =
        measurePreferredSizeInternal(maxWidth, maxHeight = null, ignoreRootExplicitHeight = true)

    private fun measurePreferredSizeInternal(
        maxWidth: Float?,
        maxHeight: Float?,
        ignoreRootExplicitHeight: Boolean
    ): Size {
        if (!activeMeasuringHosts.add(host)) {
            val expW = resolveExplicitWidthPx(host) ?: host.nativeWidget.bounds.width.takeIf { it > 0 } ?: 1
            val expH = if (ignoreRootExplicitHeight) {
                1
            } else {
                resolveExplicitHeightPx(host) ?: host.nativeWidget.bounds.height.takeIf { it > 0 } ?: 1
            }
            return Size(expW.coerceAtLeast(1), expH.coerceAtLeast(1))
        }
        return try {
            val safeAvailW = (maxWidth?.roundToInt() ?: resolveReferenceWidthPx(host)).coerceAtLeast(1)
            val safeAvailH = (maxHeight?.roundToInt() ?: 0).coerceAtLeast(0)
            val hasMaxW = (maxWidth != null)
            val hasMaxH = (maxHeight != null)
            val subTreeVer = host.nativeWidget.subtreeMutationVersion
            val subAttrVer = host.nativeWidget.subtreeAttrMutationVersion
            for (i in measureCache.indices) {
                val entry = measureCache[i] ?: continue
                if ((entry.availW == safeAvailW || (entry.widthIndependentAbove && safeAvailW >= entry.size.width)) &&
                    entry.hasMaxWidth == hasMaxW &&
                    entry.ignoreRootExplicitHeight == ignoreRootExplicitHeight &&
                    entry.treeVer == subTreeVer &&
                    entry.attrVer == subAttrVer &&
                    (entry.heightIndependent || (entry.hasMaxHeight == hasMaxH && entry.availH == safeAvailH))
                ) {
                    return entry.size
                }
            }
            val capturedSnapshots = HashMap<DesktopBaseNativeImpl<*>, LayoutStyleSnapshot>()
            val capturedMeasured = HashMap<DesktopBaseNativeImpl<*>, Size>()
            val engine = createEngine(
                root = host,
                measureMode = true,
                capturedSnapshots = capturedSnapshots,
                capturedMeasured = capturedMeasured
            )
            val rootSnapshot = buildSnapshotFor(
                impl = host,
                viewportW = safeAvailW,
                viewportH = safeAvailH.coerceAtLeast(1),
                isLayoutRoot = true,
                measureMode = true
            )
            val explicitRootW = resolveRootExplicitWidth(host, rootSnapshot, safeAvailW)
            val explicitRootH = if (ignoreRootExplicitHeight) {
                null
            } else {
                resolveRootExplicitHeight(host, rootSnapshot, safeAvailH)
            }
            if (explicitRootW != null && explicitRootH != null) {
                val resultSize = Size(explicitRootW.coerceAtLeast(0), explicitRootH.coerceAtLeast(0))
                val hUnit = rootSnapshot.height?.unit
                val isHIndep = hUnit != CssUnit.PERCENT && hUnit != CssUnit.VH
                val isWIndepAbove = rootSnapshot.width?.unit != CssUnit.PERCENT &&
                    rootSnapshot.width?.unit != CssUnit.VW &&
                    !host.nativeWidget.hasWidthUtilityClass &&
                    !host.nativeWidget.hasResponsiveLayoutClass
                if (host.nativeWidget.subtreeMutationVersion == subTreeVer &&
                    host.nativeWidget.subtreeAttrMutationVersion == subAttrVer
                ) {
                    measureCache[measureCacheCursor] = MeasureCacheEntry(
                        availW = safeAvailW,
                        availH = safeAvailH,
                        hasMaxWidth = hasMaxW,
                        hasMaxHeight = hasMaxH,
                        ignoreRootExplicitHeight = ignoreRootExplicitHeight,
                        heightIndependent = isHIndep,
                        widthIndependentAbove = isWIndepAbove,
                        treeVer = subTreeVer,
                        attrVer = subAttrVer,
                        size = resultSize
                    )
                    measureCacheCursor = (measureCacheCursor + 1) % measureCache.size
                }
                return resultSize
            }

            val computeW = explicitRootW ?: safeAvailW
            val computeH = explicitRootH ?: 0

            val isColumnFlexRoot = (rootSnapshot.display == CssDisplay.FLEX || rootSnapshot.display == CssDisplay.INLINE_FLEX) &&
                (rootSnapshot.flexDirection == FlexDirection.COLUMN ||
                    rootSnapshot.flexDirection == FlexDirection.COLUMN_REVERSE)
            val prevIndefiniteCol = measureIndefiniteColumnHeight
            measureIndefiniteColumnHeight = (explicitRootH == null && isColumnFlexRoot)
            val layoutBox = try {
                engine.computeLayout(host, Rect(0, 0, computeW, computeH))
            } finally {
                measureIndefiniteColumnHeight = prevIndefiniteCol
            }

            val children = logicalDesktopChildrenOf(host)
            val childResults = layoutBox.children

            var maxChildRight = layoutBox.contentBox.x
            var maxChildBottom = layoutBox.contentBox.y
            var visibleCount = 0
            for (i in children.indices) {
                val box = childResults.getOrNull(i) ?: continue
                if (!box.isVisibleInLayout) continue
                visibleCount++
                maxChildRight = max(maxChildRight, box.borderBox.right + box.margin.right)
                maxChildBottom = max(maxChildBottom, box.borderBox.bottom + box.margin.bottom)
            }

            val insetLeft = rootSnapshot.padding.left + rootSnapshot.border.left
            val insetRight = rootSnapshot.padding.right + rootSnapshot.border.right
            val insetTop = rootSnapshot.padding.top + rootSnapshot.border.top
            val insetBottom = rootSnapshot.padding.bottom + rootSnapshot.border.bottom
            val contentUsedW = if (visibleCount > 0) (maxChildRight - layoutBox.contentBox.x).coerceAtLeast(0) else 0
            val contentUsedH = if (visibleCount > 0) (maxChildBottom - layoutBox.contentBox.y).coerceAtLeast(0) else 0

            val outW = if (explicitRootW != null) {
                explicitRootW
            } else {
                val naturalContentW = if (!isEffectivelyLayered(host.nativeWidget)) {
                    val isFlexRow = (rootSnapshot.display == CssDisplay.FLEX ||
                        rootSnapshot.display == CssDisplay.INLINE_FLEX ||
                        rootSnapshot.display == CssDisplay.GRID ||
                        rootSnapshot.isGridRow) &&
                        (rootSnapshot.flexDirection == FlexDirection.ROW ||
                            rootSnapshot.flexDirection == FlexDirection.ROW_REVERSE)
                    val innerAvailW = (computeW - insetLeft - insetRight).coerceAtLeast(1)
                    val refH = safeAvailH.coerceAtLeast(1)
                    var sumOuterW = 0
                    var maxOuterW = 0
                    var flowCount = 0
                    for (i in children.indices) {
                        val child = children[i]
                        if (!child.isVisible()) continue
                        val cSnap = capturedSnapshots[child]
                            ?: buildSnapshotFor(child, innerAvailW, refH, isLayoutRoot = false, measureMode = true)
                        if (cSnap.display == CssDisplay.NONE) continue
                        if (cSnap.position == CssPosition.ABSOLUTE || cSnap.position == CssPosition.FIXED || cSnap.layerRole != null) continue
                        val box = childResults.getOrNull(i)
                        val explicitChildW = cSnap.width
                            ?.takeIf { !it.isAuto && !it.isNone && it.unit != CssUnit.PERCENT }
                            ?.resolvePx(
                                referencePx = innerAvailW.toFloat(),
                                viewportWidthPx = computeW.toFloat(),
                                viewportHeightPx = refH.toFloat()
                            )?.roundToInt()
                        val intrinsicW = explicitChildW
                            ?: capturedMeasured[child]?.width
                            ?: cSnap.flexBasis?.takeIf { !it.isAuto && it.unit != CssUnit.PERCENT }
                                ?.resolvePx(referencePx = innerAvailW.toFloat())?.roundToInt()
                            ?: box?.borderBox?.width
                            ?: 0
                        val mL = box?.margin?.left
                            ?: cSnap.marginLeft?.takeIf { !it.isAuto && it.unit != CssUnit.PERCENT }
                                ?.resolvePx(referencePx = innerAvailW.toFloat())?.roundToInt()
                            ?: 0
                        val mR = box?.margin?.right
                            ?: cSnap.marginRight?.takeIf { !it.isAuto && it.unit != CssUnit.PERCENT }
                                ?.resolvePx(referencePx = innerAvailW.toFloat())?.roundToInt()
                            ?: 0
                        val outerW = intrinsicW + mL + mR
                        sumOuterW += outerW
                        if (outerW > maxOuterW) maxOuterW = outerW
                        flowCount++
                    }
                    when {
                        flowCount == 0 -> contentUsedW
                        isFlexRow -> {
                            val rowW = sumOuterW + (flowCount - 1) * rootSnapshot.columnGapPx
                            if (maxWidth != null && rootSnapshot.flexWrap != FlexWrap.NOWRAP) {
                                rowW.coerceAtMost(innerAvailW)
                            } else {
                                rowW
                            }
                        }
                        else -> if (maxWidth != null) maxOuterW.coerceAtMost(innerAvailW) else maxOuterW
                    }
                } else {
                    contentUsedW
                }
                var unclampedW = naturalContentW + insetLeft + insetRight
                rootSnapshot.minWidth?.takeIf { !it.isAuto && !it.isNone && it.unit != CssUnit.PERCENT }
                    ?.resolvePx(referencePx = safeAvailW.toFloat(), viewportWidthPx = safeAvailW.toFloat(), viewportHeightPx = safeAvailH.toFloat())
                    ?.roundToInt()?.let { minW -> if (unclampedW < minW) unclampedW = minW }
                rootSnapshot.maxWidth?.takeIf { !it.isAuto && !it.isNone && (it.unit != CssUnit.PERCENT || safeAvailW > 0) }
                    ?.resolvePx(referencePx = safeAvailW.toFloat(), viewportWidthPx = safeAvailW.toFloat(), viewportHeightPx = safeAvailH.toFloat())
                    ?.roundToInt()?.let { maxW -> if (unclampedW > maxW) unclampedW = maxW }
                unclampedW
            }.coerceAtLeast(if (explicitRootW == 0) 0 else 1)

            val outH = (explicitRootH ?: (contentUsedH + insetTop + insetBottom)).coerceAtLeast(if (explicitRootH == 0) 0 else 1)
            val resultSize = Size(outW, outH)
            val isHeightIndependent = explicitRootH == null &&
                rootSnapshot.height?.unit.let { it != CssUnit.PERCENT && it != CssUnit.VH } &&
                rootSnapshot.minHeight?.unit.let { it != CssUnit.PERCENT && it != CssUnit.VH } &&
                rootSnapshot.maxHeight?.unit.let { it != CssUnit.PERCENT && it != CssUnit.VH } &&
                !host.nativeWidget.hasHeightUtilityClass &&
                children.all { child ->
                    if (!child.isVisible()) return@all true
                    val cSnap = capturedSnapshots[child]
                    if (cSnap == null || cSnap.display == CssDisplay.NONE) return@all cSnap != null
                    !child.nativeWidget.hasHeightUtilityClass &&
                        cSnap.height?.unit.let { it != CssUnit.PERCENT && it != CssUnit.VH } &&
                        cSnap.minHeight?.unit.let { it != CssUnit.PERCENT && it != CssUnit.VH } &&
                        cSnap.maxHeight?.unit.let { it != CssUnit.PERCENT && it != CssUnit.VH }
                }
            val isWidthIndependentAbove = outW < safeAvailW &&
                (explicitRootW == null || (rootSnapshot.width?.unit != CssUnit.PERCENT && rootSnapshot.width?.unit != CssUnit.VW)) &&
                !host.nativeWidget.hasResponsiveLayoutClass &&
                !host.nativeWidget.hasWidthUtilityClass &&
                !isEffectivelyLayered(host.nativeWidget) &&
                rootSnapshot.display != CssDisplay.GRID &&
                !rootSnapshot.isGridRow &&
                rootSnapshot.width?.unit != CssUnit.PERCENT &&
                rootSnapshot.width?.unit != CssUnit.VW &&
                rootSnapshot.minWidth?.unit != CssUnit.PERCENT &&
                rootSnapshot.minWidth?.unit != CssUnit.VW &&
                rootSnapshot.maxWidth?.unit != CssUnit.PERCENT &&
                rootSnapshot.maxWidth?.unit != CssUnit.VW &&
                !hasViewportRelativeSpacing(host.getComponentStyle(), host.resolveEffectiveStyle()) &&
                children.all { child ->
                    if (!child.isVisible()) return@all true
                    val cSnap = capturedSnapshots[child]
                    if (cSnap == null || cSnap.display == CssDisplay.NONE) return@all cSnap != null
                    cSnap.width?.unit.let { it != CssUnit.PERCENT && it != CssUnit.VW && it != CssUnit.VH } &&
                        cSnap.minWidth?.unit.let { it != CssUnit.PERCENT && it != CssUnit.VW && it != CssUnit.VH } &&
                        cSnap.maxWidth?.unit.let { it != CssUnit.PERCENT && it != CssUnit.VW && it != CssUnit.VH } &&
                        cSnap.flexBasis?.unit.let { it != CssUnit.PERCENT && it != CssUnit.VW && it != CssUnit.VH } &&
                        cSnap.gridColSpan == null &&
                        cSnap.aspectRatio == null &&
                        !child.nativeWidget.hasResponsiveLayoutClass &&
                        !child.nativeWidget.hasWidthUtilityClass &&
                        !hasViewportRelativeSpacing(child.getComponentStyle(), child.resolveEffectiveStyle())
                }
            if (host.nativeWidget.subtreeMutationVersion == subTreeVer &&
                host.nativeWidget.subtreeAttrMutationVersion == subAttrVer
            ) {
                measureCache[measureCacheCursor] = MeasureCacheEntry(
                    availW = safeAvailW,
                    availH = safeAvailH,
                    hasMaxWidth = hasMaxW,
                    hasMaxHeight = hasMaxH,
                    ignoreRootExplicitHeight = ignoreRootExplicitHeight,
                    heightIndependent = isHeightIndependent,
                    widthIndependentAbove = isWidthIndependentAbove,
                    treeVer = subTreeVer,
                    attrVer = subAttrVer,
                    size = resultSize
                )
                measureCacheCursor = (measureCacheCursor + 1) % measureCache.size
            }
            resultSize
        } finally {
            activeMeasuringHosts.remove(host)
        }
    }

    fun layoutInBounds(width: Float, height: Float): Boolean {
        if (isLayingOut) return false
        val w = if (width > 0f) {
            width.roundToInt().takeIf { it > 0 }
        } else {
            val explicitCssW = resolveExplicitCssWidthPx(host)
            if (explicitCssW == 0) return false
            explicitCssW
                ?: resolveExplicitWidthPx(host)
                ?: host.nativeWidget.preferredSize?.width
                ?: host.nativeWidget.bounds.width
        }
        val h = if (height > 0f) {
            height.roundToInt().takeIf { it > 0 }
        } else {
            val explicitCssH = resolveExplicitCssHeightPx(host)
            if (explicitCssH == 0) return false
            explicitCssH
                ?: resolveExplicitHeightPx(host)
                ?: host.nativeWidget.preferredSize?.height
                ?: host.nativeWidget.bounds.height
        }
        if ((w ?: 0) <= 0 && (h ?: 0) <= 0) return false

        val safeW = (w ?: 1).coerceAtLeast(1)
        val safeH = (h ?: 1).coerceAtLeast(1)

        val subVer = host.nativeWidget.subtreeMutationVersion
        val subAttrVer = host.nativeWidget.subtreeAttrMutationVersion
        if (safeW == lastLayoutW &&
            safeH == lastLayoutH &&
            subVer == lastLayoutSubtreeVer &&
            subAttrVer == lastLayoutSubtreeAttrVer
        ) {
            return false
        }
        if (logicalChildrenList.isEmpty()) {
            if (lastSortedByZOrder.isNotEmpty()) {
                lastSortedByZOrder = emptyList()
                onZOrderUpdated?.invoke(emptyList())
            }
            lastLayoutW = safeW
            lastLayoutH = safeH
            lastLayoutSubtreeVer = subVer
            lastLayoutSubtreeAttrVer = subAttrVer
            onLayoutCompleted?.invoke(emptyList())
            return false
        }

        isLayingOut = true
        return try {
            val capturedSnapshots = HashMap<DesktopBaseNativeImpl<*>, LayoutStyleSnapshot>()
            val engine = createEngine(host, measureMode = false, capturedSnapshots = capturedSnapshots)
            val result = engine.computeLayout(host, Rect(0, 0, safeW, safeH))
            val children = logicalDesktopChildrenOf(host)
            val childBoxes = result.children
            val zEntries = ArrayList<Triple<DesktopBaseNativeImpl<*>, Int, Int>>(children.size)

            for (i in children.indices) {
                val child = children[i]
                val snap = capturedSnapshots[child]
                    ?: buildSnapshotFor(child, safeW, safeH, isLayoutRoot = false)
                val rawBox = childBoxes.getOrNull(i)
                var targetRect = rawBox?.borderBox
                var effectiveZ = rawBox?.effectiveZIndex ?: snap.zIndex
                if (effectiveZ == 0) {
                    val widgetZ = child.nativeWidget.effectiveZIndex()
                    if (widgetZ != 0) {
                        effectiveZ = widgetZ
                    }
                }

                if (snap.display != CssDisplay.NONE &&
                    child.isVisible() &&
                    (snap.position == CssPosition.ABSOLUTE || snap.position == CssPosition.FIXED)
                ) {
                    val cb = resolveContainingBlockForPositionedChild(snap.position)
                    if (cb != null) {
                        val (ancImpl, offsetX, offsetY) = cb
                        val ancW = ancImpl.nativeWidget.bounds.width.takeIf { it > 0 }
                            ?: resolveExplicitWidthPx(ancImpl)
                            ?: safeW
                        val ancH = ancImpl.nativeWidget.bounds.height.takeIf { it > 0 }
                            ?: resolveExplicitHeightPx(ancImpl)
                            ?: safeH
                        val ancBox = computePositionedChildBox(ancImpl, child, ancW, ancH)
                        if (ancBox != null) {
                            targetRect = Rect(
                                x = ancBox.borderBox.x - offsetX,
                                y = ancBox.borderBox.y - offsetY,
                                width = ancBox.borderBox.width,
                                height = ancBox.borderBox.height
                            )
                            effectiveZ = ancBox.effectiveZIndex
                        }
                    }
                }

                val resolvedTransform = resolveEffectiveTransform(child)
                if (targetRect != null && snap.display != CssDisplay.NONE && child.isVisible()) {
                    val finalRect = if (applyScaleToBounds && resolvedTransform != null) {
                        applyScaleAroundCenter(targetRect, resolvedTransform)
                    } else {
                        targetRect
                    }
                    child.setResolvedTransform(resolvedTransform)
                    child.setBounds(finalRect)
                    if (child is DesktopBaseContainerNativeImpl<*>) {
                        child.layoutChildrenInBounds(finalRect.width.toFloat(), finalRect.height.toFloat())
                    }
                } else {
                    child.setResolvedTransform(resolvedTransform)
                }

                zEntries.add(Triple(child, effectiveZ, i))
            }

            val nextSorted = if (zEntries.size <= 1) {
                zEntries.map { it.first }
            } else {
                zEntries
                    .sortedWith(compareBy({ it.second }, { it.third }))
                    .map { it.first }
            }
            val prevSorted = lastSortedByZOrder
            val orderChanged = prevSorted.size != nextSorted.size ||
                prevSorted.indices.any { prevSorted[it] !== nextSorted[it] }
            lastSortedByZOrder = nextSorted
            if (orderChanged) {
                onZOrderUpdated?.invoke(nextSorted)
            }
            onLayoutCompleted?.invoke(nextSorted)
            if (host.nativeWidget.subtreeMutationVersion == subVer &&
                host.nativeWidget.subtreeAttrMutationVersion == subAttrVer
            ) {
                lastLayoutW = safeW
                lastLayoutH = safeH
                lastLayoutSubtreeVer = subVer
                lastLayoutSubtreeAttrVer = subAttrVer
                lastSortedZAttrVer = subAttrVer
            } else {
                lastLayoutW = -1
                lastSortedZAttrVer = -1L
            }
            true
        } finally {
            isLayingOut = false
        }
    }

    private fun computePositionedChildBox(
        containingBlock: DesktopBaseNativeImpl<*>,
        child: DesktopBaseNativeImpl<*>,
        viewportWidthPx: Int,
        viewportHeightPx: Int
    ): ComputedLayoutBox? {
        val safeW = viewportWidthPx.coerceAtLeast(1)
        val safeH = viewportHeightPx.coerceAtLeast(1)
        val engine = createEngine(containingBlock, measureMode = false, childSubset = listOf(child))
        return engine.computeLayout(containingBlock, Rect(0, 0, safeW, safeH)).children.firstOrNull()
    }

    private fun resolveContainingBlockForPositionedChild(
        childPosition: CssPosition
    ): Triple<DesktopBaseNativeImpl<*>, Int, Int>? {
        val hostWidget = host.nativeWidget
        if (childPosition == CssPosition.FIXED) {
            if (isWindowViewportHost(hostWidget)) return null
        } else {
            if (isWindowViewportHost(hostWidget) || isEffectivelyLayered(hostWidget)) return null
            if (resolveRawCssPosition(hostWidget) != CssPosition.STATIC) return null
        }

        var offsetX = hostWidget.bounds.x
        var offsetY = hostWidget.bounds.y
        var anc: DesktopNativeWidget? = hostWidget.parentWidget
        while (anc != null) {
            val ancImpl = anc.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
            if (ancImpl != null && (isWindowViewportHost(anc) ||
                    (childPosition != CssPosition.FIXED && isPositionedOrLayeredContainingBlock(anc)))
            ) {
                return Triple(ancImpl, offsetX, offsetY)
            }
            val ancParent = anc.parentWidget
            if (ancParent == null && ancImpl != null) {
                return Triple(ancImpl, offsetX, offsetY)
            }
            offsetX += anc.bounds.x
            offsetY += anc.bounds.y
            anc = ancParent
        }
        return null
    }

    private fun isWindowViewportHost(widget: DesktopNativeWidget): Boolean {
        val impl = widget.attributes["nativeImpl"]
        return impl is DesktopWindowNativeImpl<*> ||
            impl is DesktopDialogWindowNativeImpl<*> ||
            impl is DesktopDockWindowNativeImpl<*> ||
            widget.attributes["genericuiWindowRootHost"] == true
    }

    private fun resolveRawCssPosition(widget: DesktopNativeWidget): CssPosition = when (widget.effectivePosition()) {
        "relative" -> CssPosition.RELATIVE
        "absolute" -> CssPosition.ABSOLUTE
        "fixed" -> CssPosition.FIXED
        "sticky" -> CssPosition.STICKY
        else -> CssPosition.STATIC
    }

    private fun isPositionedOrLayeredContainingBlock(widget: DesktopNativeWidget): Boolean {
        if (isWindowViewportHost(widget)) return true
        if (isEffectivelyLayered(widget)) return true
        return resolveRawCssPosition(widget) != CssPosition.STATIC
    }

    private fun isEffectivelyLayered(widget: DesktopNativeWidget): Boolean {
        if (widget === host.nativeWidget) {
            if (isLayeredHost) return true
            val ver = widget.selfAttrMutationVersion
            if (cachedHostLayeredVer == ver) return cachedHostLayeredFlag
            val resolved = computeIsEffectivelyLayered(widget)
            cachedHostLayeredFlag = resolved
            cachedHostLayeredVer = ver
            return resolved
        }
        return computeIsEffectivelyLayered(widget)
    }

    private fun computeIsEffectivelyLayered(widget: DesktopNativeWidget): Boolean {
        val impl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val tag = resolveNormalizedTag(impl, widget)
        if (tag == "layeredcontainer" || tag == "layered-container" ||
            tag == "aspectratiocontainer" || tag == "aspect-ratio-container"
        ) {
            return true
        }
        if ((widget.attributes["isLayeredHost"] as? String)?.equals("true", ignoreCase = true) == true ||
            widget.attributes["isLayeredHost"] == true
        ) return true
        return "gui-layered-container" in widget.cachedAllClassTokens
    }

    private fun createEngine(
        root: DesktopBaseNativeImpl<*>,
        measureMode: Boolean,
        childSubset: List<DesktopBaseNativeImpl<*>>? = null,
        capturedSnapshots: MutableMap<DesktopBaseNativeImpl<*>, LayoutStyleSnapshot>? = null,
        capturedMeasured: MutableMap<DesktopBaseNativeImpl<*>, Size>? = null
    ): BrowserLayoutEngine<DesktopBaseNativeImpl<*>> {
        val adapter = object : LayoutNodeAdapter<DesktopBaseNativeImpl<*>> {
            override fun childrenOf(node: DesktopBaseNativeImpl<*>): List<DesktopBaseNativeImpl<*>> =
                if (node === root) (childSubset ?: logicalDesktopChildrenOf(root)) else emptyList()

            override fun layoutStyleOf(
                node: DesktopBaseNativeImpl<*>,
                viewportWidthPx: Int,
                viewportHeightPx: Int
            ): LayoutStyleSnapshot = buildSnapshotFor(
                impl = node,
                viewportW = viewportWidthPx,
                viewportH = viewportHeightPx,
                isLayoutRoot = (node === root),
                measureMode = measureMode
            ).also { snap ->
                if (node !== root && capturedSnapshots != null) {
                    capturedSnapshots[node] = snap
                }
            }

            override fun tagNameOf(node: DesktopBaseNativeImpl<*>): String =
                resolveTagName(node)
        }

        val measurer = IntrinsicSizeMeasurer<DesktopBaseNativeImpl<*>> { node, availableWidthPx, availableHeightPx ->
            val cachedSnap = capturedSnapshots?.get(node)
            val normalizedTag = resolveTagName(node)
                .trim()
                .lowercase()
                .removePrefix("genericui:")
            val subtractsChrome = node is DesktopBaseContainerNativeImpl<*> &&
                node.hasLayoutController() &&
                normalizedTag !in BORDER_BOX_MEASURED_TAGS
            val chromeSnap = if (subtractsChrome) {
                cachedSnap ?: buildSnapshotFor(
                    impl = node,
                    viewportW = availableWidthPx.coerceAtLeast(1),
                    viewportH = availableHeightPx.coerceAtLeast(1),
                    isLayoutRoot = false,
                    measureMode = true
                )
            } else {
                null
            }
            val horizChrome = chromeSnap?.let {
                it.padding.left + it.padding.right + it.border.left + it.border.right
            } ?: 0
            val vertChrome = chromeSnap?.let {
                it.padding.top + it.padding.bottom + it.border.top + it.border.bottom
            } ?: 0
            val explicitCssW = if (cachedSnap != null) resolveNonPercentPx(cachedSnap.width)?.coerceAtLeast(0) else resolveExplicitCssWidthPx(node)
            val explicitCssH = if (cachedSnap != null) resolveNonPercentPx(cachedSnap.height)?.coerceAtLeast(0) else resolveExplicitCssHeightPx(node)
            val expW = explicitCssW ?: node.nativeWidget.preferredSize?.width?.takeIf { it > 0 }
            val expH = explicitCssH ?: node.nativeWidget.preferredSize?.height?.takeIf { it > 0 }
            if (expW != null && expH != null) {
                val sz = Size(expW.coerceAtLeast(0), expH.coerceAtLeast(0))
                if (node !== root && capturedMeasured != null) {
                    capturedMeasured[node] = sz
                }
                return@IntrinsicSizeMeasurer if (horizChrome > 0 || vertChrome > 0) {
                    Size((sz.width - horizChrome).coerceAtLeast(0), (sz.height - vertChrome).coerceAtLeast(0))
                } else {
                    sz
                }
            }
            val measured = node.measureIntrinsicSize(
                maxWidth = (expW ?: (availableWidthPx + horizChrome)).coerceAtLeast(0).toFloat(),
                maxHeight = if (expH != null) {
                    expH.coerceAtLeast(0).toFloat()
                } else if (availableHeightPx > 0) {
                    (availableHeightPx + vertChrome).toFloat()
                } else {
                    null
                }
            )
            val borderSize = Size(
                width = expW ?: measured.width.coerceAtLeast(0),
                height = expH ?: measured.height.coerceAtLeast(0)
            )
            if (node !== root && capturedMeasured != null) {
                capturedMeasured[node] = borderSize
            }
            if (horizChrome > 0 || vertChrome > 0) {
                Size(
                    (borderSize.width - horizChrome).coerceAtLeast(0),
                    (borderSize.height - vertChrome).coerceAtLeast(0)
                )
            } else {
                borderSize
            }
        }

        return BrowserLayoutEngine(adapter, measurer)
    }

    private fun logicalDesktopChildrenOf(node: DesktopBaseNativeImpl<*>): List<DesktopBaseNativeImpl<*>> {
        val raw: List<NativeImpl<*>> = when {
            node === host -> logicalChildrenList
            node is DesktopBaseContainerNativeImpl<*> -> node.getLogicalChildren()
            else -> emptyList()
        }
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<DesktopBaseNativeImpl<*>>(raw.size)
        for (item in raw) {
            if (item is DesktopBaseNativeImpl<*>) {
                out.add(item)
            }
        }
        return out
    }

    private fun responsiveBreakpointTier(viewportW: Int): Int = when {
        viewportW < 576 -> 0
        viewportW < 768 -> 1
        viewportW < 992 -> 2
        viewportW < 1200 -> 3
        viewportW < 1400 -> 4
        else -> 5
    }

    private fun isViewportRelativeLen(len: CssLength?): Boolean =
        len != null && (len.unit == CssUnit.PERCENT || len.unit == CssUnit.VW || len.unit == CssUnit.VH)

    private fun isViewportRelativeEdges(edges: CssEdgeLengths?): Boolean =
        edges != null && (
            isViewportRelativeLen(edges.top) ||
                isViewportRelativeLen(edges.right) ||
                isViewportRelativeLen(edges.bottom) ||
                isViewportRelativeLen(edges.left)
            )

    private fun hasViewportRelativeSpacing(compStyle: ComponentStyle?, computed: ComputedStyle?): Boolean {
        if (compStyle != null && (
                isViewportRelativeEdges(compStyle.margin) ||
                    isViewportRelativeLen(compStyle.marginTop) ||
                    isViewportRelativeLen(compStyle.marginRight) ||
                    isViewportRelativeLen(compStyle.marginBottom) ||
                    isViewportRelativeLen(compStyle.marginLeft) ||
                    isViewportRelativeEdges(compStyle.padding) ||
                    isViewportRelativeLen(compStyle.paddingTop) ||
                    isViewportRelativeLen(compStyle.paddingRight) ||
                    isViewportRelativeLen(compStyle.paddingBottom) ||
                    isViewportRelativeLen(compStyle.paddingLeft) ||
                    isViewportRelativeLen(compStyle.gap) ||
                    isViewportRelativeLen(compStyle.rowGap) ||
                    isViewportRelativeLen(compStyle.columnGap)
                )
        ) {
            return true
        }
        if (computed != null && (
                isViewportRelativeEdges(computed.margin) ||
                    isViewportRelativeLen(computed.marginTop) ||
                    isViewportRelativeLen(computed.marginRight) ||
                    isViewportRelativeLen(computed.marginBottom) ||
                    isViewportRelativeLen(computed.marginLeft) ||
                    isViewportRelativeEdges(computed.padding) ||
                    isViewportRelativeLen(computed.paddingTop) ||
                    isViewportRelativeLen(computed.paddingRight) ||
                    isViewportRelativeLen(computed.paddingBottom) ||
                    isViewportRelativeLen(computed.paddingLeft) ||
                    isViewportRelativeLen(computed.gap) ||
                    isViewportRelativeLen(computed.rowGap) ||
                    isViewportRelativeLen(computed.columnGap)
                )
        ) {
            return true
        }
        return false
    }

    private fun buildSnapshotFor(
        impl: DesktopBaseNativeImpl<*>,
        viewportW: Int,
        viewportH: Int,
        isLayoutRoot: Boolean,
        measureMode: Boolean = false
    ): LayoutStyleSnapshot {
        val widget = impl.nativeWidget
        if (!impl.isVisible() || !widget.visible || (!isLayoutRoot && widget.attributes["mountedInTitleBar"] == true)) {
            return LayoutStyleSnapshot(display = CssDisplay.NONE)
        }
        val nodeAttrVer = widget.selfAttrMutationVersion
        val compStyle = impl.getComponentStyle()
        val computed = impl.resolveEffectiveStyle()
        val viewportIndependent = !hasViewportRelativeSpacing(compStyle, computed)
        val cacheKeyW: Int
        val cacheKeyH: Int
        if (viewportIndependent) {
            cacheKeyW = if (widget.hasResponsiveLayoutClass) {
                responsiveBreakpointTier(viewportW)
            } else {
                -1
            }
            val hasHeightConstraint = measureMode && !isLayoutRoot && viewportH <= 1 && (
                compStyle?.height?.unit == CssUnit.PERCENT ||
                    compStyle?.minHeight?.unit == CssUnit.PERCENT ||
                    compStyle?.maxHeight?.unit == CssUnit.PERCENT ||
                    computed?.height?.endsWith('%') == true ||
                    computed?.minHeight?.endsWith('%') == true ||
                    computed?.maxHeight?.endsWith('%') == true ||
                    widget.hasHeightUtilityClass
                )
            cacheKeyH = if (hasHeightConstraint) 0 else 1
        } else {
            cacheKeyW = viewportW
            cacheKeyH = viewportH
        }
        val flagIndefiniteCol = measureMode && !isLayoutRoot && measureIndefiniteColumnHeight
        val flagLayeredHost = !isLayoutRoot && isEffectivelyLayered(host.nativeWidget)
        val isStyledHost = !isLayoutRoot && isStyledContainerHost()
        val singleContainerFill = !isLayoutRoot &&
            !measureMode &&
            impl is DesktopBaseContainerNativeImpl<*> &&
            isStyledHost &&
            logicalDesktopChildrenOf(host).size == 1 &&
            !hasExplicitHostAlignment(host)
        val cacheMeasureMode = measureMode && (
            isLayoutRoot ||
                flagIndefiniteCol ||
                viewportH <= 1 ||
                isLikelyFragmentWidget(impl, widget) ||
                isLikelyAppWidget(impl, widget) ||
                (isStyledHost && impl is DesktopBaseContainerNativeImpl<*>)
            )
        val slots = snapshotCache[impl]
        if (slots != null) {
            for (entry in slots) {
                if (entry != null &&
                    entry.viewportW == cacheKeyW &&
                    entry.viewportH == cacheKeyH &&
                    entry.isLayoutRoot == isLayoutRoot &&
                    entry.indefiniteCol == flagIndefiniteCol &&
                    entry.layeredHost == flagLayeredHost &&
                    entry.singleContainerFill == singleContainerFill &&
                    entry.measureMode == cacheMeasureMode &&
                    entry.attrVer == nodeAttrVer &&
                    entry.compStyle === compStyle &&
                    entry.computed === computed
                ) {
                    if (isLayoutRoot && !measureMode && viewportIndependent) {
                        val wLen = CssLength.px(viewportW.toFloat())
                        val hLen = CssLength.px(viewportH.toFloat())
                        if (entry.snapshot.width == wLen && entry.snapshot.height == hLen) {
                            return entry.snapshot
                        }
                        return entry.snapshot.copy(width = wLen, height = hLen)
                    }
                    return entry.snapshot
                }
            }
        }

        val attrs = widget.snapshotStringAttributesMap()
        val normalizedTag = resolveNormalizedTag(impl, widget, attrs)
        val classes = LinkedHashSet<String>()
        addSpaceSeparatedTokens(impl.componentClassPrefix, classes)
        classes.addAll(impl.getClassTokensSet())
        classes.addAll(widget.cachedAllClassTokens)
        val styleProps = linkedMapOf<String, String>()
        if (computed != null) {
            computed.display?.let { styleProps["display"] = it }
            computed.visibility?.cssValue?.let { styleProps["visibility"] = it }
            computed.position?.let { styleProps["position"] = it }
            computed.top?.let { styleProps["top"] = it }
            computed.right?.let { styleProps["right"] = it }
            computed.bottom?.let { styleProps["bottom"] = it }
            computed.left?.let { styleProps["left"] = it }
            computed.boxSizing?.cssValue?.let { styleProps["box-sizing"] = it }
            computed.width?.let { styleProps["width"] = it }
            computed.height?.let { styleProps["height"] = it }
            computed.minWidth?.let { styleProps["min-width"] = it }
            computed.minHeight?.let { styleProps["min-height"] = it }
            computed.maxWidth?.let { styleProps["max-width"] = it }
            computed.maxHeight?.let { styleProps["max-height"] = it }
            computed.aspectRatio?.let { styleProps["aspect-ratio"] = it.toString() }
            computed.padding?.toCssString()?.let { styleProps["padding"] = it }
            computed.paddingTop?.toCssString()?.let { styleProps["padding-top"] = it }
            computed.paddingRight?.toCssString()?.let { styleProps["padding-right"] = it }
            computed.paddingBottom?.toCssString()?.let { styleProps["padding-bottom"] = it }
            computed.paddingLeft?.toCssString()?.let { styleProps["padding-left"] = it }
            computed.margin?.toCssString()?.let { styleProps["margin"] = it }
            computed.marginTop?.toCssString()?.let { styleProps["margin-top"] = it }
            computed.marginRight?.toCssString()?.let { styleProps["margin-right"] = it }
            computed.marginBottom?.toCssString()?.let { styleProps["margin-bottom"] = it }
            computed.marginLeft?.toCssString()?.let { styleProps["margin-left"] = it }
            computed.borderWidthPx?.let { styleProps["border-width"] = "${it}px" }
            computed.flexDirection?.let { styleProps["flex-direction"] = it.name.lowercase().replace('_', '-') }
            computed.flexWrap?.let { styleProps["flex-wrap"] = it.name.lowercase().replace('_', '-') }
            computed.justifyContent?.let {
                styleProps["justify-content"] = when (it) {
                    JustifyContent.START -> "flex-start"
                    JustifyContent.END -> "flex-end"
                    JustifyContent.CENTER -> "center"
                    JustifyContent.BETWEEN -> "space-between"
                    JustifyContent.AROUND -> "space-around"
                    JustifyContent.EVENLY -> "space-evenly"
                }
            }
            computed.alignItems?.let {
                styleProps["align-items"] = when (it) {
                    AlignItems.START -> "flex-start"
                    AlignItems.END -> "flex-end"
                    AlignItems.CENTER -> "center"
                    AlignItems.BASELINE -> "baseline"
                    AlignItems.STRETCH -> "stretch"
                }
            }
            computed.alignSelf?.let {
                styleProps["align-self"] = when (it) {
                    AlignSelf.AUTO -> "auto"
                    AlignSelf.START -> "flex-start"
                    AlignSelf.END -> "flex-end"
                    AlignSelf.CENTER -> "center"
                    AlignSelf.BASELINE -> "baseline"
                    AlignSelf.STRETCH -> "stretch"
                }
            }
            computed.alignContent?.cssValue?.let { styleProps["align-content"] = it }
            computed.flexGrow?.let { styleProps["flex-grow"] = it.toString() }
            computed.flexShrink?.let { styleProps["flex-shrink"] = it.toString() }
            computed.flexBasis?.toCssString()?.let { styleProps["flex-basis"] = it }
            computed.order?.let { styleProps["order"] = it.toString() }
            computed.gap?.toCssString()?.let { styleProps["gap"] = it }
            computed.rowGap?.toCssString()?.let { styleProps["row-gap"] = it }
            computed.columnGap?.toCssString()?.let { styleProps["column-gap"] = it }
            computed.overflow?.let { styleProps["overflow"] = it }
            computed.overflowX?.let { styleProps["overflow-x"] = it.name.lowercase() }
            computed.overflowY?.let { styleProps["overflow-y"] = it.name.lowercase() }
            computed.zIndex?.let { styleProps["z-index"] = it.toString() }
            computed.transform?.let { styleProps["transform"] = it }
            computed.transform2D?.let { styleProps.putIfMissing("transform", it.toCssString()) }
        }

        val snapCompStyle = impl.getComponentStyle()
        if (snapCompStyle != null && !snapCompStyle.isEmpty()) {
            applyComponentStyleLayoutProps(snapCompStyle, styleProps)
        }

        // Sync attributes written via NativeImpl.setAttribute or Bootstrap/layout sync
        attrs["display"]?.let { styleProps.putIfMissing("display", it) }
        attrs["flexDirection"]?.let { styleProps.putIfMissing("flex-direction", it) }
        attrs["flexWrap"]?.let { styleProps.putIfMissing("flex-wrap", it) }
        attrs["justifyContent"]?.let { styleProps.putIfMissing("justify-content", it) }
        attrs["alignItems"]?.let { styleProps.putIfMissing("align-items", it) }
        attrs["position"]?.let { styleProps.putIfMissing("position", it) }
        attrs["zIndex"]?.takeIf { safeToIntOrNull(it) != null }?.let { styleProps.putIfMissing("z-index", it) }
        attrs["aspectRatio"]?.takeIf { it.isNotBlank() }?.let { styleProps.putIfMissing("aspect-ratio", it) }
        fun putLengthAttrIfMissing(attrKey: String, cssProp: String) {
            attrs[attrKey]?.takeIf { it.isNotBlank() }?.let { styleProps.putIfMissing(cssProp, it) }
        }
        putLengthAttrIfMissing("styleWidth", "width")
        putLengthAttrIfMissing("styleHeight", "height")
        putLengthAttrIfMissing("styleMinWidth", "min-width")
        putLengthAttrIfMissing("styleMinHeight", "min-height")
        putLengthAttrIfMissing("styleMaxWidth", "max-width")
        putLengthAttrIfMissing("styleMaxHeight", "max-height")
        putLengthAttrIfMissing("styleTop", "top")
        putLengthAttrIfMissing("styleRight", "right")
        putLengthAttrIfMissing("styleBottom", "bottom")
        putLengthAttrIfMissing("styleLeft", "left")
        attrs["flexGrow"]?.takeIf { safeToFloatOrNull(it) != null }?.let { styleProps.putIfMissing("flex-grow", it) }
        attrs["flexShrink"]?.takeIf { safeToFloatOrNull(it) != null }?.let { styleProps.putIfMissing("flex-shrink", it) }
        putLengthAttrIfMissing("flexBasis", "flex-basis")
        safeToFloatOrNull(attrs["gapPx"])?.roundToInt()?.let { styleProps.putIfMissing("gap", "${it}px") }
        safeToFloatOrNull(attrs["rowGapPx"])?.roundToInt()?.let { styleProps.putIfMissing("row-gap", "${it}px") }
        safeToFloatOrNull(attrs["columnGapPx"])?.roundToInt()?.let { styleProps.putIfMissing("column-gap", "${it}px") }
        resolveEffectiveTransform(impl)?.let { tf ->
            if (!tf.isIdentity) {
                styleProps["transform"] = tf.toCssString()
            }
        }

        if (isLayoutRoot) {
            if (defaultDisplay != null && "display" !in styleProps && classes.none { it.startsWith("d-") }) {
                styleProps["display"] = defaultDisplay.name.lowercase().replace('_', '-')
            }
            if (defaultFlexDirection != null && "flex-direction" !in styleProps &&
                classes.none { it.startsWith("flex-row") || it.startsWith("flex-column") }
            ) {
                styleProps["flex-direction"] = defaultFlexDirection.name.lowercase().replace('_', '-')
            }
            if (defaultGapPx > 0 && "gap" !in styleProps && "row-gap" !in styleProps && "column-gap" !in styleProps) {
                styleProps["gap"] = "${defaultGapPx}px"
            }
        }

        val dataGenericUiTag = attrs["data-genericui-tag"]?.trim()?.lowercase()?.removePrefix("genericui:")
        if (normalizedTag == "card" || "card" in classes) {
            if ("display" !in styleProps && classes.none { it.startsWith("d-") }) {
                styleProps["display"] = "flex"
                styleProps.putIfMissing("flex-direction", "column")
            }
        } else if (normalizedTag == "alert" || "alert" in classes) {
            if ("display" !in styleProps && classes.none { it.startsWith("d-") }) {
                styleProps["display"] = "flex"
                styleProps.putIfMissing("flex-direction", "row")
                styleProps.putIfMissing("align-items", "center")
            }
        } else if (
            normalizedTag == "fragment-container" ||
            normalizedTag == "fragmentcontainer" ||
            normalizedTag == "fragment" ||
            "fragment-container" in classes ||
            "fragment-host" in classes ||
            "fragment-stage" in classes ||
            "fragment" in classes
        ) {
            if ("display" !in styleProps && classes.none { it.startsWith("d-") }) {
                styleProps["display"] = "flex"
                styleProps.putIfMissing("flex-direction", "column")
            }
            if (!isLayoutRoot) {
                styleProps.putIfMissing("width", "100%")
                styleProps.putIfMissing("flex-grow", "1")
                styleProps.putIfMissing("min-height", "0px")
                if (!measureMode) {
                    styleProps.putIfMissing("height", "100%")
                }
            }
        } else if (normalizedTag == "app" || dataGenericUiTag == "app") {
            if (!isLayoutRoot) {
                if (!hasWidthUtilityClass(classes) && resolveExplicitWidthPx(impl) == null) {
                    styleProps.putIfMissing("width", "100%")
                }
                styleProps.putIfMissing("flex-grow", "1")
                styleProps.putIfMissing("min-height", "0px")
                if (!measureMode &&
                    "max-height" !in styleProps &&
                    "aspect-ratio" !in styleProps &&
                    !hasHeightUtilityClass(classes) &&
                    resolveExplicitHeightPx(impl) == null
                ) {
                    styleProps.putIfMissing("height", "100%")
                }
            }
        }
        if (singleContainerFill &&
            "height" !in styleProps &&
            "max-height" !in styleProps &&
            "aspect-ratio" !in styleProps &&
            !hasHeightUtilityClass(classes) &&
            resolveExplicitHeightPx(impl) == null
        ) {
            styleProps.putIfMissing("min-height", "0px")
            styleProps.putIfMissing("height", "100%")
        }

        val overlayRole = attrs[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE]?.trim()?.lowercase()
        if (overlayRole == DesktopStageOverlaySpec.ROLE_CENTER_ZONE) {
            styleProps.putIfMissing("justify-content", "center")
            styleProps.putIfMissing("align-items", "center")
        }

        val spacerFill = attrs["spacerFill"]?.equals("true", ignoreCase = true) == true
        val spacerWeight = safeToFloatOrNull(attrs["spacerWeight"])
        if (spacerFill || (spacerWeight != null && spacerWeight > 0f)) {
            styleProps.putIfMissing("flex-grow", (spacerWeight ?: 1f).toString())
            styleProps.putIfMissing("flex-basis", "0px")
        }
        safeToIntOrNull(attrs["spacerWidthPx"])?.takeIf { it > 0 }?.let {
            styleProps.putIfMissing("width", "${it}px")
        }
        safeToIntOrNull(attrs["spacerHeightPx"])?.takeIf { it > 0 }?.let {
            styleProps.putIfMissing("height", "${it}px")
        }
        safeToFloatOrNull(attrs["data-stack-weight"])?.takeIf { it > 0f }?.let { weight ->
            styleProps.putIfMissing("flex-grow", weight.toString())
            styleProps.putIfMissing("flex-basis", "0px")
        }
        safeToFloatOrNull(attrs["data-stack-aspect-ratio"])?.takeIf { it > 0f }?.let { ar ->
            styleProps.putIfMissing("aspect-ratio", ar.toString())
        }
        if (attrs["data-stack-shrink-cross"]?.equals("true", ignoreCase = true) == true) {
            styleProps.putIfMissing("flex-shrink", "1")
        }

        val expW = resolveExplicitWidthPx(impl)?.takeIf { it > 0 }
        val expH = resolveExplicitHeightPx(impl)?.takeIf { it > 0 }
        if (expW != null && "width" !in styleProps && !hasWidthUtilityClass(classes)) {
            styleProps["width"] = "${expW}px"
        }
        if (expH != null && "height" !in styleProps && !hasHeightUtilityClass(classes)) {
            styleProps["height"] = "${expH}px"
        }

        if (measureMode && !isLayoutRoot) {
            if (measureIndefiniteColumnHeight) {
                styleProps["flex-shrink"] = "0"
                styleProps["flex-grow"] = "0"
            }
            if (viewportH <= 1) {
                stripPercentConstraintsAndClasses(
                    styleProps = styleProps,
                    classes = classes,
                    props = INDEFINITE_HEIGHT_PERCENT_PROPS,
                    classNames = INDEFINITE_HEIGHT_UTILITY_CLASSES
                )
            }
        }

        if (!isLayoutRoot && isEffectivelyLayered(widget) && "position" !in styleProps) {
            styleProps["position"] = "relative"
        }

        if (!isLayoutRoot && isEffectivelyLayered(host.nativeWidget)) {
            val hasLayerClass = "gui-layer-fill" in classes || "gui-layer-float" in classes
            val hasLayerSizing = !attrs["layerSizingMode"].isNullOrBlank() || !attrs["layerRole"].isNullOrBlank()
            val explicitPos = styleProps["position"]?.lowercase()
            if (!hasLayerClass && !hasLayerSizing && explicitPos != "absolute" && explicitPos != "fixed") {
                classes.add("gui-layer-fill")
            }
        }

        var margin = impl.getEffectiveMarginInsets(viewportW.toFloat(), viewportH.toFloat())
        if (isLayoutRoot) {
            styleProps.remove("position")
            styleProps.remove("top")
            styleProps.remove("right")
            styleProps.remove("bottom")
            styleProps.remove("left")
            styleProps.remove("transform")
            styleProps.remove("margin")
            styleProps.remove("margin-top")
            styleProps.remove("margin-right")
            styleProps.remove("margin-bottom")
            styleProps.remove("margin-left")
            margin = EdgeInsets.ZERO
            if (!measureMode) {
                styleProps["width"] = "${viewportW}px"
                styleProps["height"] = "${viewportH}px"
                for (prop in ROOT_CONSTRAINT_PROPS) {
                    styleProps.remove(prop)
                }
                if (widget.hasRootConstraintUtilityClass) {
                    classes.removeAll { cls ->
                        cls.equals("mw-100", ignoreCase = true) || cls.equals("mh-100", ignoreCase = true)
                    }
                }
            } else {
                stripPercentConstraintsAndClasses(
                    styleProps = styleProps,
                    classes = classes,
                    props = ROOT_CONSTRAINT_PROPS,
                    classNames = ROOT_CONSTRAINT_UTILITY_CLASSES
                )
                if (viewportH <= 1) {
                    stripPercentConstraintsAndClasses(
                        styleProps = styleProps,
                        classes = classes,
                        props = INDEFINITE_HEIGHT_PERCENT_PROPS,
                        classNames = INDEFINITE_HEIGHT_UTILITY_CLASSES
                    )
                }
            }
        }

        var padding = impl.getEffectivePaddingInsets(viewportW.toFloat(), viewportH.toFloat())
        if (isLayoutRoot && padding == EdgeInsets.ZERO && defaultPadding != EdgeInsets.ZERO && "padding" !in styleProps) {
            padding = defaultPadding
        }

        val borderPx = safeToFloatOrNull(attrs["borderWidthPx"])?.roundToInt()
            ?: snapCompStyle?.borderWidthPx?.roundToInt()
            ?: computed?.borderWidthPx?.roundToInt()
            ?: 0
        if (borderPx > 0 && "border-width" !in styleProps && "border" !in styleProps) {
            styleProps["border-width"] = "${borderPx}px"
        }

        val snapResult = LayoutStyleSnapshot.fromAttributesAndStyles(
            tagName = normalizedTag,
            classes = classes,
            attributes = attrs,
            styleProps = styleProps,
            explicitBounds = Rect(0, 0, expW ?: 0, expH ?: 0),
            padding = padding,
            margin = margin,
            visible = impl.isVisible() && widget.visible,
            viewportWidthPx = viewportW,
            viewportHeightPx = viewportH
        )
        if (widget.selfAttrMutationVersion == nodeAttrVer) {
            val cacheSlots = snapshotCache.getOrPut(impl) { arrayOfNulls(4) }
            var targetIdx = cacheSlots.indexOfFirst { it == null }
            if (targetIdx < 0) {
                targetIdx = snapshotSlotCursor % cacheSlots.size
                snapshotSlotCursor++
            }
            cacheSlots[targetIdx] = SnapshotCacheEntry(
                viewportW = cacheKeyW,
                viewportH = cacheKeyH,
                isLayoutRoot = isLayoutRoot,
                indefiniteCol = flagIndefiniteCol,
                layeredHost = flagLayeredHost,
                singleContainerFill = singleContainerFill,
                measureMode = cacheMeasureMode,
                attrVer = nodeAttrVer,
                compStyle = compStyle,
                computed = computed,
                snapshot = snapResult
            )
        }
        return snapResult
    }

    private fun resolveTagNameFromStringAttrs(
        impl: DesktopBaseNativeImpl<*>,
        widget: DesktopNativeWidget,
        attrs: Map<String, String>
    ): String {
        val attrTag = attrs["tagName"]
            ?: attrs["htmlTag"]
            ?: attrs["data-genericui-tag"]
        if (!attrTag.isNullOrBlank()) return attrTag
        if (impl is DesktopContainerNativeImpl<*> && impl.tagName.isNotBlank()) return impl.tagName
        if (impl is DesktopTextNativeImpl<*> && impl.tagName.isNotBlank()) return impl.tagName
        if (impl.componentClassPrefix.isNotBlank()) return impl.componentClassPrefix
        return widget.nativeClassName.ifBlank { "div" }
    }

    private fun resolveTagName(impl: DesktopBaseNativeImpl<*>): String =
        resolveNormalizedTag(impl, impl.nativeWidget)

    private fun resolveNormalizedTag(
        impl: DesktopBaseNativeImpl<*>?,
        widget: DesktopNativeWidget,
        attrs: Map<String, String>? = null
    ): String = widget.resolveNormalizedTagName {
        if (impl != null) {
            if (attrs != null) {
                resolveTagNameFromStringAttrs(impl, widget, attrs)
            } else {
                val attrTag = (widget.attributes["tagName"] as? String)
                    ?: (widget.attributes["htmlTag"] as? String)
                    ?: (widget.attributes["data-genericui-tag"] as? String)
                when {
                    !attrTag.isNullOrBlank() -> attrTag
                    impl is DesktopContainerNativeImpl<*> && impl.tagName.isNotBlank() -> impl.tagName
                    impl is DesktopTextNativeImpl<*> && impl.tagName.isNotBlank() -> impl.tagName
                    impl.componentClassPrefix.isNotBlank() -> impl.componentClassPrefix
                    else -> widget.nativeClassName.ifBlank { "div" }
                }
            }
        } else {
            (attrs?.get("tagName") ?: widget.attributes["tagName"] as? String)
                ?: (attrs?.get("htmlTag") ?: widget.attributes["htmlTag"] as? String)
                ?: widget.nativeClassName
        }
    }

    private fun extractStringAttributes(attrSnapshot: Map<String, Any?>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for ((k, v) in attrSnapshot.entries) {
            when (v) {
                is String -> out[k] = v
                is Number -> out[k] = v.toString()
                is Boolean -> out[k] = v.toString()
            }
        }
        return out
    }

    private fun resolveEffectiveTransformFromAttrs(
        impl: DesktopBaseNativeImpl<*>,
        attrSnapshot: Map<String, Any?>
    ): CssTransform? {
        val comp = impl.getComponentStyle()
        val computed = impl.resolveEffectiveStyle()
        return comp?.transform?.let { CssTransform.fromTransform2D(it) }
            ?: computed?.transform2D?.let { CssTransform.fromTransform2D(it) }
            ?: (attrSnapshot["transform2D"] as? CssTransform2D)?.let { CssTransform.fromTransform2D(it) }
            ?: (attrSnapshot["transform"] as? String)?.let { CssTransform.parse(it) }
            ?: computed?.transform?.let { CssTransform.parse(it) }
    }

    private fun resolveEffectiveTransform(impl: DesktopBaseNativeImpl<*>): CssTransform? {
        val comp = impl.getComponentStyle()
        val computed = impl.resolveEffectiveStyle()
        comp?.transform?.let { return CssTransform.fromTransform2D(it) }
        computed?.transform2D?.let { return CssTransform.fromTransform2D(it) }
        if (impl.nativeWidget.hasTransformAttribute) {
            val attrs = impl.nativeWidget.attributes
            (attrs["transform2D"] as? CssTransform2D)?.let { return CssTransform.fromTransform2D(it) }
            (attrs["transform"] as? String)?.let { return CssTransform.parse(it) }
        }
        return computed?.transform?.let { CssTransform.parse(it) }
    }

    private fun applyScaleAroundCenter(bounds: Rect, transform: CssTransform): Rect {
        if (transform.scaleX == 1f && transform.scaleY == 1f) return bounds
        val scaledW = if (bounds.width <= 0) 0 else max(1, (bounds.width * abs(transform.scaleX)).roundToInt())
        val scaledH = if (bounds.height <= 0) 0 else max(1, (bounds.height * abs(transform.scaleY)).roundToInt())
        val cx = bounds.x + bounds.width * 0.5f
        val cy = bounds.y + bounds.height * 0.5f
        return Rect(
            x = (cx - scaledW * 0.5f).roundToInt(),
            y = (cy - scaledH * 0.5f).roundToInt(),
            width = scaledW,
            height = scaledH
        )
    }

    private fun parseCssLengthAttr(raw: Any?): CssLength? = when (raw) {
        is CssLength -> raw
        is String -> CssLength.parse(raw)
        else -> null
    }

    private fun resolveNonPercentPx(len: CssLength?): Int? {
        if (len == null || len.isAuto || len.isNone || len.unit == CssUnit.PERCENT) return null
        return len.resolvePx()?.roundToInt()
    }

    private fun resolveExplicitCssWidthPx(impl: DesktopBaseNativeImpl<*>): Int? {
        val len = parseCssLengthAttr(impl.nativeWidget.attributes["styleWidth"])
            ?: impl.getComponentStyle()?.width
            ?: impl.resolveEffectiveStyle()?.width?.let { CssLength.parse(it) }
        return resolveNonPercentPx(len)?.coerceAtLeast(0)
    }

    private fun resolveExplicitCssHeightPx(impl: DesktopBaseNativeImpl<*>): Int? {
        val len = parseCssLengthAttr(impl.nativeWidget.attributes["styleHeight"])
            ?: impl.getComponentStyle()?.height
            ?: impl.resolveEffectiveStyle()?.height?.let { CssLength.parse(it) }
        return resolveNonPercentPx(len)?.coerceAtLeast(0)
    }

    private fun resolveExplicitWidthPx(impl: DesktopBaseNativeImpl<*>): Int? {
        resolveExplicitCssWidthPx(impl)?.takeIf { it > 0 }?.let { return it }
        return impl.nativeWidget.preferredSize?.width?.takeIf { it > 0 }
    }

    private fun resolveExplicitHeightPx(impl: DesktopBaseNativeImpl<*>): Int? {
        resolveExplicitCssHeightPx(impl)?.takeIf { it > 0 }?.let { return it }
        return impl.nativeWidget.preferredSize?.height?.takeIf { it > 0 }
    }

    private fun resolveReferenceWidthPx(impl: DesktopBaseNativeImpl<*>): Int {
        if (impl.nativeWidget.bounds.width > 0) return impl.nativeWidget.bounds.width
        resolveExplicitWidthPx(impl)?.let { if (it > 0) return it }
        var cur: DesktopNativeWidget? = impl.nativeWidget.parentWidget
        while (cur != null) {
            if (cur.bounds.width > 0) return cur.bounds.width
            cur = cur.parentWidget
        }
        return 800
    }

    private fun resolveReferenceHeightPx(impl: DesktopBaseNativeImpl<*>): Int {
        if (impl.nativeWidget.bounds.height > 0) return impl.nativeWidget.bounds.height
        resolveExplicitHeightPx(impl)?.let { if (it > 0) return it }
        return 0
    }

    private fun resolveRootExplicitWidth(
        impl: DesktopBaseNativeImpl<*>,
        snap: LayoutStyleSnapshot,
        availW: Int
    ): Int? = snap.width?.resolvePx(
        referencePx = availW.toFloat(),
        viewportWidthPx = availW.toFloat(),
        viewportHeightPx = availW.toFloat()
    )?.roundToInt() ?: resolveExplicitWidthPx(impl)

    private fun resolveRootExplicitHeight(
        impl: DesktopBaseNativeImpl<*>,
        snap: LayoutStyleSnapshot,
        availH: Int
    ): Int? = if (availH > 0) {
        snap.height?.resolvePx(
            referencePx = availH.toFloat(),
            viewportWidthPx = availH.toFloat(),
            viewportHeightPx = availH.toFloat()
        )?.roundToInt() ?: resolveExplicitHeightPx(impl)
    } else {
        snap.height?.takeIf { it.unit != CssUnit.PERCENT && it.unit != CssUnit.VH }?.resolvePx(
            referencePx = 0f,
            viewportWidthPx = 800f,
            viewportHeightPx = 600f
        )?.roundToInt() ?: resolveExplicitHeightPx(impl)
    }

    private fun hasWidthUtilityClass(classes: Collection<String>): Boolean =
        classes.any { cls ->
            cls.equals("w-100", ignoreCase = true) ||
                cls.equals("w-75", ignoreCase = true) ||
                cls.equals("w-50", ignoreCase = true) ||
                cls.equals("w-25", ignoreCase = true) ||
                cls.equals("w-auto", ignoreCase = true)
        }

    private fun hasHeightUtilityClass(classes: Collection<String>): Boolean =
        classes.any { cls ->
            cls.equals("h-100", ignoreCase = true) ||
                cls.equals("h-75", ignoreCase = true) ||
                cls.equals("h-50", ignoreCase = true) ||
                cls.equals("h-25", ignoreCase = true) ||
                cls.equals("h-auto", ignoreCase = true)
        }

    private fun MutableMap<String, String>.putIfMissing(key: String, value: String) {
        if (!containsKey(key)) {
            put(key, value)
        }
    }

    private fun stripPercentConstraintsAndClasses(
        styleProps: MutableMap<String, String>,
        classes: MutableSet<String>,
        props: List<String>,
        classNames: Set<String>
    ) {
        for (prop in props) {
            if (styleProps[prop]?.trim()?.endsWith("%") == true) {
                styleProps.remove(prop)
            }
        }
        classes.removeAll { cls -> classNames.any { it.equals(cls, ignoreCase = true) } }
    }

    private fun isLikelyFragmentWidget(impl: DesktopBaseNativeImpl<*>, widget: DesktopNativeWidget): Boolean {
        if (widget.hasFragmentClassToken) return true
        if (impl.componentClassPrefix.contains("fragment", ignoreCase = true)) return true
        val rawTag = (widget.attributes["tagName"] as? String)
            ?: (widget.attributes["htmlTag"] as? String)
            ?: (widget.attributes["data-genericui-tag"] as? String)
            ?: widget.nativeClassName
        return rawTag.contains("fragment", ignoreCase = true)
    }

    private fun isLikelyAppWidget(impl: DesktopBaseNativeImpl<*>, widget: DesktopNativeWidget): Boolean {
        val rawTagAttr = (widget.attributes["data-genericui-tag"] as? String)
            ?.trim()
            ?.lowercase()
            ?.removePrefix("genericui:")
        return rawTagAttr == "app" || resolveNormalizedTag(impl, widget) == "app"
    }

    private fun isStyledContainerHost(): Boolean {
        val hostTag = resolveNormalizedTag(host, host.nativeWidget)
        return hostTag == "styled-container" ||
            hostTag == "styledcontainer" ||
            host is com.nexustools.genericui.desktop.widget.DesktopStyledContainerNativeImpl<*>
    }

    private fun hasExplicitHostAlignment(hostImpl: DesktopBaseNativeImpl<*>): Boolean {
        val cs = hostImpl.getComponentStyle()
        val jc = cs?.justifyContent
        val ai = cs?.alignItems
        if (jc != null && jc != JustifyContent.START) return true
        if (ai != null && ai != AlignItems.STRETCH) return true
        val comp = hostImpl.resolveEffectiveStyle()
        val compJc = comp?.justifyContent
        val compAi = comp?.alignItems
        if (compJc != null && compJc != JustifyContent.START) return true
        if (compAi != null && compAi != AlignItems.STRETCH) return true
        val attrs = hostImpl.nativeWidget.attributes
        val attrJc = (attrs["justifyContent"] as? String)?.trim()?.lowercase()
        if (!attrJc.isNullOrBlank() && attrJc != "flex-start" && attrJc != "start" && attrJc != "normal") return true
        val attrAi = (attrs["alignItems"] as? String)?.trim()?.lowercase()
        if (!attrAi.isNullOrBlank() && attrAi != "stretch" && attrAi != "normal") return true
        for (cls in hostImpl.nativeWidget.cachedAllClassTokens) {
            val lower = cls.lowercase()
            if ((lower.startsWith("justify-content-") && lower != "justify-content-start") ||
                (lower.startsWith("align-items-") && lower != "align-items-stretch")
            ) {
                return true
            }
        }
        return false
    }

    private fun addSpaceSeparatedTokens(raw: String, out: MutableSet<String>) {
        val len = raw.length
        if (len == 0) return
        var start = -1
        for (i in 0..len) {
            val ch = if (i < len) raw[i] else ' '
            if (ch.isWhitespace()) {
                if (start >= 0) {
                    out.add(if (start == 0 && i == len) raw else raw.substring(start, i))
                    start = -1
                }
            } else if (start < 0) {
                start = i
            }
        }
    }

    private fun applyComponentStyleLayoutProps(compStyle: ComponentStyle, styleProps: MutableMap<String, String>) {
        compStyle.display?.let { styleProps["display"] = it.cssValue }
        compStyle.visibility?.let { styleProps["visibility"] = it.cssValue }
        compStyle.position?.let { styleProps["position"] = it.cssValue }
        compStyle.top?.let { styleProps["top"] = it.toCssString() }
        compStyle.right?.let { styleProps["right"] = it.toCssString() }
        compStyle.bottom?.let { styleProps["bottom"] = it.toCssString() }
        compStyle.left?.let { styleProps["left"] = it.toCssString() }
        compStyle.boxSizing?.let { styleProps["box-sizing"] = it.cssValue }
        compStyle.width?.let { styleProps["width"] = it.toCssString() }
        compStyle.height?.let { styleProps["height"] = it.toCssString() }
        compStyle.minWidth?.let { styleProps["min-width"] = it.toCssString() }
        compStyle.minHeight?.let { styleProps["min-height"] = it.toCssString() }
        compStyle.maxWidth?.let { styleProps["max-width"] = it.toCssString() }
        compStyle.maxHeight?.let { styleProps["max-height"] = it.toCssString() }
        compStyle.aspectRatio?.let { styleProps["aspect-ratio"] = it.toCssString() }
        compStyle.padding?.let { styleProps["padding"] = it.toCssString() }
        compStyle.paddingTop?.let { styleProps["padding-top"] = it.toCssString() }
        compStyle.paddingRight?.let { styleProps["padding-right"] = it.toCssString() }
        compStyle.paddingBottom?.let { styleProps["padding-bottom"] = it.toCssString() }
        compStyle.paddingLeft?.let { styleProps["padding-left"] = it.toCssString() }
        compStyle.margin?.let { styleProps["margin"] = it.toCssString() }
        compStyle.marginTop?.let { styleProps["margin-top"] = it.toCssString() }
        compStyle.marginRight?.let { styleProps["margin-right"] = it.toCssString() }
        compStyle.marginBottom?.let { styleProps["margin-bottom"] = it.toCssString() }
        compStyle.marginLeft?.let { styleProps["margin-left"] = it.toCssString() }
        compStyle.borderWidthPx?.let { styleProps["border-width"] = "${it}px" }
        compStyle.flexDirection?.let { styleProps["flex-direction"] = it.name.lowercase().replace('_', '-') }
        compStyle.flexWrap?.let { styleProps["flex-wrap"] = it.name.lowercase().replace('_', '-') }
        compStyle.justifyContent?.let {
            styleProps["justify-content"] = when (it) {
                JustifyContent.START -> "flex-start"
                JustifyContent.END -> "flex-end"
                JustifyContent.CENTER -> "center"
                JustifyContent.BETWEEN -> "space-between"
                JustifyContent.AROUND -> "space-around"
                JustifyContent.EVENLY -> "space-evenly"
            }
        }
        compStyle.alignItems?.let {
            styleProps["align-items"] = when (it) {
                AlignItems.START -> "flex-start"
                AlignItems.END -> "flex-end"
                AlignItems.CENTER -> "center"
                AlignItems.BASELINE -> "baseline"
                AlignItems.STRETCH -> "stretch"
            }
        }
        compStyle.alignSelf?.let {
            styleProps["align-self"] = when (it) {
                AlignSelf.AUTO -> "auto"
                AlignSelf.START -> "flex-start"
                AlignSelf.END -> "flex-end"
                AlignSelf.CENTER -> "center"
                AlignSelf.BASELINE -> "baseline"
                AlignSelf.STRETCH -> "stretch"
            }
        }
        compStyle.alignContent?.let { styleProps["align-content"] = it.cssValue }
        compStyle.flexGrow?.let { styleProps["flex-grow"] = it.toString() }
        compStyle.flexShrink?.let { styleProps["flex-shrink"] = it.toString() }
        compStyle.flexBasis?.let { styleProps["flex-basis"] = it.toCssString() }
        compStyle.order?.let { styleProps["order"] = it.toString() }
        compStyle.gap?.let { styleProps["gap"] = it.toCssString() }
        compStyle.rowGap?.let { styleProps["row-gap"] = it.toCssString() }
        compStyle.columnGap?.let { styleProps["column-gap"] = it.toCssString() }
        compStyle.overflow?.let { styleProps["overflow"] = it.name.lowercase() }
        compStyle.overflowX?.let { styleProps["overflow-x"] = it.name.lowercase() }
        compStyle.overflowY?.let { styleProps["overflow-y"] = it.name.lowercase() }
        compStyle.zIndex?.let { styleProps["z-index"] = it.toString() }
        compStyle.transform?.let { styleProps["transform"] = it.toCssString() }
    }

    private fun safeToFloatOrNull(raw: String?): Float? {
        val s = raw?.trim() ?: return null
        val len = s.length
        if (len == 0) return null
        var i = 0
        if (s[0] == '+' || s[0] == '-') {
            if (len == 1) return null
            i = 1
        }
        var digits = false
        var dot = false
        while (i < len) {
            val c = s[i]
            when {
                c in '0'..'9' -> digits = true
                c == '.' && !dot -> dot = true
                else -> return null
            }
            i++
        }
        return if (digits) s.toFloatOrNull() else null
    }

    private fun safeToIntOrNull(raw: String?): Int? {
        val s = raw?.trim() ?: return null
        val len = s.length
        if (len == 0) return null
        var i = if (s[0] == '+' || s[0] == '-') 1 else 0
        if (i >= len) return null
        while (i < len) {
            if (s[i] !in '0'..'9') return null
            i++
        }
        return s.toIntOrNull()
    }

    companion object {
        private val INDEFINITE_HEIGHT_PERCENT_PROPS = listOf("height", "min-height", "max-height")
        private val INDEFINITE_HEIGHT_UTILITY_CLASSES = setOf("h-100", "h-75", "h-50", "h-25", "mh-100")
        private val ROOT_CONSTRAINT_PROPS = listOf("min-width", "max-width", "min-height", "max-height")
        private val ROOT_CONSTRAINT_UTILITY_CLASSES = setOf("mw-100", "mh-100")
        private val BORDER_BOX_MEASURED_TAGS = setOf(
            "tabcontainer",
            "tab-container",
            "navbar",
            "bottomnavigationbar",
            "bottom-navigation-bar",
            "tab",
            "badgetab"
        )
    }
}
