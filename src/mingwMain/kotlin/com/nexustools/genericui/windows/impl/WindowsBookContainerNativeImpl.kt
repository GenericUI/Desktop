@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.widget.DesktopBookContainerNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import platform.windows.InvalidateRect

/**
 * Retained Win32 child `HWND` implementation (`"GenericUIBookContainerW"`) for paginated `BookContainer` documents.
 */
class WindowsBookContainerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIBookContainerW")
) : DesktopBookContainerNativeImpl<Win32HwndHandle>(widget) {

    init {
        layoutController = Win32RetainedControlSupport.initContainer(
            container = this,
            handle = nativeWidget,
            win32ClassName = "GenericUIBookContainerW"
        )
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
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
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
