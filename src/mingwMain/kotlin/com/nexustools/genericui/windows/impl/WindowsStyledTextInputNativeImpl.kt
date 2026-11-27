@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopStyledTextInputNativeImpl
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.enums.css.CssFontStyle
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32DwmUxThemeBridge
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import com.nexustools.genericui.windows.Win32StyledWidgetHelper
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import platform.windows.BeginPaint
import platform.windows.CreateSolidBrush
import platform.windows.DT_CENTER
import platform.windows.DT_END_ELLIPSIS
import platform.windows.DT_LEFT
import platform.windows.DT_NOPREFIX
import platform.windows.DT_RIGHT
import platform.windows.DT_SINGLELINE
import platform.windows.DT_WORDBREAK
import platform.windows.DefSubclassProc
import platform.windows.DeleteObject
import platform.windows.DrawTextW
import platform.windows.EM_SETPASSWORDCHAR
import platform.windows.EM_SETREADONLY
import platform.windows.EN_CHANGE
import platform.windows.ES_AUTOHSCROLL
import platform.windows.ES_AUTOVSCROLL
import platform.windows.ES_MULTILINE
import platform.windows.ES_PASSWORD
import platform.windows.ES_READONLY
import platform.windows.ES_WANTRETURN
import platform.windows.EndPaint
import platform.windows.GetClientRect
import platform.windows.HDC
import platform.windows.HDC__
import platform.windows.HWND
import platform.windows.InvalidateRect
import platform.windows.IsWindow
import platform.windows.PAINTSTRUCT
import platform.windows.RECT
import platform.windows.SelectObject
import platform.windows.SendMessageW
import platform.windows.SetBkMode
import platform.windows.SetParent
import platform.windows.SetTextColor
import platform.windows.TRANSPARENT
import platform.windows.VK_RETURN
import platform.windows.WM_KEYDOWN
import platform.windows.WM_KILLFOCUS
import platform.windows.WM_LBUTTONDOWN
import platform.windows.WM_PAINT
import platform.windows.WM_SETFOCUS
import platform.windows.WM_SETFONT
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPCHILDREN
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE
import platform.windows.WS_VSCROLL
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Retained Win32 CSS-styled text input (`"GenericUIStyledTextInputW"`):
 * - Outer custom container `HWND` rendered with [StyledVisualSpec] solid/alpha/gradient fills,
 *   rounded/pill corners, custom borders, and focus rings via [Win32StyledWidgetHelper].
 * - Borderless inner ComCtl32 `"Edit"` child `HWND` inset cleanly within pill curvature, padding,
 *   and borders, matched via `WM_CTLCOLOREDIT` brush/text colors plus custom `placeholderColor`.
 */
class WindowsStyledTextInputNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIStyledTextInputW"),
    multiline: Boolean = false,
    classPrefix: String = if (multiline) "styled-textarea" else "styled-textfield"
) : DesktopStyledTextInputNativeImpl<Win32HwndHandle>(widget, multiline, classPrefix) {

    internal val editHandle: Win32HwndHandle = Win32HwndHandle("Edit")

    private var updating: Boolean = false
    private var lastAppliedEditX: Int = -1
    private var lastAppliedEditY: Int = -1
    private var lastAppliedEditW: Int = -1
    private var lastAppliedEditH: Int = -1
    private var lastPlaceholderColorRef: UInt = Win32DwmUxThemeBridge.colorRefRgb(120, 126, 138)

    init {
        nativeWidget.attributes["nativeImpl"] = this
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "GenericUIStyledTextInputW",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPCHILDREN or WS_CLIPSIBLINGS,
            initialText = "",
            initialWidth = 220,
            initialHeight = if (multiline) 96 else 36,
            installSubclass = false
        )
        wireOuterContainerMessages()
        createOrRebuildEditHwnd(replaceExisting = false)
        refreshStyledVisuals()
    }

    private fun wireOuterContainerMessages() {
        nativeWidget.customMessageCallback = { _, msg, wParam, _ ->
            when (msg.toInt()) {
                WM_LBUTTONDOWN -> {
                    if (isEnabled() && isFocusable() && nativeWidget.isEffectivelyFocusable()) {
                        if (!isFocusedState()) {
                            setFocusedState(true)
                        } else {
                            Win32RetainedControlSupport.syncOsFocusIfNeeded(this, editHandle.hwnd, true)
                        }
                    }
                    null
                }
                WM_SETFOCUS -> {
                    if (isEnabled() && isFocusable() && nativeWidget.isEffectivelyFocusable()) {
                        if (!isFocusedState()) {
                            setFocusedState(true)
                        } else {
                            Win32RetainedControlSupport.syncOsFocusIfNeeded(this, editHandle.hwnd, true)
                        }
                        0L
                    } else {
                        null
                    }
                }
                WM_KILLFOCUS -> {
                    val newFocusLong = wParam.toLong()
                    val editLong = editHandle.hwnd?.toLong() ?: 0L
                    if (editLong != 0L && newFocusLong == editLong) 0L else null
                }
                else -> null
            }
        }
    }

    private fun resolveEditStyle(): Int {
        val ro = if (readOnlyState) ES_READONLY else 0
        return if (multilineState) {
            WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS or
                ES_MULTILINE or ES_AUTOVSCROLL or WS_VSCROLL or ES_WANTRETURN or ro
        } else {
            val pw = if (passwordModeState) ES_PASSWORD else 0
            WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS or ES_AUTOHSCROLL or pw or ro
        }
    }

    private fun createOrRebuildEditHwnd(replaceExisting: Boolean) {
        val outerHwnd = nativeWidget.hwnd
        val editHwnd = if (replaceExisting && editHandle.hwnd != null) {
            Win32ControlMessageRouter.replaceChildHwndInPlace(
                handle = editHandle,
                owner = this,
                newWin32ClassName = "Edit",
                newDwStyle = resolveEditStyle(),
                initialText = textValue,
                installSubclass = true,
                isNativeEditTarget = true
            )
        } else {
            Win32ControlMessageRouter.createChildHwnd(
                handle = editHandle,
                owner = this,
                win32ClassName = "Edit",
                dwStyle = resolveEditStyle(),
                dwExStyle = 0,
                initialText = textValue,
                initialWidth = 196,
                initialHeight = if (multilineState) 76 else 22,
                installSubclass = true,
                isNativeEditTarget = true
            )
        }

        editHandle.parentWidget = nativeWidget
        if (!editHandle.attributes.containsKey("cursor")) {
            editHandle.attributes["cursor"] = "text"
        }
        if (outerHwnd != null && IsWindow(outerHwnd) != 0) {
            SetParent(editHwnd, outerHwnd)
            editHandle.parentHwnd = outerHwnd
        }
        SendMessageW(editHwnd, EM_SETMARGINS_MSG, (EC_LEFTMARGIN_FLAG or EC_RIGHTMARGIN_FLAG).toULong(), 0L)
        if (!multilineState && passwordModeState) {
            SendMessageW(editHwnd, EM_SETPASSWORDCHAR.toUInt(), 0x25CFuL, 0L)
        }
        wireInnerEditCallbacks()
        lastAppliedEditW = -1
        lastAppliedEditH = -1
    }

    private fun wireInnerEditCallbacks() {
        editHandle.onCommandCallback = { notifyCode, _, _ ->
            if (!updating && notifyCode == EN_CHANGE) {
                val cur = Win32RetainedControlSupport.readWindowText(editHandle.hwnd)
                val emptyChanged = textValue.isEmpty() != cur.isEmpty()
                if (textValue != cur) {
                    textValue = cur
                    nativeWidget.attributes["value"] = cur
                    onValueChangedListener?.invoke(cur)
                }
                if (emptyChanged) {
                    editHandle.hwnd?.let { if (IsWindow(it) != 0) InvalidateRect(it, null, 0) }
                }
            }
        }
        editHandle.customMessageCallback = { hwnd, msg, wParam, lParam ->
            when (msg.toInt()) {
                WM_KEYDOWN -> {
                    if (!multilineState && (wParam and 0xFFFFuL).toInt() == VK_RETURN) {
                        val cur = Win32RetainedControlSupport.readWindowText(hwnd)
                        if (textValue != cur) {
                            textValue = cur
                            nativeWidget.attributes["value"] = cur
                            onValueChangedListener?.invoke(cur)
                        }
                        submitValue()
                        0L
                    } else {
                        null
                    }
                }
                WM_PAINT -> {
                    if (wParam == 0uL && textValue.isEmpty() && placeholderText.isNotEmpty()) {
                        memScoped {
                            val ps = alloc<PAINTSTRUCT>()
                            val hdc = BeginPaint(hwnd, ps.ptr)
                            try {
                                if (hdc != null) {
                                    DefSubclassProc(hwnd, WM_PRINTCLIENT_MSG, hdc.toLong().toULong(), PRF_CLIENT_FLAG)
                                    paintPlaceholderToHdc(hwnd, hdc)
                                }
                            } finally {
                                EndPaint(hwnd, ps.ptr)
                            }
                        }
                        0L
                    } else {
                        null
                    }
                }
                WM_PRINTCLIENT_INT -> {
                    val res = DefSubclassProc(hwnd, msg, wParam, lParam)
                    if (textValue.isEmpty() && placeholderText.isNotEmpty()) {
                        drawPrintClientPlaceholder(hwnd, wParam)
                    }
                    res
                }
                else -> null
            }
        }
    }

    private fun drawPrintClientPlaceholder(hwnd: HWND, wParam: ULong) {
        val hdc: HDC = wParam.toLong().toCPointer<HDC__>() ?: return
        paintPlaceholderToHdc(hwnd, hdc)
    }

    private fun paintPlaceholderToHdc(hwnd: HWND, hdc: HDC) {
        memScoped {
            val rc = alloc<RECT>()
            if (GetClientRect(hwnd, rc.ptr) == 0) return
            val oldFont = editHandle.fontHandle?.let { SelectObject(hdc, it) }
            try {
                SetBkMode(hdc, TRANSPARENT)
                SetTextColor(hdc, lastPlaceholderColorRef)
                val align = when (resolveEffectiveStyle()?.textAlign?.trim()?.lowercase()) {
                    "center" -> DT_CENTER
                    "right", "end" -> DT_RIGHT
                    else -> DT_LEFT
                }
                val flags = if (multilineState) {
                    align or DT_WORDBREAK or DT_NOPREFIX or DT_END_ELLIPSIS
                } else {
                    align or DT_SINGLELINE or DT_NOPREFIX or DT_END_ELLIPSIS
                }
                DrawTextW(hdc, placeholderText, -1, rc.ptr, flags.toUInt())
            } finally {
                if (oldFont != null) SelectObject(hdc, oldFont)
            }
        }
    }

    internal fun onContainerReparented() {
        applyStyledVisuals(resolveCurrentVisuals())
    }

    override fun applyStyledVisuals(visuals: StyledVisualSpec) {
        val outerHwnd = nativeWidget.hwnd
        val editHwnd = editHandle.hwnd
        val isDark = Win32NativeWindowHost.isEffectivelyDarkTheme()

        val compStyle = getComponentStyle()
        val effStyle = resolveEffectiveStyle()
        val fontSizePx = (visuals.fontSizePx
            ?: compStyle?.fontSize?.resolvePx()
            ?: effStyle?.fontSizePx
            ?: 14f).roundToInt().coerceIn(9, 96)
        val fontFamily = visuals.fontFamily?.takeIf { it.isNotBlank() }
            ?: compStyle?.fontFamily?.resolvedPrimaryName()?.takeIf { it.isNotBlank() }
            ?: effStyle?.fontFamily?.takeIf { it.isNotBlank() }
            ?: "Segoe UI"
        val weight = visuals.fontWeightNumeric
            ?: compStyle?.fontWeightNumeric
            ?: effStyle?.fontWeightNumeric
            ?: styleSnapshot.fontWeight?.numericWeight
            ?: 400
        val italic = (compStyle?.fontStyle ?: effStyle?.fontStyle) == CssFontStyle.ITALIC
        val resolvedFont = Win32ControlMessageRouter.obtainUiFont(fontFamily, fontSizePx, weight, italic)

        if (resolvedFont != null && (nativeWidget.fontHandle != resolvedFont || editHandle.fontHandle != resolvedFont)) {
            nativeWidget.fontHandle = resolvedFont
            nativeWidget.ownsFontHandle = false
            editHandle.fontHandle = resolvedFont
            editHandle.ownsFontHandle = false
            if (editHwnd != null && IsWindow(editHwnd) != 0) {
                SendMessageW(editHwnd, WM_SETFONT.toUInt(), resolvedFont.toLong().toULong(), 1L)
            }
        }

        val editBgColor = if (outerHwnd != null && IsWindow(outerHwnd) != 0) {
            Win32StyledWidgetHelper.resolveEffectiveEditBackdropColor(outerHwnd, visuals, isDark)
        } else {
            visuals.backgroundColor ?: if (isDark) RgbaColor(34, 34, 40) else RgbaColor(255, 255, 255)
        }
        val bgLuminance = (0.2126f * editBgColor.r + 0.7152f * editBgColor.g + 0.0722f * editBgColor.b) / 255f
        val editIsDarkBg = bgLuminance < 0.48f

        val textColor = visuals.textColor
            ?: getEffectiveTextColor()
            ?: if (editIsDarkBg) RgbaColor(242, 242, 245) else RgbaColor(20, 20, 24)
        val placeholderColor = visuals.placeholderColor
            ?: if (editIsDarkBg) RgbaColor(156, 163, 175) else RgbaColor(108, 117, 125)

        val bgRef = Win32DwmUxThemeBridge.colorRefRgb(editBgColor.r, editBgColor.g, editBgColor.b)
        val fgRef = Win32DwmUxThemeBridge.colorRefRgb(textColor.r, textColor.g, textColor.b)
        lastPlaceholderColorRef = Win32DwmUxThemeBridge.colorRefRgb(placeholderColor.r, placeholderColor.g, placeholderColor.b)

        nativeWidget.fgColorRef = fgRef
        nativeWidget.bgColorRef = bgRef
        nativeWidget.hasCustomColors = true
        nativeWidget.isTransparentCtlColor = false

        editHandle.fgColorRef = fgRef
        editHandle.bgColorRef = bgRef
        editHandle.hasCustomColors = true
        editHandle.isTransparentCtlColor = false
        editHandle.bgBrushHandle?.let { oldBrush ->
            DeleteObject(oldBrush)
            editHandle.bgBrushHandle = null
        }
        editHandle.bgBrushHandle = CreateSolidBrush(bgRef)

        val w = nativeWidget.bounds.width.takeIf { it > 0 } ?: nativeWidget.lastAppliedW.takeIf { it > 0 } ?: 220
        val h = nativeWidget.bounds.height.takeIf { it > 0 } ?: nativeWidget.lastAppliedH.takeIf { it > 0 } ?: if (multilineState) 96 else 36
        layoutInnerEditHwnd(w, h, visuals)

        if (outerHwnd != null && IsWindow(outerHwnd) != 0) {
            Win32StyledWidgetHelper.invalidateAncestorBackgroundCache(outerHwnd)
            InvalidateRect(outerHwnd, null, 0)
        }
        if (editHwnd != null && IsWindow(editHwnd) != 0) {
            InvalidateRect(editHwnd, null, 0)
        }
    }

    private fun layoutInnerEditHwnd(outerW: Int, outerH: Int, visuals: StyledVisualSpec) {
        val editHwnd = editHandle.hwnd ?: return
        if (IsWindow(editHwnd) == 0 || outerW <= 0 || outerH <= 0) return

        val pad = visuals.padding ?: nativeWidget.padding
        val borderW = if (visuals.borderStyle != CssBorderStyle.NONE && visuals.borderStyle != CssBorderStyle.HIDDEN) {
            ceil(visuals.borderWidthPx ?: 0f).toInt().coerceAtLeast(0)
        } else {
            0
        }
        val ringW = if (isFocusedState()) ceil(visuals.focusRingWidthPx ?: 0f).toInt().coerceAtLeast(0) else 0
        val borderInset = max(borderW, ringW)

        val radius = (visuals.borderRadiusPx ?: 0f).coerceIn(0f, min(outerW, outerH) * 0.5f)
        val effPadT = if (pad.top > 0) pad.top else 4
        val effPadB = if (pad.bottom > 0) pad.bottom else 4
        val topClearance = (borderInset + effPadT).coerceAtLeast(if (radius > 2f) 3 else 1)
        val botClearance = (borderInset + effPadB).coerceAtLeast(if (radius > 2f) 3 else 1)

        val minYEdge = min(topClearance, botClearance).toFloat()
        val arcIntrusionPx = if (radius > 1f && minYEdge < radius) {
            val dy = radius - minYEdge
            ceil(radius - sqrt((radius * radius - dy * dy).coerceAtLeast(0f))).toInt()
        } else {
            0
        }
        val defaultPadX = if (radius > 2f) max(8, ceil(radius * 0.5f).toInt()) else 6
        val effPadL = if (pad.left > 0) pad.left else defaultPadX
        val effPadR = if (pad.right > 0) pad.right else defaultPadX

        val leftInset = max(borderInset + arcIntrusionPx + 1, borderInset + effPadL)
        val rightInset = max(borderInset + arcIntrusionPx + 1, borderInset + effPadR)

        val maxAvailW = (outerW - leftInset - rightInset).coerceAtLeast(1)
        val maxAvailH = (outerH - topClearance - botClearance).coerceAtLeast(1)

        val editX: Int
        val editY: Int
        val editW: Int
        val editH: Int
        if (multilineState) {
            editX = leftInset.coerceIn(0, (outerW - 1).coerceAtLeast(0))
            editY = topClearance.coerceIn(0, (outerH - 1).coerceAtLeast(0))
            editW = maxAvailW
            editH = maxAvailH
        } else {
            val lineMetrics = Win32RetainedControlSupport.measureTextSize(
                text = "Ay",
                fontHandle = editHandle.fontHandle,
                extraW = 0,
                extraH = 0,
                minW = 4,
                minH = 14
            )
            editX = leftInset.coerceIn(0, (outerW - 1).coerceAtLeast(0))
            editW = maxAvailW
            editH = lineMetrics.height.coerceIn(min(14, maxAvailH), maxAvailH)
            editY = (topClearance + (maxAvailH - editH) / 2).coerceIn(0, (outerH - editH).coerceAtLeast(0))
        }

        if (lastAppliedEditX != editX || lastAppliedEditY != editY ||
            lastAppliedEditW != editW || lastAppliedEditH != editH
        ) {
            lastAppliedEditX = editX
            lastAppliedEditY = editY
            lastAppliedEditW = editW
            lastAppliedEditH = editH
            editHandle.bounds = Rect(editX, editY, editW, editH)
            Win32ControlMessageRouter.applyBounds(editHandle, Rect(editX, editY, editW, editH))
        }
    }

    override fun setValue(value: String) {
        super.setValue(value)
        updating = true
        try {
            Win32RetainedControlSupport.setWindowTextIfChanged(editHandle.hwnd, value)
            editHandle.hwnd?.let { if (IsWindow(it) != 0) InvalidateRect(it, null, 0) }
        } finally {
            updating = false
        }
    }

    override fun setPlaceholder(placeholder: String) {
        super.setPlaceholder(placeholder)
        editHandle.hwnd?.let { if (IsWindow(it) != 0) InvalidateRect(it, null, 0) }
    }

    override fun setReadOnly(readOnly: Boolean) {
        super.setReadOnly(readOnly)
        editHandle.hwnd?.let {
            if (IsWindow(it) != 0) {
                SendMessageW(it, EM_SETREADONLY.toUInt(), if (readOnly) 1uL else 0uL, 0L)
            }
        }
    }

    override fun setPasswordMode(password: Boolean) {
        val prev = passwordModeState
        super.setPasswordMode(password)
        if (prev != password && editHandle.hwnd != null && !multilineState) {
            SendMessageW(
                editHandle.hwnd,
                EM_SETPASSWORDCHAR.toUInt(),
                if (password) 0x25CFuL else 0uL,
                0L
            )
            editHandle.hwnd?.let { if (IsWindow(it) != 0) InvalidateRect(it, null, 0) }
        }
    }

    override fun setMultiline(multiline: Boolean) {
        val prev = multilineState
        super.setMultiline(multiline)
        if (prev != multiline && editHandle.hwnd != null) {
            createOrRebuildEditHwnd(replaceExisting = true)
            applyStyledVisuals(resolveCurrentVisuals())
        }
    }

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        Win32RetainedControlSupport.syncOsFocusIfNeeded(this, editHandle.hwnd, focused)
        nativeWidget.hwnd?.let { h -> if (IsWindow(h) != 0) InvalidateRect(h, null, 0) }
        editHandle.hwnd?.let { eh -> if (IsWindow(eh) != 0) InvalidateRect(eh, null, 0) }
    }

    override fun setHoveredState(hovered: Boolean) {
        super.setHoveredState(hovered)
        nativeWidget.hwnd?.let { h -> if (IsWindow(h) != 0) InvalidateRect(h, null, 0) }
    }

    override fun setActivePressedState(active: Boolean) {
        super.setActivePressedState(active)
        nativeWidget.hwnd?.let { h -> if (IsWindow(h) != 0) InvalidateRect(h, null, 0) }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val visuals = resolveCurrentVisuals()
        val pad = visuals.padding ?: nativeWidget.padding
        val bw = ceil(visuals.borderWidthPx ?: 0f).toInt().coerceAtLeast(0)
        val effPadL = if (pad.left > 0) pad.left else 12
        val effPadR = if (pad.right > 0) pad.right else 12
        val effPadT = if (pad.top > 0) pad.top else 6
        val effPadB = if (pad.bottom > 0) pad.bottom else 6
        val lineMetrics = Win32RetainedControlSupport.measureTextSize(
            text = "Ay",
            fontHandle = editHandle.fontHandle ?: nativeWidget.fontHandle,
            extraW = 0,
            extraH = 0,
            minW = 16,
            minH = 16
        )
        val baseH = if (multilineState) 92 else max(34, lineMetrics.height + effPadT + effPadB + bw * 2)
        val baseW = max(180, lineMetrics.width + effPadL + effPadR + bw * 2 + 120)
        return Size(baseW, baseH)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        Win32RetainedControlSupport.onLeafBoundsChanged(nativeWidget, rect)
        layoutInnerEditHwnd(rect.width, rect.height, resolveCurrentVisuals())
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        Win32RetainedControlSupport.onVisibilityChanged(nativeWidget, visible)
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        Win32RetainedControlSupport.onEnabledChanged(nativeWidget, enabled)
        Win32RetainedControlSupport.onEnabledChanged(editHandle, enabled)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        Win32RetainedControlSupport.onOpacityChanged(nativeWidget, opacity)
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        Win32RetainedControlSupport.onTooltipChanged(nativeWidget, tooltip)
        Win32RetainedControlSupport.onTooltipChanged(editHandle, tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        Win32RetainedControlSupport.onCursorChanged(nativeWidget, cursor)
        if (cursor != null) {
            Win32RetainedControlSupport.onCursorChanged(editHandle, cursor)
        } else {
            editHandle.activeCursorValue = null
            editHandle.attributes["cursor"] = "text"
        }
    }

    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        Win32RetainedControlSupport.onPointerEventsChanged(nativeWidget, pointerEventsNone)
        Win32RetainedControlSupport.onPointerEventsChanged(editHandle, pointerEventsNone)
    }

    override fun onNativeZIndexChanged(zIndex: Int) {
        Win32RetainedControlSupport.onZIndexChanged(nativeWidget, zIndex)
    }

    override fun onNativeStyleSynced() {
        applyStyledVisuals(resolveCurrentVisuals())
    }

    override fun dispose() {
        Win32ControlMessageRouter.destroyHandle(editHandle)
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }

    private companion object {
        private const val EM_SETMARGINS_MSG: UInt = 0x00D3u
        private const val EC_LEFTMARGIN_FLAG: Int = 0x0001
        private const val EC_RIGHTMARGIN_FLAG: Int = 0x0002
        private const val WM_PRINTCLIENT_MSG: UInt = 0x0318u
        private const val WM_PRINTCLIENT_INT: Int = 0x0318
        private const val PRF_CLIENT_FLAG: Long = 0x00000004L
    }
}
