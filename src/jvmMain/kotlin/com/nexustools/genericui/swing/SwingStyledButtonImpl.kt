package com.nexustools.genericui.swing

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.nativeimpl.action.StyledButtonNativeImpl
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.theme.SystemThemeDetector
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Java Swing `JButton` implementation of [StyledButtonNativeImpl] with interactive
 * `:hover`, `:focus`, `:active`, and `:disabled` [StyledVisualSpec] resolution.
 */
class SwingStyledButtonImpl(
    classPrefix: String = "styled-btn"
) : SwingButtonImpl(
    classPrefix,
    object : JButton() {
        override fun contains(x: Int, y: Int): Boolean {
            val impl = getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl ?: return super.contains(x, y)
            return impl.hitTestContains(this, x, y, super.contains(x, y))
        }

        override fun paintComponent(g: Graphics) {
            val rawOpacity = getClientProperty("opacity") as? Float
            @Suppress("UNCHECKED_CAST")
            val provider = getClientProperty("styledButtonVisualsProvider") as? (() -> StyledVisualSpec)
            val spec = provider?.invoke()
                ?: (getClientProperty("styledButtonVisuals") as? StyledVisualSpec)
                ?: StyledVisualSpec.EMPTY
            val componentOpacity = (rawOpacity ?: spec.opacity ?: 1f).coerceIn(0f, 1f)
            if (componentOpacity <= SwingSurfacePaintUtil.OPACITY_SKIP_THRESHOLD) return
            SwingSurfacePaintUtil.withComponentTransform(this, g) { tg ->
                if (!isContentAreaFilled) {
                    val focusedState = (getClientProperty("styledButtonFocused") as? Boolean) ?: hasFocus()
                    val impl = getClientProperty(SwingBaseNativeImpl.CLIENT_PROPERTY_KEY) as? SwingStyledButtonImpl
                    val effBackdrop = if (impl != null) {
                        SwingSurfacePaintUtil.resolveEffectiveBackdropFilter(impl, spec)
                    } else {
                        CssFilter.backdropBlurOrFilter(spec.backdropBlurPx, spec.backdropFilter)
                    }
                    val cornerRadii = impl?.currentComputedStyleForSurface()?.borderRadius
                    if (effBackdrop != null && componentOpacity > SwingSurfacePaintUtil.OPACITY_SKIP_THRESHOLD && width > 0 && height > 0) {
                        SwingSurfacePaintUtil.paintBackdropWithOpacity(
                            g = tg,
                            target = this,
                            filter = effBackdrop,
                            cornerRadiusPx = spec.borderRadiusPx ?: 0f,
                            componentOpacity = componentOpacity,
                            cornerRadii = cornerRadii
                        )
                    }
                    SwingSurfacePaintUtil.paintStyledSurface(
                        g = tg,
                        width = width,
                        height = height,
                        spec = spec.copy(opacity = null),
                        isFocused = focusedState,
                        componentOpacity = componentOpacity,
                        cornerRadii = cornerRadii
                    )
                }
                if (componentOpacity < 0.999f) {
                    val fgG2 = tg.create() as? Graphics2D
                    if (fgG2 != null) {
                        try {
                            fgG2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, componentOpacity)
                            super.paintComponent(fgG2)
                        } finally {
                            fgG2.dispose()
                        }
                        return@withComponentTransform
                    }
                }
                super.paintComponent(tg)
            }
        }

        override fun getPreferredSize(): Dimension {
            @Suppress("UNCHECKED_CAST")
            val provider = getClientProperty("styledButtonVisualsProvider") as? (() -> StyledVisualSpec)
            val spec = provider?.invoke() ?: (getClientProperty("styledButtonVisuals") as? StyledVisualSpec)
            if (spec?.widthPx != null && spec.heightPx != null) {
                return Dimension(spec.widthPx, spec.heightPx)
            }
            val intrinsic = ui?.getPreferredSize(this) ?: super.getPreferredSize() ?: Dimension(0, 0)
            val hidpiWidthCushion = if (text.orEmpty().length >= 2) 10 else 0
            val w = spec?.widthPx ?: (intrinsic.width + hidpiWidthCushion)
            val h = spec?.heightPx ?: intrinsic.height
            return Dimension(w, h)
        }
    }
), StyledButtonNativeImpl<JComponent> {

    private var baseVisuals: StyledVisualSpec = StyledVisualSpec()
    private var hoverVisuals: StyledVisualSpec? = null
    private var focusVisuals: StyledVisualSpec? = null
    private var activeVisuals: StyledVisualSpec? = null
    private var disabledVisuals: StyledVisualSpec? = null

    private var hovered: Boolean = false
    private var focused: Boolean = false
    private var activePressed: Boolean = false

    init {
        nativeWidget.isContentAreaFilled = false
        nativeWidget.isBorderPainted = false
        nativeWidget.isFocusPainted = false
        nativeWidget.isOpaque = false
        nativeWidget.putClientProperty("antialiasing", true)
        nativeWidget.putClientProperty("styledButtonVisualsProvider", { getStyledVisuals() })
        nativeWidget.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                setHoveredState(true)
            }

            override fun mouseExited(e: MouseEvent) {
                setHoveredState(false)
                setActivePressedState(false)
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    setActivePressedState(true)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                setActivePressedState(false)
            }
        })
        nativeWidget.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                setFocusedState(true)
            }

            override fun focusLost(e: FocusEvent) {
                setFocusedState(false)
            }
        })
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        nativeWidget.isContentAreaFilled = false
        nativeWidget.isBorderPainted = false
        nativeWidget.isFocusPainted = false
        nativeWidget.isOpaque = false
    }

    override fun setAttribute(name: String, value: String?) {
        super<SwingButtonImpl>.setAttribute(name, value)
        if (SwingSurfacePaintUtil.isBackdropAttributeName(name)) {
            syncCurrentVisuals()
        } else {
            nativeWidget.repaint()
        }
    }

    private fun syncCurrentVisuals() {
        val current = getStyledVisuals()
        val effBackdrop = SwingSurfacePaintUtil.resolveEffectiveBackdropFilter(this, current)
        nativeWidget.isContentAreaFilled = false
        nativeWidget.isBorderPainted = false
        nativeWidget.isFocusPainted = false
        nativeWidget.isOpaque = false
        nativeWidget.putClientProperty("backdropBlurPx", current.backdropBlurPx)
        nativeWidget.putClientProperty("backdropFilter", effBackdrop ?: current.backdropFilter)
        nativeWidget.putClientProperty(
            "-webkit-backdrop-filter",
            effBackdrop?.toCssString()
        )
        nativeWidget.putClientProperty("styledButtonVisuals", current)
        nativeWidget.putClientProperty("styledButtonFocused", focused)
        nativeWidget.putClientProperty("backgroundColor", current.backgroundColor)
        nativeWidget.putClientProperty("backgroundGradient", current.backgroundGradient)
        nativeWidget.putClientProperty("textColor", current.textColor)
        nativeWidget.putClientProperty("borderColor", current.borderColor)
        nativeWidget.putClientProperty("borderWidthPx", current.borderWidthPx)
        nativeWidget.putClientProperty("borderStyle", current.borderStyle)
        nativeWidget.putClientProperty("borderRadiusPx", current.borderRadiusPx)
        nativeWidget.putClientProperty("focusRingColor", current.focusRingColor)
        nativeWidget.putClientProperty("focusRingWidthPx", current.focusRingWidthPx)
        current.backgroundColor?.let { c ->
            nativeWidget.background = Color(c.r.coerceIn(0, 255), c.g.coerceIn(0, 255), c.b.coerceIn(0, 255), c.a.coerceIn(0, 255))
        }
        current.textColor?.let { c ->
            nativeWidget.foreground = Color(c.r.coerceIn(0, 255), c.g.coerceIn(0, 255), c.b.coerceIn(0, 255), c.a.coerceIn(0, 255))
        }
        val computedOpacity = lastAppliedComputedOpacity
        val preserveTransitionOpacity = computedOpacity != null &&
            current.opacity == computedOpacity &&
            kotlin.math.abs(getOpacity() - computedOpacity) > 0.0001f
        if (!preserveTransitionOpacity) {
            current.opacity?.let { setOpacity(it) }
        }
        current.padding?.let { pad ->
            nativeWidget.border = BorderFactory.createEmptyBorder(pad.top, pad.left, pad.bottom, pad.right)
        }
        if (current.widthPx != null && current.heightPx != null) {
            nativeWidget.preferredSize = Dimension(current.widthPx, current.heightPx)
        } else if (nativeWidget.isPreferredSizeSet) {
            nativeWidget.preferredSize = null
        }
        nativeWidget.revalidate()
        nativeWidget.repaint()
    }

    fun getStyledVisuals(): StyledVisualSpec = resolveCurrentVisuals()

    override fun setBaseVisuals(visuals: StyledVisualSpec) {
        baseVisuals = visuals
        syncCurrentVisuals()
    }

    override fun getBaseVisuals(): StyledVisualSpec = baseVisuals

    override fun setHoverVisuals(visuals: StyledVisualSpec?) {
        hoverVisuals = visuals
        syncCurrentVisuals()
    }

    override fun getHoverVisuals(): StyledVisualSpec? = hoverVisuals

    override fun setFocusVisuals(visuals: StyledVisualSpec?) {
        focusVisuals = visuals
        syncCurrentVisuals()
    }

    override fun getFocusVisuals(): StyledVisualSpec? = focusVisuals

    override fun setActiveVisuals(visuals: StyledVisualSpec?) {
        activeVisuals = visuals
        syncCurrentVisuals()
    }

    override fun getActiveVisuals(): StyledVisualSpec? = activeVisuals

    override fun setDisabledVisuals(visuals: StyledVisualSpec?) {
        disabledVisuals = visuals
        syncCurrentVisuals()
    }

    override fun getDisabledVisuals(): StyledVisualSpec? = disabledVisuals

    override fun setHoveredState(hovered: Boolean) {
        this.hovered = hovered && isEnabled()
        syncCurrentVisuals()
    }

    override fun isHoveredState(): Boolean = hovered

    override fun setFocusedState(focused: Boolean) {
        val effective = focused && isEnabled() && isEffectivelyFocusableInSwing()
        this.focused = effective
        if (effective) {
            requestAwtFocusOnEdt(nativeWidget)
        } else {
            clearAwtFocusOnEdtIfOwner(nativeWidget)
        }
        syncCurrentVisuals()
    }

    override fun isFocusedState(): Boolean = focused || nativeWidget.isFocusOwner

    override fun setActivePressedState(active: Boolean) {
        this.activePressed = active && isEnabled()
        syncCurrentVisuals()
    }

    override fun isActivePressedState(): Boolean = activePressed

    override fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            this.hovered = false
            this.focused = false
            this.activePressed = false
        }
        super.setEnabled(enabled)
        syncCurrentVisuals()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super.applyComputedStyle(style)
        syncCurrentVisuals()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super<SwingButtonImpl>.applyComponentStyle(style)
        syncCurrentVisuals()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super<SwingButtonImpl>.applyComponentBootstrap(bootstrap)
        syncCurrentVisuals()
    }

    override fun resolveCurrentVisuals(): StyledVisualSpec {
        val isDark = SwingSurfacePaintUtil.isDarkThemeForComponent(nativeWidget)
        val computedBase = StyledVisualSpec.fromComponentAndComputed(
            componentStyle = lastComponentStyle,
            computedStyle = lastComputedStyle,
            snapshot = styleSnapshot,
            darkTheme = isDark
        )
        return SwingSurfacePaintUtil.resolveStateVisuals(
            computedBase = computedBase,
            baseVisuals = baseVisuals,
            computedStyle = lastComputedStyle,
            isEnabled = isEnabled(),
            hovered = hovered,
            focused = focused,
            activePressed = activePressed,
            hoverVisuals = hoverVisuals,
            focusVisuals = focusVisuals,
            activeVisuals = activeVisuals,
            disabledVisuals = disabledVisuals
        )
    }
}
