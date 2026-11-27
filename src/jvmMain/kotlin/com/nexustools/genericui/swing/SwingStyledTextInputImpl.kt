package com.nexustools.genericui.swing

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.nativeimpl.form.StyledTextInputNativeImpl
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.theme.SystemThemeDetector
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.WeakHashMap
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Java Swing custom-styled text input (`StyledTextField` / `StyledTextArea`) painting
 * [StyledVisualSpec] rounded/pill backgrounds, gradients, borders, box shadows, focus rings,
 * backdrop blur, custom padding, placeholder colors, and `:hover`/`:focus`/`:active`/`:disabled`
 * state visuals over a transparent borderless inner [JTextComponent].
 */
class SwingStyledTextInputImpl(
    initialMultiline: Boolean = false,
    classPrefix: String = if (initialMultiline) "styled-textarea" else "styled-textfield",
    private val inputShellPanel: JPanel = createStyledInputShellPanel()
) : SwingTextInputImpl(
    initialMultiline = initialMultiline,
    rootComponent = createStyledRootPanel(),
    classPrefix = classPrefix,
    editorShellPanel = inputShellPanel
), StyledTextInputNativeImpl<JComponent> {

    private var baseVisuals: StyledVisualSpec = StyledVisualSpec()
    private var hoverVisuals: StyledVisualSpec? = null
    private var focusVisuals: StyledVisualSpec? = null
    private var activeVisuals: StyledVisualSpec? = null
    private var disabledVisuals: StyledVisualSpec? = null

    private var hovered: Boolean = false
    private var activePressed: Boolean = false
    private var lastAppliedVisuals: StyledVisualSpec = StyledVisualSpec()
    private val attachedInteractiveTargets: WeakHashMap<Component, Boolean> = WeakHashMap()
    private var styledInitComplete: Boolean = false

    private val interactiveMouseAdapter = object : MouseAdapter() {
        override fun mouseEntered(e: MouseEvent) {
            setHoveredState(true)
        }

        override fun mouseExited(e: MouseEvent) {
            val src = e.component
            if (src != null && inputShellPanel.isShowing) {
                val ptInShell = SwingUtilities.convertPoint(src, e.point, inputShellPanel)
                if (inputShellPanel.contains(ptInShell)) return
            }
            setHoveredState(false)
            setActivePressedState(false)
        }

        override fun mousePressed(e: MouseEvent) {
            if (e.button == MouseEvent.BUTTON1) {
                setActivePressedState(true)
                if (e.component === inputShellPanel && isEnabled() && isEffectivelyFocusableInSwing()) {
                    requestAwtFocusOnEdt(activeEditor)
                }
            }
        }

        override fun mouseReleased(e: MouseEvent) {
            setActivePressedState(false)
        }
    }

    init {
        val visualsProvider: () -> StyledVisualSpec = { resolveCurrentVisuals() }
        val focusProvider: () -> Boolean = { isFocusedState() }
        rootPanel.isOpaque = false
        rootPanel.putClientProperty("antialiasing", true)
        rootPanel.putClientProperty("styledInputVisualsProvider", visualsProvider)
        rootPanel.putClientProperty("styledInputFocusedProvider", focusProvider)
        inputShellPanel.isOpaque = false
        inputShellPanel.putClientProperty("antialiasing", true)
        inputShellPanel.putClientProperty("styledInputVisualsProvider", visualsProvider)
        inputShellPanel.putClientProperty("styledInputFocusedProvider", focusProvider)

        val centerComp = (inputShellPanel.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.CENTER) as? JComponent
            ?: activeEditor
        styledInitComplete = true
        decorateEditorTree(activeEditor, centerComp)
        attachHoverAndPressListeners(inputShellPanel)
        syncCurrentVisuals()
    }

    override fun onEditorMounted(editor: JTextComponent, container: JComponent) {
        if (!styledInitComplete) return
        decorateEditorTree(editor, container)
        syncCurrentVisuals()
    }

    private fun decorateEditorTree(editor: JTextComponent, container: JComponent) {
        editor.isOpaque = false
        editor.background = Color(0, 0, 0, 0)
        editor.border = BorderFactory.createEmptyBorder()
        if (container is JScrollPane) {
            container.isOpaque = false
            container.background = Color(0, 0, 0, 0)
            container.viewport.isOpaque = false
            container.viewport.background = Color(0, 0, 0, 0)
            container.border = BorderFactory.createEmptyBorder()
            attachHoverAndPressListeners(container)
            attachHoverAndPressListeners(container.viewport)
        }
        attachHoverAndPressListeners(editor)
    }

    private fun attachHoverAndPressListeners(comp: Component) {
        if (attachedInteractiveTargets.put(comp, true) == null) {
            comp.addMouseListener(interactiveMouseAdapter)
        }
    }

    override fun resolvePlaceholderColor(comp: JTextComponent): Color {
        val pc = lastAppliedVisuals.placeholderColor
        if (pc != null) {
            return Color(
                pc.r.coerceIn(0, 255),
                pc.g.coerceIn(0, 255),
                pc.b.coerceIn(0, 255),
                pc.a.coerceIn(0, 255)
            )
        }
        return super.resolvePlaceholderColor(comp)
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        if (!styledInitComplete) return
        rootPanel.isOpaque = false
        inputShellPanel.isOpaque = false
        activeEditor.isOpaque = false
        activeEditor.border = BorderFactory.createEmptyBorder()
        syncCurrentVisuals()
    }

    private fun syncCurrentVisuals() {
        val current = resolveCurrentVisuals()
        lastAppliedVisuals = current
        val focusedNow = isFocusedState()

        rootPanel.putClientProperty("backdropBlurPx", current.backdropBlurPx)
        rootPanel.putClientProperty("backdropFilter", current.backdropFilter)
        rootPanel.putClientProperty(
            "-webkit-backdrop-filter",
            current.backdropFilter?.toCssString()
                ?: current.backdropBlurPx?.takeIf { it > 0f }?.let { "blur(${it}px)" }
        )
        rootPanel.putClientProperty("styledInputVisuals", current)
        rootPanel.putClientProperty("styledInputFocused", focusedNow)
        rootPanel.putClientProperty("backgroundColor", current.backgroundColor)
        rootPanel.putClientProperty("backgroundGradient", current.backgroundGradient)
        rootPanel.putClientProperty("textColor", current.textColor)
        rootPanel.putClientProperty("borderColor", current.borderColor)
        rootPanel.putClientProperty("borderWidthPx", current.borderWidthPx)
        rootPanel.putClientProperty("borderStyle", current.borderStyle)
        rootPanel.putClientProperty("borderRadiusPx", current.borderRadiusPx)
        rootPanel.putClientProperty("focusRingColor", current.focusRingColor)
        rootPanel.putClientProperty("focusRingWidthPx", current.focusRingWidthPx)
        rootPanel.putClientProperty("placeholderColor", current.placeholderColor)

        val isDark = SwingSurfacePaintUtil.isDarkThemeForComponent(rootPanel)
        val fgColor = current.textColor?.let { c ->
            Color(c.r.coerceIn(0, 255), c.g.coerceIn(0, 255), c.b.coerceIn(0, 255), c.a.coerceIn(0, 255))
        } ?: lastComputedStyle?.textColor?.let { c ->
            Color(c.r.coerceIn(0, 255), c.g.coerceIn(0, 255), c.b.coerceIn(0, 255), c.a.coerceIn(0, 255))
        } ?: SwingSurfacePaintUtil.resolveInheritedReadableTextColor(rootPanel, isDark)
        activeEditor.foreground = fgColor
        activeEditor.caretColor = fgColor

        applyVisualTypographyToEditor(current)

        val computedOpacity = lastAppliedComputedOpacity
        val preserveTransitionOpacity = computedOpacity != null &&
            current.opacity == computedOpacity &&
            kotlin.math.abs(getOpacity() - computedOpacity) > 0.0001f
        if (!preserveTransitionOpacity) {
            current.opacity?.let { setOpacity(it) }
        }

        val pad = current.padding ?: lastComputedStyle?.padding?.toEdgeInsets() ?: DEFAULT_INPUT_PADDING
        inputShellPanel.border = BorderFactory.createEmptyBorder(
            pad.top.coerceAtLeast(0),
            pad.left.coerceAtLeast(0),
            pad.bottom.coerceAtLeast(0),
            pad.right.coerceAtLeast(0)
        )

        if (current.widthPx != null || current.heightPx != null) {
            val basePref = rootPanel.layout.preferredLayoutSize(rootPanel) ?: Dimension(180, 32)
            val w = current.widthPx ?: maxOf(basePref.width, current.minWidthPx ?: 0)
            val h = current.heightPx ?: maxOf(basePref.height, current.minHeightPx ?: 0)
            rootPanel.preferredSize = Dimension(w, h)
        } else if (rootPanel.isPreferredSizeSet) {
            rootPanel.preferredSize = null
        }

        inputShellPanel.revalidate()
        inputShellPanel.repaint()
        activeEditor.repaint()
        rootPanel.revalidate()
        rootPanel.repaint()
    }

    private fun applyVisualTypographyToEditor(spec: StyledVisualSpec) {
        val base = nativeWidget.font ?: activeEditor.font ?: Font("SansSerif", Font.PLAIN, 14)
        val family = spec.fontFamily?.takeIf { it.isNotBlank() } ?: base.family
        val sizePx = spec.fontSizePx?.takeIf { it > 0f } ?: base.size2D
        val weight = spec.fontWeightNumeric
        val style = when {
            weight == null -> base.style
            weight >= 600 -> (base.style and Font.ITALIC) or Font.BOLD
            else -> (base.style and Font.ITALIC)
        }
        val resolved = Font(family, style, sizePx.roundToInt().coerceAtLeast(8)).deriveFont(sizePx)
        activeEditor.font = resolved
        if (headerLabel.font != nativeWidget.font && nativeWidget.font != null) {
            headerLabel.font = nativeWidget.font
        }
    }

    fun getStyledVisuals(): StyledVisualSpec = lastAppliedVisuals

    override fun setBaseVisuals(visuals: StyledVisualSpec) {
        baseVisuals = sanitizeIncomingStyledVisuals(visuals)
        syncCurrentVisuals()
    }

    override fun getBaseVisuals(): StyledVisualSpec = baseVisuals

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
        super<SwingTextInputImpl>.setFocusedState(focused)
        syncCurrentVisuals()
    }

    override fun setActivePressedState(active: Boolean) {
        this.activePressed = active && isEnabled()
        syncCurrentVisuals()
    }

    override fun isActivePressedState(): Boolean = activePressed

    override fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            hovered = false
            activePressed = false
            super<SwingTextInputImpl>.setFocusedState(false)
        }
        super<SwingTextInputImpl>.setEnabled(enabled)
        syncCurrentVisuals()
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        super<SwingTextInputImpl>.applyComputedStyle(style)
        syncCurrentVisuals()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        super<SwingTextInputImpl>.applyComponentStyle(style)
        syncCurrentVisuals()
    }

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        super<SwingTextInputImpl>.applyComponentBootstrap(bootstrap)
        syncCurrentVisuals()
    }

    override fun resolveCurrentVisuals(): StyledVisualSpec {
        val isDark = (Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()) == ThemeMode.DARK
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

        if (isFocusedState()) {
            val computedFocus = lastComputedStyle?.focusStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(computedFocus).mergeWith(focusVisuals)
        }

        if (activePressed) {
            val computedActive = lastComputedStyle?.activeStyle?.let { StyledVisualSpec.fromComputedStyle(it) }
            effective = effective.mergeWith(computedActive).mergeWith(activeVisuals)
        }

        return effective
    }

    companion object {
        private val DEFAULT_INPUT_PADDING = EdgeInsets(6, 12, 6, 12)

        private fun createStyledRootPanel(): JPanel = object : JPanel(BorderLayout(0, 4)) {
            override fun contains(x: Int, y: Int): Boolean {
                val impl = getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl ?: return super.contains(x, y)
                return impl.hitTestContains(this, x, y, super.contains(x, y))
            }

            override fun paint(g: Graphics) {
                val rawOpacity = getClientProperty("opacity") as? Float
                @Suppress("UNCHECKED_CAST")
                val provider = getClientProperty("styledInputVisualsProvider") as? (() -> StyledVisualSpec)
                val spec = provider?.invoke()
                    ?: (getClientProperty("styledInputVisuals") as? StyledVisualSpec)
                    ?: StyledVisualSpec.EMPTY
                val componentOpacity = (rawOpacity ?: spec.opacity ?: 1f).coerceIn(0f, 1f)
                if (componentOpacity <= SwingSurfacePaintUtil.OPACITY_SKIP_THRESHOLD) return
                SwingSurfacePaintUtil.withComponentTransform(this, g) { tg ->
                    if (componentOpacity < 0.999f) {
                        val fgG2 = tg.create() as? Graphics2D
                        if (fgG2 != null) {
                            try {
                                fgG2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, componentOpacity)
                                super.paint(fgG2)
                            } finally {
                                fgG2.dispose()
                            }
                            return@withComponentTransform
                        }
                    }
                    super.paint(tg)
                }
            }

            override fun getPreferredSize(): Dimension {
                val base = super.getPreferredSize() ?: Dimension(180, 32)
                @Suppress("UNCHECKED_CAST")
                val provider = getClientProperty("styledInputVisualsProvider") as? (() -> StyledVisualSpec)
                val spec = provider?.invoke() ?: (getClientProperty("styledInputVisuals") as? StyledVisualSpec)
                val minW = spec?.minWidthPx ?: 0
                val minH = spec?.minHeightPx ?: 0
                val w = spec?.widthPx ?: maxOf(base.width, minW)
                val h = spec?.heightPx ?: maxOf(base.height, minH)
                return Dimension(w, h)
            }
        }.apply {
            isOpaque = false
        }

        private fun createStyledInputShellPanel(): JPanel = object : JPanel(BorderLayout(0, 0)) {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                if (width <= 0 || height <= 0) return
                @Suppress("UNCHECKED_CAST")
                val provider = getClientProperty("styledInputVisualsProvider") as? (() -> StyledVisualSpec)
                val spec = provider?.invoke()
                    ?: (getClientProperty("styledInputVisuals") as? StyledVisualSpec)
                    ?: StyledVisualSpec.EMPTY
                @Suppress("UNCHECKED_CAST")
                val focusProvider = getClientProperty("styledInputFocusedProvider") as? (() -> Boolean)
                val focusedState = focusProvider?.invoke()
                    ?: (getClientProperty("styledInputFocused") as? Boolean)
                    ?: false
                val rawOpacity = (getClientProperty("opacity") as? Float) ?: spec.opacity ?: 1f
                val effBackdrop = CssFilter.backdropBlurOrFilter(spec.backdropBlurPx, spec.backdropFilter)
                if (effBackdrop != null && rawOpacity > SwingSurfacePaintUtil.OPACITY_SKIP_THRESHOLD) {
                    val maxRadius = min(width, height).toFloat() / 2f
                    val radius = (spec.borderRadiusPx ?: 0f).coerceIn(0f, maxRadius)
                    SwingSurfacePaintUtil.paintBackdropWithOpacity(
                        g = g,
                        target = this,
                        filter = effBackdrop,
                        cornerRadiusPx = radius,
                        componentOpacity = 1f
                    )
                }
                SwingSurfacePaintUtil.paintStyledSurface(
                    g = g,
                    width = width,
                    height = height,
                    spec = spec.copy(opacity = null),
                    isFocused = focusedState,
                    componentOpacity = 1f
                )
            }
        }.apply {
            isOpaque = false
        }
    }
}
