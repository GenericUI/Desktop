@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.qt.render

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.theme.ThemeConfig
import kotlinx.cinterop.COpaquePointer
import kotlin.math.max
import kotlin.math.min

internal class QtRenderContext(
    val windowKey: Long,
    val viewport: Rect,
    val argbPixels: IntArray,
    val bufferWidth: Int,
    val bufferHeight: Int,
    val darkMode: Boolean,
    val themeConfig: ThemeConfig,
    val palette: QtThemePalette,
    val focusedWidget: DesktopNativeWidget? = null,
    val hoveredWidget: DesktopNativeWidget? = null,
    val pressedWidget: DesktopNativeWidget? = null,
    var qPainter: COpaquePointer? = null
) {
    var currentOpacity: Float = 1f
    var clipLeft: Int = viewport.x
    var clipTop: Int = viewport.y
    var clipRight: Int = viewport.right
    var clipBottom: Int = viewport.bottom

    private val clipStack = ArrayList<IntArray>(8)

    val hitRegions = ArrayList<QtHitRegion>(128)
    val scrollRegions = ArrayList<QtScrollRegion>(16)
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

    fun registerHitRegion(region: QtHitRegion) {
        val l = max(region.left, clipLeft)
        val t = max(region.top, clipTop)
        val r = min(region.right, clipRight)
        val b = min(region.bottom, clipBottom)
        if (r <= l || b <= t) return
        val clipped = if (l == region.left && t == region.top && r == region.right && b == region.bottom) {
            region
        } else {
            QtHitRegion(
                left = l,
                top = t,
                right = r,
                bottom = b,
                widget = region.widget,
                cursorCss = region.cursorCss,
                isTextInput = region.isTextInput,
                onClick = region.onClick,
                onDrag = region.onDrag,
                onRelease = region.onRelease,
                onScroll = region.onScroll
            )
        }
        hitRegions.add(clipped)
    }

    fun registerScrollRegion(region: QtScrollRegion) {
        val l = max(region.left, clipLeft)
        val t = max(region.top, clipTop)
        val r = min(region.right, clipRight)
        val b = min(region.bottom, clipBottom)
        if (r <= l || b <= t) return
        scrollRegions.add(QtScrollRegion(l, t, r, b, region.onScrollBy))
    }
}
