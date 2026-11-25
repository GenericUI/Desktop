@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.apple.style.AppleBackdropFilterController
import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.enums.ui.ObjectFit
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.macos.MacosAppKitAppearance
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.macos.MacosViewInteractionController
import com.nexustools.genericui.style.color.ImageTintMode
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssTransform
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSEvent
import platform.AppKit.NSGraphicsContext
import platform.AppKit.NSTextAlignmentCenter
import platform.AppKit.NSView
import platform.AppKit.drawInRect
import platform.CoreGraphics.CGContextClipToRect
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextSetInterpolationQuality
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGInterpolationHigh
import platform.CoreGraphics.kCGInterpolationNone
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect
import kotlin.math.max
import kotlin.math.min

internal class MacosImageView(
    private val owner: MacosImageNativeImpl,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 0.0, 0.0)
) : NSView(frameRect) {

    private val interactionController = MacosViewInteractionController(owner, this)
    private var cachedCgImage: CGImageRef? = null
    private var cachedBufferRef: ImageBuffer? = null
    private var cachedTintColor: RgbaColor? = null
    private var cachedTintMode: ImageTintMode = ImageTintMode.NONE
    private var cachedCssFilter: CssFilter? = null

    init {
        setWantsLayer(true)
    }

    override fun isFlipped(): Boolean = true

    override fun acceptsFirstMouse(event: NSEvent?): Boolean = true

    override fun hitTest(point: CValue<CGPoint>): NSView? {
        if (isHidden() || alphaValue <= 0.004 || owner.nativeWidget.isSelfPointerEventsNone()) return null
        val ptInSelf = superview?.let { convertPoint(point, fromView = it) } ?: point
        val inside = ptInSelf.useContents {
            bounds.useContents {
                x >= origin.x && y >= origin.y && x <= origin.x + size.width && y <= origin.y + size.height
            }
        }
        return if (inside) this else null
    }

    override fun updateTrackingAreas() {
        super.updateTrackingAreas()
        if (owner.hasClickListener()) interactionController.refreshTrackingAreas()
    }

    override fun resetCursorRects() {
        interactionController.applyCursorRects()
    }

    override fun cursorUpdate(event: NSEvent) {
        interactionController.handleCursorUpdate(event)
    }

    override fun mouseEntered(event: NSEvent) {
        if (owner.hasClickListener()) interactionController.handleMouseEntered(event) else super.mouseEntered(event)
    }

    override fun mouseExited(event: NSEvent) {
        if (owner.hasClickListener()) interactionController.handleMouseExited(event) else super.mouseExited(event)
    }

    override fun mouseMoved(event: NSEvent) {
        if (owner.hasClickListener()) interactionController.handleMouseMoved(event) else super.mouseMoved(event)
    }

    override fun mouseDown(event: NSEvent) {
        if (owner.hasClickListener()) interactionController.handleMouseDown(event) else super.mouseDown(event)
    }

    override fun mouseUp(event: NSEvent) {
        if (owner.hasClickListener()) interactionController.handleMouseUp(event) else super.mouseUp(event)
    }

    fun invalidateImageCache() {
        cachedCgImage?.let { CGImageRelease(it) }
        cachedCgImage = null
        cachedBufferRef = null
        setNeedsDisplay(true)
    }

    private fun resolveDisplayCgImage(): CGImageRef? {
        val buf = owner.loadedImageBuffer ?: return null
        val tint = if (owner.getTintMode() != ImageTintMode.NONE) owner.getTintColor() else null
        val filter = owner.getCssFilter()
        if (cachedCgImage != null &&
            cachedBufferRef === buf &&
            cachedTintColor == tint &&
            cachedTintMode == owner.getTintMode() &&
            cachedCssFilter == filter
        ) {
            return cachedCgImage
        }

        cachedCgImage?.let { CGImageRelease(it) }
        cachedCgImage = null
        cachedBufferRef = buf
        cachedTintColor = tint
        cachedTintMode = owner.getTintMode()
        cachedCssFilter = filter

        val baseCg = MacosAppKitAppearance.createCgImageFromBuffer(buf, tintColor = tint) ?: return null
        if (filter != null && !filter.isNone && filter.hasActiveEffects) {
            val filtered = AppleBackdropFilterController.renderFilteredCgImage(
                source = baseCg,
                filter = filter,
                width = buf.width,
                height = buf.height
            )
            CGImageRelease(baseCg)
            cachedCgImage = filtered
            return filtered
        }
        cachedCgImage = baseCg
        return baseCg
    }

    override fun drawRect(dirtyRect: CValue<NSRect>) {
        val (bw, bh) = bounds.useContents { size.width to size.height }
        if (bw <= 0.0 || bh <= 0.0) return

        val cgImg = resolveDisplayCgImage()
        if (cgImg == null) {
            val fallback = owner.currentFallbackText()
            if (!fallback.isNullOrBlank()) {
                val darkTheme = MacosAppKitAppearance.isDarkTheme(owner.nativeWidget)
                val font = MacosAppKitAppearance.resolveFont(null, null, 12.0, false)
                val attr = MacosAppKitAppearance.buildAttributedString(
                    text = fallback,
                    style = null,
                    textColor = if (darkTheme) RgbaColor(180, 180, 190, 255) else RgbaColor(80, 80, 90, 255),
                    defaultFont = font,
                    alignment = NSTextAlignmentCenter
                )
                attr.drawInRect(NSMakeRect(4.0, ((bh - 16.0) * 0.5).coerceAtLeast(0.0), (bw - 8.0).coerceAtLeast(0.0), 18.0))
            }
            return
        }

        val imgW = CGImageGetWidth(cgImg).toDouble().coerceAtLeast(1.0)
        val imgH = CGImageGetHeight(cgImg).toDouble().coerceAtLeast(1.0)
        val (dx, dy, dw, dh) = computeObjectFitRect(owner.getObjectFit(), bw, bh, imgW, imgH)

        val ctx = NSGraphicsContext.currentContext?.CGContext ?: return
        CGContextSaveGState(ctx)
        CGContextClipToRect(ctx, CGRectMake(0.0, 0.0, bw, bh))
        CGContextSetInterpolationQuality(
            ctx,
            if (owner.isAntialiasing()) kCGInterpolationHigh else kCGInterpolationNone
        )
        // Unflip locally so CGContextDrawImage paints upright inside isFlipped() == true NSView.
        CGContextTranslateCTM(ctx, dx, dy + dh)
        CGContextScaleCTM(ctx, 1.0, -1.0)
        CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, dw, dh), cgImg)
        CGContextRestoreGState(ctx)
    }

    private fun computeObjectFitRect(
        fit: ObjectFit,
        boxW: Double,
        boxH: Double,
        imgW: Double,
        imgH: Double
    ): List<Double> {
        return when (fit) {
            ObjectFit.FILL -> listOf(0.0, 0.0, boxW, boxH)
            ObjectFit.NONE -> listOf((boxW - imgW) * 0.5, (boxH - imgH) * 0.5, imgW, imgH)
            ObjectFit.COVER -> {
                val scale = max(boxW / imgW, boxH / imgH)
                val w = imgW * scale
                val h = imgH * scale
                listOf((boxW - w) * 0.5, (boxH - h) * 0.5, w, h)
            }
            ObjectFit.SCALE_DOWN -> {
                val scale = min(1.0, min(boxW / imgW, boxH / imgH))
                val w = imgW * scale
                val h = imgH * scale
                listOf((boxW - w) * 0.5, (boxH - h) * 0.5, w, h)
            }
            ObjectFit.CONTAIN -> {
                val scale = min(boxW / imgW, boxH / imgH)
                val w = imgW * scale
                val h = imgH * scale
                listOf((boxW - w) * 0.5, (boxH - h) * 0.5, w, h)
            }
        }
    }

    fun disposeResources() {
        cachedCgImage?.let { CGImageRelease(it) }
        cachedCgImage = null
        cachedBufferRef = null
    }
}

