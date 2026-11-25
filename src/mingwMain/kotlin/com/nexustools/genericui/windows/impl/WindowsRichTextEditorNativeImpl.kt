@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import platform.windows.EM_SETREADONLY
import platform.windows.EN_CHANGE
import platform.windows.ES_AUTOVSCROLL
import platform.windows.ES_MULTILINE
import platform.windows.ES_READONLY
import platform.windows.ES_WANTRETURN
import platform.windows.SendMessageW
import platform.windows.WS_BORDER
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_TABSTOP
import platform.windows.WS_VISIBLE
import platform.windows.WS_VSCROLL

/**
 * Retained Win32 `"RICHEDIT50W"` (`Msftedit.dll`) rich text editor implementation.
 */
class WindowsRichTextEditorNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("RICHEDIT50W")
) : DesktopTextInputNativeImpl<Win32HwndHandle>(widget, multiline = true) {

    private var updating: Boolean = false

    init {
        nativeWidget.attributes["nativeImpl"] = this
        val style = WS_CHILD or WS_VISIBLE or WS_TABSTOP or WS_BORDER or WS_VSCROLL or
            WS_CLIPSIBLINGS or ES_MULTILINE or ES_AUTOVSCROLL or ES_WANTRETURN or
            (if (readOnlyState) ES_READONLY else 0)
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "RICHEDIT50W",
            dwStyle = style,
            initialText = textValue,
            initialWidth = 320,
            initialHeight = 160,
            installSubclass = true,
            isNativeEditTarget = true
        )
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
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = false)
    }

    override fun setValue(value: String) {
        super.setValue(value)
        updating = true
        try {
            Win32RetainedControlSupport.setWindowTextIfChanged(nativeWidget.hwnd, value)
        } finally {
            updating = false
        }
    }

    override fun setReadOnly(readOnly: Boolean) {
        super.setReadOnly(readOnly)
        nativeWidget.hwnd?.let {
            SendMessageW(it, EM_SETREADONLY.toUInt(), if (readOnly) 1uL else 0uL, 0L)
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size = Size(320, 140)

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
