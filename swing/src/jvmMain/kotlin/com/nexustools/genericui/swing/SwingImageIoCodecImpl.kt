package com.nexustools.genericui.swing

import com.nexustools.genericui.image.*
import com.nexustools.genericui.image.cache.ImageCacheManager
import com.nexustools.genericui.image.codec.LibJpegCodec
import com.nexustools.genericui.image.codec.LibPngCodec
import com.nexustools.genericui.image.codec.LibWebpCodec
import com.nexustools.genericui.nativeimpl.image.ImageCodecNativeImpl
import com.nexustools.genericui.style.RgbaColor
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import javax.imageio.ImageIO

/**
 * Java `javax.imageio.ImageIO` + `BufferedImage` implementation of [ImageCodecNativeImpl] for `:swing`.
 */
class SwingImageIoCodecImpl : ImageCodecNativeImpl {
    override val codecBackendName: String = "swing-javax-imageio"

    init {
        if (ImageCacheManager.localResourceReaderHook == null) {
            ImageCacheManager.localResourceReaderHook = { uriOrPath ->
                readLocalOrResourceBytes(uriOrPath)
            }
        }
        if (ImageCacheManager.urlFetcherHook == null) {
            ImageCacheManager.urlFetcherHook = { url ->
                if (url.startsWith("http://", ignoreCase = true) ||
                    url.startsWith("https://", ignoreCase = true)
                ) {
                    runCatching { URI.create(url).toURL().readBytes() }.getOrNull()
                } else {
                    readLocalOrResourceBytes(url)
                }
            }
        }
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
        val bytes = resolveSourceBytes(source) ?: return null
        if (bytes.isEmpty()) return null
        return when (ImageFormat.detectFromHeader(bytes)) {
            ImageFormat.JPEG -> LibJpegCodec.probeJpegMetadata(bytes)
            ImageFormat.PNG -> LibPngCodec.probePngMetadata(bytes)
            ImageFormat.WEBP -> LibWebpCodec.probeWebpMetadata(bytes)
            else -> null
        }
    }

    override fun decode(source: ImageSource, options: ImageDecodeOptions): ImageBuffer? {
        source.memoryBuffer?.let { return it.copy() }
        val bytes = resolveSourceBytes(source) ?: return null
        if (bytes.isEmpty()) return null

        val detected = ImageFormat.detectFromHeader(bytes)
        if (detected == ImageFormat.BMP || detected == ImageFormat.PPM) {
            // Let ImageLoader's built-in lossless BMP/PPM codecs handle these directly
            return null
        }

        try {
            val bimg = ImageIO.read(ByteArrayInputStream(bytes))
            if (bimg != null && bimg.width > 0 && bimg.height > 0) {
                val out = ImageBuffer(bimg.width, bimg.height, PixelFormat.RGBA_8888, ImageColorSpace.SRGB)
                for (y in 0 until bimg.height) {
                    for (x in 0 until bimg.width) {
                        val argb = bimg.getRGB(x, y)
                        val a = ((argb ushr 24) and 0xFF) / 255f
                        val r = (argb ushr 16) and 0xFF
                        val g = (argb ushr 8) and 0xFF
                        val b = argb and 0xFF
                        out.setPixel(x, y, RgbaColor(r, g, b, a))
                    }
                }
                return out
            }
        } catch (_: Throwable) {
            // Fall through to LibJpegCodec / LibPngCodec / LibWebpCodec
        }

        return when (detected) {
            ImageFormat.JPEG -> LibJpegCodec.decode(bytes)
            ImageFormat.PNG -> LibPngCodec.decode(bytes)
            ImageFormat.WEBP -> LibWebpCodec.decode(bytes)
            else -> null
        }
    }

    override fun decodeAnimated(source: ImageSource, options: ImageDecodeOptions): AnimatedImage? {
        val frame = decode(source, options) ?: return null
        return AnimatedImage(
            width = frame.width,
            height = frame.height,
            frames = listOf(ImageFrame(0, frame, durationMs = 100))
        )
    }

    override fun encode(buffer: ImageBuffer, options: ImageEncodeOptions): ByteArray? {
        if (options.format == ImageFormat.BMP || options.format == ImageFormat.PPM) {
            return null
        }
        if (options.format == ImageFormat.RAW_RGBA) {
            return buffer.toRgba8888().bytes
        }

        val formatName = when (options.format) {
            ImageFormat.PNG -> "png"
            ImageFormat.JPEG -> "jpg"
            else -> null
        }
        if (formatName != null) {
            try {
                val w = buffer.width.coerceAtLeast(1)
                val h = buffer.height.coerceAtLeast(1)
                val imgType = if (options.format == ImageFormat.JPEG) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
                val bimg = BufferedImage(w, h, imgType)
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        val c = buffer.getPixel(x, y)
                        val a = (c.a * 255f + 0.5f).toInt().coerceIn(0, 255)
                        val rgb = (a shl 24) or ((c.r and 0xFF) shl 16) or ((c.g and 0xFF) shl 8) or (c.b and 0xFF)
                        bimg.setRGB(x, y, rgb)
                    }
                }
                val baos = ByteArrayOutputStream()
                if (ImageIO.write(bimg, formatName, baos)) {
                    val encoded = baos.toByteArray()
                    if (encoded.isNotEmpty()) return encoded
                }
            } catch (_: Throwable) {
                // Fall back to LibJpegCodec / LibPngCodec
            }
        }

        return when (options.format) {
            ImageFormat.JPEG -> LibJpegCodec.encode(buffer, options.quality)
            ImageFormat.PNG -> LibPngCodec.encode(buffer, options.compressionLevel)
            else -> null
        }
    }

    override fun dispose() {}

    private fun resolveSourceBytes(source: ImageSource): ByteArray? {
        source.encodedBytes?.let { return it }
        val uri = source.uriOrPath ?: return null
        ImageCacheManager.fetchAndCacheBytes(uri)?.let { return it }
        return readLocalOrResourceBytes(uri)
    }

    private fun readLocalOrResourceBytes(uriOrPath: String): ByteArray? {
        val clean = uriOrPath.substringBefore('?').substringBefore('#').trim()
        if (clean.isEmpty()) return null
        val candidates = listOf(
            File(clean),
            File("src/commonMain/resources/$clean"),
            File("resources/$clean"),
            File("../src/commonMain/resources/$clean")
        )
        for (f in candidates) {
            if (f.isFile && f.exists()) {
                runCatching { return f.readBytes() }
            }
        }
        val resourceStream = SwingImageIoCodecImpl::class.java.classLoader?.getResourceAsStream(clean.trimStart('/'))
        if (resourceStream != null) {
            return runCatching { resourceStream.use { it.readBytes() } }.getOrNull()
        }
        return null
    }
}
