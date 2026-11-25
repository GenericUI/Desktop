package com.nexustools.genericui.linux.titlebar

import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.color.RgbaColor
import kotlin.math.min

/**
 * Pixel-accurate vector & pixmap renderer for all 6 replicable Linux titlebar theme families
 * (`Yaru`, `Libadwaita`, `KWin Breeze`, `Mint-Y`, `Xfwm4`, `Marco`) plus neutral fallback.
 */
object LinuxTitleBarRenderer {

    fun render(
        canvas: LinuxTitleBarCanvas,
        profile: LinuxTitleBarProfile,
        layout: LinuxTitleBarLayoutResult,
        title: String,
        isActiveWindow: Boolean,
        isMaximized: Boolean,
        hoveredButton: LinuxTitleButtonKind? = null,
        pressedButton: LinuxTitleButtonKind? = null,
        buttonStates: Set<LinuxTitleButtonKind> = emptySet(),
        opacity: Float = 1f
    ) {
        val bar = layout.barBounds
        if (bar.width <= 0 || bar.height <= 0 || opacity <= 0f) return

        val palette = profile.palette
        val metrics = profile.metrics
        val topColor = applyOpacity(if (isActiveWindow) palette.activeBgTop else palette.inactiveBgTop, opacity)
        val bottomColor = applyOpacity(if (isActiveWindow) palette.activeBgBottom else palette.inactiveBgBottom, opacity)
        val topRadius = if (isMaximized) 0f else metrics.topCornerRadiusPx

        canvas.save()
        canvas.clipRect(bar)
        try {
            // 1. Background fill / Xfwm4 sliced pixmap frame
            if (profile.themeFamily == LinuxTitleBarThemeFamily.XFWM4_THEME && profile.xfwm4Assets != null) {
                renderXfwm4Background(canvas, bar, profile.xfwm4Assets, isActiveWindow, topColor, bottomColor, opacity)
            } else {
                if (topColor == bottomColor) {
                    canvas.fillTopRoundedRect(bar, topRadius, topColor)
                } else {
                    canvas.fillLinearGradientTopRounded(bar, topRadius, topColor, bottomColor)
                }

                // Top highlight bevel line (prominent on KWin Breeze, Yaru, Libadwaita, Mint-Y)
                if (palette.topHighlight.a > 0 && !isMaximized) {
                    val hiColor = applyOpacity(palette.topHighlight, opacity)
                    val inset = topRadius.coerceAtMost(bar.width / 2f)
                    canvas.drawLine(
                        x1 = bar.x + inset,
                        y1 = bar.y + 0.5f,
                        x2 = bar.right - inset,
                        y2 = bar.y + 0.5f,
                        color = hiColor,
                        strokeWidthPx = 1f
                    )
                }
            }

            // 2. Bottom separator line / maximized border
            if (metrics.drawBottomSeparator) {
                val sepColor = applyOpacity(
                    if (isActiveWindow) palette.activeBorderBottom else palette.inactiveBorderBottom,
                    opacity
                )
                if (sepColor.a > 0) {
                    canvas.drawLine(
                        x1 = bar.x.toFloat(),
                        y1 = bar.bottom - 0.5f,
                        x2 = bar.right.toFloat(),
                        y2 = bar.bottom - 0.5f,
                        color = sepColor,
                        strokeWidthPx = 1f
                    )
                }
            }
            if (isMaximized && metrics.drawMaximizedBorder) {
                val maxBorder = applyOpacity(
                    if (isActiveWindow) palette.accentColor else palette.inactiveBorderBottom,
                    opacity
                )
                canvas.drawLine(
                    x1 = bar.x.toFloat(),
                    y1 = bar.bottom - 1f,
                    x2 = bar.right.toFloat(),
                    y2 = bar.bottom - 1f,
                    color = maxBorder,
                    strokeWidthPx = 1.5f
                )
            }

            // 3. Title caption text
            if (title.isNotEmpty() && layout.titleTextBounds.width > 0 && layout.titleTextBounds.height > 0) {
                val titleColor = applyOpacity(
                    if (isActiveWindow) palette.activeTitleText else palette.inactiveTitleText,
                    opacity
                )
                val targetRect = resolveTitleDrawBounds(canvas, bar, layout.titleTextBounds, title, metrics)
                canvas.drawText(
                    text = title,
                    bounds = targetRect,
                    color = titleColor,
                    fontSizePx = metrics.fontSizePx,
                    bold = metrics.fontBold,
                    centered = metrics.centerTitle
                )
            }

            // 4. Caption buttons (left + right)
            val allButtons = layout.leftButtons + layout.rightButtons
            for (hit in allButtons) {
                if (hit.kind == LinuxTitleButtonKind.SPACER) continue
                val state = when {
                    pressedButton == hit.kind -> LinuxTitleButtonState.PRESSED
                    hoveredButton == hit.kind -> LinuxTitleButtonState.HOVERED
                    else -> LinuxTitleButtonState.NORMAL
                }
                val isToggled = (hit.kind == LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE && isMaximized) ||
                    (hit.kind in buttonStates)

                renderButton(
                    canvas = canvas,
                    profile = profile,
                    hit = hit,
                    isActiveWindow = isActiveWindow,
                    state = state,
                    isToggled = isToggled,
                    opacity = opacity
                )
            }
        } finally {
            canvas.restore()
        }
    }

