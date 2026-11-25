package com.nexustools.genericui.swing

import com.nexustools.genericui.nativeimpl.surface.StyledComponentNativeImpl
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.StyledVisualSpec
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JComponent

/**
 * Java Swing [JComponent] implementation of [StyledComponentNativeImpl] supporting full
 * [StyledVisualSpec] properties, interactive `:hover`, `:focus`, `:active`, and `:disabled`
 * visual states, opacity, `cursor: none`, `pointer-events: none` hit-test pass-through, and
 * backdrop-filter blur.
 */
class SwingStyledComponentImpl(
    classPrefix: String = "styled-component",
    val tagName: String = "genericui:styled-component"
) : SwingBaseNativeImpl(
    object : JComponent() {
        override fun contains(x: Int, y: Int): Boolean {
            val impl = getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl ?: return super.contains(x, y)
            return impl.hitTestContains(this, x, y, super.contains(x, y))
        }

        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            SwingSurfacePaintUtil.withComponentTransform(this, g) { tg ->
                @Suppress("UNCHECKED_CAST")
                val provider = getClientProperty("styledComponentVisualsProvider") as? (() -> StyledVisualSpec)
                val spec = provider?.invoke()
                    ?: (getClientProperty("styledComponentVisuals") as? StyledVisualSpec)
                    ?: StyledVisualSpec.EMPTY
                val rawOpacity = getClientProperty("opacity") as? Float
                val componentOpacity = (rawOpacity ?: spec.opacity ?: 1f).coerceIn(0f, 1f)
                if (componentOpacity <= SwingSurfacePaintUtil.OPACITY_SKIP_THRESHOLD) return@withComponentTransform

                val focusedState = (getClientProperty("styledComponentFocused") as? Boolean) ?: hasFocus()
                val effBackdrop = CssFilter.backdropBlurOrFilter(spec.backdropBlurPx, spec.backdropFilter)
                if (effBackdrop != null && width > 0 && height > 0) {
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
                    isFocused = focusedState,
                    componentOpacity = componentOpacity
                )
            }
        }

        override fun getPreferredSize(): Dimension {
            val base = super.getPreferredSize() ?: Dimension(0, 0)
            @Suppress("UNCHECKED_CAST")
            val provider = getClientProperty("styledComponentVisualsProvider") as? (() -> StyledVisualSpec)
            val spec = provider?.invoke() ?: (getClientProperty("styledComponentVisuals") as? StyledVisualSpec)
            val w = spec?.widthPx ?: base.width
            val h = spec?.heightPx ?: base.height
            return Dimension(w, h)
        }
    },
    classPrefix
), StyledComponentNativeImpl<JComponent> {

    override val componentTagName: String
        get() = tagName

    private var baseVisuals: StyledVisualSpec = StyledVisualSpec()
    private var hoverVisuals: StyledVisualSpec? = null
    private var focusVisuals: StyledVisualSpec? = null
    private var activeVisuals: StyledVisualSpec? = null
    private var disabledVisuals: StyledVisualSpec? = null

    private var hovered: Boolean = false
    private var focused: Boolean = false
    private var activePressed: Boolean = false

    init {
        nativeWidget.isOpaque = false
        nativeWidget.putClientProperty("antialiasing", true)
        nativeWidget.putClientProperty("tagName", tagName)
        nativeWidget.putClientProperty("styledComponentVisualsProvider", { resolveCurrentVisuals() })
        nativeWidget.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                if (!isPointerEventsDisabledForHitTest()) setHoveredState(true)
            }

            override fun mouseExited(e: MouseEvent) {
                setHoveredState(false)
                setActivePressedState(false)
            }

            override fun mousePressed(e: MouseEvent) {
                if (!isPointerEventsDisabledForHitTest() && e.button == MouseEvent.BUTTON1) {
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

    private fun syncCurrentVisuals() {
        val current = resolveCurrentVisuals()
        nativeWidget.isOpaque = false
        nativeWidget.putClientProperty("backdropBlurPx", current.backdropBlurPx)
        nativeWidget.putClientProperty("backdropFilter", current.backdropFilter)
        nativeWidget.putClientProperty(
            "-webkit-backdrop-filter",
            current.backdropFilter?.toCssString()
                ?: current.backdropBlurPx?.takeIf { it > 0f }?.let { "blur(${it}px)" }
        )
        nativeWidget.putClientProperty("styledComponentVisuals", current)
        nativeWidget.putClientProperty("styledComponentFocused", focused)
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
        if (current.widthPx != null || current.heightPx != null) {
            val currentPref = if (nativeWidget.isPreferredSizeSet) nativeWidget.preferredSize else null
            val w = current.widthPx ?: currentPref?.width ?: nativeWidget.width.takeIf { it > 0 } ?: 0
            val h = current.heightPx ?: currentPref?.height ?: nativeWidget.height.takeIf { it > 0 } ?: 0
            nativeWidget.preferredSize = Dimension(w, h)
        }
        nativeWidget.revalidate()
        nativeWidget.repaint()
    }

    override fun setStyledVisuals(visuals: StyledVisualSpec) {
        baseVisuals = sanitizeIncomingStyledVisuals(visuals)
        syncCurrentVisuals()
    }

    override fun getStyledVisuals(): StyledVisualSpec = baseVisuals

    override fun setHoverVisuals(visuals: StyledVisualSpec?) {
        hoverVisuals = visuals?.let { sanitizeIncomingStyledVisuals(it) }
        syncCurrentVisuals()
    }

    override fun getHoverVisuals(): StyledVisualSpec? = hoverVisuals

    override fun setFocusVisuals(visuals: StyledVisualSpec?) {
        focusVisuals = visuals?.let { sanitizeIncomingStyledVisuals(it) }
        syncCurrentVisuals()
    }

    override fun getFocusVisuals(): StyledVisualSpec? = focusVisuals

    override fun setActiveVisuals(visuals: StyledVisualSpec?) {
        activeVisuals = visuals?.let { sanitizeIncomingStyledVisuals(it) }
        syncCurrentVisuals()
    }

    override fun getActiveVisuals(): StyledVisualSpec? = activeVisuals

    override fun setDisabledVisuals(visuals: StyledVisualSpec?) {
        disabledVisuals = visuals?.let { sanitizeIncomingStyledVisuals(it) }
        syncCurrentVisuals()
    }

    override fun getDisabledVisuals(): StyledVisualSpec? = disabledVisuals

    override fun setHoveredState(hovered: Boolean) {
        this.hovered = hovered && isEnabled()
        syncCurrentVisuals()
    }

    override fun isHoveredState(): Boolean = hovered

    override fun setFocusedState(focused: Boolean) {
        this.focused = focused && isEnabled() && isEffectivelyFocusableInSwing()
        syncCurrentVisuals()
    }

    override fun isFocusedState(): Boolean = focused

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
        super<SwingBaseNativeImpl>.setEnabled(enabled)
        syncCurrentVisuals()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super<SwingBaseNativeImpl>.applyComputedStyle(style)
        syncCurrentVisuals()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super<SwingBaseNativeImpl>.applyComponentStyle(style)
        syncCurrentVisuals()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super<SwingBaseNativeImpl>.applyComponentBootstrap(bootstrap)
        syncCurrentVisuals()
    }

    override fun resolveCurrentVisuals(): StyledVisualSpec {
        val isDark = SwingSurfacePaintUtil.isDarkThemeForComponent(nativeWidget)
        val computedBase = StyledVisualSpec.fromComponentAndComputed(
            componentStyle = lastComponentStyle,
            computedStyle = lastComputedStyle,
            snapshot = snapshotForStyledVisuals(),
            darkTheme = isDark
        )
        var effective = computedBase.mergeWith(baseVisuals)

        if (!isEnabled()) {
            val computedDisabled = lastComputedStyle?.disabledStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(computedDisabled).mergeWith(disabledVisuals)
            return effective
        }

        if (hovered) {
            val computedHover = lastComputedStyle?.hoverStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(computedHover).mergeWith(hoverVisuals)
        }

        if (focused) {
            val computedFocus = lastComputedStyle?.focusStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(computedFocus).mergeWith(focusVisuals)
        }

        if (activePressed) {
            val computedActive = lastComputedStyle?.activeStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(computedActive).mergeWith(activeVisuals)
        }

        return effective
    }
}
