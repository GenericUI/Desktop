@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.render

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.widget.DesktopMarkdownViewNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.FontWeight
import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.enums.bootstrap.TextTransform
import com.nexustools.genericui.enums.css.CssFontStyle
import com.nexustools.genericui.enums.css.CssTextDecoration
import com.nexustools.genericui.enums.css.CssWhiteSpace
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.windows.Win32NativeWindowHost
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.windows.DT_CALCRECT
import platform.windows.DT_CENTER
import platform.windows.DT_END_ELLIPSIS
import platform.windows.DT_LEFT
import platform.windows.DT_NOPREFIX
import platform.windows.DT_RIGHT
import platform.windows.DT_SINGLELINE
import platform.windows.DT_TOP
import platform.windows.DT_VCENTER
import platform.windows.DT_WORDBREAK
import platform.windows.DrawTextW
import platform.windows.FW_BOLD
import platform.windows.FW_NORMAL
import platform.windows.RECT
import platform.windows.SetBkMode
import platform.windows.SetTextColor
import platform.windows.TRANSPARENT
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders typography and text display components on Windows (`Label`, `Text`, `Heading`, `Badge`,
 * `Blockquote`, `CodeBlock`, `MarkdownView`).
 *
 * Honors `ComponentStyle` typography and text color properties (`color`, `fontFamily`, `fontSize`,
 * `fontWeight`, `fontStyle`, `lineHeight`, `letterSpacing`, `textAlign`, `textDecoration`,
 * `textTransform`, `textOverflow`, `whiteSpace`, `wordBreak`) as well as `bootstrap.textColorType`,
 * `bootstrap.fontSize`, `bootstrap.fontWeight`, and `bootstrap.textAlignment`.
 */
object Win32TextRenderUtil {

