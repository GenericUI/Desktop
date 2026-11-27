@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.apple.style.AppleBackdropFilterController
import com.nexustools.genericui.apple.style.AppleStyledSurfaceHelper
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.style.css.CssCornerRadii
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssShadow
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.system.style.SemanticSurfacePaintSpec
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSView
import platform.AppKit.NSWindow
import platform.AppKit.NSWindowAbove
import platform.AppKit.NSWindowBelow
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGAffineTransformScale
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGImageRetain
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.CoreImage.CIColor
import platform.CoreImage.CIImage
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect
import platform.IOSurface.IOSurfaceGetHeight
import platform.IOSurface.IOSurfaceGetWidth
import platform.QuartzCore.CAGradientLayer
import platform.QuartzCore.CALayer
import platform.QuartzCore.CAShapeLayer
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCAGravityResize
import kotlin.math.roundToInt

private class MacosNonInteractiveSurfaceView(
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 0.0, 0.0)
) : NSView(frameRect) {
    init {
        setWantsLayer(true)
        layer?.contentsGravity = kCAGravityResize
    }

    override fun isFlipped(): Boolean = true

    override fun wantsUpdateLayer(): Boolean = true

    override fun updateLayer() {}

    override fun hitTest(point: CValue<CGPoint>): NSView? = null
}

internal class MacosSurfaceLayerSupport(private val view: NSView) {

    internal companion object {
        private var isCapturingBackdrop: Boolean = false
        private val activeBackdropSupports = LinkedHashSet<MacosSurfaceLayerSupport>()

        internal var onActiveBackdropViewsChanged: (() -> Unit)? = null

        internal fun hasActiveBackdropViewsInWindow(window: NSWindow): Boolean =
            activeBackdropSupports.any { support ->
                isSameNsWindow(support.view.window, window) &&
                    !support.view.isHidden() &&
                    support.backdropUnderlayView != null &&
                    support.activeBackdropFilter?.let { !it.isNone && it.hasActiveEffects } == true
            }

        internal fun refreshBackdropForVideoFrameIfIntersecting(
            window: NSWindow,
            stageViews: List<MacosInteractiveSurfaceView>
        ) {
            if (isCapturingBackdrop || activeBackdropSupports.isEmpty() || stageViews.isEmpty()) return
            val contentView = window.contentView ?: return
            val activeList = activeBackdropSupports.filter { support ->
                isSameNsWindow(support.view.window, window) &&
                    !support.view.isHidden() &&
                    support.backdropUnderlayView != null &&
                    support.activeBackdropFilter?.let { !it.isNone && it.hasActiveEffects } == true
            }
            if (activeList.isEmpty()) return

            for (support in activeList) {
                val filter = support.activeBackdropFilter ?: continue
                val (bw, bh) = support.view.bounds.useContents { size.width to size.height }
                if (bw <= 0.0 || bh <= 0.0) continue
                val padPt = AppleBackdropFilterController.computeBackdropPaddingPt(filter)
                val viewRect = support.view.convertRect(support.view.bounds, toView = contentView)
                val (vx0, vy0, vx1, vy1) = viewRect.useContents {
                    doubleArrayOf(
                        origin.x - padPt,
                        origin.y - padPt,
                        origin.x + size.width + padPt,
                        origin.y + size.height + padPt
                    )
                }
                var intersectsStage = false
                for (stage in stageViews) {
                    if (!isSameNsWindow(stage.window, window) || stage.isHidden()) continue
                    if (stage.currentPresentedIoSurfaceAndFrame() == null && stage.currentCgImage() == null) continue
                    if (!isPaintedBeforeTarget(stage, support.view, contentView)) continue
                    val stageRect = stage.convertRect(stage.bounds, toView = contentView)
                    val (sx0, sy0, sx1, sy1) = stageRect.useContents {
                        doubleArrayOf(origin.x, origin.y, origin.x + size.width, origin.y + size.height)
                    }
                    if (sx0 < vx1 && sx1 > vx0 && sy0 < vy1 && sy1 > vy0) {
                        intersectsStage = true
                        break
                    }
                }
                if (intersectsStage) {
                    support.refreshBackdropUnderlayIfNeeded(force = true, knownStageViews = stageViews)
                }
            }
        }

        internal fun refreshBackdropViewsForWindow(window: NSWindow, force: Boolean = true) {
            if (isCapturingBackdrop || activeBackdropSupports.isEmpty()) return
            val activeList = activeBackdropSupports.filter { support ->
                isSameNsWindow(support.view.window, window) &&
                    !support.view.isHidden() &&
                    support.backdropUnderlayView != null &&
                    support.activeBackdropFilter?.let { !it.isNone && it.hasActiveEffects } == true
            }
            for (support in activeList) {
                support.refreshBackdropUnderlayIfNeeded(force = force)
            }
        }

        private fun isPaintedBeforeTarget(candidate: NSView, target: NSView, root: NSView): Boolean {
            if (candidate === target) return false
            val targetPath = ArrayList<NSView>()
            var t: NSView? = target
            while (t != null) {
                targetPath.add(t)
                if (t === root) break
                t = t.superview
            }
            var cur: NSView? = candidate
            var prev: NSView? = null
            while (cur != null) {
                val idxInTarget = targetPath.indexOf(cur)
                if (idxInTarget >= 0) {
                    if (cur === candidate) return true // candidate is an ancestor of target
                    if (cur === target) return false   // candidate is a descendant of target
                    val candChild = prev ?: return false
                    val targChild = targetPath[idxInTarget - 1]
                    if (candChild === targChild) return false
                    val candZ = candChild.layer?.zPosition ?: 0.0
                    val targZ = targChild.layer?.zPosition ?: 0.0
                    if (candZ != targZ) return candZ < targZ
                    val subs = cur.subviews
                    return subs.indexOf(candChild) < subs.indexOf(targChild)
                }
                if (cur === root) break
                prev = cur
                cur = cur.superview
            }
            return false
        }
    }

