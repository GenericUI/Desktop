package com.nexustools.genericui.swing

import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageColorSpace
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageEncodeOptions
import com.nexustools.genericui.image.ImageFormat
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageMetadata
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.jvm.JvmImageCodecBase
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.style.color.RgbaColor
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.w3c.dom.Element

/**
 * Java `javax.imageio.ImageIO` + `BufferedImage` implementation of
 * [com.nexustools.genericui.nativeimpl.image.ImageCodecNativeImpl] for Swing, extending [JvmImageCodecBase].
 *
 * Also provides built-in `.ico`/`.cur` icon container decoding and `<svg>` vector rasterization so
 * favicons and vector icons decode cleanly on Swing without external ImageIO plugins.
 */
class SwingImageIoCodecImpl : JvmImageCodecBase(installResolverHooks = true) {
    override val codecBackendName: String = "swing-javax-imageio"

    override fun resourceClassLoader(): ClassLoader? = SwingImageIoCodecImpl::class.java.classLoader

    override fun readPlatformResourceBytes(uriOrPath: String): ByteArray? {
        val raw = uriOrPath.trim()
        if (raw.isEmpty()) return null
        if (raw.startsWith("<svg", ignoreCase = true) ||
            (raw.startsWith("<?xml", ignoreCase = true) && raw.contains("<svg", ignoreCase = true))
        ) {
            return raw.encodeToByteArray()
        }
        val clean = raw.substringBefore('?').substringBefore('#').trim()
        if (clean.isEmpty()) return null
        resolveDrawableResourceBytes(clean)?.let { return it }
        val fallback = File("../src/commonMain/resources/$clean")
        if (fallback.isFile && fallback.exists()) {
            runCatching { return fallback.readBytes() }
        }
        return null
    }

    override fun probeMetadataPlatform(bytes: ByteArray, detectedFormat: ImageFormat): ImageMetadata? {
        if (isIcoBytes(bytes, detectedFormat)) {
            val best = selectBestIcoEntry(bytes, targetWidth = null, targetHeight = null)
            if (best != null) {
                return ImageMetadata(
                    width = best.width,
                    height = best.height,
                    format = ImageFormat.ICO,
                    pixelFormat = PixelFormat.RGBA_8888,
                    colorSpace = ImageColorSpace.SRGB,
                    hasAlpha = true
                )
            }
        }
        if (isSvgBytes(bytes, detectedFormat)) {
            val dims = probeSvgDimensions(bytes)
            if (dims != null) {
                return ImageMetadata(
                    width = dims.first,
                    height = dims.second,
                    format = ImageFormat.SVG,
                    pixelFormat = PixelFormat.RGBA_8888,
                    colorSpace = ImageColorSpace.SRGB,
                    hasAlpha = true
                )
            }
        }
        return null
    }

