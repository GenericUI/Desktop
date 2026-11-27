package com.nexustools.genericui.swing

import com.nexustools.genericui.style.css.CssCornerRadii
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.FastBackdropBlurKernel
import com.nexustools.genericui.style.css.StyledVisualSpec
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Container
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.Transparency
import java.awt.Window
import java.awt.image.BufferedImage
import java.awt.image.VolatileImage
import javax.swing.JComponent
import javax.swing.JRootPane
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Shared real-time backdrop blur & filter renderer for [SwingStyledContainerImpl] and
 * [SwingStyledButtonImpl].
 *
 * - Walks up to the nearest backdrop root (`JRootPane` or outermost ancestor `Container`) and
 *   paints strictly pre-order preceding branches in Swing's reverse child paint order
 *   (`componentCount - 1 downTo 0`), capturing sibling containers, custom panels, and video surfaces.
 * - Uses Java2D GPU-accelerated `VolatileImage` multi-pass bilinear downsample/upscale ping-pong
 *   (`1/2 -> 1/4 -> 1/8`) when running on a non-headless hardware-accelerated `GraphicsConfiguration`.
 * - Falls back cleanly on headless JVMs or when CSS pixel color ops (`saturate`, `brightness`, etc.)
 *   are active to a reusable cached `BufferedImage` + zero-allocation [FastBackdropBlurKernel].
 */
object SwingHardwareBackdropBlurRenderer {

    private const val PROP_CACHE = "swingBackdropRenderCache"

    private val activeCaptureViews = ThreadLocal.withInitial { LinkedHashSet<JComponent>(4) }

    private class PaddedCaptureRegion(
        val capW: Int,
        val capH: Int,
        val regionX: Int,
        val regionY: Int,
        val translateX: Int,
        val translateY: Int
    )

    private class ComponentRenderCache {
        var viFull: VolatileImage? = null
        var lastGc: GraphicsConfiguration? = null

        var fallbackBmp: BufferedImage? = null
        var fallbackPixels: IntArray = IntArray(0)

        fun ensureFallback(w: Int, h: Int): Pair<BufferedImage, IntArray> {
            val bmp = fallbackBmp
            val targetBmp = if (bmp == null || bmp.width != w || bmp.height != h) {
                bmp?.flush()
                BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { fallbackBmp = it }
            } else {
                bmp
            }
            val needed = w * h
            if (fallbackPixels.size < needed) {
                fallbackPixels = IntArray(needed)
            }
            return targetBmp to fallbackPixels
        }

        fun ensureVolatileCapture(
            gc: GraphicsConfiguration,
            w: Int,
            h: Int
        ): VolatileImage? {
            if (lastGc !== gc) {
                flushVolatiles()
                lastGc = gc
            }
            val existing = viFull
            if (existing != null && existing.width == w && existing.height == h) {
                if (existing.validate(gc) != VolatileImage.IMAGE_INCOMPATIBLE) {
                    return existing
                }
                existing.flush()
            } else {
                existing?.flush()
            }
            val created = try {
                gc.createCompatibleVolatileImage(w, h, Transparency.TRANSLUCENT)
            } catch (_: Throwable) {
                null
            } ?: return null
            viFull = created
            return created
        }

        fun flushVolatiles() {
            viFull?.flush(); viFull = null
        }
    }