    private var surfaceFillLayer: CALayer? = null
    private var gradientLayer: CAGradientLayer? = null
    private var borderLayer: CAShapeLayer? = null
    private var focusRingLayer: CAShapeLayer? = null
    private var activeSurfaceHostLayer: CALayer? = null
    private var lastUnderlayCaptureW: Double = -1.0
    private var lastUnderlayCaptureH: Double = -1.0
    private var lastUnderlayPosX: Double = Double.NaN
    private var lastUnderlayPosY: Double = Double.NaN
    private var lastUnderlayFilter: CssFilter? = null
    private var lastUnderlayTreeMutationVer: Long = -1L
    private var underlayNeedsPostResizeRefresh: Boolean = false

    private var cachedStaticBehindCg: CGImageRef? = null
    private var cachedIntermediateCg: CGImageRef? = null
    private var cachedStaticTreeVer: Long = -1L
    private var cachedStaticCapX: Double = Double.NaN
    private var cachedStaticCapY: Double = Double.NaN
    private var cachedStaticCapW: Double = -1.0
    private var cachedStaticCapH: Double = -1.0
    private var cachedStaticScale: Double = -1.0
    private var cachedStaticStageIds: List<Long> = emptyList()

    private val outputPool = AppleBackdropFilterController.IoSurfacePingPongPool()

    internal var backdropUnderlayView: NSView? = null
        private set
    internal var tintOverlayView: NSView? = null
        private set

    var activeBackdropFilter: CssFilter? = null
        private set
    var activeCornerRadii: CssCornerRadii? = null
        private set
    var activeFallbackRadiusPx: Float = 0f
        private set
    var activeSurfaceSpec: StyledVisualSpec = StyledVisualSpec()
        private set

    internal fun hasBoundsDependentVisuals(): Boolean =
        gradientLayer != null ||
            borderLayer != null ||
            focusRingLayer != null ||
            backdropUnderlayView != null ||
            tintOverlayView != null ||
            ((activeSurfaceSpec.boxShadowColor?.a ?: 0) > 0 && (activeSurfaceSpec.boxShadowBlurPx ?: 0f) > 0f) ||
            (activeSurfaceSpec.borderRadiusPx ?: 0f) > 0f

    internal fun pinnedBackdropSubviews(): List<NSView> {
        val result = ArrayList<NSView>(2)
        backdropUnderlayView?.let { result.add(it) }
        tintOverlayView?.let { result.add(it) }
        return result
    }

