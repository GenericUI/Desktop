@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.readValue
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.useContents
import platform.AppKit.NSView
import platform.AppKit.NSWindowAbove
import platform.AppKit.frameForAlignmentRect
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSOrderedAscending
import platform.Foundation.NSOrderedDescending
import platform.Foundation.NSOrderedSame
import platform.Foundation.NSThread
import platform.QuartzCore.CATransform3DIdentity
import platform.QuartzCore.CATransform3DMakeTranslation
import platform.QuartzCore.CATransform3DRotate
import platform.QuartzCore.CATransform3DScale
import platform.QuartzCore.CATransform3DTranslate
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.math.PI

internal class MacosSubviewOrderSpec(
    val pinnedBackViews: List<NSView>,
    val orderedChildViews: List<NSView>
) {
    fun rankFor(view: NSView): Int {
        for (i in pinnedBackViews.indices) {
            if (pinnedBackViews[i] == view) return -10000 + i
        }
        for (i in orderedChildViews.indices) {
            if (orderedChildViews[i] == view) return i
        }
        return 100000
    }
}

internal object MacosAppKitDispatch {

    private val identityTransform3D by lazy { CATransform3DIdentity.readValue() }

    fun isMainThread(): Boolean = NSThread.isMainThread

    inline fun onMainOrDefer(crossinline block: () -> Unit) {
        if (NSThread.isMainThread) {
            block()
        } else {
            dispatch_async(dispatch_get_main_queue()) {
                block()
            }
        }
    }

    fun detachViewOnMain(view: NSView?) {
        if (view != null) {
            onMainOrDefer {
                view.removeFromSuperview()
            }
        }
    }

    fun resolveOrCreateChildNsView(child: DesktopBaseNativeImpl<*>): NSView? {
        val widget = child.nativeWidget
        if (widget is MacosViewHandle) {
            return widget.ensureNsViewOnMain()
        }
        if (widget.interactiveSurfaceSpec != null) {
            val cached = widget.attributes["macosMountedSurfaceView"] as? MacosInteractiveSurfaceView
            val view = cached ?: if (isMainThread()) {
                MacosInteractiveSurfaceView(surfaceWidget = widget).also { created ->
                    widget.attributes["macosMountedSurfaceView"] = created
                }
            } else {
                null
            }
            if (view != null) {
                view.setHidden(!widget.visible)
                view.setAlphaValue(widget.opacity.coerceIn(0f, 1f).toDouble())
                view.setToolTip(widget.tooltipText)
            }
            return view
        }
        return null
    }

