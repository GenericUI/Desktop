@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.windows.DT_CALCRECT
import platform.windows.DT_NOPREFIX
import platform.windows.DT_SINGLELINE
import platform.windows.DT_WORDBREAK
import platform.windows.DrawTextW
import platform.windows.GetDC
import platform.windows.GetWindowTextLengthW
import platform.windows.GetWindowTextW
import platform.windows.HFONT
import platform.windows.HWND
import platform.windows.InvalidateRect
import platform.windows.IsWindow
import platform.windows.RECT
import platform.windows.ReleaseDC
import platform.windows.SelectObject
import platform.windows.SetWindowTextW
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPCHILDREN
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_VISIBLE
import kotlin.math.ceil

/**
 * Shared retained child `HWND` lifecycle, layout controller, text measurement, and state-sync
 * helper used uniformly by all `windows/impl/Windows*.kt` widgets (preventing hook duplication).
 */
object Win32RetainedControlSupport {

    fun initContainer(
        container: DesktopBaseContainerNativeImpl<*>,
        handle: Win32HwndHandle,
        win32ClassName: String,
        dwStyle: Int = WS_CHILD or WS_VISIBLE or WS_CLIPCHILDREN or WS_CLIPSIBLINGS,
        defaultDisplay: CssDisplay? = null,
        defaultFlexDirection: FlexDirection? = null,
        defaultGapPx: Int = 0,
        defaultPadding: EdgeInsets = EdgeInsets.ZERO,
        isLayeredHost: Boolean = false
    ): DesktopContainerLayoutController {
        handle.attributes["nativeImpl"] = container
        Win32ControlMessageRouter.createChildHwnd(
            handle = handle,
            owner = container,
            win32ClassName = win32ClassName,
            dwStyle = dwStyle,
            installSubclass = false
        )
        return DesktopContainerLayoutController(
            host = container,
            defaultDisplay = defaultDisplay,
            defaultFlexDirection = defaultFlexDirection,
            defaultGapPx = defaultGapPx,
            defaultPadding = defaultPadding,
            isLayeredHost = isLayeredHost
        ).also { ctrl ->
            ctrl.onZOrderUpdated = { sorted ->
                handle.hwnd?.let { parentHwnd ->
                    Win32ControlMessageRouter.layoutChildrenBatch(parentHwnd, sorted)
                }
            }
        }
    }

    fun onContainerChildAdded(
        container: DesktopBaseContainerNativeImpl<*>,
        handle: Win32HwndHandle,
        childImpl: NativeImpl<*>,
        ensureStageUpgrade: Boolean = false
    ) {
        val parentHwnd = handle.hwnd ?: return
        val base = childImpl as? DesktopBaseNativeImpl<*> ?: return
        val childHandle = base.nativeWidget as? Win32HwndHandle ?: return

        if (ensureStageUpgrade &&
            handle.actualWin32ClassName != "GenericUILayeredStageHostW" &&
            Win32LayeredStageHostHwnd.isLayeredStageContainer(container)
        ) {
            Win32ControlMessageRouter.replaceChildHwndInPlace(
                handle = handle,
                owner = container,
                newWin32ClassName = "GenericUILayeredStageHostW",
                newDwStyle = WS_CHILD or WS_VISIBLE or WS_CLIPCHILDREN or WS_CLIPSIBLINGS,
                installSubclass = false
            )
            Win32LayeredStageHostHwnd.registerStageHost(handle, container)
        }

        val currentHostHwnd = handle.hwnd ?: parentHwnd
        Win32InteractiveSurfaceHwnd.ensureSurfaceChildHwnd(childHandle, base)
        Win32ControlMessageRouter.attachChildHwnd(childHandle, currentHostHwnd)
        if (handle.lastAppliedW > 0 && handle.lastAppliedH > 0) {
            container.layoutChildrenInBounds(handle.lastAppliedW.toFloat(), handle.lastAppliedH.toFloat())
        }
        InvalidateRect(currentHostHwnd, null, 0)
    }