    override fun decodePlatform(
        bytes: ByteArray,
        detectedFormat: ImageFormat?,
        options: ImageDecodeOptions
    ): ImageBuffer? {
        if (isIcoBytes(bytes, detectedFormat)) {
            decodeIco(bytes, options)?.let { return it }
        }
        if (isSvgBytes(bytes, detectedFormat)) {
            decodeSvg(bytes, options)?.let { return it }
        }
        val bimg = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        return bufferedImageToBuffer(bimg)
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

    private fun resolveDrawableResourceBytes(clean: String): ByteArray? {
        val stripped = clean
            .removePrefix("res://")
            .removePrefix("res/")
            .trimStart('/')
        val shortName = stripped.substringAfterLast('/').substringBeforeLast('.')
        val drawable = Resources.getDrawable(clean)
            ?: Resources.getDrawable(stripped)
            ?: shortName.takeIf { it.isNotEmpty() }?.let { Resources.getDrawable(it) }
            ?: return null
        drawable.svgSource?.takeIf { it.isNotBlank() }?.let { return it.encodeToByteArray() }
        drawable.base64Data?.takeIf { it.isNotBlank() }?.let { b64 ->
            runCatching { return Base64.getDecoder().decode(b64) }
        }
        if (drawable.svgPathDataList.isNotEmpty()) {
            val vbW = drawable.viewBoxWidth.takeIf { it > 0f } ?: 24f
            val vbH = drawable.viewBoxHeight.takeIf { it > 0f } ?: 24f
            val paths = drawable.svgPathDataList.joinToString("") { d -> "<path fill=\"#000000\" d=\"$d\"/>" }
            return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $vbW $vbH\">$paths</svg>".encodeToByteArray()
        }
        return null
    }

    private fun bufferedImageToBuffer(bimg: BufferedImage): ImageBuffer? {
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

    private data class IcoEntry(
        val width: Int,
        val height: Int,
        val bitCount: Int,
        val offset: Int,
        val size: Int
    )

    private fun isIcoBytes(bytes: ByteArray, detectedFormat: ImageFormat?): Boolean {
        if (detectedFormat == ImageFormat.ICO) return true
        if (bytes.size < 22) return false
        val reserved = readU16LE(bytes, 0)
        val type = readU16LE(bytes, 2)
        val count = readU16LE(bytes, 4)
        return reserved == 0 && (type == 1 || type == 2) && count in 1..128
    }

    private fun selectBestIcoEntry(bytes: ByteArray, targetWidth: Int?, targetHeight: Int?): IcoEntry? {
        if (bytes.size < 6) return null
        val count = readU16LE(bytes, 4).coerceAtMost(64)
        if (count <= 0) return null
        val entries = ArrayList<IcoEntry>(count)
        for (i in 0 until count) {
            val dirOff = 6 + i * 16
            if (dirOff + 16 > bytes.size) break
            val rawW = bytes[dirOff].toInt() and 0xFF
            val rawH = bytes[dirOff + 1].toInt() and 0xFF
            val w = if (rawW == 0) 256 else rawW
            val h = if (rawH == 0) 256 else rawH
            val bitCount = readU16LE(bytes, dirOff + 6)
            val dataSize = readI32LE(bytes, dirOff + 8)
            val dataOff = readI32LE(bytes, dirOff + 12)
            if (dataSize > 0 && dataOff >= 6 && dataOff.toLong() + dataSize.toLong() <= bytes.size.toLong()) {
                entries.add(IcoEntry(w, h, bitCount, dataOff, dataSize))
            }
        }
        if (entries.isEmpty()) return null
        val want = targetWidth ?: targetHeight
        if (want != null && want > 0) {
            return entries.minWithOrNull(
                compareBy<IcoEntry>(
                    { abs(it.width - want) + abs(it.height - want) },
                    { if (it.width >= want) 0 else 1 },
                    { -it.bitCount }
                )
            )
        }
        return entries.maxWithOrNull(compareBy<IcoEntry>({ it.width * it.height }, { it.bitCount }))
    }

    private fun decodeIco(bytes: ByteArray, options: ImageDecodeOptions): ImageBuffer? {
        val entry = selectBestIcoEntry(bytes, options.targetWidth, options.targetHeight) ?: return null
        val slice = bytes.copyOfRange(entry.offset, entry.offset + entry.size)
        if (slice.size >= 8 &&
            (slice[0].toInt() and 0xFF) == 0x89 &&
            slice[1] == 'P'.code.toByte() &&
            slice[2] == 'N'.code.toByte() &&
            slice[3] == 'G'.code.toByte()
        ) {
            val bimg = runCatching { ImageIO.read(ByteArrayInputStream(slice)) }.getOrNull() ?: return null
            return bufferedImageToBuffer(bimg)
        }
        return decodeIcoDib(slice, entry.width, entry.height)
    }

    private fun decodeIcoDib(dib: ByteArray, entryW: Int, entryH: Int): ImageBuffer? {
        if (dib.size < 40) return null
        val headerSize = readI32LE(dib, 0)
        if (headerSize < 40 || headerSize > dib.size) return null
        val width = readI32LE(dib, 4).takeIf { it > 0 } ?: entryW
        val rawHeight = readI32LE(dib, 8)
        val topDown = rawHeight < 0
        val totalH = abs(rawHeight)
        val height = if (totalH == entryH * 2 || totalH > entryH) (totalH / 2).coerceAtLeast(1) else entryH
        val bitCount = readU16LE(dib, 14)
        val clrUsed = readI32LE(dib, 32)
        val paletteCount = when {
            bitCount <= 8 -> if (clrUsed > 0) clrUsed else (1 shl bitCount)
            else -> 0
        }
        val paletteOffset = headerSize
        val xorOffset = paletteOffset + paletteCount * 4
        if (xorOffset >= dib.size) return null
        val xorStride = ((width * bitCount + 31) / 32) * 4
        val xorSize = xorStride * height
        val andOffset = xorOffset + xorSize
        val andStride = ((width + 31) / 32) * 4

        val out = ImageBuffer(width, height, PixelFormat.RGBA_8888, ImageColorSpace.SRGB)
        var max32Alpha = 0
        for (row in 0 until height) {
            val y = if (topDown) row else (height - 1 - row)
            val xorRow = xorOffset + row * xorStride
            for (x in 0 until width) {
                var r = 0
                var g = 0
                var b = 0
                var a = 255
                when (bitCount) {
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
                    8 -> {
                        val p = xorRow + x
                        val palIdx = if (p < dib.size) (dib[p].toInt() and 0xFF) else 0
                        val cOff = paletteOffset + palIdx * 4
                        if (cOff + 2 < dib.size) {
                            b = dib[cOff].toInt() and 0xFF
                            g = dib[cOff + 1].toInt() and 0xFF
                            r = dib[cOff + 2].toInt() and 0xFF
                        }
                    }
                    4 -> {
                        val p = xorRow + (x ushr 1)
                        val byteVal = if (p < dib.size) (dib[p].toInt() and 0xFF) else 0
                        val palIdx = if ((x and 1) == 0) (byteVal ushr 4) else (byteVal and 0x0F)
                        val cOff = paletteOffset + palIdx * 4
                        if (cOff + 2 < dib.size) {
                            b = dib[cOff].toInt() and 0xFF
                            g = dib[cOff + 1].toInt() and 0xFF
                            r = dib[cOff + 2].toInt() and 0xFF
                        }
                    }
                    1 -> {
                        val p = xorRow + (x ushr 3)
                        val byteVal = if (p < dib.size) (dib[p].toInt() and 0xFF) else 0
                        val palIdx = (byteVal ushr (7 - (x and 7))) and 1
                        val cOff = paletteOffset + palIdx * 4
                        if (cOff + 2 < dib.size) {
                            b = dib[cOff].toInt() and 0xFF
                            g = dib[cOff + 1].toInt() and 0xFF
                            r = dib[cOff + 2].toInt() and 0xFF
                        }
                    }
                    else -> return null
                }
                out.setPixel(x, y, RgbaColor(r, g, b, a / 255f))
            }
        }
        val useAndMask = (bitCount < 32 || max32Alpha == 0) && andOffset + andStride * height <= dib.size
        if (useAndMask) {
            for (row in 0 until height) {
                val y = if (topDown) row else (height - 1 - row)
                val andRow = andOffset + row * andStride
                for (x in 0 until width) {
                    val p = andRow + (x ushr 3)
                    val bit = ((dib[p].toInt() and 0xFF) ushr (7 - (x and 7))) and 1
                    val cur = out.getPixel(x, y)
                    val newA = if (bit == 1) 0f else 1f
                    out.setPixel(x, y, RgbaColor(cur.r, cur.g, cur.b, newA))
                }
            }
        }
        return out
    }

    private fun isSvgBytes(bytes: ByteArray, detectedFormat: ImageFormat?): Boolean {
        if (detectedFormat == ImageFormat.SVG) return true
        val prefixLen = min(bytes.size, 512)
        val head = bytes.decodeToString(startIndex = 0, endIndex = prefixLen).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        return head.startsWith("<svg", ignoreCase = true) ||
            (head.startsWith("<?xml", ignoreCase = true) && head.contains("<svg", ignoreCase = true))
    }

    private fun parseSvgViewport(root: Element): FloatArray {
        val vb = root.getAttribute("viewBox").trim()
        if (vb.isNotEmpty()) {
            val parts = vb.split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
            if (parts.size >= 4 && parts[2] > 0f && parts[3] > 0f) {
                return floatArrayOf(parts[0], parts[1], parts[2], parts[3])
            }
        }
        val w = parseSvgLength(root.getAttribute("width")) ?: 24f
        val h = parseSvgLength(root.getAttribute("height")) ?: 24f
        return floatArrayOf(0f, 0f, max(1f, w), max(1f, h))
    }

    private fun probeSvgDimensions(bytes: ByteArray): Pair<Int, Int>? {
        val doc = parseSvgDocument(bytes) ?: return null
        val vp = parseSvgViewport(doc.documentElement)
        val w = (parseSvgLength(doc.documentElement.getAttribute("width")) ?: vp[2]).roundToInt().coerceAtLeast(1)
        val h = (parseSvgLength(doc.documentElement.getAttribute("height")) ?: vp[3]).roundToInt().coerceAtLeast(1)
        return w to h
    }

    private fun parseSvgDocument(bytes: ByteArray) = runCatching {
        val dbf = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        dbf.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }.getOrNull()?.takeIf { it.documentElement != null }

    private fun decodeSvg(bytes: ByteArray, options: ImageDecodeOptions): ImageBuffer? {
        val doc = parseSvgDocument(bytes) ?: return null
        val root = doc.documentElement
        val vp = parseSvgViewport(root)
        val intrinsicW = (parseSvgLength(root.getAttribute("width")) ?: vp[2]).roundToInt().coerceAtLeast(1)
        val intrinsicH = (parseSvgLength(root.getAttribute("height")) ?: vp[3]).roundToInt().coerceAtLeast(1)
        val (dstW, dstH) = ImageLoader.resolveTargetDimensions(intrinsicW, intrinsicH, options)
        val bimg = BufferedImage(dstW, dstH, BufferedImage.TYPE_INT_ARGB)
        val g2 = bimg.createGraphics()
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val sx = dstW.toDouble() / vp[2].toDouble()
            val sy = dstH.toDouble() / vp[3].toDouble()
            val scale = if (options.preserveAspectRatio) min(sx, sy) else sx
            val ox = (dstW - vp[2] * scale) * 0.5
            val oy = (dstH - vp[3] * scale) * 0.5
            g2.translate(ox - vp[0] * scale, oy - vp[1] * (if (options.preserveAspectRatio) scale else sy))
            g2.scale(scale, if (options.preserveAspectRatio) scale else sy)

            val rootFill = root.getAttribute("fill").takeIf { it.isNotBlank() } ?: "#000000"
            val rootStroke = root.getAttribute("stroke").takeIf { it.isNotBlank() } ?: "none"
            val rootStrokeW = root.getAttribute("stroke-width").toFloatOrNull() ?: 1f
            renderSvgElementChildren(g2, root, rootFill, rootStroke, rootStrokeW, 1f)
        } finally {
            g2.dispose()
        }
        return bufferedImageToBuffer(bimg)
    }

    private fun renderSvgElementChildren(
        g2: Graphics2D,
        parent: Element,
        inheritedFill: String,
        inheritedStroke: String,
        inheritedStrokeW: Float,
        inheritedOpacity: Float
    ) {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            val fill = el.getAttribute("fill").takeIf { it.isNotBlank() } ?: inheritedFill
            val stroke = el.getAttribute("stroke").takeIf { it.isNotBlank() } ?: inheritedStroke
            val strokeW = el.getAttribute("stroke-width").toFloatOrNull() ?: inheritedStrokeW
            val opacity = (el.getAttribute("opacity").toFloatOrNull() ?: 1f) * inheritedOpacity
            when (el.tagName.lowercase()) {
                "g" -> renderSvgElementChildren(g2, el, fill, stroke, strokeW, opacity)
                "path" -> {
                    val d = el.getAttribute("d")
                    if (d.isNotBlank()) paintSvgShape(g2, parseSvgPath(d), fill, stroke, strokeW, opacity, el)
                }
                "rect" -> {
                    val x = el.getAttribute("x").toFloatOrNull() ?: 0f
                    val y = el.getAttribute("y").toFloatOrNull() ?: 0f
                    val w = el.getAttribute("width").toFloatOrNull() ?: 0f
                    val h = el.getAttribute("height").toFloatOrNull() ?: 0f
                    val rx = el.getAttribute("rx").toFloatOrNull() ?: 0f
                    val ry = el.getAttribute("ry").toFloatOrNull() ?: rx
                    if (w > 0f && h > 0f) {
                        val shape = if (rx > 0f || ry > 0f) RoundRectangle2D.Float(x, y, w, h, rx * 2f, ry * 2f)
                        else Rectangle2D.Float(x, y, w, h)
                        paintSvgShape(g2, shape, fill, stroke, strokeW, opacity, el)
                    }
                }
                "circle" -> {
                    val cx = el.getAttribute("cx").toFloatOrNull() ?: 0f
                    val cy = el.getAttribute("cy").toFloatOrNull() ?: 0f
                    val r = el.getAttribute("r").toFloatOrNull() ?: 0f
                    if (r > 0f) paintSvgShape(g2, Ellipse2D.Float(cx - r, cy - r, r * 2f, r * 2f), fill, stroke, strokeW, opacity, el)
                }
                "ellipse" -> {
                    val cx = el.getAttribute("cx").toFloatOrNull() ?: 0f
                    val cy = el.getAttribute("cy").toFloatOrNull() ?: 0f
                    val rx = el.getAttribute("rx").toFloatOrNull() ?: 0f
                    val ry = el.getAttribute("ry").toFloatOrNull() ?: 0f
                    if (rx > 0f && ry > 0f) paintSvgShape(g2, Ellipse2D.Float(cx - rx, cy - ry, rx * 2f, ry * 2f), fill, stroke, strokeW, opacity, el)
                }
                "line" -> {
                    val x1 = el.getAttribute("x1").toFloatOrNull() ?: 0f
                    val y1 = el.getAttribute("y1").toFloatOrNull() ?: 0f
                    val x2 = el.getAttribute("x2").toFloatOrNull() ?: 0f
                    val y2 = el.getAttribute("y2").toFloatOrNull() ?: 0f
                    paintSvgShape(g2, Line2D.Float(x1, y1, x2, y2), "none", stroke, strokeW, opacity, el)
                }
                "polygon", "polyline" -> {
                    val nums = el.getAttribute("points").trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                    if (nums.size >= 4) {
                        val path = Path2D.Float()
                        path.moveTo(nums[0], nums[1])
                        var idx = 2
                        while (idx + 1 < nums.size) {
                            path.lineTo(nums[idx], nums[idx + 1])
                            idx += 2
                        }
                        if (el.tagName.equals("polygon", ignoreCase = true)) path.closePath()
                        val effectiveFill = if (el.tagName.equals("polyline", ignoreCase = true) && !el.hasAttribute("fill")) "none" else fill
                        paintSvgShape(g2, path, effectiveFill, stroke, strokeW, opacity, el)
                    }
                }
            }
        }
    }

