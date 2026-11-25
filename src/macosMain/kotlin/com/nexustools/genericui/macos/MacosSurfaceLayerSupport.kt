@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.apple.style.AppleBackdropFilterController
import com.nexustools.genericui.apple.style.AppleStyledSurfaceHelper
import com.nexustools.genericui.desktop.core.DesktopStageOverlaySpec
import com.nexustools.genericui.style.css.CssCornerRadii
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssShadow
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.system.style.SemanticSurfacePaintSpec
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSAppearance
import platform.AppKit.NSAppearanceNameAqua
import platform.AppKit.NSAppearanceNameDarkAqua
import platform.AppKit.NSView
import platform.AppKit.NSVisualEffectBlendingMode
import platform.AppKit.NSVisualEffectMaterialHUDWindow
import platform.AppKit.NSVisualEffectMaterialHeaderView
import platform.AppKit.NSVisualEffectMaterialPopover
import platform.AppKit.NSVisualEffectMaterial
import platform.AppKit.NSVisualEffectState
import platform.AppKit.NSVisualEffectView
import platform.AppKit.NSWindowAbove
import platform.AppKit.NSWindowBelow
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGImageRetain
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRectMake
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect
import platform.QuartzCore.CAGradientLayer
import platform.QuartzCore.CALayer
import platform.QuartzCore.CAShapeLayer
import platform.QuartzCore.CATransaction

private class MacosBackdropVisualEffectView(
    frameRect: CValue<NSRect>
) : NSVisualEffectView(frameRect) {
    override fun hitTest(point: CValue<CGPoint>): NSView? = null
}

private class MacosNonInteractiveSurfaceView(
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 0.0, 0.0)
) : NSView(frameRect) {
    init {
        setWantsLayer(true)
    }

    override fun isFlipped(): Boolean = true

    override fun wantsUpdateLayer(): Boolean = true

    override fun updateLayer() {}

    override fun hitTest(point: CValue<CGPoint>): NSView? = null
}

internal class MacosSurfaceLayerSupport(private val view: NSView) {

    private companion object {
        var isCapturingBackdrop: Boolean = false
    }

    private var surfaceFillLayer: CALayer? = null
    private var gradientLayer: CAGradientLayer? = null
    private var borderLayer: CAShapeLayer? = null
    private var focusRingLayer: CAShapeLayer? = null
    private var activeSurfaceHostLayer: CALayer? = null
    private var lastUnderlayCaptureW: Double = -1.0
    private var lastUnderlayCaptureH: Double = -1.0
    private var underlayNeedsPostResizeRefresh: Boolean = false
    private var lastVevMaterial: NSVisualEffectMaterial? = null
    private var lastVevDarkTheme: Boolean? = null
    private var lastVevAlpha: Double = Double.NaN
    private var lastVevBoundsW: Double = -1.0
    private var lastVevBoundsH: Double = -1.0
    private var lastVevCornerRadii: CssCornerRadii? = null
    private var lastVevFallbackRadius: Float = Float.NaN
    var visualEffectView: NSVisualEffectView? = null
        private set

    private fun resetVisualEffectCache() {
        lastVevMaterial = null
        lastVevDarkTheme = null
        lastVevAlpha = Double.NaN
        lastVevBoundsW = -1.0
        lastVevBoundsH = -1.0
        lastVevCornerRadii = null
        lastVevFallbackRadius = Float.NaN
    }
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
            visualEffectView != null ||
            tintOverlayView != null ||
            ((activeSurfaceSpec.boxShadowColor?.a ?: 0) > 0 && (activeSurfaceSpec.boxShadowBlurPx ?: 0f) > 0f) ||
            (activeSurfaceSpec.borderRadiusPx ?: 0f) > 0f

