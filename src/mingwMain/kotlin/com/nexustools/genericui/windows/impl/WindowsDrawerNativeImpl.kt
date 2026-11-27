@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
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
 * Retained Win32 panel container for `Drawer`, `Offcanvas`, `SideNav`, and `NavigationRail`
 * (`"GenericUIDrawerW"`), mounting child controls with elevated semantic surface chrome.
 */
class WindowsDrawerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIDrawerW"),
    classPrefix: String = "offcanvas"
) : DesktopContainerNativeImpl<Win32HwndHandle>(widget, classPrefix) {

    init {
        layoutController = Win32RetainedControlSupport.initContainer(
            container = this,
            handle = nativeWidget,
            win32ClassName = "GenericUIDrawerW",
            defaultDisplay = CssDisplay.FLEX,
            defaultFlexDirection = FlexDirection.COLUMN,
            defaultGapPx = 4,
            defaultPadding = EdgeInsets(top = 8, right = 8, bottom = 8, left = 8)
        )
        nativeWidget.customPaintCallback = { hwnd, hdc, w, h ->
            val dark = Win32NativeWindowHost.isEffectivelyDarkTheme()
            val resolved = Win32StyledWidgetHelper.resolveEffectiveVisualSpec(
                nativeWidget,
                this,
                "GenericUISemanticSurfaceW",
                dark
            )
            val fallbackBg = if (dark) RgbaColor(32, 35, 42) else RgbaColor(248, 249, 250)
            val fallbackBorder = if (dark) RgbaColor(62, 66, 75) else RgbaColor(222, 226, 230)
            Win32StyledWidgetHelper.renderStyledSpecToHdc(
                hwnd = hwnd,
                hdc = hdc,
                width = w,
                height = h,
                spec = StyledVisualSpec(
                    backgroundColor = resolved.backgroundColor ?: fallbackBg,
                    borderColor = resolved.borderColor ?: fallbackBorder,
                    borderWidthPx = resolved.borderWidthPx ?: 1f,
                    borderRadiusPx = resolved.borderRadiusPx ?: 0f,
                    backgroundGradient = resolved.backgroundGradient,
                    backdropBlurPx = resolved.backdropBlurPx,
                    backdropFilter = resolved.backdropFilter
                ),
                isDark = dark
            )
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val w = if ("navigation-rail" in nativeWidget.cachedAllClassTokens) 72 else 260
        return Size(w, (maxHeight?.toInt() ?: 400).coerceAtLeast(120))
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
