@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.apple.AppleImageIoCodecNativeImpl
import com.nexustools.genericui.apple.AppleSvgCoreGraphicsRasterizer
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.res.DrawableResource
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.resources.ResourceID
import com.nexustools.genericui.resources.ResourceResolver
import com.nexustools.genericui.resources.ResourceType
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.mapper.BootstrapStyleSnapshot
import com.nexustools.genericui.threading.PlatformReentrantLock
import com.nexustools.genericui.threading.withLock
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import platform.AppKit.NSApplication
import platform.AppKit.NSColor
import platform.AppKit.NSFont
import platform.AppKit.NSFontAttributeName
import platform.AppKit.NSFontManager
import platform.AppKit.NSFontWeightBold
import platform.AppKit.NSFontWeightLight
import platform.AppKit.NSFontWeightMedium
import platform.AppKit.NSFontWeightRegular
import platform.AppKit.NSFontWeightSemibold
import platform.AppKit.NSFontWeightThin
import platform.AppKit.NSForegroundColorAttributeName
import platform.AppKit.NSImage
import platform.AppKit.NSItalicFontMask
import platform.AppKit.NSKernAttributeName
import platform.AppKit.NSMutableParagraphStyle
import platform.AppKit.NSParagraphStyleAttributeName
import platform.AppKit.NSStrikethroughStyleAttributeName
import platform.AppKit.NSTextAlignment
import platform.AppKit.NSTextAlignmentCenter
import platform.AppKit.NSTextAlignmentLeft
import platform.AppKit.NSTextAlignmentRight
import platform.AppKit.NSUnderlineStyleAttributeName
import platform.AppKit.NSUnderlineStyleSingle
import platform.AppKit.appearance
import platform.AppKit.effectiveAppearance
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGBitmapContextGetData
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGImageRetain
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.Foundation.NSAttributedString
import platform.Foundation.NSData
import platform.Foundation.NSMakeRange
import platform.Foundation.NSMutableAttributedString
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.numberWithDouble
import platform.Foundation.numberWithInteger
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.roundToInt

internal object MacosAppKitAppearance {

    private data class DecodedBufferKey(
        val source: String,
        val desiredWidth: Int,
        val desiredHeight: Int,
        val tableId: Int
    )

    private data class NsImageCacheKey(
        val spec: String,
        val desiredPx: Int,
        val tintColor: RgbaColor?,
        val templateIfMonochrome: Boolean,
        val tableId: Int
    )

    private class CachedNsImageEntry(
        val image: NSImage,
        val sourceBuffer: ImageBuffer?
    )

    private class CgImageCacheKey(
        val buffer: ImageBuffer,
        val tintColor: RgbaColor?
    ) {
        override fun equals(other: Any?): Boolean =
            other is CgImageCacheKey && buffer === other.buffer && tintColor == other.tintColor

        override fun hashCode(): Int =
            ((buffer.width * 31 + buffer.height) * 31 + buffer.pixelFormat.ordinal) * 31 + (tintColor?.hashCode() ?: 0)
    }

    private class ImmutableBufferRef(val buffer: ImageBuffer) {
        override fun equals(other: Any?): Boolean = other is ImmutableBufferRef && buffer === other.buffer
        override fun hashCode(): Int = (buffer.width * 31 + buffer.height) * 31 + buffer.bytes.size
    }

    private const val MAX_DECODED_BUFFER_CACHE_SIZE = 256
    private const val MAX_NS_IMAGE_CACHE_SIZE = 512
    private const val MAX_CG_IMAGE_CACHE_SIZE = 256

    private val imageCodec by lazy { AppleImageIoCodecNativeImpl() }
    private val cacheLock = PlatformReentrantLock()
    private val decodedBufferCache = LinkedHashMap<DecodedBufferKey, ImageBuffer>()
    private val immutableDecodedBuffers = HashSet<ImmutableBufferRef>()
    private val nsImageCache = LinkedHashMap<NsImageCacheKey, CachedNsImageEntry>()
    private val cgImageCache = LinkedHashMap<CgImageCacheKey, CGImageRef>()

