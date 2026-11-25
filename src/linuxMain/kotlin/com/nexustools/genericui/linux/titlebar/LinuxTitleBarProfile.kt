package com.nexustools.genericui.linux.titlebar

/**
 * Complete resolved Linux titlebar profile including WM detection result, replicability flag,
 * explicit diagnostic [fallbackReason] when non-replicable, button placement, palette, metrics,
 * and optional Xfwm4 pixmap sprites.
 */
data class LinuxTitleBarProfile(
    val wmType: LinuxWmType,
    val themeFamily: LinuxTitleBarThemeFamily,
    val themeName: String,
    val isReplicable: Boolean,
    val fallbackReason: String?,
    val leftButtons: List<LinuxTitleButtonKind>,
    val rightButtons: List<LinuxTitleButtonKind>,
    val palette: LinuxTitleBarPalette,
    val metrics: LinuxTitleBarMetrics,
    val xfwm4Assets: LinuxXfwm4ThemeAssets? = null
)
