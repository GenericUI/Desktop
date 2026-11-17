package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.render.Win32RenderContext
import com.nexustools.genericui.windows.render.Win32TextRenderUtil

/**
 * Win32 native implementation for `Text`, headings (`H1`..`H6`), `Badge`, and inline typography.
 */
class WindowsTextNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("WC_STATICW"),
    tagName: String = "span",
    classPrefix: String = ""
) : DesktopTextNativeImpl<Win32HwndHandle>(widget, tagName, classPrefix), WindowsRenderableNativeImpl {

    init {
        nativeWidget.attributes["nativeImpl"] = this
    }

    override fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int?
    ): Int = Win32TextRenderUtil.render(
        ctx = ctx,
        widget = nativeWidget,
        left = left,
        top = top,
        right = right
    )
}
