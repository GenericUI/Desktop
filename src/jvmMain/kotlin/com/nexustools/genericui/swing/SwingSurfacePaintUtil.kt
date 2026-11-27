package com.nexustools.genericui.swing

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssCornerRadii
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.style.gradient.Gradient.LinearGradient
import com.nexustools.genericui.style.gradient.Gradient.RadialGradient
import com.nexustools.genericui.style.mapper.BootstrapStyleSnapshot
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.system.style.SemanticSurfaceStyleResolver
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.JComponent
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Shared Swing surface-spec resolution and theme contrast helpers for explicitly styled
 * GenericUI containers and controls (`Card`, `Alert`, `Badge`, `MdiWindow`, `MdiDesktop`,
 * `OverlayWindowTitleBar`, `StyledContainer`).
 *
 * Keeps plain intermediate layout panels (`Container`, `HStack`, `VStack`, `LayeredContainer`)
 * unstyled and transparent so parent imagery/video and backdrop blur composite cleanly.
 */
internal object SwingSurfacePaintUtil {

    fun isDarkThemeForComponent(component: Component?): Boolean {
        var cur: Component? = component
        while (cur != null) {
            if (cur is JComponent) {
                val attr = (cur.getClientProperty("data-titlebar-theme") as? String)?.trim()?.lowercase()
                when (attr) {
                    "dark" -> return true
                    "light" -> return false
                }
            }
            cur = cur.parent
        }
        val appTheme = Application.current?.getResolvedTheme()
        val resolved = appTheme ?: SystemThemeDetector.resolveTheme(BootstrapColorPalette.activeThemeConfig.mode)
        return resolved == ThemeMode.DARK
    }

    fun hasClassToken(classPrefix: String, classes: Collection<String>, token: String): Boolean {
        if (classPrefix.split(Regex("\\s+")).any { it.equals(token, ignoreCase = true) }) return true
        return classes.any { cls ->
            cls.split(Regex("\\s+")).any { it.equals(token, ignoreCase = true) }
        }
    }

    fun normalizeTag(tagName: String): String =
        tagName.trim().lowercase().removePrefix("genericui:")

    fun isCardComponent(tagName: String, classPrefix: String, classes: Collection<String>): Boolean {
        val tag = normalizeTag(tagName)
        return tag == "card" || hasClassToken(classPrefix, classes, "card")
    }

    fun isAlertComponent(tagName: String, classPrefix: String, classes: Collection<String>): Boolean {
        val tag = normalizeTag(tagName)
        return tag == "alert" || hasClassToken(classPrefix, classes, "alert")
    }

    fun isBadgeComponent(tagName: String, classPrefix: String, classes: Collection<String>): Boolean {
        val tag = normalizeTag(tagName)
        return tag == "badge" || hasClassToken(classPrefix, classes, "badge")
    }

    fun isMdiWindowComponent(tagName: String, classPrefix: String, classes: Collection<String>): Boolean {
        val tag = normalizeTag(tagName)
        return tag == "mdiwindow" || tag == "mdi-window" || hasClassToken(classPrefix, classes, "mdi-window")
    }

    fun isMdiDesktopComponent(tagName: String, classPrefix: String, classes: Collection<String>): Boolean {
        val tag = normalizeTag(tagName)
        return tag == "mdidesktop" || tag == "mdi-desktop" || hasClassToken(classPrefix, classes, "mdi-desktop-surface")
    }

    internal fun resolveImplicitStyleSpec(
        nativeImpl: SwingBaseNativeImpl,
        isDark: Boolean
    ): StyledVisualSpec? {
        val fromStyles = StyledVisualSpec.fromComponentAndComputed(
            componentStyle = nativeImpl.currentComponentStyleForSurface(),
            computedStyle = nativeImpl.currentComputedStyleForSurface(),
            snapshot = null,
            darkTheme = isDark
        ).withoutBackdropFilter()
        val hasVisual = fromStyles.hasCustomBackground ||
            fromStyles.hasBorder ||
            fromStyles.borderRadiusPx != null ||
            fromStyles.boxShadowColor != null ||
            fromStyles.textColor != null
        return if (hasVisual) fromStyles else null
    }

    private val WHITESPACE_REGEX = Regex("\\s+")

    private fun collectClassTokens(classPrefix: String, classes: Collection<String>): Set<String> {
        val out = linkedSetOf<String>()
        if (classPrefix.isNotBlank()) {
            for (token in classPrefix.trim().split(WHITESPACE_REGEX)) {
                if (token.isNotEmpty()) out.add(token)
            }
        }
        for (cls in classes) {
            if (cls.isNotBlank()) {
                for (token in cls.trim().split(WHITESPACE_REGEX)) {
                    if (token.isNotEmpty()) out.add(token)
                }
            }
        }
        return out
    }

