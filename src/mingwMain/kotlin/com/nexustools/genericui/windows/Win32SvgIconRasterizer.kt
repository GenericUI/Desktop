package com.nexustools.genericui.windows

import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.style.color.RgbaColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin 2D SVG icon rasterizer for `:desktop:windows` (`WindowsWicImageCodecNativeImpl`).
 *
 * Supports `<svg viewBox>`, `<defs>` (`<linearGradient>`, `<radialGradient>`, `<stop>`,
 * `gradientUnits="userSpaceOnUse" | "objectBoundingBox"`), `<g>` inherited styles & transforms,
 * `<rect>` (including `rx`/`ry` rounded corners), `<circle>`, `<ellipse>`, `<line>`,
 * `<polygon>`, `<polyline>`, `<path d="...">` (`M/L/H/V/C/S/Q/T/A/Z`), `url(#id)` paints,
 * fill rules, stroke widths/caps/opacity, and 4x4 supersampled anti-aliasing.
 */
object Win32SvgIconRasterizer {

    private class GradientStop(
        val offset: Float,
        val color: RgbaColor
    )

    private sealed class PaintSpec {
        data object None : PaintSpec()
        class Solid(val color: RgbaColor) : PaintSpec()
        class LinearGrad(val def: LinearGradientDef) : PaintSpec()
        class RadialGrad(val def: RadialGradientDef) : PaintSpec()
    }

    private class LinearGradientDef(
        val id: String,
        val x1: Float,
        val y1: Float,
        val x1Pct: Boolean,
        val x2: Float,
        val y2: Float,
        val y1Pct: Boolean,
        val x2Pct: Boolean,
        val y2Pct: Boolean,
        val userSpaceOnUse: Boolean,
        val stops: List<GradientStop>
    )

    private class RadialGradientDef(
        val id: String,
        val cx: Float,
        val cy: Float,
        val r: Float,
        val cxPct: Boolean,
        val cyPct: Boolean,
        val rPct: Boolean,
        val userSpaceOnUse: Boolean,
        val stops: List<GradientStop>
    )

    private class InheritedStyle(
        val fillRaw: String? = null,
        val fillOpacity: Float = 1f,
        val fillRule: String = "nonzero",
        val strokeRaw: String? = null,
        val strokeWidth: Float? = null,
        val strokeOpacity: Float = 1f,
        val strokeLineCap: String = "butt",
        val opacity: Float = 1f
    )

    private class Mat2D(
        val a: Float = 1f,
        val b: Float = 0f,
        val c: Float = 0f,
        val d: Float = 1f,
        val e: Float = 0f,
        val f: Float = 0f
    ) {
        fun mapX(x: Float, y: Float): Float = a * x + c * y + e
        fun mapY(x: Float, y: Float): Float = b * x + d * y + f
        fun approxScale(): Float {
            val sx = sqrt(a * a + b * b)
            val sy = sqrt(c * c + d * d)
            return ((sx + sy) * 0.5f).coerceAtLeast(0.001f)
        }
        fun mul(o: Mat2D): Mat2D = Mat2D(
            a = a * o.a + c * o.b,
            b = b * o.a + d * o.b,
            c = a * o.c + c * o.d,
            d = b * o.c + d * o.d,
            e = a * o.e + c * o.f + e,
            f = b * o.e + d * o.f + f
        )
    }

    private class Subpath(
        val xs: FloatArray,
        val ys: FloatArray,
        val closed: Boolean
    )

    private class DrawStyle(
        val fill: PaintSpec,
        val fillOpacity: Float,
        val evenOdd: Boolean,
        val stroke: PaintSpec,
        val strokeWidth: Float,
        val strokeOpacity: Float,
        val roundCap: Boolean,
        val squareCap: Boolean
    )

    private sealed class SvgShape(val style: DrawStyle) {
        class RoundedRect(
            val x: Float,
            val y: Float,
            val w: Float,
            val h: Float,
            val rx: Float,
            val ry: Float,
            style: DrawStyle
        ) : SvgShape(style)

        class Circle(
            val cx: Float,
            val cy: Float,
            val r: Float,
            style: DrawStyle
        ) : SvgShape(style)

        class Ellipse(
            val cx: Float,
            val cy: Float,
            val rx: Float,
            val ry: Float,
            style: DrawStyle
        ) : SvgShape(style)

        class PolyPath(
            val subpaths: List<Subpath>,
            val minX: Float,
            val minY: Float,
            val maxX: Float,
            val maxY: Float,
            style: DrawStyle
        ) : SvgShape(style)
    }

    private class XmlToken(
        val name: String,
        val isClosing: Boolean,
        val isSelfClosing: Boolean,
        val attrs: Map<String, String>
    )

