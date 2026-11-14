@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.nexustools.genericui.macos.render

import com.nexustools.genericui.desktop.core.DesktopInteractiveSurfaceSpec
import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.enums.ui.ObjectFit
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.MonocolorImageRecolorProcessor
import com.nexustools.genericui.macos.MacosSvgCoreGraphicsRasterizer
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.style.color.ImageTintMode
import com.nexustools.genericui.style.color.RgbaColor
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.AppKit.NSColor
import platform.AppKit.NSFont
import platform.AppKit.NSFontAttributeName
import platform.AppKit.NSFontWeightBold
import platform.AppKit.NSFontWeightMedium
import platform.AppKit.NSFontWeightRegular
import platform.AppKit.NSFontWeightSemibold
import platform.AppKit.NSForegroundColorAttributeName
import platform.AppKit.NSLineBreakByClipping
import platform.AppKit.NSLineBreakByTruncatingTail
import platform.AppKit.NSLineBreakByWordWrapping
import platform.AppKit.NSMutableParagraphStyle
import platform.AppKit.NSParagraphStyleAttributeName
import platform.AppKit.NSStringDrawingUsesFontLeading
import platform.AppKit.NSStringDrawingUsesLineFragmentOrigin
import platform.AppKit.NSTextAlignmentCenter
import platform.AppKit.NSTextAlignmentLeft
import platform.AppKit.NSTextAlignmentRight
import platform.AppKit.boundingRectWithSize
import platform.AppKit.drawInRect
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorRenderingIntent
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextAddPath
import platform.CoreGraphics.CGContextBeginPath
import platform.CoreGraphics.CGContextClipToRect
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextDrawLinearGradient
import platform.CoreGraphics.CGContextFillPath
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextSetAllowsAntialiasing
import platform.CoreGraphics.CGContextSetAlpha
import platform.CoreGraphics.CGContextSetInterpolationQuality
import platform.CoreGraphics.CGContextSetLineCap
import platform.CoreGraphics.CGContextSetLineWidth
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGContextSetRGBStrokeColor
import platform.CoreGraphics.CGContextSetShouldAntialias
import platform.CoreGraphics.CGContextStrokeLineSegments
import platform.CoreGraphics.CGContextStrokePath
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGDataProviderCreateWithData
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGGradientCreateWithColorComponents
import platform.CoreGraphics.CGGradientRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreate
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGInterpolationQuality
import platform.CoreGraphics.CGLineCap
import platform.CoreGraphics.CGPathAddCurveToPoint
import platform.CoreGraphics.CGPathAddLineToPoint
import platform.CoreGraphics.CGPathAddRect
import platform.CoreGraphics.CGPathCloseSubpath
import platform.CoreGraphics.CGPathCreateMutable
import platform.CoreGraphics.CGPathMoveToPoint
import platform.CoreGraphics.CGPathRelease
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Little
import platform.Foundation.NSAttributedString
import platform.Foundation.NSData
import platform.Foundation.NSMakeRect
import platform.Foundation.NSMakeSize
import platform.Foundation.NSThread
import platform.Foundation.create
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy
import kotlin.concurrent.atomics.AtomicInt
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shared CoreGraphics / AppKit 2D drawing primitives for `:desktop:macos` in flipped `NSView`
 * coordinates (`(0, 0)` at top-left, `+Y` downward).
 */
object MacosGraphicsDrawUtil {

    var onAsyncAssetLoaded: (() -> Unit)? = null

    private data class TextMeasureKey(
        val text: String,
        val sizeDeciPt: Int,
        val weightBucket: Int,
        val monospace: Boolean,
        val maxWidth: Int,
        val singleLine: Boolean
    )

    private val sharedDeviceRgbColorSpace by lazy { CGColorSpaceCreateDeviceRGB() }
    private val fontCache = HashMap<Int, NSFont>(64)
    private val textMeasureCache = LinkedHashMap<TextMeasureKey, Size>(2048)
    private val spriteCgImageCache = LinkedHashMap<Long, CGImageRef>(256)
    private val svgAndIconCache = LinkedHashMap<String, ImageBuffer>(128)
    private val remoteImageCache = LinkedHashMap<String, ImageBuffer>(256)
    private val cgImageCache = LinkedHashMap<String, CGImageRef>(256)
    private val asyncCacheLock = AtomicInt(0)
    private val pendingAsyncUrls = LinkedHashSet<String>()
    private val pendingUrlListeners = HashMap<String, MutableList<DesktopImageNativeImpl<*>>>()
    private val failedAsyncUrlTimestamps = HashMap<String, kotlin.time.TimeMark>()

    private inline fun <T> withAsyncCacheLock(block: () -> T): T {
        while (!asyncCacheLock.compareAndSet(0, 1)) {
            // Short spin for thread-safe async image cache access
        }
        try {
            return block()
        } finally {
            asyncCacheLock.store(0)
        }
    }

    private fun runOnMainOrInline(block: () -> Unit) {
        if (NSThread.isMainThread || !com.nexustools.genericui.macos.MacosApplicationNativeImpl.interactiveMacosGuiEnabled) {
            block()
        } else {
            dispatch_async(dispatch_get_main_queue()) {
                block()
            }
        }
    }

    private fun isExternalOrFilesystemUri(uri: String): Boolean {
        if (uri.startsWith("http://", ignoreCase = true) ||
            uri.startsWith("https://", ignoreCase = true) ||
            uri.startsWith("file://", ignoreCase = true) ||
            uri.startsWith("blob:", ignoreCase = true) ||
            uri.startsWith("data:", ignoreCase = true) ||
            uri.startsWith("memory://", ignoreCase = true) ||
            uri.startsWith("/")
        ) {
            return true
        }
        return uri.length >= 3 && uri[0].isLetter() && uri[1] == ':' && (uri[2] == '/' || uri[2] == '\\')
    }