    fun isDarkTheme(widget: DesktopNativeWidget? = null): Boolean {
        var cur = widget
        while (cur != null) {
            val attrTheme = cur.attributes["darkTheme"] as? Boolean
            if (attrTheme != null) return attrTheme
            val bsTheme = (cur.attributes["data-bs-theme"] as? String)?.trim()?.lowercase()
            if (bsTheme == "dark") return true
            if (bsTheme == "light") return false
            cur = cur.parentWidget
        }
        val sharedApp = NSApplication.sharedApplication
        val appearanceName = sharedApp.appearance()?.name ?: sharedApp.effectiveAppearance.name ?: return true
        return appearanceName.contains("Dark", ignoreCase = true)
    }

    fun toNsColor(color: RgbaColor): NSColor =
        NSColor.colorWithRed(
            red = color.r / 255.0,
            green = color.g / 255.0,
            blue = color.b / 255.0,
            alpha = color.a / 255.0
        )

    fun toRgbaColor(color: NSColor): RgbaColor {
        val srgb = color.colorUsingColorSpace(platform.AppKit.NSColorSpace.sRGBColorSpace) ?: color
        val r = (srgb.redComponent * 255.0).roundToInt().coerceIn(0, 255)
        val g = (srgb.greenComponent * 255.0).roundToInt().coerceIn(0, 255)
        val b = (srgb.blueComponent * 255.0).roundToInt().coerceIn(0, 255)
        val a = (srgb.alphaComponent * 255.0).roundToInt().coerceIn(0, 255)
        return RgbaColor(r, g, b, a)
    }

    fun resolveFont(
        style: ComputedStyle?,
        snapshot: BootstrapStyleSnapshot? = null,
        defaultSizePt: Double = 13.0,
        defaultBold: Boolean = false
    ): NSFont {
        val px = style?.fontSizePx?.toDouble()
            ?: when (snapshot?.size) {
                com.nexustools.genericui.enums.bootstrap.BootstrapSize.SM -> 12.0
                com.nexustools.genericui.enums.bootstrap.BootstrapSize.LG -> 16.0
                else -> defaultSizePt
            }
        val numericWeight = style?.fontWeightNumeric
        val bold = defaultBold || (numericWeight != null && numericWeight >= 600)
        val weightFloat = when {
            numericWeight != null && numericWeight >= 700 -> NSFontWeightBold
            numericWeight != null && numericWeight >= 600 -> NSFontWeightSemibold
            numericWeight != null && numericWeight >= 500 -> NSFontWeightMedium
            numericWeight != null && numericWeight <= 200 -> NSFontWeightThin
            numericWeight != null && numericWeight <= 300 -> NSFontWeightLight
            bold -> NSFontWeightSemibold
            else -> NSFontWeightRegular
        }
        val familyName = style?.fontFamily?.trim()?.split(',')?.firstOrNull()?.trim()?.trim('\'', '"')
        var font = if (!familyName.isNullOrEmpty() &&
            !familyName.equals("system-ui", ignoreCase = true) &&
            !familyName.equals("sans-serif", ignoreCase = true) &&
            !familyName.equals("-apple-system", ignoreCase = true)
        ) {
            if (familyName.equals("monospace", ignoreCase = true) || familyName.equals("ui-monospace", ignoreCase = true)) {
                NSFont.monospacedSystemFontOfSize(px, weight = weightFloat)
            } else {
                NSFont.fontWithName(familyName, size = px) ?: NSFont.systemFontOfSize(px, weight = weightFloat)
            }
        } else {
            NSFont.systemFontOfSize(px, weight = weightFloat)
        }
        if (style?.fontStyle?.cssValue?.equals("italic", ignoreCase = true) == true) {
            font = NSFontManager.sharedFontManager().convertFont(font, toHaveTrait = NSItalicFontMask)
        }
        return font
    }