    /**
     * Rasterizes [svgMarkup] to an ARGB [ImageBuffer] of size `[widthPx] x [heightPx]`.
     * Returns `null` if [svgMarkup] does not contain a valid `<svg>` root.
     */
    fun rasterize(svgMarkup: String, widthPx: Int, heightPx: Int): ImageBuffer? {
        if (widthPx <= 0 || heightPx <= 0) return null
        if (!svgMarkup.contains("<svg", ignoreCase = true)) return null

        val tokens = tokenizeSvgXml(svgMarkup)
        val svgToken = tokens.firstOrNull { !it.isClosing && it.name.equals("svg", ignoreCase = true) }
            ?: return null

        val viewBox = parseViewBox(svgToken.attrs)
        val vbX = viewBox[0]
        val vbY = viewBox[1]
        val vbW = viewBox[2].coerceAtLeast(1f)
        val vbH = viewBox[3].coerceAtLeast(1f)

        val linearGrads = HashMap<String, LinearGradientDef>()
        val radialGrads = HashMap<String, RadialGradientDef>()
        val shapes = ArrayList<SvgShape>()

        val styleStack = ArrayList<InheritedStyle>()
        styleStack.add(InheritedStyle())
        val transformStack = ArrayList<Mat2D>()
        transformStack.add(Mat2D())

        var defsDepth = 0
        var activeLinId: String? = null
        var activeLinAttrs: Map<String, String>? = null
        val activeLinStops = ArrayList<GradientStop>()
        var activeRadId: String? = null
        var activeRadAttrs: Map<String, String>? = null
        val activeRadStops = ArrayList<GradientStop>()

        for (tok in tokens) {
            val tag = tok.name.lowercase()
            if (tag == "svg") {
                if (!tok.isClosing && !tok.isSelfClosing) {
                    styleStack.add(mergeStyle(styleStack.last(), tok.attrs))
                } else if (tok.isClosing && styleStack.size > 1) {
                    styleStack.removeAt(styleStack.lastIndex)
                }
                continue
            }
            if (tag == "defs" || tag == "clippath" || tag == "mask" || tag == "pattern" || tag == "style") {
                if (!tok.isClosing && !tok.isSelfClosing) defsDepth++
                else if (tok.isClosing && defsDepth > 0) defsDepth--
                continue
            }
            if (tag == "lineargradient") {
                if (!tok.isClosing) {
                    val id = tok.attrs["id"]?.trim()
                    if (!tok.isSelfClosing && !id.isNullOrEmpty()) {
                        activeLinId = id
                        activeLinAttrs = tok.attrs
                        activeLinStops.clear()
                    }
                } else if (activeLinId != null && activeLinAttrs != null) {
                    buildLinearGrad(activeLinId!!, activeLinAttrs!!, activeLinStops.toList())?.let {
                        linearGrads[it.id] = it
                    }
                    activeLinId = null
                    activeLinAttrs = null
                    activeLinStops.clear()
                }
                continue
            }
            if (tag == "radialgradient") {
                if (!tok.isClosing) {
                    val id = tok.attrs["id"]?.trim()
                    if (!tok.isSelfClosing && !id.isNullOrEmpty()) {
                        activeRadId = id
                        activeRadAttrs = tok.attrs
                        activeRadStops.clear()
                    }
                } else if (activeRadId != null && activeRadAttrs != null) {
                    buildRadialGrad(activeRadId!!, activeRadAttrs!!, activeRadStops.toList())?.let {
                        radialGrads[it.id] = it
                    }
                    activeRadId = null
                    activeRadAttrs = null
                    activeRadStops.clear()
                }
                continue
            }
            if (tag == "stop" && !tok.isClosing) {
                parseStop(tok.attrs)?.let { stop ->
                    if (activeLinId != null) activeLinStops.add(stop)
                    else if (activeRadId != null) activeRadStops.add(stop)
                }
                continue
            }
            if (defsDepth > 0) continue

            if (tag == "g") {
                if (!tok.isClosing && !tok.isSelfClosing) {
                    styleStack.add(mergeStyle(styleStack.last(), tok.attrs))
                    val t = parseTransform(tok.attrs["transform"])
                    transformStack.add(if (t != null) transformStack.last().mul(t) else transformStack.last())
                } else if (tok.isClosing) {
                    if (styleStack.size > 1) styleStack.removeAt(styleStack.lastIndex)
                    if (transformStack.size > 1) transformStack.removeAt(transformStack.lastIndex)
                }
                continue
            }
            if (tok.isClosing) continue

            val elemStyle = mergeStyle(styleStack.last(), tok.attrs)
            val elemMat = parseTransform(tok.attrs["transform"])?.let { transformStack.last().mul(it) }
                ?: transformStack.last()
            val scaleFactor = elemMat.approxScale()
            val drawStyle = resolveDrawStyle(elemStyle, scaleFactor, linearGrads, radialGrads)

            parseShape(tag, tok.attrs, drawStyle, elemMat)?.let { shapes.add(it) }
        }

        if (shapes.isEmpty()) return null

        val outR = FloatArray(widthPx * heightPx)
        val outG = FloatArray(widthPx * heightPx)
        val outB = FloatArray(widthPx * heightPx)
        val outA = FloatArray(widthPx * heightPx)

        val scaleX = widthPx.toFloat() / vbW
        val scaleY = heightPx.toFloat() / vbH
        val avgScreenScale = ((scaleX + scaleY) * 0.5f).coerceAtLeast(0.001f)
        val aaPadSvg = (0.25f / avgScreenScale)

        for (shape in shapes) {
            rasterizeShape(
                shape = shape,
                vbX = vbX,
                vbY = vbY,
                vbW = vbW,
                vbH = vbH,
                widthPx = widthPx,
                heightPx = heightPx,
                aaPadSvg = aaPadSvg,
                outR = outR,
                outG = outG,
                outB = outB,
                outA = outA
            )
        }

        val buffer = ImageBuffer(widthPx, heightPx)
        var nonZero = false
        for (py in 0 until heightPx) {
            val rowOff = py * widthPx
            for (px in 0 until widthPx) {
                val idx = rowOff + px
                val a = outA[idx].coerceIn(0f, 1f)
                if (a <= 0.001f) continue
                nonZero = true
                val r = (outR[idx] / a * 255f).roundToInt().coerceIn(0, 255)
                val g = (outG[idx] / a * 255f).roundToInt().coerceIn(0, 255)
                val b = (outB[idx] / a * 255f).roundToInt().coerceIn(0, 255)
                val ai = (a * 255f).roundToInt().coerceIn(0, 255)
                buffer.setPixel(px, py, RgbaColor(r, g, b, ai))
            }
        }
        return if (nonZero) buffer else null
    }

    private fun rasterizeShape(
        shape: SvgShape,
        vbX: Float,
        vbY: Float,
        vbW: Float,
        vbH: Float,
        widthPx: Int,
        heightPx: Int,
        aaPadSvg: Float,
        outR: FloatArray,
        outG: FloatArray,
        outB: FloatArray,
        outA: FloatArray
    ) {
        val style = shape.style
        val hasFill = style.fill !is PaintSpec.None && style.fillOpacity > 0.001f
        val hasStroke = style.stroke !is PaintSpec.None && style.strokeWidth > 0f && style.strokeOpacity > 0.001f
        if (!hasFill && !hasStroke) return

        val halfStroke = if (hasStroke) style.strokeWidth * 0.5f + aaPadSvg else 0f
        val pad = halfStroke + 1f / ((widthPx.toFloat() / vbW).coerceAtLeast(0.01f))
        val (bboxMinX, bboxMinY, bboxMaxX, bboxMaxY) = computeShapeBounds(shape)
        val px0 = floor((bboxMinX - pad - vbX) * widthPx / vbW).toInt().coerceIn(0, widthPx - 1)
        val py0 = floor((bboxMinY - pad - vbY) * heightPx / vbH).toInt().coerceIn(0, heightPx - 1)
        val px1 = ceil((bboxMaxX + pad - vbX) * widthPx / vbW).toInt().coerceIn(0, widthPx - 1)
        val py1 = ceil((bboxMaxY + pad - vbY) * heightPx / vbH).toInt().coerceIn(0, heightPx - 1)
        if (px1 < px0 || py1 < py0) return

        val inv16 = 1f / 16f
        val invW = vbW / widthPx.toFloat()
        val invH = vbH / heightPx.toFloat()

        for (py in py0..py1) {
            val rowOff = py * widthPx
            for (px in px0..px1) {
                var fillHits = 0
                var strokeHits = 0
                for (sy in 0 until 4) {
                    val sampleY = vbY + (py + (sy + 0.5f) * 0.25f) * invH
                    for (sx in 0 until 4) {
                        val sampleX = vbX + (px + (sx + 0.5f) * 0.25f) * invW
                        if (hasFill && isPointInShapeFill(shape, sampleX, sampleY)) {
                            fillHits++
                        }
                        if (hasStroke && isPointInShapeStroke(shape, sampleX, sampleY, halfStroke)) {
                            strokeHits++
                        }
                    }
                }
                if (fillHits == 0 && strokeHits == 0) continue

                val centerX = vbX + (px + 0.5f) * invW
                val centerY = vbY + (py + 0.5f) * invH
                val idx = rowOff + px

                if (fillHits > 0) {
                    val cov = fillHits * inv16 * style.fillOpacity
                    val fc = samplePaint(style.fill, centerX, centerY, bboxMinX, bboxMinY, bboxMaxX, bboxMaxY)
                    blendOver(outR, outG, outB, outA, idx, fc, cov)
                }
                if (strokeHits > 0) {
                    val cov = strokeHits * inv16 * style.strokeOpacity
                    val sc = samplePaint(style.stroke, centerX, centerY, bboxMinX, bboxMinY, bboxMaxX, bboxMaxY)
                    blendOver(outR, outG, outB, outA, idx, sc, cov)
                }
            }
        }
    }

