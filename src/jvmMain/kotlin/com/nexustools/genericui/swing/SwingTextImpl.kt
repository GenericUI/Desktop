package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.enums.bootstrap.TextTransform
import com.nexustools.genericui.enums.css.CssTextDecoration
import com.nexustools.genericui.enums.css.CssWhiteSpace
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.typography.TextNativeImpl
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Dimension
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.TextAttribute
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingConstants
import kotlin.math.max
import kotlin.math.min

/**
 * Java Swing `JLabel` implementation of [TextNativeImpl] supporting Heading scales (`h1`..`h6`),
 * [com.nexustools.genericui.enums.bootstrap.FontWeight], Bootstrap `Badge` pills with automatic
 * luminance contrast, and word-wrapping multi-line rendering inside bounded containers without clipping.
 */
class SwingTextImpl(
    val tagName: String = "p",
    classPrefix: String = ""
) : SwingBaseNativeImpl(MultiLineTextLabel(), classPrefix), TextNativeImpl<JComponent> {

    override val nativeWidget: JLabel get() = super.nativeWidget as JLabel

    override val componentTagName: String
        get() = tagName

    private var rawText: String = ""
    private var maxLinesVal: Int? = null
    private var transformedDisplayText: String = ""
    private var defaultBadgePaddingApplied: Boolean = false

    init {
        (nativeWidget as MultiLineTextLabel).owner = this
        syncSwingAppearance()
    }

    override fun dispatchesClickOnPrimaryRelease(): Boolean = true

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        val compStyle = lastComponentStyle
        val computed = lastComputedStyle
        val isBadge = isBadge()

        val compAlign = compStyle?.getTextAlign()
        val computedAlign = computed?.textAlign?.trim()?.lowercase()
        val bootAlign = styleSnapshot.textAlignment
        nativeWidget.horizontalAlignment = when {
            compAlign == TextAlignment.CENTER || computedAlign == "center" || bootAlign == TextAlignment.CENTER ->
                SwingConstants.CENTER
            compAlign == TextAlignment.END || computedAlign == "right" || computedAlign == "end" || bootAlign == TextAlignment.END ->
                SwingConstants.RIGHT
            isBadge -> SwingConstants.CENTER
            else -> SwingConstants.LEFT
        }
        nativeWidget.verticalAlignment = SwingConstants.TOP

        if (isBadge) {
            val isDark = SwingSurfacePaintUtil.isDarkThemeForComponent(nativeWidget)
            val effectiveType = styleSnapshot.type ?: styleSnapshot.backgroundType
            val (badgeBg, badgeFg) = SwingSurfacePaintUtil.resolveBadgeColors(effectiveType, isDark)
            nativeWidget.isOpaque = false
            nativeWidget.background = badgeBg
            if (customTextColor == null && compStyle?.color == null && computed?.textColor == null && styleSnapshot.textColorType == null) {
                nativeWidget.foreground = badgeFg
            }
            if (nativeWidget.border == null || defaultBadgePaddingApplied) {
                defaultBadgePaddingApplied = true
                nativeWidget.border = BorderFactory.createEmptyBorder(3, 8, 3, 8)
            }
            nativeWidget.verticalAlignment = SwingConstants.CENTER
        } else {
            defaultBadgePaddingApplied = false
        }

        val baseFont = nativeWidget.font
        if (baseFont != null) {
            val attrs = HashMap<TextAttribute, Any?>()
            val textDecoration = compStyle?.getTextDecoration() ?: computed?.textDecoration
            if (textDecoration == CssTextDecoration.UNDERLINE) {
                attrs[TextAttribute.UNDERLINE] = TextAttribute.UNDERLINE_ON
            } else if (textDecoration == CssTextDecoration.LINE_THROUGH) {
                attrs[TextAttribute.STRIKETHROUGH] = TextAttribute.STRIKETHROUGH_ON
            }
            val letterSpacingPx = (compStyle?.getLetterSpacing() ?: computed?.letterSpacing)?.resolvePx()
            if (letterSpacingPx != null && letterSpacingPx != 0f) {
                attrs[TextAttribute.TRACKING] = (letterSpacingPx / baseFont.size2D.coerceAtLeast(1f)).toDouble()
            }
            if (attrs.isNotEmpty()) {
                nativeWidget.font = baseFont.deriveFont(attrs)
            }
        }
        applyDisplayText()
    }

    internal fun isBadge(): Boolean =
        SwingSurfacePaintUtil.isBadgeComponent(tagName, componentClassPrefix, styleSnapshot.customClasses)

    internal fun isPillBadge(): Boolean =
        isBadge() && SwingSurfacePaintUtil.hasClassToken(componentClassPrefix, styleSnapshot.customClasses, "rounded-pill")

    internal fun shouldNoWrap(): Boolean {
        if (maxLinesVal == 1) return true
        val ws = lastComponentStyle?.getWhiteSpace()
            ?: lastComputedStyle?.whiteSpace?.let { raw -> CssWhiteSpace.entries.firstOrNull { it.cssValue.equals(raw, true) } }
        return ws == CssWhiteSpace.NOWRAP || ws == CssWhiteSpace.PRE
    }

    internal fun measureWrappedSize(availableWidthPx: Int): Size {
        val insets = nativeWidget.insets
        val padW = insets.left + insets.right
        val padH = insets.top + insets.bottom
        val fm = nativeWidget.getFontMetrics(nativeWidget.font)
        val lineH = (fm?.height ?: 18).coerceAtLeast(14)
        val text = transformedDisplayText
        if (text.isEmpty() && nativeWidget.icon == null) {
            return Size(padW.coerceAtLeast(1), (lineH + padH).coerceAtLeast(1))
        }
        val iconW = nativeWidget.icon?.let { it.iconWidth + nativeWidget.iconTextGap } ?: 0
        val textMaxW = if (text.isEmpty()) 0 else text.split('\n').maxOf { line ->
            fm?.stringWidth(line) ?: (line.length * 8)
        }
        val paddedTextMaxW = if (textMaxW > 0) textMaxW + max(8, (textMaxW / 10) + 6) else 0
        val naturalW = paddedTextMaxW + iconW + padW
        val boundedW = availableWidthPx.coerceAtLeast(24)

        if (shouldNoWrap() || (naturalW <= boundedW && !text.contains('\n'))) {
            val lineCount = if (shouldNoWrap()) 1 else text.split('\n').size.coerceAtLeast(1)
            val clippedLines = maxLinesVal?.takeIf { it > 0 }?.let { min(lineCount, it) } ?: lineCount
            val iconH = nativeWidget.icon?.iconHeight ?: 0
            return Size(
                width = min(naturalW, boundedW).coerceAtLeast(1),
                height = max(clippedLines * lineH, iconH) + padH
            )
        }

        val textAvailW = (boundedW - padW - iconW).coerceAtLeast(20)
        val lines = wrapTextLines(text, fm, textAvailW, maxLinesVal)
        val widestLine = lines.maxOfOrNull { fm?.stringWidth(it) ?: (it.length * 8) } ?: 0
        val paddedWidestLine = if (widestLine > 0) widestLine + max(8, (widestLine / 10) + 6) else 0
        val iconH = nativeWidget.icon?.iconHeight ?: 0
        return Size(
            width = min(paddedWidestLine + iconW + padW, boundedW).coerceAtLeast(1),
            height = max(lines.size * lineH, iconH) + padH
        )
    }

    private fun applyDisplayText() {
        val safeRawText = (rawText as String?) ?: ""
        val transform = lastComponentStyle?.getTextTransform() ?: lastComputedStyle?.textTransform ?: styleSnapshot.textTransform
        val transformed = when (transform) {
            TextTransform.UPPERCASE -> safeRawText.uppercase()
            TextTransform.LOWERCASE -> safeRawText.lowercase()
            TextTransform.CAPITALIZE -> safeRawText.split(" ").joinToString(" ") { w ->
                w.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
            }
            else -> safeRawText
        }
        transformedDisplayText = transformed
        nativeWidget.text = transformed
        invalidateLayoutHierarchy()
    }

    override fun setText(text: String) {
        rawText = text
        applyDisplayText()
    }

    override fun getText(): String = transformedDisplayText

    override fun setMaxLines(maxLines: Int?) {
        maxLinesVal = maxLines
        nativeWidget.putClientProperty("maxLines", maxLines)
        invalidateLayoutHierarchy()
        nativeWidget.repaint()
    }

    override fun getMaxLines(): Int? = maxLinesVal

    companion object {
        internal fun wrapTextLines(
            text: String,
            fm: FontMetrics?,
            maxLineWidthPx: Int,
            maxLines: Int?
        ): List<String> {
            if (text.isEmpty()) return emptyList()
            val out = ArrayList<String>()
            val paragraphs = text.split('\n')
            for (para in paragraphs) {
                if (para.isEmpty()) {
                    out.add("")
                    continue
                }
                if (fm == null || maxLineWidthPx <= 0 || fm.stringWidth(para) <= maxLineWidthPx) {
                    out.add(para)
                    continue
                }
                val words = para.split(Regex("(?<= )|(?= )")).filter { it.isNotEmpty() }
                val sb = StringBuilder()
                for (token in words) {
                    if (sb.isEmpty()) {
                        val trimmed = token.trimStart()
                        if (trimmed.isNotEmpty()) sb.append(trimmed)
                    } else {
                        val candidate = sb.toString() + token
                        if (fm.stringWidth(candidate.trimEnd()) <= maxLineWidthPx) {
                            sb.append(token)
                        } else {
                            out.add(sb.toString().trimEnd())
                            sb.clear()
                            val trimmed = token.trimStart()
                            if (trimmed.isNotEmpty()) {
                                if (fm.stringWidth(trimmed) > maxLineWidthPx) {
                                    // Break overly long token cleanly by characters
                                    var slice = StringBuilder()
                                    for (ch in trimmed) {
                                        if (slice.isNotEmpty() && fm.stringWidth(slice.toString() + ch) > maxLineWidthPx) {
                                            out.add(slice.toString())
                                            slice = StringBuilder()
                                        }
                                        slice.append(ch)
                                    }
                                    sb.append(slice)
                                } else {
                                    sb.append(trimmed)
                                }
                            }
                        }
                    }
                }
                if (sb.isNotEmpty()) {
                    out.add(sb.toString().trimEnd())
                }
            }
            val limit = maxLines?.takeIf { it > 0 }
            if (limit != null && out.size > limit) {
                val truncated = out.take(limit).toMutableList()
                val lastIdx = truncated.lastIndex
                var last = truncated[lastIdx]
                if (fm != null && maxLineWidthPx > 12) {
                    while (last.isNotEmpty() && fm.stringWidth("$last...") > maxLineWidthPx) {
                        last = last.dropLast(1)
                    }
                    truncated[lastIdx] = "$last..."
                } else if (!last.endsWith("...")) {
                    truncated[lastIdx] = "$last..."
                }
                return truncated
            }
            return out
        }
    }

    private class MultiLineTextLabel : JLabel() {
        var owner: SwingTextImpl? = null

        override fun contains(x: Int, y: Int): Boolean {
            val impl = owner ?: return super.contains(x, y)
            return impl.hitTestContains(this, x, y, super.contains(x, y))
        }

        override fun getPreferredSize(): Dimension {
            val impl = owner ?: return super.getPreferredSize()
            val explicitW = impl.getExplicitWidthPx()
            val parentAvailW = explicitW ?: parent?.width?.takeIf { it > 0 }
            val size = if (parentAvailW != null) {
                impl.measureWrappedSize(parentAvailW)
            } else {
                val base = super.getPreferredSize()
                val text = impl.transformedDisplayText
                if (text.contains('\n')) {
                    impl.measureWrappedSize(Int.MAX_VALUE / 4)
                } else {
                    Size(base.width.coerceAtLeast(1), base.height.coerceAtLeast(16))
                }
            }
            return Dimension(
                explicitW ?: size.width.coerceAtLeast(1),
                impl.getExplicitHeightPx() ?: size.height.coerceAtLeast(1)
            )
        }

        override fun paintComponent(g: Graphics) {
            val impl = owner
            val componentOpacity = ((getClientProperty("opacity") as? Float) ?: 1f).coerceIn(0f, 1f)
            if (componentOpacity <= SwingSurfacePaintUtil.OPACITY_SKIP_THRESHOLD) return
            val g2 = g.create() as Graphics2D
            try {
                if (componentOpacity < 0.999f) {
                    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, componentOpacity)
                }
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                if (impl != null && impl.isBadge() && width > 0 && height > 0) {
                    val radius = if (impl.isPillBadge()) min(width, height).toFloat() else 10f
                    g2.color = background ?: Color(13, 110, 253)
                    g2.fill(RoundRectangle2D.Float(0f, 0f, (width - 1).coerceAtLeast(1).toFloat(), (height - 1).coerceAtLeast(1).toFloat(), radius, radius))
                }

                val text = impl?.transformedDisplayText ?: text.orEmpty()
                if (impl == null || text.isEmpty() || icon != null) {
                    super.paintComponent(g2)
                    return
                }

                val fm = g2.getFontMetrics(font)
                val ins = insets
                val availW = (width - ins.left - ins.right).coerceAtLeast(8)
                val innerH = (height - ins.top - ins.bottom).coerceAtLeast(1)
                val lineH = fm.height.coerceAtLeast(12)
                val needsWrapPaint = !impl.shouldNoWrap() && (text.contains('\n') || fm.stringWidth(text) > availW || (impl.maxLinesVal != null && impl.maxLinesVal!! > 1))
                if (!needsWrapPaint && innerH >= lineH) {
                    super.paintComponent(g2)
                    return
                }

                val effectiveMaxLines = when {
                    impl.shouldNoWrap() || innerH < lineH * 2 -> 1
                    else -> impl.maxLinesVal
                }
                val lines = wrapTextLines(text, fm, availW, effectiveMaxLines)
                g2.font = font
                g2.color = foreground
                var y = if (lines.size == 1 && (innerH < lineH || verticalAlignment == CENTER)) {
                    ins.top + ((innerH + fm.ascent - fm.descent) / 2).coerceAtLeast(1)
                } else {
                    ins.top + fm.ascent
                }
                for (line in lines) {
                    val lineW = fm.stringWidth(line)
                    val x = when (horizontalAlignment) {
                        CENTER -> ins.left + ((availW - lineW) / 2).coerceAtLeast(0)
                        RIGHT -> ins.left + (availW - lineW).coerceAtLeast(0)
                        else -> ins.left
                    }
                    if (lines.size == 1 || y - fm.ascent <= height - ins.bottom) {
                        g2.drawString(line, x, y)
                    }
                    y += lineH
                }
            } finally {
                g2.dispose()
            }
        }
    }
}