    fun resolveAlignment(style: ComputedStyle?, defaultAlignment: NSTextAlignment = NSTextAlignmentLeft): NSTextAlignment {
        val align = style?.textAlign?.lowercase() ?: return defaultAlignment
        return when (align) {
            "center" -> NSTextAlignmentCenter
            "right", "end" -> NSTextAlignmentRight
            else -> NSTextAlignmentLeft
        }
    }

    fun buildAttributedString(
        text: String,
        style: ComputedStyle?,
        textColor: RgbaColor?,
        defaultFont: NSFont,
        alignment: NSTextAlignment = NSTextAlignmentLeft,
        underline: Boolean = false,
        strikethrough: Boolean = false,
        applyLineHeight: Boolean = true
    ): NSAttributedString {
        val attrs = mutableMapOf<Any?, Any?>()
        attrs[NSFontAttributeName] = defaultFont
        if (textColor != null) {
            attrs[NSForegroundColorAttributeName] = toNsColor(textColor)
        }
        val deco = style?.textDecoration?.cssValue?.lowercase().orEmpty()
        if (underline || "underline" in deco) {
            attrs[NSUnderlineStyleAttributeName] = NSNumber.numberWithInteger(NSUnderlineStyleSingle.toLong())
        }
        if (strikethrough || "line-through" in deco) {
            attrs[NSStrikethroughStyleAttributeName] = NSNumber.numberWithInteger(NSUnderlineStyleSingle.toLong())
        }
        val fontSizePx = style?.fontSizePx ?: defaultFont.pointSize.toFloat()
        val pStyle = NSMutableParagraphStyle()
        pStyle.setAlignment(alignment)
        if (applyLineHeight) {
            style?.lineHeight?.resolvePx(parentFontSizePx = fontSizePx)?.let { lh ->
                if (lh > 0f) {
                    pStyle.setMinimumLineHeight(lh.toDouble())
                    pStyle.setMaximumLineHeight(lh.toDouble())
                }
            }
        }
        attrs[NSParagraphStyleAttributeName] = pStyle
        val attrString = NSMutableAttributedString.create(string = text, attributes = attrs)
        val kernPx = style?.letterSpacing?.resolvePx(parentFontSizePx = fontSizePx)
        if (kernPx != null && kernPx != 0f && text.length > 1) {
            attrString.setAttributes(
                attrs + (NSKernAttributeName to NSNumber.numberWithDouble(kernPx.toDouble())),
                range = NSMakeRange(0uL, (text.length - 1).toULong())
            )
        }
        return attrString
    }

    /**
     * Converts any supported [ImageBuffer] into a retained `+1` CoreGraphics [CGImageRef]
     * using premultiplied big-endian RGBA (`kCGBitmapByteOrder32Big`). Caller must release
     * the returned image via `CGImageRelease`.
     */
    fun createCgImageFromBuffer(
        buffer: ImageBuffer,
        tintColor: RgbaColor? = null
    ): CGImageRef? {
        val w = buffer.width
        val h = buffer.height
        if (w <= 0 || h <= 0) return null

        val isImmutable = cacheLock.withLock { ImmutableBufferRef(buffer) in immutableDecodedBuffers }
        val cgKey = if (isImmutable) CgImageCacheKey(buffer, tintColor) else null
        if (cgKey != null) {
            cacheLock.withLock {
                val cached = cgImageCache[cgKey]
                if (cached != null) {
                    cgImageCache.remove(cgKey)
                    cgImageCache[cgKey] = cached
                    return CGImageRetain(cached)
                }
            }
        }

        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
        val bitmapInfo = kCGBitmapByteOrder32Big or CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
        val ctx = CGBitmapContextCreate(
            data = null,
            width = w.toULong(),
            height = h.toULong(),
            bitsPerComponent = 8uL,
            bytesPerRow = (w * 4).toULong(),
            space = colorSpace,
            bitmapInfo = bitmapInfo
        )
        CGColorSpaceRelease(colorSpace)
        ctx ?: return null

        val created = try {
            val rawPtr = CGBitmapContextGetData(ctx)?.reinterpret<ByteVar>() ?: return null
            writeBufferAsPremulRgba(buffer, rawPtr, tintColor)
            CGBitmapContextCreateImage(ctx)
        } finally {
            CGContextRelease(ctx)
        }
        if (created != null && cgKey != null) {
            cacheLock.withLock {
                val evicted = if (cgImageCache.size >= MAX_CG_IMAGE_CACHE_SIZE) {
                    val firstKey = cgImageCache.keys.firstOrNull()
                    if (firstKey != null) cgImageCache.remove(firstKey) else null
                } else {
                    null
                }
                cgImageCache.remove(cgKey)?.let { CGImageRelease(it) }
                cgImageCache[cgKey] = CGImageRetain(created)!!
                if (evicted != null) CGImageRelease(evicted)
            }
        }
        return created
    }

