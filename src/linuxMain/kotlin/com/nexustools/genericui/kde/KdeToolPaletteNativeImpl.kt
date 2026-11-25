@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.component.dock.ToolPaletteItem
import com.nexustools.genericui.desktop.dock.DesktopToolPaletteNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer
import kotlin.math.ceil

/**
 * Retained KDE tool palette widget (`KdeToolPaletteWidget`) painting a Breeze-themed grid of
 * selectable studio tools and active foreground/background color swatches.
 */
open class KdeToolPaletteNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("KdeToolPaletteWidget")
) : DesktopToolPaletteNativeImpl<KdeWidgetHandle>(nativeWidget) {

    private var hoveredToolIndex: Int = -1
    private val cellSizePx = 38
    private val cellGapPx = 4
    private val swatchHeightPx = 36

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQWidget(null, 0)
        nativeWidget.attachToQWidget(ptr, "KdeToolPaletteWidget", customPainted = true)
        KdePainterSupport.attachStandardInputHooks(
            widgetPtr = ptr,
            impl = this,
            hookMask = KdeVTableBridge.HOOK_PAINT or KdeVTableBridge.HOOK_RESIZE or KdeVTableBridge.HOOK_SIZE_HINT,
            dispatchLeftClickOnRelease = false,
            onPaint = { _, painter, w, h -> paintPalette(painter, w, h) },
            onSizeHint = { _ ->
                val s = measureIntrinsicSize(null, null)
                s.width to s.height
            }
        )
        KdeVTableBridge.attachWidgetHooks(ptr, KdeVTableBridge.HOOK_INPUT) {
            onMouseMove = { _, info ->
                val hit = hitTestToolIndex(info.localX.toInt(), info.localY.toInt())
                if (hoveredToolIndex != hit) {
                    hoveredToolIndex = hit
                    nativeWidget.requestRepaint()
                }
                false
            }
            onLeave = { _ ->
                if (hoveredToolIndex != -1) {
                    hoveredToolIndex = -1
                    nativeWidget.requestRepaint()
                }
            }
            onMouseRelease = { _, info ->
                if (info.button == 1 && isEnabled()) {
                    val toolIdx = hitTestToolIndex(info.localX.toInt(), info.localY.toInt())
                    if (toolIdx in toolsList.indices) {
                        val toolId = toolsList[toolIdx].id
                        GenericUiThreading.runOnLogic {
                            setActiveToolId(toolId)
                            nativeWidget.requestRepaint()
                        }
                        true
                    } else if (isPointInSwatchArea(info.localY.toInt())) {
                        GenericUiThreading.runOnLogic {
                            dispatchSwapColors()
                            nativeWidget.requestRepaint()
                        }
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
        }
    }

    private fun hitTestToolIndex(x: Int, y: Int): Int {
        val cols = columnCount.coerceAtLeast(1)
        val col = (x - cellGapPx) / (cellSizePx + cellGapPx)
        val row = (y - cellGapPx) / (cellSizePx + cellGapPx)
        if (col < 0 || col >= cols || row < 0) return -1
        val idx = row * cols + col
        return if (idx in toolsList.indices) idx else -1
    }

    private fun isPointInSwatchArea(y: Int): Boolean {
        val cols = columnCount.coerceAtLeast(1)
        val rows = if (toolsList.isEmpty()) 0 else ceil(toolsList.size.toDouble() / cols).toInt()
        val swatchTop = cellGapPx + rows * (cellSizePx + cellGapPx) + 4
        return y >= swatchTop && y <= swatchTop + swatchHeightPx
    }

    private fun paintPalette(painter: COpaquePointer, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val isDark = SystemThemeDetector.resolveTheme(ThemeMode.AUTO) == ThemeMode.DARK
        val bg = getEffectiveBackgroundColor() ?: if (isDark) RgbaColor(42, 46, 50) else RgbaColor(239, 240, 241)
        val border = if (isDark) RgbaColor(74, 80, 86) else RgbaColor(196, 200, 204)
        val accent = getEffectiveAccentColor(isDark) ?: KdeApplicationNativeImpl.breezeHighlightColor()
        val textCol = if (isDark) RgbaColor(239, 240, 241) else RgbaColor(35, 38, 41)

        KdeDynamicBindings.painterFillRect(painter, 0.0, 0.0, w.toDouble(), h.toDouble(), bg)

        val cols = columnCount.coerceAtLeast(1)
        for (i in toolsList.indices) {
            val tool = toolsList[i]
            val col = i % cols
            val row = i / cols
            val x = cellGapPx + col * (cellSizePx + cellGapPx)
            val y = cellGapPx + row * (cellSizePx + cellGapPx)
            val selected = tool.id == currentToolId
            val cellBg = when {
                selected -> accent.copy(a = 72)
                i == hoveredToolIndex -> accent.copy(a = 32)
                else -> if (isDark) RgbaColor(54, 58, 63) else RgbaColor(250, 250, 250)
            }
            val cellBorder = if (selected) accent else border
            KdeDynamicBindings.painterFillRoundedRect(painter, x.toDouble(), y.toDouble(), cellSizePx.toDouble(), cellSizePx.toDouble(), 4.0, cellBg)
            KdeDynamicBindings.painterStrokeRoundedRect(painter, x.toDouble(), y.toDouble(), cellSizePx.toDouble(), cellSizePx.toDouble(), 4.0, cellBorder, if (selected) 1.5 else 1.0)
            val badgeText = tool.name.take(2).uppercase()
            KdeDynamicBindings.painterDrawText(
                p = painter,
                text = badgeText,
                x = x.toDouble(),
                y = y.toDouble(),
                w = cellSizePx.toDouble(),
                h = cellSizePx.toDouble(),
                color = if (selected) accent else textCol,
                fontSizePx = 11f,
                fontWeight = if (selected) 700 else 400,
                italic = false,
                fontFamily = null,
                alignmentFlags = KdeDynamicBindings.ALIGN_HCENTER or KdeDynamicBindings.ALIGN_VCENTER
            )
        }

        // Dual foreground/background color swatches
        val rows = if (toolsList.isEmpty()) 0 else ceil(toolsList.size.toDouble() / cols).toInt()
        val swatchTop = (cellGapPx + rows * (cellSizePx + cellGapPx) + 6).toDouble()
        if (swatchTop + 28 < h) {
            KdeDynamicBindings.painterFillRoundedRect(painter, 18.0, swatchTop + 10.0, 20.0, 20.0, 2.0, bgColor)
            KdeDynamicBindings.painterStrokeRoundedRect(painter, 18.0, swatchTop + 10.0, 20.0, 20.0, 2.0, border, 1.0)
            KdeDynamicBindings.painterFillRoundedRect(painter, 8.0, swatchTop, 20.0, 20.0, 2.0, fgColor)
            KdeDynamicBindings.painterStrokeRoundedRect(painter, 8.0, swatchTop, 20.0, 20.0, 2.0, border, 1.0)
        }
    }

    override fun setColumns(columns: Int) {
        super.setColumns(columns)
        nativeWidget.requestRepaint()
    }

    override fun setTools(tools: List<ToolPaletteItem>, activeToolId: String?) {
        super.setTools(tools, activeToolId)
        nativeWidget.requestRepaint()
    }

    override fun setActiveToolId(toolId: String?) {
        super.setActiveToolId(toolId)
        nativeWidget.requestRepaint()
    }

    override fun setForegroundColor(color: RgbaColor) {
        super.setForegroundColor(color)
        nativeWidget.requestRepaint()
    }

    override fun setBackgroundColor(color: RgbaColor) {
        super.setBackgroundColor(color)
        nativeWidget.requestRepaint()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val cols = columnCount.coerceAtLeast(1)
        val rows = if (toolsList.isEmpty()) 1 else ceil(toolsList.size.toDouble() / cols).toInt()
        val w = cellGapPx + cols * (cellSizePx + cellGapPx)
        val h = cellGapPx + rows * (cellSizePx + cellGapPx) + swatchHeightPx + 10
        return Size(w.coerceAtLeast(72), h.coerceAtLeast(72))
    }

    override fun onNativeBoundsChanged(rect: Rect) { nativeWidget.applyBoundsToNative(rect); nativeWidget.requestRepaint() }
    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeOpacityChanged(opacity: Float) { nativeWidget.requestRepaint() }
    override fun onNativeTooltipChanged(tooltip: String?) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativeCursorChanged(cursor: CssCursorValue?) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget) }
    override fun onNativeZIndexChanged(zIndex: Int) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.raiseWidget(it) } }
    override fun onNativeTransformChanged(transform: CssTransform?) { nativeWidget.requestRepaint() }
    override fun onNativeStyleSynced() { KdePainterSupport.applyCommonWidgetProperties(this, nativeWidget); nativeWidget.requestRepaint() }

    override fun dispose() {
        super.dispose()
        nativeWidget.destroyQWidget()
    }
}
