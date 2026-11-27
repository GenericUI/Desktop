@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.widget

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.linux.style.LinuxBackdropFilterController
import com.nexustools.genericui.linux.style.LinuxStyledSurfaceHelper
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCornerRadii
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssFilterColorMatrix
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.css.GradientStopNormalizer
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.gradient.Gradient.LinearGradient
import com.nexustools.genericui.style.gradient.Gradient.RadialGradient
import com.nexustools.genericui.system.style.SemanticSurfacePaintSpec
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import platform.posix.memcpy
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Shared Cairo + GSK retained surface renderer implementing rounded clipping, linear/radial
 * gradients, CSS borders (solid/dashed/dotted/double), box shadows, all 10 `backdrop-filter` ops,
 * and GSK color-matrix/blur/drop-shadow nodes.
 */
object GtkSurfaceRenderHelper {

    data class PaddedBgraCapture(
        val pixels: IntArray,
        val bufferWidth: Int,
        val bufferHeight: Int,
        val regionX: Int,
        val regionY: Int
    )

    fun resolveWidgetBackdropFilter(
        handle: GtkWidgetHandle,
        visuals: StyledVisualSpec
    ): CssFilter? {
        val attrFilter = (handle.attributes["attrBackdropFilter"] as? CssFilter)
            ?: (handle.attributes["backdropFilter"] as? CssFilter)
            ?: visuals.backdropFilter
        val attrBlurPx = (handle.attributes["attrBackdropBlurPx"] as? Float)
            ?: visuals.backdropBlurPx
        return CssFilter.backdropBlurOrFilter(attrBlurPx, attrFilter)
    }

    fun paintStyledWidgetSurface(
        impl: DesktopBaseNativeImpl<GtkWidgetHandle>,
        cr: COpaquePointer,
        width: Int,
        height: Int,
        visuals: StyledVisualSpec,
        cornerRadii: CssCornerRadii?,
        opacity: Float,
        focused: Boolean,
        darkTheme: Boolean
    ) {
        val effectiveSpec = if (focused) visuals else visuals.copy(focusRingColor = null, focusRingWidthPx = null)
        val handle = impl.nativeWidget
        val paintSpec = fromStyledVisualSpec(
            spec = effectiveSpec,
            cornerRadii = cornerRadii,
            backdropFilter = resolveWidgetBackdropFilter(handle, effectiveSpec),
            foregroundFilter = (handle.attributes["cssFilter"] as? CssFilter) ?: effectiveSpec.filter,
            opacity = opacity,
            darkTheme = darkTheme
        )
        val hasBackdrop = effectiveBackdropFilter(paintSpec)?.let {
            LinuxBackdropFilterController.shared.hasActiveEffects(it)
        } == true
        val behindCapture: ((Int, Int, Int, Int) -> PaddedBgraCapture?)? = if (hasBackdrop) {
            { padL, padT, padR, padB ->
                GtkWidgetLifecycleBridge.captureBehindWidgetPremulBgra(impl, width, height, padL, padT, padR, padB)
            }
        } else {
            null
        }
        paintSurface(
            cr = cr,
            width = width,
            height = height,
            spec = paintSpec,
            behindBgraCapture = behindCapture
        )
    }

    fun fromStyledVisualSpec(
        spec: StyledVisualSpec,
        cornerRadii: CssCornerRadii? = null,
        backdropFilter: CssFilter? = spec.backdropFilter,
        foregroundFilter: CssFilter? = spec.filter,
        opacity: Float = spec.opacity ?: 1f,
        darkTheme: Boolean = false
    ): GtkSurfacePaintSpec = GtkSurfacePaintSpec(
        backgroundColor = spec.backgroundColor,
        backgroundGradient = spec.backgroundGradient,
        textColor = spec.textColor,
        borderColor = spec.borderColor,
        borderWidthPx = spec.borderWidthPx ?: 0f,
        borderStyle = spec.borderStyle,
        cornerRadii = cornerRadii,
        fallbackRadiusPx = spec.borderRadiusPx ?: 0f,
        boxShadowColor = spec.boxShadowColor,
        boxShadowBlurPx = spec.boxShadowBlurPx ?: 0f,
        focusRingColor = spec.focusRingColor,
        focusRingWidthPx = spec.focusRingWidthPx ?: 0f,
        backdropFilter = backdropFilter,
        backdropBlurPx = spec.backdropBlurPx ?: 0f,
        foregroundFilter = foregroundFilter,
        opacity = opacity,
        darkTheme = darkTheme
    )