    fun applyStyledVisuals(
        spec: StyledVisualSpec,
        cornerRadii: CssCornerRadii?,
        boxShadow: CssShadow?,
        isFocused: Boolean,
        clipOverflow: Boolean,
        darkTheme: Boolean,
        effectiveBackdropFilter: CssFilter?,
        effectiveForegroundFilter: CssFilter?
    ) {
        activeSurfaceSpec = spec
        activeBackdropFilter = effectiveBackdropFilter
        activeCornerRadii = cornerRadii
        val fallbackRadius = spec.borderRadiusPx ?: 0f
        activeFallbackRadiusPx = fallbackRadius

        view.setWantsLayer(true)
        val rootLayer = view.layer ?: return

        val (boundsW, boundsH) = view.bounds.useContents { size.width to size.height }
        val hasBackdrop = effectiveBackdropFilter != null &&
            !effectiveBackdropFilter.isNone &&
            effectiveBackdropFilter.hasActiveEffects

        CATransaction.begin()
        CATransaction.setDisableActions(true)
        try {
            val hasShadow = (boxShadow != null && !boxShadow.inset && boxShadow.color.a > 0) ||
                ((spec.boxShadowColor?.a ?: 0) > 0 && (spec.boxShadowBlurPx ?: 0f) > 0f)
            val rootClampedRadius = AppleStyledSurfaceHelper.applyLayerCornerRadii(
                layer = rootLayer,
                radii = cornerRadii,
                fallbackUniformPx = fallbackRadius,
                boundsWidth = boundsW,
                boundsHeight = boundsH,
                clipsToBounds = clipOverflow && !hasShadow,
                topLeftIsMinY = true
            )

            // In macOS CALayer shadowOffset, +Y points upward; pass topLeftIsMinY=false to flip CSS +Y downward.
            AppleStyledSurfaceHelper.applyLayerShadow(
                layer = rootLayer,
                shadow = boxShadow,
                fallbackColor = spec.boxShadowColor,
                fallbackBlurPx = spec.boxShadowBlurPx,
                topLeftIsMinY = false
            )

            val targetSurfaceLayer: CALayer
            val targetClampedRadius: Double
            if (hasBackdrop) {
                rootLayer.backgroundColor = null
                rootLayer.borderWidth = 0.0
                rootLayer.borderColor = null
                val tintView = tintOverlayView ?: MacosNonInteractiveSurfaceView(
                    CGRectMake(0.0, 0.0, boundsW, boundsH)
                ).also { created ->
                    tintOverlayView = created
                }
                tintView.setFrame(CGRectMake(0.0, 0.0, boundsW, boundsH))
                tintView.setWantsLayer(true)
                val tintLayer = tintView.layer
                if (tintLayer != null) {
                    targetClampedRadius = AppleStyledSurfaceHelper.applyLayerCornerRadii(
                        layer = tintLayer,
                        radii = cornerRadii,
                        fallbackUniformPx = fallbackRadius,
                        boundsWidth = boundsW,
                        boundsHeight = boundsH,
                        clipsToBounds = true,
                        topLeftIsMinY = true
                    )
                    targetSurfaceLayer = tintLayer
                } else {
                    targetClampedRadius = rootClampedRadius
                    targetSurfaceLayer = rootLayer
                }
            } else {
                tintOverlayView?.removeFromSuperview()
                tintOverlayView = null
                targetClampedRadius = rootClampedRadius
                targetSurfaceLayer = rootLayer
            }

            if (activeSurfaceHostLayer !== targetSurfaceLayer) {
                detachSurfaceChromeLayers(activeSurfaceHostLayer)
                activeSurfaceHostLayer = targetSurfaceLayer
            }

            if (spec.backgroundGradient != null) {
                targetSurfaceLayer.backgroundColor = null
                val fillLayer = ensureSurfaceFillLayer(targetSurfaceLayer)
                fillLayer.frame = CGRectMake(0.0, 0.0, boundsW, boundsH)
                AppleStyledSurfaceHelper.applyLayerCornerRadii(
                    layer = fillLayer,
                    radii = cornerRadii,
                    fallbackUniformPx = fallbackRadius,
                    boundsWidth = boundsW,
                    boundsHeight = boundsH,
                    clipsToBounds = true,
                    topLeftIsMinY = true
                )
                fillLayer.backgroundColor = null
                gradientLayer = AppleStyledSurfaceHelper.applyGradientLayer(
                    hostLayer = fillLayer,
                    existingLayer = gradientLayer,
                    gradient = spec.backgroundGradient,
                    boundsWidth = boundsW,
                    boundsHeight = boundsH,
                    cornerRadius = targetClampedRadius,
                    maskedCorners = fillLayer.maskedCorners,
                    topLeftIsMinY = true
                )
            } else {
                gradientLayer?.removeFromSuperlayer()
                gradientLayer = null
                surfaceFillLayer?.removeFromSuperlayer()
                surfaceFillLayer = null
                val bg = spec.backgroundColor
                if (bg != null && bg.a > 0) {
                    AppleStyledSurfaceHelper.withCgColor(bg) { cg ->
                        targetSurfaceLayer.backgroundColor = cg
                    }
                } else {
                    targetSurfaceLayer.backgroundColor = null
                }
            }

            borderLayer = AppleStyledSurfaceHelper.applyBorderLayer(
                hostLayer = targetSurfaceLayer,
                existingBorderLayer = borderLayer,
                boundsWidth = boundsW,
                boundsHeight = boundsH,
                borderColor = spec.borderColor,
                borderWidthPx = spec.borderWidthPx ?: 0f,
                borderStyle = spec.borderStyle,
                radii = cornerRadii,
                fallbackUniformPx = fallbackRadius,
                topLeftIsMinY = true
            )

            focusRingLayer = AppleStyledSurfaceHelper.applyFocusRingLayer(
                hostLayer = targetSurfaceLayer,
                existingRingLayer = focusRingLayer,
                isFocused = isFocused,
                ringColor = spec.focusRingColor,
                ringWidthPx = spec.focusRingWidthPx,
                boundsWidth = boundsW,
                boundsHeight = boundsH,
                radii = cornerRadii,
                fallbackUniformPx = fallbackRadius,
                topLeftIsMinY = true
            )

            syncForegroundFilter(rootLayer, effectiveForegroundFilter)
            syncBackdropFilter(effectiveBackdropFilter, boundsW, boundsH, cornerRadii, fallbackRadius)
        } finally {
            CATransaction.commit()
        }
    }

    fun applySemanticSurface(spec: SemanticSurfacePaintSpec?, clipOverflow: Boolean = false) {
        if (spec == null) {
            clearSurface()
            if (clipOverflow) {
                view.setWantsLayer(true)
            }
            view.layer?.masksToBounds = clipOverflow
            return
        }
        val boxShadow = if (spec.shadowColor != null && (spec.shadowBlurPx ?: 0f) > 0f) {
            CssShadow.px(offsetX = 0f, offsetY = 2f, blurRadius = spec.shadowBlurPx ?: 0f, color = spec.shadowColor!!)
        } else {
            null
        }
        applyStyledVisuals(
            spec = spec.toStyledVisualSpec(includePadding = false),
            cornerRadii = null,
            boxShadow = boxShadow,
            isFocused = false,
            clipOverflow = clipOverflow,
            darkTheme = false,
            effectiveBackdropFilter = null,
            effectiveForegroundFilter = null
        )
    }

    fun clearSurface() {
        activeBackdropFilter = null
        activeSurfaceSpec = StyledVisualSpec()
        clearBackdropUnderlayView()
        tintOverlayView?.removeFromSuperview()
        tintOverlayView = null
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        try {
            detachSurfaceChromeLayers(activeSurfaceHostLayer)
            activeSurfaceHostLayer = null
            view.layer?.apply {
                backgroundColor = null
                borderWidth = 0.0
                borderColor = null
                filters = null
                backgroundFilters = null
                shadowOpacity = 0f
                shadowRadius = 0.0
                shadowColor = null
            }
            view.setLayerUsesCoreImageFilters(false)
        } finally {
            CATransaction.commit()
        }
    }

