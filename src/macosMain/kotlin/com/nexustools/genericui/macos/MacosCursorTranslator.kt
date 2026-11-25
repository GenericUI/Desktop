@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.codec.LibPngCodec
import com.nexustools.genericui.style.css.CssCustomCursor
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.objcPtr
import kotlinx.cinterop.usePinned
import platform.AppKit.NSCursor
import platform.AppKit.NSImage
import platform.Foundation.NSData
import platform.Foundation.NSMakePoint
import platform.Foundation.NSMakeSize
import platform.Foundation.dataWithBytes

/**
 * Translates any [CssCustomCursor] or CSS cursor keyword into a live AppKit [NSCursor]
 * (`NSImage` PNG data + `NSMakePoint(hotspotX, hotspotY)`) for `macosMain`.
 */
object MacosCursorTranslator {

    private val encodedPngCache = mutableMapOf<String, ByteArray>()
    private val nativeCursorCache = mutableMapOf<String, NSCursor>()
    private val hiddenBlankCursor: NSCursor by lazy {
        val pngBytes = encodeToNsImagePngBytes(ImageBuffer(width = 1, height = 1))
        buildNsCursorFromPngBytes(pngBytes, hotSpotX = 0.0, hotSpotY = 0.0)
            ?: NSCursor(image = NSImage(size = NSMakeSize(1.0, 1.0)), hotSpot = NSMakePoint(0.0, 0.0))
    }

    /**
     * Resolves [cursor] into an [ImageBuffer] (rasterizing SVG or decoding PNG if needed) and encodes
     * it to PNG bytes suitable for `[NSImage initWithData:]` + `[NSCursor initWithImage:hotSpot:]`.
     */
    fun encodeToNsImagePngBytes(cursor: CssCustomCursor): ByteArray? {
        val key = buildCacheKey(cursor)
        encodedPngCache[key]?.let { return it }
        val buffer = cursor.resolveToImageBuffer() ?: return null
        val pngBytes = LibPngCodec.encode(buffer)
        encodedPngCache[key] = pngBytes
        return pngBytes
    }

    /**
     * Encodes an [ImageBuffer] directly into PNG bytes for `[NSImage initWithData:]`.
     */
    fun encodeToNsImagePngBytes(imageBuffer: ImageBuffer): ByteArray =
        LibPngCodec.encode(imageBuffer)

    /**
     * Constructs and caches a genuine AppKit [NSCursor] backed by `NSImage(data = pngNSData)` and
     * `NSMakePoint(hotspotX, hotspotY)` from [cursor].
     */
    fun toNsCursor(cursor: CssCustomCursor): NSCursor? {
        val key = buildCacheKey(cursor)
        nativeCursorCache[key]?.let { return it }
        val pngBytes = encodeToNsImagePngBytes(cursor) ?: return null
        val hotX = cursor.hotspotX.coerceIn(0, (cursor.widthPx - 1).coerceAtLeast(0)).toDouble()
        val hotY = cursor.hotspotY.coerceIn(0, (cursor.heightPx - 1).coerceAtLeast(0)).toDouble()
        val nativeCursor = buildNsCursorFromPngBytes(pngBytes, hotX, hotY) ?: return null
        nativeCursorCache[key] = nativeCursor
        return nativeCursor
    }

    private fun buildNsCursorFromPngBytes(
        pngBytes: ByteArray,
        hotSpotX: Double,
        hotSpotY: Double
    ): NSCursor? {
        if (pngBytes.isEmpty()) return null
        val nsData = pngBytes.usePinned { pinned ->
            NSData.dataWithBytes(pinned.addressOf(0), pngBytes.size.toULong())
        }
        val nsImage = NSImage(data = nsData)
        return NSCursor(image = nsImage, hotSpot = NSMakePoint(hotSpotX, hotSpotY))
    }

    internal fun isSameCursor(a: NSCursor?, b: NSCursor?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        return a.objcPtr() == b.objcPtr() || a == b
    }

    internal fun isHiddenCursor(cursor: NSCursor?): Boolean = isSameCursor(cursor, hiddenBlankCursor)

    /**
     * Resolves a cache key + AppKit [NSCursor] pair from an optional [CssCustomCursor],
     * CSS `cursor` keyword string, and whether the hovered region is a text input.
     */
    fun resolveCursor(
        rawCss: String?,
        customCursor: CssCustomCursor? = null,
        isTextInput: Boolean = false
    ): Pair<String, NSCursor> {
        if (customCursor != null) {
            val customNative = toNsCursor(customCursor)
            if (customNative != null) {
                return "custom:${buildCacheKey(customCursor)}" to customNative
            }
            return resolveStandardCursor(customCursor.fallback.cssValue, isTextInput)
        }
        return resolveStandardCursor(rawCss, isTextInput)
    }

    private fun resolveStandardCursor(rawCss: String?, isTextInput: Boolean): Pair<String, NSCursor> {
        val normalized = rawCss?.lowercase()?.trim()
        return when (normalized) {
            "pointer", "hand" -> "pointer" to NSCursor.pointingHandCursor
            "text", "ibeam" -> "ibeam" to NSCursor.IBeamCursor
            "crosshair" -> "crosshair" to NSCursor.crosshairCursor
            "col-resize", "ew-resize" -> "ew-resize" to NSCursor.resizeLeftRightCursor
            "row-resize", "ns-resize" -> "ns-resize" to NSCursor.resizeUpDownCursor
            "grab" -> "grab" to NSCursor.openHandCursor
            "grabbing" -> "grabbing" to NSCursor.closedHandCursor
            "not-allowed", "no-drop" -> "not-allowed" to NSCursor.operationNotAllowedCursor
            "none" -> "none" to hiddenBlankCursor
            else -> if (isTextInput) {
                "ibeam" to NSCursor.IBeamCursor
            } else {
                "arrow" to NSCursor.arrowCursor
            }
        }
    }

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
            viewHandle.attributes.remove("nsCursorNative")
            return false
        }
        val pngBytes = encodeToNsImagePngBytes(cursor) ?: return false
        val nativeCursor = toNsCursor(cursor)
        val hotX = cursor.hotspotX.coerceIn(0, (cursor.widthPx - 1).coerceAtLeast(0))
        val hotY = cursor.hotspotY.coerceIn(0, (cursor.heightPx - 1).coerceAtLeast(0))
        viewHandle.attributes["nsCursorPngBytes"] = pngBytes
        viewHandle.attributes["nsCursorHotspotX"] = hotX
        viewHandle.attributes["nsCursorHotspotY"] = hotY
        viewHandle.attributes["nsCursorWidth"] = cursor.widthPx
        viewHandle.attributes["nsCursorHeight"] = cursor.heightPx
        viewHandle.attributes["nsCursorFallback"] = cursor.fallback.cssValue
        if (nativeCursor != null) {
            viewHandle.attributes["nsCursorNative"] = nativeCursor
        } else {
            viewHandle.attributes.remove("nsCursorNative")
        }
        return true
    }

    fun clearCache() {
        encodedPngCache.clear()
        nativeCursorCache.clear()
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