    private fun blendOver(
        outR: FloatArray,
        outG: FloatArray,
        outB: FloatArray,
        outA: FloatArray,
        idx: Int,
        color: RgbaColor,
        coverage: Float
    ) {
        val srcA = ((color.a / 255f) * coverage).coerceIn(0f, 1f)
        if (srcA <= 0.0005f) return
        val srcR = (color.r / 255f) * srcA
        val srcG = (color.g / 255f) * srcA
        val srcB = (color.b / 255f) * srcA
        val invA = 1f - srcA
        outR[idx] = srcR + outR[idx] * invA
        outG[idx] = srcG + outG[idx] * invA
        outB[idx] = srcB + outB[idx] * invA
        outA[idx] = srcA + outA[idx] * invA
    }

    private fun computeShapeBounds(shape: SvgShape): FloatArray = when (shape) {
        is SvgShape.RoundedRect -> floatArrayOf(shape.x, shape.y, shape.x + shape.w, shape.y + shape.h)
        is SvgShape.Circle -> floatArrayOf(shape.cx - shape.r, shape.cy - shape.r, shape.cx + shape.r, shape.cy + shape.r)
        is SvgShape.Ellipse -> floatArrayOf(shape.cx - shape.rx, shape.cy - shape.ry, shape.cx + shape.rx, shape.cy + shape.ry)
        is SvgShape.PolyPath -> floatArrayOf(shape.minX, shape.minY, shape.maxX, shape.maxY)
    }

    private fun isPointInShapeFill(shape: SvgShape, x: Float, y: Float): Boolean = when (shape) {
        is SvgShape.RoundedRect -> roundedRectSdf(shape, x, y) <= 0f
        is SvgShape.Circle -> {
            val dx = x - shape.cx
            val dy = y - shape.cy
            dx * dx + dy * dy <= shape.r * shape.r
        }
        is SvgShape.Ellipse -> {
            if (shape.rx <= 0f || shape.ry <= 0f) false
            else {
                val nx = (x - shape.cx) / shape.rx
                val ny = (y - shape.cy) / shape.ry
                nx * nx + ny * ny <= 1f
            }
        }
        is SvgShape.PolyPath -> pointInSubpaths(shape.subpaths, x, y, shape.style.evenOdd)
    }

    private fun isPointInShapeStroke(shape: SvgShape, x: Float, y: Float, halfStroke: Float): Boolean = when (shape) {
        is SvgShape.RoundedRect -> abs(roundedRectSdf(shape, x, y)) <= halfStroke
        is SvgShape.Circle -> {
            val dx = x - shape.cx
            val dy = y - shape.cy
            val dist = sqrt(dx * dx + dy * dy)
            abs(dist - shape.r) <= halfStroke
        }
        is SvgShape.Ellipse -> abs(ellipseApproxSdf(shape, x, y)) <= halfStroke
        is SvgShape.PolyPath -> distanceToSubpaths(shape.subpaths, x, y, shape.style.squareCap) <= halfStroke
    }

    private fun roundedRectSdf(rect: SvgShape.RoundedRect, px: Float, py: Float): Float {
        val hw = rect.w * 0.5f
        val hh = rect.h * 0.5f
        val cx = rect.x + hw
        val cy = rect.y + hh
        val r = min(max(rect.rx, rect.ry), min(hw, hh)).coerceAtLeast(0f)
        val dx = abs(px - cx) - (hw - r)
        val dy = abs(py - cy) - (hh - r)
        val ox = max(dx, 0f)
        val oy = max(dy, 0f)
        return sqrt(ox * ox + oy * oy) + min(max(dx, dy), 0f) - r
    }

    private fun ellipseApproxSdf(e: SvgShape.Ellipse, px: Float, py: Float): Float {
        if (e.rx <= 0f || e.ry <= 0f) return Float.MAX_VALUE
        val dx = px - e.cx
        val dy = py - e.cy
        val nx = dx / e.rx
        val ny = dy / e.ry
        val norm = sqrt(nx * nx + ny * ny)
        if (norm <= 1e-6f) return -min(e.rx, e.ry)
        val gx = dx / (e.rx * e.rx)
        val gy = dy / (e.ry * e.ry)
        val gradLen = sqrt(gx * gx + gy * gy).coerceAtLeast(1e-6f)
        return (norm - 1f) * norm / gradLen
    }

    private fun pointInSubpaths(subpaths: List<Subpath>, x: Float, y: Float, evenOdd: Boolean): Boolean {
        var winding = 0
        var crossings = 0
        for (sp in subpaths) {
            val n = sp.xs.size
            if (n < 3) continue
            for (i in 0 until n) {
                val j = if (i + 1 < n) i + 1 else 0
                val x1 = sp.xs[i]
                val y1 = sp.ys[i]
                val x2 = sp.xs[j]
                val y2 = sp.ys[j]
                if ((y1 <= y && y2 > y) || (y2 <= y && y1 > y)) {
                    val xInt = x1 + (y - y1) * (x2 - x1) / (y2 - y1)
                    if (x < xInt) {
                        crossings++
                        if (y1 <= y) winding++ else winding--
                    }
                }
            }
        }
        return if (evenOdd) (crossings and 1) != 0 else winding != 0
    }

    private fun distanceToSubpaths(subpaths: List<Subpath>, px: Float, py: Float, squareCap: Boolean): Float {
        var minDistSq = Float.MAX_VALUE
        for (sp in subpaths) {
            val n = sp.xs.size
            if (n < 2) {
                if (n == 1) {
                    val dx = px - sp.xs[0]
                    val dy = py - sp.ys[0]
                    minDistSq = min(minDistSq, dx * dx + dy * dy)
                }
                continue
            }
            val segCount = if (sp.closed) n else n - 1
            for (i in 0 until segCount) {
                val j = if (i + 1 < n) i + 1 else 0
                val ax = sp.xs[i]
                val ay = sp.ys[i]
                val bx = sp.xs[j]
                val by = sp.ys[j]
                val vx = bx - ax
                val vy = by - ay
                val lenSq = vx * vx + vy * vy
                val dSq = if (lenSq <= 1e-8f) {
                    val dx = px - ax
                    val dy = py - ay
                    dx * dx + dy * dy
                } else {
                    val t = (((px - ax) * vx + (py - ay) * vy) / lenSq).coerceIn(0f, 1f)
                    val cx = ax + t * vx
                    val cy = ay + t * vy
                    val dx = px - cx
                    val dy = py - cy
                    dx * dx + dy * dy
                }
                if (dSq < minDistSq) minDistSq = dSq
            }
            if (squareCap && !sp.closed && n >= 2) {
                // Minor extension ignored cleanly; round/butt behaves identically inside stroke band
            }
        }
        return sqrt(minDistSq)
    }