    fun shouldPaintSystemSurfaceChrome(
        nativeImpl: SwingBaseNativeImpl,
        tagName: String,
        classPrefix: String,
        classes: Collection<String>
    ): Boolean {
        if (nativeImpl is SwingStyledContainerImpl) return true
        val semanticNonBadge = !isBadgeComponent(tagName, classPrefix, classes) &&
            SemanticSurfaceStyleResolver.resolveForTagAndClasses(
                tagName = tagName,
                classTokens = collectClassTokens(classPrefix, classes),
                snapshot = nativeImpl.currentStyleSnapshotForSurface(),
                darkTheme = false
            ) != null
        if (semanticNonBadge) return true
        return nativeImpl is SwingContainerImpl &&
            nativeImpl.isInCustomStyleEngineSubtree() &&
            resolveImplicitStyleSpec(nativeImpl, isDarkThemeForComponent(nativeImpl.nativeWidget)) != null
    }

    fun resolveEffectiveSurfaceSpec(
        nativeImpl: SwingBaseNativeImpl,
        tagName: String,
        classPrefix: String,
        classes: Collection<String>,
        explicitSpec: StyledVisualSpec?,
        isDark: Boolean
    ): StyledVisualSpec? {
        val defaultSpec = resolveDefaultSurfaceSpec(
            nativeImpl = nativeImpl,
            tagName = tagName,
            classPrefix = classPrefix,
            classes = classes,
            isDark = isDark
        )
        val styleSpec = explicitSpec ?: resolveImplicitStyleSpec(nativeImpl, isDark)
        val merged = when {
            defaultSpec == null && styleSpec == null -> null
            defaultSpec == null -> styleSpec
            styleSpec == null -> defaultSpec
            else -> defaultSpec.mergeWith(styleSpec)
        } ?: return null
        val customBg = styleSpec?.backgroundColor
        if (customBg != null && styleSpec.textColor == null && merged.textColor != null && customBg.a >= 120) {
            return merged.copy(textColor = ensureReadableTextOn(merged.textColor, customBg))
        }
        return merged
    }

    fun resolveDefaultSurfaceSpec(
        nativeImpl: SwingBaseNativeImpl,
        tagName: String,
        classPrefix: String,
        classes: Collection<String>,
        isDark: Boolean
    ): StyledVisualSpec? {
        val bgType = nativeImpl.getBackgroundType()?.takeIf { it != BootstrapType.NONE }
            ?: nativeImpl.getType()?.takeIf { it != BootstrapType.NONE }
        if (isBadgeComponent(tagName, classPrefix, classes) && bgType == null) {
            return null
        }
        val baseSnapshot = nativeImpl.currentStyleSnapshotForSurface()
        val effectiveSnapshot = if (baseSnapshot.type == null || baseSnapshot.type == BootstrapType.NONE) {
            if (bgType != null) baseSnapshot.copy(type = bgType) else baseSnapshot
        } else {
            baseSnapshot
        }
        return SemanticSurfaceStyleResolver.resolveForTagAndClasses(
            tagName = tagName,
            classTokens = collectClassTokens(classPrefix, classes),
            snapshot = effectiveSnapshot,
            darkTheme = isDark
        )?.toStyledVisualSpec(includePadding = false)
    }

    fun resolveBadgeColors(
        type: BootstrapType?,
        isDark: Boolean
    ): Pair<Color, Color> {
        val effectiveType = type?.takeIf { it != BootstrapType.NONE } ?: BootstrapType.PRIMARY
        val resolved = SemanticSurfaceStyleResolver.resolveForTagAndClasses(
            tagName = "span",
            classTokens = setOf("badge"),
            snapshot = BootstrapStyleSnapshot(type = effectiveType),
            darkTheme = isDark
        )
        val bg = resolved?.backgroundColor ?: BootstrapColorPalette.resolveColor(effectiveType, isDark)
        val fg = resolved?.textColor ?: ensureReadableTextOn(
            preferred = BootstrapColorPalette.resolveForegroundOn(effectiveType, isDark),
            background = bg
        )
        return Color(bg.r, bg.g, bg.b, bg.a) to Color(fg.r, fg.g, fg.b, fg.a)
    }