    internal fun refreshBackdropUnderlayIfNeeded(
        force: Boolean = false,
        knownStageViews: List<MacosInteractiveSurfaceView>? = null
    ) {
        val filter = activeBackdropFilter ?: return
        val underlay = backdropUnderlayView ?: return
        if (filter.isNone || !filter.hasActiveEffects) {
            return
        }
        val (bw, bh) = view.bounds.useContents { size.width to size.height }
        val win = view.window ?: return
        val contentView = win.contentView ?: return
        if (bw <= 0.0 || bh <= 0.0) return
        if (!force && (win.inLiveResize || (win as? MacosHostWindow)?.isPerformingNativeZoom == true)) {
            underlayNeedsPostResizeRefresh = true
            return
        }
        val rectInContent = view.convertRect(view.bounds, toView = contentView)
        val (rx, ry) = rectInContent.useContents { origin.x to origin.y }
        val treeVer = DesktopNativeWidget.globalTreeMutationVersion
        if (!force &&
            underlay.layer?.contents != null &&
            lastUnderlayCaptureW == bw &&
            lastUnderlayCaptureH == bh &&
            lastUnderlayPosX == rx &&
            lastUnderlayPosY == ry &&
            lastUnderlayFilter == filter &&
            lastUnderlayTreeMutationVer == treeVer
        ) {
            return
        }
        underlayNeedsPostResizeRefresh = false
        captureBackdropIntoUnderlay(underlay, filter, bw, bh, knownStageViews)
    }

    internal fun refreshDeferredBackdropUnderlayIfNeeded() {
        if (!underlayNeedsPostResizeRefresh) return
        underlayNeedsPostResizeRefresh = false
        refreshBackdropUnderlayIfNeeded(force = true)
    }

    private fun detachSurfaceChromeLayers(previousHostLayer: CALayer?) {
        gradientLayer?.removeFromSuperlayer()
        gradientLayer = null
        surfaceFillLayer?.removeFromSuperlayer()
        surfaceFillLayer = null
        borderLayer?.removeFromSuperlayer()
        borderLayer = null
        focusRingLayer?.removeFromSuperlayer()
        focusRingLayer = null
        previousHostLayer?.apply {
            backgroundColor = null
            borderWidth = 0.0
            borderColor = null
        }
    }

    private fun registerActiveBackdrop() {
        if (activeBackdropSupports.add(this)) {
            onActiveBackdropViewsChanged?.invoke()
        }
    }

    private fun unregisterActiveBackdrop() {
        if (activeBackdropSupports.remove(this)) {
            onActiveBackdropViewsChanged?.invoke()
        }
    }

    private fun clearBackdropUnderlayView() {
        unregisterActiveBackdrop()
        backdropUnderlayView?.let { ul ->
            ul.layer?.contents = null
            ul.removeFromSuperview()
        }
        backdropUnderlayView = null
        lastUnderlayCaptureW = -1.0
        lastUnderlayCaptureH = -1.0
        lastUnderlayPosX = Double.NaN
        lastUnderlayPosY = Double.NaN
        lastUnderlayFilter = null
        lastUnderlayTreeMutationVer = -1L
        releaseStaticCaches()
        outputPool.releaseAll()
    }

    private fun ensureSurfaceFillLayer(hostLayer: CALayer): CALayer {
        surfaceFillLayer?.let { existing ->
            if (existing.superlayer == hostLayer) return existing
            existing.removeFromSuperlayer()
        }
        val created = CALayer()
        hostLayer.insertSublayer(created, atIndex = 0u)
        surfaceFillLayer = created
        return created
    }

    private fun syncForegroundFilter(rootLayer: CALayer, filter: CssFilter?) {
        if (filter == null || filter.isNone || !filter.hasActiveEffects) {
            rootLayer.filters = null
            view.setLayerUsesCoreImageFilters(false)
            return
        }
        view.setLayerUsesCoreImageFilters(true)
        val ciFilters = AppleBackdropFilterController.buildCoreImageFilters(filter)
        rootLayer.filters = ciFilters.ifEmpty { null }
    }

    private fun syncBackdropFilter(
        filter: CssFilter?,
        boundsW: Double,
        boundsH: Double,
        cornerRadii: CssCornerRadii?,
        fallbackRadius: Float
    ) {
        if (filter == null || filter.isNone || !filter.hasActiveEffects) {
            clearBackdropUnderlayView()
            tintOverlayView?.removeFromSuperview()
            tintOverlayView = null
            return
        }

        val underlay = backdropUnderlayView ?: MacosNonInteractiveSurfaceView(
            frameRect = CGRectMake(0.0, 0.0, boundsW, boundsH)
        ).also { created ->
            backdropUnderlayView = created
            registerActiveBackdrop()
        }
        underlay.setFrame(CGRectMake(0.0, 0.0, boundsW, boundsH))
        underlay.layer?.let { ulLayer ->
            ulLayer.contentsGravity = kCAGravityResize
            AppleStyledSurfaceHelper.applyLayerCornerRadii(
                layer = ulLayer,
                radii = cornerRadii,
                fallbackUniformPx = fallbackRadius,
                boundsWidth = boundsW,
                boundsHeight = boundsH,
                clipsToBounds = true,
                topLeftIsMinY = true
            )
        }
        if (underlay.superview != view) {
            if (underlay.superview != null) underlay.removeFromSuperview()
            view.addSubview(underlay, positioned = NSWindowBelow, relativeTo = null)
        }
        ensureTintOverlaySubviewOrder(underlay)
        val win = view.window
        val contentView = win?.contentView
        if (boundsW > 0.0 && boundsH > 0.0 && win != null && contentView != null) {
            val rectInContent = view.convertRect(view.bounds, toView = contentView)
            val (rx, ry) = rectInContent.useContents { origin.x to origin.y }
            val treeVer = DesktopNativeWidget.globalTreeMutationVersion
            if (underlay.layer?.contents == null ||
                lastUnderlayCaptureW != boundsW ||
                lastUnderlayCaptureH != boundsH ||
                lastUnderlayPosX != rx ||
                lastUnderlayPosY != ry ||
                lastUnderlayFilter != filter ||
                lastUnderlayTreeMutationVer != treeVer
            ) {
                if (win.inLiveResize || (win as? MacosHostWindow)?.isPerformingNativeZoom == true) {
                    underlayNeedsPostResizeRefresh = true
                } else {
                    underlayNeedsPostResizeRefresh = false
                    captureBackdropIntoUnderlay(underlay, filter, boundsW, boundsH, knownStageViews = null)
                }
            }
        }
    }