    fun fromSemanticSpec(
        spec: SemanticSurfacePaintSpec,
        opacity: Float = 1f,
        darkTheme: Boolean = false
    ): GtkSurfacePaintSpec = GtkSurfacePaintSpec(
        backgroundColor = spec.backgroundColor,
        backgroundGradient = spec.backgroundGradient,
        textColor = spec.textColor,
        borderColor = spec.borderColor,
        borderWidthPx = spec.borderWidthPx ?: 0f,
        borderStyle = spec.borderStyle,
        fallbackRadiusPx = spec.borderRadiusPx ?: 0f,
        boxShadowColor = spec.shadowColor,
        boxShadowBlurPx = spec.shadowBlurPx ?: 0f,
        opacity = opacity,
        darkTheme = darkTheme
    )

    fun effectiveBackdropFilter(spec: GtkSurfacePaintSpec): CssFilter? =
        CssFilter.backdropBlurOrFilter(spec.backdropBlurPx.takeIf { it > 0f }, spec.backdropFilter)
            ?.takeIf { LinuxBackdropFilterController.shared.hasActiveEffects(it) }

    fun paintSurface(
        cr: COpaquePointer,
        width: Int,
        height: Int,
        spec: GtkSurfacePaintSpec,
        behindBgraCapture: ((padLeft: Int, padTop: Int, padRight: Int, padBottom: Int) -> PaddedBgraCapture?)? = null
    ) {
        if (width <= 0 || height <= 0) return
        val wF = width.toFloat()
        val hF = height.toFloat()
        val radii = LinuxStyledSurfaceHelper.resolveClampedCornerRadii(
            radii = spec.cornerRadii,
            fallbackRadiusPx = spec.fallbackRadiusPx,
            widthPx = wF,
            heightPx = hF
        )
        val tl = radii[0].toDouble()
        val tr = radii[1].toDouble()
        val br = radii[2].toDouble()
        val bl = radii[3].toDouble()
        val effAlpha = spec.opacity.coerceIn(0f, 1f).toDouble()
        val fgFilter = spec.foregroundFilter

        val drawContent: (COpaquePointer) -> Unit = { targetCr ->
            drawDropShadowAndBoxShadow(targetCr, width, height, spec, tl, tr, br, bl, effAlpha)
            paintBackdropPass(targetCr, width, height, spec, radii, behindBgraCapture)
            paintBackgroundFill(targetCr, width, height, spec, tl, tr, br, bl, effAlpha)
            paintBordersAndFocusRing(targetCr, width, height, spec, tl, tr, br, bl, effAlpha)
        }

        if (fgFilter != null && fgFilter.hasActiveEffects &&
            (fgFilter.hasBlur || fgFilter.hasPixelOps || fgFilter.hasDropShadow)
        ) {
            paintThroughForegroundFilter(cr, width, height, fgFilter, drawContent)
        } else {
            drawContent(cr)
        }
    }

    fun pushForegroundFilterToGtkSnapshot(snapshot: COpaquePointer, filter: CssFilter?): Int {
        if (filter == null || !filter.hasActiveEffects) return 0
        var pushes = 0
        val blurPx = if (filter.hasBlur) (filter.blur?.resolvePx() ?: 0f).coerceIn(0f, 48f) else 0f
        if (blurPx > 0f) {
            Gtk4DynamicBindings.gtkSnapshotPushBlur(snapshot, blurPx.toDouble())
            pushes++
        }
        if (filter.hasPixelOps) {
            val matrix = CssFilterColorMatrix.buildMatrix4x5(filter)
            if (Gtk4DynamicBindings.gtkSnapshotPushColorMatrix(snapshot, matrix)) {
                pushes++
            }
        }
        return pushes
    }

    fun popGtkSnapshotPushes(snapshot: COpaquePointer, count: Int) {
        for (i in 0 until count) {
            Gtk4DynamicBindings.gtkSnapshotPop(snapshot)
        }
    }

