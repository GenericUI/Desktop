@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageColorSpace
import com.nexustools.genericui.image.ImageMetadata
import com.nexustools.genericui.image.ImageFormat
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.style.color.RgbaColor
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextAddArc
import platform.CoreGraphics.CGContextAddPath
import platform.CoreGraphics.CGContextBeginPath
import platform.CoreGraphics.CGContextEOFillPath
import platform.CoreGraphics.CGContextFillPath
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextRestoreGState
import platform.CoreGraphics.CGContextSaveGState
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextSetAllowsAntialiasing
import platform.CoreGraphics.CGContextSetLineCap
import platform.CoreGraphics.CGContextSetLineJoin
import platform.CoreGraphics.CGContextSetLineWidth
import platform.CoreGraphics.CGContextSetMiterLimit
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGContextSetRGBStrokeColor
import platform.CoreGraphics.CGContextSetShouldAntialias
import platform.CoreGraphics.CGContextStrokePath
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGLineCap
import platform.CoreGraphics.CGLineJoin
import platform.CoreGraphics.CGMutablePathRef
import platform.CoreGraphics.CGPathAddCurveToPoint
import platform.CoreGraphics.CGPathAddLineToPoint
import platform.CoreGraphics.CGPathAddQuadCurveToPoint
import platform.CoreGraphics.CGPathAddRect
import platform.CoreGraphics.CGPathCloseSubpath
import platform.CoreGraphics.CGPathCreateMutable
import platform.CoreGraphics.CGPathMoveToPoint
import platform.CoreGraphics.CGPathRelease
import platform.CoreGraphics.CGRectMake
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Native CoreGraphics (`CGMutablePathRef` + `CGBitmapContext`) vector SVG rasterizer for `:desktop:macos`.
 *
 * Supports W3C `<svg>` documents with `viewBox`, `<g>`, `<path>`, `<rect>`, `<circle>`, `<ellipse>`,
 * `<line>`, `<polyline>`, and `<polygon>` elements, resolving `currentColor` to white (`#FFFFFF`) by
 * default so runtime icon tinting (`ImageTintMode.SRC_IN` / foreground color multiplication) preserves
 * crisp Retina vector anti-aliasing at any target pixel size.
 */
object MacosSvgCoreGraphicsRasterizer {

    private class SvgPaintStyle(
        val fill: String? = null,
        val stroke: String? = null,
        val strokeWidth: Double = 1.0,
        val fillOpacity: Double = 1.0,
        val strokeOpacity: Double = 1.0,
        val opacity: Double = 1.0,
        val fillRuleEvenOdd: Boolean = false,
        val lineCap: CGLineCap = CGLineCap.kCGLineCapButt,
        val lineJoin: CGLineJoin = CGLineJoin.kCGLineJoinMiter
    )

    fun isSvgBytes(bytes: ByteArray): Boolean {
        if (bytes.size < 5) return false
        val headLen = min(bytes.size, 512)
        val prefix = bytes.decodeToString(0, headLen, throwOnInvalidSequence = false).trimStart()
        return prefix.startsWith("<svg", ignoreCase = true) ||
            (prefix.startsWith("<?xml", ignoreCase = true) && prefix.contains("<svg", ignoreCase = true)) ||
            (prefix.startsWith("<!DOCTYPE svg", ignoreCase = true))
    }

    fun probeMetadata(bytes: ByteArray): ImageMetadata? {
        if (!isSvgBytes(bytes)) return null
        val svg = bytes.decodeToString(throwOnInvalidSequence = false)
        val (_, _, vbW, vbH) = parseViewBoxAndSize(svg)
        return ImageMetadata(
            width = vbW.roundToInt().coerceAtLeast(1),
            height = vbH.roundToInt().coerceAtLeast(1),
            format = ImageFormat.SVG,
            pixelFormat = PixelFormat.RGBA_8888,
            colorSpace = ImageColorSpace.SRGB,
            hasAlpha = true
        )
    }

    fun rasterize(
        bytes: ByteArray,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        currentColor: RgbaColor = RgbaColor(255, 255, 255, 255),
        antialiasing: Boolean = true
    ): ImageBuffer? {
        if (bytes.isEmpty()) return null
        val svg = bytes.decodeToString(throwOnInvalidSequence = false)
        if (!svg.contains("<svg", ignoreCase = true)) return null
        return rasterizeSvgText(
            svg = svg,
            targetWidth = targetWidth,
            targetHeight = targetHeight,
            currentColor = currentColor,
            antialiasing = antialiasing
        )
    }

