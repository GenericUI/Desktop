package com.nexustools.genericui.linux.titlebar

import com.nexustools.genericui.style.color.RgbaColor

/**
 * Color palette describing active/inactive titlebar fills, borders, text, accent, and caption buttons.
 */
data class LinuxTitleBarPalette(
    val activeBgTop: RgbaColor,
    val activeBgBottom: RgbaColor,
    val inactiveBgTop: RgbaColor,
    val inactiveBgBottom: RgbaColor,
    val activeBorderBottom: RgbaColor,
    val inactiveBorderBottom: RgbaColor,
    val topHighlight: RgbaColor,
    val activeTitleText: RgbaColor,
    val inactiveTitleText: RgbaColor,
    val subtitleText: RgbaColor,
    val accentColor: RgbaColor,
    val buttonBgNormal: RgbaColor,
    val buttonBgHover: RgbaColor,
    val buttonBgPressed: RgbaColor,
    val buttonIconActive: RgbaColor,
    val buttonIconInactive: RgbaColor,
    val closeBgNormal: RgbaColor,
    val closeBgHover: RgbaColor,
    val closeBgPressed: RgbaColor,
    val closeIconNormal: RgbaColor,
    val closeIconHover: RgbaColor,
    val closeIconPressed: RgbaColor,
    val outlineCloseButton: Boolean = false,
    val isDark: Boolean = false
)
