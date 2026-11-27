@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.desktop.layout.DesktopContainerLayoutController
import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.FlexDirection
import com.nexustools.genericui.enums.bootstrap.OverflowBehavior
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.style.transition.CssTransitionSpec
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSEvent
import platform.AppKit.NSView
import platform.CoreGraphics.CGPoint
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect

internal class MacosContainerView(
    private val owner: MacosContainerNativeImpl,
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
        } else if (isPassiveOverlaySpacerZone() && hit == this) {
            return null
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

    private fun isPassiveOverlaySpacerZone(): Boolean {
        val role = (owner.nativeWidget.attributes[DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE] as? String)
            ?.trim()
            ?.lowercase()
        return role == DesktopStageOverlaySpec.ROLE_CENTER_ZONE
    }
}

/**
 * macOS AppKit retained [NSView] implementation of [DesktopContainerNativeImpl].
 */
open class MacosContainerNativeImpl(
    nativeWidget: MacosViewHandle,
    classPrefix: String = "container",
    tagName: String = "div"
) : DesktopContainerNativeImpl<MacosViewHandle>(nativeWidget, classPrefix, tagName) {

    internal var containerView: MacosContainerView? = null
        private set

    internal fun hasWheelListener(): Boolean = onWheelListener != null
    private var lastVisualBoundsW: Int = -1
    private var lastVisualBoundsH: Int = -1
    private var zOrderSyncedInLayout: Boolean = false
    private var hasActiveSemanticSurface: Boolean = false
    private var lastSemanticSpec: com.nexustools.genericui.system.style.SemanticSurfacePaintSpec? = null
    private var lastSemanticClipOverflow: Boolean = false
    private var lastSyncedChildOrder: List<DesktopBaseNativeImpl<*>> = emptyList()
    private var lastSyncedBackdropCount: Int = -1
    private var lastSyncedHasInteractiveSurface: Boolean = false

    init {
        val lowerTag = tagName.lowercase()
        val lowerPrefix = classPrefix.lowercase()
        val defaultDir = when {
            "hstack" in lowerTag -> FlexDirection.ROW
            "vstack" in lowerTag -> FlexDirection.COLUMN
            else -> null
        }
        val layered = "layered" in lowerTag || "layered" in lowerPrefix
        val controller = DesktopContainerLayoutController(
            host = this,
            defaultFlexDirection = defaultDir,
            isLayeredHost = layered
        )
        controller.onZOrderUpdated = { ordered ->
            zOrderSyncedInLayout = true
            syncSubviewsWithOrder(ordered)
        }
        layoutController = controller
        nativeWidget.viewMaterializer = { ensureContainerView() }

        if (MacosAppKitDispatch.isMainThread()) {
            ensureContainerView()
        }
    }

    internal fun ensureContainerView(): MacosContainerView {
        containerView?.let { existing ->
            if (nativeWidget.nsView == null) nativeWidget.nsView = existing
            return existing
        }
        val view = MacosContainerView(this)
        containerView = view
        nativeWidget.nsView = view
        view.setHidden(!nativeWidget.visible)
        view.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        view.setToolTip(nativeWidget.tooltipText)
        syncSemanticSurfaceAppearance()
        syncInteractiveSurfaceAttachment()
        return view
    }

    protected open fun syncMacosContainerAppearance() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { syncMacosContainerAppearance() }
            return
        }
        ensureContainerView()
        syncSemanticSurfaceAppearance()
        syncInteractiveSurfaceAttachment()
        syncSubviewsWithOrder(
            layoutController?.resolveChildrenSortedByZIndex()
                ?: getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
        )
    }

    private fun syncSemanticSurfaceAppearance() {
        val view = containerView ?: return
        lastVisualBoundsW = nativeWidget.bounds.width
        lastVisualBoundsH = nativeWidget.bounds.height
        val darkTheme = MacosAppKitAppearance.isDarkTheme(nativeWidget)
        val semanticSpec = resolveSemanticSurfaceSpec(darkTheme)
        val computed = lastComputedStyle
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
        lastSemanticSpec = semanticSpec
        lastSemanticClipOverflow = clipOverflow
        view.isClippingOverflow = clipOverflow
        view.surfaceSupport.applySemanticSurface(semanticSpec, clipOverflow = clipOverflow)
        hasActiveSemanticSurface = semanticSpec != null || view.surfaceSupport.activeSurfaceSpec != StyledVisualSpec()
    }

    private fun syncInteractiveSurfaceAttachment() {
        val view = containerView ?: return
        val hasSpec = nativeWidget.interactiveSurfaceSpec != null
        if (hasSpec) {
            val createdNow = view.interactiveSurfaceView == null
            if (createdNow) {
                view.interactiveSurfaceView = MacosInteractiveSurfaceView(this)
            }
            val sv = view.interactiveSurfaceView!!
            view.bounds.useContents {
                sv.setFrame(NSMakeRect(0.0, 0.0, size.width, size.height))
            }
            if (createdNow) {
                MacosNativeWindowHost.stageTopologyVersion++
                syncSubviewsWithOrder(
                    layoutController?.resolveChildrenSortedByZIndex()
                        ?: getLogicalChildren().filterIsInstance<DesktopBaseNativeImpl<*>>()
                )
            }
        } else if (view.interactiveSurfaceView != null) {
            view.interactiveSurfaceView?.let { sv ->
                sv.disposeResources()
                sv.removeFromSuperview()
                view.interactiveSurfaceView = null
            }
            MacosNativeWindowHost.stageTopologyVersion++
        }
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
        if (!MacosAppKitDispatch.isMainThread() || isDisposed || containerView?.window == null) return
        layoutController?.invalidateZOrder()
        lastSyncedChildOrder = emptyList()
        syncSubviewsWithOrder(resolveOrderedChildren())
    }

    override fun onChildZIndexInvalidated() {
        super.onChildZIndexInvalidated()
        lastSyncedChildOrder = emptyList()
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed && containerView != null) {
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
        val parentView = ensureContainerView()
        val backdropPinned = parentView.surfaceSupport.pinnedBackdropSubviews()
        val pinnedBack = ArrayList<NSView>(1 + backdropPinned.size)
        parentView.interactiveSurfaceView?.let { pinnedBack.add(it) }
        pinnedBack.addAll(backdropPinned)

        val childViews = ArrayList<NSView>(orderedChildren.size)
        for (child in orderedChildren) {
            val childView = MacosAppKitDispatch.resolveOrCreateChildNsView(child)
            if (childView != null) {
                childViews.add(childView)
            }
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
            if (!isDisposed && containerView != null) {
                syncSubviewsWithOrder(resolveOrderedChildren())
            }
        }
        nativeWidget.requestRetainedRelayout()
    }

    override fun onChildRemovedNative(childImpl: NativeImpl<*>) {
        super.onChildRemovedNative(childImpl)
        lastSyncedChildOrder = emptyList()
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed && containerView != null) {
                syncSubviewsWithOrder(resolveOrderedChildren())
            }
        }
        nativeWidget.requestRetainedRelayout()
    }

    override fun onChildrenClearedNative() {
        super.onChildrenClearedNative()
        lastSyncedChildOrder = emptyList()
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed && containerView != null) {
                syncSubviewsWithOrder(resolveOrderedChildren())
            }
        }
        nativeWidget.requestRetainedRelayout()
    }

    override fun layoutChildrenInBounds(width: Float, height: Float) {
        zOrderSyncedInLayout = false
        val didLayout = layoutController?.layoutInBounds(width, height) ?: true
        if (MacosAppKitDispatch.isMainThread()) {
            val wasRealized = containerView != null
            val view = ensureContainerView()
            val surfaceAttachmentChanged = (view.interactiveSurfaceView != null) != (nativeWidget.interactiveSurfaceSpec != null)
            if (surfaceAttachmentChanged || didLayout || !wasRealized) {
                syncInteractiveSurfaceAttachment()
            }
            val ordered = resolveOrderedChildren()
            if (didLayout || !wasRealized || surfaceAttachmentChanged) {
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
                containerView?.interactiveSurfaceView?.setFrame(
                    NSMakeRect(0.0, 0.0, width.toDouble().coerceAtLeast(0.0), height.toDouble().coerceAtLeast(0.0))
                )
            }
            if (!zOrderSyncedInLayout) {
                val backdropPinned = view.surfaceSupport.pinnedBackdropSubviews()
                val pinnedBack = ArrayList<NSView>(1 + backdropPinned.size)
                view.interactiveSurfaceView?.let { pinnedBack.add(it) }
                pinnedBack.addAll(backdropPinned)
                val expectedChildViews = ArrayList<NSView>(ordered.size)
                for (child in ordered) {
                    val cv = MacosAppKitDispatch.resolveOrCreateChildNsView(child)
                    if (cv != null) expectedChildViews.add(cv)
                }
                val needsOrderSync = !wasRealized ||
                    lastSyncedHasInteractiveSurface != (view.interactiveSurfaceView != null) ||
                    lastSyncedBackdropCount != backdropPinned.size ||
                    ordered.size != lastSyncedChildOrder.size ||
                    ordered.indices.any { ordered[it] !== lastSyncedChildOrder[it] } ||
                    !MacosAppKitDispatch.isChildViewHierarchyInSync(view, expectedChildViews, pinnedBack)
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
        val view = ensureContainerView()
        syncInteractiveSurfaceAttachment()
        MacosAppKitDispatch.applyFrameAndTransform(
            view = view,
            bounds = rect,
            transform = nativeWidget.cssTransform,
            useAlignmentRectExpansion = false,
            knownHandle = nativeWidget
        )
        view.interactiveSurfaceView?.setFrame(
            NSMakeRect(0.0, 0.0, rect.width.coerceAtLeast(0).toDouble(), rect.height.coerceAtLeast(0).toDouble())
        )
        if (rect.width != lastVisualBoundsW || rect.height != lastVisualBoundsH) {
            lastVisualBoundsW = rect.width
            lastVisualBoundsH = rect.height
            if (hasActiveSemanticSurface && view.surfaceSupport.hasBoundsDependentVisuals()) {
                view.surfaceSupport.applySemanticSurface(lastSemanticSpec, clipOverflow = lastSemanticClipOverflow)
            }
        }
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        MacosAppKitDispatch.onMainOrDefer {
            containerView?.setHidden(!visible)
            if (visible) {
                containerView?.surfaceSupport?.refreshBackdropUnderlayIfNeeded(force = true)
            }
            if (isOverlayRootOrTitleBar()) {
                MacosNativeWindowHost.syncAllNativeOverlayTitleBars()
            }
            nativeWidget.requestRetainedRelayout()
        }
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        syncMacosContainerAppearance()
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        MacosAppKitDispatch.onMainOrDefer {
            containerView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
            if (isOverlayRootOrTitleBar()) {
                MacosNativeWindowHost.syncAllNativeOverlayTitleBars()
            }
        }
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        MacosAppKitDispatch.onMainOrDefer {
            containerView?.setToolTip(tooltip)
        }
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        MacosAppKitDispatch.onMainOrDefer {
            containerView?.let { v ->
                v.window?.invalidateCursorRectsForView(v)
            }
        }
    }

    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        // Handled dynamically in MacosContainerView.hitTest
    }

    override fun onNativeZIndexChanged(zIndex: Int) {
        (nativeWidget.parentWidget?.attributes?.get("nativeImpl") as? DesktopBaseContainerNativeImpl<*>)
            ?.onChildZIndexInvalidated()
        nativeWidget.requestRetainedRelayout()
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        MacosAppKitDispatch.onMainOrDefer {
            val view = containerView ?: return@onMainOrDefer
            MacosAppKitDispatch.applyFrameAndTransform(view, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
        }
    }

    override fun onNativeStyleSynced() {
        syncMacosContainerAppearance()
    }

    override fun setAttribute(name: String, value: String?) {
        super.setAttribute(name, value)
        if (name == "class" || name == "role" || name == "componentRole" || name == "tagName") {
            syncMacosContainerAppearance()
        }
    }

    override fun setBackgroundGradient(gradient: Gradient?) {
        super.setBackgroundGradient(gradient)
        syncMacosContainerAppearance()
    }

    override fun setType(type: BootstrapType?) {
        super.setType(type)
        syncMacosContainerAppearance()
    }

    override fun setBackgroundType(type: BootstrapType?) {
        super.setBackgroundType(type)
        syncMacosContainerAppearance()
    }

    override fun setTextColorType(type: BootstrapType?) {
        super.setTextColorType(type)
        syncMacosContainerAppearance()
    }

    override fun setOutline(outline: Boolean) {
        super.setOutline(outline)
        syncMacosContainerAppearance()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super.applyComponentBootstrap(bootstrap)
        syncMacosContainerAppearance()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super.applyComponentStyle(style)
        syncMacosContainerAppearance()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        syncMacosContainerAppearance()
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
        val view = containerView
        containerView = null
        MacosAppKitDispatch.onMainOrDefer {
            view?.interactiveSurfaceView?.disposeResources()
            view?.removeFromSuperview()
        }
        super.dispose()
    }
}