/**
 * macOS AppKit retained [NSView] CoreGraphics/CoreImage implementation of [DesktopImageNativeImpl].
 */
class MacosImageNativeImpl(
    nativeWidget: MacosViewHandle
) : DesktopImageNativeImpl<MacosViewHandle>(nativeWidget) {

    private var imageView: MacosImageView? = null

    init {
        nativeWidget.viewMaterializer = { ensureImageView() }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureImageView()
        }
    }

    internal fun currentFallbackText(): String? = fallbackTextState

    private fun ensureImageView(): MacosImageView {
        imageView?.let { return it }
        val view = MacosImageView(this)
        imageView = view
        nativeWidget.nsView = view
        view.setHidden(!nativeWidget.visible)
        view.setAlphaValue(nativeWidget.opacity.coerceIn(0f, 1f).toDouble())
        view.setToolTip(nativeWidget.tooltipText)
        return view
    }

    private fun loadSourceUrlIntoBuffer(url: String?) {
        if (url.isNullOrBlank()) {
            imageView?.invalidateImageCache()
            return
        }
        val gen = loadGeneration
        val trimmed = url.trim()
        val syncBuf = MacosAppKitAppearance.decodeSourceToImageBuffer(trimmed)
        if (syncBuf != null) {
            if (gen == loadGeneration) {
                loadedImageBuffer = syncBuf
                loadingState = false
                nativeWidget.attributes["imageBuffer"] = syncBuf
                nativeWidget.attributes["isLoading"] = false
                imageView?.invalidateImageCache()
                onLoadListener?.invoke()
            }
            return
        }

        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            markLoading(true)
            val src = ImageSource.fromUrl(trimmed)
            activeLoadJob = ImageLoader.launchLoad(
                source = src,
                options = ImageDecodeOptions(preferredPixelFormat = PixelFormat.RGBA_8888),
                onLoaded = { buf ->
                    MacosAppKitDispatch.onMainOrDefer {
                        if (gen == loadGeneration) {
                            notifyAsyncLoadSucceeded(buf)
                            imageView?.invalidateImageCache()
                        }
                    }
                },
                onError = {
                    MacosAppKitDispatch.onMainOrDefer {
                        if (gen == loadGeneration) {
                            notifyAsyncLoadFailed()
                            imageView?.invalidateImageCache()
                        }
                    }
                }
            )
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        loadedImageBuffer?.let { buf ->
            if (buf.width > 0 && buf.height > 0) return Size(buf.width, buf.height)
        }
        return super.measureIntrinsicSize(maxWidth, maxHeight)
    }

    override fun setSourceUrl(url: String?) {
        super.setSourceUrl(url)
        loadSourceUrlIntoBuffer(url)
    }

    override fun setImageBuffer(buffer: ImageBuffer?) {
        super.setImageBuffer(buffer)
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed) {
                imageView?.invalidateImageCache()
            }
        }
    }

    override fun setObjectFit(fit: ObjectFit) {
        super.setObjectFit(fit)
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed) {
                imageView?.setNeedsDisplay(true)
            }
        }
    }

    override fun setAntialiasing(enabled: Boolean) {
        super.setAntialiasing(enabled)
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed) {
                imageView?.setNeedsDisplay(true)
            }
        }
    }

    override fun setTintColor(color: RgbaColor?) {
        super.setTintColor(color)
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed) {
                imageView?.invalidateImageCache()
            }
        }
    }

    override fun setTintMode(mode: ImageTintMode) {
        super.setTintMode(mode)
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed) {
                imageView?.invalidateImageCache()
            }
        }
    }

    override fun setCssFilter(filter: CssFilter?) {
        super.setCssFilter(filter)
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed) {
                imageView?.invalidateImageCache()
            }
        }
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer {
                if (!isDisposed) onNativeBoundsChanged(nativeWidget.bounds)
            }
            return
        }
        val v = ensureImageView()
        MacosAppKitDispatch.applyFrameAndTransform(v, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
        v.setNeedsDisplay(true)
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed) {
                imageView?.setHidden(!visible)
            }
        }
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed) {
                imageView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
            }
        }
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed) {
                imageView?.setToolTip(tooltip)
            }
        }
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed) {
                imageView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
            }
        }
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed) {
                val v = imageView ?: return@onMainOrDefer
                MacosAppKitDispatch.applyFrameAndTransform(
                    v,
                    nativeWidget.bounds,
                    nativeWidget.cssTransform,
                    useAlignmentRectExpansion = false
                )
            }
        }
    }

    override fun onNativeStyleSynced() {
        MacosAppKitDispatch.onMainOrDefer {
            if (!isDisposed) {
                ensureImageView().invalidateImageCache()
            }
        }
    }

    override fun dispose() {
        val view = imageView
        imageView = null
        MacosAppKitDispatch.onMainOrDefer {
            view?.disposeResources()
            view?.removeFromSuperview()
        }
        super.dispose()
    }
}