    private fun samplePaint(
        paint: PaintSpec,
        x: Float,
        y: Float,
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float
    ): RgbaColor = when (paint) {
        is PaintSpec.Solid -> paint.color
        is PaintSpec.None -> RgbaColor(0, 0, 0, 0)
        is PaintSpec.LinearGrad -> {
            val g = paint.def
            val bw = (maxX - minX).coerceAtLeast(1e-4f)
            val bh = (maxY - minY).coerceAtLeast(1e-4f)
            val gx1 = if (g.userSpaceOnUse) g.x1 else minX + (if (g.x1Pct) g.x1 * 0.01f else g.x1) * bw
            val gy1 = if (g.userSpaceOnUse) g.y1 else minY + (if (g.y1Pct) g.y1 * 0.01f else g.y1) * bh
            val gx2 = if (g.userSpaceOnUse) g.x2 else minX + (if (g.x2Pct) g.x2 * 0.01f else g.x2) * bw
            val gy2 = if (g.userSpaceOnUse) g.y2 else minY + (if (g.y2Pct) g.y2 * 0.01f else g.y2) * bh
            val dx = gx2 - gx1
            val dy = gy2 - gy1
            val lenSq = dx * dx + dy * dy
            val t = if (lenSq <= 1e-6f) 0f else (((x - gx1) * dx + (y - gy1) * dy) / lenSq).coerceIn(0f, 1f)
            sampleStops(g.stops, t)
        }
        is PaintSpec.RadialGrad -> {
            val g = paint.def
            val bw = (maxX - minX).coerceAtLeast(1e-4f)
            val bh = (maxY - minY).coerceAtLeast(1e-4f)
            val gcx = if (g.userSpaceOnUse) g.cx else minX + (if (g.cxPct) g.cx * 0.01f else g.cx) * bw
            val gcy = if (g.userSpaceOnUse) g.cy else minY + (if (g.cyPct) g.cy * 0.01f else g.cy) * bh
            val gr = if (g.userSpaceOnUse) g.r else (if (g.rPct) g.r * 0.01f else g.r) * max(bw, bh)
            val dx = x - gcx
            val dy = y - gcy
            val t = if (gr <= 1e-5f) 1f else (sqrt(dx * dx + dy * dy) / gr).coerceIn(0f, 1f)
            sampleStops(g.stops, t)
        }
    }

    private fun sampleStops(stops: List<GradientStop>, t: Float): RgbaColor {
        if (stops.isEmpty()) return RgbaColor(0, 0, 0, 255)
        if (t <= stops.first().offset || stops.size == 1) return stops.first().color
        if (t >= stops.last().offset) return stops.last().color
        for (i in 0 until stops.lastIndex) {
            val a = stops[i]
            val b = stops[i + 1]
            if (t >= a.offset && t <= b.offset) {
                val span = (b.offset - a.offset).coerceAtLeast(1e-5f)
                val f = ((t - a.offset) / span).coerceIn(0f, 1f)
                return RgbaColor(
                    r = (a.color.r + (b.color.r - a.color.r) * f).roundToInt().coerceIn(0, 255),
                    g = (a.color.g + (b.color.g - a.color.g) * f).roundToInt().coerceIn(0, 255),
                    b = (a.color.b + (b.color.b - a.color.b) * f).roundToInt().coerceIn(0, 255),
                    a = (a.color.a + (b.color.a - a.color.a) * f).roundToInt().coerceIn(0, 255)
                )
            }
        }
        return stops.last().color
    }

    private fun parseShape(
        tag: String,
        attrs: Map<String, String>,
        style: DrawStyle,
        mat: Mat2D
    ): SvgShape? {
        return when (tag) {
            "rect" -> {
                val x = attrs["x"]?.let(::parseFloatOrNull) ?: 0f
                val y = attrs["y"]?.let(::parseFloatOrNull) ?: 0f
                val w = attrs["width"]?.let(::parseFloatOrNull) ?: 0f
                val h = attrs["height"]?.let(::parseFloatOrNull) ?: 0f
                if (w <= 0f || h <= 0f) return null
                var rx = attrs["rx"]?.let(::parseFloatOrNull) ?: -1f
                var ry = attrs["ry"]?.let(::parseFloatOrNull) ?: -1f
                if (rx < 0f && ry >= 0f) rx = ry
                if (ry < 0f && rx >= 0f) ry = rx
                if (rx < 0f) rx = 0f
                if (ry < 0f) ry = 0f
                if (isAxisAlignedIdentity(mat)) {
                    SvgShape.RoundedRect(x + mat.e, y + mat.f, w * mat.a, h * mat.d, rx * abs(mat.a), ry * abs(mat.d), style)
                } else {
                    val pts = floatArrayOf(
                        x, y, x + w, y, x + w, y + h, x, y + h
                    )
                    toTransformedPolyShape(listOf(buildClosedSubpath(pts)), style, mat)
                }
            }
            "circle" -> {
                val cx = attrs["cx"]?.let(::parseFloatOrNull) ?: 0f
                val cy = attrs["cy"]?.let(::parseFloatOrNull) ?: 0f
                val r = attrs["r"]?.let(::parseFloatOrNull) ?: 0f
                if (r <= 0f) return null
                if (isUniformTransform(mat)) {
                    val sc = abs(mat.a)
                    SvgShape.Circle(mat.mapX(cx, cy), mat.mapY(cx, cy), r * sc, style)
                } else {
                    SvgShape.Ellipse(mat.mapX(cx, cy), mat.mapY(cx, cy), r * sqrt(mat.a * mat.a + mat.b * mat.b), r * sqrt(mat.c * mat.c + mat.d * mat.d), style)
                }
            }
            "ellipse" -> {
                val cx = attrs["cx"]?.let(::parseFloatOrNull) ?: 0f
                val cy = attrs["cy"]?.let(::parseFloatOrNull) ?: 0f
                val rx = attrs["rx"]?.let(::parseFloatOrNull) ?: 0f
                val ry = attrs["ry"]?.let(::parseFloatOrNull) ?: 0f
                if (rx <= 0f || ry <= 0f) return null
                SvgShape.Ellipse(
                    mat.mapX(cx, cy),
                    mat.mapY(cx, cy),
                    rx * sqrt(mat.a * mat.a + mat.b * mat.b),
                    ry * sqrt(mat.c * mat.c + mat.d * mat.d),
                    style
                )
            }
            "line" -> {
                val x1 = attrs["x1"]?.let(::parseFloatOrNull) ?: 0f
                val y1 = attrs["y1"]?.let(::parseFloatOrNull) ?: 0f
                val x2 = attrs["x2"]?.let(::parseFloatOrNull) ?: 0f
                val y2 = attrs["y2"]?.let(::parseFloatOrNull) ?: 0f
                val lineStyle = DrawStyle(
                    fill = PaintSpec.None,
                    fillOpacity = 0f,
                    evenOdd = false,
                    stroke = style.stroke,
                    strokeWidth = style.strokeWidth,
                    strokeOpacity = style.strokeOpacity,
                    roundCap = style.roundCap,
                    squareCap = style.squareCap
                )
                val sp = Subpath(floatArrayOf(x1, x2), floatArrayOf(y1, y2), closed = false)
                toTransformedPolyShape(listOf(sp), lineStyle, mat)
            }
            "polygon", "polyline" -> {
                val rawPts = attrs["points"] ?: return null
                val nums = extractFloatList(rawPts)
                val count = nums.size / 2
                if (count < 2) return null
                val xs = FloatArray(count) { i -> nums[i * 2] }
                val ys = FloatArray(count) { i -> nums[i * 2 + 1] }
                val closed = tag == "polygon"
                toTransformedPolyShape(listOf(Subpath(xs, ys, closed)), style, mat)
            }
            "path" -> {
                val d = attrs["d"] ?: return null
                val subpaths = parseSvgPathSubpaths(d)
                if (subpaths.isEmpty()) return null
                toTransformedPolyShape(subpaths, style, mat)
            }
            else -> null
        }
    }

    private fun buildClosedSubpath(xy: FloatArray): Subpath {
        val n = xy.size / 2
        return Subpath(FloatArray(n) { xy[it * 2] }, FloatArray(n) { xy[it * 2 + 1] }, closed = true)
    }

