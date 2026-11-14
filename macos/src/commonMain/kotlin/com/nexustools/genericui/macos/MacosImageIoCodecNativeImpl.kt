@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.image.DesktopLibJpegImageCodecImpl
import com.nexustools.genericui.image.AnimatedImage
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageColorSpace
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageEncodeOptions
import com.nexustools.genericui.image.ImageFormat
import com.nexustools.genericui.image.ImageFrame
import com.nexustools.genericui.image.ImageMetadata
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.image.cache.ImageCacheManager
import com.nexustools.genericui.image.codec.LibJpegCodec
import com.nexustools.genericui.image.codec.LibWebpCodec
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataCreateMutable
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetAllowsAntialiasing
import platform.CoreGraphics.CGContextSetInterpolationQuality
import platform.CoreGraphics.CGContextSetShouldAntialias
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGInterpolationQuality
import platform.CoreGraphics.CGRectMake
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithData
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.CGImageSourceCreateImageAtIndex
import platform.ImageIO.CGImageSourceCreateWithData
import platform.ImageIO.CGImageSourceGetCount
import platform.posix.memcpy
import kotlin.math.roundToInt

/**
 * macOS native image loader & codec implementation backed by Apple `ImageIO`
 * (`CGImageSourceCreateWithData`, `CGImageSourceCreateImageAtIndex`, `CGImageDestinationCreateWithData`)
 * and CoreGraphics vector SVG rasterization ([MacosSvgCoreGraphicsRasterizer]), alongside
 * `libjpeg-turbo`, `libpng`, and `libwebp` scanline hooks.
 */