    fun canRender(widget: DesktopNativeWidget): Boolean {
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopTextNativeImpl<*> || impl is DesktopMarkdownViewNativeImpl<*>) return true
        val cls = widget.nativeClassName.lowercase()
        val role = (widget.attributes["componentRole"] as? String ?: "").lowercase()
        val tag = (widget.attributes["htmlTag"] as? String ?: "").lowercase()
        return cls in TEXT_CLASSES || role in TEXT_ROLES || tag in TEXT_TAGS
    }

    fun render(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopMarkdownViewNativeImpl<*> || isMarkdownWidget(widget)) {
            return renderMarkdownView(ctx, widget, impl as? DesktopMarkdownViewNativeImpl<*>, left, top, right)
        }
        if (isBadgeWidget(widget)) {
            return renderBadge(ctx, widget, impl as? DesktopTextNativeImpl<*>, left, top, right)
        }
        if (isCodeBlockWidget(widget)) {
            return renderCodeBlock(ctx, widget, impl as? DesktopTextNativeImpl<*>, left, top, right)
        }
        if (isBlockquoteWidget(widget)) {
            return renderBlockquote(ctx, widget, impl as? DesktopTextNativeImpl<*>, left, top, right)
        }
        return renderTypographyNode(ctx, widget, impl as? DesktopTextNativeImpl<*>, left, top, right)
    }

    /**
     * Renders `Label`, `Text`, and `Heading` (`h1`..`h6`) with full `ComponentStyle` and
     * `ComponentBootstrap` typography support.
     */
    fun renderTypographyNode(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        textImpl: DesktopTextNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val rawText = (textImpl?.getText()
            ?: (widget.attributes["text"] as? String)
            ?: Win32NativeWindowHost.extractWidgetText(widget)).trim()
        if (rawText.isEmpty()) return top

        val availW = (right - left).coerceAtLeast(16)
        val baseImpl = (textImpl as? DesktopBaseNativeImpl<*>)
            ?: (widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)
        val compStyle = baseImpl?.getComponentStyle()
        val compComputed = baseImpl?.resolveEffectiveStyle()
        val snapshot = baseImpl?.getStyleSnapshot()

        val transformedText = applyTextTransform(
            text = applyLetterSpacingIfNeeded(rawText, compStyle?.letterSpacing?.resolvePx()),
            cssTransform = compStyle?.textTransform,
            bootstrapTransform = snapshot?.textTransform,
            tokens = widget.cachedAllClassTokens
        )

        val tag = (textImpl?.tagName ?: (widget.attributes["htmlTag"] as? String) ?: "").lowercase()
        val defaultFontSizePx = when (tag) {
            "h1" -> 26
            "h2" -> 22
            "h3" -> 19
            "h4" -> 17
            "h5" -> 15
            "h6" -> 14
            "small" -> 12
            else -> when (snapshot?.fontSize?.name) {
                "FS_1" -> 26
                "FS_2" -> 22
                "FS_3" -> 19
                "FS_4" -> 17
                "FS_5" -> 15
                "FS_6" -> 13
                else -> 14
            }
        }
        val fontSizePx = compStyle?.fontSize?.resolvePx(defaultFontSizePx.toFloat())?.roundToInt()
            ?: compComputed?.fontSizePx?.roundToInt()
            ?: defaultFontSizePx

        val isHeading = tag in HEADING_TAGS || Win32NativeWindowHost.widgetHasClass(widget, "fw-bold")
        val fontWeight = resolveNumericFontWeight(
            cssWeightNumeric = compStyle?.fontWeightNumeric,
            cssWeight = compStyle?.fontWeight,
            computedWeight = compComputed?.fontWeightNumeric,
            bootstrapWeight = snapshot?.fontWeight,
            defaultBold = isHeading
        )
        val italic = compStyle?.fontStyle == CssFontStyle.ITALIC ||
            compStyle?.fontStyle == CssFontStyle.OBLIQUE ||
            "fst-italic" in widget.cachedAllClassTokens
        val decoration = compStyle?.textDecoration
        val underline = decoration == CssTextDecoration.UNDERLINE ||
            "text-decoration-underline" in widget.cachedAllClassTokens
        val strikeOut = decoration == CssTextDecoration.LINE_THROUGH ||
            "text-decoration-line-through" in widget.cachedAllClassTokens

        val fontFamily = compStyle?.fontFamily?.toCssString() ?: compComputed?.fontFamily
        val font = ctx.obtainFont(
            familyName = fontFamily,
            sizePx = fontSizePx,
            weight = fontWeight,
            italic = italic,
            underline = underline,
            strikeOut = strikeOut
        )
        ctx.selectFont(font)

        val textColorRef = resolveTypographyColorRef(ctx, widget, baseImpl, isHeading)
        val hAlignFlag = resolveHorizontalAlignFlag(compStyle?.textAlign, snapshot?.textAlignment, widget.cachedAllClassTokens)
        val noWrap = compStyle?.whiteSpace == CssWhiteSpace.NOWRAP ||
            "text-nowrap" in widget.cachedAllClassTokens ||
            "text-truncate" in widget.cachedAllClassTokens
        val maxLines = textImpl?.getMaxLines() ?: (widget.attributes["maxLines"] as? Int)
        val singleLine = noWrap || maxLines == 1

        val measuredHeight = measureTextBlockHeight(
            ctx = ctx,
            text = transformedText,
            width = availW,
            fontSizePx = fontSizePx,
            lineHeightPx = compStyle?.lineHeight?.resolvePx(fontSizePx.toFloat()),
            singleLine = singleLine,
            maxLines = maxLines
        )
        val bottom = top + measuredHeight
        widget.bounds = Rect(left, top, availW, measuredHeight)

        drawFormattedText(
            ctx = ctx,
            text = transformedText,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            colorRef = textColorRef,
            hAlignFlag = hAlignFlag,
            singleLine = singleLine
        )
        if (baseImpl?.hasClickListener() == true) {
            ctx.registerHitRegion(
                Win32NativeWindowHost.HitRegion(
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    tooltip = baseImpl.getTooltipText(),
                    widget = widget,
                    onClick = { _, _ -> baseImpl.dispatchClick() }
                )
            )
        }
        return bottom
    }

    /**
     * Renders a `Badge` pill/tag honoring `BootstrapType` (`ThemeConfig` palette) and typography styles.
     */
    fun renderBadge(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        textImpl: DesktopTextNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val rawText = (textImpl?.getText() ?: Win32NativeWindowHost.extractWidgetText(widget)).trim()
        if (rawText.isEmpty()) return top

        val baseImpl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val compStyle = baseImpl?.getComponentStyle()
        val fontSizePx = compStyle?.fontSize?.resolvePx(12f)?.roundToInt() ?: 12
        val font = ctx.obtainFont(
            familyName = compStyle?.fontFamily?.toCssString(),
            sizePx = fontSizePx,
            weight = FW_BOLD
        )
        ctx.selectFont(font)

        val textW = Win32NativeWindowHost.measureSingleLineTextWidthWithFont(rawText, 0).coerceAtLeast(rawText.length * 7)
        val badgeW = (textW + 18).coerceIn(28, (right - left).coerceAtLeast(28))
        val badgeH = max(22, fontSizePx + 10)
        val badgeRight = left + badgeW
        val badgeBottom = top + badgeH
        widget.bounds = Rect(left, top, badgeW, badgeH)

        val bootstrapType = Win32ControlTintRenderUtil.resolveWidgetBootstrapType(widget)
        val fillRgba = compStyle?.backgroundColor
            ?: BootstrapColorPalette.resolveTintOrNull(bootstrapType, ctx.darkMode, ctx.themeConfig)
            ?: ctx.fromColorRef(ctx.bgElevatedColorRef)
        val fgRgba = compStyle?.color
            ?: if (bootstrapType != null && bootstrapType != BootstrapType.NONE) {
                BootstrapColorPalette.resolveForegroundOn(bootstrapType, ctx.darkMode, ctx.themeConfig)
            } else {
                ctx.fromColorRef(ctx.textPrimaryColorRef)
            }
        val pillRadius = if ("rounded-pill" in widget.cachedAllClassTokens) badgeH / 2f else 6f

        Win32NativeWindowHost.drawAntialiasedRoundedRectRgba(
            hdc = ctx.hdc,
            left = left,
            top = top,
            right = badgeRight,
            bottom = badgeBottom,
            radiusPx = pillRadius,
            fillColor = fillRgba,
            borderColor = compStyle?.borderColor ?: RgbaColor.TRANSPARENT,
            borderWidthPx = if (compStyle?.borderColor != null) 1f else 0f,
            opacity = ctx.opacity
        )
        Win32NativeWindowHost.drawTextInRect(
            hdc = ctx.hdc,
            text = rawText,
            left = left + 8,
            top = top + 2,
            right = badgeRight - 8,
            bottom = badgeBottom - 2,
            colorRef = ctx.toColorRef(fgRgba),
            center = true
        )
        return badgeBottom
    }

    /**
     * Renders a `Blockquote` with a themed accent bar and italic/styled typography.
     */
    fun renderBlockquote(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        textImpl: DesktopTextNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val text = (textImpl?.getText() ?: Win32NativeWindowHost.extractWidgetText(widget)).trim()
        if (text.isEmpty()) return top
        val availW = (right - left).coerceAtLeast(48)
        val baseImpl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val compStyle = baseImpl?.getComponentStyle()
        val fontSizePx = compStyle?.fontSize?.resolvePx(15f)?.roundToInt() ?: 15
        val font = ctx.obtainFont(
            familyName = compStyle?.fontFamily?.toCssString(),
            sizePx = fontSizePx,
            weight = FW_NORMAL,
            italic = true
        )
        ctx.selectFont(font)

        val contentLeft = left + 16
        val textH = measureTextBlockHeight(ctx, text, (right - contentLeft - 8).coerceAtLeast(24), fontSizePx, null, singleLine = false, maxLines = null)
        val totalH = max(32, textH + 12)
        val bottom = top + totalH
        widget.bounds = Rect(left, top, availW, totalH)

        val accentColorRef = Win32ControlTintRenderUtil.resolveControlTintOrNull(ctx, widget)
            ?.let { ctx.toColorRef(it) }
            ?: ctx.accentBlueColorRef
        Win32NativeWindowHost.fillRectColor(ctx.hdc, left + 2, top + 2, left + 6, bottom - 2, accentColorRef)

        val fgColorRef = resolveTypographyColorRef(ctx, widget, baseImpl, isHeading = false)
        drawFormattedText(
            ctx = ctx,
            text = text,
            left = contentLeft,
            top = top + 6,
            right = right - 6,
            bottom = bottom - 6,
            colorRef = fgColorRef,
            hAlignFlag = DT_LEFT.toUInt(),
            singleLine = false
        )
        return bottom
    }

    /**
     * Renders a `CodeBlock` with monospace typography (`Consolas` / `Courier New`) and subtle code surface frame.
     */
    fun renderCodeBlock(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        textImpl: DesktopTextNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val codeText = (textImpl?.getText() ?: (widget.attributes["code"] as? String) ?: Win32NativeWindowHost.extractWidgetText(widget)).trimEnd()
        val availW = (right - left).coerceAtLeast(64)
        val baseImpl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val compStyle = baseImpl?.getComponentStyle()
        val fontSizePx = compStyle?.fontSize?.resolvePx(13f)?.roundToInt() ?: 13
        val monoFont = ctx.obtainFont(
            familyName = compStyle?.fontFamily?.toCssString() ?: "Consolas",
            sizePx = fontSizePx,
            weight = FW_NORMAL
        )
        ctx.selectFont(monoFont)

        val lines = codeText.lines().ifEmpty { listOf("") }
        val lineH = (fontSizePx + 6).coerceAtLeast(18)
        val totalH = (lines.size * lineH + 16).coerceIn(32, 360)
        val bottom = top + totalH
        widget.bounds = Rect(left, top, availW, totalH)

        Win32NativeWindowHost.drawRoundedRect(
            hdc = ctx.hdc,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            radius = 6,
            fillColor = ctx.bgPanelColorRef,
            borderColor = ctx.borderSubtleColorRef
        )
        val fgColorRef = compStyle?.color?.let { ctx.toColorRef(it) } ?: ctx.textPrimaryColorRef
        var lineY = top + 8
        for (line in lines) {
            if (lineY + lineH > bottom - 4) break
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = line,
                left = left + 10,
                top = lineY,
                right = right - 10,
                bottom = lineY + lineH,
                colorRef = fgColorRef,
                center = false
            )
            lineY += lineH
        }
        return bottom
    }

    /**
     * Renders a `MarkdownView` with headings, bullet lists, code blocks, blockquotes, and paragraphs.
     */
    fun renderMarkdownView(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        markdownImpl: DesktopMarkdownViewNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val markdown = markdownImpl?.getMarkdown()
            ?: (widget.attributes["markdown"] as? String)
            ?: Win32NativeWindowHost.extractWidgetText(widget)
        if (markdown.isBlank()) return top

        val availW = (right - left).coerceAtLeast(64)
        val baseImpl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        val baseColorRef = resolveTypographyColorRef(ctx, widget, baseImpl, isHeading = false)
        var cursorY = top
        var inFence = false
        val fenceBuffer = ArrayList<String>()

        for (rawLine in markdown.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.startsWith("```")) {
                if (inFence) {
                    inFence = false
                    val codeH = (fenceBuffer.size * 18 + 12).coerceAtLeast(28)
                    Win32NativeWindowHost.drawRoundedRect(
                        hdc = ctx.hdc,
                        left = left + 4,
                        top = cursorY,
                        right = right - 4,
                        bottom = cursorY + codeH,
                        radius = 5,
                        fillColor = ctx.bgPanelColorRef,
                        borderColor = ctx.borderSubtleColorRef
                    )
                    ctx.selectFont(ctx.obtainFont("Consolas", 13, FW_NORMAL))
                    for ((idx, cLine) in fenceBuffer.withIndex()) {
                        val ly = cursorY + 6 + idx * 18
                        Win32NativeWindowHost.drawTextInRect(
                            hdc = ctx.hdc,
                            text = cLine,
                            left = left + 12,
                            top = ly,
                            right = right - 12,
                            bottom = ly + 18,
                            colorRef = baseColorRef
                        )
                    }
                    fenceBuffer.clear()
                    cursorY += codeH + 6
                } else {
                    inFence = true
                    fenceBuffer.clear()
                }
                continue
            }
            if (inFence) {
                fenceBuffer.add(rawLine)
                continue
            }
            if (trimmed.isEmpty()) {
                cursorY += 6
                continue
            }
            when {
                trimmed.startsWith("# ") -> {
                    ctx.selectFont(ctx.obtainFont(sizePx = 22, weight = FW_BOLD))
                    Win32NativeWindowHost.drawTextInRect(ctx.hdc, stripInlineMarkdown(trimmed.substring(2)), left, cursorY, right, cursorY + 28, ctx.textPrimaryColorRef)
                    cursorY += 30
                }
                trimmed.startsWith("## ") -> {
                    ctx.selectFont(ctx.obtainFont(sizePx = 19, weight = FW_BOLD))
                    Win32NativeWindowHost.drawTextInRect(ctx.hdc, stripInlineMarkdown(trimmed.substring(3)), left, cursorY, right, cursorY + 24, ctx.textPrimaryColorRef)
                    cursorY += 26
                }
                trimmed.startsWith("### ") -> {
                    ctx.selectFont(ctx.obtainFont(sizePx = 16, weight = FW_BOLD))
                    Win32NativeWindowHost.drawTextInRect(ctx.hdc, stripInlineMarkdown(trimmed.substring(4)), left, cursorY, right, cursorY + 22, ctx.textPrimaryColorRef)
                    cursorY += 24
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    ctx.selectFont(ctx.fontNormal)
                    val itemText = "•  " + stripInlineMarkdown(trimmed.substring(2))
                    val itemH = measureTextBlockHeight(ctx, itemText, availW - 12, 14, null, singleLine = false, maxLines = null)
                    drawFormattedText(ctx, itemText, left + 8, cursorY, right, cursorY + itemH, baseColorRef, DT_LEFT.toUInt(), singleLine = false)
                    cursorY += itemH + 3
                }
                trimmed.startsWith("> ") -> {
                    val quoteText = stripInlineMarkdown(trimmed.substring(2))
                    val itemH = measureTextBlockHeight(ctx, quoteText, availW - 20, 14, null, singleLine = false, maxLines = null)
                    Win32NativeWindowHost.fillRectColor(ctx.hdc, left + 2, cursorY + 2, left + 5, cursorY + itemH, ctx.accentBlueColorRef)
                    ctx.selectFont(ctx.obtainFont(sizePx = 14, italic = true))
                    drawFormattedText(ctx, quoteText, left + 12, cursorY, right, cursorY + itemH, ctx.textSecondaryColorRef, DT_LEFT.toUInt(), singleLine = false)
                    cursorY += itemH + 4
                }
                else -> {
                    ctx.selectFont(ctx.fontNormal)
                    val cleanParagraph = stripInlineMarkdown(trimmed)
                    val paraH = measureTextBlockHeight(ctx, cleanParagraph, availW, 14, null, singleLine = false, maxLines = null)
                    drawFormattedText(ctx, cleanParagraph, left, cursorY, right, cursorY + paraH, baseColorRef, DT_LEFT.toUInt(), singleLine = false)
                    cursorY += paraH + 4
                }
            }
        }
        widget.bounds = Rect(left, top, availW, (cursorY - top).coerceAtLeast(20))
        return cursorY
    }

    private fun resolveTypographyColorRef(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        baseImpl: DesktopBaseNativeImpl<*>?,
        isHeading: Boolean
    ): UInt {
        val compStyle = baseImpl?.getComponentStyle()
        compStyle?.color?.let { return ctx.toColorRef(it) }
        baseImpl?.getEffectiveTextColor()?.let { return ctx.toColorRef(it) }
        (widget.attributes["textColor"] as? RgbaColor)?.let { return ctx.toColorRef(it) }

        val textColorType = baseImpl?.getTextColorType()?.takeIf { it != BootstrapType.NONE }
            ?: resolveTextBootstrapTypeFromTokens(widget.cachedAllClassTokens)
        if (textColorType != null) {
            val resolved = BootstrapColorPalette.resolveColor(textColorType, ctx.darkMode, ctx.themeConfig)
            return ctx.toColorRef(resolved)
        }
        val tokens = widget.cachedAllClassTokens
        if ("text-muted" in tokens || "text-secondary" in tokens) {
            return ctx.textSecondaryColorRef
        }
        return if (isHeading) ctx.textPrimaryColorRef else ctx.textPrimaryColorRef
    }

    private fun resolveTextBootstrapTypeFromTokens(tokens: Set<String>): BootstrapType? = when {
        "text-primary" in tokens -> BootstrapType.PRIMARY
        "text-danger" in tokens -> BootstrapType.DANGER
        "text-success" in tokens -> BootstrapType.SUCCESS
        "text-warning" in tokens -> BootstrapType.WARNING
        "text-info" in tokens -> BootstrapType.INFO
        "text-light" in tokens -> BootstrapType.LIGHT
        "text-dark" in tokens -> BootstrapType.DARK
        else -> null
    }

    private fun resolveNumericFontWeight(
        cssWeightNumeric: Int?,
        cssWeight: FontWeight?,
        computedWeight: Int?,
        bootstrapWeight: FontWeight?,
        defaultBold: Boolean
    ): Int {
        cssWeightNumeric?.let { return it }
        computedWeight?.let { return it }
        return when (cssWeight ?: bootstrapWeight) {
            FontWeight.BOLD, FontWeight.BOLDER -> FW_BOLD
            FontWeight.SEMIBOLD, FontWeight.MEDIUM -> 600
            FontWeight.LIGHT, FontWeight.LIGHTER -> 300
            FontWeight.NORMAL -> FW_NORMAL
            null -> if (defaultBold) FW_BOLD else FW_NORMAL
        }
    }

    private fun resolveHorizontalAlignFlag(
        cssAlign: TextAlignment?,
        bootstrapAlign: TextAlignment?,
        tokens: Set<String>
    ): UInt {
        val effective = cssAlign ?: bootstrapAlign
        return when {
            effective == TextAlignment.CENTER || "text-center" in tokens -> DT_CENTER.toUInt()
            effective == TextAlignment.END || "text-end" in tokens -> DT_RIGHT.toUInt()
            else -> DT_LEFT.toUInt()
        }
    }

    private fun applyTextTransform(
        text: String,
        cssTransform: TextTransform?,
        bootstrapTransform: TextTransform?,
        tokens: Set<String>
    ): String {
        val effective = cssTransform ?: bootstrapTransform
        return when {
            effective == TextTransform.UPPERCASE || "text-uppercase" in tokens ->
                text.uppercase()
            effective == TextTransform.LOWERCASE || "text-lowercase" in tokens ->
                text.lowercase()
            effective == TextTransform.CAPITALIZE || "text-capitalize" in tokens ->
                text.split(' ').joinToString(" ") { word ->
                    if (word.isEmpty()) word else word.replaceFirstChar { it.uppercaseChar() }
                }
            else -> text
        }
    }

    private fun applyLetterSpacingIfNeeded(text: String, letterSpacingPx: Float?): String {
        if (letterSpacingPx == null || letterSpacingPx < 2.5f || text.length <= 1) return text
        return text.toCharArray().joinToString(" ")
    }

    private fun measureTextBlockHeight(
        ctx: Win32RenderContext,
        text: String,
        width: Int,
        fontSizePx: Int,
        lineHeightPx: Float?,
        singleLine: Boolean,
        maxLines: Int?
    ): Int {
        val defaultLineH = lineHeightPx?.roundToInt()?.coerceAtLeast(fontSizePx + 2) ?: (fontSizePx + 6)
        if (singleLine) return defaultLineH.coerceAtLeast(20)
        val fontKind = when {
            fontSizePx >= 22 -> 3
            fontSizePx >= 17 -> 2
            fontSizePx >= 15 -> 1
            else -> 0
        }
        val avgCharPx = when {
            fontSizePx >= 22 -> 11
            fontSizePx >= 17 -> 9
            else -> 7
        }
        val singleLineW = max(
            text.length * avgCharPx,
            Win32NativeWindowHost.measureSingleLineTextWidthWithFont(text, fontKind)
        )
        val shouldWrap = text.contains('\n') || singleLineW > (width - 8).coerceAtLeast(24)
        if (!shouldWrap) return defaultLineH.coerceAtLeast(20)
        val measured = Win32NativeWindowHost.measureMultilineTextHeight(
            ctx.hdc,
            text,
            (width - 4).coerceAtLeast(32),
            defaultLineH
        )
        return if (maxLines != null && maxLines > 0) {
            measured.coerceAtMost(maxLines * defaultLineH + 4)
        } else {
            measured
        }
    }

    private fun drawFormattedText(
        ctx: Win32RenderContext,
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        colorRef: UInt,
        hAlignFlag: UInt,
        singleLine: Boolean
    ) {
        val hdc = ctx.hdc ?: return
        if (right <= left || bottom <= top) return
        if (singleLine && hAlignFlag != DT_RIGHT.toUInt()) {
            Win32NativeWindowHost.drawTextInRect(
                hdc = hdc,
                text = text,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                colorRef = colorRef,
                center = (hAlignFlag == DT_CENTER.toUInt())
            )
            return
        }
        memScoped {
            val rc = alloc<RECT>()
            rc.left = left
            rc.top = top
            rc.right = right
            rc.bottom = bottom
            SetBkMode(hdc, TRANSPARENT)
            SetTextColor(hdc, colorRef)
            val flags = if (singleLine) {
                hAlignFlag or DT_VCENTER.toUInt() or DT_SINGLELINE.toUInt() or DT_END_ELLIPSIS.toUInt() or DT_NOPREFIX.toUInt()
            } else {
                hAlignFlag or DT_TOP.toUInt() or DT_WORDBREAK.toUInt() or DT_END_ELLIPSIS.toUInt() or DT_NOPREFIX.toUInt()
            }
            DrawTextW(hdc, text, -1, rc.ptr, flags)
            Win32NativeWindowHost.markGdiRectDirty(left, top, right, bottom)
        }
    }

    private fun stripInlineMarkdown(line: String): String =
        line.replace("**", "").replace("__", "").replace("`", "")

    private fun isBadgeWidget(widget: DesktopNativeWidget): Boolean =
        Win32NativeWindowHost.widgetHasClass(widget, "badge") ||
            widget.nativeClassName.equals("Badge", ignoreCase = true) ||
            widget.attributes["componentRole"] == "badge"

    private fun isCodeBlockWidget(widget: DesktopNativeWidget): Boolean =
        widget.nativeClassName.equals("CodeBlock", ignoreCase = true) ||
            widget.attributes["componentRole"] == "code-block" ||
            (widget.attributes["htmlTag"] as? String)?.equals("pre", ignoreCase = true) == true ||
            (widget.attributes["htmlTag"] as? String)?.equals("code", ignoreCase = true) == true

    private fun isBlockquoteWidget(widget: DesktopNativeWidget): Boolean =
        widget.nativeClassName.equals("Blockquote", ignoreCase = true) ||
            widget.attributes["componentRole"] == "blockquote" ||
            (widget.attributes["htmlTag"] as? String)?.equals("blockquote", ignoreCase = true) == true

    private fun isMarkdownWidget(widget: DesktopNativeWidget): Boolean =
        widget.nativeClassName.equals("MarkdownView", ignoreCase = true) ||
            widget.attributes["componentRole"] == "markdown-view" ||
            widget.attributes.containsKey("markdown")

    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val TEXT_CLASSES = setOf("label", "text", "heading", "badge", "blockquote", "codeblock", "markdownview")
    private val TEXT_ROLES = setOf("label", "text", "heading", "badge", "blockquote", "code-block", "markdown-view")
    private val TEXT_TAGS = setOf("span", "p", "label", "h1", "h2", "h3", "h4", "h5", "h6", "small", "blockquote", "pre", "code")
}