    private fun toTransformedPolyShape(
        subpaths: List<Subpath>,
        style: DrawStyle,
        mat: Mat2D
    ): SvgShape.PolyPath? {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val out = ArrayList<Subpath>(subpaths.size)
        for (sp in subpaths) {
            val n = sp.xs.size
            if (n == 0) continue
            val tx = FloatArray(n)
            val ty = FloatArray(n)
            for (i in 0 until n) {
                val mx = mat.mapX(sp.xs[i], sp.ys[i])
                val my = mat.mapY(sp.xs[i], sp.ys[i])
                tx[i] = mx
                ty[i] = my
                if (mx < minX) minX = mx
                if (my < minY) minY = my
                if (mx > maxX) maxX = mx
                if (my > maxY) maxY = my
            }
            out.add(Subpath(tx, ty, sp.closed))
        }
        if (out.isEmpty() || minX > maxX || minY > maxY) return null
        return SvgShape.PolyPath(out, minX, minY, maxX, maxY, style)
    }

    private fun isAxisAlignedIdentity(mat: Mat2D): Boolean =
        abs(mat.b) < 1e-5f && abs(mat.c) < 1e-5f && mat.a > 0f && mat.d > 0f

    private fun isUniformTransform(mat: Mat2D): Boolean =
        abs(mat.b) < 1e-5f && abs(mat.c) < 1e-5f && abs(abs(mat.a) - abs(mat.d)) < 1e-4f

    private fun parseSvgPathSubpaths(d: String): List<Subpath> {
        val subpaths = ArrayList<Subpath>()
        var curXs = ArrayList<Float>()
        var curYs = ArrayList<Float>()
        var curX = 0f
        var curY = 0f
        var startX = 0f
        var startY = 0f
        var lastCtrlX = 0f
        var lastCtrlY = 0f
        var lastCmd = '\u0000'
        var idx = 0
        val len = d.length

        fun flush(closed: Boolean) {
            if (curXs.isNotEmpty()) {
                subpaths.add(Subpath(curXs.toFloatArray(), curYs.toFloatArray(), closed))
                curXs = ArrayList()
                curYs = ArrayList()
            }
        }

        fun skipSep() {
            while (idx < len && (d[idx].isWhitespace() || d[idx] == ',')) idx++
        }

        fun hasNumber(): Boolean {
            skipSep()
            if (idx >= len) return false
            val ch = d[idx]
            return ch == '-' || ch == '+' || ch == '.' || ch in '0'..'9'
        }

        fun nextFloat(): Float? {
            skipSep()
            if (idx >= len) return null
            val start = idx
            if (d[idx] == '-' || d[idx] == '+') idx++
            var hasDot = false
            while (idx < len) {
                val ch = d[idx]
                if (ch in '0'..'9') idx++
                else if (ch == '.' && !hasDot) {
                    hasDot = true
                    idx++
                } else if ((ch == 'e' || ch == 'E') && idx > start) {
                    idx++
                    if (idx < len && (d[idx] == '-' || d[idx] == '+')) idx++
                } else break
            }
            return if (idx > start) d.substring(start, idx).toFloatOrNull() else null
        }

        fun addPt(x: Float, y: Float) {
            curXs.add(x)
            curYs.add(y)
        }

        var cmd = '\u0000'
        while (idx < len) {
            skipSep()
            if (idx >= len) break
            val ch = d[idx]
            if (ch.isLetter()) {
                cmd = ch
                idx++
            } else if (cmd == '\u0000') {
                idx++
                continue
            }

            when (cmd) {
                'M', 'm' -> {
                    val x = nextFloat() ?: break
                    val y = nextFloat() ?: break
                    flush(closed = false)
                    curX = if (cmd == 'm') curX + x else x
                    curY = if (cmd == 'm') curY + y else y
                    startX = curX
                    startY = curY
                    addPt(curX, curY)
                    lastCtrlX = curX
                    lastCtrlY = curY
                    lastCmd = cmd
                    cmd = if (cmd == 'm') 'l' else 'L'
                }
                'L', 'l' -> {
                    while (hasNumber()) {
                        val x = nextFloat() ?: break
                        val y = nextFloat() ?: break
                        curX = if (cmd == 'l') curX + x else x
                        curY = if (cmd == 'l') curY + y else y
                        addPt(curX, curY)
                    }
                    lastCtrlX = curX
                    lastCtrlY = curY
                    lastCmd = cmd
                }
                'H', 'h' -> {
                    while (hasNumber()) {
                        val x = nextFloat() ?: break
                        curX = if (cmd == 'h') curX + x else x
                        addPt(curX, curY)
                    }
                    lastCtrlX = curX
                    lastCtrlY = curY
                    lastCmd = cmd
                }
                'V', 'v' -> {
                    while (hasNumber()) {
                        val y = nextFloat() ?: break
                        curY = if (cmd == 'v') curY + y else y
                        addPt(curX, curY)
                    }
                    lastCtrlX = curX
                    lastCtrlY = curY
                    lastCmd = cmd
                }
                'C', 'c' -> {
                    while (hasNumber()) {
                        val rel = cmd == 'c'
                        val x1 = (nextFloat() ?: break) + if (rel) curX else 0f
                        val y1 = (nextFloat() ?: break) + if (rel) curY else 0f
                        val x2 = (nextFloat() ?: break) + if (rel) curX else 0f
                        val y2 = (nextFloat() ?: break) + if (rel) curY else 0f
                        val x = (nextFloat() ?: break) + if (rel) curX else 0f
                        val y = (nextFloat() ?: break) + if (rel) curY else 0f
                        sampleCubicBezier(curX, curY, x1, y1, x2, y2, x, y, ::addPt)
                        lastCtrlX = x2
                        lastCtrlY = y2
                        curX = x
                        curY = y
                        lastCmd = cmd
                    }
                }
                'S', 's' -> {
                    while (hasNumber()) {
                        val rel = cmd == 's'
                        val x1 = if (lastCmd == 'C' || lastCmd == 'c' || lastCmd == 'S' || lastCmd == 's') {
                            2f * curX - lastCtrlX
                        } else {
                            curX
                        }
                        val y1 = if (lastCmd == 'C' || lastCmd == 'c' || lastCmd == 'S' || lastCmd == 's') {
                            2f * curY - lastCtrlY
                        } else {
                            curY
                        }
                        val x2 = (nextFloat() ?: break) + if (rel) curX else 0f
                        val y2 = (nextFloat() ?: break) + if (rel) curY else 0f
                        val x = (nextFloat() ?: break) + if (rel) curX else 0f
                        val y = (nextFloat() ?: break) + if (rel) curY else 0f
                        sampleCubicBezier(curX, curY, x1, y1, x2, y2, x, y, ::addPt)
                        lastCtrlX = x2
                        lastCtrlY = y2
                        curX = x
                        curY = y
                        lastCmd = cmd
                    }
                }
                'Q', 'q' -> {
                    while (hasNumber()) {
                        val rel = cmd == 'q'
                        val x1 = (nextFloat() ?: break) + if (rel) curX else 0f
                        val y1 = (nextFloat() ?: break) + if (rel) curY else 0f
                        val x = (nextFloat() ?: break) + if (rel) curX else 0f
                        val y = (nextFloat() ?: break) + if (rel) curY else 0f
                        sampleQuadBezier(curX, curY, x1, y1, x, y, ::addPt)
                        lastCtrlX = x1
                        lastCtrlY = y1
                        curX = x
                        curY = y
                        lastCmd = cmd
                    }
                }
                'T', 't' -> {
                    while (hasNumber()) {
                        val rel = cmd == 't'
                        val x1 = if (lastCmd == 'Q' || lastCmd == 'q' || lastCmd == 'T' || lastCmd == 't') {
                            2f * curX - lastCtrlX
                        } else {
                            curX
                        }
                        val y1 = if (lastCmd == 'Q' || lastCmd == 'q' || lastCmd == 'T' || lastCmd == 't') {
                            2f * curY - lastCtrlY
                        } else {
                            curY
                        }
                        val x = (nextFloat() ?: break) + if (rel) curX else 0f
                        val y = (nextFloat() ?: break) + if (rel) curY else 0f
                        sampleQuadBezier(curX, curY, x1, y1, x, y, ::addPt)
                        lastCtrlX = x1
                        lastCtrlY = y1
                        curX = x
                        curY = y
                        lastCmd = cmd
                    }
                }
                'A', 'a' -> {
                    while (hasNumber()) {
                        val rel = cmd == 'a'
                        val rx = abs(nextFloat() ?: break)
                        val ry = abs(nextFloat() ?: break)
                        val rot = nextFloat() ?: break
                        val largeArc = (nextFloat() ?: break) != 0f
                        val sweep = (nextFloat() ?: break) != 0f
                        val x = (nextFloat() ?: break) + if (rel) curX else 0f
                        val y = (nextFloat() ?: break) + if (rel) curY else 0f
                        sampleArc(curX, curY, rx, ry, rot, largeArc, sweep, x, y, ::addPt)
                        curX = x
                        curY = y
                        lastCtrlX = curX
                        lastCtrlY = curY
                        lastCmd = cmd
                    }
                }
                'Z', 'z' -> {
                    flush(closed = true)
                    curX = startX
                    curY = startY
                    lastCtrlX = curX
                    lastCtrlY = curY
                    lastCmd = cmd
                }
                else -> idx++
            }
        }
        flush(closed = false)
        return subpaths
    }

