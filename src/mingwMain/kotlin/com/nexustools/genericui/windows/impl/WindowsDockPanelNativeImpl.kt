@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockPanelNativeImpl
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import com.nexustools.genericui.windows.Win32StyledWidgetHelper
import com.nexustools.genericui.windows.WindowsApplicationNativeImpl
import platform.windows.InvalidateRect
import platform.windows.IsWindow
import platform.windows.SetWindowTextW

/**
 * Retained Win32 dockable panel (`"GenericUISemanticSurfaceW"` when docked, transitioning
 * seamlessly to/from a floating `WS_EX_TOOLWINDOW` host `HWND` via [Win32NativeWindowHost]).
 */
class WindowsDockPanelNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUISemanticSurfaceW")
) : DesktopDockPanelNativeImpl<Win32HwndHandle>(widget) {

    init {
        layoutController = Win32RetainedControlSupport.initContainer(
            container = this,
            handle = nativeWidget,
            win32ClassName = "GenericUISemanticSurfaceW",
            defaultDisplay = CssDisplay.FLEX,
            defaultFlexDirection = FlexDirection.COLUMN,
            defaultGapPx = 4,
            defaultPadding = EdgeInsets(top = 6, right = 8, bottom = 8, left = 8)
        )
        nativeWidget.customPaintCallback = { hwnd, hdc, w, h ->
            val dark = Win32NativeWindowHost.isEffectivelyDarkTheme()
            Win32StyledWidgetHelper.renderStyledSpecToHdc(
                hwnd = hwnd,
                hdc = hdc,
                width = w,
                height = h,
                spec = StyledVisualSpec(
                    backgroundColor = if (dark) RgbaColor(36, 39, 46) else RgbaColor(250, 251, 253),
                    borderColor = if (dark) RgbaColor(64, 68, 76) else RgbaColor(220, 224, 230),
                    borderWidthPx = 1f,
                    borderRadiusPx = 4f
                ),
                isDark = dark
            )
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    override fun setTitle(title: String) {
        super.setTitle(title)
        val hwnd = nativeWidget.hwnd
        if (floatingState && hwnd != null && IsWindow(hwnd) != 0) {
            SetWindowTextW(hwnd, title)
        }
    }

    override fun setFloating(floating: Boolean) {
        if (floatingState == floating) return
        // Detach child HWNDs cleanly before switching between docked child HWND and top-level tool HWND
        val children = getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
        for (child in children) {
            (child.nativeWidget as? Win32HwndHandle)?.let { Win32ControlMessageRouter.detachChildHwnd(it) }
        }
        if (floating) {
            Win32ControlMessageRouter.destroyHandle(nativeWidget)
            super.setFloating(true)
            if (WindowsApplicationNativeImpl.interactiveWin32GuiEnabled) {
                Win32NativeWindowHost.showDockPanelWindow(this)
            }
        } else {
            Win32NativeWindowHost.closeDockPanelWindow(this)
            super.setFloating(false)
            Win32ControlMessageRouter.createChildHwnd(
                handle = nativeWidget,
                owner = this,
                win32ClassName = "GenericUISemanticSurfaceW",
                dwStyle = Win32HwndHandle.WS_CHILD_STYLE or Win32HwndHandle.WS_VISIBLE_STYLE or
                    Win32HwndHandle.WS_CLIPCHILDREN_STYLE or Win32HwndHandle.WS_CLIPSIBLINGS_STYLE,
                installSubclass = false
            )
            nativeWidget.hwnd?.let { hostHwnd ->
                for (child in children) {
                    (child.nativeWidget as? Win32HwndHandle)?.let { Win32ControlMessageRouter.attachChildHwnd(it, hostHwnd) }
                }
            }
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        Size(240, if (collapsedState) 28 else 220)

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        Win32RetainedControlSupport.onContainerChildAdded(this, nativeWidget, childImpl)
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        Win32RetainedControlSupport.onContainerChildRemoved(this, nativeWidget, childImpl)
    }

    override fun onChildrenClearedNative() {
        Win32RetainedControlSupport.onContainerChildrenCleared(nativeWidget, childImpls.toList())
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!floatingState) {
            Win32RetainedControlSupport.onContainerBoundsChanged(this, nativeWidget, rect)
        } else if (rect.width > 0 && rect.height > 0) {
            layoutChildrenInBounds(rect.width.toFloat(), rect.height.toFloat())
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
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun dispose() {
        Win32NativeWindowHost.closeDockPanelWindow(this)
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