    fun applyCssFilterToPremulBgraBuffer(
        pixels: IntArray,
        width: Int,
        height: Int,
        filter: CssFilter,
        regionX: Int = 0,
        regionY: Int = 0,
        regionWidth: Int = width,
        regionHeight: Int = height,
        cornerRadiusPx: Float = 0f,
        componentOpacity: Float = 1f
    ): Boolean {
        if (!LinuxBackdropFilterController.shared.hasActiveEffects(filter)) return false
        return LinuxBackdropFilterController.shared.applyToPremulBgraBuffer(
            filter = filter,
            pixels = pixels,
            strideWords = width,
            bufferWidth = width,
            bufferHeight = height,
            regionX = regionX,
            regionY = regionY,
            regionWidth = regionWidth,
            regionHeight = regionHeight,
            cornerRadiusPx = cornerRadiusPx,
            componentOpacity = componentOpacity
        )
    }

    fun premulBgraWordsToCairoSurface(
        pixels: IntArray,
        width: Int,
        height: Int,
        srcStrideWords: Int = width,
        srcOffsetX: Int = 0,
        srcOffsetY: Int = 0
    ): COpaquePointer? {
        if (width <= 0 || height <= 0 || srcStrideWords < srcOffsetX + width) return null
        if (pixels.size < (srcOffsetY + height - 1) * srcStrideWords + srcOffsetX + width) return null
        val surface = Gtk4DynamicBindings.cairoImageSurfaceCreate(width, height) ?: return null
        Gtk4DynamicBindings.cairoSurfaceFlush(surface)
        val dstPtr = Gtk4DynamicBindings.cairoImageSurfaceGetData(surface)?.reinterpret<ByteVar>()
        val stride = Gtk4DynamicBindings.cairoImageSurfaceGetStride(surface)
        if (dstPtr != null && stride >= width * 4) {
            pixels.usePinned { pinned ->
                val srcBytes = pinned.addressOf(0).reinterpret<ByteVar>()
                for (y in 0 until height) {
                    val srcByteOff = ((srcOffsetY + y) * srcStrideWords + srcOffsetX) * 4
                    memcpy(dstPtr + (y * stride), srcBytes + srcByteOff, (width * 4).toULong())
                }
            }
            Gtk4DynamicBindings.cairoSurfaceMarkDirty(surface)
        }
        return surface
    }

    fun cairoSurfaceToPremulBgraWords(surface: COpaquePointer, width: Int, height: Int): IntArray {
        val out = IntArray(width * height)
        Gtk4DynamicBindings.cairoSurfaceFlush(surface)
        val srcPtr = Gtk4DynamicBindings.cairoImageSurfaceGetData(surface)?.reinterpret<IntVar>() ?: return out
        val strideWords = (Gtk4DynamicBindings.cairoImageSurfaceGetStride(surface) / 4).coerceAtLeast(width)
        for (y in 0 until height) {
            val dstRow = y * width
            val srcRow = y * strideWords
            for (x in 0 until width) {
                out[dstRow + x] = srcPtr[srcRow + x]
            }
        }
        return out
    }

    fun premulBgraBytesToRgbaBytes(premulBgra: ByteArray, width: Int, height: Int): ByteArray {
        val count = min(width * height, premulBgra.size / 4)
        val out = ByteArray(count * 4)
        for (i in 0 until count) {
            val s = i * 4
            val b = premulBgra[s].toInt() and 0xFF
            val g = premulBgra[s + 1].toInt() and 0xFF
            val r = premulBgra[s + 2].toInt() and 0xFF
            val a = premulBgra[s + 3].toInt() and 0xFF
            if (a == 0) {
                out[s] = 0; out[s + 1] = 0; out[s + 2] = 0; out[s + 3] = 0
            } else if (a == 255) {
                out[s] = r.toByte(); out[s + 1] = g.toByte(); out[s + 2] = b.toByte(); out[s + 3] = 255.toByte()
            } else {
                out[s] = ((r * 255 + (a shr 1)) / a).coerceIn(0, 255).toByte()
                out[s + 1] = ((g * 255 + (a shr 1)) / a).coerceIn(0, 255).toByte()
                out[s + 2] = ((b * 255 + (a shr 1)) / a).coerceIn(0, 255).toByte()
                out[s + 3] = a.toByte()
            }
        }
        return out
    }

