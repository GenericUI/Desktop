@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.css.CssBorderStyle
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
 * Retained Win32 container for `FileDropZone` (`"GenericUIFileDropZoneW"`), painting a dashed
 * rounded drop-target surface while mounting child labels and browse action buttons cleanly.
 */
class WindowsFilePickerDropZoneNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIFileDropZoneW"),
    classPrefix: String = "file-drop-zone"
) : DesktopContainerNativeImpl<Win32HwndHandle>(widget, classPrefix) {

    init {
        layoutController = Win32RetainedControlSupport.initContainer(
            container = this,
            handle = nativeWidget,
            win32ClassName = "GenericUIFileDropZoneW",
            defaultDisplay = CssDisplay.FLEX,
            defaultFlexDirection = FlexDirection.COLUMN,
            defaultGapPx = 6,
            defaultPadding = EdgeInsets(top = 14, right = 16, bottom = 14, left = 16)
        )
        nativeWidget.customPaintCallback = { hwnd, hdc, w, h ->
            val dark = Win32NativeWindowHost.isEffectivelyDarkTheme()
            val surface = Win32StyledWidgetHelper.resolveStyledSpecFromOwner(this)
            val fallbackBg = if (dark) RgbaColor(36, 40, 48) else RgbaColor(248, 250, 252)
            val fallbackBorder = if (dark) RgbaColor(90, 102, 120) else RgbaColor(173, 181, 189)
            val fallbackFg = if (dark) RgbaColor(200, 206, 216) else RgbaColor(73, 80, 87)
            val prompt = if (childImpls.isEmpty()) {
                (nativeWidget.attributes["placeholder"] as? String)
                    ?: (nativeWidget.attributes["label"] as? String)
                    ?: "Drop files here or click to browse"
            } else {
                null
            }
            Win32StyledWidgetHelper.renderStyledSpecToHdc(
                hwnd = hwnd,
                hdc = hdc,
                width = w,
                height = h,
                spec = StyledVisualSpec(
                    backgroundColor = surface.backgroundColor ?: fallbackBg,
                    textColor = surface.textColor ?: fallbackFg,
                    borderColor = surface.borderColor ?: fallbackBorder,
                    borderWidthPx = surface.borderWidthPx ?: 2f,
                    borderStyle = surface.borderStyle ?: CssBorderStyle.DASHED,
                    borderRadiusPx = surface.borderRadiusPx ?: 8f
                ),
                isDark = dark,
                fontHandle = nativeWidget.fontHandle,
                text = prompt,
                textColorOverride = surface.textColor ?: fallbackFg
            )
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        Size((maxWidth?.toInt() ?: 320).coerceAtLeast(180), 96)

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
