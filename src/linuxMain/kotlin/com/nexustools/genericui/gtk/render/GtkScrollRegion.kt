package com.nexustools.genericui.gtk.render

import com.nexustools.genericui.desktop.core.DesktopNativeWidget

class GtkScrollRegion(
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