    private fun paintThroughForegroundFilter(
        cr: COpaquePointer,
        width: Int,
        height: Int,
        filter: CssFilter,
        drawContent: (COpaquePointer) -> Unit
    ) {
        val tempSurface = Gtk4DynamicBindings.cairoImageSurfaceCreate(width, height)
        if (tempSurface == null) {
            drawContent(cr)
            return
        }
        val tempCr = Gtk4DynamicBindings.cairoCreate(tempSurface)
        if (tempCr == null) {
            Gtk4DynamicBindings.cairoSurfaceDestroy(tempSurface)
            drawContent(cr)
            return
        }
        try {
            drawContent(tempCr)
            Gtk4DynamicBindings.cairoDestroy(tempCr)
            val pixels = cairoSurfaceToPremulBgraWords(tempSurface, width, height)
            applyCssFilterToPremulBgraBuffer(pixels, width, height, filter)
            val filteredSurface = premulBgraWordsToCairoSurface(pixels, width, height)
            if (filteredSurface != null) {
                Gtk4DynamicBindings.cairoSave(cr)
                Gtk4DynamicBindings.cairoSetSourceSurface(cr, filteredSurface, 0.0, 0.0)
                Gtk4DynamicBindings.cairoPaint(cr)
                Gtk4DynamicBindings.cairoRestore(cr)
                Gtk4DynamicBindings.cairoSurfaceDestroy(filteredSurface)
            } else {
                Gtk4DynamicBindings.cairoSave(cr)
                Gtk4DynamicBindings.cairoSetSourceSurface(cr, tempSurface, 0.0, 0.0)
                Gtk4DynamicBindings.cairoPaint(cr)
                Gtk4DynamicBindings.cairoRestore(cr)
            }
        } finally {
            Gtk4DynamicBindings.cairoSurfaceDestroy(tempSurface)
        }
    }

    private fun drawDropShadowAndBoxShadow(
        cr: COpaquePointer,
        width: Int,
        height: Int,
        spec: GtkSurfacePaintSpec,
        tl: Double,
        tr: Double,
        br: Double,
        bl: Double,
        effAlpha: Double
    ) {
        val shadowColor = spec.boxShadowColor ?: return
        val blur = spec.boxShadowBlurPx.coerceAtLeast(0f)
        if (blur <= 0f || shadowColor.a <= 0) return
        val layers = ceil(blur.coerceAtMost(12f)).toInt().coerceIn(2, 8)
        val baseA = (shadowColor.a / 255.0) * effAlpha
        for (i in layers downTo 1) {
            val t = i.toDouble() / layers.toDouble()
            val spread = t * (blur * 0.65)
            val alpha = baseA * (1.0 - t * 0.72) / layers * 2.2
            if (alpha <= 0.002) continue
            Gtk4DynamicBindings.cairoSave(cr)
            Gtk4DynamicBindings.cairoSetSourceRgba(
                cr,
                shadowColor.r / 255.0,
                shadowColor.g / 255.0,
                shadowColor.b / 255.0,
                alpha.coerceIn(0.0, 1.0)
            )
            Gtk4DynamicBindings.gtkWidgetSetClipPathForRoundedCorners(
                cr,
                spec.boxShadowOffsetXPx - spread,
                spec.boxShadowOffsetYPx - spread,
                width + spread * 2.0,
                height + spread * 2.0,
                tl + spread, tr + spread, br + spread, bl + spread
            )
            Gtk4DynamicBindings.cairoFill(cr)
            Gtk4DynamicBindings.cairoRestore(cr)
        }
    }

