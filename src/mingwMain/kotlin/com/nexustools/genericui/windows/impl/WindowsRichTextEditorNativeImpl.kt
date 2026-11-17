package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32InputFieldRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for `RichTextEditor` controls.
 */
class WindowsRichTextEditorNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("RICHEDIT50W")
) : DesktopTextInputNativeImpl<Win32HwndHandle>(widget, multiline = true), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
        nativeWidget.attributes["componentRole"] = "rich-text-editor"
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32InputFieldRenderUtil.renderMultilineEditor(
        ctx = ctx,
        widget = nativeWidget,
        inputImpl = this,
        left = left,
        top = top,
        right = right
    )
}