    private fun renderXfwm4Background(
        canvas: LinuxTitleBarCanvas,
        bar: Rect,
        assets: LinuxXfwm4ThemeAssets,
        isActiveWindow: Boolean,
        fallbackTop: RgbaColor,
        fallbackBottom: RgbaColor,
        opacity: Float
    ) {
        if (fallbackTop == fallbackBottom) {
            canvas.fillRect(bar, fallbackTop)
        } else {
            canvas.fillLinearGradientTopRounded(bar, 0f, fallbackTop, fallbackBottom)
        }

        val leftCorner = if (isActiveWindow) assets.topLeftActive else (assets.topLeftInactive ?: assets.topLeftActive)
        val rightCorner = if (isActiveWindow) assets.topRightActive else (assets.topRightInactive ?: assets.topRightActive)
        val slices = if (isActiveWindow) assets.titleActiveSlices else assets.titleInactiveSlices.ifEmpty { assets.titleActiveSlices }

        val leftW = leftCorner?.width?.coerceAtMost(bar.width / 2) ?: 0
        val rightW = rightCorner?.width?.coerceAtMost(bar.width - leftW) ?: 0

        if (leftCorner != null && !leftCorner.isEmpty && leftW > 0) {
            canvas.drawArgbPixels(
                pixels = leftCorner.pixels,
                srcWidth = leftCorner.width,
                srcHeight = leftCorner.height,
                dstRect = Rect(bar.x, bar.y, leftW, bar.height),
                opacity = opacity
            )
        }
        if (rightCorner != null && !rightCorner.isEmpty && rightW > 0) {
            canvas.drawArgbPixels(
                pixels = rightCorner.pixels,
                srcWidth = rightCorner.width,
                srcHeight = rightCorner.height,
                dstRect = Rect(bar.right - rightW, bar.y, rightW, bar.height),
                opacity = opacity
            )
        }

        val midX = bar.x + leftW
        val midW = (bar.width - leftW - rightW).coerceAtLeast(0)
        if (midW > 0 && slices.isNotEmpty()) {
            if (slices.size == 1) {
                val slice = slices[0]
                if (!slice.isEmpty) {
                    canvas.drawArgbPixels(
                        pixels = slice.pixels,
                        srcWidth = slice.width,
                        srcHeight = slice.height,
                        dstRect = Rect(midX, bar.y, midW, bar.height),
                        opacity = opacity
                    )
                }
            } else {
                val sliceWidth = (midW / slices.size).coerceAtLeast(1)
                var curX = midX
                for (i in slices.indices) {
                    val slice = slices[i]
                    val w = if (i == slices.lastIndex) (midX + midW - curX).coerceAtLeast(0) else sliceWidth
                    if (w > 0 && !slice.isEmpty) {
                        canvas.drawArgbPixels(
                            pixels = slice.pixels,
                            srcWidth = slice.width,
                            srcHeight = slice.height,
                            dstRect = Rect(curX, bar.y, w, bar.height),
                            opacity = opacity
                        )
                    }
                    curX += w
                }
            }
        }
    }

