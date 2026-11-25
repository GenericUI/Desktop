@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import com.nexustools.genericui.windows.Win32StyledWidgetHelper
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.windows.CC_FULLOPEN
import platform.windows.CC_RGBINIT
import platform.windows.CHOOSECOLORW
import platform.windows.ChooseColorW
import platform.windows.HDC
import platform.windows.HWND
import platform.windows.InvalidateRect
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE

/**
 * Retained Win32 `"GenericUIColorPickerW"` control displaying a color swatch + hex readout and
 * launching the native Win32 `ChooseColorW` dialog when clicked.
 */
class WindowsColorPickerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIColorPickerW")
) : DesktopTextInputNativeImpl<Win32HwndHandle>(widget, multiline = false) {

    init {
        nativeWidget.attributes["nativeImpl"] = this
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "GenericUIColorPickerW",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS,
            initialWidth = 104,
            initialHeight = 32,
            installSubclass = false
        )
        nativeWidget.customPaintCallback = { hwnd, hdc, w, h ->
            paintColorSwatchControl(hwnd, hdc, w, h, textValue, nativeWidget)
        }
        setOnClick {
            val nextHex = openWin32ChooseColor(nativeWidget.hwnd ?: Win32NativeWindowHost.activeHwnd, textValue)
            if (nextHex != null && nextHex != textValue) {
                setValue(nextHex)
                onValueChangedListener?.invoke(nextHex)
            }
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    override fun setValue(value: String) {
        super.setValue(value)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size = Size(104, 32)

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

    companion object {
        private val customPaletteColors = UIntArray(16) { 0x00FFFFFFu }

        internal fun parseHexColor(raw: String?): RgbaColor {
            val clean = raw?.trim()?.removePrefix("#").orEmpty()
            return if (clean.length >= 6) {
                val r = clean.substring(0, 2).toIntOrNull(16) ?: 59
                val g = clean.substring(2, 4).toIntOrNull(16) ?: 130
                val b = clean.substring(4, 6).toIntOrNull(16) ?: 246
                RgbaColor(r, g, b)
            } else {
                RgbaColor(59, 130, 246)
            }
        }

        internal fun openWin32ChooseColor(ownerHwnd: HWND?, currentHex: String?): String? = memScoped {
            val c = parseHexColor(currentHex)
            val initRef = (c.r or (c.g shl 8) or (c.b shl 16)).toUInt()
            val custPtr = allocArray<UIntVar>(16)
            for (i in 0 until 16) custPtr[i] = customPaletteColors[i]
            val cc = alloc<CHOOSECOLORW>()
            cc.lStructSize = sizeOf<CHOOSECOLORW>().toUInt()
            cc.hwndOwner = ownerHwnd
            cc.rgbResult = initRef
            cc.lpCustColors = custPtr
            cc.Flags = (CC_RGBINIT or CC_FULLOPEN).toUInt()
            if (ChooseColorW(cc.ptr) != 0) {
                for (i in 0 until 16) customPaletteColors[i] = custPtr[i]
                val res = cc.rgbResult
                val r = (res and 0xFFu).toInt()
                val g = ((res shr 8) and 0xFFu).toInt()
                val b = ((res shr 16) and 0xFFu).toInt()
                "#" + r.toString(16).padStart(2, '0') +
                    g.toString(16).padStart(2, '0') +
                    b.toString(16).padStart(2, '0')
            } else {
                null
            }
        }

        internal fun paintColorSwatchControl(
            hwnd: HWND,
            hdc: HDC,
            width: Int,
            height: Int,
            hexValue: String,
            handle: Win32HwndHandle
        ) {
            val isDark = Win32NativeWindowHost.isEffectivelyDarkTheme()
            val swatch = parseHexColor(hexValue)
            val bg = if (isDark) RgbaColor(42, 45, 52) else RgbaColor(255, 255, 255)
            val border = if (isDark) RgbaColor(86, 92, 106) else RgbaColor(180, 185, 195)
            val spec = com.nexustools.genericui.style.css.StyledVisualSpec(
                backgroundColor = bg,
                borderColor = border,
                borderWidthPx = 1f,
                borderRadiusPx = 6f,
                textColor = if (isDark) RgbaColor(240, 240, 245) else RgbaColor(24, 24, 28)
            )
            val display = if (hexValue.isNotBlank()) "      ${hexValue.uppercase()}" else "      #3B82F6"
            Win32StyledWidgetHelper.renderStyledSpecToHdc(
                hwnd = hwnd,
                hdc = hdc,
                width = width,
                height = height,
                spec = spec,
                isDark = isDark,
                fontHandle = handle.fontHandle,
                text = display,
                alignLeft = true
            )
            // Paint crisp inner color swatch on left `[6..26, 6..height-6)` cleanly over the rendered frame
            val swW = 20.coerceAtMost(width - 10)
            val swH = (height - 12).coerceAtLeast(10)
            if (swW > 4 && swH > 4) {
                val swatchWord = (0xFF shl 24) or (swatch.r shl 16) or (swatch.g shl 8) or swatch.b
                val swPixels = IntArray(swW * swH) { swatchWord }
                memScoped {
                    val bmi = alloc<platform.windows.BITMAPINFO>()
                    bmi.bmiHeader.biSize = sizeOf<platform.windows.BITMAPINFOHEADER>().toUInt()
                    bmi.bmiHeader.biWidth = swW
                    bmi.bmiHeader.biHeight = -swH
                    bmi.bmiHeader.biPlanes = 1u
                    bmi.bmiHeader.biBitCount = 32u
                    bmi.bmiHeader.biCompression = platform.windows.BI_RGB.toUInt()
                    swPixels.usePinned { pinned ->
                        platform.windows.StretchDIBits(
                            hdc,
                            6,
                            6,
                            swW,
                            swH,
                            0,
                            0,
                            swW,
                            swH,
                            pinned.addressOf(0),
                            bmi.ptr,
                            platform.windows.DIB_RGB_COLORS.toUInt(),
                            platform.windows.SRCCOPY
                        )
                    }
                }
            }
        }
    }
}
