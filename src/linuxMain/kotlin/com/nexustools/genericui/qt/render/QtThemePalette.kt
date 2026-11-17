package com.nexustools.genericui.qt.render

import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.theme.ThemeConfig

internal class QtThemePalette(
    val dark: Boolean,
    val bgWindow: RgbaColor,
    val bgHeader: RgbaColor,
    val bgPanel: RgbaColor,
    val bgCard: RgbaColor,
    val bgControl: RgbaColor,
    val bgControlHover: RgbaColor,
    val bgInput: RgbaColor,
    val bgMuted: RgbaColor,
    val borderSubtle: RgbaColor,
    val borderStrong: RgbaColor,
    val textPrimary: RgbaColor,
    val textSecondary: RgbaColor,
    val textMuted: RgbaColor,
    val accentPrimary: RgbaColor,
    val accentSuccess: RgbaColor,
    val accentWarning: RgbaColor,
    val accentDanger: RgbaColor
) {
    companion object {
        fun resolve(dark: Boolean, themeConfig: ThemeConfig = BootstrapColorPalette.activeThemeConfig): QtThemePalette {
            val primary = BootstrapColorPalette.resolveColor(BootstrapType.PRIMARY, dark, themeConfig)
            val success = BootstrapColorPalette.resolveColor(BootstrapType.SUCCESS, dark, themeConfig)
            val warning = BootstrapColorPalette.resolveColor(BootstrapType.WARNING, dark, themeConfig)
            val danger = BootstrapColorPalette.resolveColor(BootstrapType.DANGER, dark, themeConfig)
            return if (dark) {
                QtThemePalette(
                    dark = true,
                    bgWindow = RgbaColor(32, 35, 42, 255),
                    bgHeader = RgbaColor(40, 44, 52, 255),
                    bgPanel = RgbaColor(37, 41, 49, 255),
                    bgCard = RgbaColor(45, 49, 59, 255),
                    bgControl = RgbaColor(54, 59, 71, 255),
                    bgControlHover = RgbaColor(68, 74, 88, 255),
                    bgInput = RgbaColor(27, 30, 36, 255),
                    bgMuted = RgbaColor(49, 54, 64, 255),
                    borderSubtle = RgbaColor(255, 255, 255, 34),
                    borderStrong = RgbaColor(255, 255, 255, 76),
                    textPrimary = RgbaColor(239, 240, 241, 255),
                    textSecondary = RgbaColor(186, 194, 206, 255),
                    textMuted = RgbaColor(135, 144, 158, 255),
                    accentPrimary = primary,
                    accentSuccess = success,
                    accentWarning = warning,
                    accentDanger = danger
                )
            } else {
                QtThemePalette(
                    dark = false,
                    bgWindow = RgbaColor(239, 240, 241, 255),
                    bgHeader = RgbaColor(230, 232, 235, 255),
                    bgPanel = RgbaColor(247, 248, 249, 255),
                    bgCard = RgbaColor(255, 255, 255, 255),
                    bgControl = RgbaColor(225, 228, 232, 255),
                    bgControlHover = RgbaColor(210, 214, 220, 255),
                    bgInput = RgbaColor(255, 255, 255, 255),
                    bgMuted = RgbaColor(229, 231, 235, 255),
                    borderSubtle = RgbaColor(0, 0, 0, 28),
                    borderStrong = RgbaColor(0, 0, 0, 68),
                    textPrimary = RgbaColor(35, 38, 41, 255),
                    textSecondary = RgbaColor(82, 92, 104, 255),
                    textMuted = RgbaColor(127, 140, 141, 255),
                    accentPrimary = primary,
                    accentSuccess = success,
                    accentWarning = warning,
                    accentDanger = danger
                )
            }
        }
    }
}
