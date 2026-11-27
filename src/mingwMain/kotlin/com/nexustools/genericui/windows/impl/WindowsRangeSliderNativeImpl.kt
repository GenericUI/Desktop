@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopRangeSliderNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import platform.windows.SendMessageW
import platform.windows.TBM_GETPOS
import platform.windows.TBM_SETPOS
import platform.windows.TBM_SETRANGEMAX
import platform.windows.TBM_SETRANGEMIN
import platform.windows.TBS_AUTOTICKS
import platform.windows.TBS_HORZ
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE
import kotlin.math.roundToInt

/**
 * Retained Win32 ComCtl32 `"msctls_trackbar32"` slider (`RangeSlider` / `SeekBar` / `VolumeSlider`).
 */
class WindowsRangeSliderNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("msctls_trackbar32")
) : DesktopRangeSliderNativeImpl<Win32HwndHandle>(widget) {

    private val trackbarResolution = 1000
    private var updating: Boolean = false

    init {
        nativeWidget.attributes["nativeImpl"] = this
        val hwnd = Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "msctls_trackbar32",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS or TBS_HORZ or TBS_AUTOTICKS,
            initialWidth = 180,
            initialHeight = 28,
            installSubclass = true
        )
        SendMessageW(hwnd, TBM_SETRANGEMIN.toUInt(), 0uL, 0L)
        SendMessageW(hwnd, TBM_SETRANGEMAX.toUInt(), 1uL, trackbarResolution.toLong())
        nativeWidget.onScrollCallback = { _, _, _ ->
            if (!updating) {
                val pos = SendMessageW(hwnd, TBM_GETPOS.toUInt(), 0uL, 0L).toInt()
                val frac = (pos.toDouble() / trackbarResolution.toDouble()).coerceIn(0.0, 1.0)
                setNormalizedFraction(frac)
            }
        }
        syncTrackbarPosition()
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
    }

    private fun syncTrackbarPosition() {
        val hwnd = nativeWidget.hwnd ?: return
        val span = (maxVal - minVal)
        val frac = if (span > 1e-9) ((currentVal - minVal) / span).coerceIn(0.0, 1.0) else 0.0
        val pos = (frac * trackbarResolution).roundToInt().coerceIn(0, trackbarResolution)
        updating = true
        try {
            SendMessageW(hwnd, TBM_SETPOS.toUInt(), 1uL, pos.toLong())
        } finally {
            updating = false
        }
    }

    override fun setRange(min: Double, max: Double, step: Double) {
        super.setRange(min, max, step)
        syncTrackbarPosition()
    }

    override fun setValue(value: Double) {
        super.setValue(value)
        syncTrackbarPosition()
    }

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        Win32RetainedControlSupport.syncOsFocusIfNeeded(this, nativeWidget.hwnd, focused)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size = Size(160, 28)

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
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
