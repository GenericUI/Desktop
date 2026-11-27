@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import com.nexustools.genericui.windows.Win32StyledWidgetHelper
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.BM_GETCHECK
import platform.windows.BM_SETCHECK
import platform.windows.BN_CLICKED
import platform.windows.BS_AUTOCHECKBOX
import platform.windows.BS_RADIOBUTTON
import platform.windows.BST_CHECKED
import platform.windows.BST_UNCHECKED
import platform.windows.DT_END_ELLIPSIS
import platform.windows.DT_LEFT
import platform.windows.DT_NOPREFIX
import platform.windows.DT_SINGLELINE
import platform.windows.DT_VCENTER
import platform.windows.DrawTextW
import platform.windows.HDC
import platform.windows.HWND
import platform.windows.InvalidateRect
import platform.windows.IsWindow
import platform.windows.RECT
import platform.windows.SelectObject
import platform.windows.SendMessageW
import platform.windows.SetBkMode
import platform.windows.SetTextColor
import platform.windows.TRANSPARENT
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE
import kotlin.math.min

/**
 * Retained Win32 implementation for `CheckBox` (`"Button"` + `BS_AUTOCHECKBOX`), `RadioButton`
 * (`"Button"` + `BS_RADIOBUTTON`), and `Switch` (custom toggle `"GenericUISwitchW"`).
 */
class WindowsCheckboxNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("Button"),
    switchStyle: Boolean = false,
    radioStyle: Boolean = false
) : DesktopCheckboxNativeImpl<Win32HwndHandle>(widget, switchStyle, radioStyle) {

    init {
        nativeWidget.attributes["nativeImpl"] = this
        val className = if (switchStyle) "GenericUISwitchW" else "Button"
        val style = when {
            switchStyle -> WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS
            radioStyle -> WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS or BS_RADIOBUTTON
            else -> WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS or BS_AUTOCHECKBOX
        }
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = className,
            dwStyle = style,
            initialText = labelText,
            initialWidth = 120,
            initialHeight = 24,
            installSubclass = !switchStyle
        )
        if (switchStyle) {
            nativeWidget.customPaintCallback = { hwnd, hdc, w, h -> paintSwitchControl(hwnd, hdc, w, h) }
            setOnClick {
                setChecked(!isChecked())
                onCheckedChangedListener?.invoke(isChecked())
            }
        } else {
            nativeWidget.onCommandCallback = { notifyCode, _, _ ->
                if (notifyCode == BN_CLICKED) {
                    val h = nativeWidget.hwnd
                    val nowChecked = if (radioStyle) {
                        true
                    } else if (h != null) {
                        SendMessageW(h, BM_GETCHECK.toUInt(), 0uL, 0L).toInt() == BST_CHECKED
                    } else {
                        !checkedState
                    }
                    if (checkedState != nowChecked) {
                        checkedState = nowChecked
                        nativeWidget.attributes["checked"] = nowChecked
                        if (radioStyle && h != null) {
                            SendMessageW(h, BM_SETCHECK.toUInt(), BST_CHECKED.toULong(), 0L)
                        }
                        onCheckedChangedListener?.invoke(nowChecked)
                    }
                }
            }
        }
        syncCheckStateToHwnd()
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
    }

    private fun syncCheckStateToHwnd() {
        val hwnd = nativeWidget.hwnd ?: return
        if (switchStyle) {
            InvalidateRect(hwnd, null, 0)
        } else {
            SendMessageW(
                hwnd,
                BM_SETCHECK.toUInt(),
                (if (checkedState) BST_CHECKED else BST_UNCHECKED).toULong(),
                0L
            )
        }
    }

    private fun paintSwitchControl(hwnd: HWND, hdc: HDC, width: Int, height: Int) {
        val isDark = Win32NativeWindowHost.isEffectivelyDarkTheme()
        val pixels = IntArray(width * height)
        Win32StyledWidgetHelper.captureBackdropIntoBuffer(hwnd, 0, 0, width, height, pixels, isDark)

        val trackW = 38
        val trackH = 20
        val trackX = 0
        val trackY = ((height - trackH) / 2).coerceAtLeast(0)
        val accent = getEffectiveAccentColor(isDark)
            ?: BootstrapColorPalette.resolveTintOrNull(getType(), isDark)
            ?: RgbaColor(13, 110, 253)
        val trackCol = if (checkedState) accent else if (isDark) RgbaColor(72, 76, 86) else RgbaColor(175, 180, 190)

        drawPillOnPixels(pixels, width, height, trackX, trackY, trackW, trackH, trackCol)

        val thumbD = 14
        val thumbX = if (checkedState) trackX + trackW - thumbD - 3 else trackX + 3
        val thumbY = trackY + ((trackH - thumbD) / 2)
        drawPillOnPixels(pixels, width, height, thumbX, thumbY, thumbD, thumbD, RgbaColor(255, 255, 255))

        Win32StyledWidgetHelper.blitArgbArrayToHdc(hdc, width, height, pixels, premultiplied = false)

        if (labelText.isNotBlank() && width > 46) {
            val fg = getEffectiveTextColor() ?: if (isDark) RgbaColor(240, 240, 245) else RgbaColor(24, 24, 28)
            val oldFont = nativeWidget.fontHandle?.let { SelectObject(hdc, it) }
            val prevBk = SetBkMode(hdc, TRANSPARENT)
            SetTextColor(hdc, (fg.r or (fg.g shl 8) or (fg.b shl 16)).toUInt())
            memScoped {
                val rc = alloc<RECT>()
                rc.left = 46
                rc.top = 0
                rc.right = width
                rc.bottom = height
                val flags = DT_SINGLELINE or DT_VCENTER or DT_LEFT or DT_END_ELLIPSIS or DT_NOPREFIX
                DrawTextW(hdc, labelText, -1, rc.ptr, flags.toUInt())
            }
            if (prevBk != 0) SetBkMode(hdc, prevBk)
            if (oldFont != null) SelectObject(hdc, oldFont)
        }
    }

    private fun drawPillOnPixels(
        pixels: IntArray,
        bufW: Int,
        bufH: Int,
        rx: Int,
        ry: Int,
        rw: Int,
        rh: Int,
        color: RgbaColor
    ) {
        val radius = min(rw, rh) * 0.5f
        val hw = rw * 0.5f
        val hh = rh * 0.5f
        val cx = rx + hw
        val cy = ry + hh
        val x0 = rx.coerceIn(0, bufW)
        val y0 = ry.coerceIn(0, bufH)
        val x1 = (rx + rw).coerceIn(0, bufW)
        val y1 = (ry + rh).coerceIn(0, bufH)
        val word = (0xFF shl 24) or (color.r shl 16) or (color.g shl 8) or color.b
        for (py in y0 until y1) {
            val rowOff = py * bufW
            val dy = kotlin.math.abs(py + 0.5f - cy) - (hh - radius)
            for (px in x0 until x1) {
                val dx = kotlin.math.abs(px + 0.5f - cx) - (hw - radius)
                val qx = kotlin.math.max(dx, 0f)
                val qy = kotlin.math.max(dy, 0f)
                val sd = kotlin.math.sqrt(qx * qx + qy * qy) + kotlin.math.min(kotlin.math.max(dx, dy), 0f) - radius
                if (sd <= 0.35f) {
                    pixels[rowOff + px] = word
                }
            }
        }
    }

    override fun setLabel(label: String) {
        super.setLabel(label)
        Win32RetainedControlSupport.setWindowTextIfChanged(nativeWidget.hwnd, label)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun setChecked(checked: Boolean) {
        super.setChecked(checked)
        syncCheckStateToHwnd()
    }

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        Win32RetainedControlSupport.syncOsFocusIfNeeded(this, nativeWidget.hwnd, focused)
        nativeWidget.hwnd?.let { if (IsWindow(it) != 0) InvalidateRect(it, null, 0) }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val extraW = if (switchStyle) 48 else 26
        return Win32RetainedControlSupport.measureTextSize(
            text = labelText,
            fontHandle = nativeWidget.fontHandle,
            maxWidthPx = maxWidth,
            extraW = extraW,
            extraH = 6,
            minW = if (switchStyle) 44 else 24,
            minH = 24
        )
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

    override fun onNativeStyleSynced() {
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