class MacosImageIoCodecNativeImpl : DesktopLibJpegImageCodecImpl(
    codecBackendName = "macOS ImageIO (CGImageSource / CGImageDestination) + CoreGraphics SVG + libjpeg-turbo/libpng/libwebp"
) {

    private val asyncHttpFetcher by lazy { MacosHttpClientNativeImpl() }

    init {
        installMacosImageIoHooks()
    }

    private fun installMacosImageIoHooks() {
        if (ImageCacheManager.asyncUrlFetcherHook == null) {
            ImageCacheManager.asyncUrlFetcherHook = { fetchUrl ->
                val resp = asyncHttpFetcher.execute(
                    com.nexustools.genericui.net.http.HttpRequest(
                        url = fetchUrl,
                        method = com.nexustools.genericui.net.http.HttpMethod.GET,
                        timeoutMs = 15_000L
                    )
                )
                if (resp.statusCode in 200..299 && resp.bodyBytes.isNotEmpty()) {
                    resp.bodyBytes
                } else {
                    null
                }
            }
        }
        LibJpegCodec.nativeLibJpegDecodeHook = { jpegBytes ->
            decodeRasterViaImageIO(jpegBytes, ImageDecodeOptions()) ?: LibJpegCodec.decodePureKotlin(jpegBytes)
        }
        LibJpegCodec.nativeLibJpegEncodeHook = { buffer, quality ->
            encodeViaImageIO(buffer, "public.jpeg", quality)
        }
        LibWebpCodec.nativeLibWebpDecodeHook = { webpBytes ->
            decodeRasterViaImageIO(webpBytes, ImageDecodeOptions()) ?: run {
                val meta = LibWebpCodec.probeWebpMetadata(webpBytes)
                ImageBuffer(meta.width, meta.height, PixelFormat.RGBA_8888, ImageColorSpace.SRGB)
            }
        }
    }

    override fun decodeJpegScanlines(jpegBytes: ByteArray): ImageBuffer =
        decodeRasterViaImageIO(jpegBytes, ImageDecodeOptions()) ?: super.decodeJpegScanlines(jpegBytes)

    override fun encodeJpegScanlines(buffer: ImageBuffer, quality: Int): ByteArray? =
        encodeViaImageIO(buffer, "public.jpeg", quality)

    override fun probeMetadata(source: ImageSource): ImageMetadata? {
        source.memoryBuffer?.let { buf ->
            return ImageMetadata(
                width = buf.width,
                height = buf.height,
                format = ImageFormat.RAW_RGBA,
                pixelFormat = buf.pixelFormat,
                colorSpace = buf.colorSpace
            )
        }
        val bytes = resolveSourceBytes(source) ?: return null
        if (bytes.isEmpty()) return null

        val detected = ImageFormat.detectFromHeader(bytes)
        if (detected == ImageFormat.SVG || MacosSvgCoreGraphicsRasterizer.isSvgBytes(bytes)) {
            MacosSvgCoreGraphicsRasterizer.probeMetadata(bytes)?.let { return it }
        }

        probeMetadataViaImageIO(bytes, detected)?.let { return it }
        return super.probeMetadata(source)
    }

    override fun decode(source: ImageSource, options: ImageDecodeOptions): ImageBuffer? {
        source.memoryBuffer?.let { return it.copy() }
        val bytes = resolveSourceBytes(source) ?: return null
        if (bytes.isEmpty()) return null

        val detected = ImageFormat.detectFromHeader(bytes)
        if (detected == ImageFormat.SVG || MacosSvgCoreGraphicsRasterizer.isSvgBytes(bytes)) {
            MacosSvgCoreGraphicsRasterizer.rasterize(
                bytes = bytes,
                targetWidth = options.targetWidth,
                targetHeight = options.targetHeight,
                antialiasing = options.antialiasing
            )?.let { return it }
        }

        decodeRasterViaImageIO(bytes, options)?.let { return it }
        return super.decode(source, options)
    }

    override fun decodeAnimated(source: ImageSource, options: ImageDecodeOptions): AnimatedImage? {
        val bytes = resolveSourceBytes(source)
        if (bytes != null && bytes.isNotEmpty() && !MacosSvgCoreGraphicsRasterizer.isSvgBytes(bytes)) {
            decodeAnimatedViaImageIO(bytes, options)?.let { return it }
        }
        return super.decodeAnimated(source, options)
    }

    override fun encode(buffer: ImageBuffer, options: ImageEncodeOptions): ByteArray? {
        when (options.format) {
            ImageFormat.PNG -> encodeViaImageIO(buffer, "public.png", 100)?.let { return it }
            ImageFormat.JPEG -> encodeViaImageIO(buffer, "public.jpeg", options.quality)?.let { return it }
            ImageFormat.TIFF -> encodeViaImageIO(buffer, "public.tiff", 100)?.let { return it }
            ImageFormat.BMP -> encodeViaImageIO(buffer, "com.microsoft.bmp", 100)?.let { return it }
            else -> Unit
        }
        return super.encode(buffer, options)
    }

    private fun resolveSourceBytes(source: ImageSource): ByteArray? =
        source.encodedBytes ?: source.uriOrPath?.let { ImageCacheManager.fetchAndCacheBytes(it) }

    private fun probeMetadataViaImageIO(bytes: ByteArray, detectedFormat: ImageFormat): ImageMetadata? {
        val cfData = bytes.usePinned { pinned ->
            CFDataCreate(null, pinned.addressOf(0).reinterpret(), bytes.size.convert())
        } ?: return null
        try {
            val imgSource = CGImageSourceCreateWithData(cfData, null) ?: return null
            try {
                val count = CGImageSourceGetCount(imgSource).toInt()
                if (count <= 0) return null
                val cgImg = CGImageSourceCreateImageAtIndex(imgSource, 0u, null) ?: return null
                try {
                    val w = CGImageGetWidth(cgImg).toInt()
                    val h = CGImageGetHeight(cgImg).toInt()
                    if (w <= 0 || h <= 0) return null
                    return ImageMetadata(
                        width = w,
                        height = h,
                        format = detectedFormat,
                        pixelFormat = PixelFormat.RGBA_8888,
                        colorSpace = ImageColorSpace.SRGB,
                        frameCount = count,
                        isAnimated = count > 1,
                        hasAlpha = true
                    )
                } finally {
                    CGImageRelease(cgImg)
                }
            } finally {
                CFRelease(imgSource)
            }
        } finally {
            CFRelease(cfData)
        }
    }

    private fun decodeRasterViaImageIO(
        bytes: ByteArray,
        options: ImageDecodeOptions,
        frameIndex: Int = 0
    ): ImageBuffer? {
        val cfData = bytes.usePinned { pinned ->
            CFDataCreate(null, pinned.addressOf(0).reinterpret(), bytes.size.convert())
        } ?: return null
        try {
            val imgSource = CGImageSourceCreateWithData(cfData, null) ?: return null
            try {
                val count = CGImageSourceGetCount(imgSource).toInt()
                if (count <= 0 || frameIndex !in 0 until count) return null
                val cgImg = CGImageSourceCreateImageAtIndex(imgSource, frameIndex.convert(), null) ?: return null
                try {
                    val srcW = CGImageGetWidth(cgImg).toInt()
                    val srcH = CGImageGetHeight(cgImg).toInt()
                    if (srcW <= 0 || srcH <= 0) return null

                    val aspect = srcW.toDouble() / srcH.toDouble()
                    val (outW, outH) = when {
                        options.targetWidth != null && options.targetHeight != null &&
                            options.targetWidth!! > 0 && options.targetHeight!! > 0 ->
                            options.targetWidth!!.coerceIn(1, 8192) to options.targetHeight!!.coerceIn(1, 8192)
                        options.targetWidth != null && options.targetWidth!! > 0 -> {
                            val w = options.targetWidth!!.coerceIn(1, 8192)
                            w to (w / aspect).roundToInt().coerceIn(1, 8192)
                        }
                        options.targetHeight != null && options.targetHeight!! > 0 -> {
                            val h = options.targetHeight!!.coerceIn(1, 8192)
                            (h * aspect).roundToInt().coerceIn(1, 8192) to h
                        }
                        else -> srcW to srcH
                    }

                    return rasterizeCgImageToBuffer(cgImg, outW, outH, options.antialiasing)
                } finally {
                    CGImageRelease(cgImg)
                }
            } finally {
                CFRelease(imgSource)
            }
        } finally {
            CFRelease(cfData)
        }
    }

    private fun decodeAnimatedViaImageIO(bytes: ByteArray, options: ImageDecodeOptions): AnimatedImage? {
        val cfData = bytes.usePinned { pinned ->
            CFDataCreate(null, pinned.addressOf(0).reinterpret(), bytes.size.convert())
        } ?: return null
        try {
            val imgSource = CGImageSourceCreateWithData(cfData, null) ?: return null
            try {
                val count = CGImageSourceGetCount(imgSource).toInt()
                if (count <= 0) return null
                val frames = ArrayList<ImageFrame>(count)
                for (idx in 0 until count) {
                    val cgImg = CGImageSourceCreateImageAtIndex(imgSource, idx.convert(), null) ?: continue
                    try {
                        val w = CGImageGetWidth(cgImg).toInt().coerceAtLeast(1)
                        val h = CGImageGetHeight(cgImg).toInt().coerceAtLeast(1)
                        val buf = rasterizeCgImageToBuffer(cgImg, w, h, options.antialiasing)
                        if (buf != null) {
                            frames.add(ImageFrame(frameIndex = idx, buffer = buf, durationMs = 80))
                        }
                    } finally {
                        CGImageRelease(cgImg)
                    }
                }
                if (frames.isEmpty()) return null
                return AnimatedImage(
                    width = frames.first().buffer.width,
                    height = frames.first().buffer.height,
                    frames = frames
                )
            } finally {
                CFRelease(imgSource)
            }
        } finally {
            CFRelease(cfData)
        }
    }

    private fun rasterizeCgImageToBuffer(
        cgImg: platform.CoreGraphics.CGImageRef,
        outW: Int,
        outH: Int,
        antialiasing: Boolean
    ): ImageBuffer? {
        val premulBytes = ByteArray(outW * outH * 4)
        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
        try {
            premulBytes.usePinned { pinned ->
                val ctx = CGBitmapContextCreate(
                    data = pinned.addressOf(0),
                    width = outW.convert(),
                    height = outH.convert(),
                    bitsPerComponent = 8u,
                    bytesPerRow = (outW * 4).convert(),
                    space = colorSpace,
                    bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
                ) ?: return null
                try {
                    CGContextSetAllowsAntialiasing(ctx, antialiasing)
                    CGContextSetShouldAntialias(ctx, antialiasing)
                    CGContextSetInterpolationQuality(
                        ctx,
                        if (antialiasing) {
                            platform.CoreGraphics.kCGInterpolationHigh
                        } else {
                            platform.CoreGraphics.kCGInterpolationNone
                        }
                    )
                    CGContextDrawImage(ctx, CGRectMake(0.0, 0.0, outW.toDouble(), outH.toDouble()), cgImg)
                } finally {
                    CGContextRelease(ctx)
                }
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }

        val outBytes = ByteArray(outW * outH * 4)
        var i = 0
        val total = outBytes.size
        while (i < total) {
            val a = premulBytes[i + 3].toInt() and 0xFF
            if (a == 0) {
                i += 4
                continue
            }
            if (a == 255) {
                outBytes[i] = premulBytes[i]
                outBytes[i + 1] = premulBytes[i + 1]
                outBytes[i + 2] = premulBytes[i + 2]
                outBytes[i + 3] = (-1).toByte()
            } else {
                val r = (((premulBytes[i].toInt() and 0xFF) * 255 + (a shr 1)) / a).coerceIn(0, 255)
                val g = (((premulBytes[i + 1].toInt() and 0xFF) * 255 + (a shr 1)) / a).coerceIn(0, 255)
                val b = (((premulBytes[i + 2].toInt() and 0xFF) * 255 + (a shr 1)) / a).coerceIn(0, 255)
                outBytes[i] = r.toByte()
                outBytes[i + 1] = g.toByte()
                outBytes[i + 2] = b.toByte()
                outBytes[i + 3] = a.toByte()
            }
            i += 4
        }

        return ImageBuffer(
            width = outW,
            height = outH,
            pixelFormat = PixelFormat.RGBA_8888,
            colorSpace = ImageColorSpace.SRGB,
            bytes = outBytes
        )
    }

    private fun encodeViaImageIO(buffer: ImageBuffer, uti: String, @Suppress("UNUSED_PARAMETER") quality: Int): ByteArray? {
        val rgba = buffer.asRgba8888()
        val w = rgba.width.coerceAtLeast(1)
        val h = rgba.height.coerceAtLeast(1)
        val premul = ByteArray(w * h * 4)
        val src = rgba.bytes
        var i = 0
        val n = minOf(premul.size, src.size)
        while (i + 3 < n) {
            val a = src[i + 3].toInt() and 0xFF
            if (a == 255) {
                premul[i] = src[i]
                premul[i + 1] = src[i + 1]
                premul[i + 2] = src[i + 2]
                premul[i + 3] = (-1).toByte()
            } else if (a > 0) {
                premul[i] = (((src[i].toInt() and 0xFF) * a + 127) / 255).toByte()
                premul[i + 1] = (((src[i + 1].toInt() and 0xFF) * a + 127) / 255).toByte()
                premul[i + 2] = (((src[i + 2].toInt() and 0xFF) * a + 127) / 255).toByte()
                premul[i + 3] = a.toByte()
            }
            i += 4
        }

        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
        try {
            val cgImg = premul.usePinned { pinned ->
                val ctx = CGBitmapContextCreate(
                    data = pinned.addressOf(0),
                    width = w.convert(),
                    height = h.convert(),
                    bitsPerComponent = 8u,
                    bytesPerRow = (w * 4).convert(),
                    space = colorSpace,
                    bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
                ) ?: return null
                try {
                    CGBitmapContextCreateImage(ctx)
                } finally {
                    CGContextRelease(ctx)
                }
            } ?: return null

            try {
                val mutableData = CFDataCreateMutable(null, 0) ?: return null
                try {
                    val utiStr = CFStringCreateWithCString(null, uti, kCFStringEncodingUTF8) ?: return null
                    try {
                        val dest = CGImageDestinationCreateWithData(mutableData, utiStr, 1u, null) ?: return null
                        try {
                            CGImageDestinationAddImage(dest, cgImg, null)
                            if (!CGImageDestinationFinalize(dest)) return null
                        } finally {
                            CFRelease(dest)
                        }
                    } finally {
                        CFRelease(utiStr)
                    }
                    val len = CFDataGetLength(mutableData).toInt()
                    val ptr = CFDataGetBytePtr(mutableData) ?: return null
                    if (len <= 0) return null
                    val out = ByteArray(len)
                    out.usePinned { outPin ->
                        memcpy(outPin.addressOf(0), ptr, len.convert())
                    }
                    return out
                } finally {
                    CFRelease(mutableData)
                }
            } finally {
                CGImageRelease(cgImg)
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }
    }
}