    private fun paintSvgShape(
        g2: Graphics2D,
        shape: java.awt.Shape,
        fillSpec: String,
        strokeSpec: String,
        strokeW: Float,
        opacity: Float,
        el: Element
    ) {
        val fillOpacity = (el.getAttribute("fill-opacity").toFloatOrNull() ?: 1f) * opacity
        val strokeOpacity = (el.getAttribute("stroke-opacity").toFloatOrNull() ?: 1f) * opacity
        parseSvgColor(fillSpec, fillOpacity)?.let { c ->
            if (shape is Path2D && el.getAttribute("fill-rule").equals("evenodd", ignoreCase = true)) {
                shape.windingRule = Path2D.WIND_EVEN_ODD
            }
            g2.color = c
            g2.fill(shape)
        }
        parseSvgColor(strokeSpec, strokeOpacity)?.let { c ->
            val cap = when (el.getAttribute("stroke-linecap").lowercase()) {
                "round" -> BasicStroke.CAP_ROUND
                "square" -> BasicStroke.CAP_SQUARE
                else -> BasicStroke.CAP_BUTT
            }
            val join = when (el.getAttribute("stroke-linejoin").lowercase()) {
                "round" -> BasicStroke.JOIN_ROUND
                "bevel" -> BasicStroke.JOIN_BEVEL
                else -> BasicStroke.JOIN_MITER
            }
            g2.color = c
            g2.stroke = BasicStroke(max(0.1f, strokeW), cap, join)
            g2.draw(shape)
        }
    }

