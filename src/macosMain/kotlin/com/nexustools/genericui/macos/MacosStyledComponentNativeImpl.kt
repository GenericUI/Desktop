@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.widget.DesktopStyledComponentNativeImpl
import com.nexustools.genericui.enums.bootstrap.OverflowBehavior
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.transition.CssTransitionSpec
import kotlinx.cinterop.CValue
import platform.AppKit.NSEvent
import platform.AppKit.NSView
import platform.CoreGraphics.CGPoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect

internal class MacosStyledComponentView(
    private val owner: MacosStyledComponentNativeImpl,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 0.0, 0.0)
) : NSView(frameRect) {

    val surfaceSupport = MacosSurfaceLayerSupport(this)
    private val interactionController = MacosViewInteractionController(owner, this) { owner.hasWheelListener() }

    init {
        setWantsLayer(true)
        setAutoresizesSubviews(false)
        setPostsFrameChangedNotifications(false)
        interactionController.onVisualStateChanged = {
            owner.setHoveredState(interactionController.isHovered)
        }
    }

    override fun isFlipped(): Boolean = true

    override fun acceptsFirstMouse(event: NSEvent?): Boolean = true

    override fun viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        surfaceSupport.refreshBackdropUnderlayIfNeeded(force = true)
        window?.invalidateCursorRectsForView(this)
    }

    override fun layout() {
        super.layout()
        surfaceSupport.refreshBackdropUnderlayIfNeeded(force = false)
    }

    override fun hitTest(point: CValue<CGPoint>): NSView? {
        if (isHidden() || alphaValue <= 0.004 || owner.nativeWidget.isSelfPointerEventsNone()) {
            return null
        }
        return super.hitTest(point)
    }

    override fun updateTrackingAreas() {
        super.updateTrackingAreas()
        interactionController.refreshTrackingAreas()
    }

    override fun resetCursorRects() {
        interactionController.applyCursorRects()
    }

    override fun cursorUpdate(event: NSEvent) {
        interactionController.handleCursorUpdate(event)
    }

    override fun mouseDown(event: NSEvent) {
        if (!owner.nativeWidget.enabled || owner.isPointerInteractionDisabled()) return
        owner.setActivePressedState(true)
        interactionController.handleMouseDown(event, buttonIndex = 0)
    }

    override fun mouseDragged(event: NSEvent) {
        interactionController.handleMouseDragged(event, buttonIndex = 0)
    }

    override fun mouseUp(event: NSEvent) {
        owner.setActivePressedState(false)
        interactionController.handleMouseUp(event, buttonIndex = 0)
    }

    override fun rightMouseDown(event: NSEvent) {
        interactionController.handleRightMouseDown(event)
    }

    override fun mouseEntered(event: NSEvent) {
        interactionController.handleMouseEntered(event)
    }

    override fun mouseExited(event: NSEvent) {
        interactionController.handleMouseExited(event)
    }

    override fun mouseMoved(event: NSEvent) {
        interactionController.handleMouseMoved(event)
    }

    override fun scrollWheel(event: NSEvent) {
        if (!interactionController.handleScrollWheel(event)) {
            super.scrollWheel(event)
        }
    }
}

/**
 * macOS AppKit retained [NSView] implementation of [DesktopStyledComponentNativeImpl].
 */
