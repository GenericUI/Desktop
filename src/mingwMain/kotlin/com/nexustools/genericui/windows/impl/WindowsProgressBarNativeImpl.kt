@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopProgressBarNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import platform.windows.GetWindowLongPtrW
import platform.windows.GWL_STYLE
import platform.windows.PBM_SETMARQUEE
import platform.windows.PBM_SETPOS
import platform.windows.PBM_SETRANGE32
import platform.windows.PBS_MARQUEE
import platform.windows.PBS_SMOOTH
import platform.windows.SendMessageW
import platform.windows.SetWindowLongPtrW
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_VISIBLE
import kotlin.math.roundToInt

/**
 * Retained Win32 ComCtl32 `"msctls_progress32"` progress bar (`PBS_SMOOTH` / `PBS_MARQUEE`).
 */
class WindowsProgressBarNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("msctls_progress32")
) : DesktopProgressBarNativeImpl<Win32HwndHandle>(widget) {

    init {
        nativeWidget.attributes["nativeImpl"] = this
        val hwnd = Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "msctls_progress32",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_CLIPSIBLINGS or PBS_SMOOTH,
            initialWidth = 200,
            initialHeight = 16,
            installSubclass = true
        )
        SendMessageW(hwnd, PBM_SETRANGE32.toUInt(), 0uL, 1000L)
        syncProgressState()
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    private fun syncProgressState() {
        val hwnd = nativeWidget.hwnd ?: return
        val isMarquee = indeterminateState || busyState
        var style = GetWindowLongPtrW(hwnd, GWL_STYLE).toInt()
        if (isMarquee) {
            if ((style and PBS_MARQUEE) == 0) {
                style = style or PBS_MARQUEE
                SetWindowLongPtrW(hwnd, GWL_STYLE, style.toLong())
            }
            SendMessageW(hwnd, PBM_SETMARQUEE.toUInt(), 1uL, 30L)
        } else {
            if ((style and PBS_MARQUEE) != 0) {
                style = style and PBS_MARQUEE.inv()
                SetWindowLongPtrW(hwnd, GWL_STYLE, style.toLong())
                SendMessageW(hwnd, PBM_SETMARQUEE.toUInt(), 0uL, 0L)
            }
            val pos = (progressValue.coerceIn(0f, 1f) * 1000f).roundToInt().coerceIn(0, 1000)
            SendMessageW(hwnd, PBM_SETPOS.toUInt(), pos.toULong(), 0L)
        }
    }

    override fun setProgress(progress: Float) {
        super.setProgress(progress)
        syncProgressState()
    }

    override fun setIndeterminate(indeterminate: Boolean) {
        super.setIndeterminate(indeterminate)
        syncProgressState()
    }

    override fun setBusy(busy: Boolean) {
        super.setBusy(busy)
        syncProgressState()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size = Size(180, 14)

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
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
