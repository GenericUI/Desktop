package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.enums.bootstrap.TextTransform
import com.nexustools.genericui.enums.css.CssTextDecoration
import com.nexustools.genericui.nativeimpl.typography.TextNativeImpl
import java.awt.Font
import java.awt.font.TextAttribute
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingConstants

/**
 * Java Swing `JLabel` implementation of [TextNativeImpl].
 *
 * Honors [com.nexustools.genericui.style.css.ComponentStyle] typography and text color properties
 * (`color` / `textColor`, `fontFamily`, `fontSize`, `fontWeight`, `fontStyle`, `lineHeight`,
 * `letterSpacing`, `textAlign`, `textDecoration`, `textTransform`, `textOverflow`, `whiteSpace`,
 * `wordBreak`) and [com.nexustools.genericui.style.bootstrap.ComponentBootstrap].
 */
class SwingTextImpl(
    tagName: String = "span",
    classPrefix: String = ""
) : SwingBaseNativeImpl(JLabel(), classPrefix), TextNativeImpl<JComponent> {
    override val nativeWidget: JLabel get() = super.nativeWidget as JLabel
    private var rawText: String = ""
    private var maxLinesVal: Int? = null

    init {
        nativeWidget.putClientProperty("tagName", tagName)
        syncSwingAppearance()
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        val compStyle = lastComponentStyle
        val computed = lastComputedStyle

        // Resolve textAlign / bootstrap textAlignment
        val compAlign = compStyle?.getTextAlign()
        val computedAlign = computed?.textAlign
        val bootAlign = styleSnapshot.textAlignment
        nativeWidget.horizontalAlignment = when {
            compAlign == TextAlignment.CENTER || computedAlign == "center" || bootAlign == TextAlignment.CENTER -> SwingConstants.CENTER
            compAlign == TextAlignment.END || computedAlign == "right" || computedAlign == "end" || bootAlign == TextAlignment.END -> SwingConstants.RIGHT
            else -> SwingConstants.LEFT
        }

        // Apply TextAttributes (underline / strikethrough / tracking) onto the JLabel's Font
        val baseFont: Font? = nativeWidget.font
        if (baseFont != null) {
            val attrs = HashMap<TextAttribute, Any?>()
            val textDecoration = compStyle?.getTextDecoration() ?: computed?.textDecoration
            when (textDecoration) {
                CssTextDecoration.UNDERLINE -> attrs[TextAttribute.UNDERLINE] = TextAttribute.UNDERLINE_ON
                CssTextDecoration.LINE_THROUGH -> attrs[TextAttribute.STRIKETHROUGH] = TextAttribute.STRIKETHROUGH_ON
                else -> {}
            }
            val letterSpacingPx = (compStyle?.getLetterSpacing() ?: computed?.letterSpacing)?.resolvePx()
            if (letterSpacingPx != null && baseFont.size2D > 0f) {
                attrs[TextAttribute.TRACKING] = (letterSpacingPx / baseFont.size2D).coerceIn(-0.2f, 1.0f)
            }
            if (attrs.isNotEmpty()) {
                nativeWidget.font = baseFont.deriveFont(attrs)
            }
        }

        applyDisplayText()
    }

    private fun applyDisplayText() {
        val safeRawText = (rawText as String?) ?: ""
        val transform = lastComponentStyle?.getTextTransform() ?: lastComputedStyle?.textTransform ?: styleSnapshot.textTransform
        val transformed = when (transform) {
            TextTransform.UPPERCASE -> safeRawText.uppercase()
            TextTransform.LOWERCASE -> safeRawText.lowercase()
            TextTransform.CAPITALIZE -> safeRawText.split(' ').joinToString(" ") { word ->
                if (word.isEmpty()) word else word.replaceFirstChar { it.uppercaseChar() }
            }
            else -> safeRawText
        }
        nativeWidget.text = transformed
        nativeWidget.putClientProperty("renderedText", transformed)
    }

    override fun setText(text: String) {
        rawText = text
        applyDisplayText()
    }

    override fun getText(): String = nativeWidget.text ?: rawText

    override fun setMaxLines(maxLines: Int?) {
        maxLinesVal = maxLines
        nativeWidget.putClientProperty("maxLines", maxLines)
    }

    override fun getMaxLines(): Int? = maxLinesVal
}