    private fun sampleCubicBezier(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        x3: Float,
        y3: Float,
        addPt: (Float, Float) -> Unit
    ) {
        val steps = 12
        for (s in 1..steps) {
            val t = s.toFloat() / steps
            val u = 1f - t
            val bx = u * u * u * x0 + 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t * x3
            val by = u * u * u * y0 + 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t * y3
            addPt(bx, by)
        }
    }

    private fun sampleQuadBezier(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        addPt: (Float, Float) -> Unit
    ) {
        val steps = 10
        for (s in 1..steps) {
            val t = s.toFloat() / steps
            val u = 1f - t
            val bx = u * u * x0 + 2f * u * t * x1 + t * t * x2
            val by = u * u * y0 + 2f * u * t * y1 + t * t * y2
            addPt(bx, by)
        }
    }

    private fun sampleArc(
        x0: Float,
        y0: Float,
        rxIn: Float,
        ryIn: Float,
        angleDeg: Float,
        largeArc: Boolean,
        sweep: Boolean,
        x1: Float,
        y1: Float,
        addPt: (Float, Float) -> Unit
    ) {
        if (rxIn <= 1e-5f || ryIn <= 1e-5f) {
            addPt(x1, y1)
            return
        }
        val rad = angleDeg * (PI / 180.0)
        val cosA = cos(rad)
        val sinA = sin(rad)
        val dx2 = (x0 - x1) * 0.5
        val dy2 = (y0 - y1) * 0.5
        val x1p = cosA * dx2 + sinA * dy2
        val y1p = -sinA * dx2 + cosA * dy2
        var rx = rxIn.toDouble()
        var ry = ryIn.toDouble()
        val rxSq = rx * rx
        val rySq = ry * ry
        val x1pSq = x1p * x1p
        val y1pSq = y1p * y1p
        val lambda = x1pSq / rxSq + y1pSq / rySq
        if (lambda > 1.0) {
            val s = sqrt(lambda)
            rx *= s
            ry *= s
        }
        val rx2 = rx * rx
        val ry2 = ry * ry
        val num = (rx2 * ry2 - rx2 * y1pSq - ry2 * x1pSq).coerceAtLeast(0.0)
        val den = (rx2 * y1pSq + ry2 * x1pSq).coerceAtLeast(1e-9)
        var coef = sqrt(num / den)
        if (largeArc == sweep) coef = -coef
        val cxp = coef * (rx * y1p / ry)
        val cyp = coef * -(ry * x1p / rx)
        val cx = cosA * cxp - sinA * cyp + (x0 + x1) * 0.5
        val cy = sinA * cxp + cosA * cyp + (y0 + y1) * 0.5

        fun angleVec(ux: Double, uy: Double, vx: Double, vy: Double): Double {
            val dot = ux * vx + uy * vy
            val len = (sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)).coerceAtLeast(1e-9)
            var ang = kotlin.math.acos((dot / len).coerceIn(-1.0, 1.0))
            if (ux * vy - uy * vx < 0) ang = -ang
            return ang
        }

        val ux = (x1p - cxp) / rx
        val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx
        val vy = (-y1p - cyp) / ry
        val theta1 = angleVec(1.0, 0.0, ux, uy)
        var dTheta = angleVec(ux, uy, vx, vy)
        if (!sweep && dTheta > 0) dTheta -= 2.0 * PI
        if (sweep && dTheta < 0) dTheta += 2.0 * PI

