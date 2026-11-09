package com.nexustools.genericui.swing

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.style.theme.ThemeConfig
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.JButton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Utility for rendering native Swing Look-And-Feel controls (`JButton`) and applying
 * empty-text offscreen native sprite capture + [BootstrapColorPalette] (`ThemeConfig`) pixel tinting
 * + contrasting text/icon overlay when `BootstrapType != BootstrapType.NONE`.
 */
object SwingControlTintRenderUtil {

    fun createNativeOrTintedButton(): JButton = object : JButton() {
        private var paintingOffscreenNativeSprite: Boolean = false

        override fun paintComponent(g: Graphics) {
            if (paintingOffscreenNativeSprite) {
                super.paintComponent(g)
                return
            }

            val type = (getClientProperty("bootstrapType") as? BootstrapType)
                ?.takeIf { it != BootstrapType.NONE }

            if (type == null) {
                if (!isContentAreaFilled) isContentAreaFilled = true
                if (!isBorderPainted) isBorderPainted = true
                super.paintComponent(g)
                return
            }

            val w = width.coerceAtLeast(1)
            val h = height.coerceAtLeast(1)
            val isDark = resolveIsDark(this)
            val outline = (getClientProperty("bootstrapOutline") as? Boolean) ?: false
            val hovered = model?.isRollover == true
            val pressed = (model?.isArmed == true && model?.isPressed == true) || model?.isSelected == true
            val disabled = !isEnabled
            val componentOpacity = ((getClientProperty("opacity") as? Float) ?: 1f).coerceIn(0f, 1f)

            val offscreen = renderEmptyTextTintedButtonSprite(
                button = this,
                width = w,
                height = h,
                type = type,
                outline = outline,
                isDark = isDark,
                hovered = hovered,
                pressed = pressed,
                disabled = disabled,
                paintEmptyNativeDelegate = { emptyG2 ->
                    paintingOffscreenNativeSprite = true
                    try {
                        super.paintComponent(emptyG2)
                    } finally {
                        paintingOffscreenNativeSprite = false
                    }
                }
            )
            putClientProperty("lastTintedSprite", offscreen)

            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                if (componentOpacity < 0.999f) {
                    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, componentOpacity)
                }

                // 1. Draw the tinted empty-text native Swing button sprite
                g2.drawImage(offscreen, 0, 0, null)

                // 2. Draw contrasting text and icon on top afterward
                val fgRgba = resolveButtonForeground(
                    type = type,
                    outline = outline,
                    isDark = isDark,
                    hovered = hovered,
                    pressed = pressed
                )
                drawContrastingTextAndIconOverlay(
                    g2 = g2,
                    button = this,
                    width = w,
                    height = h,
                    fgColor = toAwtColor(fgRgba, disabled),
                    pressed = pressed
                )
            } finally {
                g2.dispose()
            }
        }
    }

    fun renderEmptyTextTintedButtonSprite(
        button: JButton,
        width: Int,
        height: Int,
        type: BootstrapType,
        outline: Boolean,
        isDark: Boolean,
        hovered: Boolean = button.model?.isRollover == true,
        pressed: Boolean = (button.model?.isArmed == true && button.model?.isPressed == true),
        disabled: Boolean = !button.isEnabled,
        themeConfig: ThemeConfig = BootstrapColorPalette.activeThemeConfig,
        paintEmptyNativeDelegate: ((Graphics2D) -> Unit)? = null
    ): BufferedImage {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val offscreen = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)

        val savedText = button.text
        val savedIcon = button.icon
        val savedFilled = button.isContentAreaFilled
        val savedBorderPainted = button.isBorderPainted
        val savedWidth = button.width
        val savedHeight = button.height

        try {
            button.text = ""
            button.icon = null
            button.isContentAreaFilled = true
            button.isBorderPainted = true
            if (savedWidth <= 0 || savedHeight <= 0) {
                button.setSize(w, h)
            }
            val offG2 = offscreen.createGraphics()
            try {
                offG2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (paintEmptyNativeDelegate != null) {
                    paintEmptyNativeDelegate(offG2)
                } else {
                    button.ui?.update(offG2, button)
                }
            } finally {
                offG2.dispose()
            }
        } finally {
            button.text = savedText
            button.icon = savedIcon
            button.isContentAreaFilled = savedFilled
            button.isBorderPainted = savedBorderPainted
            if (savedWidth <= 0 || savedHeight <= 0) {
                button.setSize(savedWidth, savedHeight)
            }
        }

        val pixels = IntArray(w * h)
        offscreen.getRGB(0, 0, w, h, pixels, 0, w)

        // Ensure fallback native bevel surface if headless UI delegate painted transparent pixels
        var hasVisiblePixels = false
        for (px in pixels) {
            if (((px ushr 24) and 0xFF) > 16) {
                hasVisiblePixels = true
                break
            }
        }
        if (!hasVisiblePixels) {
            synthesizeFallbackNativeButtonSurface(pixels, w, h, isDark)
        }

        val tintColor = BootstrapColorPalette.resolveColor(type, isDark, themeConfig)
        tintNativeControlPixels(
            pixels = pixels,
            width = w,
            height = h,
            tint = tintColor,
            outlineMode = outline,
            hovered = hovered,
            pressed = pressed,
            disabled = disabled
        )
        offscreen.setRGB(0, 0, w, h, pixels, 0, w)
        return offscreen
    }

    fun resolveButtonForeground(
        type: BootstrapType,
        outline: Boolean,
        isDark: Boolean,
        hovered: Boolean = false,
        pressed: Boolean = false,
        themeConfig: ThemeConfig = BootstrapColorPalette.activeThemeConfig
    ): RgbaColor {
        if (outline && !hovered && !pressed) {
            return BootstrapColorPalette.resolveColor(type, isDark, themeConfig)
        }
        return BootstrapColorPalette.resolveForegroundOn(type, isDark, themeConfig)
    }

    fun tintNativeControlPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        tint: RgbaColor,
        outlineMode: Boolean = false,
        hovered: Boolean = false,
        pressed: Boolean = false,
        disabled: Boolean = false
    ) {
        val totalPixels = width * height
        if (width <= 0 || height <= 0 || pixels.size < totalPixels || tint.a <= 0) return

        val insetX = (width / 5).coerceAtLeast(1)
        val insetY = (height / 5).coerceAtLeast(1)
        var interiorLumSum = 0L
        var interiorCount = 0
        var visibleLumSum = 0L
        var visibleCount = 0

        for (y in 0 until height) {
            val rowOffset = y * width
            val inInteriorY = y >= insetY && y < height - insetY
            for (x in 0 until width) {
                val argb = pixels[rowOffset + x]
                val a = (argb ushr 24) and 0xFF
                if (a < 32) continue
                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                visibleLumSum += lum
                visibleCount++
                if (inInteriorY && x >= insetX && x < width - insetX && a >= 160) {
                    interiorLumSum += lum
                    interiorCount++
                }
            }
        }

        if (visibleCount == 0) return
        val baseLuminance = when {
            interiorCount > 0 -> (interiorLumSum / interiorCount).toInt().coerceIn(1, 254)
            else -> (visibleLumSum / visibleCount).toInt().coerceIn(1, 254)
        }

        val effectiveTint = adjustTintForState(
            tint = tint,
            hovered = hovered,
            pressed = pressed,
            disabled = disabled
        )
        val tintR = effectiveTint.r
        val tintG = effectiveTint.g
        val tintB = effectiveTint.b
        val tintOpacity = (effectiveTint.a / 255f).coerceIn(0f, 1f)

        val preserveInteriorForOutline = outlineMode && !hovered && !pressed
        val borderBandPx = if (min(width, height) >= 28) 2 else 1

        for (y in 0 until height) {
            val rowOffset = y * width
            val distY = min(y, height - 1 - y)
            val verticalRatio = if (height > 1) y.toFloat() / (height - 1).toFloat() else 0.5f
            val subtleVerticalShade = (0.5f - verticalRatio) * 0.06f

            for (x in 0 until width) {
                val idx = rowOffset + x
                val argb = pixels[idx]
                val a = (argb ushr 24) and 0xFF
                if (a == 0) continue

                val r = (argb ushr 16) and 0xFF
                val g = (argb ushr 8) and 0xFF
                val b = argb and 0xFF
                val lum = (r * 299 + g * 587 + b * 114) / 1000
                val lumDelta = lum - baseLuminance
                val edgeDist = min(min(x, width - 1 - x), distY)

                val isOuterFrame = edgeDist <= borderBandPx || (edgeDist <= borderBandPx + 1 && a < 245)
                val isHighContrastEdge = edgeDist <= borderBandPx + 2 && abs(lumDelta) >= 16

                if (preserveInteriorForOutline) {
                    if (!isOuterFrame && !isHighContrastEdge) {
                        continue
                    }
                    val borderWeight = when {
                        edgeDist <= borderBandPx -> 0.92f
                        isHighContrastEdge -> (abs(lumDelta) / 64f).coerceIn(0.45f, 0.88f)
                        else -> 0.65f
                    } * tintOpacity
                    val invWeight = 1f - borderWeight
                    val shadedR = (tintR * (1f + (lumDelta / 255f) * 0.25f)).roundToInt().coerceIn(0, 255)
                    val shadedG = (tintG * (1f + (lumDelta / 255f) * 0.25f)).roundToInt().coerceIn(0, 255)
                    val shadedB = (tintB * (1f + (lumDelta / 255f) * 0.25f)).roundToInt().coerceIn(0, 255)
                    val outR = (r * invWeight + shadedR * borderWeight).roundToInt().coerceIn(0, 255)
                    val outG = (g * invWeight + shadedG * borderWeight).roundToInt().coerceIn(0, 255)
                    val outB = (b * invWeight + shadedB * borderWeight).roundToInt().coerceIn(0, 255)
                    pixels[idx] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
                    continue
                }

                val deltaNorm = (lumDelta / 255f).coerceIn(-0.65f, 0.65f) + subtleVerticalShade
                val borderFactor = when {
                    edgeDist == 0 -> if (pressed) 0.72f else 0.80f
                    edgeDist == 1 && lumDelta < -8 -> 0.86f
                    else -> 1.0f
                }

                val mappedR: Int
                val mappedG: Int
                val mappedB: Int
                if (deltaNorm >= 0f) {
                    val specWeight = (deltaNorm * 0.78f).coerceIn(0f, 0.52f)
                    val baseR = tintR * borderFactor
                    val baseG = tintG * borderFactor
                    val baseB = tintB * borderFactor
                    mappedR = (baseR + (255f - baseR) * specWeight).roundToInt().coerceIn(0, 255)
                    mappedG = (baseG + (255f - baseG) * specWeight).roundToInt().coerceIn(0, 255)
                    mappedB = (baseB + (255f - baseB) * specWeight).roundToInt().coerceIn(0, 255)
                } else {
                    val shadeScale = ((1f + deltaNorm * 0.88f) * borderFactor).coerceIn(0.32f, 1.0f)
                    mappedR = (tintR * shadeScale).roundToInt().coerceIn(0, 255)
                    mappedG = (tintG * shadeScale).roundToInt().coerceIn(0, 255)
                    mappedB = (tintB * shadeScale).roundToInt().coerceIn(0, 255)
                }

                val outR = if (tintOpacity >= 0.999f) mappedR else (r * (1f - tintOpacity) + mappedR * tintOpacity).roundToInt().coerceIn(0, 255)
                val outG = if (tintOpacity >= 0.999f) mappedG else (g * (1f - tintOpacity) + mappedG * tintOpacity).roundToInt().coerceIn(0, 255)
                val outB = if (tintOpacity >= 0.999f) mappedB else (b * (1f - tintOpacity) + mappedB * tintOpacity).roundToInt().coerceIn(0, 255)
                pixels[idx] = (a shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }
    }

    internal fun drawContrastingTextAndIconOverlay(
        g2: Graphics2D,
        button: JButton,
        width: Int,
        height: Int,
        fgColor: Color,
        pressed: Boolean
    ) {
        val rawText = button.text.orEmpty()
        val iconStr = (button.getClientProperty("icon") as? String)?.takeIf { it.isNotBlank() }
        val displayLabel = when {
            iconStr != null && !iconStr.startsWith("res://") && !iconStr.startsWith("http") -> {
                if (rawText.isNotEmpty()) "$iconStr $rawText" else iconStr
            }
            else -> rawText
        }

        if (displayLabel.isEmpty() && button.icon == null) return

        g2.font = button.font
        g2.color = fgColor
        val fm = g2.fontMetrics
        val textW = fm.stringWidth(displayLabel)
        val iconObj = button.icon
        val iconW = iconObj?.iconWidth ?: 0
        val gap = if (iconObj != null && displayLabel.isNotEmpty()) button.iconTextGap else 0
        val totalW = iconW + gap + textW
        var startX = ((width - totalW) / 2).coerceAtLeast(2)
        val shiftY = if (pressed) 1 else 0

        if (iconObj != null) {
            val iconY = ((height - iconObj.iconHeight) / 2) + shiftY
            iconObj.paintIcon(button, g2, startX, iconY)
            startX += iconW + gap
        }

        if (displayLabel.isNotEmpty()) {
            val baselineY = ((height - fm.height) / 2) + fm.ascent + shiftY
            g2.drawString(displayLabel, startX, baselineY)
        }
    }

    private fun synthesizeFallbackNativeButtonSurface(
        pixels: IntArray,
        width: Int,
        height: Int,
        isDark: Boolean
    ) {
        val topLum = if (isDark) 62 else 248
        val bottomLum = if (isDark) 44 else 228
        val borderLum = if (isDark) 82 else 184
        for (y in 0 until height) {
            val ratio = if (height > 1) y.toFloat() / (height - 1).toFloat() else 0f
            val rowLum = (topLum + (bottomLum - topLum) * ratio).roundToInt().coerceIn(0, 255)
            val rowOffset = y * width
            for (x in 0 until width) {
                val isBorder = x == 0 || x == width - 1 || y == 0 || y == height - 1
                val c = if (isBorder) borderLum else rowLum
                pixels[rowOffset + x] = (0xFF shl 24) or (c shl 16) or (c shl 8) or c
            }
        }
    }

    private fun adjustTintForState(
        tint: RgbaColor,
        hovered: Boolean,
        pressed: Boolean,
        disabled: Boolean
    ): RgbaColor {
        if (disabled) {
            val neutral = 150
            return RgbaColor(
                r = ((tint.r * 55 + neutral * 45) / 100).coerceIn(0, 255),
                g = ((tint.g * 55 + neutral * 45) / 100).coerceIn(0, 255),
                b = ((tint.b * 55 + neutral * 45) / 100).coerceIn(0, 255),
                a = max(140, (tint.a * 75) / 100)
            )
        }
        if (pressed) {
            return RgbaColor(
                r = (tint.r * 86 / 100).coerceIn(0, 255),
                g = (tint.g * 86 / 100).coerceIn(0, 255),
                b = (tint.b * 86 / 100).coerceIn(0, 255),
                a = tint.a
            )
        }
        if (hovered) {
            return RgbaColor(
                r = (tint.r + (255 - tint.r) * 10 / 100).coerceIn(0, 255),
                g = (tint.g + (255 - tint.g) * 10 / 100).coerceIn(0, 255),
                b = (tint.b + (255 - tint.b) * 10 / 100).coerceIn(0, 255),
                a = tint.a
            )
        }
        return tint
    }

    internal fun toAwtColor(c: RgbaColor, disabled: Boolean): Color {
        val alpha = if (disabled) (c.a * 165 / 255).coerceIn(0, 255) else c.a.coerceIn(0, 255)
        return Color(c.r.coerceIn(0, 255), c.g.coerceIn(0, 255), c.b.coerceIn(0, 255), alpha)
    }

    internal fun resolveIsDark(component: JButton): Boolean {
        val prop = component.getClientProperty("effectiveThemeMode") as? ThemeMode
        if (prop != null) return prop == ThemeMode.DARK
        val effective = Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()
        return effective == ThemeMode.DARK
    }
}
