package com.nexustools.genericui.desktop.system

import com.nexustools.genericui.image.ImageBuffer

/**
 * Pixel-level image classifier determining whether a decoded tray icon should be rendered as a
 * macOS monochrome template mask (`NSImage.isTemplate = true`) vs preserved in full color
 * (`NSImage.isTemplate = false`).
 */
object DesktopSystemTrayTemplateAnalysis {
    private const val MIN_VISIBLE_ALPHA = 16
    private const val MAX_MONOCHROME_CHANNEL_SPREAD = 18

    /**
     * Returns `true` only when [buffer] is an alpha-masked monochrome glyph (has transparent
     * background pixels and every visible pixel has RGB channel spread within neutral grayscale
     * anti-aliasing tolerance). Full-color icons and opaque grayscale squares return `false`.
     */
    fun shouldTreatAsTemplateImage(buffer: ImageBuffer): Boolean {
        val rgba = buffer.asRgba8888()
        val bytes = rgba.bytes
        if (bytes.size < 4) return false

        var visiblePixels = 0
        var hasTransparency = false
        var idx = 0
        val limit = bytes.size - 3
        while (idx <= limit) {
            val r = bytes[idx].toInt() and 0xFF
            val g = bytes[idx + 1].toInt() and 0xFF
            val b = bytes[idx + 2].toInt() and 0xFF
            val a = bytes[idx + 3].toInt() and 0xFF
            if (a <= MIN_VISIBLE_ALPHA) {
                hasTransparency = true
            } else {
                visiblePixels++
                if (a < 245) {
                    hasTransparency = true
                }
                val maxC = maxOf(r, g, b)
                val minC = minOf(r, g, b)
                if (maxC - minC > MAX_MONOCHROME_CHANNEL_SPREAD) {
                    return false
                }
            }
            idx += 4
        }
        return visiblePixels > 0 && hasTransparency
    }
}