    private fun ensureTintOverlaySubviewOrder(backView: NSView) {
        val tintView = tintOverlayView ?: return
        val subs = view.subviews
        val backIdx = subs.indexOf(backView)
        val tintIdx = subs.indexOf(tintView)
        if (tintView.superview != view || (backIdx >= 0 && tintIdx in 0 until backIdx)) {
            if (tintView.superview != null) tintView.removeFromSuperview()
            if (backView.superview == view) {
                view.addSubview(tintView, positioned = NSWindowAbove, relativeTo = backView)
            } else {
                view.addSubview(tintView, positioned = NSWindowBelow, relativeTo = null)
            }
        }
    }

    private fun captureBackdropIntoUnderlay(
        underlay: NSView,
        filter: CssFilter,
        boundsW: Double,
        boundsH: Double,
        knownStageViews: List<MacosInteractiveSurfaceView>?
    ) {
        if (isCapturingBackdrop || boundsW <= 0.0 || boundsH <= 0.0 || view.isHidden()) return
        val win = view.window ?: return
        val contentView = win.contentView ?: return
        if (contentView === view) return

        val rectInContent = view.convertRect(view.bounds, toView = contentView)
        val (rx, ry, rw, rh) = rectInContent.useContents {
            doubleArrayOf(origin.x, origin.y, size.width, size.height)
        }
        val (cx, cy, cw, ch) = contentView.bounds.useContents {
            doubleArrayOf(origin.x, origin.y, size.width, size.height)
        }
        if (rw <= 0.0 || rh <= 0.0) return

        val padPt = AppleBackdropFilterController.computeBackdropPaddingPt(filter)
        val capX0 = maxOf(cx, rx - padPt)
        val capY0 = maxOf(cy, ry - padPt)
        val capX1 = minOf(cx + cw, rx + rw + padPt)
        val capY1 = minOf(cy + ch, ry + rh + padPt)
        val capW = capX1 - capX0
        val capH = capY1 - capY0
        if (capW <= 0.0 || capH <= 0.0) return

        val scale = win.backingScaleFactor.coerceAtLeast(1.0)
        val capPxW = (capW * scale).roundToInt().coerceAtLeast(1)
        val capPxH = (capH * scale).roundToInt().coerceAtLeast(1)

        val candidateStages = knownStageViews ?: collectStageViews(contentView)
        val behindStages = ArrayList<MacosInteractiveSurfaceView>()
        for (stage in candidateStages) {
            if (!isSameNsWindow(stage.window, win) || stage.isHidden()) continue
            if (stage.currentPresentedIoSurfaceAndFrame() == null && stage.currentCgImage() == null) continue
            if (!isPaintedBeforeTarget(stage, view, contentView)) continue
            val sRect = stage.convertRect(stage.bounds, toView = contentView)
            val (sx0, sy0, sx1, sy1) = sRect.useContents {
                doubleArrayOf(origin.x, origin.y, origin.x + size.width, origin.y + size.height)
            }
            if (sx0 < capX1 && sx1 > capX0 && sy0 < capY1 && sy1 > capY0) {
                behindStages.add(stage)
            }
        }
        if (behindStages.size > 1) {
            behindStages.sortWith { a, b ->
                when {
                    a === b -> 0
                    isPaintedBeforeTarget(a, b, contentView) -> -1
                    else -> 1
                }
            }
        }

        val treeVer = DesktopNativeWidget.globalTreeMutationVersion
        val stageIds = behindStages.map { it.surfaceWidget.nativePointer }
        val cacheValid = cachedStaticBehindCg != null &&
            cachedStaticTreeVer == treeVer &&
            cachedStaticCapX == capX0 &&
            cachedStaticCapY == capY0 &&
            cachedStaticCapW == capW &&
            cachedStaticCapH == capH &&
            cachedStaticScale == scale &&
            cachedStaticStageIds == stageIds

        isCapturingBackdrop = true
        try {
            if (!cacheValid) {
                rebuildStaticBackdropCaches(
                    contentView = contentView,
                    underlay = underlay,
                    behindStages = behindStages,
                    capX0 = capX0,
                    capY0 = capY0,
                    capW = capW,
                    capH = capH,
                    capPxW = capPxW,
                    capPxH = capPxH,
                    scale = scale,
                    treeVer = treeVer,
                    stageIds = stageIds
                )
            }
            val staticBehindCg = cachedStaticBehindCg ?: return

            var compositeCi = CIImage.imageWithCGImage(staticBehindCg)
            val capBoundsCi = CGRectMake(0.0, 0.0, capPxW.toDouble(), capPxH.toDouble())
            val capPxHD = capPxH.toDouble()

            if (behindStages.isNotEmpty()) {
                val blackColor = CIColor.colorWithRed(0.0, green = 0.0, blue = 0.0, alpha = 1.0)
                val blackImg = CIImage.imageWithColor(blackColor)
                for (stage in behindStages) {
                    val isVideoOrStage = stage.currentSpec()?.shouldPaintBlackBackdrop() == true ||
                        stage.surfaceWidget.nativeClassName.contains("video", ignoreCase = true)
                    if (isVideoOrStage) {
                        val sRect = stage.convertRect(stage.bounds, toView = contentView)
                        val (sx, sy, sw, sh) = sRect.useContents {
                            doubleArrayOf(origin.x, origin.y, size.width, size.height)
                        }
                        val sCiX = (sx - capX0) * scale
                        val sCiY = capPxHD - ((sy - capY0) + sh) * scale
                        val sCiW = sw * scale
                        val sCiH = sh * scale
                        if (sCiW > 0.0 && sCiH > 0.0) {
                            val stageBackdropCi = blackImg.imageByCroppingToRect(CGRectMake(sCiX, sCiY, sCiW, sCiH))
                            compositeCi = stageBackdropCi.imageByCompositingOverImage(compositeCi)
                        }
                    }

                    val ioPair = stage.currentPresentedIoSurfaceAndFrame()
                    if (ioPair != null) {
                        val (ioSurf, videoFrame) = ioPair
                        compositeCi = compositeTransformedStageImage(
                            baseCi = compositeCi,
                            contentView = contentView,
                            stage = stage,
                            frameInStage = videoFrame,
                            srcCi = CIImage.imageWithIOSurface(ioSurf),
                            srcW = IOSurfaceGetWidth(ioSurf).toDouble().coerceAtLeast(1.0),
                            srcH = IOSurfaceGetHeight(ioSurf).toDouble().coerceAtLeast(1.0),
                            capX0 = capX0,
                            capY0 = capY0,
                            capPxHD = capPxHD,
                            scale = scale
                        )
                    } else {
                        val cgFallback = stage.currentCgImage()
                        if (cgFallback != null) {
                            compositeCi = compositeTransformedStageImage(
                                baseCi = compositeCi,
                                contentView = contentView,
                                stage = stage,
                                frameInStage = stage.currentVideoContentLayerFrame(),
                                srcCi = CIImage.imageWithCGImage(cgFallback),
                                srcW = CGImageGetWidth(cgFallback).toDouble().coerceAtLeast(1.0),
                                srcH = CGImageGetHeight(cgFallback).toDouble().coerceAtLeast(1.0),
                                capX0 = capX0,
                                capY0 = capY0,
                                capPxHD = capPxHD,
                                scale = scale
                            )
                        }
                    }
                }
                val interCg = cachedIntermediateCg
                if (interCg != null) {
                    compositeCi = CIImage.imageWithCGImage(interCg).imageByCompositingOverImage(compositeCi)
                }
                compositeCi = compositeCi.imageByCroppingToRect(capBoundsCi)
            }

            compositeCi = compositeCi.imageByClampingToExtent()
            val viewPxW = (boundsW * scale).roundToInt().coerceAtLeast(1)
            val viewPxH = (boundsH * scale).roundToInt().coerceAtLeast(1)
            val relLeftPx = (rx - capX0) * scale
            val relTopPx = (ry - capY0) * scale
            val cropCiRect = CGRectMake(
                relLeftPx,
                capPxHD - (relTopPx + viewPxH.toDouble()),
                viewPxW.toDouble(),
                viewPxH.toDouble()
            )
            val filteredCi = AppleBackdropFilterController.renderFilteredCiImage(
                source = compositeCi,
                filter = filter,
                cropRectPx = cropCiRect,
                scaleFactor = scale,
                yPointsUp = true
            )
            val outBounds = CGRectMake(0.0, 0.0, viewPxW.toDouble(), viewPxH.toDouble())
            val outSurface = outputPool.acquire(viewPxW, viewPxH)
            var presented = false
            if (outSurface != null &&
                AppleBackdropFilterController.renderCiImageToIoSurface(filteredCi, outBounds, outSurface)
            ) {
                underlay.layer?.let { ulLayer ->
                    CATransaction.begin()
                    CATransaction.setDisableActions(true)
                    try {
                        ulLayer.contentsScale = scale
                        ulLayer.contents = AppleBackdropFilterController.ioSurfaceToLayerContents(outSurface)
                    } finally {
                        CATransaction.commit()
                    }
                }
                presented = true
            }
            if (!presented) {
                val filteredCg = AppleBackdropFilterController.renderFilteredCiImageToCgImage(
                    ciImage = filteredCi,
                    cropRectPx = outBounds
                ) ?: return
                try {
                    underlay.layer?.let { ulLayer ->
                        CATransaction.begin()
                        CATransaction.setDisableActions(true)
                        try {
                            ulLayer.contentsScale = scale
                            ulLayer.contents = CFBridgingRelease(CGImageRetain(filteredCg))
                        } finally {
                            CATransaction.commit()
                        }
                    }
                } finally {
                    CGImageRelease(filteredCg)
                }
            }
            lastUnderlayCaptureW = boundsW
            lastUnderlayCaptureH = boundsH
            lastUnderlayPosX = rx
            lastUnderlayPosY = ry
            lastUnderlayFilter = filter
            lastUnderlayTreeMutationVer = treeVer
        } finally {
            isCapturingBackdrop = false
        }
    }