    fun rasterizeSvgText(
        svg: String,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        currentColor: RgbaColor = RgbaColor(255, 255, 255, 255),
        antialiasing: Boolean = true
    ): ImageBuffer? {
        val (vbMinX, vbMinY, vbW, vbH) = parseViewBoxAndSize(svg)
        val safeVbW = if (vbW > 0.001) vbW else 24.0
        val safeVbH = if (vbH > 0.001) vbH else 24.0
        val aspect = safeVbW / safeVbH

        val outW: Int
        val outH: Int
        when {
            targetWidth != null && targetHeight != null && targetWidth > 0 && targetHeight > 0 -> {
                outW = targetWidth.coerceIn(1, 4096)
                outH = targetHeight.coerceIn(1, 4096)
            }
            targetWidth != null && targetWidth > 0 -> {
                outW = targetWidth.coerceIn(1, 4096)
                outH = (outW / aspect).roundToInt().coerceIn(1, 4096)
            }
            targetHeight != null && targetHeight > 0 -> {
                outH = targetHeight.coerceIn(1, 4096)
                outW = (outH * aspect).roundToInt().coerceIn(1, 4096)
            }
            else -> {
                outW = safeVbW.roundToInt().coerceIn(16, 1024)
                outH = safeVbH.roundToInt().coerceIn(16, 1024)
            }
        }

        val premulBytes = ByteArray(outW * outH * 4)
        val colorSpace = CGColorSpaceCreateDeviceRGB() ?: return null
        try {
            premulBytes.usePinned { pinned ->
                val ctx = CGBitmapContextCreate(
                    data = pinned.addressOf(0),
                    width = outW.convert(),
                    height = outH.convert(),
                    bitsPerComponent = 8u,
                    bytesPerRow = (outW * 4).convert(),
                    space = colorSpace,
                    bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value
                ) ?: return null
                try {
                    CGContextSetAllowsAntialiasing(ctx, antialiasing)
                    CGContextSetShouldAntialias(ctx, antialiasing)

                    // Fit viewBox proportionally inside target dimensions
                    val scale = min(outW.toDouble() / safeVbW, outH.toDouble() / safeVbH)
                    val drawnW = safeVbW * scale
                    val drawnH = safeVbH * scale
                    val padX = (outW.toDouble() - drawnW) * 0.5
                    val padY = (outH.toDouble() - drawnH) * 0.5

                    // Map top-down SVG user coordinates into CoreGraphics bitmap space so memory row 0 = top
                    CGContextTranslateCTM(ctx, padX, outH.toDouble() - padY)
                    CGContextScaleCTM(ctx, scale, -scale)
                    CGContextTranslateCTM(ctx, -vbMinX, -vbMinY)

                    renderSvgElements(ctx, svg, currentColor)
                } finally {
                    CGContextRelease(ctx)
                }
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }

        // Convert premultiplied RGBA to straight RGBA_8888 expected by ImageBuffer
        val outBytes = ByteArray(outW * outH * 4)
        var i = 0
        val total = outBytes.size
        while (i < total) {
            val a = premulBytes[i + 3].toInt() and 0xFF
            if (a == 0) {
                i += 4
                continue
            }
            if (a == 255) {
                outBytes[i] = premulBytes[i]
                outBytes[i + 1] = premulBytes[i + 1]
                outBytes[i + 2] = premulBytes[i + 2]
                outBytes[i + 3] = (-1).toByte()
            } else {
                val r = (((premulBytes[i].toInt() and 0xFF) * 255 + (a shr 1)) / a).coerceIn(0, 255)
                val g = (((premulBytes[i + 1].toInt() and 0xFF) * 255 + (a shr 1)) / a).coerceIn(0, 255)
                val b = (((premulBytes[i + 2].toInt() and 0xFF) * 255 + (a shr 1)) / a).coerceIn(0, 255)
                outBytes[i] = r.toByte()
                outBytes[i + 1] = g.toByte()
                outBytes[i + 2] = b.toByte()
                outBytes[i + 3] = a.toByte()
            }
            i += 4
        }

        return ImageBuffer(
            width = outW,
            height = outH,
            pixelFormat = PixelFormat.RGBA_8888,
            colorSpace = ImageColorSpace.SRGB,
            bytes = outBytes
        )
    }

    private fun parseViewBoxAndSize(svg: String): DoubleArray {
        val svgTagEnd = svg.indexOf('>', svg.indexOf("<svg", ignoreCase = true).coerceAtLeast(0))
        val svgTag = if (svgTagEnd > 0) svg.substring(0, svgTagEnd + 1) else svg
        val attrs = parseXmlAttributes(svgTag)
        val viewBox = attrs["viewBox"] ?: attrs["viewbox"]
        if (!viewBox.isNullOrBlank()) {
            val nums = parseNumberList(viewBox)
            if (nums.size >= 4 && nums[2] > 0.0 && nums[3] > 0.0) {
                return doubleArrayOf(nums[0], nums[1], nums[2], nums[3])
            }
        }
        val w = parseSvgDimension(attrs["width"]) ?: 24.0
        val h = parseSvgDimension(attrs["height"]) ?: 24.0
        return doubleArrayOf(0.0, 0.0, w.coerceAtLeast(1.0), h.coerceAtLeast(1.0))
    }

    private fun parseSvgDimension(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().lowercase()
            .removeSuffix("px")
            .removeSuffix("pt")
            .removeSuffix("%")
            .trim()
        return cleaned.toDoubleOrNull()
    }

    private fun renderSvgElements(
        ctx: platform.CoreGraphics.CGContextRef,
        svg: String,
        currentColor: RgbaColor
    ) {
        val styleStack = ArrayDeque<SvgPaintStyle>()
        styleStack.addLast(SvgPaintStyle(fill = "#000000"))

        var idx = 0
        val len = svg.length
        while (idx < len) {
            val lt = svg.indexOf('<', idx)
            if (lt < 0) break
            if (svg.startsWith("<!--", lt)) {
                val endComment = svg.indexOf("-->", lt + 4)
                idx = if (endComment >= 0) endComment + 3 else len
                continue
            }
            if (svg.startsWith("<?", lt) || svg.startsWith("<!", lt)) {
                val gt = svg.indexOf('>', lt + 2)
                idx = if (gt >= 0) gt + 1 else len
                continue
            }
            val gt = svg.indexOf('>', lt + 1)
            if (gt < 0) break
            val rawTag = svg.substring(lt + 1, gt).trim()
            idx = gt + 1
            if (rawTag.isEmpty()) continue

            if (rawTag.startsWith("/")) {
                val closeName = rawTag.substring(1).trim().lowercase()
                if ((closeName == "g" || closeName == "svg") && styleStack.size > 1) {
                    styleStack.removeLast()
                }
                continue
            }

            val selfClosing = rawTag.endsWith("/")
            val tagBody = if (selfClosing) rawTag.dropLast(1).trim() else rawTag
            val spaceIdx = tagBody.indexOfFirst { it.isWhitespace() }
            val tagName = (if (spaceIdx >= 0) tagBody.substring(0, spaceIdx) else tagBody).lowercase()
            val attrs = if (spaceIdx >= 0) parseXmlAttributes(tagBody.substring(spaceIdx)) else emptyMap()

            val parentStyle = styleStack.last()
            val mergedStyle = mergeStyle(parentStyle, attrs)

            if (tagName == "svg" || tagName == "g") {
                if (!selfClosing) {
                    styleStack.addLast(mergedStyle)
                }
                continue
            }

            when (tagName) {
                "path" -> {
                    val d = attrs["d"]
                    if (!d.isNullOrBlank()) {
                        val path = buildPathFromSvgData(d)
                        if (path != null) {
                            drawCgPath(ctx, path, mergedStyle, currentColor)
                            CGPathRelease(path)
                        }
                    }
                }
                "rect" -> {
                    val x = attrs["x"]?.toDoubleOrNull() ?: 0.0
                    val y = attrs["y"]?.toDoubleOrNull() ?: 0.0
                    val w = attrs["width"]?.toDoubleOrNull() ?: 0.0
                    val h = attrs["height"]?.toDoubleOrNull() ?: 0.0
                    val rx = (attrs["rx"]?.toDoubleOrNull() ?: attrs["ry"]?.toDoubleOrNull() ?: 0.0).coerceAtLeast(0.0)
                    val ry = (attrs["ry"]?.toDoubleOrNull() ?: rx).coerceAtLeast(0.0)
                    if (w > 0.0 && h > 0.0) {
                        val path = buildRoundedRectPath(x, y, w, h, min(rx, w * 0.5), min(ry, h * 0.5))
                        if (path != null) {
                            drawCgPath(ctx, path, mergedStyle, currentColor)
                            CGPathRelease(path)
                        }
                    }
                }
                "circle" -> {
                    val cx = attrs["cx"]?.toDoubleOrNull() ?: 0.0
                    val cy = attrs["cy"]?.toDoubleOrNull() ?: 0.0
                    val r = attrs["r"]?.toDoubleOrNull() ?: 0.0
                    if (r > 0.0) {
                        val path = buildEllipsePath(cx, cy, r, r)
                        if (path != null) {
                            drawCgPath(ctx, path, mergedStyle, currentColor)
                            CGPathRelease(path)
                        }
                    }
                }
                "ellipse" -> {
                    val cx = attrs["cx"]?.toDoubleOrNull() ?: 0.0
                    val cy = attrs["cy"]?.toDoubleOrNull() ?: 0.0
                    val rx = attrs["rx"]?.toDoubleOrNull() ?: 0.0
                    val ry = attrs["ry"]?.toDoubleOrNull() ?: 0.0
                    if (rx > 0.0 && ry > 0.0) {
                        val path = buildEllipsePath(cx, cy, rx, ry)
                        if (path != null) {
                            drawCgPath(ctx, path, mergedStyle, currentColor)
                            CGPathRelease(path)
                        }
                    }
                }
                "line" -> {
                    val x1 = attrs["x1"]?.toDoubleOrNull() ?: 0.0
                    val y1 = attrs["y1"]?.toDoubleOrNull() ?: 0.0
                    val x2 = attrs["x2"]?.toDoubleOrNull() ?: 0.0
                    val y2 = attrs["y2"]?.toDoubleOrNull() ?: 0.0
                    val path = CGPathCreateMutable()
                    if (path != null) {
                        CGPathMoveToPoint(path, null, x1, y1)
                        CGPathAddLineToPoint(path, null, x2, y2)
                        drawCgPath(ctx, path, mergedStyle, currentColor)
                        CGPathRelease(path)
                    }
                }
                "polyline", "polygon" -> {
                    val pts = parseNumberList(attrs["points"].orEmpty())
                    if (pts.size >= 4) {
                        val path = CGPathCreateMutable()
                        if (path != null) {
                            CGPathMoveToPoint(path, null, pts[0], pts[1])
                            var p = 2
                            while (p + 1 < pts.size) {
                                CGPathAddLineToPoint(path, null, pts[p], pts[p + 1])
                                p += 2
                            }
                            if (tagName == "polygon") {
                                CGPathCloseSubpath(path)
                            }
                            drawCgPath(ctx, path, mergedStyle, currentColor)
                            CGPathRelease(path)
                        }
                    }
                }
            }
        }
    }

    private fun drawCgPath(
        ctx: platform.CoreGraphics.CGContextRef,
        path: CGMutablePathRef,
        style: SvgPaintStyle,
        currentColor: RgbaColor
    ) {
        val fillColor = resolvePaintColor(style.fill, currentColor)
        val strokeColor = resolvePaintColor(style.stroke, currentColor)
        if (fillColor != null) {
            val alpha = ((fillColor.a / 255.0) * style.fillOpacity * style.opacity).coerceIn(0.0, 1.0)
            if (alpha > 0.001) {
                CGContextSaveGState(ctx)
                CGContextBeginPath(ctx)
                CGContextAddPath(ctx, path)
                CGContextSetRGBFillColor(
                    ctx,
                    fillColor.r / 255.0,
                    fillColor.g / 255.0,
                    fillColor.b / 255.0,
                    alpha
                )
                if (style.fillRuleEvenOdd) {
                    CGContextEOFillPath(ctx)
                } else {
                    CGContextFillPath(ctx)
                }
                CGContextRestoreGState(ctx)
            }
        }
        if (strokeColor != null && style.strokeWidth > 0.0) {
            val alpha = ((strokeColor.a / 255.0) * style.strokeOpacity * style.opacity).coerceIn(0.0, 1.0)
            if (alpha > 0.001) {
                CGContextSaveGState(ctx)
                CGContextBeginPath(ctx)
                CGContextAddPath(ctx, path)
                CGContextSetLineWidth(ctx, style.strokeWidth)
                CGContextSetLineCap(ctx, style.lineCap)
                CGContextSetLineJoin(ctx, style.lineJoin)
                CGContextSetMiterLimit(ctx, 4.0)
                CGContextSetRGBStrokeColor(
                    ctx,
                    strokeColor.r / 255.0,
                    strokeColor.g / 255.0,
                    strokeColor.b / 255.0,
                    alpha
                )
                CGContextStrokePath(ctx)
                CGContextRestoreGState(ctx)
            }
        }
    }

    private fun mergeStyle(parent: SvgPaintStyle, attrs: Map<String, String>): SvgPaintStyle {
        val inlineMap = attrs["style"]?.let { parseInlineCss(it) } ?: emptyMap()
        fun prop(name: String): String? = inlineMap[name] ?: attrs[name]

        val fill = prop("fill") ?: parent.fill
        val stroke = prop("stroke") ?: parent.stroke
        val strokeWidth = prop("stroke-width")?.let { parseSvgDimension(it) } ?: parent.strokeWidth
        val fillOpacity = prop("fill-opacity")?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: parent.fillOpacity
        val strokeOpacity = prop("stroke-opacity")?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: parent.strokeOpacity
        val localOpacity = prop("opacity")?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 1.0
        val fillRule = prop("fill-rule")?.lowercase()
        val evenOdd = if (fillRule != null) fillRule == "evenodd" else parent.fillRuleEvenOdd
        val cap = when (prop("stroke-linecap")?.lowercase()) {
            "round" -> CGLineCap.kCGLineCapRound
            "square" -> CGLineCap.kCGLineCapSquare
            "butt" -> CGLineCap.kCGLineCapButt
            else -> parent.lineCap
        }
        val join = when (prop("stroke-linejoin")?.lowercase()) {
            "round" -> CGLineJoin.kCGLineJoinRound
            "bevel" -> CGLineJoin.kCGLineJoinBevel
            "miter" -> CGLineJoin.kCGLineJoinMiter
            else -> parent.lineJoin
        }
        return SvgPaintStyle(
            fill = fill,
            stroke = stroke,
            strokeWidth = strokeWidth,
            fillOpacity = fillOpacity,
            strokeOpacity = strokeOpacity,
            opacity = parent.opacity * localOpacity,
            fillRuleEvenOdd = evenOdd,
            lineCap = cap,
            lineJoin = join
        )
    }

    private fun resolvePaintColor(spec: String?, currentColor: RgbaColor): RgbaColor? {
        val raw = spec?.trim() ?: return null
        if (raw.isEmpty() || raw.equals("none", ignoreCase = true) || raw.equals("transparent", ignoreCase = true)) {
            return null
        }
        if (raw.equals("currentColor", ignoreCase = true)) {
            return currentColor
        }
        if (raw.startsWith("#")) {
            val hex = raw.substring(1)
            return when (hex.length) {
                3 -> {
                    val r = hex[0].digitToIntOrNull(16) ?: return currentColor
                    val g = hex[1].digitToIntOrNull(16) ?: return currentColor
                    val b = hex[2].digitToIntOrNull(16) ?: return currentColor
                    RgbaColor(r * 17, g * 17, b * 17, 255)
                }
                4 -> {
                    val r = hex[0].digitToIntOrNull(16) ?: return currentColor
                    val g = hex[1].digitToIntOrNull(16) ?: return currentColor
                    val b = hex[2].digitToIntOrNull(16) ?: return currentColor
                    val a = hex[3].digitToIntOrNull(16) ?: return currentColor
                    RgbaColor(r * 17, g * 17, b * 17, a * 17)
                }
                6 -> {
                    val v = hex.toLongOrNull(16) ?: return currentColor
                    RgbaColor(
                        r = ((v shr 16) and 0xFF).toInt(),
                        g = ((v shr 8) and 0xFF).toInt(),
                        b = (v and 0xFF).toInt(),
                        a = 255
                    )
                }
                8 -> {
                    val v = hex.toLongOrNull(16) ?: return currentColor
                    RgbaColor(
                        r = ((v shr 24) and 0xFF).toInt(),
                        g = ((v shr 16) and 0xFF).toInt(),
                        b = ((v shr 8) and 0xFF).toInt(),
                        a = (v and 0xFF).toInt()
                    )
                }
                else -> currentColor
            }
        }
        if (raw.startsWith("rgb", ignoreCase = true)) {
            val open = raw.indexOf('(')
            val close = raw.lastIndexOf(')')
            if (open > 0 && close > open) {
                val nums = parseNumberList(raw.substring(open + 1, close))
                if (nums.size >= 3) {
                    val a = if (nums.size >= 4) {
                        if (nums[3] <= 1.0) (nums[3] * 255.0).roundToInt() else nums[3].roundToInt()
                    } else {
                        255
                    }
                    return RgbaColor(
                        r = nums[0].roundToInt().coerceIn(0, 255),
                        g = nums[1].roundToInt().coerceIn(0, 255),
                        b = nums[2].roundToInt().coerceIn(0, 255),
                        a = a.coerceIn(0, 255)
                    )
                }
            }
        }
        return when (raw.lowercase()) {
            "white" -> RgbaColor(255, 255, 255, 255)
            "black" -> RgbaColor(0, 0, 0, 255)
            "gray", "grey" -> RgbaColor(128, 128, 128, 255)
            "red" -> RgbaColor(239, 68, 68, 255)
            "green" -> RgbaColor(34, 197, 94, 255)
            "blue" -> RgbaColor(59, 130, 246, 255)
            "yellow" -> RgbaColor(250, 204, 21, 255)
            "cyan" -> RgbaColor(6, 182, 212, 255)
            "magenta" -> RgbaColor(217, 70, 239, 255)
            else -> currentColor
        }
    }

    private fun buildRoundedRectPath(
        x: Double,
        y: Double,
        w: Double,
        h: Double,
        rx: Double,
        ry: Double
    ): CGMutablePathRef? {
        val path = CGPathCreateMutable() ?: return null
        if (rx <= 0.001 || ry <= 0.001) {
            CGPathAddRect(path, null, CGRectMake(x, y, w, h))
            return path
        }
        val k = 0.5522847498307936
        val kx = rx * k
        val ky = ry * k
        CGPathMoveToPoint(path, null, x + rx, y)
        CGPathAddLineToPoint(path, null, x + w - rx, y)
        CGPathAddCurveToPoint(path, null, x + w - rx + kx, y, x + w, y + ry - ky, x + w, y + ry)
        CGPathAddLineToPoint(path, null, x + w, y + h - ry)
        CGPathAddCurveToPoint(path, null, x + w, y + h - ry + ky, x + w - rx + kx, y + h, x + w - rx, y + h)
        CGPathAddLineToPoint(path, null, x + rx, y + h)
        CGPathAddCurveToPoint(path, null, x + rx - kx, y + h, x, y + h - ry + ky, x, y + h - ry)
        CGPathAddLineToPoint(path, null, x, y + ry)
        CGPathAddCurveToPoint(path, null, x, y + ry - ky, x + rx - kx, y, x + rx, y)
        CGPathCloseSubpath(path)
        return path
    }

    private fun buildEllipsePath(cx: Double, cy: Double, rx: Double, ry: Double): CGMutablePathRef? {
        val path = CGPathCreateMutable() ?: return null
        val k = 0.5522847498307936
        val kx = rx * k
        val ky = ry * k
        CGPathMoveToPoint(path, null, cx + rx, cy)
        CGPathAddCurveToPoint(path, null, cx + rx, cy + ky, cx + kx, cy + ry, cx, cy + ry)
        CGPathAddCurveToPoint(path, null, cx - kx, cy + ry, cx - rx, cy + ky, cx - rx, cy)
        CGPathAddCurveToPoint(path, null, cx - rx, cy - ky, cx - kx, cy - ry, cx, cy - ry)
        CGPathAddCurveToPoint(path, null, cx + kx, cy - ry, cx + rx, cy - ky, cx + rx, cy)
        CGPathCloseSubpath(path)
        return path
    }

    private fun buildPathFromSvgData(d: String): CGMutablePathRef? {
        val path = CGPathCreateMutable() ?: return null
        var i = 0
        val n = d.length
        var cmd = ' '
        var curX = 0.0
        var curY = 0.0
        var startX = 0.0
        var startY = 0.0
        var lastCtrlX = 0.0
        var lastCtrlY = 0.0
        var prevCmd = ' '

        fun skipSeparators() {
            while (i < n && (d[i].isWhitespace() || d[i] == ',')) {
                i++
            }
        }

        fun hasNextNumber(): Boolean {
            skipSeparators()
            if (i >= n) return false
            val c = d[i]
            return c.isDigit() || c == '-' || c == '+' || c == '.'
        }

        fun readNumber(): Double {
            skipSeparators()
            val start = i
            if (i < n && (d[i] == '+' || d[i] == '-')) i++
            var seenDot = false
            while (i < n) {
                val c = d[i]
                if (c.isDigit()) {
                    i++
                } else if (c == '.' && !seenDot) {
                    seenDot = true
                    i++
                } else {
                    break
                }
            }
            if (i < n && (d[i] == 'e' || d[i] == 'E')) {
                i++
                if (i < n && (d[i] == '+' || d[i] == '-')) i++
                while (i < n && d[i].isDigit()) i++
            }
            return d.substring(start, i).toDoubleOrNull() ?: 0.0
        }

        fun readArcFlag(): Int {
            skipSeparators()
            if (i < n && (d[i] == '0' || d[i] == '1')) {
                val v = d[i] - '0'
                i++
                return v
            }
            return if (readNumber() != 0.0) 1 else 0
        }

        while (i < n) {
            skipSeparators()
            if (i >= n) break
            val ch = d[i]
            if (ch.isLetter()) {
                cmd = ch
                i++
            } else if (cmd == ' ') {
                break
            }

            when (cmd) {
                'M', 'm' -> {
                    val rel = cmd == 'm'
                    var first = true
                    while (hasNextNumber()) {
                        val x = readNumber() + if (rel) curX else 0.0
                        val y = readNumber() + if (rel) curY else 0.0
                        if (first) {
                            CGPathMoveToPoint(path, null, x, y)
                            startX = x
                            startY = y
                            first = false
                        } else {
                            CGPathAddLineToPoint(path, null, x, y)
                        }
                        curX = x
                        curY = y
                        lastCtrlX = curX
                        lastCtrlY = curY
                        prevCmd = cmd
                    }
                }
                'L', 'l' -> {
                    val rel = cmd == 'l'
                    while (hasNextNumber()) {
                        val x = readNumber() + if (rel) curX else 0.0
                        val y = readNumber() + if (rel) curY else 0.0
                        CGPathAddLineToPoint(path, null, x, y)
                        curX = x
                        curY = y
                        lastCtrlX = curX
                        lastCtrlY = curY
                        prevCmd = cmd
                    }
                }
                'H', 'h' -> {
                    val rel = cmd == 'h'
                    while (hasNextNumber()) {
                        val x = readNumber() + if (rel) curX else 0.0
                        CGPathAddLineToPoint(path, null, x, curY)
                        curX = x
                        lastCtrlX = curX
                        lastCtrlY = curY
                        prevCmd = cmd
                    }
                }
                'V', 'v' -> {
                    val rel = cmd == 'v'
                    while (hasNextNumber()) {
                        val y = readNumber() + if (rel) curY else 0.0
                        CGPathAddLineToPoint(path, null, curX, y)
                        curY = y
                        lastCtrlX = curX
                        lastCtrlY = curY
                        prevCmd = cmd
                    }
                }
                'C', 'c' -> {
                    val rel = cmd == 'c'
                    while (hasNextNumber()) {
                        val x1 = readNumber() + if (rel) curX else 0.0
                        val y1 = readNumber() + if (rel) curY else 0.0
                        val x2 = readNumber() + if (rel) curX else 0.0
                        val y2 = readNumber() + if (rel) curY else 0.0
                        val x = readNumber() + if (rel) curX else 0.0
                        val y = readNumber() + if (rel) curY else 0.0
                        CGPathAddCurveToPoint(path, null, x1, y1, x2, y2, x, y)
                        lastCtrlX = x2
                        lastCtrlY = y2
                        curX = x
                        curY = y
                        prevCmd = cmd
                    }
                }
                'S', 's' -> {
                    val rel = cmd == 's'
                    while (hasNextNumber()) {
                        val x1 = if (prevCmd in "CcSs") 2.0 * curX - lastCtrlX else curX
                        val y1 = if (prevCmd in "CcSs") 2.0 * curY - lastCtrlY else curY
                        val x2 = readNumber() + if (rel) curX else 0.0
                        val y2 = readNumber() + if (rel) curY else 0.0
                        val x = readNumber() + if (rel) curX else 0.0
                        val y = readNumber() + if (rel) curY else 0.0
                        CGPathAddCurveToPoint(path, null, x1, y1, x2, y2, x, y)
                        lastCtrlX = x2
                        lastCtrlY = y2
                        curX = x
                        curY = y
                        prevCmd = cmd
                    }
                }
                'Q', 'q' -> {
                    val rel = cmd == 'q'
                    while (hasNextNumber()) {
                        val cx = readNumber() + if (rel) curX else 0.0
                        val cy = readNumber() + if (rel) curY else 0.0
                        val x = readNumber() + if (rel) curX else 0.0
                        val y = readNumber() + if (rel) curY else 0.0
                        CGPathAddQuadCurveToPoint(path, null, cx, cy, x, y)
                        lastCtrlX = cx
                        lastCtrlY = cy
                        curX = x
                        curY = y
                        prevCmd = cmd
                    }
                }
                'T', 't' -> {
                    val rel = cmd == 't'
                    while (hasNextNumber()) {
                        val cx = if (prevCmd in "QqTt") 2.0 * curX - lastCtrlX else curX
                        val cy = if (prevCmd in "QqTt") 2.0 * curY - lastCtrlY else curY
                        val x = readNumber() + if (rel) curX else 0.0
                        val y = readNumber() + if (rel) curY else 0.0
                        CGPathAddQuadCurveToPoint(path, null, cx, cy, x, y)
                        lastCtrlX = cx
                        lastCtrlY = cy
                        curX = x
                        curY = y
                        prevCmd = cmd
                    }
                }
                'A', 'a' -> {
                    val rel = cmd == 'a'
                    while (hasNextNumber()) {
                        val rx = abs(readNumber())
                        val ry = abs(readNumber())
                        val xAxisRotation = readNumber()
                        val largeArc = readArcFlag() != 0
                        val sweep = readArcFlag() != 0
                        val x = readNumber() + if (rel) curX else 0.0
                        val y = readNumber() + if (rel) curY else 0.0
                        appendSvgArc(path, curX, curY, rx, ry, xAxisRotation, largeArc, sweep, x, y)
                        curX = x
                        curY = y
                        lastCtrlX = curX
                        lastCtrlY = curY
                        prevCmd = cmd
                    }
                }
                'Z', 'z' -> {
                    CGPathCloseSubpath(path)
                    curX = startX
                    curY = startY
                    lastCtrlX = curX
                    lastCtrlY = curY
                    prevCmd = cmd
                    cmd = ' '
                }
                else -> {
                    i++
                }
            }
        }
        return path
    }

    private fun appendSvgArc(
        path: CGMutablePathRef,
        x0: Double,
        y0: Double,
        rxIn: Double,
        ryIn: Double,
        xAxisRotationDeg: Double,
        largeArcFlag: Boolean,
        sweepFlag: Boolean,
        x: Double,
        y: Double
    ) {
        if (x0 == x && y0 == y) return
        if (rxIn <= 0.00001 || ryIn <= 0.00001) {
            CGPathAddLineToPoint(path, null, x, y)
            return
        }
        var rx = rxIn
        var ry = ryIn
        val phi = xAxisRotationDeg * PI / 180.0
        val cosPhi = cos(phi)
        val sinPhi = sin(phi)

        val dx2 = (x0 - x) * 0.5
        val dy2 = (y0 - y) * 0.5
        val x1p = cosPhi * dx2 + sinPhi * dy2
        val y1p = -sinPhi * dx2 + cosPhi * dy2

        var rxSq = rx * rx
        var rySq = ry * ry
        val x1pSq = x1p * x1p
        val y1pSq = y1p * y1p

        val radiiCheck = x1pSq / rxSq + y1pSq / rySq
        if (radiiCheck > 1.0) {
            val scale = sqrt(radiiCheck)
            rx *= scale
            ry *= scale
            rxSq = rx * rx
            rySq = ry * ry
        }

        val sign = if (largeArcFlag == sweepFlag) -1.0 else 1.0
        val num = max(0.0, rxSq * rySq - rxSq * y1pSq - rySq * x1pSq)
        val den = rxSq * y1pSq + rySq * x1pSq
        val coef = sign * (if (den > 0.0) sqrt(num / den) else 0.0)
        val cxp = coef * (rx * y1p / ry)
        val cyp = coef * -(ry * x1p / rx)

        val cx = cosPhi * cxp - sinPhi * cyp + (x0 + x) * 0.5
        val cy = sinPhi * cxp + cosPhi * cyp + (y0 + y) * 0.5

        fun vectorAngle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = hypot(ux, uy) * hypot(vx, vy)
            val cosA = if (len > 0.0) (dot / len).coerceIn(-1.0, 1.0) else 1.0
            val a = kotlin.math.acos(cosA)
            return if (ux * vy - uy * vx < 0.0) -a else a
        }

        val ux = (x1p - cxp) / rx
        val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx
        val vy = (-y1p - cyp) / ry

        val theta1 = vectorAngle(1.0, 0.0, ux, uy)
        var dTheta = vectorAngle(ux, uy, vx, vy)
        if (!sweepFlag && dTheta > 0.0) {
            dTheta -= 2.0 * PI
        } else if (sweepFlag && dTheta < 0.0) {
            dTheta += 2.0 * PI
        }

        val segments = max(1, ceil(abs(dTheta) / (PI * 0.5)).toInt())
        val delta = dTheta / segments.toDouble()
        val alpha = (4.0 / 3.0) * tan(delta * 0.25)

        var t1 = theta1
        for (seg in 0 until segments) {
            val t2 = t1 + delta
            val cos1 = cos(t1)
            val sin1 = sin(t1)
            val cos2 = cos(t2)
            val sin2 = sin(t2)

            val e1x = rx * (cos1 - alpha * sin1)
            val e1y = ry * (sin1 + alpha * cos1)
            val e2x = rx * (cos2 + alpha * sin2)
            val e2y = ry * (sin2 - alpha * cos2)
            val ex = rx * cos2
            val ey = ry * sin2

            val cp1x = cx + cosPhi * e1x - sinPhi * e1y
            val cp1y = cy + sinPhi * e1x + cosPhi * e1y
            val cp2x = cx + cosPhi * e2x - sinPhi * e2y
            val cp2y = cy + sinPhi * e2x + cosPhi * e2y
            val endX = cx + cosPhi * ex - sinPhi * ey
            val endY = cy + sinPhi * ex + cosPhi * ey

            CGPathAddCurveToPoint(path, null, cp1x, cp1y, cp2x, cp2y, endX, endY)
            t1 = t2
        }
    }

