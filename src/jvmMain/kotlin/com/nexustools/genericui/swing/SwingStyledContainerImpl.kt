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
    tagName,
    object : SwingLayoutPanel() {
        override fun isOptimizedDrawingEnabled(): Boolean = componentCount <= 1

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            SwingSurfacePaintUtil.withComponentTransform(this, g) { tg ->
                @Suppress("UNCHECKED_CAST")
                val provider = getClientProperty("styledVisualsProvider") as? (() -> StyledVisualSpec)
                val spec = provider?.invoke()
                    ?: (getClientProperty("styledVisuals") as? StyledVisualSpec)
                    ?: StyledVisualSpec.EMPTY
                val rawOpacity = getClientProperty("opacity") as? Float
                val componentOpacity = (rawOpacity ?: spec.opacity ?: 1f).coerceIn(0f, 1f)
                val effBackdrop = CssFilter.backdropBlurOrFilter(spec.backdropBlurPx, spec.backdropFilter)
                if (effBackdrop != null && componentOpacity > SwingSurfacePaintUtil.OPACITY_SKIP_THRESHOLD && width > 0 && height > 0) {
                    SwingSurfacePaintUtil.paintBackdropWithOpacity(
                        g = tg,
                        target = this,
                        filter = effBackdrop,
                        cornerRadiusPx = spec.borderRadiusPx ?: 0f,
                        componentOpacity = componentOpacity
                    )
                }
                SwingSurfacePaintUtil.paintStyledSurface(
                    g = tg,
                    width = width,
                    height = height,
                    spec = spec.copy(opacity = null),
                    isFocused = hasFocus(),
                    componentOpacity = componentOpacity
                )
            }
        }

        override fun paintChildren(g: Graphics) {
            val rawOpacity = getClientProperty("opacity") as? Float
            @Suppress("UNCHECKED_CAST")
            val specOpacity = ((getClientProperty("styledVisualsProvider") as? (() -> StyledVisualSpec))?.invoke()
                ?: (getClientProperty("styledVisuals") as? StyledVisualSpec))?.opacity
            val componentOpacity = (rawOpacity ?: specOpacity ?: 1f).coerceIn(0f, 1f)
            if (componentOpacity <= SwingSurfacePaintUtil.OPACITY_SKIP_THRESHOLD) return
            if (componentOpacity >= 0.999f || componentCount == 0) {
                super.paintChildren(g)
                return
            }
            SwingSurfacePaintUtil.paintChildrenWithOpacity(this, g, componentOpacity) { childG ->
                super.paintChildren(childG)
            }
        }

        override fun getPreferredSize(): Dimension {
            val base = super.getPreferredSize() ?: Dimension(0, 0)
            @Suppress("UNCHECKED_CAST")
            val provider = getClientProperty("styledVisualsProvider") as? (() -> StyledVisualSpec)
            val spec = provider?.invoke() ?: (getClientProperty("styledVisuals") as? StyledVisualSpec)
            val wSpec = spec?.widthPx?.takeIf { it > 0 }
            val hSpec = spec?.heightPx?.takeIf { it > 0 }
            if (wSpec != null && hSpec == null && layout is SwingFlexLayoutManager) {
                val measured = (layout as SwingFlexLayoutManager).measurePreferredSize(this, wSpec, 600, shrinkWrapWidth = false)
                return Dimension(wSpec, measured.height.coerceAtLeast(1))
            }
            val w = wSpec ?: base.width.coerceAtLeast(1)
            val h = hSpec ?: base.height.coerceAtLeast(1)
            return Dimension(w, h)
        }
    }
), StyledContainerNativeImpl<JComponent> {

    private var styledVisuals: StyledVisualSpec = StyledVisualSpec()

    init {
        nativeWidget.isOpaque = false
        nativeWidget.putClientProperty("antialiasing", true)
        nativeWidget.putClientProperty("tagName", tagName)
        nativeWidget.putClientProperty("styledVisualsProvider", { getStyledVisuals() })
    }

    override fun setAttribute(name: String, value: String?) {
        super<SwingContainerImpl>.setAttribute(name, value)
        nativeWidget.repaint()
    }

    protected fun applyStyledVisuals(visuals: StyledVisualSpec) {
        nativeWidget.putClientProperty("backdropBlurPx", visuals.backdropBlurPx)
        nativeWidget.putClientProperty("backdropFilter", visuals.backdropFilter)
        nativeWidget.putClientProperty(
            "-webkit-backdrop-filter",
            visuals.backdropFilter?.toCssString()
                ?: visuals.backdropBlurPx?.takeIf { it > 0f }?.let { "blur(${it}px)" }
        )
        nativeWidget.putClientProperty("styledVisuals", visuals)
        nativeWidget.putClientProperty("backgroundColor", visuals.backgroundColor)
        nativeWidget.putClientProperty("backgroundGradient", visuals.backgroundGradient)
        nativeWidget.putClientProperty("textColor", visuals.textColor)
        nativeWidget.putClientProperty("borderColor", visuals.borderColor)
        nativeWidget.putClientProperty("borderWidthPx", visuals.borderWidthPx)
        nativeWidget.putClientProperty("borderStyle", visuals.borderStyle)
        nativeWidget.putClientProperty("borderRadiusPx", visuals.borderRadiusPx)
        val bg = visuals.backgroundColor
        if (bg != null) {
            nativeWidget.background = Color(
                bg.r.coerceIn(0, 255),
                bg.g.coerceIn(0, 255),
                bg.b.coerceIn(0, 255),
                bg.a.coerceIn(0, 255)
            )
        }
        nativeWidget.isOpaque = false
        visuals.textColor?.let { c ->
            nativeWidget.foreground = Color(
                c.r.coerceIn(0, 255),
                c.g.coerceIn(0, 255),
                c.b.coerceIn(0, 255),
                c.a.coerceIn(0, 255)
            )
        }
        val computedOpacity = lastAppliedComputedOpacity
        val preserveTransitionOpacity = computedOpacity != null &&
            visuals.opacity == computedOpacity &&
            kotlin.math.abs(getOpacity() - computedOpacity) > 0.0001f
        if (!preserveTransitionOpacity) {
            visuals.opacity?.let { setOpacity(it) }
        }
        visuals.padding?.let { p -> setPadding(p) }
        if (visuals.widthPx != null || visuals.heightPx != null) {
            setPreferredSize(com.nexustools.genericui.geometry.Size(visuals.widthPx ?: 0, visuals.heightPx ?: 0))
        }
        invalidateLayoutHierarchy()
        nativeWidget.repaint()
    }

    protected fun syncVisualsToSwing() {
        applyStyledVisuals(getStyledVisuals())
    }

    override fun setStyledVisuals(visuals: StyledVisualSpec) {
        styledVisuals = sanitizeIncomingStyledVisuals(visuals)
        syncVisualsToSwing()
    }

    override fun getStyledVisuals(): StyledVisualSpec {
        val isDark = SwingSurfacePaintUtil.isDarkThemeForComponent(nativeWidget)
        val computedBase = StyledVisualSpec.fromComponentAndComputed(
            componentStyle = lastComponentStyle,
            computedStyle = lastComputedStyle,
            snapshot = snapshotForStyledVisuals(),
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
            componentOpacity: Float = 1f
        ) {
            SwingSurfacePaintUtil.paintStyledSurface(
                g = g,
                width = width,
                height = height,
                spec = spec,
                isFocused = isFocused,
                componentOpacity = componentOpacity
            )
        }
    }
}