    private fun rebuildStaticBackdropCaches(
        contentView: NSView,
        underlay: NSView,
        behindStages: List<MacosInteractiveSurfaceView>,
        capX0: Double,
        capY0: Double,
        capW: Double,
        capH: Double,
        capPxW: Int,
        capPxH: Int,
        scale: Double,
        treeVer: Long,
        stageIds: List<Long>
    ) {
        releaseStaticCaches()
        val hiddenViews = ArrayList<NSView>()
        fun tempHide(v: NSView) {
            if (!v.isHidden()) {
                v.setHidden(true)
                hiddenViews.add(v)
            }
        }
        try {
            tempHide(view)
            tempHide(underlay)
            // Preceding-paint-order isolation: hide all siblings/branches painted AFTER view.
            var cur: NSView = view
            while (cur !== contentView) {
                val parent = cur.superview ?: break
                val subs = parent.subviews
                val curIdx = subs.indexOf(cur)
                val curZ = cur.layer?.zPosition ?: 0.0
                for (i in subs.indices) {
                    val sib = subs[i] as? NSView ?: continue
                    if (sib === cur) continue
                    val sibZ = sib.layer?.zPosition ?: 0.0
                    if (sibZ > curZ || (sibZ == curZ && i > curIdx)) {
                        tempHide(sib)
                    }
                }
                cur = parent
            }
            for (stage in behindStages) {
                tempHide(stage)
            }

            // Collect any non-stage views painted between earliest behindStage and view intersecting capture rect & stage.
            val intermediateViews = if (behindStages.isNotEmpty()) {
                collectIntermediateOverlappingViews(
                    contentView = contentView,
                    earliestStage = behindStages.first(),
                    targetView = view,
                    behindStages = behindStages,
                    capX0 = capX0,
                    capY0 = capY0,
                    capX1 = capX0 + capW,
                    capY1 = capY0 + capH
                )
            } else {
                emptyList()
            }
            for (inter in intermediateViews) {
                tempHide(inter)
            }

            val staticCg = renderSubtreeToCgImage(
                rootLayerOwner = contentView,
                capX0 = capX0,
                capY0 = capY0,
                capPxW = capPxW,
                capPxH = capPxH,
                scale = scale
            ) ?: return
            cachedStaticBehindCg = staticCg

            if (intermediateViews.isNotEmpty()) {
                for (inter in intermediateViews) {
                    inter.setHidden(false)
                }
                cachedIntermediateCg = renderIntermediateViewsToCgImage(
                    contentView = contentView,
                    views = intermediateViews,
                    capX0 = capX0,
                    capY0 = capY0,
                    capPxW = capPxW,
                    capPxH = capPxH,
                    scale = scale
                )
            }

            cachedStaticTreeVer = treeVer
            cachedStaticCapX = capX0
            cachedStaticCapY = capY0
            cachedStaticCapW = capW
            cachedStaticCapH = capH
            cachedStaticScale = scale
            cachedStaticStageIds = stageIds
        } finally {
            for (i in hiddenViews.lastIndex downTo 0) {
                hiddenViews[i].setHidden(false)
            }
        }
    }