    private fun parseXmlAttributes(fragment: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        val n = fragment.length
        while (i < n) {
            while (i < n && (fragment[i].isWhitespace() || fragment[i] == '<' || fragment[i] == '>' || fragment[i] == '/')) {
                i++
            }
            if (i >= n) break
            val nameStart = i
            while (i < n && !fragment[i].isWhitespace() && fragment[i] != '=' && fragment[i] != '>' && fragment[i] != '/') {
                i++
            }
            val name = fragment.substring(nameStart, i).trim()
            while (i < n && fragment[i].isWhitespace()) i++
            if (i >= n || fragment[i] != '=') continue
            i++ // '='
            while (i < n && fragment[i].isWhitespace()) i++
            if (i >= n) break
            val quote = fragment[i]
            if (quote == '"' || quote == '\'') {
                i++
                val valStart = i
                while (i < n && fragment[i] != quote) i++
                val value = fragment.substring(valStart, i)
                if (i < n && fragment[i] == quote) i++
                if (name.isNotEmpty()) out[name] = value
            } else {
                val valStart = i
                while (i < n && !fragment[i].isWhitespace() && fragment[i] != '>' && fragment[i] != '/') i++
                val value = fragment.substring(valStart, i)
                if (name.isNotEmpty()) out[name] = value
            }
        }
        return out
    }