    private fun renderButton(
        canvas: LinuxTitleBarCanvas,
        profile: LinuxTitleBarProfile,
        hit: LinuxTitleBarButtonHit,
        isActiveWindow: Boolean,
        state: LinuxTitleButtonState,
        isToggled: Boolean,
        opacity: Float
    ) {
        if (profile.themeFamily == LinuxTitleBarThemeFamily.XFWM4_THEME && profile.xfwm4Assets != null) {
            val sprite = profile.xfwm4Assets.resolveButtonSprite(
                kind = hit.kind,
                isActiveWindow = isActiveWindow,
                state = state,
                toggled = isToggled
            )
            if (sprite != null && !sprite.isEmpty) {
                canvas.drawArgbPixels(sprite.pixels, sprite.width, sprite.height, hit.bounds, opacity)
                return
            }
        }

        val palette = profile.palette
        val metrics = profile.metrics
        val b = hit.bounds
        val cx = b.x + b.width / 2f
        val cy = b.y + b.height / 2f
        val radius = (min(b.width, b.height) / 2f - 1f).coerceAtLeast(4f)
        val isClose = hit.kind == LinuxTitleButtonKind.CLOSE

        // 1. Button background / disc / outline ring
        if (isClose) {
            val bgColor = when (state) {
                LinuxTitleButtonState.PRESSED -> palette.closeBgPressed
                LinuxTitleButtonState.HOVERED -> palette.closeBgHover
                LinuxTitleButtonState.NORMAL, LinuxTitleButtonState.DISABLED ->
                    if (isActiveWindow) palette.closeBgNormal else if (palette.closeBgNormal.a > 0 && profile.themeFamily != LinuxTitleBarThemeFamily.BREEZE) {
                        palette.buttonBgNormal
                    } else {
                        RgbaColor.TRANSPARENT
                    }
            }
            val effectiveBg = applyOpacity(bgColor, opacity)
            when {
                profile.themeFamily == LinuxTitleBarThemeFamily.BREEZE &&
                    palette.outlineCloseButton &&
                    (state == LinuxTitleButtonState.NORMAL || state == LinuxTitleButtonState.DISABLED) -> {
                    val ringColor = applyOpacity(
                        if (isActiveWindow) palette.closeBgNormal else palette.buttonIconInactive,
                        opacity
                    )
                    if (ringColor.a > 0) {
                        canvas.strokeCircle(cx, cy, radius, ringColor, 1.25f)
                    }
                }
                effectiveBg.a > 0 -> {
                    if (metrics.buttonCircular) {
                        canvas.fillCircle(cx, cy, radius, effectiveBg)
                    } else {
                        canvas.fillRoundedRect(b, 4f, effectiveBg)
                    }
                }
            }
        } else {
            val bgColor = when {
                state == LinuxTitleButtonState.PRESSED -> palette.buttonBgPressed
                state == LinuxTitleButtonState.HOVERED -> palette.buttonBgHover
                isToggled -> if (palette.buttonBgNormal.a > 0) palette.buttonBgNormal else palette.buttonBgHover
                isActiveWindow -> palette.buttonBgNormal
                else -> RgbaColor(palette.buttonBgNormal.r, palette.buttonBgNormal.g, palette.buttonBgNormal.b, (palette.buttonBgNormal.a * 0.6f).toInt())
            }
            val effectiveBg = applyOpacity(bgColor, opacity)
            if (effectiveBg.a > 0) {
                if (metrics.buttonCircular) {
                    canvas.fillCircle(cx, cy, radius, effectiveBg)
                } else {
                    canvas.fillRoundedRect(b, 4f, effectiveBg)
                }
            }
        }

        // 2. Button icon / glyph
        val iconColor = applyOpacity(
            when {
                isClose -> when (state) {
                    LinuxTitleButtonState.PRESSED -> palette.closeIconPressed
                    LinuxTitleButtonState.HOVERED -> palette.closeIconHover
                    LinuxTitleButtonState.NORMAL, LinuxTitleButtonState.DISABLED ->
                        if (isActiveWindow) palette.closeIconNormal else palette.buttonIconInactive
                }
                isToggled && (hit.kind == LinuxTitleButtonKind.KEEP_ABOVE || hit.kind == LinuxTitleButtonKind.KEEP_BELOW || hit.kind == LinuxTitleButtonKind.ON_ALL_DESKTOPS) ->
                    palette.accentColor
                isActiveWindow -> palette.buttonIconActive
                else -> palette.buttonIconInactive
            },
            opacity
        )

        if (profile.themeFamily == LinuxTitleBarThemeFamily.BREEZE) {
            drawBreezeGlyph(canvas, hit.kind, cx, cy, min(b.width, b.height).toFloat(), isToggled, iconColor)
        } else {
            drawStandardVectorGlyph(canvas, hit.kind, cx, cy, min(b.width, b.height).toFloat(), isToggled, iconColor)
        }
    }

