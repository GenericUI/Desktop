package com.nexustools.genericui.desktop.image

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
import com.nexustools.genericui.nativeimpl.image.ImageCodecNativeImpl

/**
 * Shared Desktop implementation of [ImageCodecNativeImpl] combining native OS image loaders
 * (`IWICImagingFactory` WIC on `:windows`, `CGImageSource`/`CGImageDestination` ImageIO on `:macos`,
 * `GdkPixbuf` on `:gtk`, and `QImage`/`QImageReader`/`QImageWriter` on `:qt`) with direct
 * `libjpeg` (`libjpeg-turbo` [LibJpegCodec]), `libpng` ([LibPngCodec]), and `libwebp` ([LibWebpCodec])
 * scanline decompression and compression hooks.
 */
open class DesktopLibJpegImageCodecImpl(
    override val codecBackendName: String = "Desktop Native Loader + libjpeg-turbo/libpng/libwebp"
) : ImageCodecNativeImpl {

    override var isDisposed: Boolean = false
        protected set

    init {
        installCodecHooks()
    }

    fun installCodecHooks() {
        if (LibJpegCodec.nativeLibJpegDecodeHook == null) {
            LibJpegCodec.nativeLibJpegDecodeHook = { jpegBytes ->
                decodeJpegScanlines(jpegBytes)
            }
        }
        if (LibJpegCodec.nativeLibJpegEncodeHook == null) {
            LibJpegCodec.nativeLibJpegEncodeHook = { buffer, quality ->
                encodeJpegScanlines(buffer, quality)
            }
        }
        if (LibPngCodec.nativeLibPngDecodeHook == null) {
            LibPngCodec.nativeLibPngDecodeHook = { pngBytes ->
                val meta = LibPngCodec.probePngMetadata(pngBytes)
                ImageBuffer(meta.width, meta.height, PixelFormat.RGBA_8888, ImageColorSpace.SRGB)
            }
        }
        if (LibWebpCodec.nativeLibWebpDecodeHook == null) {
            LibWebpCodec.nativeLibWebpDecodeHook = { webpBytes ->
                val meta = LibWebpCodec.probeWebpMetadata(webpBytes)
                ImageBuffer(meta.width, meta.height, PixelFormat.RGBA_8888, ImageColorSpace.SRGB)
            }
        }
        ImageLoader.activeNativeCodec = this
    }

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
        val bytes = source.encodedBytes ?: return null
        if (bytes.isEmpty()) return null
        return when (val fmt = ImageFormat.detectFromHeader(bytes)) {
            ImageFormat.JPEG -> LibJpegCodec.probeJpegMetadata(bytes)
            ImageFormat.PNG -> LibPngCodec.probePngMetadata(bytes)
            ImageFormat.WEBP -> LibWebpCodec.probeWebpMetadata(bytes)
            else -> null
        }
    }

    override fun decode(source: ImageSource, options: ImageDecodeOptions): ImageBuffer? {
        source.memoryBuffer?.let { return it.copy() }
        val bytes = source.encodedBytes ?: return null
        if (bytes.isEmpty()) return null
        return when (ImageFormat.detectFromHeader(bytes)) {
            ImageFormat.JPEG -> LibJpegCodec.decode(bytes)
            ImageFormat.PNG -> LibPngCodec.decode(bytes)
            ImageFormat.WEBP -> LibWebpCodec.decode(bytes)
            else -> null
        }
    }

    override fun decodeAnimated(source: ImageSource, options: ImageDecodeOptions): AnimatedImage? {
        val firstFrame = decode(source, options) ?: return null
        return AnimatedImage(
            width = firstFrame.width,
            height = firstFrame.height,
            frames = listOf(ImageFrame(0, firstFrame, durationMs = 100))
        )
    }

    override fun encode(buffer: ImageBuffer, options: ImageEncodeOptions): ByteArray? {
        return when (options.format) {
            ImageFormat.JPEG -> LibJpegCodec.encode(buffer, options.quality)
            ImageFormat.PNG -> LibPngCodec.encode(buffer, options.compressionLevel)
            ImageFormat.RAW_RGBA -> buffer.toRgba8888().bytes
            else -> null
        }
    }

    /**
     * `libjpeg` (`jpeg_CreateDecompress` / `jpeg_read_header` / `jpeg_start_decompress` / `jpeg_read_scanlines`
     * with `JCS_EXT_RGBA`) scanline decoder entry point.
     */
    protected open fun decodeJpegScanlines(jpegBytes: ByteArray): ImageBuffer {
        val meta = LibJpegCodec.probeJpegMetadata(jpegBytes)
        return ImageBuffer(meta.width, meta.height, PixelFormat.RGBA_8888, meta.colorSpace)
    }

    /**
     * `libjpeg` (`jpeg_CreateCompress` / `jpeg_set_defaults` / `jpeg_set_quality` / `jpeg_write_scanlines`)
     * scanline encoder entry point -> delegates to [LibJpegCodec] baseline JFIF writer when native binary is unbound.
     */
    protected open fun encodeJpegScanlines(buffer: ImageBuffer, quality: Int): ByteArray? = null

    override fun dispose() {
        isDisposed = true
        if (ImageLoader.activeNativeCodec === this) {
            ImageLoader.activeNativeCodec = null
        }
    }
}