    /**
     * Synchronizes [parentView]'s `subviews` to match [orderedChildViews] preceded by any
     * [pinnedBackViews] (e.g. a backdrop `NSVisualEffectView` or background surface view).
     *
     * Retained views remain mounted without calling `removeFromSuperview()`, preserving
     * `NSWindow.firstResponder` across Z-order sorts.
     */
    fun syncChildViewHierarchy(
        parentView: NSView,
        orderedChildViews: List<NSView>,
        pinnedBackViews: List<NSView> = emptyList()
    ) {
        if (!isMainThread()) {
            onMainOrDefer { syncChildViewHierarchy(parentView, orderedChildViews, pinnedBackViews) }
            return
        }
        val currentSubviews = parentView.subviews.filterIsInstance<NSView>()
        val keepSet = ArrayList<NSView>(pinnedBackViews.size + orderedChildViews.size).apply {
            addAll(pinnedBackViews)
            addAll(orderedChildViews)
        }

        for (existing in currentSubviews) {
            if (keepSet.none { it == existing }) {
                existing.removeFromSuperview()
            }
        }

        for (pinned in pinnedBackViews) {
            if (pinned.superview != parentView) {
                if (pinned.superview != null) pinned.removeFromSuperview()
                parentView.addSubview(pinned)
            }
        }
        for (child in orderedChildViews) {
            if (child.superview != parentView) {
                if (child.superview != null) child.removeFromSuperview()
                parentView.addSubview(child)
                MacosFocusSupport.syncHandleFocusableOnAttach(child)
            }
        }

        val updatedSubviews = parentView.subviews
        var orderMatches = updatedSubviews.size == keepSet.size
        if (orderMatches) {
            for (i in keepSet.indices) {
                if (updatedSubviews[i] != keepSet[i]) {
                    orderMatches = false
                    break
                }
            }
        }
        if (!orderMatches) {
            val spec = MacosSubviewOrderSpec(pinnedBackViews, orderedChildViews)
            val specRef = StableRef.create(spec)
            try {
                parentView.sortSubviewsUsingFunction(
                    compare = staticCFunction { viewA, viewB, ctx ->
                        if (ctx == null || viewA == null || viewB == null) {
                            NSOrderedSame
                        } else {
                            val order = ctx.asStableRef<MacosSubviewOrderSpec>().get()
                            val rankA = order.rankFor(viewA)
                            val rankB = order.rankFor(viewB)
                            when {
                                rankA < rankB -> NSOrderedAscending
                                rankA > rankB -> NSOrderedDescending
                                else -> NSOrderedSame
                            }
                        }
                    },
                    context = specRef.asCPointer()
                )
            } finally {
                specRef.dispose()
            }
        }

        for (i in pinnedBackViews.indices) {
            val pinned = pinnedBackViews[i]
            pinned.setWantsLayer(true)
            pinned.layer?.zPosition = (-1000 + i).toDouble()
        }
        for (i in orderedChildViews.indices) {
            val child = orderedChildViews[i]
            child.setWantsLayer(true)
            child.layer?.zPosition = i.toDouble()
        }
    }

