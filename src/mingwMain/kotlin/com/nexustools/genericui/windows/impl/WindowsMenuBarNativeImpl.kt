@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuBarNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopMenuNativeImpl
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import com.nexustools.genericui.windows.Win32StyledWidgetHelper
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.ClientToScreen
import platform.windows.CreatePopupMenu
import platform.windows.DT_CENTER
import platform.windows.DT_NOPREFIX
import platform.windows.DT_SINGLELINE
import platform.windows.DT_VCENTER
import platform.windows.DestroyMenu
import platform.windows.DrawTextW
import platform.windows.InvalidateRect
import platform.windows.POINT
import platform.windows.RECT
import platform.windows.SelectObject
import platform.windows.SetBkMode
import platform.windows.SetTextColor
import platform.windows.TPM_LEFTALIGN
import platform.windows.TPM_NONOTIFY
import platform.windows.TPM_RETURNCMD
import platform.windows.TPM_TOPALIGN
import platform.windows.TRANSPARENT
import platform.windows.TrackPopupMenuEx
import platform.windows.WM_LBUTTONUP

/**
 * Retained Win32 `MenuBar` strip (`"GenericUIMenuBarW"`) syncing to native `HMENU` bars on standard
 * top-level windows and opening real `TrackPopupMenuEx` submenus when hosted inline or in titlebars.
 */
class WindowsMenuBarNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIMenuBarW")
) : DesktopMenuBarNativeImpl<Win32HwndHandle>(widget) {

    private var itemRects: List<Pair<Rect, DesktopMenuNativeImpl<*>>> = emptyList()

    init {
        layoutController = Win32RetainedControlSupport.initContainer(
            container = this,
            handle = nativeWidget,
            win32ClassName = "GenericUIMenuBarW",
            defaultDisplay = CssDisplay.FLEX,
            defaultFlexDirection = FlexDirection.ROW,
            defaultGapPx = 4,
            defaultPadding = EdgeInsets(top = 2, right = 6, bottom = 2, left = 6)
        )
        nativeWidget.customPaintCallback = { hwnd, hdc, w, h ->
            paintMenuBarStrip(hwnd, hdc, w, h)
        }
        nativeWidget.customMessageCallback = { hwnd, msg, _, lParam ->
            if (msg.toInt() == WM_LBUTTONUP) {
                val x = (lParam and 0xFFFFL).toShort().toInt()
                val y = ((lParam shr 16) and 0xFFFFL).toShort().toInt()
                val hit = itemRects.firstOrNull { (r, _) -> r.contains(x, y) }
                if (hit != null) {
                    openTopMenuPopup(hwnd, hit.first, hit.second)
                    0L
                } else {
                    null
                }
            } else {
                null
            }
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    private fun computeMenuStripRects(height: Int): List<Pair<Rect, DesktopMenuNativeImpl<*>>> {
        val out = ArrayList<Pair<Rect, DesktopMenuNativeImpl<*>>>()
        var curX = 6
        for (child in childImpls) {
            val menu = child as? DesktopMenuNativeImpl<*> ?: continue
            val textSize = Win32RetainedControlSupport.measureTextSize(
                text = menu.getLabel().ifBlank { "Menu" },
                fontHandle = nativeWidget.fontHandle,
                extraW = 16,
                extraH = 4,
                minW = 40,
                minH = (height - 4).coerceAtLeast(22)
            )
            out.add(Rect(curX, 2, textSize.width, (height - 4).coerceAtLeast(22)) to menu)
            curX += textSize.width + 2
        }
        return out
    }

    private fun paintMenuBarStrip(hwnd: platform.windows.HWND, hdc: platform.windows.HDC, w: Int, h: Int) {
        val dark = Win32NativeWindowHost.isEffectivelyDarkTheme()
        val bg = if (dark) RgbaColor(32, 34, 40) else RgbaColor(246, 248, 250)
        Win32StyledWidgetHelper.renderStyledSpecToHdc(
            hwnd = hwnd,
            hdc = hdc,
            width = w,
            height = h,
            spec = StyledVisualSpec(backgroundColor = bg),
            isDark = dark
        )
        val computed = computeMenuStripRects(h)
        itemRects = computed
        if (computed.isEmpty()) return

        val oldFont = nativeWidget.fontHandle?.let { SelectObject(hdc, it) }
        val prevBk = SetBkMode(hdc, TRANSPARENT)
        SetTextColor(hdc, if (dark) 0x00F0F2F6u else 0x00242830u)
        try {
            memScoped {
                val rc = alloc<RECT>()
                for ((rect, menu) in computed) {
                    rc.left = rect.x
                    rc.top = rect.y
                    rc.right = rect.x + rect.width
                    rc.bottom = rect.y + rect.height
                    DrawTextW(hdc, menu.getLabel(), -1, rc.ptr, (DT_CENTER or DT_VCENTER or DT_SINGLELINE or DT_NOPREFIX).toUInt())
                }
            }
        } finally {
            SetBkMode(hdc, prevBk)
            if (oldFont != null) SelectObject(hdc, oldFont)
        }
    }

    private fun openTopMenuPopup(hwnd: platform.windows.HWND, rect: Rect, menu: DesktopMenuNativeImpl<*>) {
        val hMenu = CreatePopupMenu() ?: return
        val cmdHandlers = HashMap<Int, () -> Unit>()
        try {
            Win32NativeWindowHost.populateWin32PopupMenu(hMenu, menu.nativeWidget, cmdHandlers)
            val screenPt = memScoped {
                val pt = alloc<POINT>()
                pt.x = rect.x
                pt.y = rect.y + rect.height
                ClientToScreen(hwnd, pt.ptr)
                pt.x to pt.y
            }
            val cmd = TrackPopupMenuEx(
                hMenu,
                (TPM_LEFTALIGN or TPM_TOPALIGN or TPM_RETURNCMD or TPM_NONOTIFY).toUInt(),
                screenPt.first,
                screenPt.second,
                hwnd,
                null
            )
            if (cmd > 0) {
                cmdHandlers[cmd]?.invoke()
            }
        } finally {
            DestroyMenu(hMenu)
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val rects = computeMenuStripRects(30)
        val totalW = rects.lastOrNull()?.let { it.first.x + it.first.width + 6 } ?: 240
        return Size(totalW.coerceAtLeast(120), 30)
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        Win32RetainedControlSupport.onContainerChildAdded(this, nativeWidget, childImpl)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        Win32RetainedControlSupport.onContainerChildRemoved(this, nativeWidget, childImpl)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun onChildrenClearedNative(clearedChildren: List<NativeImpl<*>>) {
        Win32RetainedControlSupport.onContainerChildrenCleared(nativeWidget, clearedChildren)
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
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
