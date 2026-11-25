@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.AppKit.NSColor
import platform.AppKit.NSEvent
import platform.AppKit.NSFont
import platform.AppKit.NSTextAlignmentCenter
import platform.AppKit.NSTextField
import platform.AppKit.NSView
import platform.AppKit.labelWithString
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorCreateGenericRGB
import platform.CoreGraphics.CGColorRelease
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGImageRetain
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect
import platform.QuartzCore.CALayer
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCAGravityResize
import platform.QuartzCore.kCAGravityResizeAspect
import platform.posix.free
import platform.posix.malloc
import platform.posix.memcpy
import kotlin.math.roundToInt

internal class MacosInteractiveSurfaceView(
    val surfaceWidget: DesktopNativeWidget,
    private val containerOwner: DesktopBaseContainerNativeImpl<*>? = null,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 0.0, 0.0)
) : NSView(frameRect) {

    constructor(
        owner: DesktopBaseContainerNativeImpl<*>,
        frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 0.0, 0.0)
    ) : this(owner.nativeWidget, owner, frameRect)

    private var cachedInteractionController: MacosViewInteractionController? = null
    private var lastNotifiedSurfaceW: Int = 0
    private var lastNotifiedSurfaceH: Int = 0

    private fun resolveLeafInteractionController(): MacosViewInteractionController? {
        if (containerOwner != null) return null
        cachedInteractionController?.let { return it }
        val impl = surfaceWidget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*> ?: return null
        val hasWheel: () -> Boolean = {
            when (impl) {
                is MacosContainerNativeImpl -> impl.hasWheelListener()
                is MacosStyledContainerNativeImpl -> impl.hasWheelListener()
                is MacosStyledComponentNativeImpl -> impl.hasWheelListener()
                is MacosStyledButtonNativeImpl -> impl.hasWheelListener()
                else -> false
            }
        }
        return MacosViewInteractionController(impl, this, hasWheel).also { cachedInteractionController = it }
    }

    private class RingSlot {
        var nativeBuffer: COpaquePointer? = null
        var capacityBytes: Int = 0
        var width: Int = 0
        var height: Int = 0
        var cgImage: CGImageRef? = null

        fun ensureCapacity(requiredBytes: Int): COpaquePointer? {
            if (requiredBytes <= 0) return null
            if (nativeBuffer != null && capacityBytes >= requiredBytes) {
                return nativeBuffer
            }
            nativeBuffer?.let { free(it) }
            val allocated = malloc(requiredBytes.toULong())
            nativeBuffer = allocated
            capacityBytes = if (allocated != null) requiredBytes else 0
            return allocated
        }

        fun releaseImage() {
            cgImage?.let { CGImageRelease(it) }
            cgImage = null
        }

        fun dispose() {
            releaseImage()
            nativeBuffer?.let { free(it) }
            nativeBuffer = null
            capacityBytes = 0
        }
    }

    private val ringSlots = Array(3) { RingSlot() }
    private var ringCursor: Int = 0
    private var lastPresentedSwapCount: Long = -1L
    private var captionField: NSTextField? = null
    private var lastCaptionText: String? = null
    private var lastCaptionBoundsW: Double = -1.0
    private var lastCaptionBoundsH: Double = -1.0
    private var appliedBlackBackdropLayer: CALayer? = null
    private val videoContentLayer = CALayer()
    internal var ringAllocationCount: Int = 0
        private set
    internal var ringSlotReuseCount: Int = 0
        private set

    internal val ringSlotCount: Int
        get() = ringSlots.size

    internal val activeRingSlotIndex: Int
        get() = ringCursor

    internal val activePresentedSwapCount: Long
        get() = lastPresentedSwapCount

    internal fun ringSlotPointerAddress(index: Int): Long =
        ringSlots.getOrNull(index)?.nativeBuffer?.rawValue?.toLong() ?: 0L

    init {
        setWantsLayer(true)
        layer?.masksToBounds = true
        videoContentLayer.geometryFlipped = true
        videoContentLayer.contentsGravity = kCAGravityResizeAspect
        videoContentLayer.frame = bounds
        layer?.insertSublayer(videoContentLayer, atIndex = 0u)
        syncStageBackdropColor()
        syncVideoContentLayerBounds()
    }

    internal fun currentVideoContentLayerFrame(): CValue<NSRect> = videoContentLayer.frame

    private fun syncVideoContentLayerBounds() {
        val hostLayer = layer ?: return
        hostLayer.masksToBounds = true
        if (videoContentLayer.superlayer !== hostLayer) {
            hostLayer.insertSublayer(videoContentLayer, atIndex = 0u)
        }
        val (boundsW, boundsH) = bounds.useContents { size.width to size.height }
        val spec = currentSpec()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        try {
            videoContentLayer.geometryFlipped = true
            if (spec != null && boundsW > 0.0 && boundsH > 0.0) {
                val srcW = spec.surfaceWidth().coerceAtLeast(1)
                val srcH = spec.surfaceHeight().coerceAtLeast(1)
                val rect = DesktopInteractiveSurfaceSpec.computePresentationRect(boundsW, boundsH, srcW, srcH, spec)
                videoContentLayer.contentsGravity = kCAGravityResize
                videoContentLayer.frame = CGRectMake(rect.dstX, rect.dstY, rect.dstW, rect.dstH)
            } else {
                videoContentLayer.contentsGravity = kCAGravityResizeAspect
                videoContentLayer.frame = bounds
            }
        } finally {
            CATransaction.commit()
        }
    }

    internal fun syncStageBackdropColor() {
        val spec = currentSpec()
        val isVideoOrStage = spec?.shouldPaintBlackBackdrop() == true ||
            surfaceWidget.nativeClassName.contains("video", ignoreCase = true)
        if (!isVideoOrStage) {
            appliedBlackBackdropLayer = null
            return
        }
        val hostLayer = layer ?: return
        if (appliedBlackBackdropLayer === hostLayer && hostLayer.backgroundColor != null) {
            return
        }
        hostLayer.masksToBounds = true
        val bg = CGColorCreateGenericRGB(0.0, 0.0, 0.0, 1.0)
        if (bg != null) {
            try {
                hostLayer.backgroundColor = bg
                appliedBlackBackdropLayer = hostLayer
            } finally {
                CGColorRelease(bg)
            }
        }
    }

    override fun isFlipped(): Boolean = true

    override fun acceptsFirstMouse(event: NSEvent?): Boolean = true

    override fun mouseDownCanMoveWindow(): Boolean = isWithinOverlayTitleBarNonControlArea(this)

    override fun hitTest(point: CValue<CGPoint>): NSView? {
        if (containerOwner != null) {
            // Container parent routes pointer interactions; surface view itself does not swallow sibling overlays.
            return null
        }
        if (isHidden() || alphaValue <= 0.004 || surfaceWidget.isSelfPointerEventsNone()) return null
        val ptInSelf = superview?.let { convertPoint(point, fromView = it) } ?: point
        val inside = ptInSelf.useContents {
            bounds.useContents {
                x >= origin.x && y >= origin.y && x <= origin.x + size.width && y <= origin.y + size.height
            }
        }
        return if (inside) this else null
    }

    override fun setFrame(frame: CValue<NSRect>) {
        super.setFrame(frame)
        syncVideoContentLayerBounds()
        syncStageBackdropColor()
        val (fw, fh) = bounds.useContents { size.width.roundToInt() to size.height.roundToInt() }
        if (fw > 0 && fh > 0 && (fw != lastNotifiedSurfaceW || fh != lastNotifiedSurfaceH)) {
            lastNotifiedSurfaceW = fw
            lastNotifiedSurfaceH = fh
            currentSpec()?.resizeSurface?.invoke(fw, fh)
        }
        updateCaption(currentSpec()?.activeCaptionText?.invoke())
    }

    override fun layout() {
        super.layout()
        syncVideoContentLayerBounds()
    }

    override fun updateTrackingAreas() {
        super.updateTrackingAreas()
        resolveLeafInteractionController()?.refreshTrackingAreas()
    }

    override fun resetCursorRects() {
        resolveLeafInteractionController()?.applyCursorRects()
    }

    override fun cursorUpdate(event: NSEvent) {
        resolveLeafInteractionController()?.handleCursorUpdate(event) ?: super.cursorUpdate(event)
    }

    override fun mouseDown(event: NSEvent) {
        if (handleOverlayTitleBarMouseDown(this, event)) {
            return
        }
        resolveLeafInteractionController()?.handleMouseDown(event, buttonIndex = 0) ?: super.mouseDown(event)
    }

    override fun mouseDragged(event: NSEvent) {
        resolveLeafInteractionController()?.handleMouseDragged(event, buttonIndex = 0) ?: super.mouseDragged(event)
    }

    override fun mouseUp(event: NSEvent) {
        resolveLeafInteractionController()?.handleMouseUp(event, buttonIndex = 0) ?: super.mouseUp(event)
    }

    override fun rightMouseDown(event: NSEvent) {
        resolveLeafInteractionController()?.handleRightMouseDown(event) ?: super.rightMouseDown(event)
    }

    override fun mouseEntered(event: NSEvent) {
        resolveLeafInteractionController()?.handleMouseEntered(event) ?: super.mouseEntered(event)
    }

    override fun mouseExited(event: NSEvent) {
        resolveLeafInteractionController()?.handleMouseExited(event) ?: super.mouseExited(event)
    }

    override fun mouseMoved(event: NSEvent) {
        resolveLeafInteractionController()?.handleMouseMoved(event) ?: super.mouseMoved(event)
    }

    override fun scrollWheel(event: NSEvent) {
        if (resolveLeafInteractionController()?.handleScrollWheel(event) != true) {
            super.scrollWheel(event)
        }
    }

    fun currentSpec(): DesktopInteractiveSurfaceSpec? = surfaceWidget.interactiveSurfaceSpec

    fun currentCgImage(): CGImageRef? = ringSlots.getOrNull(ringCursor)?.cgImage

    fun currentSwapCount(): Long = currentSpec()?.swapCount?.invoke() ?: -1L

    fun hasNewFramePending(): Boolean {
        val spec = currentSpec() ?: return false
        val swap = spec.swapCount.invoke()
        return swap > 0L && swap != lastPresentedSwapCount
    }

    fun presentLatestFrame(forceRefresh: Boolean = false, timestampMs: Double? = null): Boolean {
        val spec = surfaceWidget.interactiveSurfaceSpec ?: return false
        syncStageBackdropColor()
        syncVideoContentLayerBounds()
        val swap = spec.swapCount.invoke()
        spec.dispatchBeforePresentFrame(swap, timestampMs)

        val srcW = spec.surfaceWidth.invoke()
        val srcH = spec.surfaceHeight.invoke()
        if (srcW <= 0 || srcH <= 0) {
            updateCaption(spec.activeCaptionText.invoke())
            return false
        }

        if (!forceRefresh && swap == lastPresentedSwapCount && ringSlots[ringCursor].cgImage != null) {
            updateCaption(spec.activeCaptionText.invoke())
            return false
        }

        var nextCg: CGImageRef? = null
        val ptrResult = spec.acquireBgraPtr?.invoke()
        if (ptrResult != null && ptrResult.first != null && ptrResult.second >= srcW * 4) {
            val srcPtr = ptrResult.first as? COpaquePointer
            val srcStride = DesktopInteractiveSurfaceSpec.resolveBgraRowStrideBytes(srcW, srcH, ptrResult.second)
            if (srcPtr != null) {
                nextCg = uploadPremulPtrToSlot(srcPtr, srcStride, srcW, srcH, isBgra = true, ignoreAlpha = true)
            }
        }
        if (nextCg == null) {
            val bgraBytes = spec.captureBgra?.invoke()
            if (bgraBytes != null && bgraBytes.size >= srcW * srcH * 4) {
                nextCg = uploadByteArrayToSlot(bgraBytes, srcW, srcH, isBgra = true)
            } else {
                val rgbaBytes = spec.captureRgba?.invoke()
                if (rgbaBytes != null && rgbaBytes.size >= srcW * srcH * 4) {
                    nextCg = uploadByteArrayToSlot(rgbaBytes, srcW, srcH, isBgra = false)
                }
            }
        }

        updateCaption(spec.activeCaptionText.invoke())
        if (nextCg != null) {
            lastPresentedSwapCount = swap
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            try {
                videoContentLayer.contents = CFBridgingRelease(CGImageRetain(nextCg))
            } finally {
                CATransaction.commit()
            }
            return true
        }
        return false
    }

    private fun uploadPremulPtrToSlot(
        srcPtr: COpaquePointer,
        srcStrideBytes: Int,
        width: Int,
        height: Int,
        isBgra: Boolean,
        ignoreAlpha: Boolean = false
    ): CGImageRef? {
        val nextIdx = (ringCursor + 1) % ringSlots.size
        val slot = ringSlots[nextIdx]
        val dstStride = width * 4
        val byteSize = dstStride * height
        val prevBuffer = slot.nativeBuffer
        val prevCapacity = slot.capacityBytes
        val dstBuf = slot.ensureCapacity(byteSize) ?: return null
        if (prevBuffer !== dstBuf || prevCapacity < byteSize) {
            ringAllocationCount++
        } else {
            ringSlotReuseCount++
        }
        val dstBytes = dstBuf.reinterpret<ByteVar>()
        val srcBytes = srcPtr.reinterpret<ByteVar>()

        if (srcStrideBytes == dstStride) {
            memcpy(dstBytes, srcBytes, byteSize.toULong())
        } else {
            for (row in 0 until height) {
                val sOff = (row.toLong() * srcStrideBytes).toInt()
                val dOff = row * dstStride
                memcpy(
                    dstBytes.plus(dOff),
                    srcBytes.plus(sOff),
                    dstStride.toULong()
                )
            }
        }

        val cgImage = buildSlotCgImage(slot, dstBuf, width, height, dstStride, isBgra, ignoreAlpha = ignoreAlpha)
        if (cgImage != null) {
            ringCursor = nextIdx
        }
        return cgImage
    }

    private fun uploadByteArrayToSlot(
        bytes: ByteArray,
        width: Int,
        height: Int,
        isBgra: Boolean
    ): CGImageRef? {
        val nextIdx = (ringCursor + 1) % ringSlots.size
        val slot = ringSlots[nextIdx]
        val dstStride = width * 4
        val byteSize = dstStride * height
        val prevBuffer = slot.nativeBuffer
        val prevCapacity = slot.capacityBytes
        val dstBuf = slot.ensureCapacity(byteSize) ?: return null
        if (prevBuffer !== dstBuf || prevCapacity < byteSize) {
            ringAllocationCount++
        } else {
            ringSlotReuseCount++
        }
        bytes.usePinned { pinned ->
            memcpy(dstBuf, pinned.addressOf(0), byteSize.toULong())
        }
        val cgImage = buildSlotCgImage(slot, dstBuf, width, height, dstStride, isBgra, ignoreAlpha = false)
        if (cgImage != null) {
            ringCursor = nextIdx
        }
        return cgImage
    }

    private fun buildSlotCgImage(
        slot: RingSlot,
        bufferPtr: COpaquePointer,
        width: Int,
        height: Int,
        strideBytes: Int,
        isBgra: Boolean,
        ignoreAlpha: Boolean = false
    ): CGImageRef? {
        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
        val bitmapInfo = when {
            isBgra && ignoreAlpha -> kCGBitmapByteOrder32Little or CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst.value
            isBgra -> kCGBitmapByteOrder32Little or CGImageAlphaInfo.kCGImageAlphaPremultipliedFirst.value
            else -> kCGBitmapByteOrder32Big or CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
        }
        val ctx = CGBitmapContextCreate(
            data = bufferPtr,
            width = width.toULong(),
            height = height.toULong(),
            bitsPerComponent = 8uL,
            bytesPerRow = strideBytes.toULong(),
            space = colorSpace,
            bitmapInfo = bitmapInfo
        )
        CGColorSpaceRelease(colorSpace)
        ctx ?: return null
        return try {
            val img = CGBitmapContextCreateImage(ctx) ?: return null
            slot.releaseImage()
            slot.width = width
            slot.height = height
            slot.cgImage = img
            img
        } finally {
            CGContextRelease(ctx)
        }
    }

    private fun updateCaption(text: String?) {
        val clean = text?.trim()?.takeIf { it.isNotEmpty() }
        if (clean == null) {
            if (lastCaptionText == null && (captionField == null || captionField?.isHidden() == true)) {
                return
            }
            lastCaptionText = null
            captionField?.setHidden(true)
            return
        }
        val (bw, bh) = bounds.useContents { size.width to size.height }
        if (clean == lastCaptionText &&
            bw == lastCaptionBoundsW &&
            bh == lastCaptionBoundsH &&
            captionField != null &&
            captionField?.isHidden() == false
        ) {
            return
        }
        val field = captionField ?: NSTextField.labelWithString(clean).also { label ->
            label.setEditable(false)
            label.setBezeled(false)
            label.setDrawsBackground(true)
            label.setBackgroundColor(NSColor.colorWithRed(0.0, 0.0, 0.0, 0.72))
            label.setTextColor(NSColor.whiteColor)
            label.setAlignment(NSTextAlignmentCenter)
            label.setFont(NSFont.systemFontOfSize(14.0, weight = platform.AppKit.NSFontWeightMedium))
            label.setWantsLayer(true)
            label.layer?.cornerRadius = 6.0
            label.layer?.masksToBounds = true
            addSubview(label)
            captionField = label
        }
        if (lastCaptionText != clean) {
            field.setStringValue(clean)
        }
        if (field.isHidden()) {
            field.setHidden(false)
        }
        val maxW = (bw - 40.0).coerceAtLeast(120.0)
        val fitting = field.sizeThatFits(platform.CoreGraphics.CGSizeMake(maxW, 120.0))
        val (tw, th) = fitting.useContents { (width + 20.0).coerceAtMost(maxW) to (height + 8.0) }
        field.setFrame(CGRectMake((bw - tw) * 0.5, (bh - th - 28.0).coerceAtLeast(8.0), tw, th))
        lastCaptionText = clean
        lastCaptionBoundsW = bw
        lastCaptionBoundsH = bh
    }

    fun disposeResources() {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        try {
            videoContentLayer.contents = null
        } finally {
            CATransaction.commit()
        }
        appliedBlackBackdropLayer = null
        lastCaptionText = null
        lastCaptionBoundsW = -1.0
        lastCaptionBoundsH = -1.0
        for (slot in ringSlots) {
            slot.dispose()
        }
        captionField?.removeFromSuperview()
        captionField = null
    }
}