    /**
     * Renders KWin Breeze's 20x20 coordinate-space vector glyphs (`M/N/S/H/I/A/X/F/B/L`)
     * matching Breeze KDecoration2 iconography.
     */
    private fun drawBreezeGlyph(
        canvas: LinuxTitleBarCanvas,
        kind: LinuxTitleButtonKind,
        cx: Float,
        cy: Float,
        sizePx: Float,
        isToggled: Boolean,
        color: RgbaColor
    ) {
        val s = (sizePx / 20f).coerceAtLeast(0.6f)
        val stroke = (1.25f * s).coerceIn(1.0f, 1.8f)

        when (kind) {
            LinuxTitleButtonKind.CLOSE -> { // X
                val d = 4.2f * s
                canvas.drawLine(cx - d, cy - d, cx + d, cy + d, color, stroke)
                canvas.drawLine(cx + d, cy - d, cx - d, cy + d, color, stroke)
            }
            LinuxTitleButtonKind.MINIMIZE -> { // I (downward chevron)
                val w = 4.5f * s
                val top = cy - 2.0f * s
                val bot = cy + 2.5f * s
                canvas.drawLine(cx - w, top, cx, bot, color, stroke)
                canvas.drawLine(cx, bot, cx + w, top, color, stroke)
            }
            LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE -> { // A (upward chevron or restore diamond)
                val w = 4.5f * s
                if (!isToggled) {
                    val bot = cy + 2.0f * s
                    val top = cy - 2.5f * s
                    canvas.drawLine(cx - w, bot, cx, top, color, stroke)
                    canvas.drawLine(cx, top, cx + w, bot, color, stroke)
                } else {
                    val d = 4.5f * s
                    canvas.drawLine(cx, cy - d, cx + d, cy, color, stroke)
                    canvas.drawLine(cx + d, cy, cx, cy + d, color, stroke)
                    canvas.drawLine(cx, cy + d, cx - d, cy, color, stroke)
                    canvas.drawLine(cx - d, cy, cx, cy - d, color, stroke)
                }
            }
            LinuxTitleButtonKind.MENU -> { // M / N (three horizontal bars)
                val w = 4.5f * s
                val dy = 3.2f * s
                canvas.drawLine(cx - w, cy - dy, cx + w, cy - dy, color, stroke)
                canvas.drawLine(cx - w, cy, cx + w, cy, color, stroke)
                canvas.drawLine(cx - w, cy + dy, cx + w, cy + dy, color, stroke)
            }
            LinuxTitleButtonKind.ON_ALL_DESKTOPS -> { // S (pin ring + dot)
                canvas.strokeCircle(cx, cy, 4.2f * s, color, stroke)
                if (isToggled) {
                    canvas.fillCircle(cx, cy, 2.0f * s, color)
                } else {
                    canvas.fillCircle(cx, cy, 1.1f * s, color)
                }
            }
            LinuxTitleButtonKind.KEEP_ABOVE -> { // F (top bar + upward chevron)
                val w = 4.2f * s
                canvas.drawLine(cx - w, cy - 3.8f * s, cx + w, cy - 3.8f * s, color, stroke)
                canvas.drawLine(cx - w, cy + 2.8f * s, cx, cy - 1.2f * s, color, stroke)
                canvas.drawLine(cx, cy - 1.2f * s, cx + w, cy + 2.8f * s, color, stroke)
            }
            LinuxTitleButtonKind.KEEP_BELOW -> { // B (downward chevron + bottom bar)
                val w = 4.2f * s
                canvas.drawLine(cx - w, cy - 2.8f * s, cx, cy + 1.2f * s, color, stroke)
                canvas.drawLine(cx, cy + 1.2f * s, cx + w, cy - 2.8f * s, color, stroke)
                canvas.drawLine(cx - w, cy + 3.8f * s, cx + w, cy + 3.8f * s, color, stroke)
            }
            LinuxTitleButtonKind.SHADE -> { // L (bar + fold chevron)
                val w = 4.2f * s
                canvas.drawLine(cx - w, cy - 3.8f * s, cx + w, cy - 3.8f * s, color, stroke)
                if (isToggled) {
                    canvas.drawLine(cx - w, cy - 0.8f * s, cx, cy + 3.2f * s, color, stroke)
                    canvas.drawLine(cx, cy + 3.2f * s, cx + w, cy - 0.8f * s, color, stroke)
                } else {
                    canvas.drawLine(cx - w, cy + 3.2f * s, cx, cy - 0.8f * s, color, stroke)
                    canvas.drawLine(cx, cy - 0.8f * s, cx + w, cy + 3.2f * s, color, stroke)
                }
            }
            LinuxTitleButtonKind.CONTEXT_HELP -> { // H (question mark)
                drawQuestionMarkGlyph(canvas, cx, cy, s, color, stroke)
            }
            LinuxTitleButtonKind.SPACER -> Unit
        }
    }

