@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.lazy.DesktopLazyListNativeImpl
import com.nexustools.genericui.enums.css.CssUnit
import com.nexustools.genericui.enums.bootstrap.FlexWrap
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.enums.ui.Orientation
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosNativeWindowHost
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.macos.usesNativeControlAlignmentExpansion
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.useContents
import platform.AppKit.NSScrollView
import platform.AppKit.NSView
import platform.AppKit.NSViewBoundsDidChangeNotification
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSNotificationCenter
import kotlin.math.roundToInt

/**
 * macOS AppKit virtualized [NSScrollView] implementation of [DesktopLazyListNativeImpl]
 * mounting only materialized item child [NSView]s inside a flipped [MacosScrollDocumentView].
 */
class MacosLazyListNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopLazyListNativeImpl<MacosViewHandle>(nativeWidget) {

    private data class RowLayoutCache(
        val crossExtent: Int,
        val mutVer: Long,
        val attrVer: Long,
        val itemW: Int,
        val itemH: Int,
        val lastLaidOutW: Int,
        val lastLaidOutH: Int,
        val lastLaidOutMutVer: Long,
        val lastLaidOutAttrVer: Long
    )

    private var scrollView: NSScrollView? = null
    private var documentView: MacosScrollDocumentView? = null
    private var boundsObserver: MacosClipBoundsObserver? = null
    private val rowLayoutCache = HashMap<DesktopNativeWidget, Array<RowLayoutCache?>>()
    private var lastMountedViews: List<NSView> = emptyList()
    private var lastDocW: Double = -1.0
    private var lastDocH: Double = -1.0
    private var lastLaidOutViewW: Int = -1
    private var lastLaidOutViewH: Int = -1
    private var lastLaidOutViewportVer: Long = -1L
    private var lastLaidOutSubtreeVer: Long = -1L
    private var lastSyncedDarkTheme: Boolean? = null