    private val measureParaSingleLine by lazy {
        NSMutableParagraphStyle().apply { setLineBreakMode(NSLineBreakByClipping) }
    }
    private val measureParaMultiLine by lazy {
        NSMutableParagraphStyle().apply { setLineBreakMode(NSLineBreakByWordWrapping) }
    }
    private val drawParaLeftSingle by lazy {
        NSMutableParagraphStyle().apply {
            setAlignment(NSTextAlignmentLeft)
            setLineBreakMode(NSLineBreakByTruncatingTail)
        }
    }
    private val drawParaLeftMulti by lazy {
        NSMutableParagraphStyle().apply {
            setAlignment(NSTextAlignmentLeft)
            setLineBreakMode(NSLineBreakByWordWrapping)
        }
    }
    private val drawParaCenterSingle by lazy {
        NSMutableParagraphStyle().apply {
            setAlignment(NSTextAlignmentCenter)
            setLineBreakMode(NSLineBreakByTruncatingTail)
        }
    }
    private val drawParaCenterMulti by lazy {
        NSMutableParagraphStyle().apply {
            setAlignment(NSTextAlignmentCenter)
            setLineBreakMode(NSLineBreakByWordWrapping)
        }
    }
    private val drawParaRightSingle by lazy {
        NSMutableParagraphStyle().apply {
            setAlignment(NSTextAlignmentRight)
            setLineBreakMode(NSLineBreakByTruncatingTail)
        }
    }
    private val drawParaRightMulti by lazy {
        NSMutableParagraphStyle().apply {
            setAlignment(NSTextAlignmentRight)
            setLineBreakMode(NSLineBreakByWordWrapping)
        }
    }

    fun fillRect(ctx: MacosRenderContext, x: Int, y: Int, w: Int, h: Int, color: RgbaColor) {
        if (w <= 0 || h <= 0 || color.a <= 0) return
        val alpha = ((color.a / 255.0) * ctx.currentOpacity).coerceIn(0.0, 1.0)
        if (alpha <= 0.001) return
        CGContextSaveGState(ctx.cgContext)
        CGContextSetRGBFillColor(
            ctx.cgContext,
            color.r / 255.0,
            color.g / 255.0,
            color.b / 255.0,
            alpha
        )
        CGContextFillRect(ctx.cgContext, CGRectMake(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble()))
        CGContextRestoreGState(ctx.cgContext)
    }

