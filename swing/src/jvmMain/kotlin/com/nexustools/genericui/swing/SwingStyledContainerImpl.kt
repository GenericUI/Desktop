package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.nativeimpl.layout.StyledContainerNativeImpl
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
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
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.cos
import kotlin.math.sin

/**
 * Java Swing `JPanel` implementation of [StyledContainerNativeImpl] with Java2D translucent RGBA,
 * [Gradient.LinearGradient] / [Gradient.RadialGradient], and rounded border rendering.
 */
class SwingStyledContainerImpl(
    classPrefix: String = "styled-container"
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
            paintStyledSurface(g, width, height, spec, hasFocus())
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
        nativeWidget.putClientProperty("styledVisualsProvider", { getStyledVisuals() })
    }

    protected fun applyStyledVisuals(visuals: StyledVisualSpec) {
        nativeWidget.putClientProperty("styledVisuals", visuals)
        val bg = visuals.backgroundColor
        if (bg != null) {
            nativeWidget.background = Color(
                bg.r.coerceIn(0, 255),
                bg.g.coerceIn(0, 255),
                bg.b.coerceIn(0, 255),
                bg.a.coerceIn(0, 255)
            )
        }
        nativeWidget.isOpaque = bg != null &&
            bg.a >= 255 &&
            visuals.backgroundGradient == null &&
            (visuals.borderRadiusPx ?: 0f) <= 0f
        visuals.textColor?.let { c ->
            nativeWidget.foreground = Color(
                c.r.coerceIn(0, 255),
                c.g.coerceIn(0, 255),
                c.b.coerceIn(0, 255),
                c.a.coerceIn(0, 255)
            )
        }
        visuals.opacity?.let { setOpacity(it) }
        visuals.padding?.let { p ->
            nativeWidget.border = BorderFactory.createEmptyBorder(p.top, p.left, p.bottom, p.right)
        }
        if (visuals.widthPx != null || visuals.heightPx != null) {
            val currentPref = if (nativeWidget.isPreferredSizeSet) nativeWidget.preferredSize else null
            val w = visuals.widthPx ?: currentPref?.width ?: nativeWidget.width.takeIf { it > 0 } ?: 0
            val h = visuals.heightPx ?: currentPref?.height ?: nativeWidget.height.takeIf { it > 0 } ?: 0
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
        val isDark = SystemThemeDetector.detectSystemTheme() == ThemeMode.DARK
        val computedBase = StyledVisualSpec.fromComponentAndComputed(
            componentStyle = lastComponentStyle,
            computedStyle = lastComputedStyle,
            snapshot = styleSnapshot,
            darkTheme = isDark
        )
        return computedBase.mergeWith(styledVisuals)
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
        internal fun paintStyledSurface(g: Graphics, width: Int, height: Int, spec: StyledVisualSpec, isFocused: Boolean = false) {
            if (width <= 0 || height <= 0) return
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                val op = (spec.opacity ?: 1f).coerceIn(0f, 1f)
                if (op < 0.999f) {
                    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, op)
                }
                val arc = ((spec.borderRadiusPx ?: 0f) * 2f).coerceAtLeast(0f)
                val shape = RoundRectangle2D.Float(0.5f, 0.5f, (width - 1).coerceAtLeast(1).toFloat(), (height - 1).coerceAtLeast(1).toFloat(), arc, arc)

                val grad = spec.backgroundGradient
                val bg = spec.backgroundColor
                if (grad != null) {
                    val paint = toAwtPaint(grad, width.toFloat(), height.toFloat())
                    if (paint != null) {
                        g2.paint = paint
                        g2.fill(shape)
                    }
                } else if (bg != null && bg.a > 0) {
                    g2.color = toAwtColor(bg)
                    g2.fill(shape)
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