    private fun paintBackdropPass(
        cr: COpaquePointer,
        width: Int,
        height: Int,
        spec: GtkSurfacePaintSpec,
        radii: FloatArray,
        behindBgraCapture: ((padLeft: Int, padTop: Int, padRight: Int, padBottom: Int) -> PaddedBgraCapture?)?
    ) {
        val filter = effectiveBackdropFilter(spec) ?: return
        val pads = LinuxBackdropFilterController.computeHaloPadding(filter)
        val captured = behindBgraCapture?.invoke(pads[0], pads[1], pads[2], pads[3])
        if (captured != null && captured.bufferWidth > 0 && captured.bufferHeight > 0 &&
            captured.pixels.size >= captured.bufferWidth * captured.bufferHeight
        ) {
            val bufW = captured.bufferWidth
            val bufH = captured.bufferHeight
            val copy = captured.pixels.copyOf(bufW * bufH)
            val applied = LinuxBackdropFilterController.shared.applyToPremulBgraBuffer(
                filter = filter,
                pixels = copy,
                strideWords = bufW,
                bufferWidth = bufW,
                bufferHeight = bufH,
                regionX = captured.regionX,
                regionY = captured.regionY,
                regionWidth = width,
                regionHeight = height,
                cornerRadiusPx = radii[0],
                componentOpacity = spec.opacity
            )
            if (applied) {
                val img = premulBgraWordsToCairoSurface(
                    pixels = copy,
                    width = width,
                    height = height,
                    srcStrideWords = bufW,
                    srcOffsetX = captured.regionX,
                    srcOffsetY = captured.regionY
                )
                if (img != null) {
                    Gtk4DynamicBindings.cairoSave(cr)
                    Gtk4DynamicBindings.gtkWidgetSetClipPathForRoundedCorners(
                        cr, 0.0, 0.0, width.toDouble(), height.toDouble(),
                        radii[0].toDouble(), radii[1].toDouble(), radii[2].toDouble(), radii[3].toDouble()
                    )
                    Gtk4DynamicBindings.cairoClip(cr)
                    Gtk4DynamicBindings.cairoSetSourceSurface(cr, img, 0.0, 0.0)
                    Gtk4DynamicBindings.cairoPaint(cr)
                    Gtk4DynamicBindings.cairoRestore(cr)
                    Gtk4DynamicBindings.cairoSurfaceDestroy(img)
                    return
                }
            }
        }
        val blurPx = if (filter.hasBlur) (filter.blur?.resolvePx() ?: spec.backdropBlurPx) else spec.backdropBlurPx
        if (blurPx > 0f) {
            Gtk4DynamicBindings.cairoApplyBackdropFilterRegion(
                cr = cr,
                x = 0,
                y = 0,
                w = width,
                h = height,
                radiusPx = radii[0],
                filter = filter,
                opacity = spec.opacity
            )
        }
    }

    private fun paintBackgroundFill(
        cr: COpaquePointer,
        width: Int,
        height: Int,
        spec: GtkSurfacePaintSpec,
        tl: Double,
        tr: Double,
        br: Double,
        bl: Double,
        effAlpha: Double
    ) {
        val grad = spec.backgroundGradient
        val bg = spec.backgroundColor
        if (grad == null && (bg == null || bg.a <= 0)) return

        Gtk4DynamicBindings.cairoSave(cr)
        Gtk4DynamicBindings.gtkWidgetSetClipPathForRoundedCorners(
            cr, 0.0, 0.0, width.toDouble(), height.toDouble(), tl, tr, br, bl
        )
        Gtk4DynamicBindings.cairoClip(cr)

        when (grad) {
            is LinearGradient -> {
                val norm = GradientStopNormalizer.normalizeLinear(grad, width.toFloat(), height.toFloat())
                val pat = Gtk4DynamicBindings.cairoPatternCreateLinear(
                    norm.x1.toDouble(), norm.y1.toDouble(), norm.x2.toDouble(), norm.y2.toDouble()
                )
                if (pat != null) {
                    for (i in norm.positions.indices) {
                        val c = norm.colors[i]
                        Gtk4DynamicBindings.cairoPatternAddColorStopRgba(
                            pat,
                            norm.positions[i].toDouble(),
                            c.r / 255.0, c.g / 255.0, c.b / 255.0,
                            (c.a / 255.0) * effAlpha
                        )
                    }
                    Gtk4DynamicBindings.cairoSetSource(cr, pat)
                    Gtk4DynamicBindings.cairoPaint(cr)
                    Gtk4DynamicBindings.cairoPatternDestroy(pat)
                }
            }
            is RadialGradient -> {
                val norm = GradientStopNormalizer.normalizeRadial(grad, width.toFloat(), height.toFloat())
                val r = max(norm.radiusX, norm.radiusY).coerceAtLeast(1f).toDouble()
                val pat = Gtk4DynamicBindings.cairoPatternCreateRadial(
                    norm.cx.toDouble(), norm.cy.toDouble(), 0.0,
                    norm.cx.toDouble(), norm.cy.toDouble(), r
                )
                if (pat != null) {
                    for (i in norm.positions.indices) {
                        val c = norm.colors[i]
                        Gtk4DynamicBindings.cairoPatternAddColorStopRgba(
                            pat,
                            norm.positions[i].toDouble(),
                            c.r / 255.0, c.g / 255.0, c.b / 255.0,
                            (c.a / 255.0) * effAlpha
                        )
                    }
                    Gtk4DynamicBindings.cairoSetSource(cr, pat)
                    Gtk4DynamicBindings.cairoPaint(cr)
                    Gtk4DynamicBindings.cairoPatternDestroy(pat)
                }
            }
            else -> {
                if (bg != null && bg.a > 0) {
                    Gtk4DynamicBindings.cairoSetSourceRgba(
                        cr,
                        bg.r / 255.0, bg.g / 255.0, bg.b / 255.0,
                        (bg.a / 255.0) * effAlpha
                    )
                    Gtk4DynamicBindings.cairoPaint(cr)
                }
            }
        }

        val insetHighlight = spec.insetHighlightColor
        if (insetHighlight != null && insetHighlight.a > 0) {
            Gtk4DynamicBindings.cairoSetSourceRgba(
                cr,
                insetHighlight.r / 255.0,
                insetHighlight.g / 255.0,
                insetHighlight.b / 255.0,
                (insetHighlight.a / 255.0) * effAlpha
            )
            Gtk4DynamicBindings.cairoSetLineWidth(cr, 1.0)
            Gtk4DynamicBindings.cairoNewPath(cr)
            Gtk4DynamicBindings.cairoMoveTo(cr, min(tl, width * 0.5).coerceAtLeast(1.0), 0.5)
            Gtk4DynamicBindings.cairoLineTo(cr, (width - min(tr, width * 0.5)).coerceAtLeast(1.0), 0.5)
            Gtk4DynamicBindings.cairoStroke(cr)
        }

        Gtk4DynamicBindings.cairoRestore(cr)
    }