internal fun createMacosInteractiveSurfaceHandle(role: String): MacosViewHandle {
    val handle = MacosViewHandle("NSView_InteractiveSurface_$role")
    handle.viewMaterializer = {
        val existing = handle.nsView as? MacosInteractiveSurfaceView
        if (existing != null) {
            existing
        } else {
            val view = MacosInteractiveSurfaceView(surfaceWidget = handle)
            handle.nsView = view
            view.setHidden(!handle.visible)
            view.setAlphaValue(handle.opacity.coerceIn(0f, 1f).toDouble())
            view.setToolTip(handle.tooltipText)
            val b = handle.bounds
            if (b.width > 0 || b.height > 0) {
                MacosAppKitDispatch.applyFrameAndTransform(
                    view = view,
                    bounds = b,
                    transform = handle.cssTransform,
                    useAlignmentRectExpansion = false
                )
            }
            view.presentLatestFrame(forceRefresh = true)
            view
        }
    }
    if (MacosAppKitDispatch.isMainThread()) {
        handle.ensureNsViewOnMain()
    }
    return handle
}

private fun kotlinx.cinterop.CPointer<ByteVar>.plus(offset: Int): kotlinx.cinterop.CPointer<ByteVar>? =
    kotlinx.cinterop.interpretCPointer(this.rawValue + offset.toLong())