    fun fillRoundedRect(
        ctx: MacosRenderContext,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        radius: Float,
        color: RgbaColor
    ) {
        if (w <= 0 || h <= 0 || color.a <= 0) return
        if (radius <= 0.5f) {
            fillRect(ctx, x, y, w, h, color)
            return
        }
        val alpha = ((color.a / 255.0) * ctx.currentOpacity).coerceIn(0.0, 1.0)
        if (alpha <= 0.001) return
        val r = min(radius.toDouble(), min(w, h) * 0.5)
        val path = createRoundedRectPath(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), r) ?: return
        try {
            CGContextSaveGState(ctx.cgContext)
            CGContextSetAllowsAntialiasing(ctx.cgContext, true)
            CGContextSetShouldAntialias(ctx.cgContext, true)
            CGContextBeginPath(ctx.cgContext)
            CGContextAddPath(ctx.cgContext, path)
            CGContextSetRGBFillColor(
                ctx.cgContext,
                color.r / 255.0,
                color.g / 255.0,
                color.b / 255.0,
                alpha
            )
            CGContextFillPath(ctx.cgContext)
            CGContextRestoreGState(ctx.cgContext)
        } finally {
            CGPathRelease(path)
        }
    }

    fun strokeRoundedRect(
        ctx: MacosRenderContext,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        radius: Float,
        borderColor: RgbaColor,
        lineWidth: Float = 1f
    ) {
        if (w <= 0 || h <= 0 || borderColor.a <= 0 || lineWidth <= 0f) return
        val alpha = ((borderColor.a / 255.0) * ctx.currentOpacity).coerceIn(0.0, 1.0)
        if (alpha <= 0.001) return
        val inset = lineWidth * 0.5
        val rx = x.toDouble() + inset
        val ry = y.toDouble() + inset
        val rw = (w.toDouble() - lineWidth).coerceAtLeast(1.0)
        val rh = (h.toDouble() - lineWidth).coerceAtLeast(1.0)
        val r = min(max(0.0, radius.toDouble() - inset), min(rw, rh) * 0.5)
        val path = createRoundedRectPath(rx, ry, rw, rh, r) ?: return
        try {
            CGContextSaveGState(ctx.cgContext)
            CGContextSetAllowsAntialiasing(ctx.cgContext, true)
            CGContextSetShouldAntialias(ctx.cgContext, true)
            CGContextBeginPath(ctx.cgContext)
            CGContextAddPath(ctx.cgContext, path)
            CGContextSetLineWidth(ctx.cgContext, lineWidth.toDouble())
            CGContextSetRGBStrokeColor(
                ctx.cgContext,
                borderColor.r / 255.0,
                borderColor.g / 255.0,
                borderColor.b / 255.0,
                alpha
            )
            CGContextStrokePath(ctx.cgContext)
            CGContextRestoreGState(ctx.cgContext)
        } finally {
            CGPathRelease(path)
        }
    }

    fun fillVerticalGradient(
        ctx: MacosRenderContext,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        topColor: RgbaColor,
        bottomColor: RgbaColor,
        radius: Float = 0f
    ) {
        if (w <= 0 || h <= 0) return
        val topA = ((topColor.a / 255.0) * ctx.currentOpacity).coerceIn(0.0, 1.0)
        val botA = ((bottomColor.a / 255.0) * ctx.currentOpacity).coerceIn(0.0, 1.0)
        if (topA <= 0.001 && botA <= 0.001) return

        val cs = CGColorSpaceCreateDeviceRGB() ?: return
        try {
            val comps = doubleArrayOf(
                topColor.r / 255.0, topColor.g / 255.0, topColor.b / 255.0, topA,
                bottomColor.r / 255.0, bottomColor.g / 255.0, bottomColor.b / 255.0, botA
            )
            val locations = doubleArrayOf(0.0, 1.0)
            val gradient = comps.usePinned { cPin ->
                locations.usePinned { lPin ->
                    CGGradientCreateWithColorComponents(cs, cPin.addressOf(0), lPin.addressOf(0), 2u)
                }
            } ?: return
            try {
                CGContextSaveGState(ctx.cgContext)
                if (radius > 0.5f) {
                    val r = min(radius.toDouble(), min(w, h) * 0.5)
                    val path = createRoundedRectPath(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble(), r)
                    if (path != null) {
                        CGContextBeginPath(ctx.cgContext)
                        CGContextAddPath(ctx.cgContext, path)
                        platform.CoreGraphics.CGContextClip(ctx.cgContext)
                        CGPathRelease(path)
                    }
                } else {
                    CGContextClipToRect(ctx.cgContext, CGRectMake(x.toDouble(), y.toDouble(), w.toDouble(), h.toDouble()))
                }
                CGContextDrawLinearGradient(
                    ctx.cgContext,
                    gradient,
                    CGPointMake(x.toDouble(), y.toDouble()),
                    CGPointMake(x.toDouble(), (y + h).toDouble()),
                    0u
                )
                CGContextRestoreGState(ctx.cgContext)
            } finally {
                CGGradientRelease(gradient)
            }
        } finally {
            CGColorSpaceRelease(cs)
        }
    }

    fun drawLine(
        ctx: MacosRenderContext,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        color: RgbaColor,
        width: Float = 1f
    ) {
        if (color.a <= 0 || width <= 0f) return
        val alpha = ((color.a / 255.0) * ctx.currentOpacity).coerceIn(0.0, 1.0)
        if (alpha <= 0.001) return
        CGContextSaveGState(ctx.cgContext)
        val path = CGPathCreateMutable()
        if (path != null) {
            CGPathMoveToPoint(path, null, x1.toDouble(), y1.toDouble())
            CGPathAddLineToPoint(path, null, x2.toDouble(), y2.toDouble())
            CGContextBeginPath(ctx.cgContext)
            CGContextAddPath(ctx.cgContext, path)
            CGContextSetLineWidth(ctx.cgContext, width.toDouble())
            CGContextSetLineCap(ctx.cgContext, CGLineCap.kCGLineCapRound)
            CGContextSetRGBStrokeColor(
                ctx.cgContext,
                color.r / 255.0,
                color.g / 255.0,
                color.b / 255.0,
                alpha
            )
            CGContextStrokePath(ctx.cgContext)
            CGPathRelease(path)
        }
        CGContextRestoreGState(ctx.cgContext)
    }

    inline fun withClip(
        ctx: MacosRenderContext,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        block: () -> Unit
    ) {
        CGContextSaveGState(ctx.cgContext)
        CGContextClipToRect(ctx.cgContext, CGRectMake(x.toDouble(), y.toDouble(), w.coerceAtLeast(0).toDouble(), h.coerceAtLeast(0).toDouble()))
        val nonEmpty = ctx.pushClipBounds(x, y, w, h)
        try {
            if (nonEmpty) {
                block()
            }
        } finally {
            ctx.popClipBounds()
            CGContextRestoreGState(ctx.cgContext)
        }
    }

    fun resolveFont(
        fontSizePx: Float = 13.5f,
        weightNumeric: Int = 400,
        monospace: Boolean = false,
        italic: Boolean = false
    ): NSFont {
        val clampedPx = fontSizePx.coerceIn(9f, 72f)
        val sizeDeciPt = (clampedPx * 10f).roundToInt()
        val weightBucket = when {
            weightNumeric >= 700 -> 3
            weightNumeric >= 600 -> 2
            weightNumeric >= 500 -> 1
            else -> 0
        }
        val key = (sizeDeciPt shl 4) or (weightBucket shl 2) or (if (monospace) 2 else 0) or (if (italic) 1 else 0)
        fontCache[key]?.let { return it }

        val pt = sizeDeciPt / 10.0
        val nsWeight = when (weightBucket) {
            3 -> NSFontWeightBold
            2 -> NSFontWeightSemibold
            1 -> NSFontWeightMedium
            else -> NSFontWeightRegular
        }
        val created = if (monospace) {
            NSFont.monospacedSystemFontOfSize(pt, weight = nsWeight)
        } else {
            NSFont.systemFontOfSize(pt, weight = nsWeight)
        }
        fontCache[key] = created
        return created
    }

    fun measureText(
        text: String,
        fontSizePx: Float = 13.5f,
        weightNumeric: Int = 400,
        monospace: Boolean = false,
        maxWidth: Int = 4096,
        singleLine: Boolean = true
    ): Size {
        if (text.isEmpty()) {
            return Size(0, ceil(fontSizePx * 1.32f).toInt())
        }
        val clampedPx = fontSizePx.coerceIn(9f, 72f)
        val sizeDeciPt = (clampedPx * 10f).roundToInt()
        val weightBucket = when {
            weightNumeric >= 700 -> 3
            weightNumeric >= 600 -> 2
            weightNumeric >= 500 -> 1
            else -> 0
        }
        val normMaxW = if (singleLine) 0 else maxWidth.coerceAtLeast(16)
        val cacheKey = if (text.length <= 256) {
            TextMeasureKey(text, sizeDeciPt, weightBucket, monospace, normMaxW, singleLine)
        } else {
            null
        }
        if (cacheKey != null) {
            textMeasureCache[cacheKey]?.let { return it }
        }

        val font = resolveFont(clampedPx, weightNumeric, monospace)
        val para = if (singleLine) measureParaSingleLine else measureParaMultiLine
        val attrs: Map<Any?, *> = mapOf(
            NSFontAttributeName to font,
            NSParagraphStyleAttributeName to para
        )
        val attrStr = NSAttributedString.create(string = text, attributes = attrs)
        val boundW = if (singleLine) 100_000.0 else normMaxW.toDouble()
        val rect = attrStr.boundingRectWithSize(
            size = NSMakeSize(boundW, 100_000.0),
            options = NSStringDrawingUsesLineFragmentOrigin or NSStringDrawingUsesFontLeading,
            context = null
        )
        val measured = rect.useContents {
            Size(
                width = ceil(size.width).toInt().coerceAtLeast(1),
                height = ceil(size.height).toInt().coerceAtLeast(ceil(clampedPx * 1.25f).toInt())
            )
        }
        if (cacheKey != null) {
            if (textMeasureCache.size >= 2048) {
                textMeasureCache.keys.firstOrNull()?.let { textMeasureCache.remove(it) }
            }
            textMeasureCache[cacheKey] = measured
        }
        return measured
    }

    fun drawText(
        ctx: MacosRenderContext,
        text: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        color: RgbaColor,
        fontSizePx: Float = 13.5f,
        weightNumeric: Int = 400,
        alignment: TextAlignment = TextAlignment.START,
        singleLine: Boolean = true,
        verticallyCentered: Boolean = true,
        monospace: Boolean = false
    ) {
        if (text.isEmpty() || w <= 0 || h <= 0 || color.a <= 0) return
        val alpha = ((color.a / 255.0) * ctx.currentOpacity).coerceIn(0.0, 1.0)
        if (alpha <= 0.001) return

        val font = resolveFont(fontSizePx, weightNumeric, monospace)
        val nsColor = NSColor.colorWithSRGBRed(
            red = color.r / 255.0,
            green = color.g / 255.0,
            blue = color.b / 255.0,
            alpha = alpha
        )
        val para = when (alignment) {
            TextAlignment.CENTER -> if (singleLine) drawParaCenterSingle else drawParaCenterMulti
            TextAlignment.END -> if (singleLine) drawParaRightSingle else drawParaRightMulti
            else -> if (singleLine) drawParaLeftSingle else drawParaLeftMulti
        }
        val attrs: Map<Any?, *> = mapOf(
            NSFontAttributeName to font,
            NSForegroundColorAttributeName to nsColor,
            NSParagraphStyleAttributeName to para
        )
        val attrStr = NSAttributedString.create(string = text, attributes = attrs)

        val drawY = if (verticallyCentered) {
            val measuredH = measureText(
                text = text,
                fontSizePx = fontSizePx,
                weightNumeric = weightNumeric,
                monospace = monospace,
                maxWidth = w,
                singleLine = singleLine
            ).height
            y + ((h - measuredH) / 2).coerceAtLeast(0)
        } else {
            y
        }

        withClip(ctx, x, y, w, h) {
            attrStr.drawInRect(NSMakeRect(x.toDouble(), drawY.toDouble(), w.toDouble(), (h + 6).toDouble()))
        }
    }

    /**
     * Blits a straight-ARGB (`0xAARRGGBB`) pixel sprite ([pixels] of [srcW] x [srcH]) into `(dstX, dstY, dstW, dstH)`.
     */
    fun blitArgbSprite(
        ctx: MacosRenderContext,
        pixels: IntArray,
        srcW: Int,
        srcH: Int,
        dstX: Int,
        dstY: Int,
        dstW: Int = srcW,
        dstH: Int = srcH,
        antialiasing: Boolean = true
    ) {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0 || pixels.size < srcW * srcH) return
        val opScale = ctx.currentOpacity.coerceIn(0f, 1f)
        if (opScale <= 0.001f) return

        val spriteKey = (pixels.hashCode().toLong() and 0xFFFFFFFFL) or
            ((srcW.toLong() and 0xFFFFL) shl 32) or
            ((srcH.toLong() and 0xFFFFL) shl 48)
        var cgImg = spriteCgImageCache[spriteKey]
        if (cgImg == null) {
            val premul = ByteArray(srcW * srcH * 4)
            var p = 0
            var bIdx = 0
            val count = srcW * srcH
            while (p < count) {
                val argb = pixels[p]
                val a = (argb ushr 24) and 0xFF
                if (a > 0) {
                    val r = (argb ushr 16) and 0xFF
                    val g = (argb ushr 8) and 0xFF
                    val b = argb and 0xFF
                    premul[bIdx] = ((r * a + 127) / 255).toByte()
                    premul[bIdx + 1] = ((g * a + 127) / 255).toByte()
                    premul[bIdx + 2] = ((b * a + 127) / 255).toByte()
                    premul[bIdx + 3] = a.toByte()
                }
                p++
                bIdx += 4
            }
            val cs = sharedDeviceRgbColorSpace ?: return
            cgImg = premul.usePinned { pinned ->
                val bmpCtx = CGBitmapContextCreate(
                    data = pinned.addressOf(0),
                    width = srcW.convert(),
                    height = srcH.convert(),
                    bitsPerComponent = 8u,
                    bytesPerRow = (srcW * 4).convert(),
                    space = cs,
                    bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
                ) ?: return
                try {
                    CGBitmapContextCreateImage(bmpCtx)
                } finally {
                    CGContextRelease(bmpCtx)
                }
            } ?: return
            if (spriteCgImageCache.size >= 256) {
                val eldest = spriteCgImageCache.keys.firstOrNull()
                if (eldest != null) {
                    spriteCgImageCache.remove(eldest)?.let { CGImageRelease(it) }
                }
            }
            spriteCgImageCache[spriteKey] = cgImg
        }

        if (opScale < 0.999f) {
            CGContextSaveGState(ctx.cgContext)
            CGContextSetAlpha(ctx.cgContext, opScale.toDouble())
            drawCgImageRightSideUp(ctx.cgContext, cgImg, dstX, dstY, dstW, dstH, antialiasing)
            CGContextRestoreGState(ctx.cgContext)
        } else {
            drawCgImageRightSideUp(ctx.cgContext, cgImg, dstX, dstY, dstW, dstH, antialiasing)
        }
    }

    /**
     * Draws an [ImageBuffer] into `(dstX, dstY, dstW, dstH)` respecting [objectFit], optional [tintColor],
     * and optional rounded corner clipping ([cornerRadius]).
     */
    fun drawImageBuffer(
        ctx: MacosRenderContext,
        buffer: ImageBuffer,
        dstX: Int,
        dstY: Int,
        dstW: Int,
        dstH: Int,
        objectFit: ObjectFit = ObjectFit.CONTAIN,
        tintColor: RgbaColor? = null,
        tintMode: ImageTintMode = ImageTintMode.NONE,
        cornerRadius: Float = 0f,
        antialiasing: Boolean = true,
        cacheKey: String? = null
    ) {
        if (dstW <= 0 || dstH <= 0 || buffer.width <= 0 || buffer.height <= 0) return
        val effectiveOpacity = ctx.currentOpacity.coerceIn(0f, 1f)
        if (effectiveOpacity <= 0.001f) return

        val applyTint = tintColor != null && tintMode != ImageTintMode.NONE && tintColor.a > 0
        val tintAlphaScale = if (applyTint && tintColor != null) (tintColor.a / 255f).coerceIn(0f, 1f) else 1f
        val fullCgKey = cacheKey?.let { key ->
            val tintPart = if (applyTint && tintColor != null) {
                "${tintColor.r}_${tintColor.g}_${tintColor.b}_${tintColor.a}_${tintMode.name}"
            } else {
                "raw"
            }
            "$key|$tintPart"
        }

        val cachedCgImg = fullCgKey?.let { cgImageCache[it] }
        val srcW: Int
        val srcH: Int
        val cgImg: CGImageRef
        val releaseAfterDraw: Boolean

        if (cachedCgImg != null) {
            srcW = buffer.width
            srcH = buffer.height
            cgImg = cachedCgImg
            releaseAfterDraw = false
        } else {
            val rgba = buffer.asRgba8888()
            srcW = rgba.width
            srcH = rgba.height
            val srcBytes = rgba.bytes
            if (srcBytes.size < srcW * srcH * 4) return

            val premul = ByteArray(srcW * srcH * 4)
            var i = 0
            val limit = srcW * srcH * 4
            while (i < limit) {
                val rawA = srcBytes[i + 3].toInt() and 0xFF
                val a = if (tintAlphaScale >= 0.999f) rawA else (rawA * tintAlphaScale).roundToInt().coerceIn(0, 255)
                if (a > 0) {
                    val r: Int
                    val g: Int
                    val b: Int
                    if (applyTint && tintColor != null) {
                        when (tintMode) {
                            ImageTintMode.MULTIPLY -> {
                                r = ((srcBytes[i].toInt() and 0xFF) * tintColor.r) / 255
                                g = ((srcBytes[i + 1].toInt() and 0xFF) * tintColor.g) / 255
                                b = ((srcBytes[i + 2].toInt() and 0xFF) * tintColor.b) / 255
                            }
                            else -> {
                                r = tintColor.r
                                g = tintColor.g
                                b = tintColor.b
                            }
                        }
                    } else {
                        r = srcBytes[i].toInt() and 0xFF
                        g = srcBytes[i + 1].toInt() and 0xFF
                        b = srcBytes[i + 2].toInt() and 0xFF
                    }
                    premul[i] = ((r * a + 127) / 255).toByte()
                    premul[i + 1] = ((g * a + 127) / 255).toByte()
                    premul[i + 2] = ((b * a + 127) / 255).toByte()
                    premul[i + 3] = a.toByte()
                }
                i += 4
            }

            val cs = CGColorSpaceCreateDeviceRGB() ?: return
            val createdImg = try {
                premul.usePinned { pinned ->
                    val bmpCtx = CGBitmapContextCreate(
                        data = pinned.addressOf(0),
                        width = srcW.convert(),
                        height = srcH.convert(),
                        bitsPerComponent = 8u,
                        bytesPerRow = (srcW * 4).convert(),
                        space = cs,
                        bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
                    ) ?: return
                    try {
                        CGBitmapContextCreateImage(bmpCtx)
                    } finally {
                        CGContextRelease(bmpCtx)
                    }
                }
            } finally {
                CGColorSpaceRelease(cs)
            } ?: return

            if (fullCgKey != null) {
                if (cgImageCache.size >= 256) {
                    val eldestKey = cgImageCache.keys.firstOrNull()
                    if (eldestKey != null) {
                        cgImageCache.remove(eldestKey)?.let { CGImageRelease(it) }
                    }
                }
                cgImageCache[fullCgKey] = createdImg
                cgImg = createdImg
                releaseAfterDraw = false
            } else {
                cgImg = createdImg
                releaseAfterDraw = true
            }
        }

        val fitRect = computeObjectFitRect(srcW, srcH, dstX, dstY, dstW, dstH, objectFit)
        try {
            CGContextSaveGState(ctx.cgContext)
            if (effectiveOpacity < 0.999f) {
                CGContextSetAlpha(ctx.cgContext, effectiveOpacity.toDouble())
            }
            if (cornerRadius > 0.5f) {
                val path = createRoundedRectPath(
                    dstX.toDouble(),
                    dstY.toDouble(),
                    dstW.toDouble(),
                    dstH.toDouble(),
                    min(cornerRadius.toDouble(), min(dstW, dstH) * 0.5)
                )
                if (path != null) {
                    CGContextBeginPath(ctx.cgContext)
                    CGContextAddPath(ctx.cgContext, path)
                    platform.CoreGraphics.CGContextClip(ctx.cgContext)
                    CGPathRelease(path)
                }
            } else if (objectFit == ObjectFit.COVER || objectFit == ObjectFit.NONE) {
                CGContextClipToRect(ctx.cgContext, CGRectMake(dstX.toDouble(), dstY.toDouble(), dstW.toDouble(), dstH.toDouble()))
            }
            drawCgImageRightSideUp(ctx.cgContext, cgImg, fitRect.x, fitRect.y, fitRect.width, fitRect.height, antialiasing)
            CGContextRestoreGState(ctx.cgContext)
        } finally {
            if (releaseAfterDraw) {
                CGImageRelease(cgImg)
            }
        }
    }

    /**
     * Resolves and renders an icon or image URI (`res://drawable/...`, `@drawable/...`, `ic_...`,
     * `memory://...`, `data:image/...`, file path, or `http(s)://...`).
     */
    fun drawIconOrUri(
        ctx: MacosRenderContext,
        uriOrIcon: String,
        dstX: Int,
        dstY: Int,
        dstW: Int,
        dstH: Int,
        tintColor: RgbaColor? = null,
        objectFit: ObjectFit = ObjectFit.CONTAIN,
        cornerRadius: Float = 0f,
        imageImpl: DesktopImageNativeImpl<*>? = null
    ): Boolean {
        val clean = uriOrIcon.trim()
        if (clean.isEmpty() || dstW <= 0 || dstH <= 0) return false

        fun drawResolved(rawBuf: ImageBuffer, cacheKeyBase: String? = null) {
            val implTintMode = imageImpl?.getTintMode() ?: ImageTintMode.NONE
            val effectiveTint = tintColor ?: imageImpl?.getTintColor()
            if (effectiveTint == null && implTintMode != ImageTintMode.NONE) {
                val recolorKey = cacheKeyBase?.let { "$it#recolor_${implTintMode.name}_${ctx.darkMode}" }
                val processed = if (recolorKey != null) {
                    svgAndIconCache[recolorKey] ?: MonocolorImageRecolorProcessor.processImageBuffer(
                        rawBuf,
                        implTintMode,
                        null,
                        ctx.darkMode
                    ).also { res ->
                        if (svgAndIconCache.size >= 256) {
                            svgAndIconCache.keys.firstOrNull()?.let { svgAndIconCache.remove(it) }
                        }
                        svgAndIconCache[recolorKey] = res
                    }
                } else {
                    MonocolorImageRecolorProcessor.processImageBuffer(rawBuf, implTintMode, null, ctx.darkMode)
                }
                drawImageBuffer(
                    ctx = ctx,
                    buffer = processed,
                    dstX = dstX,
                    dstY = dstY,
                    dstW = dstW,
                    dstH = dstH,
                    objectFit = objectFit,
                    tintColor = null,
                    tintMode = ImageTintMode.NONE,
                    cornerRadius = cornerRadius,
                    antialiasing = imageImpl?.isAntialiasing() ?: true,
                    cacheKey = recolorKey
                )
                return
            }

            val mode = if (effectiveTint != null) {
                if (implTintMode == ImageTintMode.NONE || implTintMode == ImageTintMode.MONOCOLOR_AUTO_THEME) {
                    ImageTintMode.SRC_IN
                } else {
                    implTintMode
                }
            } else {
                ImageTintMode.NONE
            }
            drawImageBuffer(
                ctx = ctx,
                buffer = rawBuf,
                dstX = dstX,
                dstY = dstY,
                dstW = dstW,
                dstH = dstH,
                objectFit = objectFit,
                tintColor = effectiveTint,
                tintMode = mode,
                cornerRadius = cornerRadius,
                antialiasing = imageImpl?.isAntialiasing() ?: true,
                cacheKey = cacheKeyBase
            )
        }

        // 1. Check already-loaded buffer on DesktopImageNativeImpl
        imageImpl?.loadedImageBuffer?.let { buf ->
            drawResolved(buf, cacheKeyBase = "$clean@${buf.width}x${buf.height}")
            return true
        }

        // 2. Check memory:// registered buffers
        if (clean.startsWith("memory://", ignoreCase = true)) {
            ImageLoader.resolveMemoryBuffer(clean)?.let { buf ->
                drawResolved(buf, cacheKeyBase = clean)
                return true
            }
        }

        // 3. Check compiled drawable registry (instant Retina SVG / Base64 raster decode)
        val scalePxW = (dstW * ctx.backingScale).roundToInt().coerceIn(16, 512)
        val scalePxH = (dstH * ctx.backingScale).roundToInt().coerceIn(16, 512)
        val cacheKey = "$clean@${scalePxW}x$scalePxH"
        svgAndIconCache[cacheKey]?.let { cached ->
            drawResolved(cached, cacheKeyBase = cacheKey)
            return true
        }

        val drawable = if (!isExternalOrFilesystemUri(clean)) {
            Resources.getDrawable(clean)
                ?: clean.removePrefix("res://drawable/").removePrefix("@drawable/").removeSuffix(".svg").removeSuffix(".png")
                    .takeIf { it != clean }?.let { Resources.getDrawable(it) }
        } else {
            null
        }
        if (drawable != null) {
            val decoded = decodeDrawableResource(drawable, scalePxW, scalePxH)
            if (decoded != null) {
                if (svgAndIconCache.size >= 256) {
                    val firstKey = svgAndIconCache.keys.firstOrNull()
                    if (firstKey != null) svgAndIconCache.remove(firstKey)
                }
                svgAndIconCache[cacheKey] = decoded
                imageImpl?.loadedImageBuffer = decoded
                drawResolved(decoded, cacheKeyBase = cacheKey)
                return true
            }
        }

        // 4. Data URL (e.g., inline SVG/PNG)
        if (clean.startsWith("data:image/", ignoreCase = true)) {
            val decoded = decodeDataUrl(clean, scalePxW, scalePxH)
            if (decoded != null) {
                svgAndIconCache[cacheKey] = decoded
                imageImpl?.loadedImageBuffer = decoded
                drawResolved(decoded, cacheKeyBase = cacheKey)
                return true
            }
        }

        // 5. Check strong LRU remoteImageCache / ImageLoader cache or launch shared async load for HTTP/disk assets
        val cachedRemote = withAsyncCacheLock {
            val hit = remoteImageCache.remove(clean)
            if (hit != null) {
                remoteImageCache[clean] = hit
            }
            hit
        }
        if (cachedRemote != null) {
            imageImpl?.loadedImageBuffer = cachedRemote
            drawResolved(cachedRemote, cacheKeyBase = clean)
            return true
        }

        ImageLoader.getWeakImageReference(clean)?.let { weakBuf ->
            withAsyncCacheLock {
                if (remoteImageCache.size >= 256 && !remoteImageCache.containsKey(clean)) {
                    remoteImageCache.keys.firstOrNull()?.let { remoteImageCache.remove(it) }
                }
                remoteImageCache[clean] = weakBuf
            }
            imageImpl?.loadedImageBuffer = weakBuf
            drawResolved(weakBuf, cacheKeyBase = clean)
            return true
        }

        val shouldLaunch = withAsyncCacheLock {
            val prevFail = failedAsyncUrlTimestamps[clean]
            if (prevFail != null) {
                if (prevFail.elapsedNow().inWholeMilliseconds < 15_000L) {
                    return@withAsyncCacheLock false
                }
                failedAsyncUrlTimestamps.remove(clean)
            }
            if (imageImpl != null) {
                val list = pendingUrlListeners.getOrPut(clean) { ArrayList(2) }
                if (list.none { it === imageImpl }) {
                    list.add(imageImpl)
                }
            }
            if (clean in pendingAsyncUrls) {
                imageImpl?.markLoading(true)
                false
            } else {
                pendingAsyncUrls.add(clean)
                imageImpl?.markLoading(true)
                true
            }
        }

        if (shouldLaunch) {
            val src = if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
                ImageSource.fromUrl(clean)
            } else {
                ImageSource.fromFile(clean)
            }
            ImageLoader.launchLoad(
                source = src,
                options = ImageDecodeOptions(antialiasing = true),
                onLoaded = { loaded ->
                    ImageLoader.trackWeakImageReference(clean, loaded)
                    val listeners = withAsyncCacheLock {
                        pendingAsyncUrls.remove(clean)
                        failedAsyncUrlTimestamps.remove(clean)
                        if (remoteImageCache.size >= 256 && !remoteImageCache.containsKey(clean)) {
                            remoteImageCache.keys.firstOrNull()?.let { remoteImageCache.remove(it) }
                        }
                        remoteImageCache[clean] = loaded
                        pendingUrlListeners.remove(clean).orEmpty()
                    }
                    runOnMainOrInline {
                        for (listener in listeners) {
                            if (!listener.isDisposed) {
                                listener.notifyAsyncLoadSucceeded(loaded)
                            }
                        }
                        onAsyncAssetLoaded?.invoke()
                    }
                },
                onError = {
                    val listeners = withAsyncCacheLock {
                        pendingAsyncUrls.remove(clean)
                        failedAsyncUrlTimestamps[clean] = kotlin.time.TimeSource.Monotonic.markNow()
                        pendingUrlListeners.remove(clean).orEmpty()
                    }
                    runOnMainOrInline {
                        for (listener in listeners) {
                            if (!listener.isDisposed) {
                                listener.notifyAsyncLoadFailed()
                            }
                        }
                        onAsyncAssetLoaded?.invoke()
                    }
                }
            )
        }
        return false
    }

    /**
     * Composites a live extension surface ([DesktopInteractiveSurfaceSpec] — e.g. LibVLC video or
     * Vulkan/MoltenVK 3D visualizer) into `(x, y, w, h)` with zero extension-module coupling.
     */
    fun drawInteractiveSurface(
        ctx: MacosRenderContext,
        spec: DesktopInteractiveSurfaceSpec,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ) {
        if (w <= 0 || h <= 0) return
        fillRect(ctx, x, y, w, h, RgbaColor.BLACK)

        // Notify dynamic surfaces (Canvas2D/Canvas3D without fixed media aspect ratio) of target viewport size
        if (spec.defaultAspectRatio == null) {
            spec.resizeSurface?.invoke(w.coerceAtLeast(16), h.coerceAtLeast(16))
        }

        val surfW = spec.surfaceWidth().coerceAtLeast(0)
        val surfH = spec.surfaceHeight().coerceAtLeast(0)
        if (surfW <= 0 || surfH <= 0) return

        val swap = spec.swapCount()
        spec.dispatchBeforePresentFrame(swap, null)

        val dstRect = if (spec.defaultAspectRatio == null) {
            Rect(x, y, w, h)
        } else {
            val aspect = (spec.defaultAspectRatio?.takeIf { it > 0.05f } ?: (surfW.toFloat() / surfH.toFloat()))
            val boxAspect = w.toFloat() / h.toFloat()
            if (boxAspect > aspect) {
                val fitW = (h * aspect).roundToInt().coerceIn(1, w)
                Rect(x + (w - fitW) / 2, y, fitW, h)
            } else {
                val fitH = (w / aspect).roundToInt().coerceIn(1, h)
                Rect(x, y + (h - fitH) / 2, w, fitH)
            }
        }

        var frameDrawn = false
        val rowBytes = surfW * 4
        val requiredByteLen = rowBytes * surfH

        // 1. True zero-copy raw BGRA pointer path (used by LibVLC vmem & native GPU readback; second = total byte length)
        val ptrPair = spec.acquireBgraPtr?.invoke()
        val rawPtr = ptrPair?.first as? CPointer<*>
        val byteCapacity = ptrPair?.second ?: 0
        if (rawPtr != null && byteCapacity >= requiredByteLen) {
            val cs = sharedDeviceRgbColorSpace ?: return
            val bmpInfo = kCGBitmapByteOrder32Little or CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst.value
            val provider = CGDataProviderCreateWithData(
                info = null,
                data = rawPtr,
                size = requiredByteLen.convert(),
                releaseData = null
            )
            if (provider != null) {
                try {
                    val cgImg = CGImageCreate(
                        width = surfW.convert(),
                        height = surfH.convert(),
                        bitsPerComponent = 8u,
                        bitsPerPixel = 32u,
                        bytesPerRow = rowBytes.convert(),
                        space = cs,
                        bitmapInfo = bmpInfo,
                        provider = provider,
                        decode = null,
                        shouldInterpolate = true,
                        intent = CGColorRenderingIntent.kCGRenderingIntentDefault
                    )
                    if (cgImg != null) {
                        try {
                            drawCgImageRightSideUp(
                                ctx.cgContext,
                                cgImg,
                                dstRect.x,
                                dstRect.y,
                                dstRect.width,
                                dstRect.height,
                                antialiasing = true,
                                fastBilinear = true
                            )
                            frameDrawn = true
                        } finally {
                            CGImageRelease(cgImg)
                        }
                    }
                } finally {
                    CGDataProviderRelease(provider)
                }
            }
        }

        // 2. ByteArray BGRA capture path
        if (!frameDrawn) {
            val bgraBytes = spec.captureBgra?.invoke()
            if (bgraBytes != null && bgraBytes.size >= requiredByteLen) {
                val cs = sharedDeviceRgbColorSpace ?: return
                val bmpInfo = kCGBitmapByteOrder32Little or CGImageAlphaInfo.kCGImageAlphaNoneSkipFirst.value
                bgraBytes.usePinned { pinned ->
                    val provider = CGDataProviderCreateWithData(
                        info = null,
                        data = pinned.addressOf(0),
                        size = requiredByteLen.convert(),
                        releaseData = null
                    )
                    if (provider != null) {
                        try {
                            val cgImg = CGImageCreate(
                                width = surfW.convert(),
                                height = surfH.convert(),
                                bitsPerComponent = 8u,
                                bitsPerPixel = 32u,
                                bytesPerRow = rowBytes.convert(),
                                space = cs,
                                bitmapInfo = bmpInfo,
                                provider = provider,
                                decode = null,
                                shouldInterpolate = true,
                                intent = CGColorRenderingIntent.kCGRenderingIntentDefault
                            )
                            if (cgImg != null) {
                                try {
                                    drawCgImageRightSideUp(
                                        ctx.cgContext,
                                        cgImg,
                                        dstRect.x,
                                        dstRect.y,
                                        dstRect.width,
                                        dstRect.height,
                                        antialiasing = true,
                                        fastBilinear = true
                                    )
                                    frameDrawn = true
                                } finally {
                                    CGImageRelease(cgImg)
                                }
                            }
                        } finally {
                            CGDataProviderRelease(provider)
                        }
                    }
                }
            }
        }

        // 3. ByteArray RGBA capture path
        if (!frameDrawn) {
            val rgbaBytes = spec.captureRgba?.invoke()
            if (rgbaBytes != null && rgbaBytes.size >= requiredByteLen) {
                val cs = sharedDeviceRgbColorSpace ?: return
                rgbaBytes.usePinned { pinned ->
                    val provider = CGDataProviderCreateWithData(
                        info = null,
                        data = pinned.addressOf(0),
                        size = requiredByteLen.convert(),
                        releaseData = null
                    )
                    if (provider != null) {
                        try {
                            val cgImg = CGImageCreate(
                                width = surfW.convert(),
                                height = surfH.convert(),
                                bitsPerComponent = 8u,
                                bitsPerPixel = 32u,
                                bytesPerRow = rowBytes.convert(),
                                space = cs,
                                bitmapInfo = CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value,
                                provider = provider,
                                decode = null,
                                shouldInterpolate = true,
                                intent = CGColorRenderingIntent.kCGRenderingIntentDefault
                            )
                            if (cgImg != null) {
                                try {
                                    drawCgImageRightSideUp(
                                        ctx.cgContext,
                                        cgImg,
                                        dstRect.x,
                                        dstRect.y,
                                        dstRect.width,
                                        dstRect.height,
                                        antialiasing = true,
                                        fastBilinear = true
                                    )
                                } finally {
                                    CGImageRelease(cgImg)
                                }
                            }
                        } finally {
                            CGDataProviderRelease(provider)
                        }
                    }
                }
            }
        }

        // 4. Optional active subtitle/caption overlay pill
        val caption = spec.activeCaptionText()?.trim()?.takeIf { it.isNotEmpty() }
        if (caption != null && dstRect.width >= 80 && dstRect.height >= 48) {
            val maxBoxW = (dstRect.width - 48).coerceAtLeast(64)
            val measured = measureText(caption, fontSizePx = 15f, weightNumeric = 500, maxWidth = maxBoxW, singleLine = false)
            val pillW = (measured.width + 28).coerceAtMost(maxBoxW)
            val pillH = (measured.height + 14).coerceAtMost(dstRect.height / 2)
            val pillX = dstRect.x + (dstRect.width - pillW) / 2
            val pillY = dstRect.y + dstRect.height - pillH - 36
            fillRoundedRect(ctx, pillX, pillY, pillW, pillH, 8f, RgbaColor(0, 0, 0, 196))
            drawText(
                ctx = ctx,
                text = caption,
                x = pillX + 14,
                y = pillY + 4,
                w = pillW - 28,
                h = pillH - 8,
                color = RgbaColor.WHITE,
                fontSizePx = 15f,
                weightNumeric = 500,
                alignment = TextAlignment.CENTER,
                singleLine = false
            )
        }
    }

    private fun drawCgImageRightSideUp(
        cgContext: CGContextRef,
        cgImage: CGImageRef,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
        antialiasing: Boolean,
        fastBilinear: Boolean = false
    ) {
        if (w <= 0 || h <= 0) return
        CGContextSaveGState(cgContext)
        CGContextSetInterpolationQuality(
            cgContext,
            when {
                !antialiasing -> platform.CoreGraphics.kCGInterpolationNone
                fastBilinear -> platform.CoreGraphics.kCGInterpolationLow
                else -> platform.CoreGraphics.kCGInterpolationMedium
            }
        )
        // Locally invert Y inside flipped NSView so row 0 of cgImage draws at top (x, y)
        CGContextTranslateCTM(cgContext, x.toDouble(), (y + h).toDouble())
        CGContextScaleCTM(cgContext, 1.0, -1.0)
        CGContextDrawImage(cgContext, CGRectMake(0.0, 0.0, w.toDouble(), h.toDouble()), cgImage)
        CGContextRestoreGState(cgContext)
    }

    private fun computeObjectFitRect(
        srcW: Int,
        srcH: Int,
        dstX: Int,
        dstY: Int,
        dstW: Int,
        dstH: Int,
        fit: ObjectFit
    ): Rect {
        if (fit == ObjectFit.FILL || srcW <= 0 || srcH <= 0) {
            return Rect(dstX, dstY, dstW, dstH)
        }
        val scaleX = dstW.toFloat() / srcW.toFloat()
        val scaleY = dstH.toFloat() / srcH.toFloat()
        val scale = when (fit) {
            ObjectFit.COVER -> max(scaleX, scaleY)
            ObjectFit.SCALE_DOWN -> min(1f, min(scaleX, scaleY))
            ObjectFit.NONE -> 1f
            else -> min(scaleX, scaleY)
        }
        val outW = (srcW * scale).roundToInt().coerceAtLeast(1)
        val outH = (srcH * scale).roundToInt().coerceAtLeast(1)
        val outX = dstX + (dstW - outW) / 2
        val outY = dstY + (dstH - outH) / 2
        return Rect(outX, outY, outW, outH)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeDrawableResource(
        drawable: com.nexustools.genericui.res.DrawableResource,
        targetW: Int,
        targetH: Int
    ): ImageBuffer? {
        val svgSrc = drawable.svgSource
        if (!svgSrc.isNullOrBlank()) {
            MacosSvgCoreGraphicsRasterizer.rasterizeSvgText(
                svg = svgSrc,
                targetWidth = targetW,
                targetHeight = targetH
            )?.let { return it }
        }
        val b64 = drawable.base64Data
        if (!b64.isNullOrBlank()) {
            val rawBytes = runCatching { Base64.decode(b64.trim()) }.getOrNull()
            if (rawBytes != null && rawBytes.isNotEmpty()) {
                ImageLoader.activeNativeCodec?.decode(
                    ImageSource.fromBytes(rawBytes),
                    ImageDecodeOptions(targetWidth = targetW, targetHeight = targetH, antialiasing = true)
                )?.let { return it }
            }
        }
        val rawResBytes = Resources.getBytes(drawable.resId)
        if (rawResBytes != null && rawResBytes.isNotEmpty()) {
            ImageLoader.activeNativeCodec?.decode(
                ImageSource.fromBytes(rawResBytes),
                ImageDecodeOptions(targetWidth = targetW, targetHeight = targetH, antialiasing = true)
            )?.let { return it }
        }
        return null
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeDataUrl(dataUrl: String, targetW: Int, targetH: Int): ImageBuffer? {
        val comma = dataUrl.indexOf(',')
        if (comma <= 0) return null
        val meta = dataUrl.substring(0, comma).lowercase()
        val payload = dataUrl.substring(comma + 1)
        val bytes = if (meta.contains(";base64")) {
            runCatching { Base64.decode(payload.trim()) }.getOrNull() ?: return null
        } else {
            percentDecodeUtf8(payload).encodeToByteArray()
        }
        if (meta.contains("svg") || MacosSvgCoreGraphicsRasterizer.isSvgBytes(bytes)) {
            return MacosSvgCoreGraphicsRasterizer.rasterize(bytes, targetW, targetH)
        }
        return ImageLoader.activeNativeCodec?.decode(
            ImageSource.fromBytes(bytes),
            ImageDecodeOptions(targetWidth = targetW, targetHeight = targetH, antialiasing = true)
        )
    }

    private fun percentDecodeUtf8(encoded: String): String {
        val out = ByteArray(encoded.length)
        var outIdx = 0
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            if (c == '%' && i + 2 < encoded.length) {
                val hi = encoded[i + 1].digitToIntOrNull(16)
                val lo = encoded[i + 2].digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    out[outIdx++] = ((hi shl 4) or lo).toByte()
                    i += 3
                    continue
                }
            } else if (c == '+') {
                out[outIdx++] = ' '.code.toByte()
                i++
                continue
            }
            out[outIdx++] = c.code.toByte()
            i++
        }
        return out.decodeToString(0, outIdx, throwOnInvalidSequence = false)
    }

    private fun createRoundedRectPath(
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        r: Double
    ): platform.CoreGraphics.CGMutablePathRef? {
        val path = CGPathCreateMutable() ?: return null
        if (r <= 0.5) {
            CGPathAddRect(path, null, CGRectMake(x, y, w, h))
            return path
        }
        val k = 0.5522847498307936 * r
        CGPathMoveToPoint(path, null, x + r, y)
        CGPathAddLineToPoint(path, null, x + w - r, y)
        CGPathAddCurveToPoint(path, null, x + w - r + k, y, x + w, y + r - k, x + w, y + r)
        CGPathAddLineToPoint(path, null, x + w, y + h - r)
        CGPathAddCurveToPoint(path, null, x + w, y + h - r + k, x + w - r + k, y + h, x + w - r, y + h)
        CGPathAddLineToPoint(path, null, x + r, y + h)
        CGPathAddCurveToPoint(path, null, x + r - k, y + h, x, y + h - r + k, x, y + h - r)
        CGPathAddLineToPoint(path, null, x, y + r)
        CGPathAddCurveToPoint(path, null, x, y + r - k, x + r - k, y, x + r, y)
        CGPathCloseSubpath(path)
        return path
    }
}
