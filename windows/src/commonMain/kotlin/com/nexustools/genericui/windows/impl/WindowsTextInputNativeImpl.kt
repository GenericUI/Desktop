package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.render.Win32InputFieldRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for single-line and multiline `TextField` / `TextArea` input controls.
 */
class WindowsTextInputNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("WC_EDITW"),
    multiline: Boolean = false
) : DesktopTextInputNativeImpl<Win32HwndHandle>(widget, multiline), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int {
        val inputType = (nativeWidget.attributes["inputType"] as? String)?.lowercase().orEmpty()
        val placeholder = getPlaceholder()
        return when {
            inputType == "color" ||
                Win32NativeWindowHost.widgetHasClass(nativeWidget, "form-control-color") ||
                Win32NativeWindowHost.widgetHasClass(nativeWidget, "color-picker") ->
                Win32InputFieldRenderUtil.renderColorPicker(ctx, nativeWidget, this, left, top, right, fixedHeight ?: 30)
            inputType == "date" ||
                inputType == "time" ||
                inputType == "datetime-local" ||
                placeholder == "YYYY-MM-DD" ||
                placeholder == "HH:MM" ->
                Win32InputFieldRenderUtil.renderDateTimePicker(ctx, nativeWidget, this, left, top, right, fixedHeight ?: 30)
            isMultiline() ||
                Win32NativeWindowHost.widgetHasClass(nativeWidget, "code-editor") ||
                Win32NativeWindowHost.widgetHasClass(nativeWidget, "rich-text-editor") ->
                Win32InputFieldRenderUtil.renderMultilineEditor(ctx, nativeWidget, this, left, top, right)
            else -> Win32InputFieldRenderUtil.renderSingleLineInput(
                ctx = ctx,
                widget = nativeWidget,
                inputImpl = this,
                left = left,
                top = top,
                right = right,
                height = fixedHeight ?: 30
            )
        }
    }
}
