package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.render.DesktopRenderableNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.windows.render.Win32RenderContext

/**
 * Win32 renderable component contract extending [DesktopRenderableNativeImpl].
 *
 * Each of the 36 `Windows*NativeImpl` classes implements [renderWin32] and delegates
 * rendering to the corresponding modular `Win32*RenderUtil` utility.
 */
interface WindowsRenderableNativeImpl : DesktopRenderableNativeImpl {

    /**
     * Renders this Win32 component into [ctx] within `[left..right]` starting at [top],
     * returning the bottom Y coordinate after rendering.
     */
    fun renderWin32(
        ctx: Win32RenderContext,
        left: Int,
        top: Int,
        right: Int,
        fixedHeight: Int? = null
    ): Int

    override fun renderDesktopNode(renderContext: Any, bounds: Rect): Boolean {
        val ctx = renderContext as? Win32RenderContext ?: return false
        val left = bounds.x
        val top = bounds.y
        val right = bounds.right
        val bottom = renderWin32(
            ctx = ctx,
            left = left,
            top = top,
            right = right,
            fixedHeight = null
        )
        nativeWidget.bounds = Rect(
            x = left,
            y = top,
            width = (right - left).coerceAtLeast(1),
            height = (bottom - top).coerceAtLeast(0)
        )
        return true
    }
}
