package com.nexustools.genericui.macos.render

import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.theme.ThemeConfig

/**
 * Semantic macOS AppKit (`NSAppearanceNameAqua` / `NSAppearanceNameDarkAqua`) color palette
 * resolved from the active [ThemeConfig] and system theme mode.
 */
class MacosThemePalette(
    val darkMode: Boolean,
    val bgWindow: RgbaColor,
    val bgPanel: RgbaColor,
    val bgCard: RgbaColor,
    val bgElevated: RgbaColor,
    val bgHeader: RgbaColor,
    val bgInput: RgbaColor,
    val bgHover: RgbaColor,
    val bgActiveRow: RgbaColor,
    val borderSubtle: RgbaColor,
    val borderStrong: RgbaColor,
    val textPrimary: RgbaColor,
    val textSecondary: RgbaColor,
    val textMuted: RgbaColor,
    val accentPrimary: RgbaColor,
    val accentSuccess: RgbaColor,
    val accentWarning: RgbaColor,
    val accentDanger: RgbaColor,
    val accentInfo: RgbaColor
) {
    companion object {
        fun resolve(darkMode: Boolean, themeConfig: ThemeConfig = BootstrapColorPalette.activeThemeConfig): MacosThemePalette {
            val effective = themeConfig.resolveEffectiveColors(darkMode)
            val primary = BootstrapColorPalette.resolveColor(BootstrapType.PRIMARY, darkMode, themeConfig)
            val success = BootstrapColorPalette.resolveColor(BootstrapType.SUCCESS, darkMode, themeConfig)
            val warning = BootstrapColorPalette.resolveColor(BootstrapType.WARNING, darkMode, themeConfig)
            val danger = BootstrapColorPalette.resolveColor(BootstrapType.DANGER, darkMode, themeConfig)
            val info = BootstrapColorPalette.resolveColor(BootstrapType.INFO, darkMode, themeConfig)

            return if (darkMode) {
                MacosThemePalette(
                    darkMode = true,
                    bgWindow = effective.background ?: RgbaColor(24, 26, 31),
                    bgPanel = effective.surface ?: RgbaColor(30, 33, 40),
                    bgCard = effective.surface ?: RgbaColor(35, 38, 46),
                    bgElevated = RgbaColor(44, 48, 58),
                    bgHeader = RgbaColor(28, 30, 36),
                    bgInput = RgbaColor(22, 24, 29),
                    bgHover = RgbaColor(255, 255, 255, 18),
                    bgActiveRow = RgbaColor(primary.r, primary.g, primary.b, 46),
                    borderSubtle = RgbaColor(255, 255, 255, 26),
                    borderStrong = RgbaColor(255, 255, 255, 48),
                    textPrimary = effective.onSurface ?: effective.onBackground ?: RgbaColor(238, 240, 245),
                    textSecondary = RgbaColor(178, 184, 196),
                    textMuted = RgbaColor(132, 139, 152),
                    accentPrimary = primary,
                    accentSuccess = success,
                    accentWarning = warning,
                    accentDanger = danger,
                    accentInfo = info
                )
            } else {
                MacosThemePalette(
                    darkMode = false,
                    bgWindow = effective.background ?: RgbaColor(244, 245, 248),
                    bgPanel = effective.surface ?: RgbaColor(250, 250, 252),
                    bgCard = effective.surface ?: RgbaColor(255, 255, 255),
                    bgElevated = RgbaColor(238, 240, 244),
                    bgHeader = RgbaColor(248, 249, 251),
                    bgInput = RgbaColor(255, 255, 255),
                    bgHover = RgbaColor(0, 0, 0, 14),
                    bgActiveRow = RgbaColor(primary.r, primary.g, primary.b, 32),
                    borderSubtle = RgbaColor(0, 0, 0, 24),
                    borderStrong = RgbaColor(0, 0, 0, 46),
                    textPrimary = effective.onSurface ?: effective.onBackground ?: RgbaColor(28, 30, 33),
                    textSecondary = RgbaColor(92, 98, 108),
                    textMuted = RgbaColor(136, 142, 152),
                    accentPrimary = primary,
                    accentSuccess = success,
                    accentWarning = warning,
                    accentDanger = danger,
                    accentInfo = info
                )
            }
        }
    }
}
