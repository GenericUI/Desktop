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
import kotlinx.coroutines.runBlocking

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
