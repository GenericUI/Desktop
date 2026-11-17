package com.nexustools.genericui.swing

import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageColorSpace
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageEncodeOptions
import com.nexustools.genericui.image.ImageFormat
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.jvm.JvmImageCodecBase
import com.nexustools.genericui.style.color.RgbaColor
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * Java `javax.imageio.ImageIO` + `BufferedImage` implementation of
 * [com.nexustools.genericui.nativeimpl.image.ImageCodecNativeImpl] for Swing, extending [JvmImageCodecBase].
 */
class SwingImageIoCodecImpl : JvmImageCodecBase(installResolverHooks = true) {
    override val codecBackendName: String = "swing-javax-imageio"

    override fun resourceClassLoader(): ClassLoader? = SwingImageIoCodecImpl::class.java.classLoader

    override fun readPlatformResourceBytes(uriOrPath: String): ByteArray? {
        val clean = uriOrPath.substringBefore('?').substringBefore('#').trim()
        if (clean.isEmpty()) return null
        val fallback = File("../src/commonMain/resources/$clean")
        if (fallback.isFile && fallback.exists()) {
            runCatching { return fallback.readBytes() }
        }
        return null
    }

    override fun decodePlatform(
        bytes: ByteArray,
        detectedFormat: ImageFormat?,
        options: ImageDecodeOptions
    ): ImageBuffer? {
        val bimg = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        if (bimg.width <= 0 || bimg.height <= 0) return null
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

    override fun encodePlatform(
        buffer: ImageBuffer,
        options: ImageEncodeOptions
    ): ByteArray? {
        val formatName = when (options.format) {
            ImageFormat.PNG -> "png"
            ImageFormat.JPEG -> "jpg"
            else -> return null
        }
        val imgType = if (options.format == ImageFormat.JPEG) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_INT_ARGB
        val bimg = SwingControlTintRenderUtil.imageBufferToBufferedImage(buffer, imgType)
        val baos = ByteArrayOutputStream()
        if (!ImageIO.write(bimg, formatName, baos)) return null
        val encoded = baos.toByteArray()
        return encoded.takeIf { it.isNotEmpty() }
    }
}
