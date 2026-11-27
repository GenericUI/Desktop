@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.ui.ObjectFit
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CValue
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.AppKit.NSColor
import platform.AppKit.NSEvent
import platform.AppKit.NSFont
import platform.AppKit.NSTextAlignmentCenter
import platform.AppKit.NSTextField
import platform.AppKit.NSView
import platform.AppKit.labelWithString
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFPropertyListRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.kCFNumberSInt32Type
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorCreateGenericRGB
import platform.CoreGraphics.CGColorRelease
import platform.CoreGraphics.CGColorSpaceCopyPropertyList
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGImageRetain
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.CoreGraphics.kCGColorSpaceSRGB
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect
import platform.IOSurface.IOSurfaceCreate
import platform.IOSurface.IOSurfaceGetBaseAddress
import platform.IOSurface.IOSurfaceGetBytesPerRow
import platform.IOSurface.IOSurfaceLock
import platform.IOSurface.IOSurfaceRef
import platform.IOSurface.IOSurfaceSetValue
import platform.IOSurface.IOSurfaceUnlock
import platform.IOSurface.kIOSurfaceAllocSize
import platform.IOSurface.kIOSurfaceBytesPerElement
import platform.IOSurface.kIOSurfaceBytesPerRow
import platform.IOSurface.kIOSurfaceColorSpace
import platform.IOSurface.kIOSurfaceHeight
import platform.IOSurface.kIOSurfaceLockReadOnly
import platform.IOSurface.kIOSurfacePixelFormat
import platform.IOSurface.kIOSurfaceWidth
import platform.QuartzCore.CALayer
import platform.QuartzCore.CATransaction
import platform.QuartzCore.kCAGravityResize
import platform.QuartzCore.kCAGravityResizeAspect
import platform.posix.free
import platform.posix.malloc
import platform.posix.memcpy
import kotlin.math.roundToInt

private const val IOSURFACE_PIXEL_FORMAT_BGRA: Int = 0x42475241 // 'BGRA'

private val sharedSrgbColorSpacePropertyList: CFPropertyListRef? by lazy {
    val cs = CGColorSpaceCreateWithName(kCGColorSpaceSRGB) ?: return@lazy null
    try {
        CGColorSpaceCopyPropertyList(cs)
    } finally {
        CGColorSpaceRelease(cs)
    }
}

