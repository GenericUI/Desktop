package com.nexustools.genericui.swing

import com.nexustools.genericui.jvm.style.JvmBackdropCapturePlan
import com.nexustools.genericui.jvm.style.JvmBackdropCapturePlanner
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
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.VolatileImage
import javax.swing.JComponent
import javax.swing.JRootPane
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

    private class ComponentRenderCache {
        var viFull: VolatileImage? = null
        var viHalf: VolatileImage? = null
        var viQuarter: VolatileImage? = null
        var viEighth: VolatileImage? = null
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

        fun ensureVolatileChain(
            gc: GraphicsConfiguration,
            w: Int,
            h: Int,
            needQuarter: Boolean,
            needEighth: Boolean
        ): Boolean {
            if (lastGc !== gc) {
                flushVolatiles()
                lastGc = gc
            }
            val full = ensureVolatile(viFull, gc, w, h) { viFull = it } ?: return false
            val hw = max(1, (w + 1) / 2)
            val hh = max(1, (h + 1) / 2)
            val half = ensureVolatile(viHalf, gc, hw, hh) { viHalf = it } ?: return false
            if (needQuarter) {
                val qw = max(1, (hw + 1) / 2)
                val qh = max(1, (hh + 1) / 2)
                ensureVolatile(viQuarter, gc, qw, qh) { viQuarter = it } ?: return false
                if (needEighth) {
                    val ew = max(1, (qw + 1) / 2)
                    val eh = max(1, (qh + 1) / 2)
                    ensureVolatile(viEighth, gc, ew, eh) { viEighth = it } ?: return false
                }
            }
            return !full.contentsLost() && !half.contentsLost()
        }

        private fun ensureVolatile(
            existing: VolatileImage?,
            gc: GraphicsConfiguration,
            w: Int,
            h: Int,
            assign: (VolatileImage) -> Unit
        ): VolatileImage? {
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
            assign(created)
            return created
        }

        fun flushVolatiles() {
            viFull?.flush(); viFull = null
            viHalf?.flush(); viHalf = null
            viQuarter?.flush(); viQuarter = null
            viEighth?.flush(); viEighth = null
        }
    }

    fun paintBackdrop(
        g: Graphics,
        target: JComponent,
        filter: CssFilter,
        cornerRadiusPx: Float
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
            val plan = JvmBackdropCapturePlanner.planCapture(filter, w, h, minHardwareBlurPx = 4f)

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

            if (g2 != null && gc != null && plan.supportsHardwareFastBlurOnly) {
                if (tryPaintHardwareVolatilePingPong(g2, gc, cache, root, path, target, w, h, plan.blurPx, cornerRadiusPx)) {
                    return
                }
            }

            paintSoftwareFallback(g, cache, root, path, target, plan, w, h, cornerRadiusPx)
        } finally {
            activeSet.remove(target)
        }
    }

    private fun tryPaintHardwareVolatilePingPong(
        g2: Graphics2D,
        gc: GraphicsConfiguration,
        cache: ComponentRenderCache,
        root: Container,
        path: List<Container>,
        target: JComponent,
        w: Int,
        h: Int,
        blurPx: Float,
        cornerRadiusPx: Float
    ): Boolean {
        val needQuarter = blurPx >= 6f && w >= 16 && h >= 16
        val needEighth = blurPx >= 12f && w >= 32 && h >= 32

        repeat(2) {
            if (!cache.ensureVolatileChain(gc, w, h, needQuarter, needEighth)) return false
            val full = cache.viFull ?: return false
            val half = cache.viHalf ?: return false

            val fg = full.createGraphics()
            try {
                fg.composite = AlphaComposite.Src
                fg.color = Color(0, 0, 0, 0)
                fg.fillRect(0, 0, w, h)
                fg.composite = AlphaComposite.SrcOver
                val (ox, oy) = computeTargetOffsetInRoot(path, target)
                fg.translate(-ox, -oy)
                drawSubtreeUpToTarget(root, path, 0, target, fg)
            } finally {
                fg.dispose()
            }

            drawScaledBilinear(full, half, half.width, half.height)
            if (needQuarter) {
                val quarter = cache.viQuarter ?: return false
                drawScaledBilinear(half, quarter, quarter.width, quarter.height)
                if (needEighth) {
                    val eighth = cache.viEighth ?: return false
                    drawScaledBilinear(quarter, eighth, eighth.width, eighth.height)
                    drawScaledBilinear(eighth, quarter, quarter.width, quarter.height)
                }
                drawScaledBilinear(quarter, half, half.width, half.height)
            }

            if (full.contentsLost() || half.contentsLost()) return@repeat

            val clipped = g2.create() as Graphics2D
            try {
                clipped.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                clipped.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                applyRoundedClip(clipped, w, h, cornerRadiusPx)
                clipped.drawImage(half, 0, 0, w, h, null)
            } finally {
                clipped.dispose()
            }
            if (!half.contentsLost()) return true
        }
        return false
    }

    private fun drawScaledBilinear(
        src: VolatileImage,
        dst: VolatileImage,
        dstW: Int,
        dstH: Int
    ) {
        val g = dst.createGraphics()
        try {
            g.composite = AlphaComposite.Src
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
            g.drawImage(src, 0, 0, dstW, dstH, null)
        } finally {
            g.dispose()
        }
    }

    private fun paintSoftwareFallback(
        g: Graphics,
        cache: ComponentRenderCache,
        root: Container,
        path: List<Container>,
        target: JComponent,
        plan: JvmBackdropCapturePlan,
        w: Int,
        h: Int,
        cornerRadiusPx: Float
    ) {
        val dsScale = plan.downsampleScale
        val capW = plan.captureWidth
        val capH = plan.captureHeight
        val (capture, pixels) = cache.ensureFallback(capW, capH)

        val capG = capture.createGraphics()
        try {
            capG.composite = AlphaComposite.Src
            capG.color = Color(0, 0, 0, 0)
            capG.fillRect(0, 0, capW, capH)
            capG.composite = AlphaComposite.SrcOver
            if (dsScale > 1) {
                capG.scale(capW.toDouble() / w.toDouble(), capH.toDouble() / h.toDouble())
            }
            val (ox, oy) = computeTargetOffsetInRoot(path, target)
            capG.translate(-ox, -oy)
            drawSubtreeUpToTarget(root, path, 0, target, capG)
        } finally {
            capG.dispose()
        }

        capture.getRGB(0, 0, capW, capH, pixels, 0, capW)
        FastBackdropBlurKernel.applyBackdropToArgbRegion(
            filter = plan.scaledFilter,
            argbPixels = pixels,
            bufferWidth = capW,
            bufferHeight = capH,
            regionX = 0,
            regionY = 0,
            regionWidth = capW,
            regionHeight = capH,
            cornerRadiusPx = 0f
        )
        capture.setRGB(0, 0, capW, capH, pixels, 0, capW)

        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            applyRoundedClip(g2, w, h, cornerRadiusPx)
            g2.drawImage(capture, 0, 0, w, h, null)
        } finally {
            g2.dispose()
        }
    }

    private fun applyRoundedClip(g2: Graphics2D, w: Int, h: Int, cornerRadiusPx: Float) {
        val r = cornerRadiusPx.coerceIn(0f, min(w, h) * 0.5f)
        if (r > 0.5f) {
            val arc = r * 2f
            g2.clip(
                RoundRectangle2D.Float(
                    0.5f,
                    0.5f,
                    (w - 1).coerceAtLeast(1).toFloat(),
                    (h - 1).coerceAtLeast(1).toFloat(),
                    arc,
                    arc
                )
            )
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
