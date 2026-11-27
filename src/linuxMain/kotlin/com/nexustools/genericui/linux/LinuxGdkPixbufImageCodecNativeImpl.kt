@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.linux

import com.nexustools.genericui.desktop.image.DesktopLibJpegImageCodecImpl
import com.nexustools.genericui.image.AnimatedImage
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageColorSpace
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageEncodeOptions
import com.nexustools.genericui.image.ImageFormat
import com.nexustools.genericui.image.ImageFrame
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageMetadata
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.image.codec.LibJpegCodec
import com.nexustools.genericui.image.codec.LibPngCodec
import com.nexustools.genericui.image.codec.LibWebpCodec
import com.nexustools.genericui.nativeimpl.net.HttpClientNativeImpl
import com.nexustools.genericui.res.DataUrlCodec
import com.nexustools.genericui.res.Resources
import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs

open class LinuxGdkPixbufImageCodecNativeImpl(
    httpClient: HttpClientNativeImpl? = null,
    codecName: String = "GdkPixbuf+LibWebP+LibJpegCodec"
) : DesktopLibJpegImageCodecImpl(codecName) {
    private val lock = LinuxReentrantLock()
    private val ownsHttpClient = httpClient == null
    private val effectiveHttpClient: HttpClientNativeImpl = httpClient ?: LinuxCurlHttpClientNativeImpl()

    override fun probeMetadata(source: ImageSource): ImageMetadata? {
        if (isDisposed) return null
        source.memoryBuffer?.let { buf ->
            return ImageMetadata(
                width = buf.width,
                height = buf.height,
                format = if (source.formatHint != ImageFormat.UNKNOWN) source.formatHint else ImageFormat.RAW_RGBA,
                pixelFormat = buf.pixelFormat,
                colorSpace = buf.colorSpace
            )
        }
        val bytes = resolveSourceBytes(source) ?: return null
        if (bytes.isEmpty()) return null

        val headerFormat = ImageFormat.detectFromHeader(bytes)
        if (headerFormat == ImageFormat.WEBP) {
            LinuxGdkPixbufBindings.probeWebpDimensions(bytes)?.let { (w, h) ->
                return ImageMetadata(width = w, height = h, format = ImageFormat.WEBP, hasAlpha = true)
            }
            LibWebpCodec.probeWebpMetadata(bytes)?.let { return it }
        }
        if (headerFormat == ImageFormat.PNG) {
            LibPngCodec.probePngMetadata(bytes)?.let { return it }
        }
        if (headerFormat == ImageFormat.JPEG) {
            LibJpegCodec.probeJpegMetadata(bytes)?.let { return it }
        }
        LinuxGdkPixbufBindings.decodeViaGdkPixbuf(bytes)?.let { decoded ->
            return ImageMetadata(
                width = decoded.width,
                height = decoded.height,
                format = if (headerFormat != ImageFormat.UNKNOWN) headerFormat else source.formatHint,
                pixelFormat = PixelFormat.RGBA_8888,
                colorSpace = ImageColorSpace.SRGB,
                hasAlpha = decoded.hasAlpha,
                attributes = mapOf("backend" to codecBackendName)
            )
        }
        return super.probeMetadata(ImageSource.fromBytes(bytes, source.formatHint))
    }

    override fun decode(source: ImageSource, options: ImageDecodeOptions): ImageBuffer? {
        if (isDisposed) return null
        source.memoryBuffer?.let { mem ->
            val (dstW, dstH) = ImageLoader.resolveTargetDimensions(mem.width, mem.height, options)
            if (dstW == mem.width && dstH == mem.height && options.preferredPixelFormat == mem.pixelFormat) {
                return mem
            }
            val encodedPng = encode(mem, ImageEncodeOptions(format = ImageFormat.PNG))
            if (encodedPng != null) {
                val resampled = LinuxGdkPixbufBindings.decodeViaGdkPixbuf(
                    encodedPng,
                    targetWidth = dstW,
                    targetHeight = dstH,
                    antialiasing = options.antialiasing
                )
                if (resampled != null) {
                    return applyRequestedPixelFormat(
                        ImageBuffer(resampled.width, resampled.height, PixelFormat.RGBA_8888, ImageColorSpace.SRGB, resampled.rgbaBytes),
                        options
                    )
                }
            }
            return applyRequestedPixelFormat(mem, options)
        }

        val bytes = resolveSourceBytes(source) ?: return null
        if (bytes.isEmpty()) return null
        val format = ImageFormat.detectFromHeader(bytes).takeIf { it != ImageFormat.UNKNOWN } ?: source.formatHint

        if (format == ImageFormat.WEBP) {
            LinuxGdkPixbufBindings.decodeWebpRgba(bytes)?.let { webpDecoded ->
                val rawBuf = ImageBuffer(webpDecoded.width, webpDecoded.height, PixelFormat.RGBA_8888, ImageColorSpace.SRGB, webpDecoded.rgbaBytes)
                val (dstW, dstH) = ImageLoader.resolveTargetDimensions(webpDecoded.width, webpDecoded.height, options)
                val resized = if (dstW != webpDecoded.width || dstH != webpDecoded.height) {
                    val png = LibPngCodec.encode(rawBuf)
                    png?.let {
                        LinuxGdkPixbufBindings.decodeViaGdkPixbuf(it, dstW, dstH, options.antialiasing)?.let { scaled ->
                            ImageBuffer(scaled.width, scaled.height, PixelFormat.RGBA_8888, ImageColorSpace.SRGB, scaled.rgbaBytes)
                        }
                    } ?: rawBuf
                } else {
                    rawBuf
                }
                return applyRequestedPixelFormat(resized, options)
            }
        }

        val probeDims = when (format) {
            ImageFormat.PNG -> LibPngCodec.probePngMetadata(bytes)?.let { it.width to it.height }
            ImageFormat.JPEG -> LibJpegCodec.probeJpegMetadata(bytes)?.let { it.width to it.height }
            else -> null
        }
        val (targetW, targetH) = if (probeDims != null && (options.targetWidth != null || options.targetHeight != null)) {
            ImageLoader.resolveTargetDimensions(probeDims.first, probeDims.second, options)
        } else {
            options.targetWidth to options.targetHeight
        }

        LinuxGdkPixbufBindings.decodeViaGdkPixbuf(
            bytes = bytes,
            targetWidth = targetW,
            targetHeight = targetH,
            antialiasing = options.antialiasing
        )?.let { pixbufDecoded ->
            return applyRequestedPixelFormat(
                ImageBuffer(pixbufDecoded.width, pixbufDecoded.height, PixelFormat.RGBA_8888, ImageColorSpace.SRGB, pixbufDecoded.rgbaBytes),
                options
            )
        }

        if (isIcoHeader(bytes, format)) {
            decodeIcoFallback(bytes, options)?.let { icoBuf ->
                return applyRequestedPixelFormat(icoBuf, options)
            }
        }

        val fallbackDecoded = super.decode(ImageSource.fromBytes(bytes, format), options) ?: return null
        return applyRequestedPixelFormat(fallbackDecoded, options)
    }

    override fun decodeAnimated(source: ImageSource, options: ImageDecodeOptions): AnimatedImage? {
        val frame = decode(source, options) ?: return null
        return AnimatedImage(
            width = frame.width,
            height = frame.height,
            frames = listOf(ImageFrame(frameIndex = 0, buffer = frame, durationMs = 100))
        )
    }

    override fun encode(buffer: ImageBuffer, options: ImageEncodeOptions): ByteArray? {
        if (isDisposed) return null
        val rgba = buffer.toRgba8888()
        return when (options.format) {
            ImageFormat.PNG -> {
                LinuxGdkPixbufBindings.encodeViaGdkPixbuf(rgba.bytes, rgba.width, rgba.height, "png", options.quality)
                    ?: LibPngCodec.encode(rgba, options.compressionLevel)
            }
            ImageFormat.JPEG -> {
                LinuxGdkPixbufBindings.encodeViaGdkPixbuf(rgba.bytes, rgba.width, rgba.height, "jpeg", options.quality)
                    ?: LibJpegCodec.encode(rgba, options.quality)
            }
            ImageFormat.WEBP -> {
                LinuxGdkPixbufBindings.encodeWebpRgba(rgba.bytes, rgba.width, rgba.height, options.quality.toFloat())
                    ?: LibWebpCodec.nativeLibWebpEncodeHook?.invoke(rgba, options.quality)
            }
            ImageFormat.BMP -> {
                LinuxGdkPixbufBindings.encodeViaGdkPixbuf(rgba.bytes, rgba.width, rgba.height, "bmp", options.quality)
            }
            ImageFormat.RAW_RGBA -> rgba.bytes
            else -> LibPngCodec.encode(rgba, options.compressionLevel)
        }
    }

    override fun dispose() {
        super.dispose()
        if (ownsHttpClient) {
            effectiveHttpClient.dispose()
        }
    }

    private fun resolveSourceBytes(source: ImageSource): ByteArray? {
        source.encodedBytes?.let { return it }
        source.resId?.let { resId ->
            LinuxResourceResolverImpl().let { resolver ->
                val resolved = resolver.resolveBytes(resId)
                resolver.dispose()
                if (resolved != null) return resolved
            }
        }
        val uri = source.uriOrPath?.trim() ?: return null
        if (uri.startsWith("data:", ignoreCase = true)) {
            return DataUrlCodec.decodeBytes(uri)
        }
        val trimmedMarkup = uri.trimStart()
        if (trimmedMarkup.startsWith("<svg", ignoreCase = true) ||
            (trimmedMarkup.startsWith("<?xml", ignoreCase = true) && trimmedMarkup.contains("<svg", ignoreCase = true))
        ) {
            return trimmedMarkup.encodeToByteArray()
        }
        resolveDrawableBytes(uri)?.let { return it }
        if (uri.startsWith("http://", ignoreCase = true) || uri.startsWith("https://", ignoreCase = true)) {
            return runBlocking {
                val req = LinuxHttpRequestNativeImpl(url = uri)
                try {
                    val resp = effectiveHttpClient.execute(req)
                    try {
                        if (resp.statusCode in 200..299) resp.bodyBytes else null
                    } finally {
                        resp.dispose()
                    }
                } finally {
                    req.dispose()
                }
            }
        }
        val path = if (uri.startsWith("file://", ignoreCase = true)) {
            uri.removePrefix("file://").removePrefix("FILE://")
        } else {
            uri
        }
        return LinuxPosixPaths.readAllBytes(path) ?: super.decode(source, ImageDecodeOptions())?.toRgba8888()?.bytes
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun resolveDrawableBytes(uriOrName: String): ByteArray? {
        val clean = uriOrName.substringBefore('?').substringBefore('#').trim()
        if (clean.isEmpty()) return null
        val stripped = clean.removePrefix("res://").removePrefix("res/").trimStart('/')
        val shortName = stripped.substringAfterLast('/').substringBeforeLast('.')
        val drawable = Resources.getDrawable(clean)
            ?: Resources.getDrawable(stripped)
            ?: shortName.takeIf { it.isNotEmpty() }?.let { Resources.getDrawable(it) }
            ?: return null
        drawable.svgSource?.takeIf { it.isNotBlank() }?.let { return it.encodeToByteArray() }
        drawable.base64Data?.takeIf { it.isNotBlank() }?.let { b64 ->
            runCatching { return Base64.decode(b64.trim()) }
        }
        if (drawable.svgPathDataList.isNotEmpty()) {
            val vbW = drawable.viewBoxWidth.takeIf { it > 0f } ?: 24f
            val vbH = drawable.viewBoxHeight.takeIf { it > 0f } ?: 24f
            val paths = drawable.svgPathDataList.joinToString("") { d -> "<path fill=\"#000000\" d=\"$d\"/>" }
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $vbW $vbH\">$paths</svg>".encodeToByteArray()
        }
        return null
    }

    private fun isIcoHeader(bytes: ByteArray, format: ImageFormat): Boolean {
        if (format == ImageFormat.ICO) return true
        if (bytes.size < 22) return false
        val reserved = readU16LE(bytes, 0)
        val type = readU16LE(bytes, 2)
        val count = readU16LE(bytes, 4)
        return reserved == 0 && (type == 1 || type == 2) && count in 1..128
    }

    private fun decodeIcoFallback(bytes: ByteArray, options: ImageDecodeOptions): ImageBuffer? {
        if (bytes.size < 6) return null
        val count = readU16LE(bytes, 4).coerceAtMost(64)
        if (count <= 0) return null
        var bestW = 0
        var bestH = 0
        var bestBpp = -1
        var bestOff = -1
        var bestSize = 0
        var bestScore = Int.MAX_VALUE
        val want = options.targetWidth ?: options.targetHeight
        for (i in 0 until count) {
            val dir = 6 + i * 16
            if (dir + 16 > bytes.size) break
            val w = (bytes[dir].toInt() and 0xFF).let { if (it == 0) 256 else it }
            val h = (bytes[dir + 1].toInt() and 0xFF).let { if (it == 0) 256 else it }
            val bpp = readU16LE(bytes, dir + 6)
            val sz = readI32LE(bytes, dir + 8)
            val off = readI32LE(bytes, dir + 12)
            if (sz <= 0 || off < 6 || off.toLong() + sz.toLong() > bytes.size.toLong()) continue
            val score = if (want != null && want > 0) {
                (abs(w - want) + abs(h - want)) * 1000 + (if (w >= want) 0 else 500) - bpp
            } else {
                -(w * h * 100 + bpp)
            }
            if (score < bestScore) {
                bestScore = score
                bestW = w; bestH = h; bestBpp = bpp; bestOff = off; bestSize = sz
            }
        }
        if (bestOff < 0 || bestSize <= 0) return null
        val slice = bytes.copyOfRange(bestOff, bestOff + bestSize)
        if (slice.size >= 8 &&
            (slice[0].toInt() and 0xFF) == 0x89 &&
            slice[1] == 'P'.code.toByte() &&
            slice[2] == 'N'.code.toByte() &&
            slice[3] == 'G'.code.toByte()
        ) {
            return LibPngCodec.decode(slice)
        }
        return decodeIcoDib(slice, bestW, bestH)
    }

    private fun decodeIcoDib(dib: ByteArray, entryW: Int, entryH: Int): ImageBuffer? {
        if (dib.size < 40) return null
        val headerSize = readI32LE(dib, 0)
        if (headerSize < 40 || headerSize > dib.size) return null
        val width = readI32LE(dib, 4).takeIf { it > 0 } ?: entryW
        val rawH = readI32LE(dib, 8)
        val topDown = rawH < 0
        val totalH = abs(rawH)
        val height = if (totalH == entryH * 2 || totalH > entryH) (totalH / 2).coerceAtLeast(1) else entryH
        val bpp = readU16LE(dib, 14)
        val clrUsed = readI32LE(dib, 32)
        val paletteCount = if (bpp <= 8) (if (clrUsed > 0) clrUsed else (1 shl bpp)) else 0
        val paletteOffset = headerSize
        val xorOffset = paletteOffset + paletteCount * 4
        if (xorOffset >= dib.size) return null
        val xorStride = ((width * bpp + 31) / 32) * 4
        val andOffset = xorOffset + xorStride * height
        val andStride = ((width + 31) / 32) * 4
        val out = ImageBuffer(width, height, PixelFormat.RGBA_8888, ImageColorSpace.SRGB)
        var max32Alpha = 0
        for (row in 0 until height) {
            val y = if (topDown) row else (height - 1 - row)
            val xorRow = xorOffset + row * xorStride
            for (x in 0 until width) {
                var r = 0; var g = 0; var b = 0; var a = 255
                when (bpp) {
                    32 -> {
                        val p = xorRow + x * 4
                        if (p + 3 < dib.size) {
                            b = dib[p].toInt() and 0xFF
                            g = dib[p + 1].toInt() and 0xFF
                            r = dib[p + 2].toInt() and 0xFF
                            a = dib[p + 3].toInt() and 0xFF
                            if (a > max32Alpha) max32Alpha = a
                        }
                    }
                    24 -> {
                        val p = xorRow + x * 3
                        if (p + 2 < dib.size) {
                            b = dib[p].toInt() and 0xFF
                            g = dib[p + 1].toInt() and 0xFF
                            r = dib[p + 2].toInt() and 0xFF
                        }
                    }
                    8, 4, 1 -> {
                        val palIdx = when (bpp) {
                            8 -> {
                                val p = xorRow + x
                                if (p < dib.size) (dib[p].toInt() and 0xFF) else 0
                            }
                            4 -> {
                                val p = xorRow + (x ushr 1)
                                val v = if (p < dib.size) (dib[p].toInt() and 0xFF) else 0
                                if ((x and 1) == 0) (v ushr 4) else (v and 0x0F)
                            }
                            else -> {
                                val p = xorRow + (x ushr 3)
                                val v = if (p < dib.size) (dib[p].toInt() and 0xFF) else 0
                                (v ushr (7 - (x and 7))) and 1
                            }
                        }
                        val cOff = paletteOffset + palIdx * 4
                        if (cOff + 2 < dib.size) {
                            b = dib[cOff].toInt() and 0xFF
                            g = dib[cOff + 1].toInt() and 0xFF
                            r = dib[cOff + 2].toInt() and 0xFF
                        }
                    }
                    else -> return null
                }
                out.setPixel(x, y, com.nexustools.genericui.style.color.RgbaColor(r, g, b, a / 255f))
            }
        }
        val useAndMask = (bpp < 32 || max32Alpha == 0) && andOffset + andStride * height <= dib.size
        if (useAndMask) {
            for (row in 0 until height) {
                val y = if (topDown) row else (height - 1 - row)
                val andRow = andOffset + row * andStride
                for (x in 0 until width) {
                    val p = andRow + (x ushr 3)
                    val bit = ((dib[p].toInt() and 0xFF) ushr (7 - (x and 7))) and 1
                    val cur = out.getPixel(x, y)
                    out.setPixel(x, y, com.nexustools.genericui.style.color.RgbaColor(cur.r, cur.g, cur.b, if (bit == 1) 0f else 1f))
                }
            }
        }
        return out
    }

    private fun readU16LE(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    private fun readI32LE(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)

    private fun applyRequestedPixelFormat(buffer: ImageBuffer, options: ImageDecodeOptions): ImageBuffer {
        if (buffer.pixelFormat == options.preferredPixelFormat) return buffer
        val rgba = buffer.toRgba8888()
        if (options.preferredPixelFormat == PixelFormat.RGBA_8888) return rgba
        if (options.preferredPixelFormat == PixelFormat.BGRA_8888) {
            val src = rgba.bytes
            val out = ByteArray(src.size)
            var i = 0
            while (i + 3 < src.size) {
                out[i] = src[i + 2]
                out[i + 1] = src[i + 1]
                out[i + 2] = src[i]
                out[i + 3] = src[i + 3]
                i += 4
            }
            return ImageBuffer(rgba.width, rgba.height, PixelFormat.BGRA_8888, rgba.colorSpace, out)
        }
        return rgba
    }
}