    private fun parseSvgLength(raw: String): Float? {
        val cleaned = raw.trim().lowercase().removeSuffix("px").removeSuffix("pt")
        return cleaned.toFloatOrNull()
    }

    private fun parseSvgColor(spec: String, opacity: Float): Color? {
        val trimmed = spec.trim()
        if (trimmed.isEmpty() || trimmed.equals("none", ignoreCase = true) || trimmed.equals("transparent", ignoreCase = true)) {
            return null
        }
        val aScale = opacity.coerceIn(0f, 1f)
        val base: Color = when {
            trimmed.equals("currentColor", ignoreCase = true) -> Color(0, 0, 0, 255)
            trimmed.equals("black", ignoreCase = true) -> Color(0, 0, 0, 255)
            trimmed.equals("white", ignoreCase = true) -> Color(255, 255, 255, 255)
            trimmed.equals("red", ignoreCase = true) -> Color(220, 38, 38, 255)
            trimmed.equals("green", ignoreCase = true) -> Color(22, 163, 74, 255)
            trimmed.equals("blue", ignoreCase = true) -> Color(37, 99, 235, 255)
            trimmed.equals("yellow", ignoreCase = true) -> Color(234, 179, 8, 255)
            trimmed.equals("orange", ignoreCase = true) -> Color(249, 115, 22, 255)
            trimmed.equals("gray", ignoreCase = true) || trimmed.equals("grey", ignoreCase = true) -> Color(128, 128, 128, 255)
            trimmed.startsWith("#") -> {
                val hex = trimmed.substring(1)
                when (hex.length) {
                    3 -> {
                        val r = hex[0].digitToIntOrNull(16)?.let { it * 17 } ?: return null
                        val g = hex[1].digitToIntOrNull(16)?.let { it * 17 } ?: return null
                        val b = hex[2].digitToIntOrNull(16)?.let { it * 17 } ?: return null
                        Color(r, g, b, 255)
                    }
                    6 -> {
                        val rgb = hex.toIntOrNull(16) ?: return null
                        Color((rgb ushr 16) and 0xFF, (rgb ushr 8) and 0xFF, rgb and 0xFF, 255)
                    }
                    8 -> {
                        val rgba = hex.toLongOrNull(16) ?: return null
                        Color(((rgba ushr 24) and 0xFF).toInt(), ((rgba ushr 16) and 0xFF).toInt(), ((rgba ushr 8) and 0xFF).toInt(), (rgba and 0xFF).toInt())
                    }
                    else -> return null
                }
            }
            trimmed.startsWith("rgb", ignoreCase = true) -> {
                val nums = trimmed.substringAfter('(').substringBefore(')').split(',').mapNotNull { it.trim().toFloatOrNull() }
                if (nums.size < 3) return null
                val a = if (nums.size >= 4) (nums[3] * 255f).roundToInt().coerceIn(0, 255) else 255
                Color(nums[0].roundToInt().coerceIn(0, 255), nums[1].roundToInt().coerceIn(0, 255), nums[2].roundToInt().coerceIn(0, 255), a)
            }
            else -> return null
        }
        val finalAlpha = (base.alpha * aScale).roundToInt().coerceIn(0, 255)
        return Color(base.red, base.green, base.blue, finalAlpha)
    }

