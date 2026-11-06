package com.nexustools.genericui.swing

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.nativeimpl.action.StyledButtonNativeImpl
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.theme.SystemThemeDetector
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
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
        override fun paintComponent(g: Graphics) {
            if (!isContentAreaFilled) {
                @Suppress("UNCHECKED_CAST")
                val provider = getClientProperty("styledButtonVisualsProvider") as? (() -> StyledVisualSpec)
                val spec = provider?.invoke()
                    ?: (getClientProperty("styledButtonVisuals") as? StyledVisualSpec)
                    ?: StyledVisualSpec.EMPTY
                val focusedState = (getClientProperty("styledButtonFocused") as? Boolean) ?: hasFocus()
                val componentOpacity = (getClientProperty("opacity") as? Float) ?: 1f
                SwingStyledContainerImpl.paintStyledSurface(
                    g = g,
                    width = width,
                    height = height,
                    spec = spec,
                    isFocused = focusedState,
                    componentOpacity = componentOpacity
                )
            }
            super.paintComponent(g)
        }

        override fun getPreferredSize(): Dimension {
            val base = super.getPreferredSize() ?: Dimension(0, 0)
            @Suppress("UNCHECKED_CAST")
            val provider = getClientProperty("styledButtonVisualsProvider") as? (() -> StyledVisualSpec)
            val spec = provider?.invoke() ?: (getClientProperty("styledButtonVisuals") as? StyledVisualSpec)
            val w = spec?.widthPx ?: base.width
            val h = spec?.heightPx ?: base.height
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
        nativeWidget.isFocusPainted = false
        nativeWidget.isOpaque = false
    }

    private fun syncCurrentVisuals() {
        val current = getStyledVisuals()
        nativeWidget.isContentAreaFilled = false
        nativeWidget.isFocusPainted = false
        nativeWidget.isOpaque = false
        nativeWidget.putClientProperty("backdropBlurPx", null)
        nativeWidget.putClientProperty("backdrop-filter", null)
        nativeWidget.putClientProperty("-webkit-backdrop-filter", null)
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
        current.opacity?.let { setOpacity(it) }
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
        this.focused = focused && isEnabled()
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
        val isDark = (Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()) == ThemeMode.DARK
        val computedBase = StyledVisualSpec.fromComponentAndComputed(
            componentStyle = lastComponentStyle,
            computedStyle = lastComputedStyle,
            snapshot = styleSnapshot,
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
