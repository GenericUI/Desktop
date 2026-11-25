@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toLong
import platform.windows.DTM_GETSYSTEMTIME
import platform.windows.DTM_SETSYSTEMTIME
import platform.windows.DTN_DATETIMECHANGE
import platform.windows.DTS_SHORTDATECENTURYFORMAT
import platform.windows.DTS_TIMEFORMAT
import platform.windows.GDT_VALID
import platform.windows.HWND
import platform.windows.NMDATETIMECHANGE
import platform.windows.SYSTEMTIME
import platform.windows.SendMessageW
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE

/**
 * Retained Win32 ComCtl32 `"SysDateTimePick32"` (`DTS_SHORTDATECENTURYFORMAT` / `DTS_TIMEFORMAT`)
 * implementation for `DatePicker` and `TimePicker`.
 */
class WindowsDateTimePickerNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("SysDateTimePick32")
) : DesktopTextInputNativeImpl<Win32HwndHandle>(widget, multiline = false) {

    private var updating: Boolean = false

    init {
        nativeWidget.attributes["nativeImpl"] = this
        val isTime = resolveIsTimeMode()
        val style = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS or
            if (isTime) DTS_TIMEFORMAT else DTS_SHORTDATECENTURYFORMAT
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "SysDateTimePick32",
            dwStyle = style,
            initialWidth = 150,
            initialHeight = 30,
            installSubclass = true
        )
        wireNotifications()
        applyDateTextToControl(nativeWidget.hwnd, textValue, isTime)
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    private fun resolveIsTimeMode(): Boolean {
        val typeAttr = (nativeWidget.attributes["inputType"] as? String)?.lowercase()
        val variant = resolveControlVariant()?.lowercase()
        return typeAttr == "time" || variant == "time" || variant == "timepicker" || placeholderText == "HH:MM"
    }

    private fun wireNotifications() {
        nativeWidget.onNotifyCallback = { code, nmhdrPtr ->
            if (!updating && code == DTN_DATETIMECHANGE.toUInt()) {
                val info = nmhdrPtr.reinterpret<NMDATETIMECHANGE>().pointed
                if (info.dwFlags == GDT_VALID.toUInt()) {
                    val formatted = formatSystemTime(info.st, resolveIsTimeMode())
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

    override fun setValue(value: String) {
        super.setValue(value)
        updating = true
        try {
            applyDateTextToControl(nativeWidget.hwnd, value, resolveIsTimeMode())
        } finally {
            updating = false
        }
    }

    override fun onVariantChanged(variant: String?) {
        if (nativeWidget.hwnd == null) return
        val isTime = resolveIsTimeMode()
        Win32ControlMessageRouter.replaceChildHwndInPlace(
            handle = nativeWidget,
            owner = this,
            newWin32ClassName = "SysDateTimePick32",
            newDwStyle = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_CLIPSIBLINGS or
                if (isTime) DTS_TIMEFORMAT else DTS_SHORTDATECENTURYFORMAT,
            installSubclass = true
        )
        wireNotifications()
        applyDateTextToControl(nativeWidget.hwnd, textValue, isTime)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size = Size(156, 32)

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
        internal fun applyDateTextToControl(hwnd: HWND?, value: String, isTime: Boolean) {
            if (hwnd == null || value.isBlank()) return
            memScoped {
                val st = alloc<SYSTEMTIME>()
                SendMessageW(hwnd, DTM_GETSYSTEMTIME.toUInt(), 0uL, st.ptr.toLong())
                if (isTime) {
                    val parts = value.trim().split(":")
                    val h = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: return@memScoped
                    val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    val s = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
                    st.wHour = h.toUShort()
                    st.wMinute = m.toUShort()
                    st.wSecond = s.toUShort()
                } else {
                    val datePart = value.trim().substringBefore('T').substringBefore(' ')
                    val parts = datePart.split("-")
                    val y = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(1900, 9999) ?: return@memScoped
                    val m = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 12) ?: return@memScoped
                    val d = parts.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 31) ?: return@memScoped
                    st.wYear = y.toUShort()
                    st.wMonth = m.toUShort()
                    st.wDay = d.toUShort()
                }
                SendMessageW(hwnd, DTM_SETSYSTEMTIME.toUInt(), GDT_VALID.toULong(), st.ptr.toLong())
            }
        }

        internal fun formatSystemTime(st: SYSTEMTIME, isTime: Boolean): String =
            if (isTime) {
                "${st.wHour.toString().padStart(2, '0')}:${st.wMinute.toString().padStart(2, '0')}"
            } else {
                val y = st.wYear.toString().padStart(4, '0')
                val m = st.wMonth.toString().padStart(2, '0')
                val d = st.wDay.toString().padStart(2, '0')
                "$y-$m-$d"
            }
    }
}