    private fun isLikelyMonochromeBuffer(buffer: ImageBuffer): Boolean {
        if (buffer.pixelFormat == PixelFormat.GRAY_8 || buffer.pixelFormat == PixelFormat.GRAY_ALPHA_88) {
            return true
        }
        if (buffer.pixelFormat != PixelFormat.RGBA_8888 && buffer.pixelFormat != PixelFormat.BGRA_8888) {
            return false
        }
        val bytes = buffer.bytes
        val pxCount = buffer.width * buffer.height
        val step = maxOf(1, pxCount / 256)
        var opaqueSamples = 0
        var refR = -1
        var refG = -1
        var refB = -1
        var uniformOrGrayCount = 0
        var i = 0
        while (i < pxCount) {
            val o = i * 4
            if (o + 3 >= bytes.size) break
            val a = bytes[o + 3].toInt() and 0xFF
            if (a >= 16) {
                opaqueSamples++
                val c0 = bytes[o].toInt() and 0xFF
                val c1 = bytes[o + 1].toInt() and 0xFF
                val c2 = bytes[o + 2].toInt() and 0xFF
                if (refR < 0) {
                    refR = c0; refG = c1; refB = c2
                }
                val isGray = maxOf(abs(c0 - c1), abs(c1 - c2), abs(c0 - c2)) <= 20
                val matchesRefHue = maxOf(abs(c0 - refR), abs(c1 - refG), abs(c2 - refB)) <= 28
                if (isGray || matchesRefHue) {
                    uniformOrGrayCount++
                }
            }
            i += step
        }
        return opaqueSamples > 0 && uniformOrGrayCount * 10 >= opaqueSamples * 9
    }