    private fun paintBordersAndFocusRing(
        cr: COpaquePointer,
        width: Int,
        height: Int,
        spec: GtkSurfacePaintSpec,
        tl: Double,
        tr: Double,
        br: Double,
        bl: Double,
        effAlpha: Double
    ) {
        val borderColor = spec.borderColor
        if (borderColor != null && borderColor.a > 0 &&
            LinuxStyledSurfaceHelper.isVisibleBorder(spec.borderStyle, spec.borderWidthPx)
        ) {
            val bw = spec.borderWidthPx.toDouble()
            Gtk4DynamicBindings.cairoSave(cr)
            Gtk4DynamicBindings.cairoSetSourceRgba(
                cr,
                borderColor.r / 255.0,
                borderColor.g / 255.0,
                borderColor.b / 255.0,
                (borderColor.a / 255.0) * effAlpha
            )
            if (LinuxStyledSurfaceHelper.isDoubleBorder(spec.borderStyle, spec.borderWidthPx)) {
                val metrics = LinuxStyledSurfaceHelper.resolveDoubleBorderMetrics(spec.borderWidthPx)
                val lineW = metrics[0].toDouble()
                val outerInset = metrics[1].toDouble()
                val innerInset = metrics[2].toDouble()
                Gtk4DynamicBindings.cairoSetLineWidth(cr, lineW)
                Gtk4DynamicBindings.gtkWidgetSetClipPathForRoundedCorners(
                    cr, outerInset, outerInset,
                    (width - outerInset * 2.0).coerceAtLeast(1.0),
                    (height - outerInset * 2.0).coerceAtLeast(1.0),
                    max(0.0, tl - outerInset), max(0.0, tr - outerInset),
                    max(0.0, br - outerInset), max(0.0, bl - outerInset)
                )
                Gtk4DynamicBindings.cairoStroke(cr)
                Gtk4DynamicBindings.gtkWidgetSetClipPathForRoundedCorners(
                    cr, innerInset, innerInset,
                    (width - innerInset * 2.0).coerceAtLeast(1.0),
                    (height - innerInset * 2.0).coerceAtLeast(1.0),
                    max(0.0, tl - innerInset), max(0.0, tr - innerInset),
                    max(0.0, br - innerInset), max(0.0, bl - innerInset)
                )
                Gtk4DynamicBindings.cairoStroke(cr)
            } else {
                val inset = bw * 0.5
                val dashes = LinuxStyledSurfaceHelper.resolveDashPatternDoubles(spec.borderStyle, spec.borderWidthPx)
                Gtk4DynamicBindings.cairoSetDash(cr, dashes, 0.0)
                Gtk4DynamicBindings.cairoSetLineWidth(cr, bw)
                Gtk4DynamicBindings.gtkWidgetSetClipPathForRoundedCorners(
                    cr, inset, inset,
                    (width - bw).coerceAtLeast(1.0),
                    (height - bw).coerceAtLeast(1.0),
                    max(0.0, tl - inset), max(0.0, tr - inset),
                    max(0.0, br - inset), max(0.0, bl - inset)
                )
                Gtk4DynamicBindings.cairoStroke(cr)
            }
            Gtk4DynamicBindings.cairoRestore(cr)
        }

        val ringColor = spec.focusRingColor
        val ringW = spec.focusRingWidthPx
        if (ringColor != null && ringColor.a > 0 && ringW > 0f) {
            Gtk4DynamicBindings.cairoSave(cr)
            Gtk4DynamicBindings.cairoSetSourceRgba(
                cr,
                ringColor.r / 255.0,
                ringColor.g / 255.0,
                ringColor.b / 255.0,
                (ringColor.a / 255.0) * effAlpha
            )
            val rw = ringW.toDouble()
            val inset = rw * 0.5
            Gtk4DynamicBindings.cairoSetLineWidth(cr, rw)
            Gtk4DynamicBindings.gtkWidgetSetClipPathForRoundedCorners(
                cr, inset, inset,
                (width - rw).coerceAtLeast(1.0),
                (height - rw).coerceAtLeast(1.0),
                max(0.0, tl - inset), max(0.0, tr - inset),
                max(0.0, br - inset), max(0.0, bl - inset)
            )
            Gtk4DynamicBindings.cairoStroke(cr)
            Gtk4DynamicBindings.cairoRestore(cr)
        }
    }