    private fun drawStandardVectorGlyph(
        canvas: LinuxTitleBarCanvas,
        kind: LinuxTitleButtonKind,
        cx: Float,
        cy: Float,
        sizePx: Float,
        isToggled: Boolean,
        color: RgbaColor
    ) {
        val s = (sizePx / 24f).coerceAtLeast(0.55f)
        val stroke = (1.35f * s).coerceIn(1.1f, 1.8f)

        when (kind) {
            LinuxTitleButtonKind.CLOSE -> {
                val d = 4.2f * s
                canvas.drawLine(cx - d, cy - d, cx + d, cy + d, color, stroke)
                canvas.drawLine(cx + d, cy - d, cx - d, cy + d, color, stroke)
            }
            LinuxTitleButtonKind.MINIMIZE -> {
                val w = 4.5f * s
                val y = cy + 2.5f * s
                canvas.drawLine(cx - w, y, cx + w, y, color, stroke)
            }
            LinuxTitleButtonKind.MAXIMIZE_OR_RESTORE -> {
                if (!isToggled) {
                    val box = Rect(
                        (cx - 4.2f * s).toInt(),
                        (cy - 4.2f * s).toInt(),
                        (8.4f * s).toInt().coerceAtLeast(4),
                        (8.4f * s).toInt().coerceAtLeast(4)
                    )
                    canvas.strokeRoundedRect(box, 1.2f, color, stroke)
                } else {
                    val smallSize = (6.4f * s).toInt().coerceAtLeast(4)
                    val frontRect = Rect(
                        (cx - 4.5f * s).toInt(),
                        (cy - 2.0f * s).toInt(),
                        smallSize,
                        smallSize
                    )
                    canvas.strokeRoundedRect(frontRect, 1f, color, stroke)
                    val backRight = frontRect.x + smallSize + 2f * s
                    val backTop = frontRect.y - 2.2f * s
                    canvas.drawLine(frontRect.x + 2f * s, backTop, backRight, backTop, color, stroke)
                    canvas.drawLine(backRight, backTop, backRight, backTop + smallSize, color, stroke)
                }
            }
            LinuxTitleButtonKind.MENU -> {
                val w = 4.6f * s
                val dy = 3.2f * s
                canvas.drawLine(cx - w, cy - dy, cx + w, cy - dy, color, stroke)
                canvas.drawLine(cx - w, cy, cx + w, cy, color, stroke)
                canvas.drawLine(cx - w, cy + dy, cx + w, cy + dy, color, stroke)
            }
            LinuxTitleButtonKind.KEEP_ABOVE -> {
                val w = 4.2f * s
                canvas.drawLine(cx - w, cy - 3.8f * s, cx + w, cy - 3.8f * s, color, stroke)
                canvas.drawLine(cx - w, cy + 2.8f * s, cx, cy - 1.2f * s, color, stroke)
                canvas.drawLine(cx, cy - 1.2f * s, cx + w, cy + 2.8f * s, color, stroke)
            }
            LinuxTitleButtonKind.KEEP_BELOW -> {
                val w = 4.2f * s
                canvas.drawLine(cx - w, cy - 2.8f * s, cx, cy + 1.2f * s, color, stroke)
                canvas.drawLine(cx, cy + 1.2f * s, cx + w, cy - 2.8f * s, color, stroke)
                canvas.drawLine(cx - w, cy + 3.8f * s, cx + w, cy + 3.8f * s, color, stroke)
            }
            LinuxTitleButtonKind.ON_ALL_DESKTOPS -> {
                canvas.strokeCircle(cx, cy, 4.2f * s, color, stroke)
                if (isToggled) {
                    canvas.fillCircle(cx, cy, 2.0f * s, color)
                }
            }
            LinuxTitleButtonKind.SHADE -> {
                val w = 4.2f * s
                canvas.drawLine(cx - w, cy - 3.5f * s, cx + w, cy - 3.5f * s, color, stroke)
                canvas.drawLine(cx - w, cy + 2.5f * s, cx, cy - 0.8f * s, color, stroke)
                canvas.drawLine(cx, cy - 0.8f * s, cx + w, cy + 2.5f * s, color, stroke)
            }
            LinuxTitleButtonKind.CONTEXT_HELP -> {
                drawQuestionMarkGlyph(canvas, cx, cy, s, color, stroke)
            }
            LinuxTitleButtonKind.SPACER -> Unit
        }
    }

