@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.style.css.CssCustomCursor
import kotlinx.cinterop.*
import platform.windows.*

/**
 * Windows `.cur` binary cursor encoder and Win32 [HCURSOR] factory (`:desktop:windows`).
 *
 * Converts any [CssCustomCursor] or [ImageBuffer] (PNG resource, rasterized SVG resource/markup,
 * or raw RGBA pixel buffer) into a standards-compliant Windows `.cur` binary (`idType = 2`) with
 * embedded `(hotspotX, hotspotY)` coordinates, and creates native Win32 [HCURSOR] handles via
 * `CreateIconIndirect` + 32-bpp ARGB DIB sections.
 */
object WindowsCurCodec {

    private val cursorCache = mutableMapOf<String, HCURSOR>()

    /**
     * Resolves [cursor] to an [ImageBuffer] (rasterizing SVG or decoding PNG if needed) and encodes
     * it into a valid Windows `.cur` binary (`ByteArray`).
     */
    fun encodeToCurBytes(cursor: CssCustomCursor): ByteArray? {
        val image = cursor.resolveToImageBuffer() ?: return null
        return encodeToCurBytes(
            imageBuffer = image,
            hotspotX = cursor.hotspotX,
            hotspotY = cursor.hotspotY
        )
    }

    /**
     * Encodes [imageBuffer] and `(hotspotX, hotspotY)` into a complete Windows `.cur` binary (`ByteArray`):
     * - 6-byte `ICONDIR` header (`idReserved = 0`, `idType = 2`, `idCount = 1`)
     * - 16-byte `ICONDIRENTRY` (`bWidth`, `bHeight`, `0`, `0`, `hotspotX` UShort LE, `hotspotY` UShort LE,
     *   `dibAndMaskByteSize` UInt LE, `22` UInt LE)
     * - 40-byte `BITMAPINFOHEADER` (`biSize = 40`, `biWidth = w`, `biHeight = h * 2`, `biPlanes = 1`, `biBitCount = 32`)
     * - Bottom-up 32-bit BGRA XOR pixel array (`w * h * 4` bytes)
     * - Bottom-up 32-bit-aligned 1-bpp AND mask (`((w + 31) / 32) * 4 * h` bytes)
     */
    fun encodeToCurBytes(
        imageBuffer: ImageBuffer,
        hotspotX: Int = 0,
        hotspotY: Int = 0
    ): ByteArray {
        val w = imageBuffer.width.coerceIn(1, 256)
        val h = imageBuffer.height.coerceIn(1, 256)
        val safeHotX = hotspotX.coerceIn(0, 0xFFFF)
        val safeHotY = hotspotY.coerceIn(0, 0xFFFF)

        val xorBytesSize = w * h * 4
        val andRowStride = ((w + 31) / 32) * 4
        val andMaskBytesSize = andRowStride * h
        val dibHeaderSize = 40
        val dibAndMaskByteSize = dibHeaderSize + xorBytesSize + andMaskBytesSize
        val dataOffset = 6 + 16
        val totalSize = dataOffset + dibAndMaskByteSize

        val out = ByteArray(totalSize)

        // 1. 6-byte ICONDIR header
        writeUShortLE(out, 0, 0) // idReserved = 0
        writeUShortLE(out, 2, 2) // idType = 2 (.cur)
        writeUShortLE(out, 4, 1) // idCount = 1

        // 2. 16-byte ICONDIRENTRY
        out[6] = if (w == 256) 0 else w.toByte()
        out[7] = if (h == 256) 0 else h.toByte()
        out[8] = 0 // bColorCount
        out[9] = 0 // bReserved
        writeUShortLE(out, 10, safeHotX) // wPlanes = hotspotX for .cur
        writeUShortLE(out, 12, safeHotY) // wBitCount = hotspotY for .cur
        writeIntLE(out, 14, dibAndMaskByteSize) // dwBytesInRes
        writeIntLE(out, 18, dataOffset) // dwImageOffset = 22

        // 3. 40-byte BITMAPINFOHEADER
        writeIntLE(out, 22, dibHeaderSize) // biSize = 40
        writeIntLE(out, 26, w) // biWidth
        writeIntLE(out, 30, h * 2) // biHeight = h * 2 (XOR + AND)
        writeUShortLE(out, 34, 1) // biPlanes = 1
        writeUShortLE(out, 36, 32) // biBitCount = 32 (BGRA)
        writeIntLE(out, 38, 0) // biCompression = BI_RGB (0)
        writeIntLE(out, 42, xorBytesSize + andMaskBytesSize) // biSizeImage
        writeIntLE(out, 46, 0) // biXPelsPerMeter
        writeIntLE(out, 50, 0) // biYPelsPerMeter
        writeIntLE(out, 54, 0) // biClrUsed
        writeIntLE(out, 58, 0) // biClrImportant

        // 4. Bottom-up 32-bit BGRA XOR bitmap + 5. Bottom-up 1-bpp AND mask
        val xorStartOffset = 62
        val andStartOffset = xorStartOffset + xorBytesSize

        var bottomUpRow = 0
        for (srcY in (h - 1) downTo 0) {
            val xorRowOffset = xorStartOffset + bottomUpRow * (w * 4)
            val andRowOffset = andStartOffset + bottomUpRow * andRowStride
            for (srcX in 0 until w) {
                val color = imageBuffer.getPixel(srcX, srcY)
                val alpha = color.a.coerceIn(0, 255)
                val pxOffset = xorRowOffset + srcX * 4
                out[pxOffset] = color.b.coerceIn(0, 255).toByte()
                out[pxOffset + 1] = color.g.coerceIn(0, 255).toByte()
                out[pxOffset + 2] = color.r.coerceIn(0, 255).toByte()
                out[pxOffset + 3] = alpha.toByte()

                if (alpha == 0) {
                    val byteIndex = andRowOffset + (srcX shr 3)
                    val bitMask = 1 shl (7 - (srcX and 7))
                    out[byteIndex] = (out[byteIndex].toInt() or bitMask).toByte()
                }
            }
            bottomUpRow++
        }

        return out
    }