    fun resolveInheritedReadableTextColor(component: Component?, fallbackIsDark: Boolean): Color {
        var cur: Component? = component?.parent
        while (cur != null) {
            if (cur is JComponent) {
                val impl = cur.getClientProperty(SwingBaseNativeImpl.CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl
                if (impl != null && shouldPaintSystemSurfaceChrome(impl, impl.componentTagName, impl.componentClassPrefix, impl.getClasses())) {
                    val isDark = isDarkThemeForComponent(cur)
                    val spec = resolveEffectiveSurfaceSpec(
                        nativeImpl = impl,
                        tagName = impl.componentTagName,
                        classPrefix = impl.componentClassPrefix,
                        classes = impl.getClasses(),
                        explicitSpec = (impl as? SwingStyledContainerImpl)?.getStyledVisuals(),
                        isDark = isDark
                    )
                    spec?.textColor?.let { return Color(it.r, it.g, it.b, it.a) }
                    spec?.backgroundColor?.takeIf { it.a >= 120 }?.let { bg ->
                        val textRgba = ensureReadableTextOn(null, bg)
                        return Color(textRgba.r, textRgba.g, textRgba.b)
                    }
                }
            }
            cur = cur.parent
        }
        val effectiveColors = BootstrapColorPalette.activeThemeConfig.resolveEffectiveColors(fallbackIsDark)
        val fallback = effectiveColors.onSurface ?: effectiveColors.onBackground
            ?: if (fallbackIsDark) RgbaColor(235, 235, 240) else RgbaColor(28, 28, 30)
        return Color(fallback.r, fallback.g, fallback.b)
    }

    fun blendRgb(base: RgbaColor, overlay: RgbaColor, weightOverlay: Float): RgbaColor =
        SemanticSurfaceStyleResolver.blendRgb(base, overlay, weightOverlay)

    fun relativeLuminance(c: RgbaColor): Double =
        SemanticSurfaceStyleResolver.relativeLuminance(c)

    fun ensureReadableTextOn(preferred: RgbaColor?, background: RgbaColor): RgbaColor =
        SemanticSurfaceStyleResolver.ensureReadableTextOn(preferred, background)

    const val OPACITY_SKIP_THRESHOLD: Float = 0.004f

    fun resolveClampedCornerRadii(
        radii: CssCornerRadii?,
        fallbackRadiusPx: Float?,
        widthPx: Float,
        heightPx: Float
    ): FloatArray {
        if (widthPx <= 0f || heightPx <= 0f) return FloatArray(4)
        var tl: Float
        var tr: Float
        var br: Float
        var bl: Float
        if (radii != null) {
            tl = (radii.topLeft.resolvePx(widthPx) ?: 0f).coerceAtLeast(0f)
            tr = (radii.topRight.resolvePx(widthPx) ?: 0f).coerceAtLeast(0f)
            br = (radii.bottomRight.resolvePx(widthPx) ?: 0f).coerceAtLeast(0f)
            bl = (radii.bottomLeft.resolvePx(widthPx) ?: 0f).coerceAtLeast(0f)
        } else {
            val uniform = (fallbackRadiusPx ?: 0f).coerceAtLeast(0f)
            tl = uniform; tr = uniform; br = uniform; bl = uniform
        }
        var scale = 1f
        if (tl + tr > widthPx) scale = min(scale, widthPx / (tl + tr))
        if (bl + br > widthPx) scale = min(scale, widthPx / (bl + br))
        if (tl + bl > heightPx) scale = min(scale, heightPx / (tl + bl))
        if (tr + br > heightPx) scale = min(scale, heightPx / (tr + br))
        if (scale < 1f) {
            tl *= scale; tr *= scale; br *= scale; bl *= scale
        }
        return floatArrayOf(tl, tr, br, bl)
    }

    fun buildRoundedRectShape(x: Float, y: Float, w: Float, h: Float, radii: FloatArray): Shape {
        val tl = radii[0]
        val tr = radii[1]
        val br = radii[2]
        val bl = radii[3]
        if (tl == tr && tr == br && br == bl) {
            val arc = tl * 2f
            return RoundRectangle2D.Float(x, y, w, h, arc, arc)
        }
        val path = Path2D.Float()
        val r = x + w
        val b = y + h
        path.moveTo((x + tl).toDouble(), y.toDouble())
        path.lineTo((r - tr).toDouble(), y.toDouble())
        if (tr > 0f) path.quadTo(r.toDouble(), y.toDouble(), r.toDouble(), (y + tr).toDouble())
        else path.lineTo(r.toDouble(), y.toDouble())
        path.lineTo(r.toDouble(), (b - br).toDouble())
        if (br > 0f) path.quadTo(r.toDouble(), b.toDouble(), (r - br).toDouble(), b.toDouble())
        else path.lineTo(r.toDouble(), b.toDouble())
        path.lineTo((x + bl).toDouble(), b.toDouble())
        if (bl > 0f) path.quadTo(x.toDouble(), b.toDouble(), x.toDouble(), (b - bl).toDouble())
        else path.lineTo(x.toDouble(), b.toDouble())
        path.lineTo(x.toDouble(), (y + tl).toDouble())
        if (tl > 0f) path.quadTo(x.toDouble(), y.toDouble(), (x + tl).toDouble(), y.toDouble())
        else path.lineTo(x.toDouble(), y.toDouble())
        path.closePath()
        return path
    }

    fun isBackdropAttributeName(name: String): Boolean =
        name.equals("backdrop-filter", ignoreCase = true) ||
            name.equals("-webkit-backdrop-filter", ignoreCase = true) ||
            name.equals("backdropFilter", ignoreCase = true) ||
            name.equals("backdropBlurPx", ignoreCase = true)

    fun resolveEffectiveBackdropFilter(impl: SwingBaseNativeImpl, spec: StyledVisualSpec): CssFilter? {
        val attrFilterRaw = impl.getAttribute("backdrop-filter")
            ?: impl.getAttribute("-webkit-backdrop-filter")
            ?: impl.getAttribute("backdropFilter")
        val attrFilter = attrFilterRaw?.let { CssFilter.parse(it) }
        val attrBlurPx = impl.getAttribute("backdropBlurPx")?.toFloatOrNull()
        val effectiveFilter = attrFilter ?: spec.backdropFilter
        val effectiveBlurPx = attrBlurPx ?: spec.backdropBlurPx
        return CssFilter.backdropBlurOrFilter(effectiveBlurPx, effectiveFilter)
    }

    fun resolveStateVisuals(
        computedBase: StyledVisualSpec,
        baseVisuals: StyledVisualSpec,
        computedStyle: ComputedStyle?,
        isEnabled: Boolean,
        hovered: Boolean,
        focused: Boolean,
        activePressed: Boolean,
        hoverVisuals: StyledVisualSpec? = null,
        focusVisuals: StyledVisualSpec? = null,
        activeVisuals: StyledVisualSpec? = null,
        disabledVisuals: StyledVisualSpec? = null
    ): StyledVisualSpec {
        var effective = computedBase.mergeWith(baseVisuals)
        if (!isEnabled) {
            val computedDisabled = computedStyle?.disabledStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            return effective.mergeWith(computedDisabled).mergeWith(disabledVisuals)
        }
        if (hovered) {
            val computedHover = computedStyle?.hoverStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(computedHover).mergeWith(hoverVisuals)
        }
        if (focused) {
            val computedFocus = computedStyle?.focusStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(computedFocus).mergeWith(focusVisuals)
        }
        if (activePressed) {
            val computedActive = computedStyle?.activeStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(computedActive).mergeWith(activeVisuals)
        }
        return effective
    }

    fun paintBackdropWithOpacity(
        g: Graphics,
        target: JComponent,
        filter: CssFilter,
        cornerRadiusPx: Float,
        componentOpacity: Float,
        cornerRadii: CssCornerRadii? = null
    ) {
        val alpha = componentOpacity.coerceIn(0f, 1f)
        if (alpha <= OPACITY_SKIP_THRESHOLD || target.width <= 0 || target.height <= 0) return
        val blurG2 = g.create() as? Graphics2D
        if (blurG2 != null) {
            try {
                if (alpha < 0.999f) {
                    blurG2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha)
                }
                SwingHardwareBackdropBlurRenderer.paintBackdrop(blurG2, target, filter, cornerRadiusPx, cornerRadii)
            } finally {
                blurG2.dispose()
            }
        } else {
            SwingHardwareBackdropBlurRenderer.paintBackdrop(g, target, filter, cornerRadiusPx, cornerRadii)
        }
    }

