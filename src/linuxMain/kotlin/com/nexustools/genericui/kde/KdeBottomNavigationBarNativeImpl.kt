@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.widget.DesktopBottomNavigationBarNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer

/**
 * Retained KDE bottom navigation bar backed by a custom-painted Breeze `QWidget`
 * rendering selectable tabs and active Breeze accent indicators.
 */
open class KdeBottomNavigationBarNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("KdeBottomNavigationBar")
) : DesktopBottomNavigationBarNativeImpl<KdeWidgetHandle>(nativeWidget) {

    private var hoveredIndex: Int = -1

    init {
        KdeDynamicBindings.ensureQApplication()
        val ptr = KdeDynamicBindings.createQWidget(null, 0)
        nativeWidget.attachToQWidget(ptr, "KdeBottomNavigationBar", customPainted = true)
        KdePainterSupport.attachStandardInputHooks(
            widgetPtr = ptr,
            impl = this,
            hookMask = KdeVTableBridge.HOOK_PAINT or KdeVTableBridge.HOOK_RESIZE or KdeVTableBridge.HOOK_SIZE_HINT,
            dispatchLeftClickOnRelease = false,
            onPaint = { wPtr, painter, w, h -> paintBottomNav(wPtr, painter, w, h) },
            onSizeHint = { _ -> 320 to 56 }
        )
        KdeVTableBridge.attachWidgetHooks(ptr, KdeVTableBridge.HOOK_INPUT) {
            onMouseMove = { _, info ->
                val count = itemsList.size
                val w = nativeWidget.bounds.width
                val next = if (count > 0 && w > 0) ((info.localX.toInt() * count) / w).coerceIn(0, count - 1) else -1
                if (hoveredIndex != next) {
                    hoveredIndex = next
                    nativeWidget.requestRepaint()
                }
                false
            }
            onLeave = { _ ->
                if (hoveredIndex != -1) {
                    hoveredIndex = -1
                    nativeWidget.requestRepaint()
                }
            }
            onMouseRelease = { _, info ->
                if (info.button == 1 && isEnabled() && itemsList.isNotEmpty()) {
                    val w = nativeWidget.bounds.width.coerceAtLeast(1)
                    val idx = ((info.localX.toInt() * itemsList.size) / w).coerceIn(0, itemsList.size - 1)
                    val clickedId = itemsList[idx].first
                    GenericUiThreading.runOnLogic {
                        setSelectedItemId(clickedId)
                        nativeWidget.requestRepaint()
                    }
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun paintBottomNav(wPtr: COpaquePointer, painter: COpaquePointer, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val isDark = SystemThemeDetector.resolveTheme(ThemeMode.AUTO) == ThemeMode.DARK
        val computed = getComputedStyle()
        val bg = getEffectiveBackgroundColor() ?: computed?.backgroundColor ?: if (isDark) RgbaColor(35, 38, 41) else RgbaColor(239, 240, 241)
        val border = computed?.borderColor ?: if (isDark) RgbaColor(60, 64, 67) else RgbaColor(210, 213, 216)
        val accent = getEffectiveAccentColor(isDark) ?: KdeApplicationNativeImpl.breezeHighlightColor()
        val fgMuted = if (isDark) RgbaColor(174, 180, 186) else RgbaColor(92, 99, 106)

        KdeDynamicBindings.painterFillRect(painter, 0.0, 0.0, w.toDouble(), h.toDouble(), bg)
        KdeDynamicBindings.painterFillRect(painter, 0.0, 0.0, w.toDouble(), 1.0, border)

        val items = itemsList
        if (items.isEmpty()) return
        val tabW = w.toDouble() / items.size.toDouble()
        for (i in items.indices) {
            val (id, label, _) = items[i]
            val selected = (id == selectedId)
            val x = i * tabW
            if (selected) {
                KdeDynamicBindings.painterFillRect(painter, x + 6.0, 1.0, (tabW - 12.0).coerceAtLeast(4.0), 3.0, accent)
            } else if (i == hoveredIndex) {
                KdeDynamicBindings.painterFillRect(painter, x + 4.0, 4.0, (tabW - 8.0).coerceAtLeast(4.0), (h - 8).toDouble(), accent.copy(a = 24))
            }
            val textColor = if (selected) accent else fgMuted
            KdeDynamicBindings.painterDrawText(
                p = painter,
                text = label,
                x = x + 4.0,
                y = 6.0,
                w = (tabW - 8.0).coerceAtLeast(4.0),
                h = (h - 10).coerceAtLeast(10).toDouble(),
                color = textColor,
                fontSizePx = computed?.fontSizePx ?: 12f,
                fontWeight = if (selected) 700 else 400,
                italic = false,
                fontFamily = computed?.fontFamily,
                alignmentFlags = KdeDynamicBindings.ALIGN_HCENTER or KdeDynamicBindings.ALIGN_VCENTER
            )
        }
    }

    override fun setItems(items: List<Triple<String, String, String?>>) {
        super.setItems(items)
        nativeWidget.requestRepaint()
    }

    override fun setSelectedItemId(itemId: String) {
        super.setSelectedItemId(itemId)
        nativeWidget.requestRepaint()
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size = Size(maxWidth?.toInt() ?: 320, 56)
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
