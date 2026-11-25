package com.nexustools.genericui.linux.titlebar

import com.nexustools.genericui.geometry.Rect

/**
 * Geometry output of [LinuxTitleBarLayoutEngine.computeLayout] covering button rects,
 * embedded content slots, and non-overlapping title text bounds.
 */
data class LinuxTitleBarLayoutResult(
    val barBounds: Rect,
    val leftButtons: List<LinuxTitleBarButtonHit>,
    val rightButtons: List<LinuxTitleBarButtonHit>,
    val contentSlotBounds: Rect,
    val leadingSlotBounds: Rect,
    val centerSlotBounds: Rect,
    val trailingSlotBounds: Rect,
    val titleTextBounds: Rect
)