    fun paintChildrenWithOpacity(
        container: JComponent,
        g: Graphics,
        opacity: Float,
        superPaintChildren: (Graphics) -> Unit
    ) {
        val op = opacity.coerceIn(0f, 1f)
        if (op <= OPACITY_SKIP_THRESHOLD) return
        if (op >= 0.999f || container.componentCount == 0) {
            superPaintChildren(g)
            return
        }
        val w = container.width
        val h = container.height
        if (w <= 0 || h <= 0) return

        val g2 = g as? Graphics2D
        if (g2 == null) {
            superPaintChildren(g)
            return
        }
        val tf = g2.transform
        val hasShearOrRot = tf.shearX != 0.0 || tf.shearY != 0.0
        val sx = if (!hasShearOrRot) abs(tf.scaleX).coerceIn(1.0, 4.0) else 1.0
        val sy = if (!hasShearOrRot) abs(tf.scaleY).coerceIn(1.0, 4.0) else 1.0
        val bw = ceil(w * sx).toInt().coerceAtLeast(1)
        val bh = ceil(h * sy).toInt().coerceAtLeast(1)

        val cached = container.getClientProperty("swingOpacityChildrenBuffer") as? BufferedImage
        val buffer = if (cached != null && cached.width == bw && cached.height == bh) {
            cached
        } else {
            cached?.flush()
            BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB).also {
                container.putClientProperty("swingOpacityChildrenBuffer", it)
            }
        }

