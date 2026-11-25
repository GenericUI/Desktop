@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopTabNativeImpl
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
import platform.windows.InvalidateRect
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE

/**
 * Retained Win32 implementation for individual `Tab` items (`"GenericUITabItemW"` when standalone,
 * synced natively to parent `SysTabControl32` `TCITEMW` entries when hosted in [WindowsTabContainerNativeImpl]).
 */
class WindowsTabNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUITabItemW"),
    closable: Boolean = false
) : DesktopTabNativeImpl<Win32HwndHandle>(widget, closable) {

    internal var parentTabContainer: WindowsTabContainerNativeImpl? = null

    init {
        nativeWidget.attributes["nativeImpl"] = this
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "GenericUITabItemW",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS,
            initialText = titleVal,
            initialWidth = 100,
            initialHeight = 32,
            installSubclass = false
        )
        nativeWidget.customPaintCallback = { hwnd, hdc, w, h ->
            val dark = Win32NativeWindowHost.isEffectivelyDarkTheme()
            val bg = if (selectedVal) {
                if (dark) RgbaColor(54, 58, 66) else RgbaColor(255, 255, 255)
            } else {
                if (dark) RgbaColor(36, 39, 45) else RgbaColor(238, 240, 244)
            }
            val fg = if (selectedVal) {
                if (dark) RgbaColor(242, 245, 250) else RgbaColor(25, 28, 34)
            } else {
                if (dark) RgbaColor(180, 185, 195) else RgbaColor(88, 94, 104)
            }
            val border = if (selectedVal) RgbaColor(13, 110, 253) else RgbaColor(180, 185, 194, 128)
            val label = buildDisplayTitle()
            Win32StyledWidgetHelper.renderStyledSpecToHdc(
                hwnd = hwnd,
                hdc = hdc,
                width = w,
                height = h,
                spec = StyledVisualSpec(
                    backgroundColor = bg,
                    textColor = fg,
                    borderColor = border,
                    borderWidthPx = 1f,
                    borderRadiusPx = 6f
                ),
                isDark = dark,
                focused = isFocusedState(),
                fontHandle = nativeWidget.fontHandle,
                text = label,
                textColorOverride = fg
            )
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    internal fun buildDisplayTitle(): String {
        val dirtySuffix = if (dirtyVal) " \u2022" else ""
        val badgeSuffix = badgeTextVal?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
        return "$titleVal$dirtySuffix$badgeSuffix"
    }

    private fun notifyTabChanged() {
        parentTabContainer?.syncTabItemFromChild(this)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun setTitleNative(title: String) {
        super.setTitleNative(title)
        Win32RetainedControlSupport.setWindowTextIfChanged(nativeWidget.hwnd, title)
        notifyTabChanged()
    }

    override fun setIconNative(icon: String?) {
        super.setIconNative(icon)
        notifyTabChanged()
    }

    override fun setTooltipNative(tooltip: String?) {
        super.setTooltipNative(tooltip)
        Win32RetainedControlSupport.onTooltipChanged(nativeWidget, tooltip)
        notifyTabChanged()
    }

    override fun setClosableNative(closable: Boolean) {
        super.setClosableNative(closable)
        notifyTabChanged()
    }

    override fun setDirtyNative(dirty: Boolean) {
        super.setDirtyNative(dirty)
        notifyTabChanged()
    }

    override fun setBadgeNative(text: String?, variantCss: String?) {
        super.setBadgeNative(text, variantCss)
        notifyTabChanged()
    }

    override fun setSelectedNative(selected: Boolean) {
        super.setSelectedNative(selected)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        Win32RetainedControlSupport.measureTextSize(
            text = buildDisplayTitle().ifBlank { "Tab" },
            fontHandle = nativeWidget.fontHandle,
            maxWidthPx = null,
            extraW = if (closableVal) 36 else 24,
            extraH = 12,
            minW = 56,
            minH = 30
        )

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
        parentTabContainer = null
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