    private fun collectStageViews(root: NSView): List<MacosInteractiveSurfaceView> {
        val result = ArrayList<MacosInteractiveSurfaceView>()
        fun walk(v: NSView) {
            if (v is MacosInteractiveSurfaceView) {
                result.add(v)
            }
            for (sub in v.subviews) {
                if (sub is NSView) walk(sub)
            }
        }
        walk(root)
        return result
    }

    private fun collectIntermediateOverlappingViews(
        contentView: NSView,
        earliestStage: MacosInteractiveSurfaceView,
        targetView: NSView,
        behindStages: List<MacosInteractiveSurfaceView>,
        capX0: Double,
        capY0: Double,
        capX1: Double,
        capY1: Double
    ): List<NSView> {
        val excluded = HashSet<NSView>()
        var anc: NSView? = targetView
        while (anc != null) {
            excluded.add(anc)
            if (anc === contentView) break
            anc = anc.superview
        }
        for (stage in behindStages) {
            var sAnc: NSView? = stage
            while (sAnc != null) {
                excluded.add(sAnc)
                if (sAnc === contentView) break
                sAnc = sAnc.superview
            }
        }

        val out = ArrayList<NSView>()
        fun walk(v: NSView) {
            if (v.isHidden()) return
            if (v !in excluded &&
                isPaintedBeforeTarget(earliestStage, v, contentView) &&
                isPaintedBeforeTarget(v, targetView, contentView)
            ) {
                val r = v.convertRect(v.bounds, toView = contentView)
                val (vx0, vy0, vx1, vy1) = r.useContents {
                    doubleArrayOf(origin.x, origin.y, origin.x + size.width, origin.y + size.height)
                }
                if (vx0 < capX1 && vx1 > capX0 && vy0 < capY1 && vy1 > capY0) {
                    out.add(v)
                    return
                }
            }
            for (sub in v.subviews) {
                if (sub is NSView) walk(sub)
            }
        }
        walk(contentView)
        return out
    }

