package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.enums.bootstrap.TextTransform
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.typography.MarkdownViewNativeImpl
import com.nexustools.genericui.style.color.BootstrapColorPalette
import java.awt.Color
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JEditorPane
import kotlin.math.max

/**
 * Java Swing `JEditorPane("text/html")` implementation of [MarkdownViewNativeImpl] with real
 * Markdown-to-HTML rendering, light/dark theme integration, transparent surface background,
 * and width-aware wrapped height measurement so content does not clip.
 */
class SwingMarkdownViewImpl : SwingBaseNativeImpl(
    ResponsiveMarkdownEditorPane(),
    "markdown-body"
), MarkdownViewNativeImpl<JComponent> {

    override val nativeWidget: JEditorPane get() = super.nativeWidget as JEditorPane
    private var rawMarkdown: String = ""
    private var isMeasuringPreferredSize: Boolean = false

    init {
        (nativeWidget as ResponsiveMarkdownEditorPane).owner = this
        nativeWidget.contentType = "text/html"
        nativeWidget.isEditable = false
        nativeWidget.isOpaque = false
        nativeWidget.background = Color(0, 0, 0, 0)
        nativeWidget.border = BorderFactory.createEmptyBorder()
        nativeWidget.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        syncSwingAppearance()
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        nativeWidget.isOpaque = false
        if (customBackgroundColor == null && lastComponentStyle?.backgroundColor == null && lastComputedStyle?.backgroundColor == null) {
            nativeWidget.background = Color(0, 0, 0, 0)
        }
        renderMarkdownHtml()
    }

    internal fun measureWrappedSize(availableWidthPx: Int): Size {
        val w = availableWidthPx.coerceAtLeast(48)
        if (isMeasuringPreferredSize) {
            val rawPref = (nativeWidget as? ResponsiveMarkdownEditorPane)?.rawSuperPreferredSize()
                ?: Dimension(w, 24)
            return Size(width = w, height = rawPref.height.coerceAtLeast(24))
        }
        isMeasuringPreferredSize = true
        return try {
            val prev = nativeWidget.size
            nativeWidget.setSize(w, Short.MAX_VALUE.toInt())
            val pref = (nativeWidget as? ResponsiveMarkdownEditorPane)?.rawSuperPreferredSize()
                ?: nativeWidget.preferredSize
            if (prev.width > 0 || prev.height > 0) {
                nativeWidget.size = prev
            }
            Size(
                width = w,
                height = pref.height.coerceAtLeast(24)
            )
        } finally {
            isMeasuringPreferredSize = false
        }
    }

    private fun renderMarkdownHtml() {
        val compStyle = lastComponentStyle
        val computed = lastComputedStyle
        val isDark = SwingSurfacePaintUtil.isDarkThemeForComponent(nativeWidget)
        val activeConfig = BootstrapColorPalette.activeThemeConfig
        val themeColors = activeConfig.resolveEffectiveColors(isDark)
        val textColorType = styleSnapshot.textColorType
        val bootColorHex = if (textColorType != null && textColorType != BootstrapType.NONE) {
            BootstrapColorPalette.resolveColor(textColorType, isDark, activeConfig).toHex(includeAlpha = false)
        } else {
            null
        }
        val readableColor = SwingSurfacePaintUtil.resolveInheritedReadableTextColor(nativeWidget, isDark)
        val readableHex = String.format("#%02x%02x%02x", readableColor.red, readableColor.green, readableColor.blue)
        val defaultHex = (themeColors.onSurface ?: themeColors.onBackground)?.toHex(includeAlpha = false)
            ?: readableHex
        val colorCss = compStyle?.getTextColor()?.toHex(includeAlpha = false)
            ?: computed?.textColor?.toHex(includeAlpha = false)
            ?: bootColorHex
            ?: defaultHex
        val fontFamily = compStyle?.getFontFamily()?.resolvedPrimaryName() ?: computed?.fontFamily ?: "SansSerif"
        val fontSizePx = compStyle?.fontSizePx ?: computed?.fontSizePx ?: 14f

        val compAlign = compStyle?.getTextAlign()
        val computedAlign = computed?.textAlign?.trim()?.lowercase()
        val bootAlign = styleSnapshot.textAlignment
        val alignCss = when {
            compAlign == TextAlignment.CENTER || computedAlign == "center" || bootAlign == TextAlignment.CENTER -> "center"
            compAlign == TextAlignment.END || computedAlign == "right" || computedAlign == "end" || bootAlign == TextAlignment.END -> "right"
            compAlign == TextAlignment.JUSTIFY || computedAlign == "justify" || bootAlign == TextAlignment.JUSTIFY -> "justify"
            else -> "left"
        }

        val textTransform = compStyle?.getTextTransform() ?: computed?.textTransform ?: styleSnapshot.textTransform
        val safeMarkdown = (rawMarkdown as String?) ?: ""
        val transformedMd = when (textTransform) {
            TextTransform.UPPERCASE -> safeMarkdown.uppercase()
            TextTransform.LOWERCASE -> safeMarkdown.lowercase()
            TextTransform.CAPITALIZE -> safeMarkdown.split(" ").joinToString(" ") { w ->
                w.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
            }
            else -> safeMarkdown
        }

        val codeBg = if (isDark) "#2a2e39" else "#f1f3f5"
        val quoteBorder = if (isDark) "#4f5970" else "#ced4da"
        val linkColor = if (isDark) "#74b3ff" else "#0d6efd"
        val fontSizeCss = if (fontSizePx == fontSizePx.toInt().toFloat()) {
            "${fontSizePx.toInt()}px"
        } else {
            "${fontSizePx}px"
        }
        val bodyHtml = markdownToHtml(transformedMd)
        val html = """
            <html>
              <head>
                <style>
                  body { color: $colorCss; font-family: $fontFamily; font-size: $fontSizeCss; text-align: $alignCss; margin: 0; padding: 0; }
                  h1, h2, h3, h4, h5, h6 { margin: 8px 0 4px 0; font-weight: bold; }
                  h1 { font-size: 1.75em; }
                  h2 { font-size: 1.45em; }
                  h3 { font-size: 1.25em; }
                  p { margin: 4px 0 8px 0; }
                  ul, ol { margin: 4px 0 8px 18px; }
                  code { background-color: $codeBg; padding: 1px 4px; font-family: monospace; }
                  pre { background-color: $codeBg; padding: 8px; margin: 6px 0; font-family: monospace; }
                  blockquote { border-left: 3px solid $quoteBorder; padding-left: 10px; margin: 6px 0; }
                  a { color: $linkColor; }
                </style>
              </head>
              <body>$bodyHtml</body>
            </html>
        """.trimIndent()
        nativeWidget.text = html
        nativeWidget.putClientProperty("renderedHtml", html)
        nativeWidget.caretPosition = 0
        invalidateLayoutHierarchy()
    }

    override fun setMarkdown(markdown: String) {
        rawMarkdown = markdown
        renderMarkdownHtml()
    }

    override fun getMarkdown(): String = rawMarkdown

    companion object {
        private val LINK_REGEX = Regex("""\[([^\]]+)]\(([^)]+)\)""")
        private val CODE_INLINE_REGEX = Regex("""`([^`]+)`""")
        private val BOLD_REGEX = Regex("""\*\*(.+?)\*\*|__(.+?)__""")
        private val ITALIC_REGEX = Regex("""(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)|(?<!_)_(?!_)(.+?)(?<!_)_(?!_)""")
        private val STRIKE_REGEX = Regex("""~~(.+?)~~""")
        private val HEADING_REGEX = Regex("""^(#{1,6})\s+(.*)$""")
        private val ORDERED_REGEX = Regex("""^\d+\.\s+(.*)$""")

        internal fun markdownToHtml(md: String): String {
            if (md.isEmpty()) return ""
            val sb = StringBuilder()
            val lines = md.split('\n')
            var inCodeBlock = false
            var inUl = false
            var inOl = false

            fun closeLists() {
                if (inUl) { sb.append("</ul>"); inUl = false }
                if (inOl) { sb.append("</ol>"); inOl = false }
            }

            for (rawLine in lines) {
                val trimmed = rawLine.trim()
                if (trimmed.startsWith("```")) {
                    closeLists()
                    if (!inCodeBlock) {
                        sb.append("<pre><code>")
                        inCodeBlock = true
                    } else {
                        sb.append("</code></pre>")
                        inCodeBlock = false
                    }
                    continue
                }
                if (inCodeBlock) {
                    sb.append(escapeHtml(rawLine)).append('\n')
                    continue
                }
                if (trimmed.isEmpty()) {
                    closeLists()
                    continue
                }
                if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
                    closeLists()
                    sb.append("<hr/>")
                    continue
                }
                val hMatch = HEADING_REGEX.matchEntire(trimmed)
                if (hMatch != null) {
                    closeLists()
                    val level = hMatch.groupValues[1].length.coerceIn(1, 6)
                    sb.append("<h$level>").append(formatInline(hMatch.groupValues[2])).append("</h$level>")
                    continue
                }
                if (trimmed.startsWith("> ")) {
                    closeLists()
                    sb.append("<blockquote>").append(formatInline(trimmed.drop(2))).append("</blockquote>")
                    continue
                }
                if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                    if (inOl) { sb.append("</ol>"); inOl = false }
                    if (!inUl) { sb.append("<ul>"); inUl = true }
                    sb.append("<li>").append(formatInline(trimmed.drop(2))).append("</li>")
                    continue
                }
                val olMatch = ORDERED_REGEX.matchEntire(trimmed)
                if (olMatch != null) {
                    if (inUl) { sb.append("</ul>"); inUl = false }
                    if (!inOl) { sb.append("<ol>"); inOl = true }
                    sb.append("<li>").append(formatInline(olMatch.groupValues[1])).append("</li>")
                    continue
                }
                closeLists()
                sb.append("<p>").append(formatInline(trimmed)).append("</p>")
            }
            closeLists()
            if (inCodeBlock) {
                sb.append("</code></pre>")
            }
            return sb.toString()
        }

        private fun formatInline(text: String): String {
            var escaped = escapeHtml(text)
            escaped = CODE_INLINE_REGEX.replace(escaped) { "<code>${it.groupValues[1]}</code>" }
            escaped = LINK_REGEX.replace(escaped) { "<a href=\"${it.groupValues[2]}\">${it.groupValues[1]}</a>" }
            escaped = BOLD_REGEX.replace(escaped) {
                val content = it.groupValues[1].ifEmpty { it.groupValues[2] }
                "<b>$content</b>"
            }
            escaped = ITALIC_REGEX.replace(escaped) {
                val content = it.groupValues[1].ifEmpty { it.groupValues[2] }
                "<i>$content</i>"
            }
            escaped = STRIKE_REGEX.replace(escaped) { "<s>${it.groupValues[1]}</s>" }
            return escaped
        }

        private fun escapeHtml(raw: String): String =
            raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
    }

    private class ResponsiveMarkdownEditorPane : JEditorPane("text/html", "") {
        var owner: SwingMarkdownViewImpl? = null

        fun rawSuperPreferredSize(): Dimension = super.getPreferredSize()

        override fun getPreferredSize(): Dimension {
            val impl = owner ?: return super.getPreferredSize()
            if (impl.isMeasuringPreferredSize) return super.getPreferredSize()
            val explicitW = impl.getExplicitWidthPx()
            val explicitH = impl.getExplicitHeightPx()
            val refW = explicitW ?: parent?.width?.takeIf { it > 0 } ?: 420
            val sz = impl.measureWrappedSize(refW)
            return Dimension(
                explicitW ?: max(sz.width, 48),
                explicitH ?: max(sz.height, 24)
            )
        }
    }
}