    private fun writeBufferAsPremulRgba(
        buffer: ImageBuffer,
        dst: kotlinx.cinterop.CPointer<ByteVar>,
        tintColor: RgbaColor?
    ) {
        val src = buffer.bytes
        val pxCount = buffer.width * buffer.height
        val tintR = tintColor?.r ?: 0
        val tintG = tintColor?.g ?: 0
        val tintB = tintColor?.b ?: 0
        val tintA = tintColor?.a ?: 255

        for (i in 0 until pxCount) {
            var r = 0
            var g = 0
            var b = 0
            var a = 255
            when (buffer.pixelFormat) {
                PixelFormat.RGBA_8888 -> {
                    val o = i * 4
                    if (o + 3 < src.size) {
                        r = src[o].toInt() and 0xFF
                        g = src[o + 1].toInt() and 0xFF
                        b = src[o + 2].toInt() and 0xFF
                        a = src[o + 3].toInt() and 0xFF
                    }
                }
                PixelFormat.BGRA_8888 -> {
                    val o = i * 4
                    if (o + 3 < src.size) {
                        b = src[o].toInt() and 0xFF
                        g = src[o + 1].toInt() and 0xFF
                        r = src[o + 2].toInt() and 0xFF
                        a = src[o + 3].toInt() and 0xFF
                    }
                }
                PixelFormat.RGB_888 -> {
                    val o = i * 3
                    if (o + 2 < src.size) {
                        r = src[o].toInt() and 0xFF
                        g = src[o + 1].toInt() and 0xFF
                        b = src[o + 2].toInt() and 0xFF
                    }
                }
                PixelFormat.GRAY_8 -> {
                    if (i < src.size) {
                        val v = src[i].toInt() and 0xFF
                        r = v; g = v; b = v
                    }
                }
                PixelFormat.GRAY_ALPHA_88 -> {
                    val o = i * 2
                    if (o + 1 < src.size) {
                        val v = src[o].toInt() and 0xFF
                        r = v; g = v; b = v
                        a = src[o + 1].toInt() and 0xFF
                    }
                }
                PixelFormat.RGBA_F16, PixelFormat.RGBA_F32 -> {
                    // Fallback black transparent when non-8bit buffer reaches CG raster
                    r = 0; g = 0; b = 0; a = 0
                }
            }
            if (tintColor != null) {
                r = tintR
                g = tintG
                b = tintB
                a = (a * tintA + 127) / 255
            }
            val pr = if (a == 255) r else (r * a + 127) / 255
            val pg = if (a == 255) g else (g * a + 127) / 255
            val pb = if (a == 255) b else (b * a + 127) / 255
            val dstOff = i * 4
            dst[dstOff] = pr.toByte()
            dst[dstOff + 1] = pg.toByte()
            dst[dstOff + 2] = pb.toByte()
            dst[dstOff + 3] = a.toByte()
        }
    }