    fun measureTextSize(
        text: String,
        fontSizePx: Float,
        fontFamily: String = "Sans",
        fontWeight: Int = Gtk4DynamicBindings.PANGO_WEIGHT_NORMAL,
        italic: Boolean = false,
        maxWidthPx: Int? = null
    ): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to ceil(fontSizePx * 1.35f).toInt().coerceAtLeast(14)
        val tmpSurface = Gtk4DynamicBindings.cairoImageSurfaceCreate(4, 4)
            ?: return (text.length * (fontSizePx * 0.55f)).roundToInt() to ceil(fontSizePx * 1.35f).toInt()
        val tmpCr = Gtk4DynamicBindings.cairoCreate(tmpSurface)
        if (tmpCr == null) {
            Gtk4DynamicBindings.cairoSurfaceDestroy(tmpSurface)
            return (text.length * (fontSizePx * 0.55f)).roundToInt() to ceil(fontSizePx * 1.35f).toInt()
        }
        val layout = Gtk4DynamicBindings.pangoCairoCreateLayout(tmpCr)
        return try {
            if (layout == null) {
                (text.length * (fontSizePx * 0.55f)).roundToInt() to ceil(fontSizePx * 1.35f).toInt()
            } else {
                val desc = Gtk4DynamicBindings.pangoFontDescriptionNew()
                if (desc != null) {
                    Gtk4DynamicBindings.pangoFontDescriptionSetFamily(desc, fontFamily.ifBlank { "Sans" })
                    Gtk4DynamicBindings.pangoFontDescriptionSetWeight(desc, fontWeight)
                    Gtk4DynamicBindings.pangoFontDescriptionSetStyle(
                        desc,
                        if (italic) Gtk4DynamicBindings.PANGO_STYLE_ITALIC else Gtk4DynamicBindings.PANGO_STYLE_NORMAL
                    )
                    Gtk4DynamicBindings.pangoFontDescriptionSetAbsoluteSize(desc, (fontSizePx * Gtk4DynamicBindings.PANGO_SCALE).toDouble())
                    Gtk4DynamicBindings.pangoLayoutSetFontDescription(layout, desc)
                    Gtk4DynamicBindings.pangoFontDescriptionFree(desc)
                }
                Gtk4DynamicBindings.pangoLayoutSetText(layout, text)
                if (maxWidthPx != null && maxWidthPx > 0) {
                    Gtk4DynamicBindings.pangoLayoutSetWidth(layout, maxWidthPx * Gtk4DynamicBindings.PANGO_SCALE)
                    Gtk4DynamicBindings.pangoLayoutSetWrap(layout, Gtk4DynamicBindings.PANGO_WRAP_WORD_CHAR)
                }
                Gtk4DynamicBindings.pangoLayoutGetPixelSize(layout)
            }
        } finally {
            if (layout != null) com.nexustools.genericui.linux.LinuxGlibGioBindings.objectUnref(layout)
            Gtk4DynamicBindings.cairoDestroy(tmpCr)
            Gtk4DynamicBindings.cairoSurfaceDestroy(tmpSurface)
        }
    }

    fun drawPangoText(
        cr: COpaquePointer,
        text: String,
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        color: RgbaColor,
        fontSizePx: Float = 14f,
        fontFamily: String = "Sans",
        fontWeight: Int = Gtk4DynamicBindings.PANGO_WEIGHT_NORMAL,
        italic: Boolean = false,
        underline: Boolean = false,
        strikethrough: Boolean = false,
        align: Int = Gtk4DynamicBindings.PANGO_ALIGN_LEFT,
        singleLine: Boolean = true,
        ellipsize: Boolean = singleLine,
        opacity: Float = 1f
    ) {
        if (text.isEmpty() || width <= 1.0 || height <= 1.0) return
        val layout = Gtk4DynamicBindings.pangoCairoCreateLayout(cr) ?: return
        try {
            val desc = Gtk4DynamicBindings.pangoFontDescriptionNew()
            if (desc != null) {
                Gtk4DynamicBindings.pangoFontDescriptionSetFamily(desc, fontFamily.ifBlank { "Sans" })
                Gtk4DynamicBindings.pangoFontDescriptionSetWeight(desc, fontWeight)
                Gtk4DynamicBindings.pangoFontDescriptionSetStyle(
                    desc,
                    if (italic) Gtk4DynamicBindings.PANGO_STYLE_ITALIC else Gtk4DynamicBindings.PANGO_STYLE_NORMAL
                )
                Gtk4DynamicBindings.pangoFontDescriptionSetAbsoluteSize(desc, (fontSizePx * Gtk4DynamicBindings.PANGO_SCALE).toDouble())
                Gtk4DynamicBindings.pangoLayoutSetFontDescription(layout, desc)
                Gtk4DynamicBindings.pangoFontDescriptionFree(desc)
            }
            Gtk4DynamicBindings.pangoLayoutSetText(layout, text)
            Gtk4DynamicBindings.pangoLayoutSetWidth(layout, (width * Gtk4DynamicBindings.PANGO_SCALE).roundToInt().coerceAtLeast(Gtk4DynamicBindings.PANGO_SCALE))
            Gtk4DynamicBindings.pangoLayoutSetAlignment(layout, align)
            Gtk4DynamicBindings.pangoLayoutSetWrap(layout, Gtk4DynamicBindings.PANGO_WRAP_WORD_CHAR)
            Gtk4DynamicBindings.pangoLayoutSetEllipsize(
                layout,
                if (ellipsize) Gtk4DynamicBindings.PANGO_ELLIPSIZE_END else Gtk4DynamicBindings.PANGO_ELLIPSIZE_NONE
            )
            if (underline || strikethrough) {
                Gtk4DynamicBindings.pangoLayoutSetDecoration(layout, underline, strikethrough)
            }
            val (_, textH) = Gtk4DynamicBindings.pangoLayoutGetPixelSize(layout)
            val drawY = y + if (singleLine) ((height - textH) * 0.5).coerceAtLeast(0.0) else 0.0
            Gtk4DynamicBindings.cairoSave(cr)
            Gtk4DynamicBindings.cairoRectangle(cr, x, y, width, height)
            Gtk4DynamicBindings.cairoClip(cr)
            Gtk4DynamicBindings.cairoSetSourceRgba(
                cr,
                color.r / 255.0, color.g / 255.0, color.b / 255.0,
                (color.a / 255.0) * opacity.coerceIn(0f, 1f)
            )
            Gtk4DynamicBindings.cairoMoveTo(cr, x, drawY)
            Gtk4DynamicBindings.pangoCairoShowLayout(cr, layout)
            Gtk4DynamicBindings.cairoRestore(cr)
        } finally {
            com.nexustools.genericui.linux.LinuxGlibGioBindings.objectUnref(layout)
        }
    }
}
