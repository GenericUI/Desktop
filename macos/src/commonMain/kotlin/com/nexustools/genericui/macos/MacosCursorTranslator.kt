package com.nexustools.genericui.macos

import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.PureKotlinPngCodec
import com.nexustools.genericui.style.css.CssCustomCursor

/**
 * Translates any [CssCustomCursor] (PNG resource, rasterized SVG resource/markup, or [ImageBuffer])
 * into a macOS AppKit `NSCursor` representation (`NSImage` PNG data + `NSMakePoint(hotspotX, hotspotY)`)
 * for `:desktop:macos`.
 */
object MacosCursorTranslator {

    private val encodedPngCache = mutableMapOf<String, ByteArray>()

    /**
     * Resolves [cursor] into an [ImageBuffer] (rasterizing SVG or decoding PNG if needed) and encodes
     * it to PNG bytes suitable for `[NSImage initWithData:]` + `[NSCursor initWithImage:hotSpot:]`.
     */
    fun encodeToNsImagePngBytes(cursor: CssCustomCursor): ByteArray? {
        val key = buildCacheKey(cursor)
        encodedPngCache[key]?.let { return it }
        val buffer = cursor.resolveToImageBuffer() ?: return null
        val pngBytes = PureKotlinPngCodec.encodePng(buffer)
        encodedPngCache[key] = pngBytes
        return pngBytes
    }

    /**
     * Encodes an [ImageBuffer] directly into PNG bytes for `[NSImage initWithData:]`.
     */
    fun encodeToNsImagePngBytes(imageBuffer: ImageBuffer): ByteArray =
        PureKotlinPngCodec.encodePng(imageBuffer)

    /**
     * Applies [cursor] to [viewHandle], storing the resolved `NSImage` PNG payload and
     * `NSMakePoint(hotspotX, hotspotY)` attributes for AppKit `resetCursorRects` / `cursorUpdate:`.
     */
    fun applyToViewHandle(viewHandle: MacosViewHandle, cursor: CssCustomCursor?): Boolean {
        if (cursor == null) {
            viewHandle.attributes.remove("nsCursorPngBytes")
            viewHandle.attributes.remove("nsCursorHotspotX")
            viewHandle.attributes.remove("nsCursorHotspotY")
            viewHandle.attributes.remove("nsCursorWidth")
            viewHandle.attributes.remove("nsCursorHeight")
            viewHandle.attributes.remove("nsCursorFallback")
            return false
        }
        val pngBytes = encodeToNsImagePngBytes(cursor) ?: return false
        val hotX = cursor.hotspotX.coerceIn(0, (cursor.widthPx - 1).coerceAtLeast(0))
        val hotY = cursor.hotspotY.coerceIn(0, (cursor.heightPx - 1).coerceAtLeast(0))
        viewHandle.attributes["nsCursorPngBytes"] = pngBytes
        viewHandle.attributes["nsCursorHotspotX"] = hotX
        viewHandle.attributes["nsCursorHotspotY"] = hotY
        viewHandle.attributes["nsCursorWidth"] = cursor.widthPx
        viewHandle.attributes["nsCursorHeight"] = cursor.heightPx
        viewHandle.attributes["nsCursorFallback"] = cursor.fallback.cssValue
        return true
    }

    fun clearCache() {
        encodedPngCache.clear()
    }

    private fun buildCacheKey(cursor: CssCustomCursor): String = buildString {
        append(cursor.resourceId?.id ?: -1)
        append(':')
        append(cursor.svgMarkup?.hashCode() ?: 0)
        append(':')
        append(cursor.imageBuffer?.hashCode() ?: 0)
        append(':')
        append(cursor.widthPx)
        append('x')
        append(cursor.heightPx)
        append('@')
        append(cursor.hotspotX)
        append(',')
        append(cursor.hotspotY)
    }
}
