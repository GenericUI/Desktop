@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopScrollViewNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.css.CssCursorValue
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
 * Retained Win32 scroll viewport (`"GenericUIScrollViewW"`) using native `SetScrollInfo`/`WM_VSCROLL`
 * and `WM_HSCROLL` / `WM_MOUSEWHEEL` offsets batched through `BeginDeferWindowPos`.
 */
class WindowsScrollViewNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIScrollViewW")
) : DesktopScrollViewNativeImpl<Win32HwndHandle>(widget) {

    private var contentW: Int = 0
    private var contentH: Int = 0

    init {
        layoutController = Win32RetainedControlSupport.initContainer(
            container = this,
            handle = nativeWidget,
            win32ClassName = "GenericUIScrollViewW",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_CLIPCHILDREN or WS_CLIPSIBLINGS or WS_VSCROLL
        ).also { ctrl ->
            ctrl.onZOrderUpdated = { sorted ->
                nativeWidget.hwnd?.let { parentHwnd ->
                    syncScrollMetrics()
                    Win32ControlMessageRouter.layoutChildrenBatch(
                        parentHwnd = parentHwnd,
                        children = sorted,
                        offsetX = -scrollX,
                        offsetY = -scrollY
                    )
                }
            }
        }
        nativeWidget.onScrollCallback = { msg, wParam, _ ->
            handleScrollMessage(msg.toInt(), wParam)
        }
        nativeWidget.customMessageCallback = { _, msg, wParam, _ ->
            if (msg.toInt() == WM_MOUSEWHEEL) {
                val delta = ((wParam.toLong() ushr 16).toShort().toInt()) / 120f
                scrollTo(scrollX, (scrollY - (delta * 48f).roundToInt()).coerceAtLeast(0))
                0L
            } else {
                null
            }
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
    }

    override fun scrollTo(x: Int, y: Int) {
        val viewW = nativeWidget.bounds.width.coerceAtLeast(1)
        val viewH = nativeWidget.bounds.height.coerceAtLeast(1)
        val maxX = max(0, contentW - viewW)
        val maxY = max(0, contentH - viewH)
        super.scrollTo(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
        syncScrollMetrics()
        relayoutChildrenOffset()
    }

    override fun scrollToBottom() {
        val viewH = nativeWidget.bounds.height.coerceAtLeast(1)
        val maxY = max(0, contentH - viewH)
        scrollTo(scrollX, maxY)
    }

    private fun handleScrollMessage(msgInt: Int, wParam: ULong) {
        val hwnd = nativeWidget.hwnd ?: return
        val isHorz = msgInt == WM_HSCROLL
        val bar = if (isHorz) SB_HORZ else SB_VERT
        val code = (wParam and 0xFFFFuL).toInt()
        val viewExtent = if (isHorz) nativeWidget.bounds.width.coerceAtLeast(1) else nativeWidget.bounds.height.coerceAtLeast(1)
        val maxScroll = if (isHorz) max(0, contentW - viewExtent) else max(0, contentH - viewExtent)
        var pos = if (isHorz) scrollX else scrollY

        when (code) {
            SB_LINEUP -> pos -= 32
            SB_LINEDOWN -> pos += 32
            SB_PAGEUP -> pos -= (viewExtent * 0.85f).roundToInt().coerceAtLeast(32)
            SB_PAGEDOWN -> pos += (viewExtent * 0.85f).roundToInt().coerceAtLeast(32)
            SB_THUMBTRACK, SB_THUMBPOSITION -> {
                pos = memScoped {
                    val si = alloc<SCROLLINFO>()
                    si.cbSize = sizeOf<SCROLLINFO>().toUInt()
                    si.fMask = 0x10u // SIF_TRACKPOS
                    GetScrollInfo(hwnd, bar, si.ptr)
                    si.nTrackPos
                }
            }
        }
        pos = pos.coerceIn(0, maxScroll)
        if (isHorz) {
            scrollTo(pos, scrollY)
        } else {
            scrollTo(scrollX, pos)
        }
    }

    private fun syncScrollMetrics() {
        val hwnd = nativeWidget.hwnd ?: return
        val pref = layoutController?.measurePreferredSize(
            nativeWidget.bounds.width.takeIf { it > 0 }?.toFloat(),
            null
        )
        contentW = pref?.width ?: nativeWidget.bounds.width
        contentH = pref?.height ?: nativeWidget.bounds.height
        val viewH = nativeWidget.bounds.height.coerceAtLeast(1)
        val maxY = max(0, contentH - viewH)
        if (scrollY > maxY) {
            updateScrollPositionFromHost(scrollX, maxY)
        }
        memScoped {
            val si = alloc<SCROLLINFO>()
            si.cbSize = sizeOf<SCROLLINFO>().toUInt()
            si.fMask = (SIF_RANGE or SIF_PAGE or SIF_POS).toUInt()
            si.nMin = 0
            si.nMax = max(contentH - 1, 0)
            si.nPage = viewH.toUInt()
            si.nPos = scrollY
            SetScrollInfo(hwnd, SB_VERT, si.ptr, 1)
        }
    }

    private fun relayoutChildrenOffset() {
        val hwnd = nativeWidget.hwnd ?: return
        val children = getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
        if (children.isNotEmpty()) {
            Win32ControlMessageRouter.layoutChildrenBatch(hwnd, children, offsetX = -scrollX, offsetY = -scrollY)
        }
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        Win32RetainedControlSupport.onContainerChildAdded(this, nativeWidget, childImpl)
        syncScrollMetrics()
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        Win32RetainedControlSupport.onContainerChildRemoved(this, nativeWidget, childImpl)
        syncScrollMetrics()
    }

    override fun onChildrenClearedNative() {
        Win32RetainedControlSupport.onContainerChildrenCleared(nativeWidget, childImpls.toList())
        syncScrollMetrics()
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
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
