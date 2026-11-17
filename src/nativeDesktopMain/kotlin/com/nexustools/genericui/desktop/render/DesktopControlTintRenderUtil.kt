package com.nexustools.genericui.desktop.render

import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.theme.ThemeConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shared desktop utility for tinting offscreen empty-text 32-bpp ARGB native OS control sprites
 * with a [ThemeConfig]-resolved [BootstrapType] color while preserving native 3D bevels, specular
 * highlights, inner/outer borders, rounded corner anti-aliasing alpha, and hover/pressed/disabled
 * shading gradients.
 *
 * Used across `:desktop:windows` (`uxtheme.dll`), `:desktop:macos` (`NSButtonCell` / `NSControl`),
 * `:desktop:gtk` (`gtk_render_*`), and `:desktop:qt` (`QStyle::drawControl`).
 */
object DesktopControlTintRenderUtil {

    private val DARK_TEXT_COLOR = RgbaColor(33, 37, 41)
    private val LIGHT_TEXT_COLOR = RgbaColor(255, 255, 255)

    /**
     * Tints an offscreen 32-bpp straight-ARGB pixel buffer ([pixels] of dimensions [width] x [height])
     * captured from an empty-text native OS control.
     *
     * - Preserves native bevels, specular highlights, inner/outer 3D borders, rounded corner
     *   anti-aliasing alpha, and hover/pressed/disabled shading gradients by computing each pixel's
     *   native luminance and border contrast relative to the neutral control surface luminance, and
     *   mapping the interior/border chroma and luminance onto [tint].
     * - In [outlineMode] (`bootstrap.outline == true` and not [hovered]/[pressed]): preserves the
     *   native interior control surface while tinting the control border/frame pixels to [tint].
     */
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

        // 1. Sample the interior of the empty-text native control to determine its neutral surface luminance.
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

        // 2. Adjust target tint for interactive state while keeping native control bevel modulation.
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
        val borderBandPx = when {
            min(width, height) >= 28 -> 2
            else -> 1
        }

        // 3. Transform each visible pixel while preserving exact native corner anti-aliasing alpha.
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
                        // Keep the genuine native control interior surface untouched in outline mode.
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

                // Filled Bootstrap tint mode (or outline control while hovered/pressed):
                // Map the native control's bevel/highlight/shadow luminance delta onto the target tint.
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

    /**
     * Resolves the contrasting foreground (text/icon) color for a control tinted with [tint]:
     * - Returns [tint] when `outlineMode && !hovered && !pressed`.
     * - Otherwise returns dark (`#212529`) or light (`#FFFFFF`) text color based on perceived
     *   luminance `(tint.r * 299 + tint.g * 587 + tint.b * 114) / 1000 >= 150`.
     */
    fun resolveContrastingForeground(
        tint: RgbaColor,
        outlineMode: Boolean = false,
        hovered: Boolean = false,
        pressed: Boolean = false
    ): RgbaColor {
        if (outlineMode && !hovered && !pressed) {
            return tint
        }
        val perceivedLuminance = (tint.r * 299 + tint.g * 587 + tint.b * 114) / 1000
        return if (perceivedLuminance >= 150) {
            DARK_TEXT_COLOR
        } else {
            LIGHT_TEXT_COLOR
        }
    }

    /**
     * Resolves the [RgbaColor] tint for [type] from [themeConfig] (or returns `null` when [type]
     * is `null` or [BootstrapType.NONE]).
     */
    fun resolveBootstrapTintOrNull(
        type: BootstrapType?,
        darkMode: Boolean = false,
        themeConfig: ThemeConfig = BootstrapColorPalette.activeThemeConfig
    ): RgbaColor? = BootstrapColorPalette.resolveTintOrNull(type, darkMode, themeConfig)

    /**
     * Resolves the contrasting foreground color for [type] from [themeConfig] taking [outlineMode],
     * [hovered], and [pressed] into account.
     */
    fun resolveBootstrapForegroundOrNull(
        type: BootstrapType?,
        outlineMode: Boolean = false,
        hovered: Boolean = false,
        pressed: Boolean = false,
        darkMode: Boolean = false,
        themeConfig: ThemeConfig = BootstrapColorPalette.activeThemeConfig
    ): RgbaColor? {
        val tint = resolveBootstrapTintOrNull(type, darkMode, themeConfig) ?: return null
        if (!outlineMode || hovered || pressed) {
            themeConfig.resolveBootstrapForeground(type!!, darkMode)?.let { return it }
        }
        return resolveContrastingForeground(
            tint = tint,
            outlineMode = outlineMode,
            hovered = hovered,
            pressed = pressed
        )
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
}
