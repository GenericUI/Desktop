package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32InputFieldRenderUtil
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 native implementation for `CodeEditor` controls with line-number gutter.
 */
class WindowsCodeEditorNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUICodeEditorClass")
) : DesktopTextInputNativeImpl<Win32HwndHandle>(widget, multiline = true), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
        nativeWidget.attributes["componentRole"] = "code-editor"
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
