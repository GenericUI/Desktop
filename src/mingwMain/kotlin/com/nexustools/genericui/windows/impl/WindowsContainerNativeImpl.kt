@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32LayeredStageHostHwnd
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import platform.windows.InvalidateRect
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPCHILDREN
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_VISIBLE

/**
 * Retained Win32 container implementation (`"GenericUIContainerW"`, `"GenericUISemanticSurfaceW"`,
 * or `"GenericUILayeredStageHostW"` when hosting a layered interactive/video stage + overlays).
 */
class WindowsContainerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIContainerW"),
    classPrefix: String = "container",
    tagName: String = "div"
) : DesktopContainerNativeImpl<Win32HwndHandle>(widget, classPrefix, tagName) {

    init {
        val initialClass = resolveTargetWin32Class()
        layoutController = Win32RetainedControlSupport.initContainer(
            container = this,
            handle = nativeWidget,
            win32ClassName = initialClass
        )
        if (initialClass == "GenericUILayeredStageHostW") {
            Win32LayeredStageHostHwnd.registerStageHost(nativeWidget, this)
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
    }

    private fun resolveTargetWin32Class(): String = when {
        Win32LayeredStageHostHwnd.isLayeredStageContainer(this) -> "GenericUILayeredStageHostW"
        resolveSemanticSurfaceSpec(Win32NativeWindowHost.isEffectivelyDarkTheme()) != null ||
            getEffectiveBackgroundColor() != null ||
            getEffectiveBackgroundGradient() != null ||
            (resolveEffectiveStyle()?.borderWidthPx ?: 0f) > 0f -> "GenericUISemanticSurfaceW"
        else -> "GenericUIContainerW"
    }

    private fun syncContainerWindowClassIfNeeded() {
        if (nativeWidget.hwnd == null) return
        val desiredClass = resolveTargetWin32Class()
        if (nativeWidget.actualWin32ClassName == desiredClass) return
        if (nativeWidget.actualWin32ClassName == "GenericUILayeredStageHostW") {
            Win32LayeredStageHostHwnd.unregisterStageHost(nativeWidget.hwnd)
        }
        Win32ControlMessageRouter.replaceChildHwndInPlace(
            handle = nativeWidget,
            owner = this,
            newWin32ClassName = desiredClass,
            newDwStyle = WS_CHILD or WS_VISIBLE or WS_CLIPCHILDREN or WS_CLIPSIBLINGS,
            installSubclass = false
        )
        if (desiredClass == "GenericUILayeredStageHostW") {
            Win32LayeredStageHostHwnd.registerStageHost(nativeWidget, this)
        }
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        Win32RetainedControlSupport.onContainerChildAdded(
            container = this,
            handle = nativeWidget,
            childImpl = childImpl,
            ensureStageUpgrade = true
        )
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        Win32RetainedControlSupport.onContainerChildRemoved(this, nativeWidget, childImpl)
    }

    override fun onChildrenClearedNative() {
        Win32RetainedControlSupport.onContainerChildrenCleared(nativeWidget, childImpls.toList())
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
        syncContainerWindowClassIfNeeded()
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
