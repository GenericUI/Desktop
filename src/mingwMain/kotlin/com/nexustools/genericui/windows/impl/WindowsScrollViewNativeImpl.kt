@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopPopoverNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopScrollViewNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import com.nexustools.genericui.windows.Win32ScrollViewCompositor
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import platform.windows.BeginDeferWindowPos
import platform.windows.DeferWindowPos
import platform.windows.EndDeferWindowPos
import platform.windows.GWL_STYLE
import platform.windows.GetClientRect
import platform.windows.GetScrollInfo
import platform.windows.GetWindowLongPtrW
import platform.windows.HWND
import platform.windows.InvalidateRect
import platform.windows.IsWindow
import platform.windows.MK_SHIFT
import platform.windows.RECT
import platform.windows.SB_HORZ
import platform.windows.SB_LINEDOWN
import platform.windows.SB_LINEUP
import platform.windows.SB_PAGEDOWN
import platform.windows.SB_PAGEUP
import platform.windows.SB_THUMBPOSITION
import platform.windows.SB_THUMBTRACK
import platform.windows.SB_VERT
import platform.windows.SCROLLINFO
import platform.windows.SIF_PAGE
import platform.windows.SIF_POS
import platform.windows.SIF_RANGE
import platform.windows.SWP_HIDEWINDOW
import platform.windows.SWP_NOACTIVATE
import platform.windows.SWP_NOMOVE
import platform.windows.SWP_NOSIZE
import platform.windows.SWP_NOZORDER
import platform.windows.SWP_SHOWWINDOW
import platform.windows.SW_HIDE
import platform.windows.SW_SHOWNOACTIVATE
import platform.windows.SetScrollInfo
import platform.windows.SetWindowPos
import platform.windows.ShowWindow
import platform.windows.WM_HSCROLL
import platform.windows.WM_MOUSEWHEEL
import platform.windows.WM_VSCROLL
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPCHILDREN
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_VISIBLE
import platform.windows.WS_VSCROLL
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Retained Win32 scroll viewport (`"GenericUIScrollViewW"`) using native `SetScrollInfo`/`WM_VSCROLL`
 * and `WM_HSCROLL` / `WM_MOUSEWHEEL` offsets batched through `BeginDeferWindowPos`.
 */
class WindowsScrollViewNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIScrollViewW")
) : DesktopScrollViewNativeImpl<Win32HwndHandle>(widget) {

    private var contentW: Int = 0
    private var contentH: Int = 0
    private var isLayoutingChildren: Boolean = false
    private var isUpdatingScrollBars: Boolean = false

    private var lastSyncSubtreeVer: Long = -1L
    private var lastSyncViewW: Int = -1
    private var lastSyncViewH: Int = -1

    private var lastVertMax: Int = -1
    private var lastVertPage: Int = -1
    private var lastVertPos: Int = -1
    private var lastHorzMax: Int = -1
    private var lastHorzPage: Int = -1
    private var lastHorzPos: Int = -1

    private class CullCandidate(
        val handle: Win32HwndHandle,
        val topInContent: Int,
        val bottomInContent: Int,
        var isCulled: Boolean = false,
        var isParked: Boolean = false
    )

    private var lastRelayoutScrollX: Int = -1
    private var lastRelayoutScrollY: Int = -1
    private var lastRelayoutSubtreeVer: Long = -1L
    private val viewportCulledHandles = LinkedHashSet<Win32HwndHandle>()
    private var cachedCullCandidates: List<CullCandidate> = emptyList()
    private var lastCullScanSubtreeVer: Long = -1L
    private var lastCullScanViewH: Int = -1
    private var cachedFastComposite: Boolean = false
    private var lastFastCompositeSubtreeVer: Long = -1L
    private var lastFastCompositeGlobalVer: Long = -1L
    private var lastFastCompositeIsDark: Boolean = false
    private val pendingShownHandles = ArrayList<Win32HwndHandle>()

    internal fun drainPendingShownHandles(out: ArrayList<Win32HwndHandle>): Boolean {
        if (pendingShownHandles.isEmpty()) return false
        out.addAll(pendingShownHandles)
        pendingShownHandles.clear()
        return true
    }

    private fun resolveFastCompositeEligible(): Boolean {
        val subtreeVer = nativeWidget.subtreeMutationVersion
        val globalVer = DesktopNativeWidget.globalNonOverlayMutationVersion
        val isDark = Win32NativeWindowHost.isEffectivelyDarkTheme()
        if (lastFastCompositeSubtreeVer == subtreeVer &&
            lastFastCompositeGlobalVer == globalVer &&
            lastFastCompositeIsDark == isDark
        ) {
            return cachedFastComposite
        }
        val eligible = Win32ScrollViewCompositor.canUseFastScrollComposite(this)
        cachedFastComposite = eligible
        lastFastCompositeSubtreeVer = subtreeVer
        lastFastCompositeGlobalVer = globalVer
        lastFastCompositeIsDark = isDark
        return eligible
    }

    init {
        layoutController = Win32RetainedControlSupport.initContainer(
            container = this,
            handle = nativeWidget,
            win32ClassName = "GenericUIScrollViewW",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_CLIPCHILDREN or WS_CLIPSIBLINGS or WS_VSCROLL
        ).also { ctrl ->
            ctrl.onZOrderUpdated = null
            ctrl.onLayoutCompleted = { sorted ->
                invalidateCullCandidates()
                syncScrollMetrics(sorted, force = true)
                relayoutChildrenOffset(sorted)
            }
        }
        nativeWidget.onScrollCallback = { msg, wParam, _ ->
            handleScrollMessage(msg.toInt(), wParam)
        }
        nativeWidget.customMessageCallback = { _, msg, wParam, _ ->
            if (msg.toInt() == WM_MOUSEWHEEL) {
                val delta = ((wParam.toLong() ushr 16).toShort().toInt()) / 120f
                val keys = (wParam.toLong() and 0xFFFFL).toInt()
                val step = (delta * 48f).roundToInt()
                if ((keys and MK_SHIFT) != 0) {
                    scrollTo((scrollX - step).coerceAtLeast(0), scrollY)
                } else {
                    scrollTo(scrollX, (scrollY - step).coerceAtLeast(0))
                }
                0L
            } else {
                null
            }
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
    }

    private fun resolveViewportSize(): Pair<Int, Int> {
        val hwnd = nativeWidget.hwnd
        if (hwnd != null && IsWindow(hwnd) != 0) {
            val rc = scratchRect ?: nativeHeap.alloc<RECT>().also { scratchRect = it }
            if (GetClientRect(hwnd, rc.ptr) != 0) {
                val cw = rc.right - rc.left
                val ch = rc.bottom - rc.top
                if (cw > 0 && ch > 0) {
                    return cw to ch
                }
            }
        }
        val viewW = (nativeWidget.bounds.width.takeIf { it > 0 } ?: nativeWidget.lastAppliedW).coerceAtLeast(1)
        val viewH = (nativeWidget.bounds.height.takeIf { it > 0 } ?: nativeWidget.lastAppliedH).coerceAtLeast(1)
        return viewW to viewH
    }

    private fun resolveLayoutViewportSize(width: Float, height: Float): Pair<Float, Float> {
        val fallbackW = width.coerceAtLeast(1f)
        val fallbackH = height.coerceAtLeast(1f)
        val hwnd = nativeWidget.hwnd
        if (hwnd != null && IsWindow(hwnd) != 0) {
            val client = memScoped {
                val rc = alloc<RECT>()
                if (GetClientRect(hwnd, rc.ptr) != 0) {
                    (rc.right - rc.left) to (rc.bottom - rc.top)
                } else {
                    null
                }
            }
            if (client != null && client.first > 0 && client.second > 0) {
                if (abs(fallbackW.roundToInt() - client.first) <= 24 &&
                    abs(fallbackH.roundToInt() - client.second) <= 24
                ) {
                    return client.first.toFloat() to client.second.toFloat()
                }
            }
        }
        return fallbackW to fallbackH
    }

    override fun scrollTo(x: Int, y: Int) {
        scrollToInternal(x, y, skipMetricsSync = false)
    }

    private fun scrollToInternal(
        x: Int,
        y: Int,
        skipMetricsSync: Boolean,
        viewWOverride: Int = -1,
        viewHOverride: Int = -1
    ) {
        val (viewW, viewH) = if (!skipMetricsSync) {
            syncScrollMetrics(force = false)
        } else if (viewWOverride > 0 && viewHOverride > 0) {
            viewWOverride to viewHOverride
        } else {
            resolveViewportSize()
        }
        val maxX = max(0, contentW - viewW)
        val maxY = max(0, contentH - viewH)
        val targetX = x.coerceIn(0, maxX)
        val targetY = y.coerceIn(0, maxY)
        if (targetX == scrollX && targetY == scrollY) return
        scrollX = targetX
        scrollY = targetY
        nativeWidget.hwnd?.let { hwnd ->
            updateScrollBarInfo(hwnd, viewW, viewH)
        }
        relayoutChildrenOffset(viewWOverride = viewW, viewHOverride = viewH)
    }

    override fun scrollToBottom() {
        val (_, viewH) = syncScrollMetrics(force = false)
        val maxY = max(0, contentH - viewH)
        scrollToInternal(scrollX, maxY, skipMetricsSync = true)
    }

    private fun handleScrollMessage(msgInt: Int, wParam: ULong) {
        val hwnd = nativeWidget.hwnd ?: return
        val (viewW, viewH) = syncScrollMetrics(force = false)
        val isHorz = msgInt == WM_HSCROLL
        val bar = if (isHorz) SB_HORZ else SB_VERT
        val code = (wParam and 0xFFFFuL).toInt()
        val viewExtent = if (isHorz) viewW else viewH
        val maxScroll = if (isHorz) max(0, contentW - viewExtent) else max(0, contentH - viewExtent)
        var pos = if (isHorz) scrollX else scrollY

        when (code) {
            SB_LINEUP -> pos -= 32
            SB_LINEDOWN -> pos += 32
            SB_PAGEUP -> pos -= (viewExtent * 0.85f).roundToInt().coerceAtLeast(32)
            SB_PAGEDOWN -> pos += (viewExtent * 0.85f).roundToInt().coerceAtLeast(32)
            SB_THUMBTRACK, SB_THUMBPOSITION -> {
                val si = scratchScrollInfo ?: nativeHeap.alloc<SCROLLINFO>().also { scratchScrollInfo = it }
                si.cbSize = sizeOf<SCROLLINFO>().toUInt()
                si.fMask = 0x10u // SIF_TRACKPOS
                GetScrollInfo(hwnd, bar, si.ptr)
                pos = si.nTrackPos
            }
        }
        pos = pos.coerceIn(0, maxScroll)
        if (isHorz) {
            scrollToInternal(pos, scrollY, skipMetricsSync = true, viewWOverride = viewW, viewHOverride = viewH)
        } else {
            scrollToInternal(scrollX, pos, skipMetricsSync = true, viewWOverride = viewW, viewHOverride = viewH)
        }
    }

    private fun updateScrollBarInfo(hwnd: HWND, viewW: Int, viewH: Int) {
        if (isUpdatingScrollBars || IsWindow(hwnd) == 0) return
        val vertMax = max(contentH - 1, 0)
        val vertPage = viewH.coerceAtLeast(1)
        val vertPos = scrollY.coerceIn(0, max(0, contentH - viewH))
        val vertRangeChanged = vertMax != lastVertMax || vertPage != lastVertPage
        val vertPosChanged = vertPos != lastVertPos

        val needHorz = if (contentW > viewW || scrollX > 0 || lastHorzMax > 0) {
            contentW > viewW || scrollX > 0 ||
                ((GetWindowLongPtrW(hwnd, GWL_STYLE).toInt() and Win32HwndHandle.WS_HSCROLL_STYLE) != 0)
        } else {
            false
        }
        val horzMax = if (contentW > viewW) max(contentW - 1, 0) else 0
        val horzPage = viewW.coerceAtLeast(1)
        val horzPos = scrollX.coerceIn(0, max(0, contentW - viewW))
        val horzRangeChanged = needHorz && (horzMax != lastHorzMax || horzPage != lastHorzPage)
        val horzPosChanged = needHorz && horzPos != lastHorzPos

        if (!vertRangeChanged && !vertPosChanged && !horzRangeChanged && !horzPosChanged) return

        val si = scratchScrollInfo ?: nativeHeap.alloc<SCROLLINFO>().also { scratchScrollInfo = it }
        isUpdatingScrollBars = true
        try {
            if (vertRangeChanged || vertPosChanged) {
                lastVertMax = vertMax
                lastVertPage = vertPage
                lastVertPos = vertPos
                si.cbSize = sizeOf<SCROLLINFO>().toUInt()
                if (vertRangeChanged) {
                    si.fMask = (SIF_RANGE or SIF_PAGE or SIF_POS or SIF_DISABLENOSCROLL_MASK).toUInt()
                    si.nMin = 0
                    si.nMax = vertMax
                    si.nPage = vertPage.toUInt()
                } else {
                    si.fMask = SIF_POS.toUInt()
                }
                si.nPos = vertPos
                SetScrollInfo(hwnd, SB_VERT, si.ptr, 1)
            }
            if (horzRangeChanged || horzPosChanged) {
                lastHorzMax = horzMax
                lastHorzPage = horzPage
                lastHorzPos = horzPos
                si.cbSize = sizeOf<SCROLLINFO>().toUInt()
                if (horzRangeChanged) {
                    si.fMask = (SIF_RANGE or SIF_PAGE or SIF_POS).toUInt()
                    si.nMin = 0
                    si.nMax = horzMax
                    si.nPage = horzPage.toUInt()
                } else {
                    si.fMask = SIF_POS.toUInt()
                }
                si.nPos = horzPos
                SetScrollInfo(hwnd, SB_HORZ, si.ptr, 1)
            }
        } finally {
            isUpdatingScrollBars = false
        }
    }

    private fun syncScrollMetrics(
        sortedChildren: List<DesktopBaseNativeImpl<*>>? = null,
        force: Boolean = false
    ): Pair<Int, Int> {
        val (viewW, viewH) = resolveViewportSize()
        val subtreeVer = nativeWidget.subtreeMutationVersion
        val cacheHit = !force &&
            sortedChildren == null &&
            contentH > 0 &&
            lastSyncSubtreeVer == subtreeVer &&
            lastSyncViewW == viewW &&
            lastSyncViewH == viewH

        if (!cacheHit) {
            val measureW = viewW.toFloat()
            val pref = layoutController?.measureScrollableContentSize(measureW)
                ?: layoutController?.measurePreferredSize(measureW, null)
            val children = sortedChildren
                ?: layoutController?.resolveChildrenSortedByZIndex()
                ?: getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
            var maxChildRight = 0
            var maxChildBottom = 0
            for (child in children) {
                if (!child.isVisible()) continue
                val b = child.nativeWidget.bounds
                if (b.right > maxChildRight) maxChildRight = b.right
                if (b.bottom > maxChildBottom) maxChildBottom = b.bottom
            }
            contentW = maxOf(pref?.width ?: 0, maxChildRight, viewW)
            contentH = maxOf(pref?.height ?: 0, maxChildBottom, viewH)
            lastSyncSubtreeVer = subtreeVer
            lastSyncViewW = viewW
            lastSyncViewH = viewH
        }

        val maxX = max(0, contentW - viewW)
        val maxY = max(0, contentH - viewH)
        if (scrollX > maxX || scrollY > maxY) {
            scrollX = scrollX.coerceIn(0, maxX)
            scrollY = scrollY.coerceIn(0, maxY)
        }
        val hwnd = nativeWidget.hwnd ?: return viewW to viewH
        updateScrollBarInfo(hwnd, viewW, viewH)
        if (!cacheHit) {
            val (postW, postH) = resolveViewportSize()
            lastSyncViewW = postW
            lastSyncViewH = postH
            return postW to postH
        }
        return viewW to viewH
    }

    private fun invalidateCullCandidates() {
        lastCullScanSubtreeVer = -1L
    }

    private fun restoreAllViewportCulledHandles() {
        pendingShownHandles.clear()
        if (viewportCulledHandles.isEmpty()) return
        val snapshot = ArrayList(viewportCulledHandles)
        viewportCulledHandles.clear()
        for (candidate in cachedCullCandidates) {
            candidate.isCulled = false
            candidate.isParked = false
        }
        val toShow = ArrayList<Win32HwndHandle>(snapshot.size)
        for (h in snapshot) {
            Win32ControlMessageRouter.setViewportCulled(h, false)
            if (h.hwnd != null && IsWindow(h.hwnd) != 0 && h.parentHwnd != null) {
                toShow.add(h)
            }
        }
        Win32ControlMessageRouter.applyScrollViewportCullingBatch(emptyList(), toShow)
    }

    private fun buildCullCandidates(
        container: DesktopBaseContainerNativeImpl<*>,
        accumY: Int,
        depth: Int,
        viewH: Int,
        outCandidates: ArrayList<CullCandidate>
    ) {
        if (depth > 8) return
        val children = container.getLogicalChildren()
        if (children.isEmpty()) return

        var visibleCount = 0
        for (child in children) {
            val base = child as? DesktopBaseNativeImpl<*> ?: continue
            if (!base.isVisible()) continue
            val h = base.nativeWidget as? Win32HwndHandle ?: continue
            if (!h.effectiveVisible || h.effectiveOpacity <= 0.001f) continue
            visibleCount++
        }

        val cullDirectChildren = visibleCount >= 8
        for (child in children) {
            val base = child as? DesktopBaseNativeImpl<*> ?: continue
            if (base is DesktopPopoverNativeImpl<*> || base is DesktopDialogWindowNativeImpl<*>) continue
            if (!base.isVisible()) continue
            val h = base.nativeWidget as? Win32HwndHandle ?: continue
            if (!h.effectiveVisible || h.effectiveOpacity <= 0.001f) continue
            val b = h.bounds
            if (b.width <= 0 || b.height <= 0) continue

            if (cullDirectChildren) {
                if (h.hwnd != null &&
                    IsWindow(h.hwnd) != 0 &&
                    h.parentHwnd != null &&
                    !Win32NativeWindowHost.isActiveFullscreenElement(h) &&
                    h.effectivePosition() != "fixed"
                ) {
                    outCandidates.add(
                        CullCandidate(
                            handle = h,
                            topInContent = accumY + b.y,
                            bottomInContent = accumY + b.bottom,
                            isCulled = h in viewportCulledHandles,
                            isParked = Win32ControlMessageRouter.isViewportParked(h)
                        )
                    )
                }
                continue
            }

            if (base is DesktopBaseContainerNativeImpl<*> &&
                (visibleCount == 1 || b.height > viewH) &&
                base !is WindowsScrollViewNativeImpl &&
                base !is WindowsLazyListNativeImpl &&
                base !is WindowsTabContainerNativeImpl
            ) {
                buildCullCandidates(
                    container = base,
                    accumY = accumY + b.y,
                    depth = depth + 1,
                    viewH = viewH,
                    outCandidates = outCandidates
                )
            }
        }
    }

    private fun ensureCullCandidatesUpToDate(viewH: Int): List<CullCandidate> {
        val subtreeVer = nativeWidget.subtreeMutationVersion
        if (lastCullScanSubtreeVer == subtreeVer &&
            lastCullScanViewH == viewH
        ) {
            return cachedCullCandidates
        }
        val collected = ArrayList<CullCandidate>()
        buildCullCandidates(
            container = this,
            accumY = 0,
            depth = 0,
            viewH = viewH,
            outCandidates = collected
        )
        if (viewportCulledHandles.isNotEmpty()) {
            val validHandles = HashSet<Win32HwndHandle>(collected.size)
            for (c in collected) {
                validHandles.add(c.handle)
            }
            val staleShow = ArrayList<Win32HwndHandle>()
            val iter = viewportCulledHandles.iterator()
            while (iter.hasNext()) {
                val h = iter.next()
                if (h !in validHandles) {
                    iter.remove()
                    Win32ControlMessageRouter.setViewportCulled(h, false)
                    staleShow.add(h)
                }
            }
            if (staleShow.isNotEmpty()) {
                Win32ControlMessageRouter.applyScrollViewportCullingBatch(emptyList(), staleShow)
            }
        }
        cachedCullCandidates = collected
        lastCullScanSubtreeVer = subtreeVer
        lastCullScanViewH = viewH
        return collected
    }

    private fun syncViewportChildVisibility(viewH: Int, suppressRedraw: Boolean = false) {
        if (viewH <= 0 || contentH <= viewH + 72) {
            restoreAllViewportCulledHandles()
            return
        }
        val candidates = ensureCullCandidatesUpToDate(viewH)
        if (candidates.isEmpty()) {
            restoreAllViewportCulledHandles()
            return
        }
        val hideTop = -72
        val hideBottom = viewH + 72
        val showTop = -24
        val showBottom = viewH + 24
        val parkTop = -(viewH + 256)
        val parkBottom = viewH * 2 + 256
        val unparkTop = -256
        val unparkBottom = viewH + 256
        var toShow: ArrayList<Win32HwndHandle>? = null
        var toHide: ArrayList<Win32HwndHandle>? = null
        var toPark: ArrayList<Win32HwndHandle>? = null
        var toUnparkHidden: ArrayList<Win32HwndHandle>? = null
        for (i in candidates.indices) {
            val c = candidates[i]
            val topInView = c.topInContent - scrollY
            val bottomInView = c.bottomInContent - scrollY
            val farOutside = bottomInView < parkTop || topInView > parkBottom
            if (!c.isCulled) {
                if (farOutside) {
                    c.isCulled = true
                    c.isParked = true
                    viewportCulledHandles.add(c.handle)
                    val list = toPark ?: ArrayList<Win32HwndHandle>().also { toPark = it }
                    list.add(c.handle)
                } else if (bottomInView < hideTop || topInView > hideBottom) {
                    c.isCulled = true
                    c.isParked = false
                    viewportCulledHandles.add(c.handle)
                    val list = toHide ?: ArrayList<Win32HwndHandle>().also { toHide = it }
                    list.add(c.handle)
                }
            } else if (!c.isParked) {
                if (bottomInView >= showTop && topInView <= showBottom) {
                    c.isCulled = false
                    viewportCulledHandles.remove(c.handle)
                    val list = toShow ?: ArrayList<Win32HwndHandle>().also { toShow = it }
                    list.add(c.handle)
                } else if (farOutside) {
                    c.isParked = true
                    val list = toPark ?: ArrayList<Win32HwndHandle>().also { toPark = it }
                    list.add(c.handle)
                }
            } else {
                if (bottomInView >= showTop && topInView <= showBottom) {
                    c.isCulled = false
                    c.isParked = false
                    viewportCulledHandles.remove(c.handle)
                    val list = toShow ?: ArrayList<Win32HwndHandle>().also { toShow = it }
                    list.add(c.handle)
                } else if (bottomInView >= unparkTop && topInView <= unparkBottom) {
                    c.isParked = false
                    val list = toUnparkHidden ?: ArrayList<Win32HwndHandle>().also { toUnparkHidden = it }
                    list.add(c.handle)
                }
            }
        }
        val hideList = toHide
        val showList = toShow
        val parkList = toPark
        val unparkHiddenList = toUnparkHidden
        if (showList != null) {
            pendingShownHandles.addAll(showList)
        }
        if (hideList != null || showList != null || parkList != null || unparkHiddenList != null) {
            Win32ControlMessageRouter.applyScrollViewportCullingBatch(
                toHide = hideList ?: emptyList(),
                toShow = showList ?: emptyList(),
                toPark = parkList ?: emptyList(),
                toUnparkHidden = unparkHiddenList ?: emptyList(),
                suppressRedraw = suppressRedraw
            )
        }
    }

    private fun relayoutChildrenOffset(
        sortedChildren: List<DesktopBaseNativeImpl<*>>? = null,
        viewWOverride: Int = -1,
        viewHOverride: Int = -1
    ) {
        val hwnd = nativeWidget.hwnd ?: return
        val viewW: Int
        val viewH: Int
        if (viewWOverride > 0 && viewHOverride > 0) {
            viewW = viewWOverride
            viewH = viewHOverride
        } else {
            val resolved = resolveViewportSize()
            viewW = resolved.first
            viewH = resolved.second
        }
        val fastComposite = resolveFastCompositeEligible()
        syncViewportChildVisibility(viewH, suppressRedraw = fastComposite)
        val children = sortedChildren
            ?: layoutController?.resolveChildrenSortedByZIndex()
            ?: getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()

        var movedFastSingleChild = false
        if (fastComposite && children.size == 1) {
            val onlyImpl = children[0]
            val h = onlyImpl.nativeWidget as? Win32HwndHandle
            val chHwnd = h?.hwnd
            if (onlyImpl !is DesktopPopoverNativeImpl<*> &&
                onlyImpl !is DesktopDialogWindowNativeImpl<*> &&
                h != null &&
                chHwnd != null &&
                IsWindow(chHwnd) != 0 &&
                h.parentHwnd == hwnd &&
                h.buddyHwnd == null &&
                h.actualWin32ClassName != "GenericUILayeredStageHostW" &&
                !h.actualWin32ClassName.equals("ComboBox", ignoreCase = true) &&
                !Win32ControlMessageRouter.isViewportCulled(h) &&
                !Win32NativeWindowHost.isActiveFullscreenElement(h)
            ) {
                val b = onlyImpl.nativeWidget.bounds
                val targetW = b.width.coerceAtLeast(0)
                val targetH = b.height.coerceAtLeast(0)
                if (h.lastAppliedW == targetW && h.lastAppliedH == targetH) {
                    val targetX = b.x - scrollX
                    val targetY = b.y - scrollY
                    if (h.lastAppliedX != targetX || h.lastAppliedY != targetY) {
                        h.lastAppliedX = targetX
                        h.lastAppliedY = targetY
                        SetWindowPos(
                            chHwnd,
                            null,
                            targetX,
                            targetY,
                            0,
                            0,
                            (SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE or SWP_NOREDRAW_FLAG or SWP_NOCOPYBITS_FLAG or SWP_NOSENDCHANGING_FLAG).toUInt()
                        )
                    }
                    movedFastSingleChild = true
                }
            }
        }
        if (!movedFastSingleChild && children.isNotEmpty()) {
            Win32ControlMessageRouter.layoutChildrenBatch(
                hwnd,
                children,
                offsetX = -scrollX,
                offsetY = -scrollY,
                recursive = false,
                suppressRedraw = fastComposite
            )
        }
        lastRelayoutScrollX = scrollX
        lastRelayoutScrollY = scrollY
        lastRelayoutSubtreeVer = nativeWidget.subtreeMutationVersion
        if (fastComposite) {
            InvalidateRect(hwnd, null, 0)
            return
        }
        var singleChildCoversViewport = false
        if (children.size == 1) {
            val onlyChild = children[0]
            if (onlyChild.isVisible() && onlyChild.nativeWidget.opacity >= 0.996f) {
                val b = onlyChild.nativeWidget.bounds
                singleChildCoversViewport =
                    (b.x - scrollX) <= 0 &&
                    (b.right - scrollX) >= viewW &&
                    (b.y - scrollY) <= 0 &&
                    (b.bottom - scrollY) >= viewH
            }
        }
        if (!singleChildCoversViewport) {
            InvalidateRect(hwnd, null, 0)
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val pref = super.measureIntrinsicSize(maxWidth, maxHeight)
        val capH = maxHeight?.roundToInt()?.takeIf { it > 0 }
        return if (capH != null && pref.height > capH) {
            Size(pref.width, capH)
        } else {
            pref
        }
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        if (isLayoutingChildren || isUpdatingScrollBars) return
        isLayoutingChildren = true
        try {
            invalidateCullCandidates()
            val (safeW, safeH) = resolveLayoutViewportSize(width, height)
            val pref = layoutController?.measureScrollableContentSize(safeW)
                ?: layoutController?.measurePreferredSize(safeW, null)
            val layoutH = maxOf(safeH, pref?.height?.toFloat() ?: safeH)
            val ran = layoutController?.layoutInBounds(safeW, layoutH) ?: false
            if (ran) {
                Win32ScrollViewCompositor.invalidateBackbufferContent()
            } else {
                syncScrollMetrics(force = false)
                if (lastRelayoutScrollX != scrollX ||
                    lastRelayoutScrollY != scrollY ||
                    lastRelayoutSubtreeVer != nativeWidget.subtreeMutationVersion
                ) {
                    relayoutChildrenOffset()
                }
            }
        } finally {
            isLayoutingChildren = false
        }
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        invalidateCullCandidates()
        Win32ScrollViewCompositor.invalidateBackbufferContent()
        Win32RetainedControlSupport.onContainerChildAdded(this, nativeWidget, childImpl)
        syncScrollMetrics(force = true)
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        invalidateCullCandidates()
        Win32ScrollViewCompositor.invalidateBackbufferContent()
        (childImpl.nativeWidget as? Win32HwndHandle)?.let { childHandle ->
            if (viewportCulledHandles.remove(childHandle)) {
                Win32ControlMessageRouter.setViewportCulled(childHandle, false)
            }
        }
        Win32RetainedControlSupport.onContainerChildRemoved(this, nativeWidget, childImpl)
        syncScrollMetrics(force = true)
    }

    override fun onChildrenClearedNative(clearedChildren: List<NativeImpl<*>>) {
        invalidateCullCandidates()
        Win32ScrollViewCompositor.invalidateBackbufferContent()
        pendingShownHandles.clear()
        Win32RetainedControlSupport.onContainerChildrenCleared(nativeWidget, clearedChildren)
        restoreAllViewportCulledHandles()
        syncScrollMetrics(force = true)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        Win32RetainedControlSupport.onContainerBoundsChanged(this, nativeWidget, rect)
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        Win32RetainedControlSupport.onVisibilityChanged(nativeWidget, visible)
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        Win32RetainedControlSupport.onEnabledChanged(nativeWidget, enabled)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        Win32RetainedControlSupport.onOpacityChanged(nativeWidget, opacity)
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        Win32RetainedControlSupport.onTooltipChanged(nativeWidget, tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        Win32RetainedControlSupport.onCursorChanged(nativeWidget, cursor)
    }

    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        Win32RetainedControlSupport.onPointerEventsChanged(nativeWidget, pointerEventsNone)
    }

    override fun onNativeZIndexChanged(zIndex: Int) {
        Win32RetainedControlSupport.onZIndexChanged(nativeWidget, zIndex)
    }

    override fun onNativeStyleSynced() {
        Win32ScrollViewCompositor.invalidateBackbufferContent()
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun dispose() {
        restoreAllViewportCulledHandles()
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }

    private companion object {
        private const val SIF_DISABLENOSCROLL_MASK: Int = 0x0008
        private const val SWP_NOREDRAW_FLAG: Int = 0x0008
        private const val SWP_NOCOPYBITS_FLAG: Int = 0x0100
        private const val SWP_NOSENDCHANGING_FLAG: Int = 0x0400
        private var scratchRect: RECT? = null
        private var scratchScrollInfo: SCROLLINFO? = null
    }
}
