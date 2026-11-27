@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.style.css.CssCursorValue
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toLong
import platform.windows.DRAWITEMSTRUCT
import platform.windows.DeleteObject
import platform.windows.HBRUSH
import platform.windows.HDC
import platform.windows.HFONT
import platform.windows.HWND
import platform.windows.HWND__
import platform.windows.LPARAM
import platform.windows.LRESULT
import platform.windows.NMHDR
import platform.windows.UINT
import platform.windows.WPARAM

/**
 * Win32 handle wrapper backed by a live retained child/top-level [HWND] plus subclassed event,
 * typography, color, tooltip, Z-order, and custom painting bindings.
 */
class Win32HwndHandle(
    win32ClassName: String,
    var dwStyle: Long = 0L,
    var dwExStyle: Long = 0L,
    nativePointer: COpaquePointer? = null
) : DesktopNativeWidget("Win32", win32ClassName, nativePointer?.toLong() ?: nextPointerId()) {

    var hwnd: HWND? = nativePointer?.reinterpret<HWND__>()
        internal set

    var actualWin32ClassName: String = win32ClassName
        internal set

    var parentHwnd: HWND? = null
        internal set

    var controlId: Int = 0
        internal set

    var buddyHwnd: HWND? = null
        internal set

    var secondaryHwnd: HWND? = null
        internal set

    var secondaryHwnd2: HWND? = null
        internal set

    var tooltipHwnd: HWND? = null
        internal set

    var fontHandle: HFONT? = null
        internal set

    var ownsFontHandle: Boolean = false
        internal set

    var bgBrushHandle: HBRUSH? = null
        internal set

    var fgColorRef: UInt = 0x00000000u
        internal set

    var bgColorRef: UInt = 0x00FFFFFFu
        internal set

    var hasCustomColors: Boolean = false
        internal set

    var isTransparentCtlColor: Boolean = false
        internal set

    var effectiveVisible: Boolean = true
        internal set

    var effectiveEnabled: Boolean = true
        internal set

    var effectiveOpacity: Float = 1.0f
        internal set

    var pointerEventsNone: Boolean = false
        internal set

    var desiredZIndex: Int = 0
        internal set

    var isMouseTracking: Boolean = false
        internal set

    var isNativeEditTarget: Boolean = false
        internal set

    var hadTabStop: Boolean = false
        internal set

    override fun onEffectiveFocusableChanged(focusable: Boolean) {
        Win32ControlMessageRouter.syncFocusable(this)
    }

    var ownerImpl: DesktopBaseNativeImpl<*>? = null
        internal set

    var activeCursorValue: CssCursorValue? = null
        internal set

    var lastAppliedX: Int = Int.MIN_VALUE
        internal set

    var lastAppliedY: Int = Int.MIN_VALUE
        internal set

    var lastAppliedW: Int = -1
        internal set

    var lastAppliedH: Int = -1
        internal set

    internal var customPaintCallback: ((hwnd: HWND, hdc: HDC, width: Int, height: Int) -> Unit)? = null
    internal var customMessageCallback: ((hwnd: HWND, msg: UINT, wParam: WPARAM, lParam: LPARAM) -> LRESULT?)? = null
    internal var onCommandCallback: ((notifyCode: Int, wParam: WPARAM, lParam: LPARAM) -> Unit)? = null
    internal var onNotifyCallback: ((code: UInt, nmhdrPtr: kotlinx.cinterop.CPointer<NMHDR>) -> LRESULT?)? = null
    internal var onDrawItemCallback: ((disPtr: kotlinx.cinterop.CPointer<DRAWITEMSTRUCT>) -> LRESULT?)? = null
    internal var onScrollCallback: ((msg: UINT, wParam: WPARAM, lParam: LPARAM) -> Unit)? = null
    internal var onDpiChangedCallback: ((newDpi: Int) -> Unit)? = null

    fun releaseWin32GdiResources() {
        val brush = bgBrushHandle
        if (brush != null) {
            DeleteObject(brush)
            bgBrushHandle = null
        }
        if (ownsFontHandle) {
            val font = fontHandle
            if (font != null) {
                DeleteObject(font)
            }
            fontHandle = null
            ownsFontHandle = false
        }
    }

    companion object {
        const val WS_POPUP: Long = 0x80000000L
        const val WS_CHILD: Long = 0x40000000L
        const val WS_MINIMIZE: Long = 0x20000000L
        const val WS_VISIBLE: Long = 0x10000000L
        const val WS_DISABLED: Long = 0x08000000L
        const val WS_CLIPSIBLINGS: Long = 0x04000000L
        const val WS_CLIPCHILDREN: Long = 0x02000000L
        const val WS_MAXIMIZE: Long = 0x01000000L
        const val WS_CAPTION: Long = 0x00C00000L
        const val WS_BORDER: Long = 0x00800000L
        const val WS_SYSMENU: Long = 0x00080000L
        const val WS_THICKFRAME: Long = 0x00040000L
        const val WS_TABSTOP: Long = 0x00010000L
        const val WS_MINIMIZEBOX: Long = 0x00020000L
        const val WS_MAXIMIZEBOX: Long = 0x00010000L
        const val WS_OVERLAPPEDWINDOW: Long =
            0x00CF0000L // WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_THICKFRAME | WS_MINIMIZEBOX | WS_MAXIMIZEBOX

        const val WS_EX_DLGMODALFRAME: Long = 0x00000001L
        const val WS_EX_TOPMOST: Long = 0x00000008L
        const val WS_EX_TRANSPARENT: Long = 0x00000020L
        const val WS_EX_TOOLWINDOW: Long = 0x00000080L
        const val WS_EX_LAYERED: Long = 0x00080000L

        const val WS_CHILD_STYLE: Int = 0x40000000
        const val WS_VISIBLE_STYLE: Int = 0x10000000
        const val WS_DISABLED_STYLE: Int = 0x08000000
        const val WS_TABSTOP_STYLE: Int = 0x00010000
        const val WS_CLIPCHILDREN_STYLE: Int = 0x02000000
        const val WS_CLIPSIBLINGS_STYLE: Int = 0x04000000
        const val WS_BORDER_STYLE: Int = 0x00800000
        const val WS_VSCROLL_STYLE: Int = 0x00200000
        const val WS_HSCROLL_STYLE: Int = 0x00100000
        const val WS_EX_LAYERED_STYLE: Int = 0x00080000
        const val WS_EX_TRANSPARENT_STYLE: Int = 0x00000020
        const val WS_EX_TOOLWINDOW_STYLE: Int = 0x00000080
    }
}
