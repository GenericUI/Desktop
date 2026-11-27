@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.desktop.widget.DesktopStyledComponentNativeImpl
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import kotlinx.cinterop.COpaquePointer

/**
 * Retained KDE custom-painted leaf surface (`KdeStyledComponentWidget`) rendering gradients,
 * rounded corners, borders, box shadows, focus rings, and 10-op CSS `backdrop-filter`.
 */
open class KdeStyledComponentNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("KdeStyledComponentWidget"),
    classPrefix: String = "styled-component",
    tagName: String = "genericui:styled-component"
) : DesktopStyledComponentNativeImpl<KdeWidgetHandle>(nativeWidget, classPrefix, tagName) {

    private var hovered = false
    private var pressed = false

    init {
        KdeDynamicBindings.ensureQApplication()
        nativeWidget.attributes["useSystemControlAppearance"] = "false"
        val ptr = KdeDynamicBindings.createQWidget(null, 0)
        nativeWidget.attachToQWidget(ptr, "KdeStyledComponentWidget", customPainted = true)
        KdeDynamicBindings.setWidgetAttribute(ptr, KdeDynamicBindings.WA_HOVER, true)

        KdePainterSupport.attachStandardInputHooks(
            widgetPtr = ptr,
            impl = this,
            hookMask = KdeVTableBridge.HOOK_PAINT or KdeVTableBridge.HOOK_RESIZE,
            dispatchLeftClickOnRelease = true,
            onPaint = { wPtr, painter, w, h -> paintStyledComponent(wPtr, painter, w, h) },
            onHoverChanged = { h -> hovered = h; setHoveredState(h); nativeWidget.requestRepaint() },
            onPressedChanged = { p -> pressed = p; setActivePressedState(p); nativeWidget.requestRepaint() }
        )
        KdeVTableBridge.attachWidgetHooks(ptr, KdeVTableBridge.HOOK_INPUT) {
            onFocusIn = { _ -> setFocusedState(true); nativeWidget.requestRepaint() }
            onFocusOut = { _ -> setFocusedState(false); nativeWidget.requestRepaint() }
        }
    }

    private fun paintStyledComponent(widgetPtr: COpaquePointer, painter: COpaquePointer, w: Int, h: Int) {
        if (nativeWidget.interactiveSurfaceSpec != null) {
            if (KdeInteractiveSurfaceWidget.paintInteractiveSurface(nativeWidget, painter, w, h)) {
                return
            }
        }
        val visuals = resolveCurrentVisuals()
        val computed = getComputedStyle()
        val backdrop = KdePainterSupport.resolveWidgetBackdropFilter(nativeWidget, visuals)
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
            focused = isFocusedState(),
            opacity = visuals.opacity ?: getOpacity(),
            isPressed = pressed,
            isHovered = hovered
        )
    }

    override fun setAttribute(name: String, value: String?) {
        super.setAttribute(name, value)
        KdePainterSupport.handleStyledBackdropAttribute(this, name, value)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        nativeWidget.applyBoundsToNative(rect)
        nativeWidget.requestRepaint()
    }

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

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        KdePainterSupport.syncWidgetFocus(this, isFocusedState())
        nativeWidget.requestRepaint()
    }

    override fun dispose() {
        nativeWidget.destroyQWidget()
        super.dispose()
    }
}
