@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyListNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.ui.Orientation
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
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Retained Win32 virtualized `LazyList` / `LazyColumn` viewport (`"GenericUIScrollViewW"`),
 * mounting materialized recycled item child `HWND`s directly inside a native scrolling viewport.
 */
class WindowsLazyListNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIScrollViewW")
) : DesktopLazyListNativeImpl<Win32HwndHandle>(widget) {

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
                val maxScroll = max(0f, viewportEngine.totalContentExtentPx - viewExtent)
                val nextOffset = (viewportEngine.scrollOffsetPx - delta * 48f).coerceIn(0f, maxScroll)
                dispatchHostViewportScroll(nextOffset, viewExtent)
                syncScrollBar()
                layoutVisibleItemHwnds()
                0L
            } else {
                null
            }
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
    }

    private fun resolveViewportExtent(): Float {
        val w = nativeWidget.bounds.width.takeIf { it > 0 } ?: nativeWidget.lastAppliedW.coerceAtLeast(1)
        val h = nativeWidget.bounds.height.takeIf { it > 0 } ?: nativeWidget.lastAppliedH.coerceAtLeast(1)
        return (if (orientationState == Orientation.HORIZONTAL) w else h).toFloat()
    }

    private fun handleScrollMessage(msgInt: Int, wParam: ULong) {
        val hwnd = nativeWidget.hwnd ?: return
        val bar = if (msgInt == WM_HSCROLL) SB_HORZ else SB_VERT
        val code = (wParam and 0xFFFFuL).toInt()
        val viewExtent = resolveViewportExtent().coerceAtLeast(1f)
        val maxScroll = max(0f, viewportEngine.totalContentExtentPx - viewExtent)
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
    }

    private fun syncScrollBar() {
        val hwnd = nativeWidget.hwnd ?: return
        val viewExtent = resolveViewportExtent().roundToInt().coerceAtLeast(1)
        val totalExtent = max(viewportEngine.totalContentExtentPx.roundToInt(), totalContentExtentPx.roundToInt())
        val bar = if (orientationState == Orientation.HORIZONTAL) SB_HORZ else SB_VERT
        memScoped {
            val si = alloc<SCROLLINFO>()
            si.cbSize = sizeOf<SCROLLINFO>().toUInt()
            si.fMask = (SIF_RANGE or SIF_PAGE or SIF_POS).toUInt()
            si.nMin = 0
            si.nMax = max(totalExtent - 1, 0)
            si.nPage = viewExtent.toUInt()
            si.nPos = viewportEngine.scrollOffsetPx.roundToInt().coerceAtLeast(0)
            SetScrollInfo(hwnd, bar, si.ptr, 1)
        }
    }

    private fun layoutVisibleItemHwnds() {
        val hwnd = nativeWidget.hwnd ?: return
        val w = nativeWidget.bounds.width.takeIf { it > 0 } ?: nativeWidget.lastAppliedW
        val h = nativeWidget.bounds.height.takeIf { it > 0 } ?: nativeWidget.lastAppliedH
        if (w <= 0 || h <= 0) return

        val ordered = materializedItemsSnapshot().entries.sortedBy { it.key }
        val ext = viewportEngine.estimatedItemExtentPx.roundToInt().coerceAtLeast(24)
        val scrollPx = viewportEngine.scrollOffsetPx.roundToInt()

        for ((idx, item) in ordered) {
            val base = item as? DesktopBaseNativeImpl<*> ?: continue
            val itemHandle = base.nativeWidget as? Win32HwndHandle ?: continue
            if (itemHandle.parentHwnd != hwnd) {
                Win32ControlMessageRouter.attachChildHwnd(itemHandle, hwnd)
            }
            val pref = base.measureIntrinsicSize(w.toFloat(), null)
            val rect = if (orientationState == Orientation.HORIZONTAL) {
                val cw = pref.width.coerceAtLeast(ext)
                Rect(idx * ext - scrollPx, 0, cw, h)
            } else {
                val ch = pref.height.coerceAtLeast(ext)
                Rect(0, idx * ext - scrollPx, w, ch)
            }
            base.setBounds(rect)
            if (base is com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl<*>) {
                base.layoutChildrenInBounds(rect.width.toFloat(), rect.height.toFloat())
            }
        }
        val visibleChildren = ordered.mapNotNull { it.value as? DesktopBaseNativeImpl<*> }
        Win32ControlMessageRouter.layoutChildrenBatch(hwnd, visibleChildren)
    }

    override fun rematerializeVisibleWindow() {
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
            val ext = (if (orientationState == Orientation.HORIZONTAL) rect.width else rect.height).toFloat()
            if (kotlin.math.abs(viewportEngine.viewportExtentPx - ext) > 0.5f) {
                viewportEngine.viewportExtentPx = ext
                rematerializeVisibleWindow()
            } else {
                syncScrollBar()
                layoutVisibleItemHwnds()
            }
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
