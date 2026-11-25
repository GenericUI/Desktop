package com.nexustools.genericui.linux.titlebar

/**
 * Geometry and typography metrics for a replicated Linux titlebar.
 */
data class LinuxTitleBarMetrics(
    val heightPx: Int,
    val compactHeightPx: Int,
    val topCornerRadiusPx: Float,
    val horizontalPaddingPx: Int,
    val buttonWidthPx: Int,
    val buttonHeightPx: Int,
    val buttonSpacingPx: Int,
    val buttonCircular: Boolean,
    val centerTitle: Boolean,
    val fontSizePx: Float,
    val fontBold: Boolean,
    val drawBottomSeparator: Boolean,
    val drawMaximizedBorder: Boolean
)
