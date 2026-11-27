@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.widget.DesktopNavbarNativeImpl
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
import platform.windows.InvalidateRect

/**
 * Retained Win32 toolbar / navbar host (`"GenericUINavbarW"`) mounting navigation links and
 * controls as genuine child `HWND`s with optional leading brand title rendering.
 */
class WindowsToolBarNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUINavbarW")
) : DesktopNavbarNativeImpl<Win32HwndHandle>(widget) {

    init {
        layoutController = Win32RetainedControlSupport.initContainer(
            container = this,
            handle = nativeWidget,
            win32ClassName = "GenericUINavbarW",
            defaultDisplay = CssDisplay.FLEX,
            defaultFlexDirection = FlexDirection.ROW,
            defaultGapPx = 8,
            defaultPadding = EdgeInsets(top = 6, right = 12, bottom = 6, left = 12)
        )
        nativeWidget.customPaintCallback = { hwnd, hdc, w, h ->
            val dark = Win32NativeWindowHost.isEffectivelyDarkTheme()
            val surface = Win32StyledWidgetHelper.resolveStyledSpecFromOwner(this)
            val fallbackBg = if (dark) RgbaColor(32, 35, 42) else RgbaColor(246, 248, 250)
            val fallbackFg = if (dark) RgbaColor(240, 242, 247) else RgbaColor(28, 32, 38)
            val brandLabel = if (isBrandVisible() && brandTitleText.isNotBlank()) brandTitleText else null
            Win32StyledWidgetHelper.renderStyledSpecToHdc(
                hwnd = hwnd,
                hdc = hdc,
                width = w,
                height = h,
                spec = StyledVisualSpec(
                    backgroundColor = surface.backgroundColor ?: fallbackBg,
                    textColor = surface.textColor ?: fallbackFg,
                    borderColor = surface.borderColor ?: if (dark) RgbaColor(58, 62, 70) else RgbaColor(218, 222, 228),
                    borderWidthPx = surface.borderWidthPx ?: 1f,
                    borderRadiusPx = surface.borderRadiusPx ?: 0f,
                    backdropBlurPx = surface.backdropBlurPx,
                    backdropFilter = surface.backdropFilter,
                    backgroundGradient = surface.backgroundGradient
                ),
                isDark = dark,
                fontHandle = nativeWidget.fontHandle,
                text = brandLabel,
                textColorOverride = surface.textColor ?: fallbackFg,
                alignLeft = true
            )
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val w = (maxWidth?.toInt() ?: 480).coerceAtLeast(180)
        return Size(w, 44)
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        Win32RetainedControlSupport.onContainerChildAdded(this, nativeWidget, childImpl)
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        Win32RetainedControlSupport.onContainerChildRemoved(this, nativeWidget, childImpl)
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