    private fun parseSvgPath(d: String): Path2D.Float {
        val path = Path2D.Float()
        val tokens = ArrayList<String>()
        var i = 0
        while (i < d.length) {
            val ch = d[i]
            when {
                ch.isWhitespace() || ch == ',' -> i++
                ch.isLetter() -> {
                    tokens.add(ch.toString())
                    i++
                }
                else -> {
                    val start = i
                    if (ch == '+' || ch == '-') i++
                    var seenDot = false
                    var seenExp = false
                    while (i < d.length) {
                        val c = d[i]
                        if (c.isDigit()) {
                            i++
                        } else if (c == '.' && !seenDot && !seenExp) {
                            seenDot = true
                            i++
                        } else if ((c == 'e' || c == 'E') && !seenExp) {
                            seenExp = true
                            i++
                            if (i < d.length && (d[i] == '+' || d[i] == '-')) i++
                        } else {
                            break
                        }
                    }
                    if (i > start) tokens.add(d.substring(start, i)) else i++
                }
            }
        }
        var idx = 0
        var cmd = 'M'
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f
        var lastCpX = 0f
        var lastCpY = 0f
        var prevCmd = ' '
        fun nextNum(): Float? = tokens.getOrNull(idx)?.toFloatOrNull()?.also { idx++ }
        while (idx < tokens.size) {
            val t = tokens[idx]
            if (t.length == 1 && t[0].isLetter()) {
                cmd = t[0]
                idx++
            }
            when (cmd) {
                'M', 'm' -> {
                    val x = nextNum() ?: break
                    val y = nextNum() ?: break
                    val nx = if (cmd == 'm') cx + x else x
                    val ny = if (cmd == 'm') cy + y else y
                    path.moveTo(nx, ny)
                    cx = nx; cy = ny; sx = nx; sy = ny
                    cmd = if (cmd == 'm') 'l' else 'L'
                }
                'L', 'l' -> {
                    val x = nextNum() ?: break
                    val y = nextNum() ?: break
                    val nx = if (cmd == 'l') cx + x else x
                    val ny = if (cmd == 'l') cy + y else y
                    path.lineTo(nx, ny)
                    cx = nx; cy = ny
                }
                'H', 'h' -> {
                    val x = nextNum() ?: break
                    val nx = if (cmd == 'h') cx + x else x
                    path.lineTo(nx, cy)
                    cx = nx
                }
                'V', 'v' -> {
                    val y = nextNum() ?: break
                    val ny = if (cmd == 'v') cy + y else y
                    path.lineTo(cx, ny)
                    cy = ny
                }
                'C', 'c' -> {
                    val x1 = nextNum() ?: break; val y1 = nextNum() ?: break
                    val x2 = nextNum() ?: break; val y2 = nextNum() ?: break
                    val x = nextNum() ?: break; val y = nextNum() ?: break
                    val rel = cmd == 'c'
                    val ax1 = if (rel) cx + x1 else x1; val ay1 = if (rel) cy + y1 else y1
                    val ax2 = if (rel) cx + x2 else x2; val ay2 = if (rel) cy + y2 else y2
                    val ax = if (rel) cx + x else x; val ay = if (rel) cy + y else y
                    path.curveTo(ax1, ay1, ax2, ay2, ax, ay)
                    lastCpX = ax2; lastCpY = ay2; cx = ax; cy = ay
                }
                'S', 's' -> {
                    val x2 = nextNum() ?: break; val y2 = nextNum() ?: break
                    val x = nextNum() ?: break; val y = nextNum() ?: break
                    val rel = cmd == 's'
                    val ax1 = if (prevCmd in charArrayOf('C', 'c', 'S', 's')) 2f * cx - lastCpX else cx
                    val ay1 = if (prevCmd in charArrayOf('C', 'c', 'S', 's')) 2f * cy - lastCpY else cy
                    val ax2 = if (rel) cx + x2 else x2; val ay2 = if (rel) cy + y2 else y2
                    val ax = if (rel) cx + x else x; val ay = if (rel) cy + y else y
                    path.curveTo(ax1, ay1, ax2, ay2, ax, ay)
                    lastCpX = ax2; lastCpY = ay2; cx = ax; cy = ay
                }
                'Q', 'q' -> {
                    val x1 = nextNum() ?: break; val y1 = nextNum() ?: break
                    val x = nextNum() ?: break; val y = nextNum() ?: break
                    val rel = cmd == 'q'
                    val ax1 = if (rel) cx + x1 else x1; val ay1 = if (rel) cy + y1 else y1
                    val ax = if (rel) cx + x else x; val ay = if (rel) cy + y else y
                    path.quadTo(ax1, ay1, ax, ay)
                    lastCpX = ax1; lastCpY = ay1; cx = ax; cy = ay
                }
                'T', 't' -> {
                    val x = nextNum() ?: break; val y = nextNum() ?: break
                    val rel = cmd == 't'
                    val ax1 = if (prevCmd in charArrayOf('Q', 'q', 'T', 't')) 2f * cx - lastCpX else cx
                    val ay1 = if (prevCmd in charArrayOf('Q', 'q', 'T', 't')) 2f * cy - lastCpY else cy
                    val ax = if (rel) cx + x else x; val ay = if (rel) cy + y else y
                    path.quadTo(ax1, ay1, ax, ay)
                    lastCpX = ax1; lastCpY = ay1; cx = ax; cy = ay
                }
                'A', 'a' -> {
                    val rx = nextNum() ?: break; val ry = nextNum() ?: break
                    val rot = nextNum() ?: break; val largeArc = (nextNum() ?: break) != 0f
                    val sweep = (nextNum() ?: break) != 0f
                    val x = nextNum() ?: break; val y = nextNum() ?: break
                    val ax = if (cmd == 'a') cx + x else x; val ay = if (cmd == 'a') cy + y else y
                    appendSvgArc(path, cx.toDouble(), cy.toDouble(), rx.toDouble(), ry.toDouble(), rot.toDouble(), largeArc, sweep, ax.toDouble(), ay.toDouble())
                    cx = ax; cy = ay
                }
                'Z', 'z' -> {
                    path.closePath()
                    cx = sx; cy = sy
                }
                else -> idx++
            }
            prevCmd = cmd
        }
        return path
    }

