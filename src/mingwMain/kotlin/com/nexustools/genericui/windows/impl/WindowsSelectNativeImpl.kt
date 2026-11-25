@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toLong
import kotlinx.cinterop.wcstr
import platform.windows.CBN_EDITCHANGE
import platform.windows.CBN_SELCHANGE
import platform.windows.CBS_AUTOHSCROLL
import platform.windows.CBS_DROPDOWN
import platform.windows.CBS_DROPDOWNLIST
import platform.windows.CB_ADDSTRING
import platform.windows.CB_GETCURSEL
import platform.windows.CB_RESETCONTENT
import platform.windows.CB_SETITEMHEIGHT
import platform.windows.CB_SETCURSEL
import platform.windows.SendMessageW
import platform.windows.SetWindowPos
import platform.windows.SWP_NOACTIVATE
import platform.windows.SWP_NOMOVE
import platform.windows.SWP_NOZORDER
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE
import platform.windows.WS_VSCROLL
import kotlin.math.max

/**
 * Retained Win32 `"ComboBox"` implementation (`CBS_DROPDOWNLIST` for `Select`, `CBS_DROPDOWN` when
 * [isComboBoxVariant] is `true`), honoring Win32's dropdown height contract (`cy = maxOf(h, 260)` and
 * `CB_SETITEMHEIGHT(-1, h - 6)`).
 */
class WindowsSelectNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("ComboBox")
) : DesktopSelectNativeImpl<Win32HwndHandle>(widget) {

    private var updatingControl: Boolean = false

    init {
        nativeWidget.attributes["nativeImpl"] = this
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "ComboBox",
            dwStyle = resolveComboStyle(),
            initialText = "",
            initialWidth = 140,
            initialHeight = 260,
            installSubclass = true,
            isNativeEditTarget = isComboBoxVariant()
        )
        wireCommands()
        syncOptionsToComboBox()
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    private fun resolveComboStyle(): Int {
        val base = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_VSCROLL or WS_CLIPSIBLINGS
        return if (isComboBoxVariant()) {
            base or CBS_DROPDOWN or CBS_AUTOHSCROLL
        } else {
            base or CBS_DROPDOWNLIST
        }
    }

    private fun wireCommands() {
        nativeWidget.onCommandCallback = { notifyCode, _, _ ->
            if (!updatingControl) {
                val hwnd = nativeWidget.hwnd
                if (hwnd != null) {
                    if (notifyCode == CBN_SELCHANGE) {
                        val idx = SendMessageW(hwnd, CB_GETCURSEL.toUInt(), 0uL, 0L).toInt()
                        if (idx in optionsList.indices) {
                            notifyUserSelectionChanged(optionsList[idx].first)
                        }
                    } else if (notifyCode == CBN_EDITCHANGE && isComboBoxVariant()) {
                        val typed = Win32RetainedControlSupport.readWindowText(hwnd)
                        notifyUserSelectionChanged(typed)
                    }
                }
            }
        }
    }

    private fun syncOptionsToComboBox() {
        val hwnd = nativeWidget.hwnd ?: return
        updatingControl = true
        try {
            SendMessageW(hwnd, CB_RESETCONTENT.toUInt(), 0uL, 0L)
            for ((_, label) in optionsList) {
                memScoped {
                    SendMessageW(hwnd, CB_ADDSTRING.toUInt(), 0uL, label.wcstr.ptr.toLong())
                }
            }
            syncSelectionToComboBox()
        } finally {
            updatingControl = false
        }
    }

    private fun syncSelectionToComboBox() {
        val hwnd = nativeWidget.hwnd ?: return
        updatingControl = true
        try {
            val sel = selectedVal
            val matchIdx = if (sel != null) optionsList.indexOfFirst { it.first == sel } else -1
            SendMessageW(hwnd, CB_SETCURSEL.toUInt(), matchIdx.toLong().toULong(), 0L)
            if (isComboBoxVariant() && matchIdx < 0 && sel != null) {
                Win32RetainedControlSupport.setWindowTextIfChanged(hwnd, sel)
            }
        } finally {
            updatingControl = false
        }
    }

    override fun onVariantChanged(variant: String?) {
        if (nativeWidget.hwnd == null) return
        val isCombo = isComboBoxVariant()
        Win32ControlMessageRouter.replaceChildHwndInPlace(
            handle = nativeWidget,
            owner = this,
            newWin32ClassName = "ComboBox",
            newDwStyle = resolveComboStyle(),
            initialText = if (isCombo) (selectedVal ?: "") else "",
            installSubclass = true,
            isNativeEditTarget = isCombo
        )
        wireCommands()
        syncOptionsToComboBox()
    }

    override fun setOptions(options: List<Pair<String, String>>) {
        super.setOptions(options)
        syncOptionsToComboBox()
    }

    override fun setSelectedValue(value: String?) {
        super.setSelectedValue(value)
        syncSelectionToComboBox()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        var maxW = 96
        for ((_, label) in optionsList) {
            val measured = Win32RetainedControlSupport.measureTextSize(
                text = label,
                fontHandle = nativeWidget.fontHandle,
                extraW = 38,
                extraH = 12,
                minW = 96,
                minH = 30
            )
            maxW = max(maxW, measured.width)
        }
        return Size(maxW, 32)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        Win32RetainedControlSupport.onLeafBoundsChanged(nativeWidget, rect)
        val hwnd = nativeWidget.hwnd ?: return
        val layoutH = rect.height.coerceAtLeast(24)
        SetWindowPos(
            hwnd,
            null,
            0,
            0,
            rect.width.coerceAtLeast(1),
            maxOf(layoutH, 260),
            (SWP_NOMOVE or SWP_NOZORDER or SWP_NOACTIVATE).toUInt()
        )
        SendMessageW(hwnd, CB_SETITEMHEIGHT.toUInt(), (-1L).toULong(), (layoutH - 6).coerceAtLeast(16).toLong())
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