    init {
        nativeWidget.onViewportRedisplayRequested = {
            MacosNativeWindowHost.requestViewportRedisplay()
        }
        nativeWidget.viewMaterializer = { ensureScrollView() }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureScrollView()
        }
    }

    private var isLayingOutMaterializedViews: Boolean = false
    private var suppressRematerializeLayout: Boolean = false

    private fun ensureScrollView(): NSScrollView {
        scrollView?.let { return it }
        val sv = NSScrollView(frame = NSMakeRect(0.0, 0.0, 320.0, 400.0)).apply {
            setAutomaticallyAdjustsContentInsets(false)
            setHasVerticalScroller(orientationState == Orientation.VERTICAL)
            setHasHorizontalScroller(orientationState == Orientation.HORIZONTAL)
            setDrawsBackground(false)
            setAutohidesScrollers(true)
        }
        val doc = MacosScrollDocumentView(NSMakeRect(0.0, 0.0, 320.0, 400.0))
        sv.setDocumentView(doc)
        val clipView = sv.contentView()
        clipView.setPostsBoundsChangedNotifications(true)
        val obs = MacosClipBoundsObserver {
            if (isLayingOutMaterializedViews) return@MacosClipBoundsObserver
            val (ox, oy, cw, ch) = clipView.bounds.useContents {
                listOf(origin.x, origin.y, size.width, size.height)
            }
            if (orientationState == Orientation.HORIZONTAL) {
                dispatchHostViewportScroll(ox.toFloat(), cw.toFloat().coerceAtLeast(1f))
            } else {
                dispatchHostViewportScroll(oy.toFloat(), ch.toFloat().coerceAtLeast(1f))
            }
        }
        NSNotificationCenter.defaultCenter.addObserver(
            observer = obs,
            selector = obs.boundsSelector,
            name = NSViewBoundsDidChangeNotification,
            `object` = clipView
        )
        scrollView = sv
        documentView = doc
        boundsObserver = obs
        nativeWidget.nsView = sv
        sv.setHidden(!nativeWidget.visible)
        sv.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        sv.setToolTip(nativeWidget.tooltipText)
        if (!nativeWidget.isEffectivelyFocusable()) {
            com.nexustools.genericui.macos.MacosFocusSupport.applyFocusable(sv, false)
        }
        return sv
    }

    override fun rematerializeVisibleWindow() {
        super.rematerializeVisibleWindow()
        lastLaidOutViewportVer = -1L
        if (suppressRematerializeLayout) return
        if (MacosAppKitDispatch.isMainThread()) {
            ensureScrollView()
            layoutMaterializedViews()
        } else {
            MacosAppKitDispatch.onMainOrDefer {
                if (!isDisposed) {
                    ensureScrollView()
                    layoutMaterializedViews()
                }
            }
        }
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        val newExtent = (if (orientationState == Orientation.HORIZONTAL) width else height).coerceAtLeast(1f)
        if (kotlin.math.abs(newExtent - viewportEngine.viewportExtentPx) > 0.5f ||
            (viewportEngine.totalItemCount > 0 && materializedItems.isEmpty())
        ) {
            suppressRematerializeLayout = true
            try {
                dispatchLayoutViewportExtent(newExtent)
            } finally {
                suppressRematerializeLayout = false
            }
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureScrollView()
            layoutMaterializedViews(width, height)
        }
    }

    private fun hasExplicitFixedMainExtent(baseImpl: DesktopBaseNativeImpl<*>, isHoriz: Boolean): Boolean {
        val pref = baseImpl.nativeWidget.preferredSize
        if (pref != null && (if (isHoriz) pref.width > 0 else pref.height > 0)) return true
        val comp = baseImpl.getComponentStyle()
        val compLen = if (isHoriz) comp?.width else comp?.height
        if (compLen != null && !compLen.isAuto && !compLen.isNone &&
            compLen.unit != CssUnit.PERCENT && compLen.unit != CssUnit.VW && compLen.unit != CssUnit.VH
        ) return true
        val eff = baseImpl.resolveEffectiveStyle()
        val effLen = eff?.let { CssLength.parse(if (isHoriz) it.width else it.height) }
        return effLen != null && !effLen.isAuto && !effLen.isNone &&
            effLen.unit != CssUnit.PERCENT && effLen.unit != CssUnit.VW && effLen.unit != CssUnit.VH
    }

    private fun incompressibleRowChildWidth(rowBase: DesktopBaseNativeImpl<*>): Int {
        val flexGrow = rowBase.getComponentStyle()?.flexGrow
            ?: rowBase.resolveEffectiveStyle()?.flexGrow
            ?: (rowBase.nativeWidget.attributes["weight"] as? Number)?.toFloat()
            ?: 0f
        val isEmptyLeafOrContainer = (rowBase is DesktopBaseContainerNativeImpl<*> && rowBase.rawChildImplsSnapshot().isEmpty()) ||
            rowBase.componentClassPrefix.equals("spacer", ignoreCase = true) ||
            (rowBase.nativeWidget.attributes["tagName"] as? String)?.lowercase()?.removePrefix("genericui:") == "spacer"
        if (flexGrow > 0f && isEmptyLeafOrContainer) {
            val minW = rowBase.getComponentStyle()?.minWidth
                ?.takeIf { !it.isAuto && !it.isNone && it.unit == CssUnit.PX }
                ?.resolvePx(referencePx = 0f)
                ?.roundToInt()
                ?: 0
            val prefW = rowBase.nativeWidget.preferredSize?.width?.coerceAtLeast(0) ?: 0
            return maxOf(minW, prefW).coerceAtMost(rowBase.nativeWidget.bounds.width.coerceAtLeast(0))
        }
        return rowBase.nativeWidget.bounds.width.coerceAtLeast(0)
    }

    private fun isRowHeightIndependentOfWidth(
        impl: DesktopBaseNativeImpl<*>,
        newOuterW: Int,
        deltaShrinkPx: Int,
        depth: Int = 0
    ): Boolean {
        if (depth > 4) return false
        if (hasExplicitFixedMainExtent(impl, isHoriz = false)) return true
        if (!impl.isVisible() || !impl.nativeWidget.visible) return true
        val comp = impl.getComponentStyle()
        val eff = impl.resolveEffectiveStyle()
        if (comp?.aspectRatio != null || eff?.aspectRatio != null || impl.nativeWidget.attributes["aspectRatio"] != null) {
            return false
        }
        val hLen = comp?.height ?: eff?.height?.let { CssLength.parse(it) }
        if (hLen != null && (hLen.unit == CssUnit.PERCENT || hLen.unit == CssUnit.VW || hLen.unit == CssUnit.VH)) {
            return false
        }
        if (comp?.flexWrap == FlexWrap.WRAP || eff?.flexWrap == FlexWrap.WRAP) return false
        if (impl is MacosTextNativeImpl) {
            val childAvailW = if (impl.nativeWidget.bounds.width > 0) {
                (impl.nativeWidget.bounds.width - deltaShrinkPx).coerceAtLeast(0)
            } else {
                0
            }
            return impl.isHeightIndependentAtWidth(childAvailW)
        }
        if (impl is DesktopBaseContainerNativeImpl<*>) {
            for (child in impl.rawChildImplsSnapshot()) {
                val baseChild = child as? DesktopBaseNativeImpl<*> ?: continue
                if (!isRowHeightIndependentOfWidth(baseChild, newOuterW, deltaShrinkPx, depth + 1)) {
                    return false
                }
            }
        }
        return true
    }

    private fun layoutMaterializedViews(
        viewW: Float = nativeWidget.bounds.width.coerceAtLeast(1).toFloat(),
        viewH: Float = nativeWidget.bounds.height.coerceAtLeast(1).toFloat()
    ) {
        val doc = documentView ?: return
        val curDark = com.nexustools.genericui.macos.MacosAppKitAppearance.isDarkTheme(nativeWidget)
        val prevDark = lastSyncedDarkTheme
        if (prevDark != curDark) {
            lastSyncedDarkTheme = curDark
            if (prevDark != null) {
                onNativeStyleSynced()
            }
        }
        val safeViewW = viewW.roundToInt().coerceAtLeast(1)
        val safeViewH = viewH.roundToInt().coerceAtLeast(1)
        val curViewportVer = viewportVersion
        val curSubtreeVer = nativeWidget.subtreeMutationVersion
        if (safeViewW == lastLaidOutViewW &&
            safeViewH == lastLaidOutViewH &&
            curViewportVer == lastLaidOutViewportVer &&
            curSubtreeVer == lastLaidOutSubtreeVer
        ) {
            return
        }
        isLayingOutMaterializedViews = true
        try {
            val isHoriz = orientationState == Orientation.HORIZONTAL
            val est = viewportEngine.estimatedItemExtentPx.coerceAtLeast(1f)
            val orderedPairs = materializedItemsSnapshot().entries.map { it.key to it.value }.sortedBy { it.first }
            val mountedViews = ArrayList<NSView>(orderedPairs.size)
            val activeWidgets = HashSet<DesktopNativeWidget>(orderedPairs.size * 2)
            val crossExtent = if (isHoriz) viewH.roundToInt() else viewW.roundToInt()

            var cursor = leadingSpacerPx
            var anyRowBoundsChanged = false
            for ((_, childImpl) in orderedPairs) {
                val baseImpl = childImpl as? DesktopBaseNativeImpl<*> ?: continue
                val childWidget = baseImpl.nativeWidget
                activeWidgets.add(childWidget)
                val mutVer = childWidget.subtreeMutationVersion
                val attrVer = childWidget.subtreeAttrMutationVersion
                val slots = rowLayoutCache[childWidget]
                var cached: RowLayoutCache? = null
                if (slots != null) {
                    for (entry in slots) {
                        if (entry != null &&
                            entry.crossExtent == crossExtent &&
                            entry.mutVer == mutVer &&
                            entry.attrVer == attrVer
                        ) {
                            cached = entry
                            break
                        }
                    }
                }
                val itemW: Int
                val itemH: Int
                val targetViewW = viewW.roundToInt()
                val reuseFixedMainSlot = if (cached == null && slots != null) {
                    slots.firstOrNull { it != null && it.mutVer == mutVer && it.attrVer == attrVer }
                        ?.takeIf { slot ->
                            if (hasExplicitFixedMainExtent(baseImpl, isHoriz)) {
                                true
                            } else if (!isHoriz) {
                                val rawShrink = (slot.crossExtent - targetViewW).coerceAtLeast(0)
                                val effectiveShrink = if (rawShrink > 0 && baseImpl is DesktopBaseContainerNativeImpl<*>) {
                                    var occupiedW = baseImpl.nativeWidget.padding.left + baseImpl.nativeWidget.padding.right
                                    var visibleCount = 0
                                    for (rowChild in baseImpl.rawChildImplsSnapshot()) {
                                        val rowBase = rowChild as? DesktopBaseNativeImpl<*> ?: continue
                                        if (!rowBase.isVisible() || !rowBase.nativeWidget.visible) continue
                                        occupiedW += incompressibleRowChildWidth(rowBase)
                                        visibleCount++
                                    }
                                    if (visibleCount > 1) {
                                        val gapPx = baseImpl.nativeWidget.attributes["gapPx"] as? Int ?: 0
                                        occupiedW += (visibleCount - 1) * gapPx
                                    }
                                    val freeSlack = (slot.crossExtent - occupiedW).coerceAtLeast(0)
                                    (rawShrink - freeSlack).coerceAtLeast(0)
                                } else {
                                    rawShrink
                                }
                                isRowHeightIndependentOfWidth(baseImpl, targetViewW, effectiveShrink)
                            } else {
                                false
                            }
                        }
                } else null
                if (cached != null) {
                    itemW = cached.itemW
                    itemH = cached.itemH
                } else if (reuseFixedMainSlot != null) {
                    itemW = if (isHoriz) reuseFixedMainSlot.itemW else viewW.roundToInt()
                    itemH = if (isHoriz) viewH.roundToInt() else reuseFixedMainSlot.itemH
                } else {
                    val measured = baseImpl.measureIntrinsicSize(
                        if (isHoriz) null else viewW,
                        if (isHoriz) viewH else null
                    )
                    itemW = if (isHoriz) measured.width.coerceAtLeast(est.roundToInt()) else viewW.roundToInt()
                    itemH = if (isHoriz) viewH.roundToInt() else measured.height.coerceAtLeast(est.roundToInt())
                }
                val ix = if (isHoriz) cursor.roundToInt() else 0
                val iy = if (isHoriz) 0 else cursor.roundToInt()
                val targetRect = Rect(ix, iy, itemW, itemH)

                val childHandle = childWidget as? MacosViewHandle
                val wasRealized = childHandle?.nsView != null
                val cv = childHandle?.ensureNsViewOnMain()
                val boundsChanged = baseImpl.nativeWidget.bounds != targetRect
                if (boundsChanged) {
                    anyRowBoundsChanged = true
                    baseImpl.setBounds(targetRect)
                }
                val needsChildLayout = cached == null ||
                    cached.lastLaidOutW != itemW ||
                    cached.lastLaidOutH != itemH ||
                    cached.lastLaidOutMutVer != mutVer ||
                    cached.lastLaidOutAttrVer != attrVer
                if (needsChildLayout && baseImpl is DesktopBaseContainerNativeImpl<*>) {
                    baseImpl.layoutChildrenInBounds(itemW.toFloat(), itemH.toFloat())
                }
                val nextEntry = RowLayoutCache(
                    crossExtent = crossExtent,
                    mutVer = mutVer,
                    attrVer = attrVer,
                    itemW = itemW,
                    itemH = itemH,
                    lastLaidOutW = itemW,
                    lastLaidOutH = itemH,
                    lastLaidOutMutVer = mutVer,
                    lastLaidOutAttrVer = attrVer
                )
                val targetSlots = slots ?: arrayOfNulls<RowLayoutCache>(2).also { rowLayoutCache[childWidget] = it }
                val matchIdx = targetSlots.indexOfFirst { it?.crossExtent == crossExtent }
                if (matchIdx >= 0) {
                    targetSlots[matchIdx] = nextEntry
                } else if (targetSlots[0] == null) {
                    targetSlots[0] = nextEntry
                } else {
                    targetSlots[1] = targetSlots[0]
                    targetSlots[0] = nextEntry
                }
                if (cv != null) {
                    mountedViews.add(cv)
                    if (!wasRealized || boundsChanged) {
                        MacosAppKitDispatch.applyFrameAndTransform(
                            view = cv,
                            bounds = targetRect,
                            transform = childHandle.cssTransform,
                            useAlignmentRectExpansion = baseImpl.usesNativeControlAlignmentExpansion(),
                            knownHandle = childHandle
                        )
                    }
                }
                cursor += if (isHoriz) itemW else itemH
            }

            if (rowLayoutCache.size > activeWidgets.size) {
                val it = rowLayoutCache.keys.iterator()
                while (it.hasNext()) {
                    if (it.next() !in activeWidgets) {
                        it.remove()
                    }
                }
            }

            val totalExtent = maxOf(totalContentExtentPx.toDouble(), cursor.toDouble())
            val docW = if (isHoriz) totalExtent.coerceAtLeast(viewW.toDouble()) else viewW.toDouble()
            val docH = if (isHoriz) viewH.toDouble() else totalExtent.coerceAtLeast(viewH.toDouble())
            val docSizeChanged = docW != lastDocW || docH != lastDocH
            if (docSizeChanged) {
                lastDocW = docW
                lastDocH = docH
                doc.setFrame(NSMakeRect(0.0, 0.0, docW, docH))
            }
            val hierarchyChanged = mountedViews.size != lastMountedViews.size ||
                mountedViews.indices.any { mountedViews[it] !== lastMountedViews[it] }
            if (hierarchyChanged) {
                lastMountedViews = mountedViews
                MacosAppKitDispatch.syncChildViewHierarchy(doc, mountedViews)
                doc.setNeedsDisplay(true)
            }
            scrollView?.let { sv ->
                if (hierarchyChanged || docSizeChanged || anyRowBoundsChanged) {
                    sv.reflectScrolledClipView(sv.contentView())
                }
            }
            lastLaidOutViewW = safeViewW
            lastLaidOutViewH = safeViewH
            lastLaidOutViewportVer = viewportVersion
            lastLaidOutSubtreeVer = nativeWidget.subtreeMutationVersion
        } finally {
            isLayingOutMaterializedViews = false
        }
    }

    override fun scrollToIndex(index: Int) {
        super.scrollToIndex(index)
        val sv = scrollView ?: return
        val target = viewportEngine.scrollOffsetPx.toDouble().coerceAtLeast(0.0)
        val pt = if (orientationState == Orientation.HORIZONTAL) {
            NSMakePoint(target, 0.0)
        } else {
            NSMakePoint(0.0, target)
        }
        val clip = sv.contentView()
        clip.scrollToPoint(pt)
        sv.reflectScrolledClipView(clip)
    }

    override fun setOrientation(orientation: Orientation) {
        super.setOrientation(orientation)
        scrollView?.let { sv ->
            sv.setHasVerticalScroller(orientation == Orientation.VERTICAL)
            sv.setHasHorizontalScroller(orientation == Orientation.HORIZONTAL)
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        Size((maxWidth?.toInt() ?: 320).coerceAtLeast(160), (maxHeight?.toInt() ?: 400).coerceAtLeast(120))

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer {
                if (!isDisposed) onNativeBoundsChanged(nativeWidget.bounds)
            }
            return
        }
        val sv = ensureScrollView()
        val prevLayingOut = isLayingOutMaterializedViews
        isLayingOutMaterializedViews = true
        try {
            MacosAppKitDispatch.applyFrameAndTransform(
                sv,
                rect,
                nativeWidget.cssTransform,
                useAlignmentRectExpansion = false,
                knownHandle = nativeWidget
            )
            val clipOrigin = sv.contentView().bounds.useContents {
                if (orientationState == Orientation.HORIZONTAL) origin.x else origin.y
            }.toFloat().coerceAtLeast(0f)
            if (kotlin.math.abs(clipOrigin - viewportEngine.scrollOffsetPx) > 0.5f) {
                viewportEngine.scrollOffsetPx = clipOrigin
            }
        } finally {
            isLayingOutMaterializedViews = prevLayingOut
        }
        val newExtent = if (orientationState == Orientation.HORIZONTAL) {
            rect.width.coerceAtLeast(1).toFloat()
        } else {
            rect.height.coerceAtLeast(1).toFloat()
        }
        if (kotlin.math.abs(newExtent - viewportEngine.viewportExtentPx) > 0.5f ||
            (viewportEngine.totalItemCount > 0 && materializedItems.isEmpty())
        ) {
            suppressRematerializeLayout = true
            try {
                dispatchLayoutViewportExtent(newExtent)
            } finally {
                suppressRematerializeLayout = false
            }
        }
        layoutMaterializedViews(rect.width.toFloat(), rect.height.toFloat())
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        MacosAppKitDispatch.onMainOrDefer {
            scrollView?.setHidden(!visible)
        }
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        MacosAppKitDispatch.onMainOrDefer {
            scrollView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
        }
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        MacosAppKitDispatch.onMainOrDefer {
            scrollView?.setToolTip(tooltip)
        }
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        MacosAppKitDispatch.onMainOrDefer {
            scrollView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
        }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        MacosAppKitDispatch.onMainOrDefer {
            val sv = scrollView ?: return@onMainOrDefer
            MacosAppKitDispatch.applyFrameAndTransform(sv, nativeWidget.bounds, transform, useAlignmentRectExpansion = false, knownHandle = nativeWidget)
        }
    }

    override fun onNativeStyleSynced() {
        lastSyncedDarkTheme = com.nexustools.genericui.macos.MacosAppKitAppearance.isDarkTheme(nativeWidget)
        fun propagateTheme(impl: com.nexustools.genericui.core.NativeImpl<*>) {
            val base = impl as? DesktopBaseNativeImpl<*> ?: return
            base.notifyHostThemeChanged()
            if (base is DesktopBaseContainerNativeImpl<*>) {
                for (child in base.rawChildImplsSnapshot()) {
                    propagateTheme(child)
                }
            }
        }
        for (itemImpl in materializedItemsSnapshot().values) {
            propagateTheme(itemImpl)
        }
    }

    override fun dispose() {
        val obs = boundsObserver
        val sv = scrollView
        boundsObserver = null
        scrollView = null
        documentView = null
        MacosAppKitDispatch.onMainOrDefer {
            obs?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
            sv?.removeFromSuperview()
        }
        super.dispose()
    }
}
