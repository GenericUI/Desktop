package com.nexustools.genericui.qt.render

internal class QtScrollRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val onScrollBy: (Float) -> Boolean
) {
    fun contains(px: Int, py: Int): Boolean = px in left until right && py in top until bottom
}
