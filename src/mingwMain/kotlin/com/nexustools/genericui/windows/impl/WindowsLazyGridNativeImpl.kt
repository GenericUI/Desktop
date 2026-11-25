@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyGridNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import platform.windows.GetScrollInfo
import platform.windows.InvalidateRect
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
import platform.windows.WM_MOUSEWHEEL
import platform.windows.WM_VSCROLL
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPCHILDREN
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_VISIBLE
import platform.windows.WS_VSCROLL
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Retained Win32 virtualized `LazyGrid` viewport (`"GenericUIScrollViewW"`), supporting adaptive
 * `minColumnWidthPx` column computation and recycled grid-cell child `HWND`s.
 */
class WindowsLazyGridNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIScrollViewW")
) : DesktopLazyGridNativeImpl<Win32HwndHandle>(widget) {

    var minColumnWidthPx: Int = 0
        private set

    init {
        layoutController = Win32RetainedControlSupport.initContainer(
            container = this,
            handle = nativeWidget,
            win32ClassName = "GenericUIScrollViewW",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_CLIPCHILDREN or WS_CLIPSIBLINGS or WS_VSCROLL
        )
        nativeWidget.onScrollCallback = { msg, wParam, _ ->
            if (msg.toInt() == WM_VSCROLL) {
                handleVerticalScroll(wParam)
            }
        }
        nativeWidget.customMessageCallback = { _, msg, wParam, _ ->
            if (msg.toInt() == WM_MOUSEWHEEL) {
                val delta = ((wParam.toLong() ushr 16).toShort().toInt()) / 120f
                val viewH = resolveViewportHeight().coerceAtLeast(1f)
                val maxScroll = max(0f, viewportEngine.totalContentExtentPx - viewH)
                viewportEngine.scrollOffsetPx = (viewportEngine.scrollOffsetPx - delta * 48f).coerceIn(0f, maxScroll)
                rematerializeVisibleWindow()
                0L
            } else {
                null
            }
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
    }

    override fun setMinColumnWidthPx(minWidthPx: Int) {
        minColumnWidthPx = minWidthPx.coerceAtLeast(0)
        nativeWidget.attributes["minColumnWidthPx"] = minColumnWidthPx
        recomputeAdaptiveColumns(nativeWidget.bounds.width)
    }

    fun getMinColumnWidthPx(): Int = minColumnWidthPx

    private fun resolveViewportHeight(): Float =
        (nativeWidget.bounds.height.takeIf { it > 0 } ?: nativeWidget.lastAppliedH.coerceAtLeast(1)).toFloat()

    private fun recomputeAdaptiveColumns(widthPx: Int) {
        if (minColumnWidthPx > 0 && widthPx > 0) {
            val cols = (widthPx / minColumnWidthPx).coerceAtLeast(1)
            if (viewportEngine.columns != cols) {
                viewportEngine.columns = cols
                rematerializeVisibleWindow()
            }
        }
    }

    private fun handleVerticalScroll(wParam: ULong) {
        val hwnd = nativeWidget.hwnd ?: return
        val code = (wParam and 0xFFFFuL).toInt()
        val viewH = resolveViewportHeight().coerceAtLeast(1f)
        val maxScroll = max(0f, viewportEngine.totalContentExtentPx - viewH)
        var pos = viewportEngine.scrollOffsetPx
        when (code) {
            SB_LINEUP -> pos -= 36f
            SB_LINEDOWN -> pos += 36f
            SB_PAGEUP -> pos -= (viewH * 0.85f).coerceAtLeast(36f)
            SB_PAGEDOWN -> pos += (viewH * 0.85f).coerceAtLeast(36f)
            SB_THUMBTRACK, SB_THUMBPOSITION -> {
                pos = memScoped {
                    val si = alloc<SCROLLINFO>()
                    si.cbSize = sizeOf<SCROLLINFO>().toUInt()
                    si.fMask = SIF_TRACKPOS.toUInt()
                    GetScrollInfo(hwnd, SB_VERT, si.ptr)
                    si.nTrackPos.toFloat()
                }
            }
        }
        viewportEngine.scrollOffsetPx = pos.coerceIn(0f, maxScroll)
        rematerializeVisibleWindow()
    }

    private fun syncScrollBar() {
        val hwnd = nativeWidget.hwnd ?: return
        val viewH = resolveViewportHeight().roundToInt().coerceAtLeast(1)
        val totalH = viewportEngine.totalContentExtentPx.roundToInt().coerceAtLeast(0)
        memScoped {
            val si = alloc<SCROLLINFO>()
            si.cbSize = sizeOf<SCROLLINFO>().toUInt()
            si.fMask = (SIF_RANGE or SIF_PAGE or SIF_POS).toUInt()
            si.nMin = 0
            si.nMax = max(totalH - 1, 0)
            si.nPage = viewH.toUInt()
            si.nPos = viewportEngine.scrollOffsetPx.roundToInt().coerceAtLeast(0)
            SetScrollInfo(hwnd, SB_VERT, si.ptr, 1)
        }
    }

    private fun layoutGridCellHwnds() {
        val hwnd = nativeWidget.hwnd ?: return
        val w = nativeWidget.bounds.width.takeIf { it > 0 } ?: nativeWidget.lastAppliedW
        val h = nativeWidget.bounds.height.takeIf { it > 0 } ?: nativeWidget.lastAppliedH
        if (w <= 0 || h <= 0) return

        val cols = getColumnCount().coerceAtLeast(1)
        val cellW = (w / cols).coerceAtLeast(24)
        val cellH = viewportEngine.estimatedItemExtentPx.roundToInt().coerceAtLeast(24)
        val scrollPx = viewportEngine.scrollOffsetPx.roundToInt()

        val ordered = materializedItems.entries.map { it.key to it.value }.sortedBy { it.first }
        for ((idx, item) in ordered) {
            val base = item as? DesktopBaseNativeImpl<*> ?: continue
            val cellHandle = base.nativeWidget as? Win32HwndHandle ?: continue
            if (cellHandle.parentHwnd != hwnd) {
                Win32ControlMessageRouter.attachChildHwnd(cellHandle, hwnd)
            }
            val row = idx / cols
            val col = idx % cols
            val rect = Rect(col * cellW, row * cellH - scrollPx, cellW, cellH)
            base.setBounds(rect)
            if (base is com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl<*>) {
                base.layoutChildrenInBounds(rect.width.toFloat(), rect.height.toFloat())
            }
        }
        val visibleCells = ordered.mapNotNull { it.second as? DesktopBaseNativeImpl<*> }
        Win32ControlMessageRouter.layoutChildrenBatch(hwnd, visibleCells)
    }

    override fun rematerializeVisibleWindow() {
        super.rematerializeVisibleWindow()
        syncScrollBar()
        layoutGridCellHwnds()
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        Win32RetainedControlSupport.onContainerChildAdded(this, nativeWidget, childImpl)
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        val childHandle = childImpl.nativeWidget as? Win32HwndHandle
        if (childHandle != null) {
            Win32ControlMessageRouter.detachChildHwnd(childHandle)
        }
    }

    override fun onChildrenClearedNative() {
        Win32RetainedControlSupport.onContainerChildrenCleared(nativeWidget, childImpls.toList())
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        Win32ControlMessageRouter.applyBounds(nativeWidget, rect)
        if (rect.width > 0 && rect.height > 0) {
            viewportEngine.viewportExtentPx = rect.height.toFloat()
            recomputeAdaptiveColumns(rect.width)
            syncScrollBar()
            layoutGridCellHwnds()
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
}