    fun resolveNsImage(
        spec: String?,
        desiredPx: Int = 20,
        tintColor: RgbaColor? = null,
        templateIfMonochrome: Boolean = false
    ): NSImage? {
        if (spec.isNullOrBlank()) return null
        val trimmed = spec.trim()
        val isMemory = trimmed.startsWith("memory://", ignoreCase = true)
        val tableId = if (isMemory) 0 else Resources.activeTable.hashCode()
        val cacheKey = NsImageCacheKey(
            spec = trimmed,
            desiredPx = desiredPx,
            tintColor = tintColor,
            templateIfMonochrome = templateIfMonochrome,
            tableId = tableId
        )

        val memBuf = if (isMemory) ImageLoader.resolveMemoryBuffer(trimmed) else null
        cacheLock.withLock {
            val cached = nsImageCache[cacheKey]
            if (cached != null && (!isMemory || cached.sourceBuffer === memBuf)) {
                nsImageCache.remove(cacheKey)
                nsImageCache[cacheKey] = cached
                return cached.image
            }
        }

        val buffer = if (isMemory) memBuf else decodeSourceToImageBuffer(trimmed, desiredPx, desiredPx)
        var resolvedImg: NSImage? = null
        if (buffer != null) {
            val cgImage = createCgImageFromBuffer(buffer, tintColor = tintColor)
            if (cgImage != null) {
                try {
                    val img = NSImage(cGImage = cgImage, size = CGSizeMake(desiredPx.toDouble(), desiredPx.toDouble()))
                    if (templateIfMonochrome && tintColor == null && isLikelyMonochromeBuffer(buffer)) {
                        img.setTemplate(true)
                    }
                    resolvedImg = img
                } finally {
                    CGImageRelease(cgImage)
                }
            }
        }
        if (resolvedImg == null) {
            val sfName = trimmed.removePrefix("sf://").removePrefix("symbol://")
            if (!trimmed.contains('/') && !trimmed.contains('<')) {
                val sfImg = NSImage.imageWithSystemSymbolName(sfName, accessibilityDescription = null)
                if (sfImg != null) {
                    if (templateIfMonochrome) sfImg.setTemplate(true)
                    resolvedImg = sfImg
                }
            }
        }
        if (resolvedImg != null) {
            cacheLock.withLock {
                nsImageCache.remove(cacheKey)
                nsImageCache[cacheKey] = CachedNsImageEntry(resolvedImg, if (isMemory) buffer else null)
                if (nsImageCache.size > MAX_NS_IMAGE_CACHE_SIZE) {
                    nsImageCache.keys.firstOrNull()?.let { nsImageCache.remove(it) }
                }
            }
        }
        return resolvedImg
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun decodeSourceToImageBuffer(
        source: String?,
        desiredWidth: Int? = null,
        desiredHeight: Int? = null
    ): ImageBuffer? {
        if (source.isNullOrBlank()) return null
        val trimmed = source.trim()
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return null
        }
        if (trimmed.startsWith("memory://", ignoreCase = true)) {
            return ImageLoader.resolveMemoryBuffer(trimmed)
        }

        val normW = desiredWidth?.takeIf { it > 0 } ?: 0
        val normH = desiredHeight?.takeIf { it > 0 } ?: 0
        val cacheKey = DecodedBufferKey(
            source = trimmed,
            desiredWidth = normW,
            desiredHeight = normH,
            tableId = Resources.activeTable.hashCode()
        )
        cacheLock.withLock {
            val cached = decodedBufferCache[cacheKey]
            if (cached != null) {
                decodedBufferCache.remove(cacheKey)
                decodedBufferCache[cacheKey] = cached
                return cached
            }
        }

        val options = ImageDecodeOptions(
            targetWidth = normW.takeIf { it > 0 },
            targetHeight = normH.takeIf { it > 0 },
            preferredPixelFormat = PixelFormat.RGBA_8888
        )

        val decoded: ImageBuffer? = when {
            trimmed.startsWith("<svg", ignoreCase = true) || trimmed.startsWith("<?xml", ignoreCase = true) -> {
                AppleSvgCoreGraphicsRasterizer.rasterize(trimmed.encodeToByteArray(), options)
            }
            trimmed.startsWith("data:", ignoreCase = true) -> {
                val comma = trimmed.indexOf(',')
                if (comma > 0) {
                    val header = trimmed.substring(0, comma).lowercase()
                    val payload = trimmed.substring(comma + 1)
                    val bytes = if (";base64" in header) {
                        try {
                            Base64.decode(payload)
                        } catch (_: IllegalArgumentException) {
                            return null
                        }
                    } else {
                        decodePercentEncoded(payload).encodeToByteArray()
                    }
                    if ("svg" in header) {
                        AppleSvgCoreGraphicsRasterizer.rasterize(bytes, options)
                    } else {
                        decodeImageBytes(bytes, options)
                    }
                } else {
                    null
                }
            }
            else -> {
                val bytes = resolveResourceOrFileBytes(trimmed) ?: return null
                if (AppleSvgCoreGraphicsRasterizer.probeDimensions(bytes) != null) {
                    AppleSvgCoreGraphicsRasterizer.rasterize(bytes, options)
                } else {
                    decodeImageBytes(bytes, options)
                }
            }
        }
        if (decoded != null) {
            cacheLock.withLock {
                immutableDecodedBuffers.add(ImmutableBufferRef(decoded))
                decodedBufferCache.remove(cacheKey)
                decodedBufferCache[cacheKey] = decoded
                if (decodedBufferCache.size > MAX_DECODED_BUFFER_CACHE_SIZE) {
                    val removedKey = decodedBufferCache.keys.firstOrNull()
                    val removedBuf = removedKey?.let { decodedBufferCache.remove(it) }
                    if (removedBuf != null && decodedBufferCache.values.none { it === removedBuf }) {
                        immutableDecodedBuffers.remove(ImmutableBufferRef(removedBuf))
                    }
                }
            }
        }
        return decoded
    }

