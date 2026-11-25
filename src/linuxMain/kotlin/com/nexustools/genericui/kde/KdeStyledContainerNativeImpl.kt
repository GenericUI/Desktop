@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.desktop.widget.DesktopStyledContainerNativeImpl
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.css.CssDisplay
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import kotlinx.cinterop.COpaquePointer

/**
 * Retained KDE custom-painted container (`KdeStyledContainerWidget`) rendering multi-stop CSS
 * gradients, rounded corners, CSS borders (solid/dashed/dotted/double), box shadows, focus rings,
 * and real preceding-sibling `QWidget::render` + 10-op `backdrop-filter` via
 * [LinuxBackdropFilterController].
 */
open class KdeStyledContainerNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("KdeStyledContainerWidget"),
    classPrefix: String = "styled-container",
    tagName: String = "div"
) : DesktopStyledContainerNativeImpl<KdeWidgetHandle>(nativeWidget, classPrefix, tagName) {

    init {
        KdeDynamicBindings.ensureQApplication()
        nativeWidget.attributes["tagName"] = tagName
        nativeWidget.attributes["useSystemControlAppearance"] = "false"
        val isLayered = tagName.equals("genericui:layered", ignoreCase = true) ||
            tagName.equals("genericui:layered-container", ignoreCase = true) ||
            classPrefix.contains("layered", ignoreCase = true) ||
            nativeWidget.cachedAllClassTokens.any {
                it.equals("layered", ignoreCase = true) ||
                    it.equals("layered-container", ignoreCase = true) ||
                    it.equals("gui-layered-container", ignoreCase = true)
            }

        val ptr = KdeDynamicBindings.createQWidget(null, 0)
        nativeWidget.attachToQWidget(ptr, "KdeStyledContainerWidget", customPainted = true)
        KdeDynamicBindings.setWidgetAttribute(ptr, KdeDynamicBindings.WA_HOVER, true)

        layoutController = DesktopContainerLayoutController(
            host = this,
            defaultDisplay = if (isLayered) CssDisplay.BLOCK else null,
            defaultFlexDirection = if (isLayered) null else FlexDirection.COLUMN,
            isLayeredHost = isLayered,
            applyScaleToBounds = false
        )

        KdePainterSupport.attachStandardInputHooks(
            widgetPtr = ptr,
            impl = this,
            hookMask = KdeVTableBridge.HOOK_PAINT or KdeVTableBridge.HOOK_RESIZE,
            dispatchLeftClickOnRelease = true,
            onPaint = { wPtr, painter, w, h -> paintStyledContainer(wPtr, painter, w, h) }
        )
    }

    private fun paintStyledContainer(widgetPtr: COpaquePointer, painter: COpaquePointer, w: Int, h: Int) {
        if (nativeWidget.interactiveSurfaceSpec != null) {
            if (KdeInteractiveSurfaceWidget.paintInteractiveSurface(nativeWidget, painter, w, h)) {
                return
            }
        }
        val visuals = getStyledVisuals()
        val computed = getComputedStyle()
        val backdrop = nativeWidget.attributes["backdropFilter"] as? com.nexustools.genericui.style.css.CssFilter ?: visuals.backdropFilter
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
            focused = false,
            opacity = getOpacity()
        )
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size =
        layoutController?.measurePreferredSize(maxWidth, maxHeight) ?: super.measureIntrinsicSize(maxWidth, maxHeight)

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        super.layoutChildrenInBounds(width, height)
        KdePainterSupport.restackChildrenByZOrder(childImpls)
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(nativeWidget)
        val b = nativeWidget.bounds
        if (b.width > 0 && b.height > 0) layoutChildrenInBounds(b.width.toFloat(), b.height.toFloat())
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        (childImpl.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        super.onChildRemovedNative(childImpl)
    }

    override fun onChildrenClearedNative() {
        for (c in childImpls) (c.nativeWidget as? KdeWidgetHandle)?.syncChildParentAndVisibility(null)
        super.onChildrenClearedNative()
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        nativeWidget.applyBoundsToNative(rect)
        layoutChildrenInBounds(rect.width.toFloat(), rect.height.toFloat())
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

    override fun dispose() {
        nativeWidget.destroyQWidget()
        super.dispose()
    }
}
