@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toLong
import kotlinx.cinterop.wcstr
import platform.windows.CreateWindowExW
import platform.windows.DTN_DATETIMECHANGE
import platform.windows.DTS_SHORTDATECENTURYFORMAT
import platform.windows.DTS_TIMEFORMAT
import platform.windows.DestroyWindow
import platform.windows.EM_SETCUEBANNER
import platform.windows.EM_SETPASSWORDCHAR
import platform.windows.EM_SETREADONLY
import platform.windows.EN_CHANGE
import platform.windows.ES_AUTOHSCROLL
import platform.windows.ES_AUTOVSCROLL
import platform.windows.ES_MULTILINE
import platform.windows.ES_NUMBER
import platform.windows.ES_PASSWORD
import platform.windows.ES_READONLY
import platform.windows.ES_WANTRETURN
import platform.windows.GDT_VALID
import platform.windows.GetModuleHandleW
import platform.windows.IsWindow
import platform.windows.NMDATETIMECHANGE
import platform.windows.SendMessageW
import platform.windows.UDM_GETPOS32
import platform.windows.UDM_SETBUDDY
import platform.windows.UDM_SETPOS32
import platform.windows.UDM_SETRANGE32
import platform.windows.UDS_ALIGNRIGHT
import platform.windows.UDS_ARROWKEYS
import platform.windows.UDS_SETBUDDYINT
import platform.windows.VK_RETURN
import platform.windows.WM_KEYDOWN
import platform.windows.WS_BORDER
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE
import platform.windows.WS_VSCROLL

/**
 * Retained Win32 ComCtl32 text/input control adapting seamlessly across variants:
 * - Default `TextField` / `SearchField` / `PasswordField`: `"Edit"` (`ES_AUTOHSCROLL`, `ES_PASSWORD`, `EM_SETCUEBANNER`)
 * - `TextArea`: `"Edit"` (`ES_MULTILINE | WS_VSCROLL`)
 * - `NumberField`: `"Edit"` (`ES_NUMBER`) paired with `"msctls_updown32"` buddy spinner (`UDM_SETBUDDY`)
 * - `DatePicker` / `TimePicker`: `"SysDateTimePick32"` (`DTS_SHORTDATECENTURYFORMAT` / `DTS_TIMEFORMAT`)
 * - `ColorPicker`: `"GenericUIColorPickerW"` (`ChooseColorW`)
 */
class WindowsTextInputNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("Edit"),
    multiline: Boolean = false
) : DesktopTextInputNativeImpl<Win32HwndHandle>(widget, multiline) {

    private var updating: Boolean = false

    init {
        nativeWidget.attributes["nativeImpl"] = this
        createControlForActiveVariant(replaceExisting = false)
    }

    private fun resolveVariantKind(): String {
        val v = resolveControlVariant()?.lowercase()
        val inputType = (nativeWidget.attributes["inputType"] as? String)?.lowercase()
        return when {
            v == "color" || inputType == "color" ||
                "form-control-color" in nativeWidget.cachedAllClassTokens ||
                "color-picker" in nativeWidget.cachedAllClassTokens -> "color"
            v == "time" || inputType == "time" || placeholderText == "HH:MM" -> "time"
            v == "date" || inputType == "date" || inputType == "datetime-local" || placeholderText == "YYYY-MM-DD" -> "date"
            v == "number" || inputType == "number" -> "number"
            multilineState || v == "textarea" -> "textarea"
            passwordModeState || v == "password" || inputType == "password" -> "password"
            else -> "text"
        }
    }

    private fun createControlForActiveVariant(replaceExisting: Boolean) {
        val kind = resolveVariantKind()
        if (replaceExisting) {
            nativeWidget.buddyHwnd?.let { oldBuddy ->
                Win32ControlMessageRouter.unregisterEntryHwnd(oldBuddy)
                if (IsWindow(oldBuddy) != 0) DestroyWindow(oldBuddy)
                nativeWidget.buddyHwnd = null
            }
            nativeWidget.customPaintCallback = null
            setOnClick(null)
        }

        val win32Class: String
        val style: Int
        val installSub: Boolean
        val isEdit: Boolean
        when (kind) {
            "color" -> {
                win32Class = "GenericUIColorPickerW"
                style = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS
                installSub = false
                isEdit = false
            }
            "date", "time" -> {
                win32Class = "SysDateTimePick32"
                style = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS or
                    if (kind == "time") DTS_TIMEFORMAT else DTS_SHORTDATECENTURYFORMAT
                installSub = true
                isEdit = false
            }
            "textarea" -> {
                win32Class = "Edit"
                style = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_BORDER or WS_VSCROLL or
                    WS_CLIPSIBLINGS or ES_MULTILINE or ES_AUTOVSCROLL or ES_WANTRETURN or
                    (if (readOnlyState) ES_READONLY else 0)
                installSub = true
                isEdit = true
            }
            "number" -> {
                win32Class = "Edit"
                style = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_BORDER or
                    WS_CLIPSIBLINGS or ES_AUTOHSCROLL or ES_NUMBER or
                    (if (readOnlyState) ES_READONLY else 0)
                installSub = true
                isEdit = true
            }
            "password" -> {
                win32Class = "Edit"
                style = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_BORDER or
                    WS_CLIPSIBLINGS or ES_AUTOHSCROLL or ES_PASSWORD or
                    (if (readOnlyState) ES_READONLY else 0)
                installSub = true
                isEdit = true
            }
            else -> {
                win32Class = "Edit"
                style = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_BORDER or
                    WS_CLIPSIBLINGS or ES_AUTOHSCROLL or
                    (if (readOnlyState) ES_READONLY else 0)
                installSub = true
                isEdit = true
            }
        }

        val hwnd = if (replaceExisting) {
            Win32ControlMessageRouter.replaceChildHwndInPlace(
                handle = nativeWidget,
                owner = this,
                newWin32ClassName = win32Class,
                newDwStyle = style,
                initialText = if (isEdit) textValue else "",
                installSubclass = installSub,
                isNativeEditTarget = isEdit
            )
        } else {
            Win32ControlMessageRouter.createChildHwnd(
                handle = nativeWidget,
                owner = this,
                win32ClassName = win32Class,
                dwStyle = style,
                initialText = if (isEdit) textValue else "",
                initialWidth = 180,
                initialHeight = if (kind == "textarea") 96 else 30,
                installSubclass = installSub,
                isNativeEditTarget = isEdit
            )
        }

        if (kind == "number") {
            val parentForBuddy = nativeWidget.parentHwnd ?: Win32ControlMessageRouter.getOrCreateParkingHwnd()
            val hInst = GetModuleHandleW(null)
            val buddy = CreateWindowExW(
                0u,
                "msctls_updown32",
                null,
                (WS_CHILD or WS_VISIBLE or WS_CLIPSIBLINGS or UDS_SETBUDDYINT or UDS_ALIGNRIGHT or UDS_ARROWKEYS).toUInt(),
                0,
                0,
                18,
                30,
                parentForBuddy,
                null,
                hInst,
                null
            )
            if (buddy != null) {
                nativeWidget.buddyHwnd = buddy
                SendMessageW(buddy, UDM_SETBUDDY.toUInt(), hwnd.toLong().toULong(), 0L)
                SendMessageW(buddy, UDM_SETRANGE32.toUInt(), Int.MIN_VALUE.toLong().toULong(), Int.MAX_VALUE.toLong())
                val num = textValue.toIntOrNull() ?: 0
                SendMessageW(buddy, UDM_SETPOS32.toUInt(), 0uL, num.toLong())
            }
        }

        wireVariantCallbacks(kind)
        if (kind == "date" || kind == "time") {
            WindowsDateTimePickerNativeImpl.applyDateTextToControl(hwnd, textValue, kind == "time")
        } else if (isEdit) {
            applyCueBanner()
            if (kind == "password") {
                SendMessageW(hwnd, EM_SETPASSWORDCHAR.toUInt(), 0x25CFuL, 0L)
            }
        }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    private fun wireVariantCallbacks(kind: String) {
        when (kind) {
            "color" -> {
                nativeWidget.customPaintCallback = { h, hdc, w, hgt ->
                    WindowsColorPickerNativeImpl.paintColorSwatchControl(h, hdc, w, hgt, textValue, nativeWidget)
                }
                setOnClick {
                    val chosen = WindowsColorPickerNativeImpl.openWin32ChooseColor(
                        nativeWidget.hwnd ?: Win32NativeWindowHost.activeHwnd,
                        textValue
                    )
                    if (chosen != null && chosen != textValue) {
                        setValue(chosen)
                        onValueChangedListener?.invoke(chosen)
                    }
                }
            }
            "date", "time" -> {
                nativeWidget.onNotifyCallback = { code, nmhdrPtr ->
                    if (!updating && code == DTN_DATETIMECHANGE.toUInt()) {
                        val info = nmhdrPtr.reinterpret<NMDATETIMECHANGE>().pointed
                        if (info.dwFlags == GDT_VALID.toUInt()) {
                            val formatted = WindowsDateTimePickerNativeImpl.formatSystemTime(info.st, kind == "time")
                            if (textValue != formatted) {
                                textValue = formatted
                                nativeWidget.attributes["value"] = formatted
                                onValueChangedListener?.invoke(formatted)
                            }
                        }
                        0L
                    } else {
                        null
                    }
                }
            }
            else -> {
                nativeWidget.onCommandCallback = { notifyCode, _, _ ->
                    if (!updating && notifyCode == EN_CHANGE) {
                        val cur = Win32RetainedControlSupport.readWindowText(nativeWidget.hwnd)
                        if (textValue != cur) {
                            textValue = cur
                            nativeWidget.attributes["value"] = cur
                            onValueChangedListener?.invoke(cur)
                        }
                    }
                }
                nativeWidget.customMessageCallback = { _, msg, wParam, _ ->
                    if (!multilineState && msg.toInt() == WM_KEYDOWN && (wParam and 0xFFFFuL).toInt() == VK_RETURN) {
                        onSubmitListener?.invoke(textValue)
                        0L
                    } else {
                        null
                    }
                }
            }
        }
    }

    private fun applyCueBanner() {
        val hwnd = nativeWidget.hwnd ?: return
        if (nativeWidget.actualWin32ClassName != "Edit") return
        memScoped {
            SendMessageW(hwnd, EM_SETCUEBANNER.toUInt(), 1uL, placeholderText.wcstr.ptr.toLong())
        }
    }

    override fun onVariantChanged(variant: String?) {
        if (nativeWidget.hwnd == null) return
        createControlForActiveVariant(replaceExisting = true)
    }

    override fun setValue(value: String) {
        super.setValue(value)
        updating = true
        try {
            val hwnd = nativeWidget.hwnd
            when (resolveVariantKind()) {
                "date" -> WindowsDateTimePickerNativeImpl.applyDateTextToControl(hwnd, value, false)
                "time" -> WindowsDateTimePickerNativeImpl.applyDateTextToControl(hwnd, value, true)
                "color" -> hwnd?.let { platform.windows.InvalidateRect(it, null, 0) }
                "number" -> {
                    Win32RetainedControlSupport.setWindowTextIfChanged(hwnd, value)
                    nativeWidget.buddyHwnd?.let { b ->
                        value.toIntOrNull()?.let { SendMessageW(b, UDM_SETPOS32.toUInt(), 0uL, it.toLong()) }
                    }
                }
                else -> Win32RetainedControlSupport.setWindowTextIfChanged(hwnd, value)
            }
        } finally {
            updating = false
        }
    }

    override fun setPlaceholder(placeholder: String) {
        super.setPlaceholder(placeholder)
        applyCueBanner()
    }

    override fun setReadOnly(readOnly: Boolean) {
        super.setReadOnly(readOnly)
        if (nativeWidget.actualWin32ClassName == "Edit") {
            nativeWidget.hwnd?.let { SendMessageW(it, EM_SETREADONLY.toUInt(), if (readOnly) 1uL else 0uL, 0L) }
        }
    }

    override fun setPasswordMode(password: Boolean) {
        val prev = passwordModeState
        super.setPasswordMode(password)
        if (prev != password && nativeWidget.hwnd != null) {
            if (nativeWidget.actualWin32ClassName == "Edit") {
                SendMessageW(
                    nativeWidget.hwnd,
                    EM_SETPASSWORDCHAR.toUInt(),
                    if (password) 0x25CFuL else 0uL,
                    0L
                )
                nativeWidget.hwnd?.let { platform.windows.InvalidateRect(it, null, 0) }
            }
        }
    }

    override fun setMultiline(multiline: Boolean) {
        val prev = multilineState
        super.setMultiline(multiline)
        if (prev != multiline && nativeWidget.hwnd != null) {
            createControlForActiveVariant(replaceExisting = true)
        }
    }

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        Win32RetainedControlSupport.syncOsFocusIfNeeded(this, nativeWidget.hwnd, focused)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        when (resolveVariantKind()) {
            "textarea" -> Size(260, 88)
            "color" -> Size(104, 32)
            "date", "time" -> Size(156, 32)
            else -> Size(180, 32)
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
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
