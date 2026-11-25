@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopStyledButtonNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import platform.windows.InvalidateRect
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE

/**
 * Retained Win32 CSS-styled button (`"GenericUIStyledButtonW"`).
 */
class WindowsStyledButtonNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIStyledButtonW"),
    classPrefix: String = "styled-btn"
) : DesktopStyledButtonNativeImpl<Win32HwndHandle>(widget, classPrefix) {

    init {
        nativeWidget.attributes["nativeImpl"] = this
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "GenericUIStyledButtonW",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS,
            initialText = computeDisplayLabel(),
            initialWidth = 96,
            initialHeight = 32,
            installSubclass = false
        )
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    private fun computeDisplayLabel(): String {
        val text = getText()
        val icon = getIcon()
        return when {
            !icon.isNullOrBlank() && text.isBlank() -> icon
            !icon.isNullOrBlank() && icon.length <= 2 -> "$icon $text"
            else -> text
        }
    }

    override fun setText(text: String) {
        super.setText(text)
        Win32RetainedControlSupport.setWindowTextIfChanged(nativeWidget.hwnd, computeDisplayLabel())
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun setIcon(icon: String?) {
        super.setIcon(icon)
        Win32RetainedControlSupport.setWindowTextIfChanged(nativeWidget.hwnd, computeDisplayLabel())
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        Win32RetainedControlSupport.measureTextSize(
            text = computeDisplayLabel(),
            fontHandle = nativeWidget.fontHandle,
            maxWidthPx = maxWidth,
            extraW = 28,
            extraH = 14,
            minW = 40,
            minH = 28
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
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