private fun createBgraIoSurface(width: Int, height: Int, alignedRowBytes: Int): IOSurfaceRef? {
    if (width <= 0 || height <= 0 || alignedRowBytes <= 0) return null
    val allocSize = (alignedRowBytes.toLong() * height.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val props: CFDictionaryRef = memScoped {
        val keys = allocArray<COpaquePointerVar>(6)
        val vals = allocArray<COpaquePointerVar>(6)
        val vWidth = alloc<IntVar>().apply { value = width }
        val vHeight = alloc<IntVar>().apply { value = height }
        val vBpe = alloc<IntVar>().apply { value = 4 }
        val vBpr = alloc<IntVar>().apply { value = alignedRowBytes }
        val vSize = alloc<IntVar>().apply { value = allocSize }
        val vFmt = alloc<IntVar>().apply { value = IOSURFACE_PIXEL_FORMAT_BGRA }

        val nWidth = CFNumberCreate(null, kCFNumberSInt32Type, vWidth.ptr)
        val nHeight = CFNumberCreate(null, kCFNumberSInt32Type, vHeight.ptr)
        val nBpe = CFNumberCreate(null, kCFNumberSInt32Type, vBpe.ptr)
        val nBpr = CFNumberCreate(null, kCFNumberSInt32Type, vBpr.ptr)
        val nSize = CFNumberCreate(null, kCFNumberSInt32Type, vSize.ptr)
        val nFmt = CFNumberCreate(null, kCFNumberSInt32Type, vFmt.ptr)
        try {
            if (nWidth == null || nHeight == null || nBpe == null || nBpr == null || nSize == null || nFmt == null) {
                return@memScoped null
            }
            keys[0] = kIOSurfaceWidth
            vals[0] = nWidth
            keys[1] = kIOSurfaceHeight
            vals[1] = nHeight
            keys[2] = kIOSurfaceBytesPerElement
            vals[2] = nBpe
            keys[3] = kIOSurfaceBytesPerRow
            vals[3] = nBpr
            keys[4] = kIOSurfaceAllocSize
            vals[4] = nSize
            keys[5] = kIOSurfacePixelFormat
            vals[5] = nFmt
            CFDictionaryCreate(
                null,
                keys,
                vals,
                6L,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr
            )
        } finally {
            nWidth?.let { CFRelease(it) }
            nHeight?.let { CFRelease(it) }
            nBpe?.let { CFRelease(it) }
            nBpr?.let { CFRelease(it) }
            nSize?.let { CFRelease(it) }
            nFmt?.let { CFRelease(it) }
        }
    } ?: return null

    return try {
        val created = IOSurfaceCreate(props) ?: return null
        val csKey = kIOSurfaceColorSpace
        val csPlist = sharedSrgbColorSpacePropertyList
        if (csKey != null && csPlist != null) {
            IOSurfaceSetValue(created, csKey, csPlist)
        }
        created
    } finally {
        CFRelease(props)
    }
}

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
        var ioSurface: IOSurfaceRef? = null
        var surfaceObj: Any? = null
        var bytesPerRow: Int = 0
        var nativeBuffer: COpaquePointer? = null
        var capacityBytes: Int = 0
        var width: Int = 0
        var height: Int = 0
        var ignoreAlpha: Boolean = false
        var hasValidFrame: Boolean = false
        var cgImage: CGImageRef? = null

        fun ensureSurface(reqW: Int, reqH: Int): Boolean {
            if (reqW <= 0 || reqH <= 0) return false
            if (ioSurface != null && width == reqW && height == reqH) {
                return false
            }
            val alignedRowBytes = ((reqW * 4 + 15) / 16) * 16
            if (ioSurface == null && nativeBuffer != null && capacityBytes >= alignedRowBytes * reqH && width == reqW && height == reqH) {
                return false
            }
            releaseImage()
            surfaceObj = null
            ioSurface?.let { CFRelease(it) }
            ioSurface = null
            bytesPerRow = 0
            hasValidFrame = false

            val created = createBgraIoSurface(reqW, reqH, alignedRowBytes)
            if (created != null) {
                nativeBuffer?.let { free(it) }
                nativeBuffer = null
                capacityBytes = 0
                ioSurface = created
                surfaceObj = CFBridgingRelease(CFRetain(created))
                bytesPerRow = IOSurfaceGetBytesPerRow(created).toInt().coerceAtLeast(alignedRowBytes)
                width = reqW
                height = reqH
                return true
            }

            // Defensive CPU fallback only if IOSurfaceCreate fails
            val reqBytes = reqW * reqH * 4
            if (nativeBuffer == null || capacityBytes < reqBytes) {
                nativeBuffer?.let { free(it) }
                val allocated = malloc(reqBytes.toULong())
                nativeBuffer = allocated
                capacityBytes = if (allocated != null) reqBytes else 0
            }
            bytesPerRow = reqW * 4
            width = reqW
            height = reqH
            return nativeBuffer != null
        }

        fun hasContent(): Boolean = (ioSurface != null && hasValidFrame) || cgImage != null

        fun releaseImage() {
            cgImage?.let { CGImageRelease(it) }
            cgImage = null
        }

        fun dispose() {
            releaseImage()
            surfaceObj = null
            ioSurface?.let { CFRelease(it) }
            ioSurface = null
            nativeBuffer?.let { free(it) }
            nativeBuffer = null
            bytesPerRow = 0
            capacityBytes = 0
            width = 0
            height = 0
            ignoreAlpha = false
            hasValidFrame = false
        }
    }

    private val ringSlots = Array(3) { RingSlot() }
    private var ringCursor: Int = 0
    private var lastPresentedSwapCount: Long = -1L
    private var captionField: NSTextField? = null
    private var lastCaptionText: String? = null
    private var lastCaptionBoundsW: Double = -1.0
    private var lastCaptionBoundsH: Double = -1.0
    private var appliedBlackBackdropLayerPtr: Long = 0L
    private val videoContentLayer = CALayer()
    private var lastLayoutHostLayerPtr: Long = 0L
    private var lastLayoutBoundsW: Double = -1.0
    private var lastLayoutBoundsH: Double = -1.0
    private var lastLayoutSrcW: Int = -1
    private var lastLayoutSrcH: Int = -1
    private var lastLayoutHasSpec: Boolean = false
    private var lastLayoutFitMode: ObjectFit? = null
    private var lastLayoutAspectRatio: Float = Float.NaN
    internal var ringAllocationCount: Int = 0
        private set
    internal var ringSlotReuseCount: Int = 0
        private set
    internal var cgImageBuildCount: Int = 0
        private set
    internal var layerBoundsSyncCommitCount: Int = 0
        private set

    internal val ringSlotCount: Int
        get() = ringSlots.size

    internal val activeRingSlotIndex: Int
        get() = ringCursor

    internal val activePresentedSwapCount: Long
        get() = lastPresentedSwapCount

    internal val activeSlotUsesIoSurface: Boolean
        get() = ringSlots.getOrNull(ringCursor)?.ioSurface != null

    internal fun ringSlotPointerAddress(index: Int): Long =
        ringSlots.getOrNull(index)?.let { slot ->
            slot.ioSurface?.rawValue?.toLong() ?: slot.nativeBuffer?.rawValue?.toLong()
        } ?: 0L

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

    internal fun currentPresentedIoSurfaceAndFrame(): Pair<IOSurfaceRef, CValue<CGRect>>? {
        val slot = ringSlots.getOrNull(ringCursor) ?: return null
        val surface = slot.ioSurface ?: return null
        if (!slot.hasValidFrame || slot.width <= 0 || slot.height <= 0) return null
        return surface to videoContentLayer.frame
    }

    private fun syncVideoContentLayerBounds() {
        val hostLayer = layer ?: return
        val hostLayerPtr = hostLayer.objcPtr().toLong()
        val superlayerMatches = videoContentLayer.superlayer?.objcPtr()?.toLong() == hostLayerPtr
        val (boundsW, boundsH) = bounds.useContents { size.width to size.height }
        val spec = currentSpec()
        val hasSpec = spec != null && boundsW > 0.0 && boundsH > 0.0
        val srcW = if (hasSpec) spec!!.surfaceWidth().coerceAtLeast(1) else 0
        val srcH = if (hasSpec) spec!!.surfaceHeight().coerceAtLeast(1) else 0
        val fitMode = if (hasSpec) spec!!.resolveContentFitMode() else null
        val aspectRatio = if (hasSpec) spec!!.resolveContentAspectRatio(srcW, srcH) else Float.NaN
        if (superlayerMatches &&
            lastLayoutHostLayerPtr == hostLayerPtr &&
            lastLayoutBoundsW == boundsW &&
            lastLayoutBoundsH == boundsH &&
            lastLayoutHasSpec == hasSpec &&
            (!hasSpec || (
                lastLayoutSrcW == srcW &&
                    lastLayoutSrcH == srcH &&
                    lastLayoutFitMode == fitMode &&
                    lastLayoutAspectRatio == aspectRatio
                ))
        ) {
            return
        }

        hostLayer.masksToBounds = true
        if (!superlayerMatches) {
            hostLayer.insertSublayer(videoContentLayer, atIndex = 0u)
        }
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        try {
            videoContentLayer.geometryFlipped = true
            if (hasSpec) {
                val rect = DesktopInteractiveSurfaceSpec.computePresentationRect(
                    containerW = boundsW,
                    containerH = boundsH,
                    srcW = srcW,
                    srcH = srcH,
                    aspectRatio = aspectRatio,
                    fitMode = fitMode!!
                )
                videoContentLayer.contentsGravity = kCAGravityResize
                videoContentLayer.frame = CGRectMake(rect.dstX, rect.dstY, rect.dstW, rect.dstH)
            } else {
                videoContentLayer.contentsGravity = kCAGravityResizeAspect
                videoContentLayer.frame = bounds
            }
        } finally {
            CATransaction.commit()
        }
        lastLayoutHostLayerPtr = hostLayerPtr
        lastLayoutBoundsW = boundsW
        lastLayoutBoundsH = boundsH
        lastLayoutHasSpec = hasSpec
        lastLayoutSrcW = srcW
        lastLayoutSrcH = srcH
        lastLayoutFitMode = fitMode
        lastLayoutAspectRatio = aspectRatio
        layerBoundsSyncCommitCount++
    }

    internal fun syncStageBackdropColor() {
        val spec = currentSpec()
        val isVideoOrStage = spec?.shouldPaintBlackBackdrop() == true ||
            surfaceWidget.nativeClassName.contains("video", ignoreCase = true)
        if (!isVideoOrStage) {
            appliedBlackBackdropLayerPtr = 0L
            return
        }
        val hostLayer = layer ?: return
        val hostLayerPtr = hostLayer.objcPtr().toLong()
        if (appliedBlackBackdropLayerPtr == hostLayerPtr && hostLayer.backgroundColor != null) {
            return
        }
        hostLayer.masksToBounds = true
        val bg = CGColorCreateGenericRGB(0.0, 0.0, 0.0, 1.0)
        if (bg != null) {
            try {
                hostLayer.backgroundColor = bg
                appliedBlackBackdropLayerPtr = hostLayerPtr
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

    fun currentCgImage(): CGImageRef? {
        val slot = ringSlots.getOrNull(ringCursor) ?: return null
        slot.cgImage?.let { return it }
        val surface = slot.ioSurface ?: return null
        if (!slot.hasValidFrame || slot.width <= 0 || slot.height <= 0) return null
        IOSurfaceLock(surface, kIOSurfaceLockReadOnly, null)
        return try {
            val base = IOSurfaceGetBaseAddress(surface) ?: return null
            buildSlotCgImage(
                slot = slot,
                bufferPtr = base,
                width = slot.width,
                height = slot.height,
                strideBytes = slot.bytesPerRow,
                isBgra = true,
                ignoreAlpha = slot.ignoreAlpha
            )
        } finally {
            IOSurfaceUnlock(surface, kIOSurfaceLockReadOnly, null)
        }
    }

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

        if (!forceRefresh && swap == lastPresentedSwapCount && ringSlots[ringCursor].hasContent()) {
            updateCaption(spec.activeCaptionText.invoke())
            return false
        }

        var nextContents: Any? = null
        var nextOpaque = false
        val ptrResult = spec.acquireBgraPtr?.invoke()
        if (ptrResult != null && ptrResult.first != null && ptrResult.second >= srcW * 4) {
            val srcPtr = ptrResult.first as? COpaquePointer
            val srcStride = DesktopInteractiveSurfaceSpec.resolveBgraRowStrideBytes(srcW, srcH, ptrResult.second)
            if (srcPtr != null) {
                nextContents = uploadPremulPtrToSlot(srcPtr, srcStride, srcW, srcH, isBgra = true, ignoreAlpha = true)
                nextOpaque = nextContents != null
            }
        }
        if (nextContents == null) {
            val bgraBytes = spec.captureBgra?.invoke()
            if (bgraBytes != null && bgraBytes.size >= srcW * srcH * 4) {
                nextContents = uploadByteArrayToSlot(bgraBytes, srcW, srcH, isBgra = true)
            } else {
                val rgbaBytes = spec.captureRgba?.invoke()
                if (rgbaBytes != null && rgbaBytes.size >= srcW * srcH * 4) {
                    nextContents = uploadByteArrayToSlot(rgbaBytes, srcW, srcH, isBgra = false)
                }
            }
        }

        updateCaption(spec.activeCaptionText.invoke())
        if (nextContents != null) {
            lastPresentedSwapCount = swap
            CATransaction.begin()
            CATransaction.setDisableActions(true)
            try {
                videoContentLayer.opaque = nextOpaque
                videoContentLayer.contents = nextContents
            } finally {
                CATransaction.commit()
            }
            return true
        }
        return false
    }

    private fun copyBgraRowsToBuffer(
        srcBytes: kotlinx.cinterop.CPointer<ByteVar>,
        srcStrideBytes: Int,
        dstBytes: kotlinx.cinterop.CPointer<ByteVar>,
        dstStrideBytes: Int,
        width: Int,
        height: Int,
        ignoreAlpha: Boolean
    ) {
        val copyRowBytes = width * 4
        if (!ignoreAlpha) {
            if (srcStrideBytes == dstStrideBytes && srcStrideBytes == copyRowBytes) {
                memcpy(dstBytes, srcBytes, (copyRowBytes.toLong() * height.toLong()).toULong())
            } else {
                for (row in 0 until height) {
                    memcpy(
                        dstBytes.plus(row * dstStrideBytes),
                        srcBytes.plus(row * srcStrideBytes),
                        copyRowBytes.toULong()
                    )
                }
            }
            return
        }

        // Fast sample check: if LibVLC / source already wrote 0xFF alpha, use vectorized memcpy
        val midOff = (height / 2) * srcStrideBytes + (width / 2) * 4 + 3
        val endOff = (height - 1) * srcStrideBytes + (width - 1) * 4 + 3
        val opaqueByte = 0xFF.toByte()
        if (srcBytes[3] == opaqueByte && srcBytes[midOff] == opaqueByte && srcBytes[endOff] == opaqueByte) {
            if (srcStrideBytes == dstStrideBytes && srcStrideBytes == copyRowBytes) {
                memcpy(dstBytes, srcBytes, (copyRowBytes.toLong() * height.toLong()).toULong())
            } else {
                for (row in 0 until height) {
                    memcpy(
                        dstBytes.plus(row * dstStrideBytes),
                        srcBytes.plus(row * srcStrideBytes),
                        copyRowBytes.toULong()
                    )
                }
            }
            return
        }

        // Fused 64-bit copy setting alpha byte to 0xFF across pairs of pixels
        val pairs = width / 2
        val hasTail = (width and 1) != 0
        val tailOffsetBytes = (width - 1) * 4
        val alphaMask64 = 0xFF000000_FF000000uL
        val alphaMask32 = 0xFF000000u
        for (row in 0 until height) {
            val sRow = srcBytes.plus(row * srcStrideBytes)!!
            val dRow = dstBytes.plus(row * dstStrideBytes)!!
            if (((sRow.rawValue.toLong() or dRow.rawValue.toLong()) and 7L) == 0L) {
                val s64 = sRow.reinterpret<ULongVar>()
                val d64 = dRow.reinterpret<ULongVar>()
                for (i in 0 until pairs) {
                    d64[i] = s64[i] or alphaMask64
                }
                if (hasTail) {
                    val s32 = sRow.plus(tailOffsetBytes)!!.reinterpret<UIntVar>()
                    val d32 = dRow.plus(tailOffsetBytes)!!.reinterpret<UIntVar>()
                    d32[0] = s32[0] or alphaMask32
                }
            } else {
                memcpy(dRow, sRow, copyRowBytes.toULong())
                if ((dRow.rawValue.toLong() and 3L) == 0L) {
                    val d32 = dRow.reinterpret<UIntVar>()
                    for (x in 0 until width) {
                        d32[x] = d32[x] or alphaMask32
                    }
                } else {
                    for (x in 0 until width) {
                        dRow[x * 4 + 3] = opaqueByte
                    }
                }
            }
        }
    }

    private fun copyRgbaRowsAsBgra(
        srcBytes: kotlinx.cinterop.CPointer<ByteVar>,
        dstBytes: kotlinx.cinterop.CPointer<ByteVar>,
        dstStrideBytes: Int,
        width: Int,
        height: Int
    ) {
        val srcStrideBytes = width * 4
        for (row in 0 until height) {
            val sRow = srcBytes.plus(row * srcStrideBytes)!!
            val dRow = dstBytes.plus(row * dstStrideBytes)!!
            for (x in 0 until width) {
                val p = x * 4
                dRow[p] = sRow[p + 2]
                dRow[p + 1] = sRow[p + 1]
                dRow[p + 2] = sRow[p]
                dRow[p + 3] = sRow[p + 3]
            }
        }
    }

    private fun uploadPremulPtrToSlot(
        srcPtr: COpaquePointer,
        srcStrideBytes: Int,
        width: Int,
        height: Int,
        isBgra: Boolean,
        ignoreAlpha: Boolean = false
    ): Any? {
        val nextIdx = (ringCursor + 1) % ringSlots.size
        val slot = ringSlots[nextIdx]
        val allocated = slot.ensureSurface(width, height)
        if (allocated) {
            ringAllocationCount++
        } else {
            ringSlotReuseCount++
        }
        val srcBytes = srcPtr.reinterpret<ByteVar>()

        val surface = slot.ioSurface
        if (surface != null) {
            IOSurfaceLock(surface, 0u, null)
            try {
                val base = IOSurfaceGetBaseAddress(surface) ?: return null
                val dstBytes = base.reinterpret<ByteVar>()
                if (isBgra) {
                    copyBgraRowsToBuffer(
                        srcBytes = srcBytes,
                        srcStrideBytes = srcStrideBytes,
                        dstBytes = dstBytes,
                        dstStrideBytes = slot.bytesPerRow,
                        width = width,
                        height = height,
                        ignoreAlpha = ignoreAlpha
                    )
                } else {
                    copyRgbaRowsAsBgra(
                        srcBytes = srcBytes,
                        dstBytes = dstBytes,
                        dstStrideBytes = slot.bytesPerRow,
                        width = width,
                        height = height
                    )
                }
            } finally {
                IOSurfaceUnlock(surface, 0u, null)
            }
            slot.releaseImage()
            slot.ignoreAlpha = ignoreAlpha
            slot.hasValidFrame = true
            ringCursor = nextIdx
            return slot.surfaceObj
        }

        val dstBuf = slot.nativeBuffer ?: return null
        val dstStride = width * 4
        val dstBytes = dstBuf.reinterpret<ByteVar>()
        if (isBgra) {
            copyBgraRowsToBuffer(
                srcBytes = srcBytes,
                srcStrideBytes = srcStrideBytes,
                dstBytes = dstBytes,
                dstStrideBytes = dstStride,
                width = width,
                height = height,
                ignoreAlpha = ignoreAlpha
            )
        } else {
            copyRgbaRowsAsBgra(srcBytes, dstBytes, dstStride, width, height)
        }
        val cgImage = buildSlotCgImage(slot, dstBuf, width, height, dstStride, isBgra = true, ignoreAlpha = ignoreAlpha)
        if (cgImage != null) {
            slot.ignoreAlpha = ignoreAlpha
            slot.hasValidFrame = true
            ringCursor = nextIdx
            return CFBridgingRelease(CGImageRetain(cgImage))
        }
        return null
    }

    private fun uploadByteArrayToSlot(
        bytes: ByteArray,
        width: Int,
        height: Int,
        isBgra: Boolean
    ): Any? {
        val nextIdx = (ringCursor + 1) % ringSlots.size
        val slot = ringSlots[nextIdx]
        val allocated = slot.ensureSurface(width, height)
        if (allocated) {
            ringAllocationCount++
        } else {
            ringSlotReuseCount++
        }
        val srcStride = width * 4

        val surface = slot.ioSurface
        if (surface != null) {
            IOSurfaceLock(surface, 0u, null)
            try {
                val base = IOSurfaceGetBaseAddress(surface) ?: return null
                val dstBytes = base.reinterpret<ByteVar>()
                bytes.usePinned { pinned ->
                    val srcBytes = pinned.addressOf(0)
                    if (isBgra) {
                        copyBgraRowsToBuffer(
                            srcBytes = srcBytes,
                            srcStrideBytes = srcStride,
                            dstBytes = dstBytes,
                            dstStrideBytes = slot.bytesPerRow,
                            width = width,
                            height = height,
                            ignoreAlpha = false
                        )
                    } else {
                        copyRgbaRowsAsBgra(
                            srcBytes = srcBytes,
                            dstBytes = dstBytes,
                            dstStrideBytes = slot.bytesPerRow,
                            width = width,
                            height = height
                        )
                    }
                }
            } finally {
                IOSurfaceUnlock(surface, 0u, null)
            }
            slot.releaseImage()
            slot.ignoreAlpha = false
            slot.hasValidFrame = true
            ringCursor = nextIdx
            return slot.surfaceObj
        }

        val dstBuf = slot.nativeBuffer ?: return null
        val byteSize = srcStride * height
        bytes.usePinned { pinned ->
            memcpy(dstBuf, pinned.addressOf(0), byteSize.toULong())
        }
        val cgImage = buildSlotCgImage(slot, dstBuf, width, height, srcStride, isBgra, ignoreAlpha = false)
        if (cgImage != null) {
            slot.ignoreAlpha = false
            slot.hasValidFrame = true
            ringCursor = nextIdx
            return CFBridgingRelease(CGImageRetain(cgImage))
        }
        return null
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
        val colorSpace = CGColorSpaceCreateWithName(kCGColorSpaceSRGB) ?: CGColorSpaceCreateDeviceRGB() ?: return null
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
            cgImageBuildCount++
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
        appliedBlackBackdropLayerPtr = 0L
        lastLayoutHostLayerPtr = 0L
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
