package com.nexustools.genericui.macos.render

import com.nexustools.genericui.desktop.core.DesktopNativeWidget

/**
 * Scrollable viewport hit-test region registered during a macOS CoreGraphics frame pass
 * for `ScrollView`, `LazyColumn` / `LazyRow`, and `LazyGrid` containers.
 */
class MacosScrollRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val widget: DesktopNativeWidget,
    val viewportHeight: Int,
    val contentHeight: Int,
    val currentScrollY: Float,
    val onScrollBy: (deltaY: Float) -> Boolean
) {
    fun contains(px: Int, py: Int): Boolean =
        px in left until right && py in top until bottom
}