    internal fun computeDirectionalHaloPadding(filter: CssFilter): IntArray {
        val blurPx = if (filter.hasBlur) (filter.blur?.resolvePx() ?: 0f).coerceIn(0f, 128f) else 0f
        val blurPad = if (blurPx > 0f) ceil(blurPx * 1.5f).toInt().coerceIn(1, 96) else 0
        val shadow = filter.dropShadow?.takeIf { filter.hasDropShadow }
        val shadowBlurPx = if (shadow != null) (shadow.blurRadius.resolvePx() ?: 0f).coerceIn(0f, 128f) else 0f
        val shadowBlurPad = if (shadow != null && shadowBlurPx > 0f) ceil(shadowBlurPx * 1.5f).toInt().coerceIn(1, 96) else 0
        val shadowOffX = shadow?.offsetX?.resolvePx() ?: 0f
        val shadowOffY = shadow?.offsetY?.resolvePx() ?: 0f
        val shadowSpread = abs(shadow?.spreadRadius?.resolvePx() ?: 0f)
        val padLeft = if (shadow != null) max(blurPad, shadowBlurPad + ceil(max(0f, shadowOffX) + shadowSpread).toInt().coerceAtMost(64)) else blurPad
        val padRight = if (shadow != null) max(blurPad, shadowBlurPad + ceil(max(0f, -shadowOffX) + shadowSpread).toInt().coerceAtMost(64)) else blurPad
        val padTop = if (shadow != null) max(blurPad, shadowBlurPad + ceil(max(0f, shadowOffY) + shadowSpread).toInt().coerceAtMost(64)) else blurPad
        val padBottom = if (shadow != null) max(blurPad, shadowBlurPad + ceil(max(0f, -shadowOffY) + shadowSpread).toInt().coerceAtMost(64)) else blurPad
        return intArrayOf(padLeft, padTop, padRight, padBottom)
    }

