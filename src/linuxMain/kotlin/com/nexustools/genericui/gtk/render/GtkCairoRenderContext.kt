@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.render

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.theme.ThemeConfig
import kotlinx.cinterop.COpaquePointer
import kotlin.math.max
import kotlin.math.min

class GtkCairoRenderContext(
    val cairoContext: COpaquePointer?,
    val windowKey: Long,
    val viewport: Rect,
    val scaleFactor: Double,
    val darkMode: Boolean,
    val themeConfig: ThemeConfig,
    val palette: GtkThemePalette,
    val focusedWidget: DesktopNativeWidget? = null,
    val hoveredWidget: DesktopNativeWidget? = null,
    val pressedWidget: DesktopNativeWidget? = null
) {
    var currentOpacity: Float = 1f
    var clipLeft: Int = viewport.x
    var clipTop: Int = viewport.y
    var clipRight: Int = viewport.right
    var clipBottom: Int = viewport.bottom

    private val clipStack = ArrayList<IntArray>(8)

    val hitRegions = ArrayList<GtkHitRegion>(128)
    val scrollRegions = ArrayList<GtkScrollRegion>(16)
    val deferredOverlays = ArrayList<() -> Unit>(8)

    fun pushClipBounds(x: Int, y: Int, w: Int, h: Int): Boolean {
        clipStack.add(intArrayOf(clipLeft, clipTop, clipRight, clipBottom))
        clipLeft = max(clipLeft, x)
        clipTop = max(clipTop, y)
        clipRight = min(clipRight, x + w)
        clipBottom = min(clipBottom, y + h)
        return clipRight > clipLeft && clipBottom > clipTop
    }

    fun popClipBounds() {
        if (clipStack.isNotEmpty()) {
            val prev = clipStack.removeAt(clipStack.lastIndex)
            clipLeft = prev[0]
            clipTop = prev[1]
            clipRight = prev[2]
            clipBottom = prev[3]
        }
    }

    fun registerHitRegion(region: GtkHitRegion) {
        val l = max(region.left, clipLeft)
        val t = max(region.top, clipTop)
        val r = min(region.right, clipRight)
        val b = min(region.bottom, clipBottom)
        if (r <= l || b <= t) return
        val clipped = if (l == region.left && t == region.top && r == region.right && b == region.bottom) {
            region
        } else {
            GtkHitRegion(
                left = l,
                top = t,
                right = r,
                bottom = b,
                widget = region.widget,
                tooltip = region.tooltip,
                cursorCss = region.cursorCss,
                isTextInput = region.isTextInput,
                onPress = region.onPress,
                onDrag = region.onDrag,
                onRelease = region.onRelease,
                onClick = region.onClick,
                onDoubleClick = region.onDoubleClick,
                onContextMenu = region.onContextMenu
            )
        }
        hitRegions.add(clipped)
    }

    fun registerScrollRegion(region: GtkScrollRegion) {
        val l = max(region.left, clipLeft)
        val t = max(region.top, clipTop)
        val r = min(region.right, clipRight)
        val b = min(region.bottom, clipBottom)
        if (r <= l || b <= t) return
        scrollRegions.add(
            GtkScrollRegion(
                left = l,
                top = t,
                right = r,
                bottom = b,
                widget = region.widget,
                viewportHeight = region.viewportHeight,
                contentHeight = region.contentHeight,
                currentScrollY = region.currentScrollY,
                onScrollBy = region.onScrollBy
            )
        )
    }
}
