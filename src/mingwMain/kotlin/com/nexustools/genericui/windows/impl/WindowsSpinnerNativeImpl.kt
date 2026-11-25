@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopSpinnerNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import com.nexustools.genericui.windows.Win32StyledWidgetHelper
import platform.windows.HDC
import platform.windows.HWND
import platform.windows.InvalidateRect
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_VISIBLE
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Retained Win32 `"GenericUISpinnerW"` progress ring control rendering anti-aliased spinning arcs.
 */
class WindowsSpinnerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUISpinnerW")
) : DesktopSpinnerNativeImpl<Win32HwndHandle>(widget) {

    init {
        nativeWidget.attributes["nativeImpl"] = this
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "GenericUISpinnerW",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_CLIPSIBLINGS,
            initialWidth = 28,
            initialHeight = 28,
            installSubclass = false
        )
        nativeWidget.customPaintCallback = { hwnd, hdc, w, h -> paintSpinnerRing(hwnd, hdc, w, h) }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
    }

    private fun paintSpinnerRing(hwnd: HWND, hdc: HDC, width: Int, height: Int) {
        val isDark = Win32NativeWindowHost.isEffectivelyDarkTheme()
        val pixels = IntArray(width * height)
        Win32StyledWidgetHelper.captureBackdropIntoBuffer(hwnd, 0, 0, width, height, pixels, isDark)

        val accent = getEffectiveAccentColor(isDark)
            ?: BootstrapColorPalette.resolveTintOrNull(getType(), isDark)
            ?: RgbaColor(13, 110, 253)
        val cx = width * 0.5f
        val cy = height * 0.5f
        val outerR = (min(width, height) * 0.5f - 2f).coerceAtLeast(4f)
        val stroke = (outerR * 0.25f).coerceIn(2f, 5f)
        val midR = outerR - stroke * 0.5f
        val phase = ((Win32NativeWindowHost.nowMicros() / 3500L) % 360L).toFloat() * (PI.toFloat() / 180f)

        for (py in 0 until height) {
            val rowOff = py * width
            val dy = py + 0.5f - cy
            for (px in 0 until width) {
                val dx = px + 0.5f - cx
                val dist = sqrt(dx * dx + dy * dy)
                val ringDist = kotlin.math.abs(dist - midR) - stroke * 0.5f
                if (ringDist <= 0.7f) {
                    val cov = (0.7f - ringDist).coerceIn(0f, 1f)
                    val ang = atan2(dy, dx)
                    val rel = ((ang - phase) % (2f * PI.toFloat()) + 2f * PI.toFloat()) % (2f * PI.toFloat())
                    val inArc = rel <= 4.2f
                    val sr: Int
                    val sg: Int
                    val sb: Int
                    val sa: Int
                    if (inArc) {
                        sr = accent.r; sg = accent.g; sb = accent.b; sa = (255f * cov).toInt()
                    } else {
                        sr = accent.r; sg = accent.g; sb = accent.b; sa = (55f * cov).toInt()
                    }
                    val dst = pixels[rowOff + px]
                    val dr = (dst ushr 16) and 0xFF
                    val dg = (dst ushr 8) and 0xFF
                    val db = dst and 0xFF
                    val invA = 255 - sa
                    val outR = (sr * sa + dr * invA + 127) / 255
                    val outG = (sg * sa + dg * invA + 127) / 255
                    val outB = (sb * sa + db * invA + 127) / 255
                    pixels[rowOff + px] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }
        }
        Win32StyledWidgetHelper.blitArgbArrayToHdc(hdc, width, height, pixels, premultiplied = false)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size = Size(28, 28)

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
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
