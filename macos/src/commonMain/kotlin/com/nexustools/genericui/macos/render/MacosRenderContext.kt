@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.render

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.theme.ThemeConfig
import platform.CoreGraphics.CGContextRef
import kotlin.math.max
import kotlin.math.min

/**
 * Per-frame CoreGraphics rendering and hit-testing state for a live AppKit `NSWindow` or `NSPanel`.
 */
class MacosRenderContext(
    val cgContext: CGContextRef,
    val windowKey: Long,
    val viewport: Rect,
    val backingScale: Double,
    val darkMode: Boolean,
    val themeConfig: ThemeConfig,
    val palette: MacosThemePalette,
    val customTitleBarInsetTop: Int = 0,
    val explicitCustomTitleBar: Boolean = false,
    val titleBarWidgets: Set<DesktopNativeWidget> = emptySet(),
    val focusedWidget: DesktopNativeWidget? = null,
    val hoveredWidget: DesktopNativeWidget? = null,
    val pressedWidget: DesktopNativeWidget? = null,
    val overlayHudAlpha: Float = 1f
) {
    var currentOpacity: Float = 1f
    var clipLeft: Int = viewport.x
    var clipTop: Int = viewport.y
    var clipRight: Int = viewport.right
    var clipBottom: Int = viewport.bottom

    private val clipStack = ArrayList<IntArray>(8)

    val hitRegions = ArrayList<MacosHitRegion>(128)
    val scrollRegions = ArrayList<MacosScrollRegion>(16)
    val deferredOverlays = ArrayList<() -> Unit>(8)
    var renderedStageWidget: DesktopNativeWidget? = null
    var renderedStageBounds: Rect? = null
    var stageHitRegionStartIdx: Int = -1
    var stageHitRegionEndIdx: Int = -1
    var stageScrollRegionStartIdx: Int = -1
    var stageScrollRegionEndIdx: Int = -1

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

    fun registerHitRegion(region: MacosHitRegion) {
        val l = max(region.left, clipLeft)
        val t = max(region.top, clipTop)
        val r = min(region.right, clipRight)
        val b = min(region.bottom, clipBottom)
        if (r <= l || b <= t) return
        val clipped = if (l == region.left && t == region.top && r == region.right && b == region.bottom) {
            region
        } else {
            MacosHitRegion(
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

    fun registerScrollRegion(region: MacosScrollRegion) {
        val l = max(region.left, clipLeft)
        val t = max(region.top, clipTop)
        val r = min(region.right, clipRight)
        val b = min(region.bottom, clipBottom)
        if (r <= l || b <= t) return
        scrollRegions.add(
            MacosScrollRegion(
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