    /**
     * Dynamically swaps a mounted [NSView] inside its parent container while preserving layout bounds,
     * Z-order placement, and active window responder focus.
     */
    fun swapMountedView(
        handle: MacosViewHandle,
        oldView: NSView?,
        newView: NSView,
        owner: DesktopBaseNativeImpl<*>
    ) {
        if (oldView == newView) {
            handle.nsView = newView
            return
        }
        val parent = oldView?.superview
        val wasFocused = oldView?.window?.firstResponder == oldView
        val bounds = owner.nativeWidget.bounds

        handle.nsView = newView
        newView.setHidden(!owner.nativeWidget.visible)
        newView.setAlphaValue(owner.nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        newView.setToolTip(owner.nativeWidget.tooltipText)

        if (parent != null && parent != newView && oldView != null) {
            if (newView.superview != parent) {
                parent.addSubview(newView, positioned = NSWindowAbove, relativeTo = oldView)
            }
            oldView.removeFromSuperview()
        } else {
            oldView?.removeFromSuperview()
        }

        handle.lastAppliedFrameView = null
        handle.lastAppliedFrameBounds = null
        handle.lastAppliedFrameTransform = null
        handle.hasNonIdentityLayerTransform = false

        applyFrameAndTransform(
            view = newView,
            bounds = bounds,
            transform = handle.cssTransform,
            useAlignmentRectExpansion = false
        )

        if (wasFocused) {
            newView.window?.makeFirstResponder(newView)
        }
    }

    /**
     * Resets `layer.transform` before applying [bounds] into the AppKit frame
     * (so `NSView.hitTest:`, `convertRect:`, and `cacheDisplayInRect` match visual bounds —
     * `DesktopContainerLayoutController` / `BrowserLayoutEngine` already fold 2D translations
     * into `bounds`), expands standard AppKit control alignment frames when
     * [useAlignmentRectExpansion] is true, and applies centered 2D rotation/scale via
     * `CALayer.transform`.
     */
    fun applyFrameAndTransform(
        view: NSView,
        bounds: Rect,
        transform: CssTransform?,
        useAlignmentRectExpansion: Boolean,
        knownHandle: MacosViewHandle? = null
    ) {
        val handle = knownHandle ?: MacosFocusSupport.directHandleForView(view)
        if (handle != null &&
            handle.lastAppliedFrameView === view &&
            handle.lastAppliedFrameBounds == bounds &&
            handle.lastAppliedFrameTransform == transform &&
            handle.lastAppliedFrameAlignExpand == useAlignmentRectExpansion
        ) {
            return
        }

        val w = bounds.width.coerceAtLeast(0).toDouble()
        val h = bounds.height.coerceAtLeast(0).toDouble()
        val hasLayerTransform = transform != null &&
            !transform.isIdentity &&
            (transform.scaleX != 1f || transform.scaleY != 1f || transform.rotateDeg != 0f) &&
            w > 0.0 &&
            h > 0.0

        if (handle?.hasNonIdentityLayerTransform == true || (handle == null && transform != null && !hasLayerTransform)) {
            view.layer?.let { layer ->
                layer.transform = identityTransform3D
            }
        }

        val frameUnchangedFast = handle != null &&
            handle.lastAppliedFrameView === view &&
            handle.lastAppliedFrameBounds == bounds &&
            handle.lastAppliedFrameAlignExpand == useAlignmentRectExpansion

        if (!frameUnchangedFast) {
            val layoutRect = CGRectMake(
                bounds.x.toDouble(),
                bounds.y.toDouble(),
                w,
                h
            )
            if (!useAlignmentRectExpansion) {
                view.setFrame(layoutRect)
            } else {
                val targetFrame = if (useAlignmentRectExpansion && w > 0.0 && h > 0.0) {
                    view.frameForAlignmentRect(layoutRect)
                } else {
                    layoutRect
                }
                var curX = 0.0
                var curY = 0.0
                var curW = 0.0
                var curH = 0.0
                view.frame.useContents {
                    curX = origin.x
                    curY = origin.y
                    curW = size.width
                    curH = size.height
                }
                val frameChanged = targetFrame.useContents {
                    kotlin.math.abs(curX - origin.x) > 0.5 ||
                        kotlin.math.abs(curY - origin.y) > 0.5 ||
                        kotlin.math.abs(curW - size.width) > 0.5 ||
                        kotlin.math.abs(curH - size.height) > 0.5
                }
                if (frameChanged) {
                    view.setFrame(targetFrame)
                }
            }
        }

        if (hasLayerTransform) {
            val sx = transform!!.scaleX.toDouble()
            val sy = transform.scaleY.toDouble()
            val rotRad = transform.rotateDeg.toDouble() * (PI / 180.0)
            view.setWantsLayer(true)
            val layer = view.layer
            if (layer != null) {
                val ax = layer.anchorPoint.useContents { x }
                val ay = layer.anchorPoint.useContents { y }
                val cx = (0.5 - ax) * w
                val cy = (0.5 - ay) * h

                var matrix = CATransform3DMakeTranslation(cx, cy, 0.0)
                if (rotRad != 0.0) {
                    matrix = CATransform3DRotate(matrix, rotRad, 0.0, 0.0, 1.0)
                }
                if (sx != 1.0 || sy != 1.0) {
                    matrix = CATransform3DScale(matrix, sx, sy, 1.0)
                }
                matrix = CATransform3DTranslate(matrix, -cx, -cy, 0.0)
                layer.transform = matrix
            }
        }

        if (handle != null) {
            handle.lastAppliedFrameView = view
            handle.lastAppliedFrameBounds = bounds
            handle.lastAppliedFrameTransform = transform
            handle.lastAppliedFrameAlignExpand = useAlignmentRectExpansion
            handle.hasNonIdentityLayerTransform = hasLayerTransform
        }
    }

    fun hitTestOverflowChildren(view: NSView, pointInSuperview: kotlinx.cinterop.CValue<platform.CoreGraphics.CGPoint>): NSView? {
        val sv = view.superview ?: return null
        val localPt = view.convertPoint(pointInSuperview, fromView = sv)
        val subs = view.subviews
        for (i in subs.indices.reversed()) {
            val child = subs[i] as? NSView ?: continue
            val childHit = child.hitTest(localPt)
            if (childHit != null) return childHit
        }
        return null
    }
}