    fun paintBackdrop(
        g: Graphics,
        target: JComponent,
        filter: CssFilter,
        cornerRadiusPx: Float,
        cornerRadii: CssCornerRadii? = null
    ) {
        val w = target.width
        val h = target.height
        if (w <= 0 || h <= 0 || !filter.hasActiveEffects) return
        val activeSet = activeCaptureViews.get()
        if (!activeSet.add(target)) return
        try {
            val root = resolveBackdropRoot(target) ?: return
            val path = buildAncestorPath(root, target) ?: return
            val cache = obtainCache(target)
            val region = computePaddedCaptureRegion(root, path, target, w, h, computeDirectionalHaloPadding(filter))

            val g2 = g as? Graphics2D
            val gc = if (g2 != null && !GraphicsEnvironment.isHeadless()) {
                try {
                    g2.deviceConfiguration
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }

            if (g2 != null && gc != null) {
                if (tryPaintHardwareVolatilePingPong(g2, gc, cache, root, path, target, region, filter, w, h, cornerRadiusPx, cornerRadii)) {
                    return
                }
            }

            paintSoftwareFallback(g, cache, root, path, target, region, filter, w, h, cornerRadiusPx, cornerRadii)
        } finally {
            activeSet.remove(target)
        }
    }

    private fun computePaddedCaptureRegion(
        root: Container,
        path: List<Container>,
        target: JComponent,
        w: Int,
        h: Int,
        pads: IntArray
    ): PaddedCaptureRegion {
        val (ox, oy) = computeTargetOffsetInRoot(path, target)
        val rootW = max(root.width, ox + w)
        val rootH = max(root.height, oy + h)
        val effPadL = pads[0].coerceIn(0, ox.coerceAtLeast(0))
        val effPadT = pads[1].coerceIn(0, oy.coerceAtLeast(0))
        val effPadR = pads[2].coerceIn(0, max(0, rootW - (ox + w)))
        val effPadB = pads[3].coerceIn(0, max(0, rootH - (oy + h)))
        return PaddedCaptureRegion(
            capW = w + effPadL + effPadR,
            capH = h + effPadT + effPadB,
            regionX = effPadL,
            regionY = effPadT,
            translateX = -(ox - effPadL),
            translateY = -(oy - effPadT)
        )
    }

    private fun tryPaintHardwareVolatilePingPong(
        g2: Graphics2D,
        gc: GraphicsConfiguration,
        cache: ComponentRenderCache,
        root: Container,
        path: List<Container>,
        target: JComponent,
        region: PaddedCaptureRegion,
        filter: CssFilter,
        w: Int,
        h: Int,
        cornerRadiusPx: Float,
        cornerRadii: CssCornerRadii?
    ): Boolean {
        val capW = region.capW
        val capH = region.capH
        repeat(2) {
            val full = cache.ensureVolatileCapture(gc, capW, capH) ?: return false
            val fg = full.createGraphics()
            try {
                fg.composite = AlphaComposite.Src
                fg.color = Color(0, 0, 0, 0)
                fg.fillRect(0, 0, capW, capH)
                fg.composite = AlphaComposite.SrcOver
                fg.translate(region.translateX, region.translateY)
                drawSubtreeUpToTarget(root, path, 0, target, fg)
            } finally {
                fg.dispose()
            }
            if (full.contentsLost()) return@repeat

            val snapshot = try {
                full.snapshot
            } catch (_: Throwable) {
                null
            } ?: return false
            val (outBmp, pixels) = cache.ensureFallback(capW, capH)
            snapshot.getRGB(0, 0, capW, capH, pixels, 0, capW)
            snapshot.flush()

            FastBackdropBlurKernel.applyBackdropToArgbRegion(
                filter = filter,
                argbPixels = pixels,
                bufferWidth = capW,
                bufferHeight = capH,
                regionX = region.regionX,
                regionY = region.regionY,
                regionWidth = w,
                regionHeight = h,
                cornerRadiusPx = 0f
            )
            outBmp.setRGB(0, 0, capW, capH, pixels, 0, capW)

            drawCroppedBackdrop(g2, outBmp, region, w, h, cornerRadiusPx, cornerRadii)
            return true
        }
        return false
    }

    private fun paintSoftwareFallback(
        g: Graphics,
        cache: ComponentRenderCache,
        root: Container,
        path: List<Container>,
        target: JComponent,
        region: PaddedCaptureRegion,
        filter: CssFilter,
        w: Int,
        h: Int,
        cornerRadiusPx: Float,
        cornerRadii: CssCornerRadii?
    ) {
        val capW = region.capW
        val capH = region.capH
        val (capture, pixels) = cache.ensureFallback(capW, capH)

        val capG = capture.createGraphics()
        try {
            capG.composite = AlphaComposite.Src
            capG.color = Color(0, 0, 0, 0)
            capG.fillRect(0, 0, capW, capH)
            capG.composite = AlphaComposite.SrcOver
            capG.translate(region.translateX, region.translateY)
            drawSubtreeUpToTarget(root, path, 0, target, capG)
        } finally {
            capG.dispose()
        }

        capture.getRGB(0, 0, capW, capH, pixels, 0, capW)
        FastBackdropBlurKernel.applyBackdropToArgbRegion(
            filter = filter,
            argbPixels = pixels,
            bufferWidth = capW,
            bufferHeight = capH,
            regionX = region.regionX,
            regionY = region.regionY,
            regionWidth = w,
            regionHeight = h,
            cornerRadiusPx = 0f
        )
        capture.setRGB(0, 0, capW, capH, pixels, 0, capW)

        val g2 = g.create() as Graphics2D
        try {
            drawCroppedBackdrop(g2, capture, region, w, h, cornerRadiusPx, cornerRadii)
        } finally {
            g2.dispose()
        }
    }

    private fun drawCroppedBackdrop(
        g2: Graphics2D,
        paddedImage: BufferedImage,
        region: PaddedCaptureRegion,
        w: Int,
        h: Int,
        cornerRadiusPx: Float,
        cornerRadii: CssCornerRadii?
    ) {
        val clipped = g2.create() as Graphics2D
        try {
            clipped.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            clipped.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            applyRoundedClip(clipped, w, h, cornerRadiusPx, cornerRadii)
            clipped.drawImage(
                paddedImage,
                0, 0, w, h,
                region.regionX, region.regionY, region.regionX + w, region.regionY + h,
                null
            )
        } finally {
            clipped.dispose()
        }
    }

    private fun applyRoundedClip(
        g2: Graphics2D,
        w: Int,
        h: Int,
        cornerRadiusPx: Float,
        cornerRadii: CssCornerRadii?
    ) {
        val radii = SwingSurfacePaintUtil.resolveClampedCornerRadii(cornerRadii, cornerRadiusPx, w.toFloat(), h.toFloat())
        if (radii[0] > 0.5f || radii[1] > 0.5f || radii[2] > 0.5f || radii[3] > 0.5f) {
            g2.clip(SwingSurfacePaintUtil.buildRoundedRectShape(0f, 0f, w.toFloat(), h.toFloat(), radii))
        }
    }

    private fun resolveBackdropRoot(target: JComponent): Container? {
        var cur: Container? = target.parent ?: return null
        var top: Container = cur!!
        while (cur != null) {
            if (cur is JRootPane) {
                return cur.contentPane ?: cur
            }
            val next = cur.parent ?: break
            if (next is Window) {
                top = cur
                break
            }
            top = next
            cur = next
        }
        return top
    }

    private fun buildAncestorPath(root: Container, target: JComponent): List<Container>? {
        val chain = ArrayList<Container>(8)
        var cur: Container? = target.parent
        while (cur != null) {
            chain.add(cur)
            if (cur === root) {
                chain.reverse()
                return chain
            }
            cur = cur.parent
        }
        return null
    }

    private fun computeTargetOffsetInRoot(path: List<Container>, target: JComponent): Pair<Int, Int> {
        var ox = target.x
        var oy = target.y
        for (i in 1 until path.size) {
            val c = path[i]
            ox += c.x
            oy += c.y
        }
        return ox to oy
    }

    private fun drawSubtreeUpToTarget(
        current: Container,
        path: List<Container>,
        depth: Int,
        target: JComponent,
        capG: Graphics2D
    ): Boolean {
        paintContainerBackgroundWithoutRecursion(current, capG)
        val count = current.componentCount
        if (count <= 0) return false
        val nextContainer: Container? = if (depth + 1 < path.size) path[depth + 1] else null

        // Swing Container.paintChildren paints index (componentCount - 1) downTo 0 (back to front).
        for (idx in (count - 1) downTo 0) {
            val child = current.getComponent(idx)
            if (child === target) {
                return true
            }
            if (nextContainer != null && child === nextContainer) {
                val cg = capG.create(child.x, child.y, child.width, child.height) as Graphics2D
                try {
                    drawSubtreeUpToTarget(nextContainer, path, depth + 1, target, cg)
                } finally {
                    cg.dispose()
                }
                return true
            }
            if (!child.isVisible || child.width <= 0 || child.height <= 0) continue
            val sg = capG.create(child.x, child.y, child.width, child.height) as Graphics2D
            try {
                child.paint(sg)
            } finally {
                sg.dispose()
            }
        }
        return false
    }

    private fun paintContainerBackgroundWithoutRecursion(container: Container, capG: Graphics2D) {
        if (container.isOpaque && container.background != null) {
            capG.color = container.background
            capG.fillRect(0, 0, container.width, container.height)
        }
        val jComp = container as? JComponent ?: return
        val impl = jComp.getClientProperty(SwingBaseNativeImpl.CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl
        @Suppress("UNCHECKED_CAST")
        val provider = jComp.getClientProperty("styledVisualsProvider") as? (() -> StyledVisualSpec)
        val explicitSpec = provider?.invoke()
            ?: (jComp.getClientProperty("styledVisuals") as? StyledVisualSpec)
        val effectiveSpec = if (impl != null &&
            SwingSurfacePaintUtil.shouldPaintSystemSurfaceChrome(
                nativeImpl = impl,
                tagName = impl.componentTagName,
                classPrefix = impl.componentClassPrefix,
                classes = impl.getClasses()
            )
        ) {
            SwingSurfacePaintUtil.resolveEffectiveSurfaceSpec(
                nativeImpl = impl,
                tagName = impl.componentTagName,
                classPrefix = impl.componentClassPrefix,
                classes = impl.getClasses(),
                explicitSpec = explicitSpec,
                isDark = SwingSurfacePaintUtil.isDarkThemeForComponent(jComp)
            )
        } else {
            explicitSpec
        } ?: return
        val op = (jComp.getClientProperty("opacity") as? Float) ?: 1f
        SwingStyledContainerImpl.paintStyledSurface(
            g = capG,
            width = jComp.width,
            height = jComp.height,
            spec = effectiveSpec.withoutBackdropFilter(),
            componentOpacity = op
        )
    }

    private fun obtainCache(target: JComponent): ComponentRenderCache {
        val existing = target.getClientProperty(PROP_CACHE) as? ComponentRenderCache
        if (existing != null) return existing
        val created = ComponentRenderCache()
        target.putClientProperty(PROP_CACHE, created)
        return created
    }
}
