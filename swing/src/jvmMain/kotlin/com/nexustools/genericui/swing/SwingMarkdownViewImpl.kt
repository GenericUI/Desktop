package com.nexustools.genericui.swing

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.enums.bootstrap.TextOverflow
import com.nexustools.genericui.enums.bootstrap.TextTransform
import com.nexustools.genericui.enums.css.CssFontStyle
import com.nexustools.genericui.enums.css.CssTextDecoration
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.nativeimpl.typography.MarkdownViewNativeImpl
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.theme.SystemThemeDetector
import javax.swing.JComponent
import javax.swing.JEditorPane

/**
 * Java Swing `JEditorPane("text/html")` implementation of [MarkdownViewNativeImpl].
 *
 * Honors [com.nexustools.genericui.style.css.ComponentStyle] typography and text color properties
 * (`color` / `textColor`, `fontFamily`, `fontSize`, `fontWeight`, `fontStyle`, `lineHeight`,
 * `letterSpacing`, `textAlign`, `textDecoration`, `textTransform`, `textOverflow`, `whiteSpace`,
 * `wordBreak`) and [com.nexustools.genericui.style.bootstrap.ComponentBootstrap].
 */
class SwingMarkdownViewImpl : SwingBaseNativeImpl(JEditorPane("text/html", ""), "markdown-body"), MarkdownViewNativeImpl<JComponent> {
    override val nativeWidget: JEditorPane get() = super.nativeWidget as JEditorPane
    private var rawMarkdown: String = ""

    init {
        nativeWidget.isEditable = false
        nativeWidget.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        syncSwingAppearance()
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        nativeWidget.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        renderMarkdownHtml()
    }

    private fun renderMarkdownHtml() {
        val compStyle = lastComponentStyle
        val computed = lastComputedStyle
        val isDark = (Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()) == ThemeMode.DARK
        val textColorType = styleSnapshot.textColorType ?: styleSnapshot.type
        val bootColorHex = if (textColorType != null && textColorType != BootstrapType.NONE) {
            BootstrapColorPalette.resolveColor(textColorType, isDark, BootstrapColorPalette.activeThemeConfig).toHex()
        } else null

        val colorCss = compStyle?.getTextColor()?.toHex() ?: computed?.textColor?.toHex() ?: bootColorHex
        val fontFamily = compStyle?.getFontFamily()?.resolvedPrimaryName() ?: computed?.fontFamily
        val fontSizePx = compStyle?.fontSizePx ?: computed?.fontSizePx
            ?: styleSnapshot.fontSize?.let {
                when (it.cssClass) {
                    "fs-1" -> 36f
                    "fs-2" -> 30f
                    "fs-3" -> 24f
                    "fs-4" -> 20f
                    "fs-5" -> 18f
                    "fs-6" -> 16f
                    else -> 14f
                }
            }
        val fontWeightNum = compStyle?.fontWeightNumeric ?: computed?.fontWeightNumeric
        val fontStyle = compStyle?.getFontStyle() ?: computed?.fontStyle
        val lineHeight = (compStyle?.getLineHeight() ?: computed?.lineHeight)?.toCssString()
        val letterSpacing = (compStyle?.getLetterSpacing() ?: computed?.letterSpacing)?.toCssString()
        val compAlign = compStyle?.getTextAlign()
        val computedAlign = computed?.textAlign
        val bootAlign = styleSnapshot.textAlignment
        val textDecoration = compStyle?.getTextDecoration() ?: computed?.textDecoration
        val textTransform = compStyle?.getTextTransform() ?: computed?.textTransform ?: styleSnapshot.textTransform
        val textOverflow = compStyle?.getTextOverflow() ?: computed?.textOverflow ?: styleSnapshot.textOverflow
        val whiteSpace = compStyle?.getWhiteSpace()?.cssValue ?: computed?.whiteSpace
        val wordBreak = compStyle?.getWordBreak() ?: computed?.wordBreak

        val cssRules = mutableListOf<String>()
        if (!colorCss.isNullOrBlank()) cssRules += "color: $colorCss"
        if (!fontFamily.isNullOrBlank()) cssRules += "font-family: $fontFamily"
        if (fontSizePx != null) cssRules += "font-size: ${fontSizePx.toInt()}px"
        when {
            fontWeightNum != null -> cssRules += "font-weight: $fontWeightNum"
            styleSnapshot.fontWeight != null -> cssRules += "font-weight: ${if (styleSnapshot.fontWeight?.cssClass?.contains("bold") == true) "bold" else "normal"}"
        }
        if (fontStyle == CssFontStyle.ITALIC) {
            cssRules += "font-style: italic"
        } else if (fontStyle == CssFontStyle.OBLIQUE) {
            cssRules += "font-style: oblique"
        }
        if (!lineHeight.isNullOrBlank()) cssRules += "line-height: $lineHeight"
        if (!letterSpacing.isNullOrBlank()) cssRules += "letter-spacing: $letterSpacing"
        when {
            compAlign == TextAlignment.CENTER || computedAlign == "center" || bootAlign == TextAlignment.CENTER -> cssRules += "text-align: center"
            compAlign == TextAlignment.END || computedAlign == "right" || computedAlign == "end" || bootAlign == TextAlignment.END -> cssRules += "text-align: right"
            compAlign == TextAlignment.JUSTIFY || computedAlign == "justify" || bootAlign == TextAlignment.JUSTIFY -> cssRules += "text-align: justify"
            compAlign == TextAlignment.START || computedAlign == "left" || bootAlign == TextAlignment.START -> cssRules += "text-align: left"
        }
        when (textDecoration) {
            CssTextDecoration.UNDERLINE -> cssRules += "text-decoration: underline"
            CssTextDecoration.LINE_THROUGH -> cssRules += "text-decoration: line-through"
            CssTextDecoration.NONE -> cssRules += "text-decoration: none"
            else -> {}
        }
        when (textTransform) {
            TextTransform.UPPERCASE -> cssRules += "text-transform: uppercase"
            TextTransform.LOWERCASE -> cssRules += "text-transform: lowercase"
            TextTransform.CAPITALIZE -> cssRules += "text-transform: capitalize"
            else -> {}
        }
        if (textOverflow == TextOverflow.ELLIPSIS) cssRules += "text-overflow: ellipsis"
        if (!whiteSpace.isNullOrBlank()) cssRules += "white-space: $whiteSpace"
        if (wordBreak != null) cssRules += "word-break: ${wordBreak.cssValue}"

        val safeMarkdown = (rawMarkdown as String?) ?: ""
        val transformedBody = when (textTransform) {
            TextTransform.UPPERCASE -> safeMarkdown.uppercase()
            TextTransform.LOWERCASE -> safeMarkdown.lowercase()
            TextTransform.CAPITALIZE -> safeMarkdown.split(' ').joinToString(" ") { w ->
                if (w.isEmpty()) w else w.replaceFirstChar { it.uppercaseChar() }
            }
            else -> safeMarkdown
        }.replace("\n", "<br/>")

        val styleAttr = if (cssRules.isNotEmpty()) " style=\"${cssRules.joinToString("; ")}\"" else ""
        val html = "<html><body$styleAttr>$transformedBody</body></html>"
        nativeWidget.putClientProperty("renderedHtml", html)
        nativeWidget.text = html
    }

    override fun setMarkdown(markdown: String) {
        rawMarkdown = markdown
        renderMarkdownHtml()
    }

    override fun getMarkdown(): String = rawMarkdown
}