    private fun appendSvgArc(
        path: Path2D.Float,
        x0: Double,
        y0: Double,
        rxIn: Double,
        ryIn: Double,
        angleDeg: Double,
        largeArcFlag: Boolean,
        sweepFlag: Boolean,
        x: Double,
        y: Double
    ) {
        if (x0 == x && y0 == y) return
        if (rxIn == 0.0 || ryIn == 0.0) {
            path.lineTo(x.toFloat(), y.toFloat())
            return
        }
        val dx2 = (x0 - x) * 0.5
        val dy2 = (y0 - y) * 0.5
        val rad = Math.toRadians(angleDeg % 360.0)
        val cosA = cos(rad)
        val sinA = sin(rad)
        val x1 = cosA * dx2 + sinA * dy2
        val y1 = -sinA * dx2 + cosA * dy2
        var rx = abs(rxIn)
        var ry = abs(ryIn)
        val radiiCheck = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
        if (radiiCheck > 1.0) {
            val s = sqrt(radiiCheck)
            rx *= s; ry *= s
        }
        val prx = rx * rx
        val pry = ry * ry
        val px1 = x1 * x1
        val py1 = y1 * y1
        val sign = if (largeArcFlag == sweepFlag) -1.0 else 1.0
        val denom = prx * py1 + pry * px1
        val sq = if (denom > 0.0) max(0.0, (prx * pry - denom) / denom) else 0.0
        val coef = sign * sqrt(sq)
        val cx1 = coef * ((rx * y1) / ry)
        val cy1 = coef * -((ry * x1) / rx)
        val sx2 = (x0 + x) * 0.5
        val sy2 = (y0 + y) * 0.5
        val cxp = sx2 + (cosA * cx1 - sinA * cy1)
        val cyp = sy2 + (sinA * cx1 + cosA * cy1)
        val ux = (x1 - cx1) / rx
        val uy = (y1 - cy1) / ry
        val vx = (-x1 - cx1) / rx
        val vy = (-y1 - cy1) / ry
        fun vecAngle(ax: Double, ay: Double, bx: Double, by: Double): Double {
            val n = hypot(ax, ay) * hypot(bx, by)
            if (n <= 0.0) return 0.0
            val c = ((ax * bx + ay * by) / n).coerceIn(-1.0, 1.0)
            val ang = acos(c)
            return if (ax * by - ay * bx < 0.0) -ang else ang
        }
        val theta1 = vecAngle(1.0, 0.0, ux, uy)
        var dTheta = vecAngle(ux, uy, vx, vy)
        if (!sweepFlag && dTheta > 0.0) dTheta -= 2.0 * Math.PI
        if (sweepFlag && dTheta < 0.0) dTheta += 2.0 * Math.PI
        val arc = Arc2D.Double(cxp - rx, cyp - ry, rx * 2.0, ry * 2.0, -Math.toDegrees(theta1), -Math.toDegrees(dTheta), Arc2D.OPEN)
        val at = java.awt.geom.AffineTransform.getRotateInstance(rad, cxp, cyp)
        path.append(at.createTransformedShape(arc), true)
    }

    private fun readU16LE(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    private fun readI32LE(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)
}
