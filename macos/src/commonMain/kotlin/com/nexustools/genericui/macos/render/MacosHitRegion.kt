package com.nexustools.genericui.macos.render

import com.nexustools.genericui.desktop.core.DesktopNativeWidget

/**
 * Interactive pointer hit-test region registered during a macOS CoreGraphics frame pass.
 */
class MacosHitRegion(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val widget: DesktopNativeWidget?,
    val tooltip: String? = null,
    val cursorCss: String? = null,
    val isTextInput: Boolean = false,
    val onPress: ((localX: Float, localY: Float, windowX: Float, windowY: Float, button: Int, clickCount: Int) -> Boolean)? = null,
    val onDrag: ((localX: Float, localY: Float, windowX: Float, windowY: Float) -> Unit)? = null,
    val onRelease: ((localX: Float, localY: Float, windowX: Float, windowY: Float, inside: Boolean) -> Unit)? = null,
    val onClick: (() -> Unit)? = null,
    val onDoubleClick: (() -> Unit)? = null,
    val onContextMenu: ((windowX: Float, windowY: Float) -> Boolean)? = null
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)

    fun contains(px: Int, py: Int): Boolean =
        px in left until right && py in top until bottom
}