        val bufG = buffer.createGraphics()
        try {
            bufG.composite = AlphaComposite.Clear
            bufG.fillRect(0, 0, bw, bh)
            bufG.composite = AlphaComposite.SrcOver
            if (sx != 1.0 || sy != 1.0) {
                bufG.scale(sx, sy)
            }
            bufG.clip = g2.clip
            superPaintChildren(bufG)
        } finally {
            bufG.dispose()
        }

        val drawG = g2.create() as Graphics2D
        try {
            drawG.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, op)
            drawG.drawImage(buffer, 0, 0, w, h, null)
        } finally {
            drawG.dispose()
        }
    }

    inline fun withComponentTransform(
        comp: JComponent,
        g: Graphics,
        block: (Graphics) -> Unit
    ) {
        val impl = comp.getClientProperty(SwingBaseNativeImpl.CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl
        val tf = impl?.resolveEffectiveTransform2D()
        if (tf == null || (tf.scaleX == 1f && tf.scaleY == 1f && tf.rotateDeg == 0f)) {
            block(g)
            return
        }
        val g2 = g.create() as? Graphics2D
        if (g2 == null) {
            block(g)
            return
        }
        try {
            val cx = comp.width / 2.0
            val cy = comp.height / 2.0
            g2.translate(cx, cy)
            if (tf.rotateDeg != 0f) {
                g2.rotate(Math.toRadians(tf.rotateDeg.toDouble()))
            }
            if (tf.scaleX != 1f || tf.scaleY != 1f) {
                g2.scale(tf.scaleX.toDouble(), tf.scaleY.toDouble())
            }
            g2.translate(-cx, -cy)
            block(g2)
        } finally {
            g2.dispose()
        }
    }

    fun paintStyledSurface(
        g: Graphics,
        width: Int,
        height: Int,
        spec: StyledVisualSpec,
        isFocused: Boolean = false,
        componentOpacity: Float = 1f,
        cornerRadii: CssCornerRadii? = null
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
            val sw = (width - 1).coerceAtLeast(1).toFloat()
            val sh = (height - 1).coerceAtLeast(1).toFloat()
            val radii = resolveClampedCornerRadii(cornerRadii, spec.borderRadiusPx, sw, sh)
            val shape = buildRoundedRectShape(0.5f, 0.5f, sw, sh, radii)

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

            val borderW = spec.borderWidthPx ?: 0f
            val borderCol = spec.borderColor
            val borderStyle = spec.borderStyle
            if (borderW > 0f && borderCol != null && borderStyle != CssBorderStyle.NONE) {
                g2.color = toAwtColor(borderCol)
                g2.stroke = when (borderStyle) {
                    CssBorderStyle.DASHED -> BasicStroke(
                        borderW,
                        BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_MITER,
                        10f,
                        floatArrayOf(6f, 4f),
                        0f
                    )
                    CssBorderStyle.DOTTED -> BasicStroke(
                        borderW,
                        BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND,
                        10f,
                        floatArrayOf(2f, 3f),
                        0f
                    )
                    else -> BasicStroke(borderW)
                }
                g2.draw(shape)
            }

            val ringCol = spec.focusRingColor
            if (isFocused && ringCol != null) {
                val ringW = (spec.focusRingWidthPx ?: 2f).coerceAtLeast(1f)
                g2.color = toAwtColor(ringCol)
                g2.stroke = BasicStroke(ringW)
                val ringBoxW = (width - 3).coerceAtLeast(1).toFloat()
                val ringBoxH = (height - 3).coerceAtLeast(1).toFloat()
                val ringMaxRadius = minOf(ringBoxW, ringBoxH) * 0.5f
                val ringRadii = FloatArray(4) { i -> minOf((radii[i] - 1f).coerceAtLeast(0f), ringMaxRadius) }
                val ringShape = buildRoundedRectShape(
                    1.5f,
                    1.5f,
                    ringBoxW,
                    ringBoxH,
                    ringRadii
                )
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
