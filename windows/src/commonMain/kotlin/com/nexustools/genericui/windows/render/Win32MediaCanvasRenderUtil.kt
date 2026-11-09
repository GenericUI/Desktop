@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.render

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.windows.Win32NativeWindowHost
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders interactive media, canvas, webview, graph, and chart components on Windows (`Canvas2D`,
 * `Canvas3D`, `VideoPlayer`, `AudioPlayer`, `WebView`, `NodeGraphEditor`, `ChartView`, `Sparkline`).
 */
object Win32MediaCanvasRenderUtil {

    fun canRender(widget: DesktopNativeWidget): Boolean {
        if (widget.interactiveSurfaceSpec != null ||
            widget.attributes.containsKey("captureBgraPtr") ||
            widget.attributes.containsKey("captureBgra") ||
            widget.attributes.containsKey("captureRgba")
        ) {
            return true
        }
        val cls = widget.nativeClassName.lowercase()
        val role = (widget.attributes["componentRole"] as? String ?: "").lowercase()
        return cls in MEDIA_CANVAS_CLASSES || role in MEDIA_CANVAS_ROLES
    }

    fun render(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val cls = widget.nativeClassName.lowercase()
        val role = (widget.attributes["componentRole"] as? String ?: "").lowercase()

        return when {
            cls == "audioplayer" || role == "audio-player" ->
                renderAudioPlayer(ctx, widget, left, top, right)
            cls == "webview" || role == "webview" || widget.attributes["customStyleEngine"] == true ->
                renderWebView(ctx, widget, left, top, right)
            cls == "chartview" || role == "chart-view" || cls == "sparkline" || role == "sparkline" ->
                renderChartOrSparkline(ctx, widget, left, top, right)
            cls == "nodegrapheditor" || role == "node-graph-editor" ->
                renderNodeGraphEditor(ctx, widget, left, top, right)
            else ->
                renderInteractiveCanvasOrVideoSurface(ctx, widget, left, top, right)
        }
    }

    /**
     * Renders `Canvas2D`, `Canvas3D`, or `VideoPlayer` by blitting its captured BGRA/RGBA frame
     * buffer via [Win32NativeWindowHost.renderInlineCanvasSurface].
     */
    fun renderInteractiveCanvasOrVideoSurface(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val availW = (right - left).coerceAtLeast(64)
        val aspect = (widget.attributes["aspectRatio"] as? String)?.toFloatOrNull() ?: (16f / 9f)
        val surfaceH = widget.preferredSize?.height?.takeIf { it > 0 }
            ?: (availW / aspect).roundToInt().coerceIn(96, 480)
        val bottom = top + surfaceH
        widget.bounds = Rect(left, top, availW, surfaceH)

        Win32NativeWindowHost.renderInlineCanvasSurface(
            hdc = ctx.hdc,
            canvasWidget = widget,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            theme = ctx.systemTheme,
            registerAsPrimaryCanvas = false
        )
        return bottom
    }

    /**
     * Renders an `AudioPlayer` transport bar.
     */
    fun renderAudioPlayer(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 44
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)
        Win32NativeWindowHost.drawRoundedRect(
            hdc = ctx.hdc,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            radius = 6,
            fillColor = ctx.bgElevatedColorRef,
            borderColor = ctx.borderSubtleColorRef
        )
        val children = ctx.visibleChildrenOf(widget)
        if (children.isNotEmpty()) {
            var curX = left + 8
            for (child in children) {
                val itemW = 72
                val itemR = (curX + itemW).coerceAtMost(right - 8)
                if (curX >= itemR) break
                ctx.renderChild(child, curX, top + 6, itemR)
                curX = itemR + 6
            }
        } else {
            ctx.selectFont(ctx.fontNormal)
            val title = (widget.attributes["title"] as? String) ?: "▶  Audio Track  0:00 / 0:00"
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = title,
                left = left + 12,
                top = top,
                right = right - 12,
                bottom = bottom,
                colorRef = ctx.textPrimaryColorRef
            )
        }
        return bottom
    }

    /**
     * Renders a `WebView` container where hosted children honor full custom CSS/ComponentStyle skinning.
     */
    fun renderWebView(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        widget.attributes["customStyleEngine"] = true
        val minH = widget.preferredSize?.height ?: 120
        var curY = top + 6
        for (child in ctx.visibleChildrenOf(widget)) {
            curY = ctx.renderChild(child, left + 6, curY, right - 6) + 4
        }
        val bottom = max(top + minH, curY + 6)
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), bottom - top)
        return bottom
    }

    /**
     * Renders a `ChartView` or `Sparkline` visualization.
     */
    fun renderChartOrSparkline(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val isSparkline = widget.nativeClassName.equals("Sparkline", ignoreCase = true) ||
            widget.attributes["componentRole"] == "sparkline"
        val height = widget.preferredSize?.height ?: if (isSparkline) 32 else 140
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)

        Win32NativeWindowHost.drawRoundedRect(
            hdc = ctx.hdc,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            radius = 6,
            fillColor = ctx.bgCardColorRef,
            borderColor = ctx.borderSubtleColorRef
        )
        val barColorRef = Win32ControlTintRenderUtil.resolveControlTintOrNull(ctx, widget)
            ?.let { ctx.toColorRef(it) }
            ?: ctx.accentBlueColorRef
        val sampleBars = listOf(0.35f, 0.62f, 0.48f, 0.82f, 0.56f, 0.91f, 0.70f)
        val pad = if (isSparkline) 4 else 12
        val innerW = (right - left - pad * 2).coerceAtLeast(14)
        val innerH = (height - pad * 2).coerceAtLeast(8)
        val barW = (innerW / sampleBars.size - 3).coerceAtLeast(2)
        for ((idx, frac) in sampleBars.withIndex()) {
            val bL = left + pad + idx * (barW + 3)
            val bR = (bL + barW).coerceAtMost(right - pad)
            val bH = (innerH * frac).roundToInt().coerceAtLeast(2)
            Win32NativeWindowHost.fillRectColor(ctx.hdc, bL, bottom - pad - bH, bR, bottom - pad, barColorRef)
        }
        return bottom
    }

    /**
     * Renders a `NodeGraphEditor` canvas surface.
     */
    fun renderNodeGraphEditor(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val height = widget.preferredSize?.height ?: 180
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)
        Win32NativeWindowHost.drawRoundedRect(
            hdc = ctx.hdc,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            radius = 6,
            fillColor = ctx.bgBaseColorRef,
            borderColor = ctx.borderSubtleColorRef
        )
        var curY = top + 12
        for (node in ctx.visibleChildrenOf(widget)) {
            curY = ctx.renderChild(node, left + 16, curY, right - 16) + 8
        }
        return max(bottom, curY + 8)
    }

    private val MEDIA_CANVAS_CLASSES = setOf(
        "canvas2d",
        "canvas3d",
        "videoplayer",
        "audioplayer",
        "webview",
        "nodegrapheditor",
        "chartview",
        "sparkline"
    )
    private val MEDIA_CANVAS_ROLES = setOf(
        "canvas-2d",
        "canvas-3d",
        "video-player",
        "audio-player",
        "webview",
        "node-graph-editor",
        "chart-view",
        "sparkline"
    )
}