    private fun drawQuestionMarkGlyph(
        canvas: LinuxTitleBarCanvas,
        cx: Float,
        cy: Float,
        scale: Float,
        color: RgbaColor,
        stroke: Float
    ) {
        val w = 2.6f * scale
        canvas.drawLine(cx - w, cy - 2.8f * scale, cx - 1.0f * scale, cy - 4.4f * scale, color, stroke)
        canvas.drawLine(cx - 1.0f * scale, cy - 4.4f * scale, cx + 1.4f * scale, cy - 4.4f * scale, color, stroke)
        canvas.drawLine(cx + 1.4f * scale, cy - 4.4f * scale, cx + w, cy - 2.6f * scale, color, stroke)
        canvas.drawLine(cx + w, cy - 2.6f * scale, cx, cy + 0.5f * scale, color, stroke)
        canvas.drawLine(cx, cy + 0.5f * scale, cx, cy + 1.8f * scale, color, stroke)
        canvas.fillCircle(cx, cy + 4.2f * scale, (1.1f * scale).coerceAtLeast(0.9f), color)
    }

    private fun resolveTitleDrawBounds(
        canvas: LinuxTitleBarCanvas,
        bar: Rect,
        titleTextBounds: Rect,
        title: String,
        metrics: LinuxTitleBarMetrics
    ): Rect {
        if (!metrics.centerTitle) return titleTextBounds
        val textW = canvas.measureTextWidth(title, metrics.fontSizePx, metrics.fontBold)
        if (textW <= 0 || textW >= titleTextBounds.width) {
            return titleTextBounds
        }
        val idealX = bar.x + (bar.width - textW) / 2
        val minX = titleTextBounds.x
        val maxX = (titleTextBounds.right - textW).coerceAtLeast(minX)
        return Rect(idealX.coerceIn(minX, maxX), titleTextBounds.y, textW, titleTextBounds.height)
    }

    private fun applyOpacity(color: RgbaColor, opacity: Float): RgbaColor {
        if (opacity >= 0.999f || color.a == 0) return color
        val scaledAlpha = (color.a * opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return RgbaColor(color.r, color.g, color.b, scaledAlpha)
    }
}
