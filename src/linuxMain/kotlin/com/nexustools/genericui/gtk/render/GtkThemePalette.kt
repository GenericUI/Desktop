package com.nexustools.genericui.gtk.render

import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.theme.ThemeConfig

class GtkThemePalette(
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
        fun resolve(
            darkMode: Boolean,
            themeConfig: ThemeConfig = BootstrapColorPalette.activeThemeConfig
        ): GtkThemePalette {
            val effective = themeConfig.resolveEffectiveColors(darkMode)
            val primary = BootstrapColorPalette.resolveColor(BootstrapType.PRIMARY, darkMode, themeConfig)
            val success = BootstrapColorPalette.resolveColor(BootstrapType.SUCCESS, darkMode, themeConfig)
            val warning = BootstrapColorPalette.resolveColor(BootstrapType.WARNING, darkMode, themeConfig)
            val danger = BootstrapColorPalette.resolveColor(BootstrapType.DANGER, darkMode, themeConfig)
            val info = BootstrapColorPalette.resolveColor(BootstrapType.INFO, darkMode, themeConfig)

            return if (darkMode) {
                GtkThemePalette(
                    darkMode = true,
                    bgWindow = effective.background ?: RgbaColor(30, 30, 34),
                    bgPanel = effective.surface ?: RgbaColor(36, 36, 40),
                    bgCard = effective.surface ?: RgbaColor(42, 42, 47),
                    bgElevated = RgbaColor(54, 54, 60),
                    bgHeader = RgbaColor(34, 34, 38),
                    bgInput = RgbaColor(24, 24, 28),
                    bgHover = RgbaColor(255, 255, 255, 20),
                    bgActiveRow = RgbaColor(primary.r, primary.g, primary.b, 48),
                    borderSubtle = RgbaColor(255, 255, 255, 28),
                    borderStrong = RgbaColor(255, 255, 255, 52),
                    textPrimary = effective.onSurface ?: effective.onBackground ?: RgbaColor(245, 246, 248),
                    textSecondary = RgbaColor(186, 190, 198),
                    textMuted = RgbaColor(138, 142, 152),
                    accentPrimary = primary,
                    accentSuccess = success,
                    accentWarning = warning,
                    accentDanger = danger,
                    accentInfo = info
                )
            } else {
                GtkThemePalette(
                    darkMode = false,
                    bgWindow = effective.background ?: RgbaColor(246, 245, 244),
                    bgPanel = effective.surface ?: RgbaColor(250, 250, 250),
                    bgCard = effective.surface ?: RgbaColor(255, 255, 255),
                    bgElevated = RgbaColor(235, 235, 238),
                    bgHeader = RgbaColor(242, 242, 244),
                    bgInput = RgbaColor(255, 255, 255),
                    bgHover = RgbaColor(0, 0, 0, 14),
                    bgActiveRow = RgbaColor(primary.r, primary.g, primary.b, 32),
                    borderSubtle = RgbaColor(0, 0, 0, 26),
                    borderStrong = RgbaColor(0, 0, 0, 48),
                    textPrimary = effective.onSurface ?: effective.onBackground ?: RgbaColor(34, 34, 38),
                    textSecondary = RgbaColor(94, 98, 108),
                    textMuted = RgbaColor(138, 142, 152),
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
