@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.desktop.widget.DesktopStyledContainerNativeImpl
import com.nexustools.genericui.enums.bootstrap.OverflowBehavior
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
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

internal class MacosStyledContainerView(
    private val owner: MacosStyledContainerNativeImpl,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 0.0, 0.0)
) : NSView(frameRect) {

    val surfaceSupport = MacosSurfaceLayerSupport(this)
    private val interactionController = MacosViewInteractionController(owner, this) { owner.hasWheelListener() }
    var interactiveSurfaceView: MacosInteractiveSurfaceView? = null
    internal var isClippingOverflow: Boolean = false

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

    override fun mouseDownCanMoveWindow(): Boolean = isWithinOverlayTitleBarNonControlArea(this)

    override fun viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        surfaceSupport.refreshBackdropUnderlayIfNeeded(force = true)
        if (window != null) {
            owner.resyncChildZOrderOnWindowAttach()
        }
        if (owner.nativeWidget.attributes["cursor"] != null || owner.nativeWidget.attributes["customCursor"] != null) {
            window?.invalidateCursorRectsForView(this)
        }
    }

    override fun layout() {
        super.layout()
        surfaceSupport.refreshBackdropUnderlayIfNeeded(force = false)
    }

    override fun hitTest(point: CValue<CGPoint>): NSView? {
        if (isHidden() || alphaValue <= 0.004) return null
        val hit = super.hitTest(point)
        if (hit == null) {
            return if (!isClippingOverflow) {
                MacosAppKitDispatch.hitTestOverflowChildren(this, point)
            } else {
                null
            }
        }
        if (owner.nativeWidget.isSelfPointerEventsNone()) {
            if (hit == this || MacosFocusSupport.shouldPassThroughPointerHit(hit, this)) {
                return null
            }
        }
        return hit
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
        if (handleOverlayTitleBarMouseDown(this, event)) {
            return
        }
        interactionController.handleMouseDown(event, buttonIndex = 0)
    }

    override fun mouseDragged(event: NSEvent) {
        interactionController.handleMouseDragged(event, buttonIndex = 0)
    }

    override fun mouseUp(event: NSEvent) {
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
 * macOS AppKit retained [NSView] implementation of [DesktopStyledContainerNativeImpl] supporting
 * gradients, borders, shadows, focus rings, opacity, and all 10 W3C `filter` / `backdrop-filter`
 * operations via [MacosSurfaceLayerSupport].
 */
class MacosStyledContainerNativeImpl(
    nativeWidget: MacosViewHandle,
    classPrefix: String = "styled-container",
    tagName: String = "genericui:styled-container"
) : DesktopStyledContainerNativeImpl<MacosViewHandle>(nativeWidget, classPrefix, tagName) {

    private var styledView: MacosStyledContainerView? = null

    internal fun hasWheelListener(): Boolean = onWheelListener != null
    private var lastVisualBoundsW: Int = -1
    private var lastVisualBoundsH: Int = -1
    private var zOrderSyncedInLayout: Boolean = false
    private var lastSyncedChildOrder: List<DesktopBaseNativeImpl<*>> = emptyList()
    private var lastSyncedBackdropCount: Int = -1
    private var lastSyncedHasInteractiveSurface: Boolean = false

    init {
        val controller = DesktopContainerLayoutController(
            host = this,
            isLayeredHost = "layered" in classPrefix.lowercase() || "layered" in tagName.lowercase()
        )
        controller.onZOrderUpdated = { ordered ->
            zOrderSyncedInLayout = true
            syncSubviewsWithOrder(ordered)
        }
        layoutController = controller
        nativeWidget.viewMaterializer = {
            ensureStyledView().also { syncMacosStyledContainerVisuals() }
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureStyledView()
            syncMacosStyledContainerVisuals()
        }
    }

    private fun ensureStyledView(): MacosStyledContainerView {
        styledView?.let { existing ->
            if (nativeWidget.nsView == null) nativeWidget.nsView = existing
            return existing
        }
        val view = MacosStyledContainerView(this)
        styledView = view
        nativeWidget.nsView = view
        view.setHidden(!nativeWidget.visible)
        view.setToolTip(nativeWidget.tooltipText)
        return view
    }

    private fun syncMacosStyledContainerVisuals(syncChildren: Boolean = true) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { syncMacosStyledContainerVisuals(syncChildren = syncChildren) }
            return
        }
        val view = ensureStyledView()
        lastVisualBoundsW = nativeWidget.bounds.width
        lastVisualBoundsH = nativeWidget.bounds.height
        val isDark = MacosAppKitAppearance.isDarkTheme(nativeWidget)
        val resolved = (nativeWidget.attributes["styledContainerVisuals"]
            ?: nativeWidget.attributes["styledVisuals"]) as? StyledVisualSpec
            ?: getStyledVisuals()
        val computed = lastComputedStyle

        val effOpacity = getOpacity().coerceIn(0f, 1f)
        view.setAlphaValue(effOpacity.toDouble())

        val compStyle = getComponentStyle()
        val classTokens = getClassTokensSet()
        val clipOverflow = computed?.overflowX == OverflowBehavior.HIDDEN ||
            computed?.overflowY == OverflowBehavior.HIDDEN ||
            computed?.overflow.equals("hidden", ignoreCase = true) ||
            compStyle?.overflowX == OverflowBehavior.HIDDEN ||
            compStyle?.overflowY == OverflowBehavior.HIDDEN ||
            compStyle?.overflow == OverflowBehavior.HIDDEN ||
            "overflow-hidden" in classTokens ||
            "gui-layered-container" in classTokens
        view.isClippingOverflow = clipOverflow
        val effBackdrop = nativeWidget.attributes["backdropFilter"] as? CssFilter
        val effForeground = nativeWidget.attributes["cssFilter"] as? CssFilter

        view.surfaceSupport.applyStyledVisuals(
            spec = resolved,
            cornerRadii = computed?.borderRadius,
            boxShadow = computed?.boxShadowList?.shadows?.firstOrNull(),
            isFocused = isFocusedState(),
            clipOverflow = clipOverflow,
            darkTheme = isDark,
            effectiveBackdropFilter = effBackdrop,
            effectiveForegroundFilter = effForeground
        )

        if (syncChildren) {
            syncSubviewsWithOrder(
                layoutController?.resolveChildrenSortedByZIndex()
                    ?: getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
            )
        }
    }

    private fun hasBoundsDependentVisualEffects(): Boolean {
        val effBackdrop = nativeWidget.attributes["backdropFilter"] as? CssFilter
        if (effBackdrop != null && !effBackdrop.isNone && effBackdrop.hasActiveEffects) return true
        val effForeground = nativeWidget.attributes["cssFilter"] as? CssFilter
        if (effForeground != null && !effForeground.isNone && effForeground.hasActiveEffects) return true
        if (isFocusedState()) return true
        val resolved = (nativeWidget.attributes["styledContainerVisuals"]
            ?: nativeWidget.attributes["styledVisuals"]) as? StyledVisualSpec
            ?: getStyledVisuals()
        if (resolved.backgroundGradient != null || resolved.boxShadowColor != null) return true
        if ((resolved.borderRadiusPx ?: 0f) > 0f || (resolved.borderWidthPx ?: 0f) > 0f) return true
        val computed = lastComputedStyle ?: return false
        if (computed.backgroundGradient != null || computed.borderRadius != null) return true
        if ((computed.borderRadiusPx ?: 0f) > 0f || (computed.borderWidthPx ?: 0f) > 0f) return true
        if (computed.boxShadowList?.shadows?.isNotEmpty() == true) return true
        return false
    }

    private fun resolveOrderedChildren(): List<DesktopBaseNativeImpl<*>> =
        layoutController?.resolveChildrenSortedByZIndex()
            ?: getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()

    private fun isOverlayRootOrTitleBar(): Boolean {
        if (nativeWidget.attributes["data-overlay-root"] == "true") return true
        val role = (nativeWidget.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] as? String)
            ?.trim()
            ?.lowercase()
        return role == DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR
    }

    internal fun resyncChildZOrderOnWindowAttach() {
        if (!MacosAppKitDispatch.isMainThread() || isDisposed || styledView?.window == null) return
        layoutController?.invalidateZOrder()
        lastSyncedChildOrder = emptyList()
        syncSubviewsWithOrder(resolveOrderedChildren())
    }

    override fun onChildZIndexInvalidated() {
        super.onChildZIndexInvalidated()
        lastSyncedChildOrder = emptyList()
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed && styledView != null) {
                syncSubviewsWithOrder(resolveOrderedChildren())
            }
        }
        nativeWidget.notifyTreeInvalidated(visualOnly = false)
        nativeWidget.requestRetainedRelayout()
    }

    private fun syncSubviewsWithOrder(orderedChildren: List<DesktopBaseNativeImpl<*>>) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer {
                if (!isDisposed) syncSubviewsWithOrder(resolveOrderedChildren())
            }
            return
        }
        val parentView = ensureStyledView()
        val backdropPinned = parentView.surfaceSupport.pinnedBackdropSubviews()
        val pinnedBack = ArrayList<NSView>(1 + backdropPinned.size)
        parentView.interactiveSurfaceView?.let { pinnedBack.add(it) }
        pinnedBack.addAll(backdropPinned)

        val childViews = ArrayList<NSView>(orderedChildren.size)
        for (child in orderedChildren) {
            val v = MacosAppKitDispatch.resolveOrCreateChildNsView(child)
            if (v != null) childViews.add(v)
        }
        MacosAppKitDispatch.syncChildViewHierarchy(parentView, childViews, pinnedBack)
        lastSyncedChildOrder = orderedChildren
        lastSyncedBackdropCount = backdropPinned.size
        lastSyncedHasInteractiveSurface = parentView.interactiveSurfaceView != null
    }

    override fun onChildAddedNative(childImpl: NativeImpl<*>, index: Int) {
        super.onChildAddedNative(childImpl, index)
        lastSyncedChildOrder = emptyList()
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed && styledView != null) {
                syncSubviewsWithOrder(resolveOrderedChildren())
            }
        }
        nativeWidget.requestRetainedRelayout()
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        super.onChildRemovedNative(childImpl)
        lastSyncedChildOrder = emptyList()
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed && styledView != null) {
                syncSubviewsWithOrder(resolveOrderedChildren())
            }
        }
        nativeWidget.requestRetainedRelayout()
    }

    override fun onChildrenClearedNative() {
        super.onChildrenClearedNative()
        lastSyncedChildOrder = emptyList()
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed && styledView != null) {
                syncSubviewsWithOrder(resolveOrderedChildren())
            }
        }
        nativeWidget.requestRetainedRelayout()
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        zOrderSyncedInLayout = false
        val didLayout = layoutController?.layoutInBounds(width, height) ?: true
        if (MacosAppKitDispatch.isMainThread()) {
            val wasRealized = styledView != null
            val parentView = ensureStyledView()
            val ordered = resolveOrderedChildren()
            if (didLayout || !wasRealized) {
                for (child in ordered) {
                    val childView = MacosAppKitDispatch.resolveOrCreateChildNsView(child) ?: continue
                    MacosAppKitDispatch.applyFrameAndTransform(
                        view = childView,
                        bounds = child.nativeWidget.bounds,
                        transform = (child.nativeWidget as? MacosViewHandle)?.cssTransform,
                        useAlignmentRectExpansion = child.usesNativeControlAlignmentExpansion(),
                        knownHandle = child.nativeWidget as? MacosViewHandle
                    )
                }
            }
            if (!zOrderSyncedInLayout) {
                val backdropPinned = parentView.surfaceSupport.pinnedBackdropSubviews()
                val pinnedBack = ArrayList<NSView>(1 + backdropPinned.size)
                parentView.interactiveSurfaceView?.let { pinnedBack.add(it) }
                pinnedBack.addAll(backdropPinned)
                val expectedChildViews = ArrayList<NSView>(ordered.size)
                for (child in ordered) {
                    val v = MacosAppKitDispatch.resolveOrCreateChildNsView(child)
                    if (v != null) expectedChildViews.add(v)
                }
                val needsOrderSync = !wasRealized ||
                    lastSyncedHasInteractiveSurface != (parentView.interactiveSurfaceView != null) ||
                    lastSyncedBackdropCount != backdropPinned.size ||
                    ordered.size != lastSyncedChildOrder.size ||
                    ordered.indices.any { ordered[it] !== lastSyncedChildOrder[it] } ||
                    !MacosAppKitDispatch.isChildViewHierarchyInSync(parentView, expectedChildViews, pinnedBack)
                if (needsOrderSync) {
                    syncSubviewsWithOrder(ordered)
                }
            }
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        return layoutController?.measurePreferredSize(maxWidth, maxHeight)
            ?: super.measureIntrinsicSize(maxWidth, maxHeight)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer {
                if (!isDisposed) onNativeBoundsChanged(nativeWidget.bounds)
            }
            return
        }
        val view = ensureStyledView()
        MacosAppKitDispatch.applyFrameAndTransform(
            view = view,
            bounds = rect,
            transform = nativeWidget.cssTransform,
            useAlignmentRectExpansion = false,
            knownHandle = nativeWidget
        )
        if (rect.width != lastVisualBoundsW || rect.height != lastVisualBoundsH) {
            lastVisualBoundsW = rect.width
            lastVisualBoundsH = rect.height
            if (hasBoundsDependentVisualEffects()) {
                syncMacosStyledContainerVisuals(syncChildren = false)
            }
        }
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        MacosAppKitDispatch.onMainOrDefer {
            styledView?.setHidden(!visible)
            if (visible) {
                styledView?.surfaceSupport?.refreshBackdropUnderlayIfNeeded(force = true)
            }
            if (isOverlayRootOrTitleBar()) {
                MacosNativeWindowHost.syncAllNativeOverlayTitleBars()
            }
            nativeWidget.requestRetainedRelayout()
        }
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        syncMacosStyledContainerVisuals()
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        MacosAppKitDispatch.onMainOrDefer {
            styledView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
            if (isOverlayRootOrTitleBar()) {
                MacosNativeWindowHost.syncAllNativeOverlayTitleBars()
            }
        }
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        styledView?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        styledView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeZIndexChanged(zIndex: Int) {
        (nativeWidget.parentWidget?.attributes?.get("nativeImpl") as? DesktopBaseContainerNativeImpl<*>)
            ?.onChildZIndexInvalidated()
        nativeWidget.requestRetainedRelayout()
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val view = styledView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(view, nativeWidget.bounds, transform, useAlignmentRectExpansion = false, knownHandle = nativeWidget)
    }

    override fun onNativeStyleSynced() {
        syncMacosStyledContainerVisuals()
    }

    override fun setAttribute(name: String, value: String?) {
        super.setAttribute(name, value)
        if (name == "backdrop-filter" || name == "backdropFilter") {
            val parsed = CssFilter.parse(value)
            setStyledVisuals(
                getStyledVisuals().copy(
                    backdropBlurPx = parsed?.blur?.resolvePx(),
                    backdropFilter = parsed
                )
            )
        } else if (name == "backdropBlurPx" || name == "data-backdrop-blur-px") {
            val px = value?.trim()?.removeSuffix("px")?.toFloatOrNull()
            val cur = getStyledVisuals()
            val nextBf = cur.backdropFilter?.copy(
                blur = px?.takeIf { it > 0f }?.let { com.nexustools.genericui.style.css.CssLength.px(it) }
            )
            setStyledVisuals(cur.copy(backdropBlurPx = px, backdropFilter = nextBf))
        } else if (name == "filter") {
            val parsed = CssFilter.parse(value)
            setStyledVisuals(getStyledVisuals().copy(filter = parsed))
        }
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
        val view = styledView
        styledView = null
        MacosAppKitDispatch.onMainOrDefer {
            view?.interactiveSurfaceView?.disposeResources()
            view?.removeFromSuperview()
        }
        super.dispose()
    }
}
