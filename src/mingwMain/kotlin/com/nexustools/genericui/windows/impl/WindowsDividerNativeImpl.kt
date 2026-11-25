@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopDividerNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import com.nexustools.genericui.windows.Win32StyledWidgetHelper
import platform.windows.HDC
import platform.windows.HWND
import platform.windows.InvalidateRect
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_VISIBLE

/**
 * Retained Win32 `"GenericUIDividerW"` rule divider supporting both horizontal and vertical layouts.
 */
class WindowsDividerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIDividerW")
) : DesktopDividerNativeImpl<Win32HwndHandle>(widget) {

    init {
        nativeWidget.attributes["nativeImpl"] = this
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "GenericUIDividerW",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_CLIPSIBLINGS,
            initialWidth = 100,
            initialHeight = 1,
            installSubclass = false
        )
        nativeWidget.customPaintCallback = { hwnd, hdc, w, h -> paintDividerLine(hwnd, hdc, w, h) }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
    }

    private fun paintDividerLine(hwnd: HWND, hdc: HDC, width: Int, height: Int) {
        val isDark = Win32NativeWindowHost.isEffectivelyDarkTheme()
        val pixels = IntArray(width * height)
        Win32StyledWidgetHelper.captureBackdropIntoBuffer(hwnd, 0, 0, width, height, pixels, isDark)
        val lineCol = getEffectiveBackgroundColor()
            ?: resolveEffectiveStyle()?.borderColor
            ?: if (isDark) RgbaColor(68, 72, 82) else RgbaColor(214, 218, 226)
        val word = (0xFF shl 24) or (lineCol.r shl 16) or (lineCol.g shl 8) or lineCol.b
        val vertical = height > width * 2
        if (vertical) {
            val midX = (width / 2).coerceIn(0, width - 1)
            for (py in 0 until height) {
                pixels[py * width + midX] = word
            }
        } else {
            val midY = (height / 2).coerceIn(0, height - 1)
            val rowOff = midY * width
            for (px in 0 until width) {
                pixels[rowOff + px] = word
            }
        }
        Win32StyledWidgetHelper.blitArgbArrayToHdc(hdc, width, height, pixels, premultiplied = false)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size = Size(1, 1)

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
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