        val steps = max(8, (abs(dTheta) / (PI / 8.0)).roundToInt().coerceIn(8, 32))
        for (i in 1..steps) {
            val ang = theta1 + dTheta * (i.toDouble() / steps)
            val ex = rx * cos(ang)
            val ey = ry * sin(ang)
            val px = (cosA * ex - sinA * ey + cx).toFloat()
            val py = (sinA * ex + cosA * ey + cy).toFloat()
            addPt(px, py)
        }
    }

    private fun tokenizeSvgXml(svg: String): List<XmlToken> {
        val tokens = ArrayList<XmlToken>()
        var i = 0
        val n = svg.length
        while (i < n) {
            val lt = svg.indexOf('<', i)
            if (lt < 0) break
            if (svg.startsWith("<!--", lt)) {
                val endComment = svg.indexOf("-->", lt + 4)
                i = if (endComment >= 0) endComment + 3 else n
                continue
            }
            if (svg.startsWith("<?", lt) || svg.startsWith("<!", lt)) {
                val gt = svg.indexOf('>', lt + 2)
                i = if (gt >= 0) gt + 1 else n
                continue
            }
            var j = lt + 1
            var inQuote = '\u0000'
            while (j < n) {
                val c = svg[j]
                if (inQuote != '\u0000') {
                    if (c == inQuote) inQuote = '\u0000'
                } else {
                    if (c == '"' || c == '\'') inQuote = c
                    else if (c == '>') break
                }
                j++
            }
            if (j >= n) break
            val inner = svg.substring(lt + 1, j).trim()
            i = j + 1
            if (inner.isEmpty()) continue

            val isClosing = inner.startsWith('/')
            val isSelfClosing = !isClosing && inner.endsWith('/')
            val clean = inner.trimStart('/').trimEnd('/').trim()
            if (clean.isEmpty()) continue

            var nameEnd = 0
            while (nameEnd < clean.length && !clean[nameEnd].isWhitespace()) nameEnd++
            val tagName = clean.substring(0, nameEnd)
            val attrStr = if (nameEnd < clean.length) clean.substring(nameEnd) else ""
            val attrs = if (!isClosing && attrStr.isNotBlank()) parseXmlAttrs(attrStr) else emptyMap()
            tokens.add(XmlToken(tagName, isClosing, isSelfClosing, attrs))
        }
        return tokens
    }

    private fun parseXmlAttrs(raw: String): Map<String, String> {
        val map = HashMap<String, String>()
        var i = 0
        val n = raw.length
        while (i < n) {
            while (i < n && raw[i].isWhitespace()) i++
            if (i >= n) break
            val nameStart = i
            while (i < n && raw[i] != '=' && !raw[i].isWhitespace()) i++
            val key = raw.substring(nameStart, i).trim().lowercase()
            while (i < n && raw[i].isWhitespace()) i++
            if (i >= n || raw[i] != '=') {
                if (key.isNotEmpty()) map[key] = ""
                continue
            }
            i++ // '='
            while (i < n && raw[i].isWhitespace()) i++
            if (i >= n) break
            val quote = raw[i]
            val value = if (quote == '"' || quote == '\'') {
                i++
                val vStart = i
                while (i < n && raw[i] != quote) i++
                val v = raw.substring(vStart, i)
                if (i < n) i++
                v
            } else {
                val vStart = i
                while (i < n && !raw[i].isWhitespace()) i++
                raw.substring(vStart, i)
            }
            if (key.isNotEmpty()) {
                map[key] = value
                if (key == "style") {
                    for (decl in value.split(';')) {
                        val colon = decl.indexOf(':')
                        if (colon > 0) {
                            val k = decl.substring(0, colon).trim().lowercase()
                            val v = decl.substring(colon + 1).trim()
                            if (k.isNotEmpty() && !map.containsKey(k)) {
                                map[k] = v
                            }
                        }
                    }
                }
            }
        }
        return map
    }

    private fun parseViewBox(attrs: Map<String, String>): FloatArray {
        val vb = attrs["viewbox"]
        if (!vb.isNullOrBlank()) {
            val parts = extractFloatList(vb)
            if (parts.size >= 4 && parts[2] > 0f && parts[3] > 0f) {
                return floatArrayOf(parts[0], parts[1], parts[2], parts[3])
            }
        }
        val w = attrs["width"]?.let(::parseLengthNoUnit) ?: 24f
        val h = attrs["height"]?.let(::parseLengthNoUnit) ?: 24f
        return floatArrayOf(0f, 0f, w.coerceAtLeast(1f), h.coerceAtLeast(1f))
    }

    private fun parseLengthNoUnit(raw: String): Float? =
        raw.trim().removeSuffix("px").trim().toFloatOrNull()

    private fun mergeStyle(parent: InheritedStyle, attrs: Map<String, String>): InheritedStyle {
        val fillRaw = attrs["fill"] ?: parent.fillRaw
        val fillOp = (attrs["fill-opacity"]?.toFloatOrNull() ?: parent.fillOpacity).coerceIn(0f, 1f)
        val fillRule = attrs["fill-rule"]?.trim()?.lowercase() ?: parent.fillRule
        val strokeRaw = attrs["stroke"] ?: parent.strokeRaw
        val strokeW = attrs["stroke-width"]?.let(::parseLengthNoUnit) ?: parent.strokeWidth
        val strokeOp = (attrs["stroke-opacity"]?.toFloatOrNull() ?: parent.strokeOpacity).coerceIn(0f, 1f)
        val cap = attrs["stroke-linecap"]?.trim()?.lowercase() ?: parent.strokeLineCap
        val op = (parent.opacity * (attrs["opacity"]?.toFloatOrNull() ?: 1f)).coerceIn(0f, 1f)
        return InheritedStyle(fillRaw, fillOp, fillRule, strokeRaw, strokeW, strokeOp, cap, op)
    }

    private fun resolveDrawStyle(
        style: InheritedStyle,
        scaleFactor: Float,
        linearGrads: Map<String, LinearGradientDef>,
        radialGrads: Map<String, RadialGradientDef>
    ): DrawStyle {
        val fillPaint = resolvePaint(style.fillRaw, defaultFill = true, linearGrads, radialGrads)
        val strokePaint = resolvePaint(style.strokeRaw, defaultFill = false, linearGrads, radialGrads)
        val sw = ((style.strokeWidth ?: if (strokePaint is PaintSpec.None) 0f else 1f) * scaleFactor).coerceAtLeast(0f)
        return DrawStyle(
            fill = fillPaint,
            fillOpacity = (style.fillOpacity * style.opacity).coerceIn(0f, 1f),
            evenOdd = style.fillRule == "evenodd",
            stroke = strokePaint,
            strokeWidth = sw,
            strokeOpacity = (style.strokeOpacity * style.opacity).coerceIn(0f, 1f),
            roundCap = style.strokeLineCap == "round",
            squareCap = style.strokeLineCap == "square"
        )
    }

    private fun resolvePaint(
        raw: String?,
        defaultFill: Boolean,
        linearGrads: Map<String, LinearGradientDef>,
        radialGrads: Map<String, RadialGradientDef>
    ): PaintSpec {
        val s = raw?.trim()
        if (s.isNullOrEmpty()) {
            return if (defaultFill) PaintSpec.Solid(RgbaColor(0, 0, 0, 255)) else PaintSpec.None
        }
        if (s.equals("none", ignoreCase = true) || s.equals("transparent", ignoreCase = true)) {
            return PaintSpec.None
        }
        if (s.startsWith("url(", ignoreCase = true)) {
            val inner = s.substring(4).substringBefore(')').trim().trim('"', '\'').removePrefix("#")
            linearGrads[inner]?.let { return PaintSpec.LinearGrad(it) }
            radialGrads[inner]?.let { return PaintSpec.RadialGrad(it) }
        }
        parseSvgColor(s)?.let { return PaintSpec.Solid(it) }
        return if (defaultFill) PaintSpec.Solid(RgbaColor(0, 0, 0, 255)) else PaintSpec.None
    }

    private fun buildLinearGrad(
        id: String,
        attrs: Map<String, String>,
        stops: List<GradientStop>
    ): LinearGradientDef? {
        if (stops.isEmpty()) return null
        val userSpace = attrs["gradientunits"]?.equals("userSpaceOnUse", ignoreCase = true) == true
        val (x1, x1p) = parseGradCoord(attrs["x1"], if (userSpace) 0f else 0f)
        val (y1, y1p) = parseGradCoord(attrs["y1"], if (userSpace) 0f else 0f)
        val (x2, x2p) = parseGradCoord(attrs["x2"], if (userSpace) 0f else 1f)
        val (y2, y2p) = parseGradCoord(attrs["y2"], if (userSpace) 0f else 0f)
        val sorted = stops.sortedBy { it.offset }
        return LinearGradientDef(id, x1, y1, x1p, x2, y2, y1p, x2p, y2p, userSpace, sorted)
    }

    private fun buildRadialGrad(
        id: String,
        attrs: Map<String, String>,
        stops: List<GradientStop>
    ): RadialGradientDef? {
        if (stops.isEmpty()) return null
        val userSpace = attrs["gradientunits"]?.equals("userSpaceOnUse", ignoreCase = true) == true
        val (cx, cxp) = parseGradCoord(attrs["cx"], 0.5f)
        val (cy, cyp) = parseGradCoord(attrs["cy"], 0.5f)
        val (r, rp) = parseGradCoord(attrs["r"], 0.5f)
        val sorted = stops.sortedBy { it.offset }
        return RadialGradientDef(id, cx, cy, r, cxp, cyp, rp, userSpace, sorted)
    }

    private fun parseGradCoord(raw: String?, defaultVal: Float): Pair<Float, Boolean> {
        val s = raw?.trim() ?: return defaultVal to false
        if (s.endsWith("%")) {
            val v = s.dropLast(1).toFloatOrNull() ?: (defaultVal * 100f)
            return v to true
        }
        return (s.toFloatOrNull() ?: defaultVal) to false
    }

    private fun parseStop(attrs: Map<String, String>): GradientStop? {
        val rawOffset = attrs["offset"]?.trim() ?: "0"
        val offset = if (rawOffset.endsWith("%")) {
            ((rawOffset.dropLast(1).toFloatOrNull() ?: 0f) * 0.01f).coerceIn(0f, 1f)
        } else {
            (rawOffset.toFloatOrNull() ?: 0f).coerceIn(0f, 1f)
        }
        val color = parseSvgColor(attrs["stop-color"] ?: "black") ?: RgbaColor(0, 0, 0, 255)
        val stopOp = (attrs["stop-opacity"]?.toFloatOrNull() ?: 1f).coerceIn(0f, 1f)
        val a = ((color.a / 255f) * stopOp * 255f).roundToInt().coerceIn(0, 255)
        return GradientStop(offset, RgbaColor(color.r, color.g, color.b, a))
    }

    private fun parseSvgColor(raw: String): RgbaColor? {
        val s = raw.trim().lowercase()
        if (s.isEmpty() || s == "none" || s == "transparent") return RgbaColor(0, 0, 0, 0)
        if (s.startsWith("#")) {
            val hex = s.drop(1)
            return when (hex.length) {
                3 -> {
                    val r = hex[0].digitToIntOrNull(16) ?: return null
                    val g = hex[1].digitToIntOrNull(16) ?: return null
                    val b = hex[2].digitToIntOrNull(16) ?: return null
                    RgbaColor(r * 17, g * 17, b * 17, 255)
                }
                4 -> {
                    val r = hex[0].digitToIntOrNull(16) ?: return null
                    val g = hex[1].digitToIntOrNull(16) ?: return null
                    val b = hex[2].digitToIntOrNull(16) ?: return null
                    val a = hex[3].digitToIntOrNull(16) ?: return null
                    RgbaColor(r * 17, g * 17, b * 17, a * 17)
                }
                6 -> {
                    val v = hex.toLongOrNull(16) ?: return null
                    RgbaColor(((v ushr 16) and 0xFF).toInt(), ((v ushr 8) and 0xFF).toInt(), (v and 0xFF).toInt(), 255)
                }
                8 -> {
                    val v = hex.toLongOrNull(16) ?: return null
                    RgbaColor(
                        ((v ushr 24) and 0xFF).toInt(),
                        ((v ushr 16) and 0xFF).toInt(),
                        ((v ushr 8) and 0xFF).toInt(),
                        (v and 0xFF).toInt()
                    )
                }
                else -> null
            }
        }
        if (s.startsWith("rgb(") || s.startsWith("rgba(")) {
            val inner = s.substringAfter('(').substringBefore(')')
            val parts = inner.split(',').map { it.trim() }
            if (parts.size >= 3) {
                val r = parts[0].toFloatOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null
                val g = parts[1].toFloatOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null
                val b = parts[2].toFloatOrNull()?.roundToInt()?.coerceIn(0, 255) ?: return null
                val a = if (parts.size >= 4) {
                    val rawA = parts[3].toFloatOrNull() ?: 1f
                    (if (rawA <= 1f) rawA * 255f else rawA).roundToInt().coerceIn(0, 255)
                } else 255
                return RgbaColor(r, g, b, a)
            }
        }
        return when (s) {
            "black", "currentcolor" -> RgbaColor(0, 0, 0, 255)
            "white" -> RgbaColor(255, 255, 255, 255)
            "red" -> RgbaColor(255, 0, 0, 255)
            "green" -> RgbaColor(0, 128, 0, 255)
            "blue" -> RgbaColor(0, 0, 255, 255)
            "yellow" -> RgbaColor(255, 255, 0, 255)
            "cyan", "aqua" -> RgbaColor(0, 255, 255, 255)
            "magenta", "fuchsia" -> RgbaColor(255, 0, 255, 255)
            "gray", "grey" -> RgbaColor(128, 128, 128, 255)
            "orange" -> RgbaColor(255, 165, 0, 255)
            "purple" -> RgbaColor(128, 0, 128, 255)
            else -> null
        }
    }

    private fun parseTransform(raw: String?): Mat2D? {
        if (raw.isNullOrBlank()) return null
        var cur = Mat2D()
        var idx = 0
        while (idx < raw.length) {
            val open = raw.indexOf('(', idx)
            if (open < 0) break
            val close = raw.indexOf(')', open + 1)
            if (close < 0) break
            val name = raw.substring(idx, open).trim().lowercase()
            val args = extractFloatList(raw.substring(open + 1, close))
            idx = close + 1
            val next = when {
                name.endsWith("translate") && args.isNotEmpty() -> {
                    Mat2D(1f, 0f, 0f, 1f, args[0], args.getOrElse(1) { 0f })
                }
                name.endsWith("scale") && args.isNotEmpty() -> {
                    val sx = args[0]
                    val sy = args.getOrElse(1) { sx }
                    Mat2D(sx, 0f, 0f, sy, 0f, 0f)
                }
                name.endsWith("rotate") && args.isNotEmpty() -> {
                    val rad = args[0] * (PI / 180.0)
                    val ca = cos(rad).toFloat()
                    val sa = sin(rad).toFloat()
                    val cx = args.getOrElse(1) { 0f }
                    val cy = args.getOrElse(2) { 0f }
                    if (cx != 0f || cy != 0f) {
                        Mat2D(1f, 0f, 0f, 1f, cx, cy)
                            .mul(Mat2D(ca, sa, -sa, ca, 0f, 0f))
                            .mul(Mat2D(1f, 0f, 0f, 1f, -cx, -cy))
                    } else {
                        Mat2D(ca, sa, -sa, ca, 0f, 0f)
                    }
                }
                name.endsWith("matrix") && args.size >= 6 -> {
                    Mat2D(args[0], args[1], args[2], args[3], args[4], args[5])
                }
                else -> null
            }
            if (next != null) cur = cur.mul(next)
        }
        return cur
    }

    private fun parseFloatOrNull(raw: String): Float? = parseLengthNoUnit(raw)

    private fun extractFloatList(raw: String): List<Float> {
        val out = ArrayList<Float>()
        var i = 0
        val n = raw.length
        while (i < n) {
            while (i < n && (raw[i].isWhitespace() || raw[i] == ',')) i++
            if (i >= n) break
            val start = i
            if (raw[i] == '-' || raw[i] == '+') i++
            var hasDot = false
            while (i < n) {
                val c = raw[i]
                if (c in '0'..'9') i++
                else if (c == '.' && !hasDot) {
                    hasDot = true
                    i++
                } else if ((c == 'e' || c == 'E') && i > start) {
                    i++
                    if (i < n && (raw[i] == '-' || raw[i] == '+')) i++
                } else break
            }
            if (i > start) {
                raw.substring(start, i).toFloatOrNull()?.let { out.add(it) }
            } else {
                i++
            }
        }
        return out
    }
}
