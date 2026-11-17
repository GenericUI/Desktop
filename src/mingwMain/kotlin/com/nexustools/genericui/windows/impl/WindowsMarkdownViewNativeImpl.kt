package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopMarkdownViewNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32RenderContext
import com.nexustools.genericui.windows.render.Win32TextRenderUtil

/**
 * Win32 native implementation for `MarkdownView`.
 */
class WindowsMarkdownViewNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("RICHEDIT50W_MARKDOWN")
) : DesktopMarkdownViewNativeImpl<Win32HwndHandle>(widget), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32TextRenderUtil.renderMarkdownView(
        ctx = ctx,
        widget = nativeWidget,
        markdownImpl = this,
        left = left,
        top = top,
        right = right
    )
}
