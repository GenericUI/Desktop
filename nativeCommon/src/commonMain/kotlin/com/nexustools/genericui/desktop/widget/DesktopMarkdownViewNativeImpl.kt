package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.nativeimpl.typography.MarkdownViewNativeImpl

/**
 * Shared desktop base implementation of [MarkdownViewNativeImpl].
 */
open class DesktopMarkdownViewNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseNativeImpl<W>(nativeWidget, "markdown"), MarkdownViewNativeImpl<W> {

    protected var markdownSource: String = ""

    override fun setMarkdown(markdown: String) {
        markdownSource = markdown
        nativeWidget.attributes["markdown"] = markdown
    }

    override fun getMarkdown(): String = markdownSource
}