    private fun compositeTransformedStageImage(
        baseCi: CIImage,
        contentView: NSView,
        stage: MacosInteractiveSurfaceView,
        frameInStage: CValue<CGRect>,
        srcCi: CIImage,
        srcW: Double,
        srcH: Double,
        capX0: Double,
        capY0: Double,
        capPxHD: Double,
        scale: Double
    ): CIImage {
        val vRect = stage.convertRect(frameInStage, toView = contentView)
        val (vx, vy, vw, vh) = vRect.useContents {
            doubleArrayOf(origin.x, origin.y, size.width, size.height)
        }
        val vCiX = (vx - capX0) * scale
        val vCiY = capPxHD - ((vy - capY0) + vh) * scale
        val vCiW = vw * scale
        val vCiH = vh * scale
        if (vCiW <= 0.0 || vCiH <= 0.0) return baseCi
        val tx = CGAffineTransformScale(
            CGAffineTransformMakeTranslation(vCiX, vCiY),
            vCiW / srcW,
            vCiH / srcH
        )
        return srcCi.imageByApplyingTransform(tx).imageByCompositingOverImage(baseCi)
    }

    private fun createSrgbBgraBitmapContext(widthPx: Int, heightPx: Int): CGContextRef? {
        val colorSpace = CGColorSpaceCreateWithName(kCGColorSpaceSRGB) ?: CGColorSpaceCreateDeviceRGB() ?: return null
        val bitmapInfo = kCGBitmapByteOrder32Little or CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value
        val ctx = CGBitmapContextCreate(
            data = null,
            width = widthPx.toULong(),
            height = heightPx.toULong(),
            bitsPerComponent = 8uL,
            bytesPerRow = (widthPx * 4).toULong(),
            space = colorSpace,
            bitmapInfo = bitmapInfo
        )
        CGColorSpaceRelease(colorSpace)
        return ctx
    }

    private fun renderSubtreeToCgImage(
        rootLayerOwner: NSView,
        capX0: Double,
        capY0: Double,
        capPxW: Int,
        capPxH: Int,
        scale: Double
    ): CGImageRef? {
        val layer = rootLayerOwner.layer ?: return null
        val ctx = createSrgbBgraBitmapContext(capPxW, capPxH) ?: return null
        return try {
            // contentView is flipped (isGeometryFlipped=true); flip CGContext Y so top-of-view aligns with row 0.
            CGContextTranslateCTM(ctx, 0.0, capPxH.toDouble())
            CGContextScaleCTM(ctx, scale, -scale)
            CGContextTranslateCTM(ctx, -capX0, -capY0)
            layer.renderInContext(ctx)
            CGBitmapContextCreateImage(ctx)
        } finally {
            CGContextRelease(ctx)
        }
    }

    private fun renderIntermediateViewsToCgImage(
        contentView: NSView,
        views: List<NSView>,
        capX0: Double,
        capY0: Double,
        capPxW: Int,
        capPxH: Int,
        scale: Double
    ): CGImageRef? {
        val ctx = createSrgbBgraBitmapContext(capPxW, capPxH) ?: return null
        return try {
            for (inter in views) {
                val interLayer = inter.layer ?: continue
                val rectInContent = inter.convertRect(inter.bounds, toView = contentView)
                val (ix, iy) = rectInContent.useContents { origin.x to origin.y }
                CGContextSaveGState(ctx)
                try {
                    CGContextTranslateCTM(ctx, 0.0, capPxH.toDouble())
                    CGContextScaleCTM(ctx, scale, -scale)
                    CGContextTranslateCTM(ctx, ix - capX0, iy - capY0)
                    interLayer.renderInContext(ctx)
                } finally {
                    CGContextRestoreGState(ctx)
                }
            }
            CGBitmapContextCreateImage(ctx)
        } finally {
            CGContextRelease(ctx)
        }
    }

    private fun releaseStaticCaches() {
        cachedStaticBehindCg?.let { CGImageRelease(it) }
        cachedStaticBehindCg = null
        cachedIntermediateCg?.let { CGImageRelease(it) }
        cachedIntermediateCg = null
        cachedStaticTreeVer = -1L
        cachedStaticCapX = Double.NaN
        cachedStaticCapY = Double.NaN
        cachedStaticCapW = -1.0
        cachedStaticCapH = -1.0
        cachedStaticScale = -1.0
        cachedStaticStageIds = emptyList()
    }
}

internal class MacosForegroundDrawView(
    private val onDraw: (dirtyRect: CValue<NSRect>) -> Unit,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 0.0, 0.0)
) : NSView(frameRect) {

    private var lastSyncedW: Double = -1.0
    private var lastSyncedH: Double = -1.0

    init {
        setWantsLayer(true)
    }

    override fun isFlipped(): Boolean = true

    override fun hitTest(point: CValue<CGPoint>): NSView? = null

    override fun drawRect(dirtyRect: CValue<NSRect>) {
        onDraw(dirtyRect)
    }

    fun syncToHost(host: NSView, forceRedraw: Boolean = false) {
        val (bw, bh) = host.bounds.useContents { size.width to size.height }
        val sizeChanged = bw != lastSyncedW || bh != lastSyncedH
        if (sizeChanged) {
            setFrame(host.bounds)
            lastSyncedW = bw
            lastSyncedH = bh
        }
        var reattached = false
        if (superview != host || host.subviews.lastOrNull() != this) {
            if (superview != null) removeFromSuperview()
            host.addSubview(this)
            reattached = true
        }
        if (forceRedraw || sizeChanged || reattached) {
            setNeedsDisplay(true)
        }
    }
}
