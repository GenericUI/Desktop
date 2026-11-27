package com.nexustools.genericui.linux

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.MonocolorImageRecolorProcessor
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.style.color.ImageTintMode
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.theme.SystemThemeDetector
import kotlinx.coroutines.Job
import platform.posix.R_OK
import platform.posix.access

/**
 * Shared Linux backend helper for decoding, resizing, and optional theme-tinting of tab icons
 * (PNG, JPEG, WEBP, ICO, SVG, `data:`, `res://`, local paths, and remote `http(s)://` favicons)
 * across GTK4 and KDE Qt 6 tab implementations.
 */
internal object LinuxTabIconHelper {

    fun isDarkTheme(): Boolean =
        (Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()) == ThemeMode.DARK

    fun resolveDefaultThemeTint(isDark: Boolean): RgbaColor =
        if (isDark) RgbaColor(232, 236, 242, 255) else RgbaColor(32, 36, 44, 255)

    fun looksLikeImageOrVectorSpec(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.contains("://") || trimmed.contains('/') || trimmed.contains('\\')) return true
        if (trimmed.startsWith("<svg", ignoreCase = true) || trimmed.startsWith("data:", ignoreCase = true)) return true
        val lower = trimmed.substringBefore('?').substringBefore('#').lowercase()
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp") ||
            lower.endsWith(".ico") || lower.endsWith(".svg")
        ) {
            return true
        }
        if (Resources.getDrawable(trimmed) != null) return true
        return access(trimmed, R_OK) == 0
    }

    fun prepareTabIconBuffer(
        raw: ImageBuffer,
        sizePx: Int,
        tintToTheme: Boolean,
        isDark: Boolean,
        customTint: RgbaColor? = null
    ): ImageBuffer {
        val size = sizePx.coerceIn(8, 128)
        val scaled = if (raw.width != size || raw.height != size) {
            ImageLoader.resize(raw, size, size, antialiasing = true)
        } else {
            raw
        }
        if (!tintToTheme) {
            return scaled.toRgba8888()
        }
        val effectiveTint = customTint ?: resolveDefaultThemeTint(isDark)
        return MonocolorImageRecolorProcessor.processImageBuffer(
            buffer = scaled,
            mode = ImageTintMode.SRC_IN,
            tintColor = effectiveTint,
            isDarkTheme = isDark
        )
    }

    fun hasVisiblePixels(buf: ImageBuffer): Boolean {
        if (buf.width <= 0 || buf.height <= 0) return false
        val rgba = buf.toRgba8888().bytes
        var i = 3
        while (i < rgba.size) {
            if ((rgba[i].toInt() and 0xFF) > 0) return true
            i += 4
        }
        return false
    }

    fun toPremulBgraBytes(buf: ImageBuffer): ByteArray {
        val rgba = buf.toRgba8888()
        val w = rgba.width
        val h = rgba.height
        val src = rgba.bytes
        val out = ByteArray(w * h * 4)
        val total = w * h
        for (i in 0 until total) {
            val o = i * 4
            val r = src[o].toInt() and 0xFF
            val g = src[o + 1].toInt() and 0xFF
            val b = src[o + 2].toInt() and 0xFF
            val a = src[o + 3].toInt() and 0xFF
            val pb = if (a == 255) b else (b * a + 127) / 255
            val pg = if (a == 255) g else (g * a + 127) / 255
            val pr = if (a == 255) r else (r * a + 127) / 255
            out[o] = pb.toByte()
            out[o + 1] = pg.toByte()
            out[o + 2] = pr.toByte()
            out[o + 3] = a.toByte()
        }
        return out
    }

    fun ensureLinuxImageCodec(): LinuxGdkPixbufImageCodecNativeImpl {
        val existing = ImageLoader.activeNativeCodec as? LinuxGdkPixbufImageCodecNativeImpl
        if (existing != null) return existing
        val created = LinuxGdkPixbufImageCodecNativeImpl()
        if (ImageLoader.activeNativeCodec == null) {
            ImageLoader.activeNativeCodec = created
        }
        return created
    }

    fun launchLoadTabIcon(
        iconSpec: String,
        targetSizePx: Int,
        onLoaded: (ImageBuffer) -> Unit,
        onError: () -> Unit = {}
    ): Job? {
        val trimmed = iconSpec.trim()
        if (trimmed.isEmpty() || !looksLikeImageOrVectorSpec(trimmed)) {
            onError()
            return null
        }
        val size = targetSizePx.coerceIn(8, 128)
        ensureLinuxImageCodec()
        if (trimmed.startsWith("memory://", ignoreCase = true)) {
            ImageLoader.resolveMemoryBuffer(trimmed)?.let { buf ->
                onLoaded(buf)
                return null
            }
        }
        return ImageLoader.launchLoad(
            source = ImageSource.fromUrl(trimmed),
            options = ImageDecodeOptions(targetWidth = size, targetHeight = size, antialiasing = true),
            onLoaded = { buf ->
                if (hasVisiblePixels(buf)) {
                    onLoaded(buf)
                } else {
                    onError()
                }
            },
            onError = { onError() }
        )
    }
}
