package com.nexustools.genericui.qt.render

import com.nexustools.genericui.desktop.core.DesktopNativeWidget

internal class QtHitRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val widget: DesktopNativeWidget? = null,
    val cursorCss: String? = null,
    val isTextInput: Boolean = false,
    val onClick: (() -> Unit)? = null,
    val onDrag: ((Float, Float, Float, Float) -> Unit)? = null,
    val onRelease: ((Float, Float, Float, Float, Boolean) -> Unit)? = null,
    val onScroll: ((Float) -> Boolean)? = null
) {
    fun contains(px: Int, py: Int): Boolean = px in left until right && py in top until bottom
}
