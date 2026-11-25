@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopBottomNavigationBarNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import com.nexustools.genericui.windows.Win32StyledWidgetHelper
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.CreateSolidBrush
import platform.windows.DT_CENTER
import platform.windows.DT_END_ELLIPSIS
import platform.windows.DT_NOPREFIX
import platform.windows.DT_SINGLELINE
import platform.windows.DT_VCENTER
import platform.windows.DeleteObject
import platform.windows.DrawTextW
import platform.windows.FillRect
import platform.windows.InvalidateRect
import platform.windows.RECT
import platform.windows.SelectObject
import platform.windows.SetBkMode
import platform.windows.SetTextColor
import platform.windows.TRANSPARENT
import platform.windows.VK_LEFT
import platform.windows.VK_RIGHT
import platform.windows.WM_KEYDOWN
import platform.windows.WM_LBUTTONUP
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE

/**
 * Retained Win32 segmented navigation bar (`"GenericUIBottomNavW"`) for `BottomNavigationBar`,
 * rendering evenly spaced navigation segments with keyboard/pointer selection.
 */
class WindowsBottomNavigationBarNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIBottomNavW")
) : DesktopBottomNavigationBarNativeImpl<Win32HwndHandle>(widget) {

    init {
        nativeWidget.attributes["nativeImpl"] = this
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "GenericUIBottomNavW",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS,
            initialWidth = 360,
            initialHeight = 52,
            installSubclass = false
        )
        nativeWidget.customPaintCallback = { hwnd, hdc, w, h ->
            paintSegments(hwnd, hdc, w, h)
        }
        nativeWidget.customMessageCallback = { _, msg, wParam, lParam ->
            when (msg.toInt()) {
                WM_LBUTTONUP -> {
                    val x = (lParam and 0xFFFFL).toShort().toInt()
                    val w = nativeWidget.bounds.width.takeIf { it > 0 } ?: nativeWidget.lastAppliedW
                    if (itemsList.isNotEmpty() && w > 0) {
                        val idx = ((x * itemsList.size) / w).coerceIn(0, itemsList.lastIndex)
                        setSelectedItemId(itemsList[idx].first)
                    }
                    null
                }
                WM_KEYDOWN -> {
                    if (itemsList.isNotEmpty()) {
                        val curIdx = itemsList.indexOfFirst { it.first == selectedId }.coerceAtLeast(0)
                        val vk = (wParam and 0xFFFFuL).toInt()
                        when (vk) {
                            VK_LEFT -> setSelectedItemId(itemsList[(curIdx - 1).coerceAtLeast(0)].first)
                            VK_RIGHT -> setSelectedItemId(itemsList[(curIdx + 1).coerceAtMost(itemsList.lastIndex)].first)
                        }
                    }
                    null
                }
                else -> null
            }
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    private fun paintSegments(hwnd: platform.windows.HWND, hdc: platform.windows.HDC, w: Int, h: Int) {
        val dark = Win32NativeWindowHost.isEffectivelyDarkTheme()
        val bg = if (dark) RgbaColor(30, 33, 40) else RgbaColor(247, 248, 250)
        val border = if (dark) RgbaColor(58, 62, 70) else RgbaColor(218, 222, 228)
        Win32StyledWidgetHelper.renderStyledSpecToHdc(
            hwnd = hwnd,
            hdc = hdc,
            width = w,
            height = h,
            spec = StyledVisualSpec(
                backgroundColor = bg,
                borderColor = border,
                borderWidthPx = 1f,
                borderRadiusPx = 0f
            ),
            isDark = dark
        )
        if (itemsList.isEmpty()) return

        val segW = w.toFloat() / itemsList.size
        val accentRef = 0x00FD6E0Du
        val normalTextRef = if (dark) 0x00D0D4DCu else 0x00495057u
        val oldFont = nativeWidget.fontHandle?.let { SelectObject(hdc, it) }
        val prevBk = SetBkMode(hdc, TRANSPARENT)
        try {
            memScoped {
                val rc = alloc<RECT>()
                for ((idx, item) in itemsList.withIndex()) {
                    val left = (idx * segW).toInt()
                    val right = ((idx + 1) * segW).toInt()
                    val isSelected = item.first == selectedId
                    if (isSelected) {
                        rc.left = left + 8
                        rc.top = 4
                        rc.right = (right - 8).coerceAtLeast(rc.left + 4)
                        rc.bottom = 7
                        val pillBrush = CreateSolidBrush(accentRef)
                        if (pillBrush != null) {
                            FillRect(hdc, rc.ptr, pillBrush)
                            DeleteObject(pillBrush)
                        }
                    }
                    rc.left = left + 4
                    rc.top = 6
                    rc.right = (right - 4).coerceAtLeast(rc.left + 4)
                    rc.bottom = h - 4
                    SetTextColor(hdc, if (isSelected) accentRef else normalTextRef)
                    DrawTextW(hdc, item.second, -1, rc.ptr, (DT_CENTER or DT_VCENTER or DT_SINGLELINE or DT_END_ELLIPSIS or DT_NOPREFIX).toUInt())
                }
            }
        } finally {
            SetBkMode(hdc, prevBk)
            if (oldFont != null) SelectObject(hdc, oldFont)
        }
    }

    override fun setItems(items: List<Triple<String, String, String?>>) {
        super.setItems(items)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun setSelectedItemId(itemId: String) {
        super.setSelectedItemId(itemId)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        Size((maxWidth?.toInt() ?: 360).coerceAtLeast(180), 52)

    override fun onNativeBoundsChanged(rect: Rect) {
        Win32RetainedControlSupport.onLeafBoundsChanged(nativeWidget, rect)
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
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
