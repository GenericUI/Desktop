@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopStyledComponentNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import platform.windows.InvalidateRect
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_VISIBLE

/**
 * Retained Win32 CSS-styled visual leaf component (`"GenericUIStyledComponentW"`).
 */
class WindowsStyledComponentNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIStyledComponentW"),
    classPrefix: String = "styled-component",
    tagName: String = "genericui:styled-component"
) : DesktopStyledComponentNativeImpl<Win32HwndHandle>(widget, classPrefix, tagName) {

    init {
        nativeWidget.attributes["nativeImpl"] = this
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "GenericUIStyledComponentW",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_CLIPSIBLINGS,
            installSubclass = false
        )
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
    }

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

    override fun setAttribute(name: String, value: String?) {
        super.setAttribute(name, value)
        Win32RetainedControlSupport.handleStyledAttributeUpdate(nativeWidget, name)
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
