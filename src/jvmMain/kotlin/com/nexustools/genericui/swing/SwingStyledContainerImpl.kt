package com.nexustools.genericui.swing

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.nativeimpl.layout.StyledContainerNativeImpl
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.style.gradient.Gradient.LinearGradient
import com.nexustools.genericui.style.gradient.Gradient.RadialGradient
import com.nexustools.genericui.style.theme.SystemThemeDetector
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Java Swing `JPanel` implementation of [StyledContainerNativeImpl] with Java2D translucent RGBA,
 * [Gradient.LinearGradient] / [Gradient.RadialGradient], and rounded border rendering.
 */
class SwingStyledContainerImpl(
    classPrefix: String = "styled-container",
    val tagName: String = "div"
) : SwingContainerImpl(
    classPrefix,
    object : JPanel(BorderLayout()) {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            @Suppress("UNCHECKED_CAST")
            val provider = getClientProperty("styledVisualsProvider") as? (() -> StyledVisualSpec)
            val spec = provider?.invoke()
                ?: (getClientProperty("styledVisuals") as? StyledVisualSpec)
                ?: StyledVisualSpec.EMPTY
            val componentOpacity = (getClientProperty("opacity") as? Float) ?: 1f
            val pointerSide = getClientProperty("pointerSide") as? String
            val pointerOffset = (getClientProperty("pointerOffset") as? String)?.toFloatOrNull() ?: 0.5f
            val pointerWidthPx = (getClientProperty("pointerWidthPx") as? String)?.toFloatOrNull() ?: 12f
            val pointerHeightPx = (getClientProperty("pointerHeightPx") as? String)?.toFloatOrNull() ?: 8f
            val effBackdrop = if (getClientProperty("isTextBubble") == true) {
                null
            } else {
                CssFilter.backdropBlurOrFilter(spec.backdropBlurPx, spec.backdropFilter)
            }
            if (effBackdrop != null && width > 0 && height > 0) {
                SwingHardwareBackdropBlurRenderer.paintBackdrop(g, this, effBackdrop, spec.borderRadiusPx ?: 0f)
            }
            paintStyledSurface(
                g = g,
                width = width,
                height = height,
                spec = spec,
                isFocused = hasFocus(),
                componentOpacity = componentOpacity,
                pointerSide = pointerSide,
                pointerOffset = pointerOffset,
                pointerWidthPx = pointerWidthPx,
                pointerHeightPx = pointerHeightPx
            )
        }

        override fun getPreferredSize(): Dimension {
            val base = super.getPreferredSize() ?: Dimension(0, 0)
            @Suppress("UNCHECKED_CAST")
            val provider = getClientProperty("styledVisualsProvider") as? (() -> StyledVisualSpec)
            val spec = provider?.invoke() ?: (getClientProperty("styledVisuals") as? StyledVisualSpec)
            val w = spec?.widthPx ?: base.width
            val h = spec?.heightPx ?: base.height
            return Dimension(w, h)
        }
    }
), StyledContainerNativeImpl<JComponent> {

    private var styledVisuals: StyledVisualSpec = StyledVisualSpec()

    init {
        nativeWidget.isOpaque = false
        nativeWidget.putClientProperty("antialiasing", true)
        nativeWidget.putClientProperty("tagName", tagName)
        nativeWidget.putClientProperty("isTextBubble", isTextBubbleComponent())
        nativeWidget.putClientProperty("styledVisualsProvider", { getStyledVisuals() })
    }

    private fun isTextBubbleComponent(): Boolean {
        val raw = "$tagName $componentClassPrefix".trim().lowercase()
        val tokens = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return tokens.any {
            val norm = it.removePrefix("genericui:")
            norm == "text-bubble" || norm == "textbubble" || norm == "gui-text-bubble"
        }
    }

    override fun setAttribute(name: String, value: String?) {
        super<SwingContainerImpl>.setAttribute(name, value)
        nativeWidget.repaint()
    }

    protected fun applyStyledVisuals(visuals: StyledVisualSpec) {
        val effVisuals = if (isTextBubbleComponent()) visuals.withoutBackdropFilter() else visuals
        nativeWidget.putClientProperty("isTextBubble", isTextBubbleComponent())
        nativeWidget.putClientProperty("backdropBlurPx", effVisuals.backdropBlurPx)
        nativeWidget.putClientProperty("backdropFilter", effVisuals.backdropFilter)
        nativeWidget.putClientProperty(
            "-webkit-backdrop-filter",
            effVisuals.backdropFilter?.toCssString()
                ?: effVisuals.backdropBlurPx?.takeIf { it > 0f }?.let { "blur(${it}px)" }
        )
        nativeWidget.putClientProperty("styledVisuals", effVisuals)
        nativeWidget.putClientProperty("backgroundColor", effVisuals.backgroundColor)
        nativeWidget.putClientProperty("backgroundGradient", effVisuals.backgroundGradient)
        nativeWidget.putClientProperty("textColor", effVisuals.textColor)
        nativeWidget.putClientProperty("borderColor", effVisuals.borderColor)
        nativeWidget.putClientProperty("borderWidthPx", effVisuals.borderWidthPx)
        nativeWidget.putClientProperty("borderStyle", effVisuals.borderStyle)
        nativeWidget.putClientProperty("borderRadiusPx", effVisuals.borderRadiusPx)
        val bg = effVisuals.backgroundColor
        if (bg != null) {
            nativeWidget.background = Color(
                bg.r.coerceIn(0, 255),
                bg.g.coerceIn(0, 255),
                bg.b.coerceIn(0, 255),
                bg.a.coerceIn(0, 255)
            )
        }
        nativeWidget.isOpaque = !effVisuals.hasBackdropFilter &&
            bg != null &&
            bg.a >= 255 &&
            effVisuals.backgroundGradient == null &&
            (effVisuals.borderRadiusPx ?: 0f) <= 0f
        effVisuals.textColor?.let { c ->
            nativeWidget.foreground = Color(
                c.r.coerceIn(0, 255),
                c.g.coerceIn(0, 255),
                c.b.coerceIn(0, 255),
                c.a.coerceIn(0, 255)
            )
        }
        effVisuals.opacity?.let { setOpacity(it) }
        effVisuals.padding?.let { p ->
            nativeWidget.border = BorderFactory.createEmptyBorder(p.top, p.left, p.bottom, p.right)
        }
        if (effVisuals.widthPx != null || effVisuals.heightPx != null) {
            val currentPref = if (nativeWidget.isPreferredSizeSet) nativeWidget.preferredSize else null
            val w = effVisuals.widthPx ?: currentPref?.width ?: nativeWidget.width.takeIf { it > 0 } ?: 0
            val h = effVisuals.heightPx ?: currentPref?.height ?: nativeWidget.height.takeIf { it > 0 } ?: 0
            nativeWidget.preferredSize = Dimension(w, h)
        }
        nativeWidget.revalidate()
        nativeWidget.repaint()
    }

    protected fun syncVisualsToSwing() {
        applyStyledVisuals(getStyledVisuals())
    }

    override fun setStyledVisuals(visuals: StyledVisualSpec) {
        styledVisuals = visuals
        syncVisualsToSwing()
    }

    override fun getStyledVisuals(): StyledVisualSpec {
        val isDark = (Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()) == ThemeMode.DARK
        val computedBase = StyledVisualSpec.fromComponentAndComputed(
            componentStyle = lastComponentStyle,
            computedStyle = lastComputedStyle,
            snapshot = styleSnapshot,
            darkTheme = isDark
        )
        val merged = computedBase.mergeWith(styledVisuals)
        return if (isTextBubbleComponent()) merged.withoutBackdropFilter() else merged
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        syncVisualsToSwing()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super<SwingContainerImpl>.applyComponentStyle(style)
        syncVisualsToSwing()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super<SwingContainerImpl>.applyComponentBootstrap(bootstrap)
        syncVisualsToSwing()
    }

    companion object {
        internal fun applyBackdropToBufferedImage(
            backing: BufferedImage,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            spec: StyledVisualSpec
        ): Boolean {
            val effFilter = CssFilter.backdropBlurOrFilter(spec.backdropBlurPx, spec.backdropFilter) ?: return false
            val bufW = backing.width
            val bufH = backing.height
            if (bufW <= 0 || bufH <= 0 || width <= 0 || height <= 0) return false
            val x0 = x.coerceIn(0, bufW)
            val y0 = y.coerceIn(0, bufH)
            val x1 = (x + width).coerceIn(0, bufW)
            val y1 = (y + height).coerceIn(0, bufH)
            val copyW = x1 - x0
            val copyH = y1 - y0
            if (copyW <= 0 || copyH <= 0) return false

            val blurPx = (effFilter.blur?.resolvePx() ?: 0f).coerceIn(0f, 48f)
            val pad = if (blurPx > 0f) ceil(blurPx * 1.5f).toInt().coerceIn(1, 32) else 0
            val capX0 = (x0 - pad).coerceAtLeast(0)
            val capY0 = (y0 - pad).coerceAtLeast(0)
            val capX1 = (x1 + pad).coerceAtMost(bufW)
            val capY1 = (y1 + pad).coerceAtMost(bufH)
            val capW = capX1 - capX0
            val capH = capY1 - capY0
            if (capW <= 0 || capH <= 0) return false

            val pixels = IntArray(capW * capH)
            backing.getRGB(capX0, capY0, capW, capH, pixels, 0, capW)
            val applied = effFilter.applyBackdropToArgbRegion(
                argbPixels = pixels,
                bufferWidth = capW,
                bufferHeight = capH,
                regionX = x0 - capX0,
                regionY = y0 - capY0,
                regionWidth = copyW,
                regionHeight = copyH,
                cornerRadiusPx = (spec.borderRadiusPx ?: 0f).coerceAtLeast(0f)
            )
            if (!applied) return false
            backing.setRGB(capX0, capY0, capW, capH, pixels, 0, capW)
            return true
        }

        internal fun paintStyledSurface(
            g: Graphics,
            width: Int,
            height: Int,
            spec: StyledVisualSpec,
            isFocused: Boolean = false,
            componentOpacity: Float = 1f,
            pointerSide: String? = null,
            pointerOffset: Float = 0.5f,
            pointerWidthPx: Float = 12f,
            pointerHeightPx: Float = 8f
        ) {
            if (width <= 0 || height <= 0) return
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                val op = ((spec.opacity ?: 1f) * componentOpacity).coerceIn(0f, 1f)
                if (op <= 0.001f) return
                if (op < 0.999f) {
                    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, op)
                }
                val arc = ((spec.borderRadiusPx ?: 0f) * 2f).coerceAtLeast(0f)
                val shape = RoundRectangle2D.Float(0.5f, 0.5f, (width - 1).coerceAtLeast(1).toFloat(), (height - 1).coerceAtLeast(1).toFloat(), arc, arc)

                val grad = spec.backgroundGradient
                val bg = spec.backgroundColor
                val fillPaint: java.awt.Paint? = when {
                    grad != null -> toAwtPaint(grad, width.toFloat(), height.toFloat())
                    bg != null && bg.a > 0 -> toAwtColor(bg)
                    else -> null
                }
                if (fillPaint != null) {
                    g2.paint = fillPaint
                    g2.fill(shape)
                }

                // Render TextBubble pointer triangle when configured
                if (!pointerSide.isNullOrBlank() && pointerSide != "none" && pointerWidthPx > 0f && pointerHeightPx > 0f) {
                    val clampedOffset = pointerOffset.coerceIn(0.1f, 0.9f)
                    val triangle = Path2D.Float()
                    when (pointerSide.lowercase()) {
                        "bottom" -> {
                            val cx = width * clampedOffset
                            val baseY = (height - 1).toFloat()
                            triangle.moveTo((cx - pointerWidthPx / 2f).toDouble(), baseY.toDouble())
                            triangle.lineTo(cx.toDouble(), (baseY + pointerHeightPx).toDouble())
                            triangle.lineTo((cx + pointerWidthPx / 2f).toDouble(), baseY.toDouble())
                            triangle.closePath()
                        }
                        "top" -> {
                            val cx = width * clampedOffset
                            triangle.moveTo((cx - pointerWidthPx / 2f).toDouble(), 0.5)
                            triangle.lineTo(cx.toDouble(), (0.5f - pointerHeightPx).toDouble())
                            triangle.lineTo((cx + pointerWidthPx / 2f).toDouble(), 0.5)
                            triangle.closePath()
                        }
                        "left" -> {
                            val cy = height * clampedOffset
                            triangle.moveTo(0.5, (cy - pointerWidthPx / 2f).toDouble())
                            triangle.lineTo((0.5f - pointerHeightPx).toDouble(), cy.toDouble())
                            triangle.lineTo(0.5, (cy + pointerWidthPx / 2f).toDouble())
                            triangle.closePath()
                        }
                        "right" -> {
                            val cy = height * clampedOffset
                            val baseX = (width - 1).toFloat()
                            triangle.moveTo(baseX.toDouble(), (cy - pointerWidthPx / 2f).toDouble())
                            triangle.lineTo((baseX + pointerHeightPx).toDouble(), cy.toDouble())
                            triangle.lineTo(baseX.toDouble(), (cy + pointerWidthPx / 2f).toDouble())
                            triangle.closePath()
                        }
                    }
                    if (fillPaint != null) {
                        g2.paint = fillPaint
                        g2.fill(triangle)
                    }
                }

                val borderW = spec.borderWidthPx ?: 0f
                val borderCol = spec.borderColor
                val borderStyle = spec.borderStyle
                if (borderW > 0f && borderCol != null && borderStyle != CssBorderStyle.NONE) {
                    g2.color = toAwtColor(borderCol)
                    g2.stroke = when (borderStyle) {
                        CssBorderStyle.DASHED -> BasicStroke(borderW, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 4f), 0f)
                        CssBorderStyle.DOTTED -> BasicStroke(borderW, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(2f, 3f), 0f)
                        else -> BasicStroke(borderW)
                    }
                    g2.draw(shape)
                }

                if (isFocused && spec.focusRingColor != null) {
                    val ringW = (spec.focusRingWidthPx ?: 2f).coerceAtLeast(1f)
                    g2.color = toAwtColor(spec.focusRingColor!!)
                    g2.stroke = BasicStroke(ringW)
                    val ringShape = RoundRectangle2D.Float(1.5f, 1.5f, (width - 3).coerceAtLeast(1).toFloat(), (height - 3).coerceAtLeast(1).toFloat(), arc, arc)
                    g2.draw(ringShape)
                }
            } finally {
                g2.dispose()
            }
        }

        private fun toAwtColor(c: RgbaColor): Color =
            Color(c.r.coerceIn(0, 255), c.g.coerceIn(0, 255), c.b.coerceIn(0, 255), c.a.coerceIn(0, 255))

        private fun toAwtPaint(gradient: Gradient, w: Float, h: Float): java.awt.Paint? {
            val stops = gradient.stops
            if (stops.size < 2) return stops.firstOrNull()?.color?.let { toAwtColor(it) }
            val fractions = FloatArray(stops.size)
            val colors = Array(stops.size) { idx ->
                val rawPos = (stops[idx].position ?: (idx.toFloat() / (stops.size - 1).coerceAtLeast(1).toFloat())).coerceIn(0f, 1f)
                fractions[idx] = if (idx == 0) rawPos else maxOf(rawPos, fractions[idx - 1] + 0.0001f).coerceAtMost(1f)
                toAwtColor(stops[idx].color)
            }
            if (fractions.last() <= fractions[fractions.size - 2]) {
                for (i in fractions.indices) {
                    fractions[i] = i.toFloat() / (fractions.size - 1).toFloat()
                }
            }
            return when (gradient) {
                is LinearGradient -> {
                    val rad = Math.toRadians((gradient.angleDegrees - 90f).toDouble())
                    val cx = w / 2f
                    val cy = h / 2f
                    val halfLen = maxOf(w, h) / 2f
                    val x1 = (cx - cos(rad) * halfLen).toFloat()
                    val y1 = (cy - sin(rad) * halfLen).toFloat()
                    val x2 = (cx + cos(rad) * halfLen).toFloat()
                    val y2 = (cy + sin(rad) * halfLen).toFloat()
                    LinearGradientPaint(Point2D.Float(x1, y1), Point2D.Float(x2, y2), fractions, colors)
                }
                is RadialGradient -> {
                    val cx = (w * gradient.centerX).coerceIn(0f, w)
                    val cy = (h * gradient.centerY).coerceIn(0f, h)
                    val radius = (maxOf(w * gradient.radiusX, h * gradient.radiusY)).coerceAtLeast(1f)
                    RadialGradientPaint(Point2D.Float(cx, cy), radius, fractions, colors)
                }
                else -> toAwtColor(stops.first().color)
            }
        }
    }
}
