@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.lazy.DesktopLazyListNativeImpl
import com.nexustools.genericui.enums.ui.Orientation
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32InteractiveSurfaceHwnd
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import platform.windows.GetClientRect
import platform.windows.GetScrollInfo
import platform.windows.InvalidateRect
import platform.windows.IsWindow
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
import platform.windows.SIF_TRACKPOS
import platform.windows.SetScrollInfo
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
 * Retained Win32 virtualized `LazyList` / `LazyColumn` viewport (`"GenericUIScrollViewW"`),
 * mounting materialized recycled item child `HWND`s directly inside a native scrolling viewport.
 */
class WindowsLazyListNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIScrollViewW")
) : DesktopLazyListNativeImpl<Win32HwndHandle>(widget) {

    private var isLayingOutChildrenBounds: Boolean = false
    private var isLayingOutItems: Boolean = false
    private var isRematerializing: Boolean = false
    private var isUpdatingScrollBar: Boolean = false
    private var lastDispatchedLayoutExtentPx: Float = -1f
    private var lastScrollMax: Int = -1
    private var lastScrollPage: Int = -1
    private var lastScrollPos: Int = -1

    init {
        layoutController = Win32RetainedControlSupport.initContainer(
            container = this,
            handle = nativeWidget,
            win32ClassName = "GenericUIScrollViewW",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_CLIPCHILDREN or WS_CLIPSIBLINGS or WS_VSCROLL
        )
        nativeWidget.onScrollCallback = { msg, wParam, _ ->
            handleScrollMessage(msg.toInt(), wParam)
        }
        nativeWidget.customMessageCallback = { _, msg, wParam, _ ->
            if (msg.toInt() == WM_MOUSEWHEEL) {
                val delta = ((wParam.toLong() ushr 16).toShort().toInt()) / 120f
                val viewExtent = resolveViewportExtent().coerceAtLeast(1f)
                val effectiveContentExtent = maxOf(viewportEngine.totalContentExtentPx, totalContentExtentPx)
                val maxScroll = max(0f, effectiveContentExtent - viewExtent)
                val nextOffset = (viewportEngine.scrollOffsetPx - delta * 48f).coerceIn(0f, maxScroll)
                dispatchHostViewportScroll(nextOffset, viewExtent)
                syncScrollBar()
                layoutVisibleItemHwnds()
                nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
                0L
            } else {
                null
            }
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
    }

    private fun resolveViewportDims(widthOverride: Int? = null, heightOverride: Int? = null): Pair<Int, Int> {
        val fallbackW = widthOverride ?: nativeWidget.bounds.width.takeIf { it > 0 } ?: nativeWidget.lastAppliedW
        val fallbackH = heightOverride ?: nativeWidget.bounds.height.takeIf { it > 0 } ?: nativeWidget.lastAppliedH
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
                if (fallbackW <= 0 || fallbackH <= 0 ||
                    (abs(fallbackW - client.first) <= 24 && abs(fallbackH - client.second) <= 24)
                ) {
                    return client.first to client.second
                }
            }
        }
        return fallbackW to fallbackH
    }

    private fun resolveViewportExtent(): Float {
        val (w, h) = resolveViewportDims()
        return (if (orientationState == Orientation.HORIZONTAL) w.coerceAtLeast(1) else h.coerceAtLeast(1)).toFloat()
    }

    private fun handleScrollMessage(msgInt: Int, wParam: ULong) {
        val hwnd = nativeWidget.hwnd ?: return
        val bar = if (msgInt == WM_HSCROLL) SB_HORZ else SB_VERT
        val code = (wParam and 0xFFFFuL).toInt()
        val viewExtent = resolveViewportExtent().coerceAtLeast(1f)
        val effectiveContentExtent = maxOf(viewportEngine.totalContentExtentPx, totalContentExtentPx)
        val maxScroll = max(0f, effectiveContentExtent - viewExtent)
        var pos = viewportEngine.scrollOffsetPx

        when (code) {
            SB_LINEUP -> pos -= 32f
            SB_LINEDOWN -> pos += 32f
            SB_PAGEUP -> pos -= (viewExtent * 0.85f).coerceAtLeast(32f)
            SB_PAGEDOWN -> pos += (viewExtent * 0.85f).coerceAtLeast(32f)
            SB_THUMBTRACK, SB_THUMBPOSITION -> {
                pos = memScoped {
                    val si = alloc<SCROLLINFO>()
                    si.cbSize = sizeOf<SCROLLINFO>().toUInt()
                    si.fMask = SIF_TRACKPOS.toUInt()
                    GetScrollInfo(hwnd, bar, si.ptr)
                    si.nTrackPos.toFloat()
                }
            }
        }
        val clamped = pos.coerceIn(0f, maxScroll)
        dispatchHostViewportScroll(clamped, viewExtent)
        syncScrollBar()
        layoutVisibleItemHwnds()
        InvalidateRect(hwnd, null, 0)
    }

    private fun syncScrollBar() {
        val hwnd = nativeWidget.hwnd ?: return
        if (isUpdatingScrollBar || IsWindow(hwnd) == 0) return
        val viewExtent = resolveViewportExtent().roundToInt().coerceAtLeast(1)
        val totalExtent = max(viewportEngine.totalContentExtentPx.roundToInt(), totalContentExtentPx.roundToInt())
        val nMax = max(totalExtent - 1, 0)
        val nPage = viewExtent
        val nPos = viewportEngine.scrollOffsetPx.roundToInt().coerceIn(0, max(0, totalExtent - viewExtent))
        if (nMax == lastScrollMax && nPage == lastScrollPage && nPos == lastScrollPos) return
        val bar = if (orientationState == Orientation.HORIZONTAL) SB_HORZ else SB_VERT
        isUpdatingScrollBar = true
        lastScrollMax = nMax
        lastScrollPage = nPage
        lastScrollPos = nPos
        try {
            memScoped {
                val si = alloc<SCROLLINFO>()
                si.cbSize = sizeOf<SCROLLINFO>().toUInt()
                si.fMask = (SIF_RANGE or SIF_PAGE or SIF_POS or SIF_DISABLENOSCROLL_MASK).toUInt()
                si.nMin = 0
                si.nMax = nMax
                si.nPage = nPage.toUInt()
                si.nPos = nPos
                SetScrollInfo(hwnd, bar, si.ptr, 1)
            }
        } finally {
            isUpdatingScrollBar = false
        }
    }

    private fun layoutVisibleItemHwnds(widthOverride: Int? = null, heightOverride: Int? = null) {
        if (isLayingOutItems) return
        val hwnd = nativeWidget.hwnd ?: return
        val (w, h) = resolveViewportDims(widthOverride, heightOverride)
        if (w <= 0 || h <= 0) return

        isLayingOutItems = true
        try {
            val ext = viewportEngine.estimatedItemExtentPx.roundToInt().coerceAtLeast(24)
            val scrollPx = viewportEngine.scrollOffsetPx.roundToInt()
            val snapshot = materializedItemsSnapshot()
            if (snapshot.isNotEmpty()) {
                val ordered = snapshot.entries.sortedBy { it.key }
                var cursor = ordered.first().key * ext
                var prevIdx = ordered.first().key
                val visibleChildren = ArrayList<DesktopBaseNativeImpl<*>>(ordered.size)
                for ((idx, item) in ordered) {
                    val base = item as? DesktopBaseNativeImpl<*> ?: continue
                    val itemHandle = base.nativeWidget as? Win32HwndHandle ?: continue
                    if (itemHandle.parentHwnd != hwnd) {
                        Win32ControlMessageRouter.attachChildHwnd(itemHandle, hwnd)
                    }
                    if (idx > prevIdx + 1) {
                        cursor += (idx - prevIdx - 1) * ext
                    }
                    prevIdx = idx
                    val pref = base.measureIntrinsicSize(
                        if (orientationState == Orientation.HORIZONTAL) null else w.toFloat(),
                        if (orientationState == Orientation.HORIZONTAL) h.toFloat() else null
                    )
                    val rect = if (orientationState == Orientation.HORIZONTAL) {
                        val cw = pref.width.coerceAtLeast(ext)
                        val r = Rect(cursor - scrollPx, 0, cw, h)
                        cursor += cw
                        r
                    } else {
                        val ch = pref.height.coerceAtLeast(ext)
                        val r = Rect(0, cursor - scrollPx, w, ch)
                        cursor += ch
                        r
                    }
                    base.setBounds(rect)
                    if (base is DesktopBaseContainerNativeImpl<*>) {
                        base.layoutChildrenInBounds(rect.width.toFloat(), rect.height.toFloat())
                    }
                    visibleChildren.add(base)
                }
                if (visibleChildren.isNotEmpty()) {
                    Win32ControlMessageRouter.layoutChildrenBatch(hwnd, visibleChildren, recursive = true)
                }
            } else {
                val logical = getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
                if (logical.isNotEmpty()) {
                    var offset = leadingSpacerPx.roundToInt() - scrollPx
                    val visibleChildren = ArrayList<DesktopBaseNativeImpl<*>>(logical.size)
                    for (base in logical) {
                        val itemHandle = base.nativeWidget as? Win32HwndHandle ?: continue
                        if (itemHandle.parentHwnd != hwnd) {
                            Win32ControlMessageRouter.attachChildHwnd(itemHandle, hwnd)
                        }
                        val pref = base.measureIntrinsicSize(
                            if (orientationState == Orientation.HORIZONTAL) null else w.toFloat(),
                            if (orientationState == Orientation.HORIZONTAL) h.toFloat() else null
                        )
                        val rect = if (orientationState == Orientation.HORIZONTAL) {
                            val cw = pref.width.coerceAtLeast(ext)
                            val r = Rect(offset, 0, cw, h)
                            offset += cw
                            r
                        } else {
                            val ch = pref.height.coerceAtLeast(ext)
                            val r = Rect(0, offset, w, ch)
                            offset += ch
                            r
                        }
                        base.setBounds(rect)
                        if (base is DesktopBaseContainerNativeImpl<*>) {
                            base.layoutChildrenInBounds(rect.width.toFloat(), rect.height.toFloat())
                        }
                        visibleChildren.add(base)
                    }
                    if (visibleChildren.isNotEmpty()) {
                        Win32ControlMessageRouter.layoutChildrenBatch(hwnd, visibleChildren, recursive = true)
                    }
                }
            }
        } finally {
            isLayingOutItems = false
        }
    }

    private fun shouldSuppressLayoutSync(): Boolean =
        isLayingOutChildrenBounds ||
            isLayingOutItems ||
            isRematerializing ||
            DesktopNativeWidget.activeViewportScrollScopeCount.value > 0

    private fun dispatchLayoutViewportExtentIfNeeded(ext: Float) {
        if (abs(ext - lastDispatchedLayoutExtentPx) > 0.5f ||
            (viewportEngine.itemCount > 0 && materializedItemsSnapshot().isEmpty() && getLogicalChildren().isEmpty())
        ) {
            lastDispatchedLayoutExtentPx = ext
            dispatchLayoutViewportExtent(ext)
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val w = (maxWidth?.roundToInt() ?: 320).coerceAtLeast(1)
        val h = (maxHeight?.roundToInt() ?: 240).coerceAtLeast(1)
        return Size(w, h)
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        if (isLayingOutChildrenBounds || isUpdatingScrollBar) return
        if (width <= 0f || height <= 0f) return
        isLayingOutChildrenBounds = true
        try {
            val (vw, vh) = resolveViewportDims(width.roundToInt(), height.roundToInt())
            val ext = (if (orientationState == Orientation.HORIZONTAL) vw else vh).coerceAtLeast(1).toFloat()
            dispatchLayoutViewportExtentIfNeeded(ext)
            syncScrollBar()
            layoutVisibleItemHwnds(vw, vh)
        } finally {
            isLayingOutChildrenBounds = false
        }
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
        super.updateViewportMetrics(
            scrollOffsetPx,
            viewportExtentPx,
            firstActiveIndex,
            lastActiveIndex,
            leadingSpacerPx,
            trailingSpacerPx,
            totalContentExtentPx
        )
        syncScrollBar()
        beginViewportScrollScope()
        try {
            layoutVisibleItemHwnds()
        } finally {
            endViewportScrollScope()
        }
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun rematerializeVisibleWindow() {
        isRematerializing = true
        beginViewportScrollScope()
        try {
            super.rematerializeVisibleWindow()
            val hostHwnd = nativeWidget.hwnd
            if (hostHwnd != null) {
                for (item in materializedItemsSnapshot().values) {
                    val base = item as? DesktopBaseNativeImpl<*> ?: continue
                    val h = base.nativeWidget as? Win32HwndHandle ?: continue
                    if (h.parentHwnd != hostHwnd) {
                        Win32ControlMessageRouter.attachChildHwnd(h, hostHwnd)
                    }
                }
            }
            syncScrollBar()
            layoutVisibleItemHwnds()
            hostHwnd?.let { InvalidateRect(it, null, 0) }
        } finally {
            endViewportScrollScope()
            isRematerializing = false
        }
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        if (shouldSuppressLayoutSync()) {
            val parentHwnd = nativeWidget.hwnd
            val base = childImpl as? DesktopBaseNativeImpl<*>
            val childHandle = base?.nativeWidget as? Win32HwndHandle
            if (parentHwnd != null && childHandle != null) {
                Win32InteractiveSurfaceHwnd.ensureSurfaceChildHwnd(childHandle, base)
                Win32ControlMessageRouter.attachChildHwnd(childHandle, parentHwnd)
            }
        } else {
            Win32RetainedControlSupport.onContainerChildAdded(this, nativeWidget, childImpl)
        }
        if (isRematerializing) return
        val (w, h) = resolveViewportDims()
        if (w > 0 && h > 0) {
            syncScrollBar()
            layoutVisibleItemHwnds(w, h)
            nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
        }
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        val childHandle = childImpl.nativeWidget as? Win32HwndHandle
        if (childHandle != null) {
            Win32ControlMessageRouter.detachChildHwnd(childHandle)
        }
        if (isRematerializing) return
        val (w, h) = resolveViewportDims()
        if (w > 0 && h > 0) {
            syncScrollBar()
            layoutVisibleItemHwnds(w, h)
            nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
        }
        if (!shouldSuppressLayoutSync()) {
            Win32NativeWindowHost.requestCoalescedNonOverlayLayoutSync()
        }
    }

    override fun onChildrenClearedNative(clearedChildren: List<NativeImpl<*>>) {
        if (shouldSuppressLayoutSync()) {
            for (child in clearedChildren) {
                val childHandle = child.nativeWidget as? Win32HwndHandle ?: continue
                Win32ControlMessageRouter.detachChildHwnd(childHandle)
            }
            nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
        } else {
            Win32RetainedControlSupport.onContainerChildrenCleared(nativeWidget, clearedChildren)
        }
        syncScrollBar()
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        Win32ControlMessageRouter.applyBounds(nativeWidget, rect)
        if (rect.width > 0 && rect.height > 0) {
            val (vw, vh) = resolveViewportDims(rect.width, rect.height)
            val ext = (if (orientationState == Orientation.HORIZONTAL) vw else vh).coerceAtLeast(1).toFloat()
            dispatchLayoutViewportExtentIfNeeded(ext)
            syncScrollBar()
            layoutVisibleItemHwnds(vw, vh)
        }
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
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }

    private companion object {
        private const val SIF_DISABLENOSCROLL_MASK: Int = 0x0008
    }
}
