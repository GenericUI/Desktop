@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.widget.DesktopStyledButtonNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import kotlinx.cinterop.COpaquePointer

/**
 * Retained KDE custom-styled button (`KdeStyledButtonWidget`) with hover/press/focus/disabled state
 * visuals, rounded backgrounds, gradients, borders, 10-op `backdrop-filter`, and centered typography.
 */
open class KdeStyledButtonNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("KdeStyledButtonWidget"),
    classPrefix: String = "styled-btn"
) : DesktopStyledButtonNativeImpl<KdeWidgetHandle>(nativeWidget, classPrefix) {

    private var hovered = false
    private var pressed = false
    private var focused = false

    init {
        KdeDynamicBindings.ensureQApplication()
        nativeWidget.attributes["useSystemControlAppearance"] = "false"
        val ptr = KdeDynamicBindings.createQWidget(null, 0)
        nativeWidget.attachToQWidget(ptr, "KdeStyledButtonWidget", customPainted = true)
        KdeDynamicBindings.setWidgetAttribute(ptr, KdeDynamicBindings.WA_HOVER, true)
        KdeDynamicBindings.setWidgetFocusPolicy(ptr, KdeDynamicBindings.FOCUS_STRONG_FOCUS)

        KdePainterSupport.attachStandardInputHooks(
            widgetPtr = ptr,
            impl = this,
            hookMask = KdeVTableBridge.HOOK_PAINT or KdeVTableBridge.HOOK_RESIZE or KdeVTableBridge.HOOK_SIZE_HINT,
            dispatchLeftClickOnRelease = true,
            onPaint = { wPtr, painter, w, h -> paintStyledButton(wPtr, painter, w, h) },
            onSizeHint = { _ ->
                val sz = measureIntrinsicSize(null, null)
                sz.width.toInt().coerceAtLeast(32) to sz.height.toInt().coerceAtLeast(24)
            },
            onHoverChanged = { h -> hovered = h; nativeWidget.requestRepaint() },
            onPressedChanged = { p -> pressed = p; nativeWidget.requestRepaint() }
        )
        KdeVTableBridge.attachWidgetHooks(ptr, KdeVTableBridge.HOOK_INPUT) {
            onFocusIn = { _ -> focused = true; nativeWidget.requestRepaint() }
            onFocusOut = { _ -> focused = false; nativeWidget.requestRepaint() }
        }
    }

    private fun paintStyledButton(widgetPtr: COpaquePointer, painter: COpaquePointer, w: Int, h: Int) {
        val visuals = resolveCurrentVisuals()
        val computed = getComputedStyle()
        val backdrop = nativeWidget.attributes["backdropFilter"] as? CssFilter ?: visuals.backdropFilter
        val grad = visuals.backgroundGradient ?: (nativeWidget.attributes["backgroundGradient"] as? Gradient)

        KdePainterSupport.paintStyledSurface(
            widgetPtr = widgetPtr,
            painterPtr = painter,
            widthPx = w,
            heightPx = h,
            spec = visuals,
            radii = computed?.borderRadius,
            gradientOverride = grad,
            backdropFilterOverride = backdrop,
            focused = focused,
            opacity = getOpacity(),
            isPressed = pressed,
            isHovered = hovered
        )

        val label = getText()
        if (label.isNotEmpty()) {
            val textColor = visuals.textColor ?: getEffectiveTextColor() ?: RgbaColor(0x23, 0x26, 0x29)
            val fontSize = computed?.fontSizePx ?: (nativeWidget.attributes["fontSizePx"] as? Number)?.toFloat() ?: 14f
            val bold = (computed?.fontWeightNumeric ?: 400) >= 600
            KdeDynamicBindings.painterDrawText(
                p = painter,
                text = label,
                x = 0.0,
                y = 0.0,
                w = w.toDouble(),
                h = h.toDouble(),
                color = textColor,
                fontSizePx = fontSize,
                fontWeight = if (bold) 700 else 400,
                fontFamily = computed?.fontFamily,
                alignmentFlags = KdeDynamicBindings.ALIGN_CENTER
            )
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val base = super.measureIntrinsicSize(maxWidth, maxHeight)
        val label = getText()
        if (label.isEmpty()) return base
        val fontSize = lastComputedStyle?.fontSizePx ?: (nativeWidget.attributes["fontSizePx"] as? Number)?.toFloat() ?: 14f
        val measured = KdeDynamicBindings.measureTextSize(label, fontSize)
        return Size(
            maxOf(base.width.toFloat(), (measured.first + 24.0).toFloat()),
            maxOf(base.height.toFloat(), (measured.second + 14.0).toFloat())
        )
    }

    override fun onNativeBoundsChanged(rect: Rect) { nativeWidget.applyBoundsToNative(rect); nativeWidget.requestRepaint() }
    override fun onNativeVisibilityChanged(visible: Boolean) { nativeWidget.syncVisibilityToNative(visible) }
    override fun onNativeEnabledChanged(enabled: Boolean) { nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) } }
    override fun onNativeOpacityChanged(opacity: Float) { nativeWidget.requestRepaint() }
    override fun onNativeTooltipChanged(tooltip: String?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, nativeWidget.isSelfPointerEventsNone(), tooltip, getOpacity())
    }
    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, cursor, nativeWidget.isSelfPointerEventsNone(), nativeWidget.tooltipText, getOpacity())
    }
    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, pointerEventsNone, nativeWidget.tooltipText, getOpacity())
    }
    override fun onNativeTransformChanged(transform: CssTransform?) { nativeWidget.requestRepaint() }
    override fun onNativeZIndexChanged(zIndex: Int) { KdePainterSupport.restackParentByZOrder(nativeWidget) }
    override fun onNativeStyleSynced() { nativeWidget.requestRepaint() }
    override fun requestRepaintNative() { nativeWidget.requestRepaint() }

    override fun dispose() {
        nativeWidget.destroyQWidget()
        super.dispose()
    }
}