    internal fun pinnedBackdropSubviews(): List<NSView> {
        val result = ArrayList<NSView>(3)
        visualEffectView?.let { result.add(it) }
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
            syncBackdropFilter(effectiveBackdropFilter, darkTheme, boundsW, boundsH, cornerRadii, fallbackRadius)
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
        visualEffectView?.removeFromSuperview()
        visualEffectView = null
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

    internal fun refreshBackdropUnderlayIfNeeded(force: Boolean = false) {
        val filter = activeBackdropFilter ?: return
        val underlay = backdropUnderlayView ?: return
        if (filter.isNone || !filter.hasActiveEffects || !AppleBackdropFilterController.requiresFullFilterPipeline(filter)) {
            return
        }
        val (bw, bh) = view.bounds.useContents { size.width to size.height }
        val win = view.window ?: return
        if (bw <= 0.0 || bh <= 0.0) return
        if (!force && (win.inLiveResize || (win as? MacosHostWindow)?.isPerformingNativeZoom == true)) {
            underlayNeedsPostResizeRefresh = true
            return
        }
        if (!force && underlay.layer?.contents != null && lastUnderlayCaptureW == bw && lastUnderlayCaptureH == bh) {
            return
        }
        underlayNeedsPostResizeRefresh = false
        captureBackdropIntoUnderlay(underlay, filter, bw, bh)
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

    private fun clearBackdropUnderlayView() {
        backdropUnderlayView?.let { ul ->
            ul.layer?.contents = null
            ul.removeFromSuperview()
        }
        backdropUnderlayView = null
        lastUnderlayCaptureW = -1.0
        lastUnderlayCaptureH = -1.0
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
        darkTheme: Boolean,
        boundsW: Double,
        boundsH: Double,
        cornerRadii: CssCornerRadii?,
        fallbackRadius: Float
    ) {
        if (filter == null || filter.isNone || !filter.hasActiveEffects) {
            visualEffectView?.removeFromSuperview()
            visualEffectView = null
            resetVisualEffectCache()
            clearBackdropUnderlayView()
            tintOverlayView?.removeFromSuperview()
            tintOverlayView = null
            return
        }

        val overlayRole = (MacosFocusSupport.directHandleForView(view)
            ?.attributes?.get(DesktopStageOverlaySpec.ATTR_OVERLAY_ROLE) as? String)
            ?.trim()
            ?.lowercase()
        val isOverlayTitleBar = overlayRole == DesktopStageOverlaySpec.ROLE_WINDOW_TITLE_BAR
        val isVibrancyOnlyTitleBar = isOverlayTitleBar &&
            filter.hasBlur &&
            !filter.hasDropShadow &&
            filter.grayscale == null &&
            filter.sepia == null &&
            filter.hueRotateDeg == null &&
            filter.invert == null &&
            filter.brightness == null &&
            filter.contrast == null

        if (!AppleBackdropFilterController.requiresFullFilterPipeline(filter) || isVibrancyOnlyTitleBar) {
            clearBackdropUnderlayView()
            val vev = visualEffectView ?: MacosBackdropVisualEffectView(
                frameRect = CGRectMake(0.0, 0.0, boundsW, boundsH)
            ).also { created ->
                created.setWantsLayer(true)
                created.setBlendingMode(NSVisualEffectBlendingMode.NSVisualEffectBlendingModeWithinWindow)
                created.setState(NSVisualEffectState.NSVisualEffectStateActive)
                resetVisualEffectCache()
                visualEffectView = created
            }
            val material = when {
                !isOverlayTitleBar -> NSVisualEffectMaterialHeaderView
                darkTheme -> NSVisualEffectMaterialHUDWindow
                else -> NSVisualEffectMaterialPopover
            }
            if (lastVevMaterial != material) {
                vev.setMaterial(material)
                lastVevMaterial = material
            }
            if (lastVevDarkTheme != darkTheme) {
                val appearanceName = if (darkTheme) NSAppearanceNameDarkAqua else NSAppearanceNameAqua
                vev.setAppearance(NSAppearance.appearanceNamed(appearanceName))
                lastVevDarkTheme = darkTheme
            }
            val blurAlpha = ((filter.blur?.resolvePx() ?: 8f) / 12.0).coerceIn(0.28, 1.0)
            val bg = activeSurfaceSpec.backgroundColor
            val effectiveVevAlpha = if (bg != null && bg.a in 1..254) {
                val bgAlpha = bg.a / 255.0
                if (isOverlayTitleBar) {
                    (blurAlpha * (0.30 + 0.60 * bgAlpha)).coerceIn(0.30, 0.85)
                } else {
                    (blurAlpha * (0.35 + 0.65 * bgAlpha)).coerceIn(0.28, 1.0)
                }
            } else {
                blurAlpha
            }
            if (lastVevAlpha.isNaN() || kotlin.math.abs(lastVevAlpha - effectiveVevAlpha) > 0.002) {
                vev.setAlphaValue(effectiveVevAlpha)
                lastVevAlpha = effectiveVevAlpha
            }
            val boundsChanged = lastVevBoundsW != boundsW || lastVevBoundsH != boundsH
            val radiiChanged = lastVevCornerRadii != cornerRadii || lastVevFallbackRadius != fallbackRadius
            if (boundsChanged) {
                vev.setFrame(CGRectMake(0.0, 0.0, boundsW, boundsH))
                lastVevBoundsW = boundsW
                lastVevBoundsH = boundsH
            }
            if (boundsChanged || radiiChanged) {
                vev.layer?.let { vevLayer ->
                    AppleStyledSurfaceHelper.applyLayerCornerRadii(
                        layer = vevLayer,
                        radii = cornerRadii,
                        fallbackUniformPx = fallbackRadius,
                        boundsWidth = boundsW,
                        boundsHeight = boundsH,
                        clipsToBounds = true,
                        topLeftIsMinY = vev.isFlipped()
                    )
                }
                lastVevCornerRadii = cornerRadii
                lastVevFallbackRadius = fallbackRadius
            }
            if (vev.superview != view) {
                if (vev.superview != null) vev.removeFromSuperview()
                view.addSubview(vev, positioned = NSWindowBelow, relativeTo = null)
            }
            ensureTintOverlaySubviewOrder(vev)
        } else {
            visualEffectView?.removeFromSuperview()
            visualEffectView = null
            resetVisualEffectCache()
            val underlay = backdropUnderlayView ?: MacosNonInteractiveSurfaceView(
                frameRect = CGRectMake(0.0, 0.0, boundsW, boundsH)
            ).also { created ->
                backdropUnderlayView = created
            }
            underlay.setFrame(CGRectMake(0.0, 0.0, boundsW, boundsH))
            underlay.layer?.let { ulLayer ->
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
            if (boundsW > 0.0 && boundsH > 0.0 && win != null &&
                (underlay.layer?.contents == null || lastUnderlayCaptureW != boundsW || lastUnderlayCaptureH != boundsH)
            ) {
                if (win.inLiveResize || (win as? MacosHostWindow)?.isPerformingNativeZoom == true) {
                    underlayNeedsPostResizeRefresh = true
                } else {
                    underlayNeedsPostResizeRefresh = false
                    captureBackdropIntoUnderlay(underlay, filter, boundsW, boundsH)
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
        boundsH: Double
    ) {
        if (isCapturingBackdrop || boundsW <= 0.0 || boundsH <= 0.0 || view.isHidden()) return
        val win = view.window ?: return
        val contentView = win.contentView ?: return
        if (contentView === view) return

        val rectInContent = view.convertRect(view.bounds, toView = contentView)
        val (rx, ry, rw, rh) = rectInContent.useContents {
            listOf(origin.x, origin.y, size.width, size.height)
        }
        val (cx, cy, cw, ch) = contentView.bounds.useContents {
            listOf(origin.x, origin.y, size.width, size.height)
        }
        val ix0 = maxOf(rx, cx)
        val iy0 = maxOf(ry, cy)
        val ix1 = minOf(rx + rw, cx + cw)
        val iy1 = minOf(ry + rh, cy + ch)
        if (ix1 <= ix0 || iy1 <= iy0) return

        isCapturingBackdrop = true
        val wasHidden = view.isHidden()
        val underlayWasHidden = underlay.isHidden()
        try {
            view.setHidden(true)
            underlay.setHidden(true)
            val rep = contentView.bitmapImageRepForCachingDisplayInRect(rectInContent) ?: return
            contentView.cacheDisplayInRect(rectInContent, toBitmapImageRep = rep)
            val srcCg = rep.CGImage ?: return
            val pixW = CGImageGetWidth(srcCg).toInt()
            val pixH = CGImageGetHeight(srcCg).toInt()
            if (pixW <= 0 || pixH <= 0) return
            val scale = if (boundsW > 0.0) {
                (pixW.toDouble() / boundsW).coerceAtLeast(1.0)
            } else {
                win.backingScaleFactor.coerceAtLeast(1.0)
            }
            val filteredCg = AppleBackdropFilterController.renderFilteredCgImage(
                source = srcCg,
                filter = filter,
                width = pixW,
                height = pixH
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
                    lastUnderlayCaptureW = boundsW
                    lastUnderlayCaptureH = boundsH
                }
            } finally {
                CGImageRelease(filteredCg)
            }
        } finally {
            underlay.setHidden(underlayWasHidden)
            view.setHidden(wasHidden)
            isCapturingBackdrop = false
        }
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