    fun onContainerChildRemoved(
        container: DesktopBaseContainerNativeImpl<*>,
        handle: Win32HwndHandle,
        childImpl: NativeImpl<*>
    ) {
        val childHandle = childImpl.nativeWidget as? Win32HwndHandle
        if (childHandle != null) {
            Win32ControlMessageRouter.detachChildHwnd(childHandle)
        }
        if (handle.lastAppliedW > 0 && handle.lastAppliedH > 0) {
            container.layoutChildrenInBounds(handle.lastAppliedW.toFloat(), handle.lastAppliedH.toFloat())
        }
        handle.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    fun onContainerChildrenCleared(
        handle: Win32HwndHandle,
        clearedChildren: List<NativeImpl<*>>
    ) {
        for (child in clearedChildren) {
            val childHandle = child.nativeWidget as? Win32HwndHandle ?: continue
            Win32ControlMessageRouter.detachChildHwnd(childHandle)
        }
        handle.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    fun onContainerBoundsChanged(
        container: DesktopBaseContainerNativeImpl<*>,
        handle: Win32HwndHandle,
        rect: Rect
    ) {
        Win32ControlMessageRouter.applyBounds(handle, rect)
        if (rect.width > 0 && rect.height > 0) {
            container.layoutChildrenInBounds(rect.width.toFloat(), rect.height.toFloat())
        }
    }

    fun onLeafBoundsChanged(handle: Win32HwndHandle, rect: Rect) {
        Win32ControlMessageRouter.applyBounds(handle, rect)
    }

    fun onVisibilityChanged(handle: Win32HwndHandle, visible: Boolean) {
        Win32ControlMessageRouter.applyVisibility(handle, visible)
    }

    fun onEnabledChanged(handle: Win32HwndHandle, enabled: Boolean) {
        Win32ControlMessageRouter.applyEnabled(handle, enabled)
    }

    fun onOpacityChanged(handle: Win32HwndHandle, opacity: Float) {
        Win32ControlMessageRouter.applyOpacity(handle, opacity)
    }

    fun onTooltipChanged(handle: Win32HwndHandle, tooltip: String?) {
        Win32ControlMessageRouter.applyTooltip(handle, tooltip)
    }

    fun onCursorChanged(handle: Win32HwndHandle, cursor: CssCursorValue?) {
        Win32ControlMessageRouter.applyCursor(handle, cursor)
    }

    fun onPointerEventsChanged(handle: Win32HwndHandle, pointerEventsNone: Boolean) {
        Win32ControlMessageRouter.applyPointerEventsNone(handle, pointerEventsNone)
    }

    fun onZIndexChanged(handle: Win32HwndHandle, zIndex: Int) {
        Win32ControlMessageRouter.applyZIndex(handle, zIndex)
    }

    fun onStyleSynced(
        owner: DesktopBaseNativeImpl<*>,
        handle: Win32HwndHandle,
        defaultTransparentBg: Boolean = true
    ) {
        if (handle.hwnd == null) return
        Win32ControlMessageRouter.syncTypographyAndColors(handle, owner, defaultTransparentBg)
    }

    fun onDispose(handle: Win32HwndHandle) {
        Win32InteractiveSurfaceHwnd.releaseSurfaceResources(handle)
        handle.hwnd?.let { Win32LayeredStageHostHwnd.unregisterStageHost(it) }
        Win32ControlMessageRouter.destroyHandle(handle)
    }

    fun setWindowTextIfChanged(hwnd: HWND?, text: String) {
        if (hwnd == null || IsWindow(hwnd) == 0) return
        if (readWindowText(hwnd) != text) {
            SetWindowTextW(hwnd, text)
        }
    }

    fun readWindowText(hwnd: HWND?): String {
        if (hwnd == null || IsWindow(hwnd) == 0) return ""
        val len = GetWindowTextLengthW(hwnd)
        if (len <= 0) return ""
        return memScoped {
            val buf = allocArray<UShortVar>(len + 2)
            GetWindowTextW(hwnd, buf, len + 1)
            buf.toKString()
        }
    }

    fun measureTextSize(
        text: String,
        fontHandle: HFONT?,
        maxWidthPx: Float? = null,
        extraW: Int = 0,
        extraH: Int = 0,
        minW: Int = 0,
        minH: Int = 0
    ): Size {
        if (text.isEmpty()) return Size(minW.coerceAtLeast(extraW), minH.coerceAtLeast(extraH))
        val hdc = GetDC(null) ?: return Size(
            (text.length * 8 + extraW).coerceAtLeast(minW),
            (20 + extraH).coerceAtLeast(minH)
        )
        return try {
            val oldFont = if (fontHandle != null) SelectObject(hdc, fontHandle) else null
            try {
                memScoped {
                    val rc = alloc<RECT>()
                    rc.left = 0
                    rc.top = 0
                    val maxContentW = if (maxWidthPx != null && maxWidthPx > extraW) {
                        ceil(maxWidthPx - extraW).toInt().coerceAtLeast(16)
                    } else {
                        0
                    }
                    rc.right = maxContentW
                    rc.bottom = 0
                    val flags = if (maxContentW > 0) {
                        DT_CALCRECT or DT_WORDBREAK or DT_NOPREFIX
                    } else {
                        DT_CALCRECT or DT_SINGLELINE or DT_NOPREFIX
                    }
                    DrawTextW(hdc, text, -1, rc.ptr, flags.toUInt())
                    Size(
                        (rc.right - rc.left + extraW).coerceAtLeast(minW),
                        (rc.bottom - rc.top + extraH).coerceAtLeast(minH)
                    )
                }
            } finally {
                if (oldFont != null) SelectObject(hdc, oldFont)
            }
        } finally {
            ReleaseDC(null, hdc)
        }
    }
}