    private fun parseInlineCss(style: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (part in style.split(';')) {
            val colon = part.indexOf(':')
            if (colon > 0) {
                val k = part.substring(0, colon).trim().lowercase()
                val v = part.substring(colon + 1).trim()
                if (k.isNotEmpty() && v.isNotEmpty()) {
                    out[k] = v
                }
            }
        }
        return out
    }

    private fun parseNumberList(input: String): DoubleArray {
        if (input.isEmpty()) return DoubleArray(0)
        val list = ArrayList<Double>(8)
        var i = 0
        val n = input.length
        while (i < n) {
            while (i < n && (input[i].isWhitespace() || input[i] == ',')) i++
            if (i >= n) break
            val start = i
            if (input[i] == '+' || input[i] == '-') i++
            var seenDot = false
            while (i < n) {
                val c = input[i]
                if (c.isDigit()) {
                    i++
                } else if (c == '.' && !seenDot) {
                    seenDot = true
                    i++
                } else {
                    break
                }
            }
            if (i < n && (input[i] == 'e' || input[i] == 'E')) {
                i++
                if (i < n && (input[i] == '+' || input[i] == '-')) i++
                while (i < n && input[i].isDigit()) i++
            }
            if (i > start) {
                input.substring(start, i).toDoubleOrNull()?.let { list.add(it) }
            } else {
                i++
            }
        }
        return list.toDoubleArray()
    }
}