    /**
     * Validates whether [curBytes] is a well-formed Windows `.cur` binary (`idType == 2`).
     */
    fun isValidCurBinary(curBytes: ByteArray): Boolean {
        if (curBytes.size < 62) return false
        val idReserved = readUShortLE(curBytes, 0)
        val idType = readUShortLE(curBytes, 2)
        val idCount = readUShortLE(curBytes, 4)
        val imageOffset = readIntLE(curBytes, 18)
        val biSize = readIntLE(curBytes, 22)
        return idReserved == 0 && idType == 2 && idCount >= 1 && imageOffset == 22 && biSize == 40
    }

    /**
     * Extracts `(hotspotX, hotspotY)` from a `.cur` binary's `ICONDIRENTRY`, or `null` if invalid.
     */
    fun parseCurHotspot(curBytes: ByteArray): Pair<Int, Int>? {
        if (!isValidCurBinary(curBytes)) return null
        val hotX = readUShortLE(curBytes, 10)
        val hotY = readUShortLE(curBytes, 12)
        return hotX to hotY
    }

    /**
     * Extracts `(width, height)` from a `.cur` binary's `ICONDIRENTRY`, or `null` if invalid.
     */
    fun parseCurDimensions(curBytes: ByteArray): Pair<Int, Int>? {
        if (!isValidCurBinary(curBytes)) return null
        val rawW = curBytes[6].toInt() and 0xFF
        val rawH = curBytes[7].toInt() and 0xFF
        val w = if (rawW == 0) 256 else rawW
        val h = if (rawH == 0) 256 else rawH
        return w to h
    }

    /**
     * Creates (or retrieves from cache) a native Win32 [HCURSOR] from [cursor].
     */
    fun createWin32Cursor(cursor: CssCustomCursor): HCURSOR? {
        val cacheKey = buildString {
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
        cursorCache[cacheKey]?.let { return it }

        val image = cursor.resolveToImageBuffer() ?: return null
        val w = image.width.coerceIn(1, 256)
        val h = image.height.coerceIn(1, 256)
        val hotX = cursor.hotspotX.coerceIn(0, w - 1)
        val hotY = cursor.hotspotY.coerceIn(0, h - 1)

        val hCursor = memScoped {
            val bmi = alloc<BITMAPINFO>()
            bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
            bmi.bmiHeader.biWidth = w
            bmi.bmiHeader.biHeight = -h // top-down DIB
            bmi.bmiHeader.biPlanes = 1u
            bmi.bmiHeader.biBitCount = 32u
            bmi.bmiHeader.biCompression = BI_RGB.toUInt()

            val bitsPtrVar = alloc<CPointerVar< kotlinx.cinterop.ByteVar >>()
            val screenDc = GetDC(null)
            val hbmColor = CreateDIBSection(
                screenDc,
                bmi.ptr,
                DIB_RGB_COLORS.toUInt(),
                bitsPtrVar.ptr.reinterpret(),
                null,
                0u
            )
            ReleaseDC(null, screenDc)
            if (hbmColor == null) return@memScoped null

            val bitsPtr = bitsPtrVar.value
            if (bitsPtr != null) {
                for (y in 0 until h) {
                    val rowBase = y * w * 4
                    for (x in 0 until w) {
                        val c = image.getPixel(x, y)
                        val idx = rowBase + x * 4
                        bitsPtr[idx] = c.b.coerceIn(0, 255).toByte()
                        bitsPtr[idx + 1] = c.g.coerceIn(0, 255).toByte()
                        bitsPtr[idx + 2] = c.r.coerceIn(0, 255).toByte()
                        bitsPtr[idx + 3] = c.a.coerceIn(0, 255).toByte()
                    }
                }
            }

            val hbmMask = CreateBitmap(w, h, 1u, 1u, null)
            if (hbmMask == null) {
                DeleteObject(hbmColor)
                return@memScoped null
            }

            val iconInfo = alloc<ICONINFO>()
            iconInfo.fIcon = 0 // FALSE -> Cursor (not Icon)
            iconInfo.xHotspot = hotX.toUInt()
            iconInfo.yHotspot = hotY.toUInt()
            iconInfo.hbmMask = hbmMask
            iconInfo.hbmColor = hbmColor

            val created = CreateIconIndirect(iconInfo.ptr)
            DeleteObject(hbmMask)
            DeleteObject(hbmColor)
            created
        }

        if (hCursor != null) {
            cursorCache[cacheKey] = hCursor
        }
        return hCursor
    }

    /**
     * Releases all cached Win32 [HCURSOR] handles.
     */
    fun clearCursorCache() {
        for ((_, handle) in cursorCache) {
            runCatching { DestroyIcon(handle) }
        }
        cursorCache.clear()
    }

    private fun writeUShortLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun readUShortLE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8)

    private fun readIntLE(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)
}