class MacosStyledComponentNativeImpl(
    nativeWidget: MacosViewHandle,
    classPrefix: String = "styled-component",
    tagName: String = "genericui:styled-component"
) : DesktopStyledComponentNativeImpl<MacosViewHandle>(nativeWidget, classPrefix, tagName) {

    private var componentView: MacosStyledComponentView? = null

    internal fun hasWheelListener(): Boolean = onWheelListener != null
    private var lastVisualBoundsW: Int = -1
    private var lastVisualBoundsH: Int = -1

    init {
        nativeWidget.viewMaterializer = {
            ensureComponentView().also { syncMacosStyledComponentVisuals() }
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureComponentView()
            syncMacosStyledComponentVisuals()
        }
    }

    private fun ensureComponentView(): MacosStyledComponentView {
        componentView?.let { existing ->
            if (nativeWidget.nsView == null) nativeWidget.nsView = existing
            return existing
        }
        val view = MacosStyledComponentView(this)
        componentView = view
        nativeWidget.nsView = view
        view.setHidden(!nativeWidget.visible)
        view.setToolTip(nativeWidget.tooltipText)
        return view
    }

    private fun syncMacosStyledComponentVisuals() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { syncMacosStyledComponentVisuals() }
            return
        }
        val view = ensureComponentView()
        lastVisualBoundsW = nativeWidget.bounds.width
        lastVisualBoundsH = nativeWidget.bounds.height
        val isDark = MacosAppKitAppearance.isDarkTheme(nativeWidget)
        val resolved = resolveCurrentVisuals()
        val computed = lastComputedStyle

        val effOpacity = getOpacity().coerceIn(0f, 1f)
        view.setAlphaValue(effOpacity.toDouble())

        val clipOverflow = computed?.overflowX == OverflowBehavior.HIDDEN ||
            computed?.overflowY == OverflowBehavior.HIDDEN ||
            computed?.overflow.equals("hidden", ignoreCase = true)

        view.surfaceSupport.applyStyledVisuals(
            spec = resolved,
            cornerRadii = computed?.borderRadius,
            boxShadow = computed?.boxShadowList?.shadows?.firstOrNull(),
            isFocused = isFocusedState(),
            clipOverflow = clipOverflow,
            darkTheme = isDark,
            effectiveBackdropFilter = nativeWidget.attributes["backdropFilter"] as? CssFilter,
            effectiveForegroundFilter = nativeWidget.attributes["cssFilter"] as? CssFilter
        )
        MacosAppKitDispatch.syncChildViewHierarchy(
            parentView = view,
            orderedChildViews = emptyList(),
            pinnedBackViews = view.surfaceSupport.pinnedBackdropSubviews()
        )
    }

    private fun hasBoundsDependentVisualEffects(): Boolean {
        val effBackdrop = nativeWidget.attributes["backdropFilter"] as? CssFilter
        if (effBackdrop != null && !effBackdrop.isNone && effBackdrop.hasActiveEffects) return true
        val effForeground = nativeWidget.attributes["cssFilter"] as? CssFilter
        if (effForeground != null && !effForeground.isNone && effForeground.hasActiveEffects) return true
        if (isFocusedState()) return true
        val resolved = (nativeWidget.attributes["styledComponentVisuals"]
            ?: nativeWidget.attributes["styledVisuals"]) as? StyledVisualSpec
        if (resolved != null && (
                resolved.backgroundGradient != null ||
                    resolved.boxShadowColor != null ||
                    (resolved.borderRadiusPx ?: 0f) > 0f ||
                    (resolved.borderWidthPx ?: 0f) > 0f
                )
        ) {
            return true
        }
        val computed = lastComputedStyle ?: return false
        if (computed.backgroundGradient != null || computed.borderRadius != null) return true
        if ((computed.borderRadiusPx ?: 0f) > 0f || (computed.borderWidthPx ?: 0f) > 0f) return true
        if (computed.boxShadowList?.shadows?.isNotEmpty() == true) return true
        return false
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer {
                if (!isDisposed) onNativeBoundsChanged(nativeWidget.bounds)
            }
            return
        }
        val view = ensureComponentView()
        MacosAppKitDispatch.applyFrameAndTransform(view, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
        if (rect.width != lastVisualBoundsW || rect.height != lastVisualBoundsH) {
            lastVisualBoundsW = rect.width
            lastVisualBoundsH = rect.height
            if (hasBoundsDependentVisualEffects()) {
                syncMacosStyledComponentVisuals()
            }
        }
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        MacosAppKitDispatch.onMainOrDefer {
            componentView?.setHidden(!visible)
            if (visible) {
                componentView?.surfaceSupport?.refreshBackdropUnderlayIfNeeded(force = true)
            }
            nativeWidget.requestRetainedRelayout()
        }
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        syncMacosStyledComponentVisuals()
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        MacosAppKitDispatch.onMainOrDefer {
            componentView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
        }
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        componentView?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        componentView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeZIndexChanged(zIndex: Int) {
        nativeWidget.requestRetainedRelayout()
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val view = componentView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(view, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
    }

    override fun onNativeStyleSynced() {
        syncMacosStyledComponentVisuals()
    }

    override fun runNativeTransition(
        property: String,
        fromValue: String?,
        toValue: String,
        spec: CssTransitionSpec,
        onComplete: () -> Unit
    ): Boolean {
        val handled = super.runNativeTransition(property, fromValue, toValue, spec, onComplete)
        if (property.equals("opacity", ignoreCase = true)) {
            val fromAlpha = (fromValue?.toFloatOrNull() ?: getOpacity()).coerceIn(0f, 1f)
            val targetAlpha = (toValue.toFloatOrNull() ?: getOpacity()).coerceIn(0f, 1f)
            nativeWidget.attributes["transitionFromOpacity"] = fromAlpha
            nativeWidget.attributes["transitionToOpacity"] = targetAlpha
            setOpacity(targetAlpha)
            if (spec.durationMs <= 0f) {
                onComplete()
            }
            return true
        }
        return handled
    }

    override fun dispose() {
        val view = componentView
        componentView = null
        MacosAppKitDispatch.detachViewOnMain(view)
        super.dispose()
    }
}
