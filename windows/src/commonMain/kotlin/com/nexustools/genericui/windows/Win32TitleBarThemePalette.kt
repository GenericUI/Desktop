package com.nexustools.genericui.windows

/**
 * Resolved native Windows titlebar theme palette and DWM capability metrics for a specific window
 * and frame pass.
 */
data class Win32TitleBarThemePalette(
    val generation: Win32VisualStyleGeneration,
    val isWindowActive: Boolean,
    val isDark: Boolean,
    val hasAccentColorOnTitleBar: Boolean,
    val useTransparentDwmBackdrop: Boolean,
    val dwmDrawsCaptionButtons: Boolean,
    val centerWindowTitle: Boolean,
    val drawAeroTextGlow: Boolean,
    val captionBgColor: UInt,
    val captionBgGradientEndColor: UInt?,
    val bottomBorderColor: UInt?,
    val captionTextColor: UInt,
    val captionSecondaryTextColor: UInt,
    val buttonHoverBgColor: UInt,
    val buttonHoverAlpha: Int,
    val buttonPressBgColor: UInt,
    val buttonPressAlpha: Int,
    val closeButtonHoverBgColor: UInt,
    val closeButtonPressBgColor: UInt,
    val closeButtonHoverTextColor: UInt,
    val componentRestBgColor: UInt,
    val componentRestBgAlpha: Int,
    val componentHoverBgColor: UInt,
    val componentHoverBgAlpha: Int,
    val componentPressBgColor: UInt,
    val componentPressBgAlpha: Int,
    val componentBorderColor: UInt,
    val componentBorderAlpha: Int,
    val captionButtonWidthPx: Int,
    val nativeCaptionButtonsRightInsetPx: Int
)
