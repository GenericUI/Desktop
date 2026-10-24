package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.typography.MarkdownViewNativeImpl
import javax.swing.JComponent
import javax.swing.JEditorPane

/**
 * Java Swing `JEditorPane("text/html")` implementation of [MarkdownViewNativeImpl].
 */
class SwingMarkdownViewImpl : SwingBaseNativeImpl(JEditorPane("text/html", ""), "markdown-body"), MarkdownViewNativeImpl<JComponent> {
    override val nativeWidget: JEditorPane = super.nativeWidget as JEditorPane
    private var rawMarkdown: String = ""

    init {
        nativeWidget.isEditable = false
    }

    override fun setMarkdown(markdown: String) {
        rawMarkdown = markdown
        nativeWidget.text = "<html><body>${markdown.replace("\n", "<br/>")}</body></html>"
    }

    override fun getMarkdown(): String = rawMarkdown
}