    fun decodeDrawableToBuffer(drawable: DrawableResource): ImageBuffer? {
        val decodedOrSvg = if (!drawable.base64Data.isNullOrBlank()) {
            try {
                Base64.decode(drawable.base64Data!!.trim())
            } catch (_: IllegalArgumentException) {
                null
            }
        } else if (!drawable.svgSource.isNullOrBlank()) {
            drawable.svgSource!!.encodeToByteArray()
        } else {
            null
        }
        val rawBytes = decodedOrSvg
            ?: resolveResourceOrFileBytes(drawable.toIconUri())
            ?: return null

        val options = ImageDecodeOptions(preferredPixelFormat = PixelFormat.RGBA_8888)
        if (AppleSvgCoreGraphicsRasterizer.probeDimensions(rawBytes) != null) {
            return AppleSvgCoreGraphicsRasterizer.rasterize(rawBytes, options)
        }
        return decodeImageBytes(rawBytes, options)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun drawableToBytes(drawable: DrawableResource?): ByteArray? {
        drawable ?: return null
        if (!drawable.svgSource.isNullOrBlank()) {
            return drawable.svgSource!!.encodeToByteArray()
        }
        if (!drawable.base64Data.isNullOrBlank()) {
            return try {
                Base64.decode(drawable.base64Data!!.trim())
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return null
    }

    private fun resolveResourceOrFileBytes(spec: String): ByteArray? {
        if (spec.startsWith("res://", ignoreCase = true) || spec.startsWith("drawable://", ignoreCase = true)) {
            val resId = resolveResourceIdFromUri(spec)
            if (resId != null) {
                ResourceResolver.getBytes(resId)?.takeIf { it.isNotEmpty() }?.let { return it }
                Resources.activeTable.getRawBytes(resId)?.takeIf { it.isNotEmpty() }?.let { return it }
                drawableToBytes(Resources.getDrawable(resId) ?: Resources.activeTable.getDrawable(resId))
                    ?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            drawableToBytes(Resources.getDrawable(spec))?.takeIf { it.isNotEmpty() }?.let { return it }
        } else {
            Resources.activeTable.findResourceId(ResourceType.DRAWABLE, spec)?.let { resId ->
                ResourceResolver.getBytes(resId)?.takeIf { it.isNotEmpty() }?.let { return it }
                Resources.activeTable.getRawBytes(resId)?.takeIf { it.isNotEmpty() }?.let { return it }
                drawableToBytes(Resources.getDrawable(resId) ?: Resources.activeTable.getDrawable(resId))
                    ?.takeIf { it.isNotEmpty() }?.let { return it }
            }
            drawableToBytes(Resources.getDrawable(spec))?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        val cleanPath = if (spec.startsWith("file://", ignoreCase = true)) spec.removePrefix("file://") else spec
        NSData.dataWithContentsOfFile(cleanPath)?.let { nsData ->
            val len = nsData.length.toInt()
            val ptr = nsData.bytes
            if (len > 0 && ptr != null) {
                val out = ByteArray(len)
                out.usePinned { pinned ->
                    platform.posix.memcpy(pinned.addressOf(0), ptr, nsData.length)
                }
                return out
            }
        }
        return null
    }

    private fun resolveResourceIdFromUri(uri: String): ResourceID? {
        val clean = uri.trim()
        Resources.activeTable.findResourceId(ResourceType.DRAWABLE, clean)?.let { return it }
        val normalizedRel = clean
            .removePrefix("res://")
            .removePrefix("drawable://")
            .removePrefix("res/")
            .removePrefix("assets/drawable/")
            .removePrefix("drawable/")
            .trimStart('/')
        if (normalizedRel.isNotEmpty()) {
            Resources.activeTable.allDrawables().firstOrNull { d ->
                val rel = d.resId.relativePath.replace('\\', '/').trimStart('/')
                rel.equals(normalizedRel, ignoreCase = true) ||
                    rel.substringAfterLast('/').equals(normalizedRel.substringAfterLast('/'), ignoreCase = true)
            }?.let { return it.resId }
        }
        return null
    }

    private fun decodeImageBytes(bytes: ByteArray, options: ImageDecodeOptions): ImageBuffer? {
        return imageCodec.decode(ImageSource.fromBytes(bytes), options)
    }

    private fun decodePercentEncoded(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '%' && i + 2 < text.length) {
                val hex = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    sb.append(hex.toChar())
                    i += 3
                    continue
                }
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }
}
