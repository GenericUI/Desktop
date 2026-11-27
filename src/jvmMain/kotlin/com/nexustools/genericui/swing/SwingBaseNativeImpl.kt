package com.nexustools.genericui.swing

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.bootstrap.*
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.enums.css.CssVisibility
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.event.KeyEvent
import com.nexustools.genericui.event.PointerDragManager
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.event.WheelEvent
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssCustomCursor
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.style.mapper.BootstrapClassMapper
import com.nexustools.genericui.style.mapper.BootstrapStyleSnapshot
import com.nexustools.genericui.style.policy.AestheticStylePolicy
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.style.transition.CssTransform2D
import com.nexustools.genericui.style.transition.CssTransitionSpec
import com.nexustools.genericui.enums.css.CssUnit
import com.nexustools.genericui.style.css.CssLength
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.KeyAdapter
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import java.awt.image.BufferedImage
import java.util.WeakHashMap
import javax.swing.AbstractButton
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JSlider
import javax.swing.JSpinner
import javax.swing.JSplitPane
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JToolBar
import javax.swing.JTree
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Normalized snapshot input extracted from a [SwingBaseNativeImpl] for [SwingFlexLayoutManager].
 */
internal data class SwingLayoutStyleInput(
    val tagName: String,
    val classPrefix: String,
    val classes: Set<String>,
    val attributes: Map<String, String>,
    val styleProps: Map<String, String>,
    val padding: EdgeInsets,
    val margin: EdgeInsets,
    val explicitWidthPx: Int?,
    val explicitHeightPx: Int?,
    val cssBorderWidthPx: Int,
    val visible: Boolean
)

/**
 * Base [NativeImpl] for the Java `:swing` backend.
 */
open class SwingBaseNativeImpl(
    protected val baseWidget: JComponent = JPanel(),
    override val componentClassPrefix: String = ""
) : NativeImpl<JComponent> {

    companion object {
        const val CLIENT_PROPERTY_KEY: String = "genericUiNativeImpl"
    }

    open val componentTagName: String
        get() = ""

    override val nativeWidget: JComponent
        get() = baseWidget

    protected var styleSnapshot: BootstrapStyleSnapshot =
        BootstrapStyleSnapshot(componentPrefix = componentClassPrefix)

    private var opacityVal: Float = 1.0f
    private var focusableState: Boolean = true
    private val savedAwtFocusable: WeakHashMap<Component, Boolean> = WeakHashMap()
    private var paddingInsets: EdgeInsets = EdgeInsets.ZERO
    private var marginInsets: EdgeInsets = EdgeInsets.ZERO
    private var logicalBounds: Rect = Rect(0, 0, 200, 40)
    private var explicitPreferredWidthPx: Int? = null
    private var explicitPreferredHeightPx: Int? = null

    protected var lastComputedStyle: ComputedStyle? = null
    protected var lastComponentStyle: ComponentStyle? = null
    protected var lastBootstrap: ComponentBootstrap? = null
    protected var rawLayoutComputedStyle: ComputedStyle? = null
    protected var rawLayoutComponentStyle: ComponentStyle? = null
    protected var lastAppliedComputedOpacity: Float? = null
    protected val attributesMap: LinkedHashMap<String, String> = linkedMapOf()
    protected var customCursorState: CssCursorValue? = null
    protected var customBackgroundColor: RgbaColor? = null
    protected var customTextColor: RgbaColor? = null
    private var backgroundGradientState: Gradient? = null

    private val defaultComponentBackground: Color? = baseWidget.background
    private val defaultComponentForeground: Color? = baseWidget.foreground
    private val defaultComponentBorder = baseWidget.border
    private val defaultComponentFont: Font? = baseWidget.font

    protected var onClickListener: (() -> Unit)? = null
    protected var onDoubleClickListener: (() -> Unit)? = null
    protected var onContextMenuListener: ((PointerEvent) -> Unit)? = null
    protected var onPointerDownListener: ((PointerEvent) -> Unit)? = null
    protected var onPointerMoveListener: ((PointerEvent) -> Unit)? = null
    protected var onPointerUpListener: ((PointerEvent) -> Unit)? = null
    protected var onPointerEnterListener: ((PointerEvent) -> Unit)? = null
    protected var onPointerLeaveListener: ((PointerEvent) -> Unit)? = null
    protected var onWheelListener: ((WheelEvent) -> Unit)? = null
    protected var onKeyDownListener: ((KeyEvent) -> Unit)? = null
    protected var onKeyUpListener: ((KeyEvent) -> Unit)? = null

    private var mouseListenerAttached: Boolean = false
    private var mouseMotionAttached: Boolean = false
    private var wheelListenerAttached: Boolean = false

    private val swingMouseAdapter = object : MouseAdapter() {
        private var primaryPressActive: Boolean = false
        private var pressStartX: Int = 0
        private var pressStartY: Int = 0
        private var pressMovedBeyondTolerance: Boolean = false
        private var releaseDispatchedClick: Boolean = false
        private var releaseDispatchedWhen: Long = Long.MIN_VALUE
        private var releaseDispatchedX: Int = Int.MIN_VALUE
        private var releaseDispatchedY: Int = Int.MIN_VALUE

        override fun mouseClicked(e: MouseEvent) {
            if (e.isConsumed || !nativeWidget.isEnabled || isPointerEventsDisabled()) return
            if (com.nexustools.genericui.event.PopupMouseCaptureManager.interceptClick(e.x.toFloat(), e.y.toFloat(), this@SwingBaseNativeImpl)) return
            if (e.clickCount >= 2 && onDoubleClickListener != null) {
                releaseDispatchedClick = false
                releaseDispatchedWhen = Long.MIN_VALUE
                onDoubleClickListener?.invoke()
            } else if ((e.button == MouseEvent.BUTTON1 || e.button == MouseEvent.NOBUTTON) && onClickListener != null) {
                val isDuplicateOfRelease = releaseDispatchedClick &&
                    abs(e.getWhen() - releaseDispatchedWhen) <= 5L &&
                    abs(e.x - releaseDispatchedX) <= 2 &&
                    abs(e.y - releaseDispatchedY) <= 2
                releaseDispatchedClick = false
                releaseDispatchedWhen = Long.MIN_VALUE
                if (!isDuplicateOfRelease) {
                    onClickListener?.invoke()
                }
            }
        }

        override fun mousePressed(e: MouseEvent) {
            if (e.isConsumed || !nativeWidget.isEnabled || isPointerEventsDisabled()) return
            if (com.nexustools.genericui.event.PopupMouseCaptureManager.interceptPointerDown(e.x.toFloat(), e.y.toFloat(), this@SwingBaseNativeImpl)) return
            if (e.button == MouseEvent.BUTTON1 && !e.isPopupTrigger) {
                primaryPressActive = true
                pressStartX = e.x
                pressStartY = e.y
                pressMovedBeyondTolerance = false
                releaseDispatchedClick = false
                releaseDispatchedWhen = Long.MIN_VALUE
            }
            val pe = toPointerEvent(e)
            onPointerDownListener?.invoke(pe)
            if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) {
                onContextMenuListener?.invoke(pe)
            }
        }

        override fun mouseReleased(e: MouseEvent) {
            if (e.isConsumed || !nativeWidget.isEnabled || isPointerEventsDisabled()) {
                primaryPressActive = false
                pressMovedBeyondTolerance = false
                return
            }
            if (com.nexustools.genericui.event.PopupMouseCaptureManager.interceptPointerUp(e.x.toFloat(), e.y.toFloat(), this@SwingBaseNativeImpl)) {
                primaryPressActive = false
                pressMovedBeyondTolerance = false
                return
            }
            val pe = toPointerEvent(e)
            val wasDragging = PointerDragManager.isDragging
            if (wasDragging) {
                PointerDragManager.notifyGlobalPointerUp(pe, coordinatesAreTargetLocal = false)
            }
            onPointerUpListener?.invoke(pe)
            if (e.isPopupTrigger) {
                onContextMenuListener?.invoke(pe)
            }
            if (primaryPressActive) {
                val movedTooFar = pressMovedBeyondTolerance ||
                    abs(e.x - pressStartX) > 6 ||
                    abs(e.y - pressStartY) > 6
                val isPrimary = e.button == MouseEvent.BUTTON1 || e.button == MouseEvent.NOBUTTON
                val shouldDispatchReleaseClick = dispatchesClickOnPrimaryRelease() &&
                    !wasDragging &&
                    !movedTooFar &&
                    !e.isPopupTrigger &&
                    isPrimary &&
                    e.clickCount <= 1 &&
                    onClickListener != null &&
                    nativeWidget.contains(e.x, e.y)
                primaryPressActive = false
                pressMovedBeyondTolerance = false
                if (shouldDispatchReleaseClick) {
                    releaseDispatchedClick = true
                    releaseDispatchedWhen = e.getWhen()
                    releaseDispatchedX = e.x
                    releaseDispatchedY = e.y
                    onClickListener?.invoke()
                }
            }
        }

        override fun mouseMoved(e: MouseEvent) {
            if (e.isConsumed || !nativeWidget.isEnabled || isPointerEventsDisabled()) return
            onPointerMoveListener?.invoke(toPointerEvent(e))
        }

        override fun mouseDragged(e: MouseEvent) {
            if (e.isConsumed || !nativeWidget.isEnabled || isPointerEventsDisabled()) return
            if (primaryPressActive && (abs(e.x - pressStartX) > 6 || abs(e.y - pressStartY) > 6)) {
                pressMovedBeyondTolerance = true
            }
            val pe = toPointerEvent(e)
            if (PointerDragManager.isDragging) {
                PointerDragManager.notifyGlobalPointerMove(pe, coordinatesAreTargetLocal = false)
            }
            onPointerMoveListener?.invoke(pe)
        }

        override fun mouseEntered(e: MouseEvent) {
            if (e.isConsumed || !nativeWidget.isEnabled || isPointerEventsDisabled()) return
            onPointerEnterListener?.invoke(toPointerEvent(e))
        }

        override fun mouseExited(e: MouseEvent) {
            if (e.isConsumed || !nativeWidget.isEnabled || isPointerEventsDisabled()) return
            onPointerLeaveListener?.invoke(toPointerEvent(e))
        }
    }

    private val swingWheelListener = MouseWheelListener { e: MouseWheelEvent ->
        if (!e.isConsumed && nativeWidget.isEnabled && !isPointerEventsDisabled()) {
            onWheelListener?.invoke(
                WheelEvent(
                    x = e.x.toFloat(),
                    y = e.y.toFloat(),
                    deltaX = 0f,
                    deltaY = e.preciseWheelRotation.toFloat(),
                    ctrlKey = e.isControlDown,
                    shiftKey = e.isShiftDown,
                    altKey = e.isAltDown
                )
            )
        }
    }

    init {
        nativeWidget.putClientProperty(CLIENT_PROPERTY_KEY, this)
        nativeWidget.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (nativeWidget.isEnabled) onKeyDownListener?.invoke(toKeyEvent(e))
            }

            override fun keyReleased(e: java.awt.event.KeyEvent) {
                if (nativeWidget.isEnabled) onKeyUpListener?.invoke(toKeyEvent(e))
            }
        })
    }

    private fun isPointerEventsDisabled(): Boolean {
        if (styleSnapshot.pointerEvents == BootstrapPointerEvents.NONE) return true
        if (attributesMap["layerPassThrough"]?.equals("true", ignoreCase = true) == true) return true
        if (lastComputedStyle?.pointerEventsEnabled == false || rawLayoutComputedStyle?.pointerEventsEnabled == false) {
            return true
        }
        val cssPe = rawLayoutComponentStyle?.toCssPropertyMap()?.get("pointer-events")?.first
        if (cssPe.equals("none", ignoreCase = true)) return true
        return "gui-layer-pass-through" in styleSnapshot.customClasses
    }

    internal fun isPointerEventsDisabledForHitTest(): Boolean = isPointerEventsDisabled()

    internal fun hitTestContains(comp: JComponent, x: Int, y: Int, superContains: Boolean): Boolean {
        if (!superContains) return false
        if (!isPointerEventsDisabled()) return true
        if (comp.componentCount > 0) {
            for (i in 0 until comp.componentCount) {
                val child = comp.getComponent(i)
                if (child.isVisible && child.contains(x - child.x, y - child.y)) {
                    return true
                }
            }
        }
        return false
    }

    internal fun hasInteractiveNonDragClickHandler(): Boolean =
        !isPointerEventsDisabled() && (onClickListener != null || onDoubleClickListener != null)

    protected fun syncPointerListeners() {
        val blocked = isPointerEventsDisabled()
        val needMouse = !blocked && (
            onClickListener != null ||
                onDoubleClickListener != null ||
                onContextMenuListener != null ||
                onPointerDownListener != null ||
                onPointerUpListener != null ||
                onPointerEnterListener != null ||
                onPointerLeaveListener != null
            )
        val needMotion = !blocked && (
            onPointerMoveListener != null ||
                onPointerEnterListener != null
            )
        val needWheel = !blocked && onWheelListener != null

        if (needMouse && !mouseListenerAttached) {
            nativeWidget.addMouseListener(swingMouseAdapter)
            mouseListenerAttached = true
        } else if (!needMouse && mouseListenerAttached) {
            nativeWidget.removeMouseListener(swingMouseAdapter)
            mouseListenerAttached = false
        }

        if (needMotion && !mouseMotionAttached) {
            nativeWidget.addMouseMotionListener(swingMouseAdapter)
            mouseMotionAttached = true
        } else if (!needMotion && mouseMotionAttached) {
            nativeWidget.removeMouseMotionListener(swingMouseAdapter)
            mouseMotionAttached = false
        }

        if (needWheel && !wheelListenerAttached) {
            nativeWidget.addMouseWheelListener(swingWheelListener)
            wheelListenerAttached = true
        } else if (!needWheel && wheelListenerAttached) {
            nativeWidget.removeMouseWheelListener(swingWheelListener)
            wheelListenerAttached = false
        }
    }

    internal fun currentComponentStyleForSurface(): ComponentStyle? = lastComponentStyle
    internal fun currentComputedStyleForSurface(): ComputedStyle? = lastComputedStyle
    internal fun currentStyleSnapshotForSurface(): BootstrapStyleSnapshot = styleSnapshot

    internal fun isInCustomStyleEngineSubtree(): Boolean {
        if (attributesMap["data-style-engine"] == "custom") return true
        var cur: Component? = nativeWidget.parent
        while (cur != null) {
            if (cur is JComponent) {
                val ancestor = cur.getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl
                if (ancestor != null) {
                    if (ancestor.componentClassPrefix.contains("unalien", ignoreCase = true)) return false
                    if (ancestor.attributesMap["data-style-engine"] == "custom") return true
                }
            }
            cur = cur.parent
        }
        return false
    }

    protected open fun dispatchesClickOnPrimaryRelease(): Boolean = false

    protected fun requestAwtFocusOnEdt(target: JComponent) {
        val task = Runnable {
            if (!target.isFocusOwner && target.isFocusable && target.isEnabled) {
                target.requestFocusInWindow()
            }
        }
        if (SwingUtilities.isEventDispatchThread() || GraphicsEnvironment.isHeadless()) {
            task.run()
        } else {
            SwingUtilities.invokeLater(task)
        }
    }

    protected fun clearAwtFocusOnEdtIfOwner(target: JComponent) {
        val task = Runnable {
            if (target.isFocusOwner) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner()
            }
        }
        if (SwingUtilities.isEventDispatchThread() || GraphicsEnvironment.isHeadless()) {
            task.run()
        } else {
            SwingUtilities.invokeLater(task)
        }
    }

    protected open fun allowsSystemAestheticStyles(): Boolean =
        this is SwingStyledContainerImpl ||
            this is SwingStyledComponentImpl ||
            this is SwingStyledButtonImpl ||
            this is SwingStyledTextInputImpl ||
            AestheticStylePolicy.isExplicitlyStyledTagOrPrefix(componentTagName, componentClassPrefix) ||
            isInCustomStyleEngineSubtree()

    protected open fun allowsBackdropBlur(): Boolean =
        this is SwingStyledContainerImpl ||
            this is SwingStyledComponentImpl ||
            this is SwingStyledButtonImpl ||
            this is SwingStyledTextInputImpl

    protected open fun allowsBootstrapButtonTinting(): Boolean =
        this is SwingButtonImpl && this !is SwingStyledButtonImpl

    protected open fun allowsBootstrapNonControlColor(): Boolean =
        AestheticStylePolicy.isBootstrapSpecificNonControlTagOrPrefix(componentTagName, componentClassPrefix)

    protected open fun allowsBootstrapColorForStyledSpec(): Boolean =
        this is SwingStyledButtonImpl ||
            this is SwingStyledTextInputImpl ||
            allowsBootstrapNonControlColor() ||
            isInCustomStyleEngineSubtree()

    internal fun snapshotForStyledVisuals(): BootstrapStyleSnapshot =
        if (allowsBootstrapColorForStyledSpec()) {
            styleSnapshot
        } else {
            styleSnapshot.copy(
                type = null,
                backgroundType = null,
                textColorType = null,
                borderType = null
            )
        }

    internal fun sanitizeIncomingStyledVisuals(spec: StyledVisualSpec): StyledVisualSpec {
        if (allowsBootstrapColorForStyledSpec()) return spec
        val semType = styleSnapshot.type?.takeIf { it.isSemantic }
            ?: styleSnapshot.backgroundType?.takeIf { it.isSemantic }
        val textType = styleSnapshot.textColorType?.takeIf { it.isSemantic }
        val borderType = styleSnapshot.borderType?.takeIf { it.isSemantic }
        if (semType == null && textType == null && borderType == null) return spec

        val isDark = SwingSurfacePaintUtil.isDarkThemeForComponent(nativeWidget)
        val explicitBg = rawLayoutComponentStyle?.backgroundColor ?: lastComputedStyle?.backgroundColor
        val explicitFg = rawLayoutComponentStyle?.color ?: lastComputedStyle?.textColor
        val explicitBorder = rawLayoutComponentStyle?.borderColor ?: lastComputedStyle?.borderColor

        var sanitized = spec
        if (explicitBg == null && semType != null && !styleSnapshot.outline) {
            val bsBg = BootstrapColorPalette.resolveColor(semType, isDark)
            if (sanitized.backgroundColor == bsBg) {
                sanitized = sanitized.copy(backgroundColor = null)
            }
        }
        if (explicitFg == null && (semType != null || textType != null)) {
            val bsFg = textType?.let { BootstrapColorPalette.resolveColor(it, isDark) }
                ?: semType?.let {
                    if (styleSnapshot.outline) BootstrapColorPalette.resolveColor(it, isDark)
                    else BootstrapColorPalette.resolveForegroundOn(it)
                }
            if (bsFg != null && sanitized.textColor == bsFg) {
                sanitized = sanitized.copy(textColor = null)
            }
        }
        if (explicitBorder == null && (borderType != null || (semType != null && styleSnapshot.outline))) {
            val bsBorder = (borderType ?: semType)?.let { BootstrapColorPalette.resolveColor(it, isDark) }
            if (bsBorder != null && sanitized.borderColor == bsBorder) {
                val clearWidth = rawLayoutComponentStyle?.borderWidthPx == null &&
                    lastComputedStyle?.borderWidthPx == null &&
                    styleSnapshot.borderWidth == null &&
                    sanitized.borderWidthPx == 1f
                sanitized = sanitized.copy(
                    borderColor = null,
                    borderWidthPx = if (clearWidth) null else sanitized.borderWidthPx,
                    borderStyle = if (clearWidth) null else sanitized.borderStyle
                )
            }
        }
        return sanitized
    }

    internal fun resolveEffectiveTransform2D(): CssTransform2D {
        rawLayoutComponentStyle?.getTransform()?.let { return it }
        rawLayoutComputedStyle?.transform2D?.let { return it }
        return CssTransform2D.parse(rawLayoutComputedStyle?.transform)
    }

    protected open fun allowsSystemTypographyStyles(): Boolean =
        allowsSystemAestheticStyles() || this is SwingTextImpl || this is SwingMarkdownViewImpl

    protected open fun keepsOpaqueContainerBackground(): Boolean =
        this is SwingBookContainerImpl || this is SwingWindowImpl || this is SwingDialogWindowImpl

    internal fun refreshDescendantThemeAppearance() {
        val container = nativeWidget as? Container ?: return
        fun refreshChildren(c: Component) {
            if (c is JComponent) {
                (c.getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl)?.refreshThemeAppearance()
            }
            if (c is Container) {
                for (child in c.components) refreshChildren(child)
            }
        }
        for (child in container.components) refreshChildren(child)
    }

    internal open fun refreshThemeAppearance() {
        syncSwingAppearance()
        nativeWidget.repaint()
    }

    internal open fun onSystemThemeUpdated() {
        refreshThemeAppearance()
    }

    protected open fun syncSwingAppearance() {
        val allowTypography = allowsSystemTypographyStyles()
        val allowAesthetic = allowsSystemAestheticStyles()
        rawLayoutComponentStyle?.let { raw ->
            val refiltered = raw.copyFilteredForSystemComponent(
                allowTypography = allowTypography,
                allowAestheticSkin = allowAesthetic
            )
            lastComponentStyle = refiltered
            customBackgroundColor = if (allowAesthetic) refiltered.backgroundColor else null
            customTextColor = if (allowTypography) refiltered.color else null
            backgroundGradientState = if (allowAesthetic) refiltered.backgroundImage?.gradient else null
        }
        rawLayoutComputedStyle?.let { raw ->
            lastComputedStyle = raw.copyFilteredForSystemComponent(
                allowTypography = allowTypography,
                allowAestheticSkin = allowAesthetic
            )
        }

        val isDark = SwingSurfacePaintUtil.isDarkThemeForComponent(nativeWidget)
        val effectiveTheme = if (isDark) ThemeMode.DARK else ThemeMode.LIGHT
        val activeThemeConfig = BootstrapColorPalette.activeThemeConfig
        val themeColors = activeThemeConfig.resolveEffectiveColors(isDark)
        nativeWidget.putClientProperty("effectiveThemeMode", effectiveTheme)
        nativeWidget.putClientProperty("themeMode", if (isDark) "dark" else "light")
        if (!allowsBackdropBlur()) {
            nativeWidget.putClientProperty("backdropBlurPx", null)
            nativeWidget.putClientProperty("backdrop-filter", null)
            nativeWidget.putClientProperty("-webkit-backdrop-filter", null)
        }

        val allowButtonTint = allowsBootstrapButtonTinting()
        val allowBootstrapColor = allowButtonTint || allowsBootstrapColorForStyledSpec()
        val semanticType = if (allowBootstrapColor) {
            styleSnapshot.type?.takeIf { it.isSemantic }
                ?: styleSnapshot.backgroundType?.takeIf { it.isSemantic }
        } else {
            null
        }
        val explicitBg = if (allowAesthetic) {
            customBackgroundColor
                ?: lastComponentStyle?.backgroundColor
                ?: lastComputedStyle?.backgroundColor
        } else {
            null
        }
        val hasExplicitOrSemanticBg = explicitBg != null || semanticType != null
        nativeWidget.putClientProperty("useSystemControlAppearance", if (hasExplicitOrSemanticBg) "false" else "true")
        nativeWidget.putClientProperty("bootstrapType", if (allowBootstrapColor) (semanticType ?: BootstrapType.NONE) else BootstrapType.NONE)
        nativeWidget.putClientProperty("bootstrapOutline", if (allowBootstrapColor) styleSnapshot.outline else false)

        val bgRgba = explicitBg
            ?: semanticType?.let { BootstrapColorPalette.resolveColor(it, isDark, activeThemeConfig) }
        val explicitFg = if (allowTypography) {
            customTextColor
                ?: lastComponentStyle?.color
                ?: lastComputedStyle?.textColor
        } else {
            null
        }
        val fgRgba = explicitFg
            ?: (if (allowBootstrapColor) {
                styleSnapshot.textColorType?.takeIf { it.isSemantic }?.let {
                    BootstrapColorPalette.resolveColor(it, isDark, activeThemeConfig)
                }
            } else {
                null
            })
            ?: semanticType?.let {
                if (styleSnapshot.outline) BootstrapColorPalette.resolveColor(it, isDark, activeThemeConfig)
                else BootstrapColorPalette.resolveForegroundOn(it, isDark, activeThemeConfig)
            }

        val preserveNativeChrome =
            nativeWidget is JProgressBar ||
                nativeWidget is JSlider ||
                nativeWidget is JTabbedPane ||
                nativeWidget is JScrollPane ||
                nativeWidget is JComboBox<*> ||
                nativeWidget is JTable ||
                nativeWidget is JTree ||
                nativeWidget is JTextComponent ||
                nativeWidget is JToolBar ||
                nativeWidget is javax.swing.JSeparator ||
                nativeWidget is javax.swing.JMenuBar ||
                nativeWidget is javax.swing.JMenu ||
                nativeWidget is javax.swing.JMenuItem

        val usesSurfaceChrome = SwingSurfacePaintUtil.shouldPaintSystemSurfaceChrome(
            nativeImpl = this,
            tagName = componentTagName,
            classPrefix = componentClassPrefix,
            classes = styleSnapshot.customClasses
        )

        if (bgRgba != null && !styleSnapshot.outline) {
            nativeWidget.background = Color(
                bgRgba.r.coerceIn(0, 255),
                bgRgba.g.coerceIn(0, 255),
                bgRgba.b.coerceIn(0, 255),
                bgRgba.a.coerceIn(0, 255)
            )
            val needsTransparentRoundedPass = usesSurfaceChrome ||
                (nativeWidget is JPanel && bgRgba.a < 255) ||
                allowsBackdropBlur()
            nativeWidget.isOpaque = bgRgba.a == 255 && !needsTransparentRoundedPass
            (nativeWidget as? AbstractButton)?.let { btn ->
                btn.isContentAreaFilled = true
                btn.isBorderPainted = true
            }
        } else if (keepsOpaqueContainerBackground()) {
            val c = themeColors.background
            nativeWidget.background = if (c != null) {
                Color(c.r.coerceIn(0, 255), c.g.coerceIn(0, 255), c.b.coerceIn(0, 255), c.a.coerceIn(0, 255))
            } else if (isDark) {
                Color(30, 30, 34)
            } else {
                defaultComponentBackground ?: Color(245, 245, 247)
            }
            nativeWidget.isOpaque = true
        } else if (nativeWidget is JButton || preserveNativeChrome) {
            // Preserve Swing System L&F native control background, borders & painters
            if (defaultComponentBackground != null) {
                nativeWidget.background = defaultComponentBackground
            }
            (nativeWidget as? AbstractButton)?.let { btn ->
                btn.isContentAreaFilled = true
                btn.isBorderPainted = true
            }
        } else if (nativeWidget is JPanel) {
            // Intermediate layout JPanels stay transparent so parent imagery, video, and backdrop blur composite cleanly
            nativeWidget.isOpaque = false
            nativeWidget.background = null
        }

        if (fgRgba != null) {
            nativeWidget.foreground = Color(
                fgRgba.r.coerceIn(0, 255),
                fgRgba.g.coerceIn(0, 255),
                fgRgba.b.coerceIn(0, 255),
                fgRgba.a.coerceIn(0, 255)
            )
        } else if (preserveNativeChrome || nativeWidget is JButton) {
            if (defaultComponentForeground != null) {
                nativeWidget.foreground = defaultComponentForeground
            } else {
                val defaultFg = themeColors.onSurface ?: themeColors.onBackground
                nativeWidget.foreground = if (defaultFg != null) {
                    Color(defaultFg.r.coerceIn(0, 255), defaultFg.g.coerceIn(0, 255), defaultFg.b.coerceIn(0, 255))
                } else if (isDark) {
                    Color(235, 235, 240)
                } else {
                    Color(28, 28, 30)
                }
            }
        } else {
            nativeWidget.foreground = SwingSurfacePaintUtil.resolveInheritedReadableTextColor(nativeWidget, isDark)
        }

        applyResolvedTypographyFont()

        if (styleSnapshot.display == DisplayMode.NONE || styleSnapshot.visibility == BootstrapVisibility.INVISIBLE) {
            nativeWidget.isVisible = false
        }
        styleSnapshot.opacity?.let {
            opacityVal = it.alpha.coerceIn(0f, 1f)
            nativeWidget.putClientProperty("opacity", opacityVal)
        }
        syncPointerListeners()
    }

    protected open fun applyResolvedTypographyFont() {
        val base = defaultComponentFont ?: nativeWidget.font ?: Font("SansSerif", Font.PLAIN, 14)
        val allowTypo = allowsSystemTypographyStyles()
        val compStyle = if (allowTypo) lastComponentStyle else null
        val compComputed = if (allowTypo) lastComputedStyle else null

        val explicitWeightNumeric = compStyle?.fontWeightNumeric
            ?: compComputed?.fontWeightNumeric
            ?: styleSnapshot.fontWeight?.numericWeight
            ?: when (compStyle?.getFontWeight()) {
                FontWeight.LIGHTER -> 200
                FontWeight.LIGHT -> 300
                FontWeight.NORMAL -> 400
                FontWeight.MEDIUM -> 500
                FontWeight.SEMIBOLD -> 600
                FontWeight.BOLD -> 700
                FontWeight.BOLDER -> 800
                null -> null
            }
        val headingDefaultSize: Float? = if (this is SwingTextImpl) {
            resolveDefaultHeadingFontSize(componentTagName, componentClassPrefix, styleSnapshot.customClasses)
        } else {
            null
        }
        val headingBoldDefault = this is SwingTextImpl && headingDefaultSize != null
        val effectiveWeight = explicitWeightNumeric ?: if (headingBoldDefault) 700 else null
        if (effectiveWeight != null) {
            nativeWidget.putClientProperty("fontWeightNumeric", effectiveWeight)
        }
        val isBold = effectiveWeight?.let { it >= 600 } ?: base.isBold
        val fontStyleSpec = compStyle?.getFontStyle()
        val isItalic = fontStyleSpec == com.nexustools.genericui.enums.css.CssFontStyle.ITALIC ||
            fontStyleSpec == com.nexustools.genericui.enums.css.CssFontStyle.OBLIQUE ||
            (this is SwingTextImpl && SwingSurfacePaintUtil.hasClassToken(componentClassPrefix, styleSnapshot.customClasses, "fst-italic"))
        val explicitFamilyName = compStyle?.getFontFamily()?.resolvedPrimaryName()
        val rawFamilyName = explicitFamilyName ?: base.family
        val familyName = if (explicitFamilyName == null && rawFamilyName.equals("Tahoma", ignoreCase = true)) {
            Font.SANS_SERIF
        } else {
            rawFamilyName
        }
        val sizePx = compStyle?.fontSizePx
            ?: compComputed?.fontSizePx
            ?: headingDefaultSize
            ?: styleSnapshot.fontSize?.let { bsFontSizePx(it) }
            ?: base.size2D
        val styleMask = (if (isBold) Font.BOLD else 0) or (if (isItalic) Font.ITALIC else 0)
        val widgetText = (nativeWidget as? javax.swing.AbstractButton)?.text
            ?: (nativeWidget as? javax.swing.JLabel)?.text
        val pointSize = sizePx.toInt().coerceAtLeast(1)
        val candidate = Font(familyName, styleMask, pointSize).deriveFont(sizePx)
        val resolvedFont = if (!widgetText.isNullOrEmpty() && candidate.canDisplayUpTo(widgetText) != -1) {
            val sansFallback = Font(Font.SANS_SERIF, styleMask, pointSize).deriveFont(sizePx)
            if (sansFallback.canDisplayUpTo(widgetText) == -1) sansFallback else candidate
        } else {
            candidate
        }
        val currentFont = nativeWidget.font
        val currentMissingGlyph = !widgetText.isNullOrEmpty() &&
            (currentFont == null || currentFont.canDisplayUpTo(widgetText) != -1)
        if (currentMissingGlyph ||
            resolvedFont.family != currentFont?.family ||
            styleMask != currentFont.style ||
            kotlin.math.abs(sizePx - currentFont.size2D) > 0.05f
        ) {
            nativeWidget.font = resolvedFont
        }
    }

    private fun bsFontSizePx(size: BootstrapFontSize): Float = when (size) {
        BootstrapFontSize.FS_1 -> 36f
        BootstrapFontSize.FS_2 -> 30f
        BootstrapFontSize.FS_3 -> 24f
        BootstrapFontSize.FS_4 -> 20f
        BootstrapFontSize.FS_5 -> 16f
        BootstrapFontSize.FS_6 -> 14f
    }

    private fun resolveDefaultHeadingFontSize(
        tagName: String,
        classPrefix: String,
        classes: Collection<String>
    ): Float? {
        val normTag = SwingSurfacePaintUtil.normalizeTag(tagName)
        return when {
            normTag == "h1" || SwingSurfacePaintUtil.hasClassToken(classPrefix, classes, "h1") -> 32f
            normTag == "h2" || SwingSurfacePaintUtil.hasClassToken(classPrefix, classes, "h2") -> 26f
            normTag == "h3" || SwingSurfacePaintUtil.hasClassToken(classPrefix, classes, "h3") -> 22f
            normTag == "h4" || SwingSurfacePaintUtil.hasClassToken(classPrefix, classes, "h4") -> 18f
            normTag == "h5" || SwingSurfacePaintUtil.hasClassToken(classPrefix, classes, "h5") -> 16f
            normTag == "h6" || SwingSurfacePaintUtil.hasClassToken(classPrefix, classes, "h6") -> 14f
            else -> null
        }
    }

    override fun setAttribute(name: String, value: String?) {
        if (value == null) {
            attributesMap.remove(name)
        } else {
            attributesMap[name] = value
        }
        if (name == "id") {
            nativeWidget.putClientProperty("id", value)
            if (!value.isNullOrBlank()) {
                nativeWidget.name = value
            }
        }
        if (!allowsBackdropBlur() && (
                name.equals("backdropBlurPx", ignoreCase = true) ||
                    name.equals("backdrop-filter", ignoreCase = true) ||
                    name.equals("-webkit-backdrop-filter", ignoreCase = true)
                )
        ) {
            nativeWidget.putClientProperty("backdropBlurPx", null)
            nativeWidget.putClientProperty("backdrop-filter", null)
            nativeWidget.putClientProperty("-webkit-backdrop-filter", null)
            return
        }
        nativeWidget.putClientProperty(name, value)
        when (name) {
            "layerPassThrough" -> syncPointerListeners()
            "data-titlebar-theme", "data-style-engine" -> {
                refreshThemeAppearance()
                refreshDescendantThemeAppearance()
            }
            "layerZIndex", "zIndex", "spacerWeight", "spacerFill", "spacerWidthPx", "spacerHeightPx",
            "data-stack-weight", "data-stack-aspect-ratio", "data-stack-shrink-cross",
            "layerSizingMode", "layerRole", "layerAlignment", "layerLeftPercent", "layerTopPercent",
            "layerRightPx", "layerBottomPx", "layerOffsetXPx", "layerOffsetYPx", "gapPx" -> invalidateLayoutHierarchy()
        }
    }

    override fun setClass(className: String) {
        val tokens = className.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
        styleSnapshot = styleSnapshot.copy(customClasses = tokens)
        syncPointerListeners()
        invalidateLayoutHierarchy()
    }

    override fun getClass(): String =
        styleSnapshot.customClasses.joinToString(" ")

    override fun getClasses(): Set<String> =
        styleSnapshot.customClasses.toSet()

    override fun addClass(className: String) {
        val tokens = className.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return
        val updated = LinkedHashSet(styleSnapshot.customClasses)
        updated.addAll(tokens)
        styleSnapshot = styleSnapshot.copy(customClasses = updated)
        syncPointerListeners()
        syncSwingAppearance()
        invalidateLayoutHierarchy()
    }

    override fun removeClass(className: String) {
        val tokens = className.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return
        val updated = LinkedHashSet(styleSnapshot.customClasses)
        updated.removeAll(tokens.toSet())
        styleSnapshot = styleSnapshot.copy(customClasses = updated)
        syncPointerListeners()
        syncSwingAppearance()
        invalidateLayoutHierarchy()
    }

    override fun hasClass(className: String): Boolean {
        val tokens = className.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        return styleSnapshot.customClasses.containsAll(tokens)
    }

    fun getEnumClasses(): Set<String> =
        BootstrapClassMapper.toEnumClassSet(styleSnapshot)

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        lastBootstrap = bootstrap
        styleSnapshot = bootstrap.applyToSnapshot(styleSnapshot)
        syncPointerListeners()
        syncSwingAppearance()
        invalidateLayoutHierarchy()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        rawLayoutComponentStyle = style
        val allowTypography = allowsSystemTypographyStyles()
        val allowAesthetic = allowsSystemAestheticStyles()
        val filtered = style.copyFilteredForSystemComponent(
            allowTypography = allowTypography,
            allowAestheticSkin = allowAesthetic
        )
        lastComponentStyle = filtered
        customBackgroundColor = if (allowAesthetic) filtered.backgroundColor else null
        customTextColor = if (allowTypography) filtered.color else null
        backgroundGradientState = if (allowAesthetic) filtered.backgroundImage?.gradient else null

        if (allowTypography) {
            filtered.fontSizePx?.let { nativeWidget.putClientProperty("fontSizePx", it) }
            filtered.getFontFamily()?.let { nativeWidget.putClientProperty("fontFamily", it.resolvedPrimaryName()) }
        } else {
            nativeWidget.putClientProperty("fontSizePx", null)
            nativeWidget.putClientProperty("fontFamily", null)
        }

        filtered.opacity?.let {
            opacityVal = it.coerceIn(0f, 1f)
            nativeWidget.putClientProperty("opacity", opacityVal)
        }
        val resolvedCursor = resolveEffectiveAwtCursor()
        applyAwtCursor(resolvedCursor)
        style.padding?.let { setPadding(it.toEdgeInsets()) }
        style.margin?.let { setMargin(it.toEdgeInsets()) }
        style.flexDirection?.let { styleSnapshot = styleSnapshot.copy(flexDirection = it) }
        style.flexWrap?.let { styleSnapshot = styleSnapshot.copy(flexWrap = it) }
        style.justifyContent?.let { styleSnapshot = styleSnapshot.copy(justifyContent = it) }
        style.alignItems?.let { styleSnapshot = styleSnapshot.copy(alignItems = it) }
        style.alignSelf?.let { styleSnapshot = styleSnapshot.copy(alignSelf = it) }

        val wFixed = style.width?.takeIf { it.unit != CssUnit.PERCENT && !it.isAuto }?.resolvePx()?.roundToInt()
        val hFixed = style.height?.takeIf { it.unit != CssUnit.PERCENT && !it.isAuto }?.resolvePx()?.roundToInt()
        if (wFixed != null) explicitPreferredWidthPx = wFixed.takeIf { it > 0 }
        if (hFixed != null) explicitPreferredHeightPx = hFixed.takeIf { it > 0 }

        val effectiveVis = style.visibility ?: filtered.visibility
        if (effectiveVis == CssVisibility.HIDDEN || effectiveVis == CssVisibility.COLLAPSE) {
            nativeWidget.isVisible = false
        } else if (effectiveVis == CssVisibility.VISIBLE) {
            nativeWidget.isVisible = true
        }
        syncSwingAppearance()
        invalidateLayoutHierarchy()
    }

    override fun setCustomCursor(cursor: CssCursorValue?) {
        customCursorState = cursor
        applyAwtCursor(resolveEffectiveAwtCursor())
    }

    private fun resolveEffectiveAwtCursor(): CssCursorValue? =
        customCursorState
            ?: lastComponentStyle?.cursor
            ?: resolveComputedCursor(lastComputedStyle)

    internal fun refreshEffectiveAwtCursor() {
        applyAwtCursor(resolveEffectiveAwtCursor())
    }

    internal fun resolveAwtCursor(cursor: CssCursorValue?): Cursor = when (cursor) {
        null -> Cursor.getDefaultCursor()
        is CssCursor -> mapCssCursorToAwt(cursor)
        is CssCustomCursor -> {
            if (!GraphicsEnvironment.isHeadless()) {
                val imgBuf = cursor.resolveToImageBuffer()
                if (imgBuf != null && imgBuf.width > 0 && imgBuf.height > 0) {
                    val bImg = BufferedImage(imgBuf.width, imgBuf.height, BufferedImage.TYPE_INT_ARGB)
                    for (y in 0 until imgBuf.height) {
                        for (x in 0 until imgBuf.width) {
                            val px = imgBuf.getPixel(x, y)
                            val argb = ((px.a and 0xFF) shl 24) or
                                ((px.r and 0xFF) shl 16) or
                                ((px.g and 0xFF) shl 8) or
                                (px.b and 0xFF)
                            bImg.setRGB(x, y, argb)
                        }
                    }
                    val hotSpot = Point(
                        cursor.hotspotX.coerceIn(0, (imgBuf.width - 1).coerceAtLeast(0)),
                        cursor.hotspotY.coerceIn(0, (imgBuf.height - 1).coerceAtLeast(0))
                    )
                    Toolkit.getDefaultToolkit().createCustomCursor(bImg, hotSpot, "GenericUICustomCursor")
                } else {
                    mapCssCursorToAwt(cursor.fallback)
                }
            } else {
                Cursor.getDefaultCursor()
            }
        }
        else -> Cursor.getDefaultCursor()
    }

    private fun applyAwtCursor(cursor: CssCursorValue?) {
        if (PointerDragManager.isCursorLocked) return
        nativeWidget.cursor = resolveAwtCursor(cursor)
    }

    private val blankCursor: Cursor by lazy {
        if (GraphicsEnvironment.isHeadless()) {
            Cursor.getDefaultCursor()
        } else {
            Toolkit.getDefaultToolkit().createCustomCursor(
                BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
                Point(0, 0),
                "none"
            )
        }
    }

    private fun mapCssCursorToAwt(cursor: CssCursor): Cursor = when (cursor) {
        CssCursor.NONE -> blankCursor
        CssCursor.POINTER -> Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        CssCursor.TEXT -> Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        CssCursor.CROSSHAIR -> Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
        CssCursor.MOVE, CssCursor.GRAB, CssCursor.GRABBING -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
        CssCursor.WAIT, CssCursor.PROGRESS -> Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
        CssCursor.N_RESIZE, CssCursor.ROW_RESIZE -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
        CssCursor.S_RESIZE, CssCursor.NS_RESIZE -> Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)
        CssCursor.E_RESIZE, CssCursor.EW_RESIZE, CssCursor.COL_RESIZE -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
        CssCursor.W_RESIZE -> Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
        CssCursor.NE_RESIZE, CssCursor.NESW_RESIZE -> Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR)
        CssCursor.NW_RESIZE, CssCursor.NWSE_RESIZE -> Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)
        CssCursor.SE_RESIZE -> Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
        CssCursor.SW_RESIZE -> Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR)
        else -> Cursor.getDefaultCursor()
    }

    override fun setBackgroundGradient(gradient: Gradient?) {
        backgroundGradientState = gradient
    }

    override fun getBackgroundGradient(): Gradient? =
        backgroundGradientState
            ?: lastComponentStyle?.backgroundImage?.gradient
            ?: lastComputedStyle?.backgroundGradient

    override fun setType(type: BootstrapType?) {
        styleSnapshot = styleSnapshot.copy(type = type)
        syncSwingAppearance()
    }
    override fun getType(): BootstrapType? = styleSnapshot.type

    override fun setOutline(outline: Boolean) {
        styleSnapshot = styleSnapshot.copy(outline = outline)
        syncSwingAppearance()
    }
    override fun isOutline(): Boolean = styleSnapshot.outline

    override fun setSize(size: BootstrapSize?) { styleSnapshot = styleSnapshot.copy(size = size) }
    override fun getSize(): BootstrapSize? = styleSnapshot.size

    override fun setBackgroundType(type: BootstrapType?) {
        styleSnapshot = styleSnapshot.copy(backgroundType = type)
        syncSwingAppearance()
    }
    override fun getBackgroundType(): BootstrapType? = styleSnapshot.backgroundType

    override fun setTextColorType(type: BootstrapType?) {
        styleSnapshot = styleSnapshot.copy(textColorType = type)
        syncSwingAppearance()
    }
    override fun getTextColorType(): BootstrapType? = styleSnapshot.textColorType

    override fun setBorderType(type: BootstrapType?) { styleSnapshot = styleSnapshot.copy(borderType = type) }
    override fun getBorderType(): BootstrapType? = styleSnapshot.borderType

    override fun setTextAlignment(alignment: TextAlignment?) { styleSnapshot = styleSnapshot.copy(textAlignment = alignment) }
    override fun getTextAlignment(): TextAlignment? = styleSnapshot.textAlignment

    override fun setFontWeight(weight: FontWeight?) {
        styleSnapshot = styleSnapshot.copy(fontWeight = weight)
        syncSwingAppearance()
    }
    override fun getFontWeight(): FontWeight? = styleSnapshot.fontWeight

    override fun setTextTransform(transform: TextTransform?) { styleSnapshot = styleSnapshot.copy(textTransform = transform) }
    override fun getTextTransform(): TextTransform? = styleSnapshot.textTransform

    override fun setTextOverflow(overflow: TextOverflow?) { styleSnapshot = styleSnapshot.copy(textOverflow = overflow) }
    override fun getTextOverflow(): TextOverflow? = styleSnapshot.textOverflow

    override fun setBorderRadius(radius: BorderRadiusStyle?) { styleSnapshot = styleSnapshot.copy(borderRadius = radius) }
    override fun getBorderRadius(): BorderRadiusStyle? = styleSnapshot.borderRadius

    override fun setShadow(shadow: ShadowElevation?) { styleSnapshot = styleSnapshot.copy(shadow = shadow) }
    override fun getShadow(): ShadowElevation? = styleSnapshot.shadow

    override fun setDisplay(display: DisplayMode?) {
        styleSnapshot = styleSnapshot.copy(display = display)
        syncSwingAppearance()
        invalidateLayoutHierarchy()
    }
    override fun getDisplay(): DisplayMode? = styleSnapshot.display

    override fun setFlexDirection(direction: FlexDirection?) {
        styleSnapshot = styleSnapshot.copy(flexDirection = direction)
        invalidateLayoutHierarchy()
    }
    override fun getFlexDirection(): FlexDirection? = styleSnapshot.flexDirection

    override fun setFlexWrap(wrap: FlexWrap?) {
        styleSnapshot = styleSnapshot.copy(flexWrap = wrap)
        invalidateLayoutHierarchy()
    }
    override fun getFlexWrap(): FlexWrap? = styleSnapshot.flexWrap

    override fun setJustifyContent(justify: JustifyContent?) {
        styleSnapshot = styleSnapshot.copy(justifyContent = justify)
        invalidateLayoutHierarchy()
    }
    override fun getJustifyContent(): JustifyContent? = styleSnapshot.justifyContent

    override fun setAlignItems(align: AlignItems?) {
        styleSnapshot = styleSnapshot.copy(alignItems = align)
        invalidateLayoutHierarchy()
    }
    override fun getAlignItems(): AlignItems? = styleSnapshot.alignItems

    override fun setAlignSelf(align: AlignSelf?) {
        styleSnapshot = styleSnapshot.copy(alignSelf = align)
        invalidateLayoutHierarchy()
    }
    override fun getAlignSelf(): AlignSelf? = styleSnapshot.alignSelf

    override fun setOverflow(overflow: OverflowBehavior?) {
        styleSnapshot = styleSnapshot.copy(overflow = overflow)
        invalidateLayoutHierarchy()
    }
    override fun getOverflow(): OverflowBehavior? = styleSnapshot.overflow

    override fun setPosition(position: PositionMode?) {
        styleSnapshot = styleSnapshot.copy(position = position)
        invalidateLayoutHierarchy()
    }
    override fun getPosition(): PositionMode? = styleSnapshot.position

    override fun setMarginAll(scale: SpacingScale?) {
        styleSnapshot = styleSnapshot.copy(marginAll = scale)
        invalidateLayoutHierarchy()
    }
    override fun getMarginAll(): SpacingScale? = styleSnapshot.marginAll

    override fun setPaddingAll(scale: SpacingScale?) {
        styleSnapshot = styleSnapshot.copy(paddingAll = scale)
        invalidateLayoutHierarchy()
    }
    override fun getPaddingAll(): SpacingScale? = styleSnapshot.paddingAll

    override fun setVisible(visible: Boolean) {
        nativeWidget.isVisible = visible
        invalidateLayoutHierarchy()
    }

    override fun isVisible(): Boolean = nativeWidget.isVisible

    override fun setEnabled(enabled: Boolean) {
        nativeWidget.isEnabled = enabled
    }

    override fun isEnabled(): Boolean = nativeWidget.isEnabled

    override fun setFocusable(focusable: Boolean) {
        if (focusableState == focusable) return
        focusableState = focusable
        applyAwtFocusableRecursive(nativeWidget, focusable)
        if (!focusable) {
            setFocusedState(false)
            if (isFocusOwnerInSubtree(nativeWidget)) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().clearGlobalFocusOwner()
            }
        }
    }

    override fun isFocusable(): Boolean = focusableState

    protected fun isEffectivelyFocusableInSwing(): Boolean {
        if (!focusableState) return false
        var cur: Container? = nativeWidget.parent
        while (cur != null) {
            val owner = (cur as? JComponent)?.getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl
            if (owner != null && !owner.isFocusable()) return false
            cur = cur.parent
        }
        return true
    }

    protected fun applyAwtFocusableRecursive(comp: Component, inheritedFocusable: Boolean) {
        val childOwner = if (comp !== nativeWidget) {
            (comp as? JComponent)?.getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl
        } else {
            null
        }
        val effective = if (childOwner != null) {
            inheritedFocusable && childOwner.isFocusable()
        } else {
            inheritedFocusable
        }
        if (!effective) {
            if (!savedAwtFocusable.containsKey(comp)) {
                savedAwtFocusable[comp] = comp.isFocusable
            }
            comp.isFocusable = false
        } else if (savedAwtFocusable.containsKey(comp)) {
            comp.isFocusable = savedAwtFocusable.remove(comp) ?: true
        }
        if (comp is JComboBox<*>) {
            comp.editor?.editorComponent?.let { ed ->
                applyAwtFocusableRecursive(ed, effective)
            }
        }
        if (comp is JSpinner) {
            (comp.editor as? JSpinner.DefaultEditor)?.textField?.let { tf ->
                applyAwtFocusableRecursive(tf, effective)
            }
        }
        if (comp is Container) {
            for (child in comp.components) {
                applyAwtFocusableRecursive(child, effective)
            }
        }
    }

    private fun isFocusOwnerInSubtree(comp: Component): Boolean {
        if (comp.isFocusOwner) return true
        if (comp is JComboBox<*>) {
            val ed = comp.editor?.editorComponent
            if (ed != null && isFocusOwnerInSubtree(ed)) return true
        }
        if (comp is JSpinner) {
            val tf = (comp.editor as? JSpinner.DefaultEditor)?.textField
            if (tf != null && isFocusOwnerInSubtree(tf)) return true
        }
        if (comp is Container) {
            for (child in comp.components) {
                if (isFocusOwnerInSubtree(child)) return true
            }
        }
        return false
    }

    override fun setOpacity(opacity: Float) {
        opacityVal = opacity.coerceIn(0f, 1f)
        nativeWidget.putClientProperty("opacity", opacityVal)
        nativeWidget.repaint()
    }

    override fun getOpacity(): Float = opacityVal

    override fun setTooltipText(tooltip: String?) {
        nativeWidget.toolTipText = tooltip
    }

    override fun getTooltipText(): String? = nativeWidget.toolTipText

    override fun setPreferredSize(size: Size?) {
        if (size != null) {
            explicitPreferredWidthPx = size.width.takeIf { it > 0 }
            explicitPreferredHeightPx = size.height.takeIf { it > 0 }
            val w = explicitPreferredWidthPx
            val h = explicitPreferredHeightPx
            if (w != null && h != null) {
                nativeWidget.preferredSize = Dimension(w, h)
                nativeWidget.setSize(w, h)
                logicalBounds = logicalBounds.copy(width = w, height = h)
            } else {
                // Single-axis constraint: never pass 0px on the unconstrained orthogonal axis
                nativeWidget.preferredSize = null
                val natural = SwingIntrinsicSizeMeasurer.measureIntrinsicSize(
                    nativeWidget,
                    w ?: 800,
                    h ?: 600
                )
                val resolvedW = w ?: natural.width.coerceAtLeast(1)
                val resolvedH = h ?: natural.height.coerceAtLeast(1)
                if (nativeWidget !is JPanel && nativeWidget.layout == null) {
                    nativeWidget.preferredSize = Dimension(resolvedW, resolvedH)
                }
                logicalBounds = logicalBounds.copy(width = resolvedW, height = resolvedH)
            }
        } else {
            explicitPreferredWidthPx = null
            explicitPreferredHeightPx = null
            nativeWidget.preferredSize = null
        }
        invalidateLayoutHierarchy()
    }

    internal fun getExplicitWidthPx(): Int? = explicitPreferredWidthPx

    internal fun getExplicitHeightPx(): Int? = explicitPreferredHeightPx

    internal fun updateLogicalBoundsFromAwt(x: Int, y: Int, w: Int, h: Int) {
        logicalBounds = Rect(x, y, w, h)
    }

    override fun getBounds(): Rect {
        val b = nativeWidget.bounds
        return if (b.width > 0 || b.height > 0) {
            Rect(b.x, b.y, b.width, b.height)
        } else {
            logicalBounds
        }
    }

    override fun setPadding(padding: EdgeInsets) {
        paddingInsets = padding
        val managedLayout = (nativeWidget as? Container)?.layout is SwingFlexLayoutManager
        nativeWidget.putClientProperty(SwingFlexLayoutManager.MANAGED_LAYOUT_INSETS_KEY, managedLayout)
        if (!managedLayout) {
            if (padding != EdgeInsets.ZERO) {
                nativeWidget.border = BorderFactory.createEmptyBorder(padding.top, padding.left, padding.bottom, padding.right)
            } else {
                nativeWidget.border = defaultComponentBorder
            }
        }
        invalidateLayoutHierarchy()
    }

    override fun setMargin(margin: EdgeInsets) {
        marginInsets = margin
        invalidateLayoutHierarchy()
    }

    protected fun invalidateLayoutHierarchy() {
        nativeWidget.invalidate()
        (nativeWidget.parent as? JComponent)?.let { parent ->
            parent.invalidate()
            if (parent.isShowing) {
                parent.revalidate()
            }
        }
    }

    internal open fun buildLayoutStyleInput(): SwingLayoutStyleInput {
        val allClasses = LinkedHashSet<String>()
        if (componentClassPrefix.isNotBlank()) {
            allClasses.addAll(componentClassPrefix.trim().split(Regex("\\s+")).filter { it.isNotEmpty() })
        }
        allClasses.addAll(getEnumClasses())
        allClasses.addAll(getClasses())

        val styleProps = linkedMapOf<String, String>()
        rawLayoutComputedStyle?.let { computed ->
            computed.display?.let { styleProps["display"] = it }
            computed.visibility?.cssValue?.let { styleProps["visibility"] = it }
            computed.position?.let { styleProps["position"] = it }
            computed.top?.let { styleProps["top"] = it }
            computed.right?.let { styleProps["right"] = it }
            computed.bottom?.let { styleProps["bottom"] = it }
            computed.left?.let { styleProps["left"] = it }
            computed.boxSizing?.cssValue?.let { styleProps["box-sizing"] = it }
            computed.width?.let { styleProps["width"] = it }
            computed.height?.let { styleProps["height"] = it }
            computed.minWidth?.let { styleProps["min-width"] = it }
            computed.minHeight?.let { styleProps["min-height"] = it }
            computed.maxWidth?.let { styleProps["max-width"] = it }
            computed.maxHeight?.let { styleProps["max-height"] = it }
            computed.aspectRatio?.let { styleProps["aspect-ratio"] = it.toString() }
            computed.padding?.toCssString()?.let { styleProps["padding"] = it }
            computed.paddingTop?.toCssString()?.let { styleProps["padding-top"] = it }
            computed.paddingRight?.toCssString()?.let { styleProps["padding-right"] = it }
            computed.paddingBottom?.toCssString()?.let { styleProps["padding-bottom"] = it }
            computed.paddingLeft?.toCssString()?.let { styleProps["padding-left"] = it }
            computed.margin?.toCssString()?.let { styleProps["margin"] = it }
            computed.marginTop?.toCssString()?.let { styleProps["margin-top"] = it }
            computed.marginRight?.toCssString()?.let { styleProps["margin-right"] = it }
            computed.marginBottom?.toCssString()?.let { styleProps["margin-bottom"] = it }
            computed.marginLeft?.toCssString()?.let { styleProps["margin-left"] = it }
            computed.borderWidthPx?.let { styleProps["border-width"] = "${it}px" }
            computed.flexDirection?.let { styleProps["flex-direction"] = it.name.lowercase().replace('_', '-') }
            computed.flexWrap?.let { styleProps["flex-wrap"] = it.name.lowercase().replace('_', '-') }
            computed.justifyContent?.let {
                styleProps["justify-content"] = when (it) {
                    JustifyContent.START -> "flex-start"
                    JustifyContent.END -> "flex-end"
                    JustifyContent.CENTER -> "center"
                    JustifyContent.BETWEEN -> "space-between"
                    JustifyContent.AROUND -> "space-around"
                    JustifyContent.EVENLY -> "space-evenly"
                }
            }
            computed.alignItems?.let {
                styleProps["align-items"] = when (it) {
                    AlignItems.START -> "flex-start"
                    AlignItems.END -> "flex-end"
                    AlignItems.CENTER -> "center"
                    AlignItems.BASELINE -> "baseline"
                    AlignItems.STRETCH -> "stretch"
                }
            }
            computed.alignSelf?.let {
                styleProps["align-self"] = when (it) {
                    AlignSelf.AUTO -> "auto"
                    AlignSelf.START -> "flex-start"
                    AlignSelf.END -> "flex-end"
                    AlignSelf.CENTER -> "center"
                    AlignSelf.BASELINE -> "baseline"
                    AlignSelf.STRETCH -> "stretch"
                }
            }
            computed.alignContent?.cssValue?.let { styleProps["align-content"] = it }
            computed.flexGrow?.let { styleProps["flex-grow"] = it.toString() }
            computed.flexShrink?.let { styleProps["flex-shrink"] = it.toString() }
            computed.flexBasis?.toCssString()?.let { styleProps["flex-basis"] = it }
            computed.order?.let { styleProps["order"] = it.toString() }
            computed.gap?.toCssString()?.let { styleProps["gap"] = it }
            computed.rowGap?.toCssString()?.let { styleProps["row-gap"] = it }
            computed.columnGap?.toCssString()?.let { styleProps["column-gap"] = it }
            computed.overflow?.let { styleProps["overflow"] = it }
            computed.overflowX?.let { styleProps["overflow-x"] = it.name.lowercase() }
            computed.overflowY?.let { styleProps["overflow-y"] = it.name.lowercase() }
            computed.zIndex?.let { styleProps["z-index"] = it.toString() }
            computed.transform?.let { styleProps["transform"] = it }
            computed.transform2D?.takeIf { it != CssTransform2D.IDENTITY }?.let {
                if ("transform" !in styleProps) styleProps["transform"] = it.toCssString()
            }
        }
        rawLayoutComponentStyle?.takeIf { !it.isEmpty() }?.let { compStyle ->
            for ((k, pair) in compStyle.toCssPropertyMap()) {
                styleProps[k.lowercase()] = pair.first
            }
        }
        // Apply direct NativeImpl enum overrides if set explicitly
        styleSnapshot.display?.let { d ->
            styleProps["display"] = when (d) {
                DisplayMode.FLEX -> "flex"
                DisplayMode.INLINE_FLEX -> "inline-flex"
                DisplayMode.BLOCK -> "block"
                DisplayMode.INLINE -> "inline"
                DisplayMode.INLINE_BLOCK -> "inline-block"
                DisplayMode.GRID -> "grid"
                DisplayMode.NONE -> "none"
            }
        }
        styleSnapshot.flexDirection?.let { styleProps["flex-direction"] = it.name.lowercase().replace('_', '-') }
        styleSnapshot.flexWrap?.let { styleProps["flex-wrap"] = it.name.lowercase().replace('_', '-') }
        styleSnapshot.justifyContent?.let {
            styleProps["justify-content"] = when (it) {
                JustifyContent.START -> "flex-start"
                JustifyContent.END -> "flex-end"
                JustifyContent.CENTER -> "center"
                JustifyContent.BETWEEN -> "space-between"
                JustifyContent.AROUND -> "space-around"
                JustifyContent.EVENLY -> "space-evenly"
            }
        }
        styleSnapshot.alignItems?.let {
            styleProps["align-items"] = when (it) {
                AlignItems.START -> "flex-start"
                AlignItems.END -> "flex-end"
                AlignItems.CENTER -> "center"
                AlignItems.BASELINE -> "baseline"
                AlignItems.STRETCH -> "stretch"
            }
        }
        styleSnapshot.alignSelf?.let {
            styleProps["align-self"] = when (it) {
                AlignSelf.AUTO -> "auto"
                AlignSelf.START -> "flex-start"
                AlignSelf.END -> "flex-end"
                AlignSelf.CENTER -> "center"
                AlignSelf.BASELINE -> "baseline"
                AlignSelf.STRETCH -> "stretch"
            }
        }
        styleSnapshot.position?.let {
            if ("position" !in styleProps) {
                styleProps["position"] = it.name.lowercase()
            }
        }

        val cssBorderPx = (rawLayoutComponentStyle?.borderWidthPx ?: rawLayoutComputedStyle?.borderWidthPx ?: 0f).roundToInt()
        return SwingLayoutStyleInput(
            tagName = componentTagName,
            classPrefix = componentClassPrefix,
            classes = allClasses,
            attributes = HashMap(attributesMap),
            styleProps = styleProps,
            padding = paddingInsets,
            margin = marginInsets,
            explicitWidthPx = explicitPreferredWidthPx,
            explicitHeightPx = explicitPreferredHeightPx,
            cssBorderWidthPx = cssBorderPx,
            visible = nativeWidget.isVisible
        )
    }

    private fun resolveComputedCursor(style: ComputedStyle?): CssCursorValue? =
        style?.customCursor ?: style?.cursor?.let { CssCustomCursor.parse(it) }

    override fun applyComputedStyle(style: ComputedStyle) {
        rawLayoutComputedStyle = style
        val allowTypography = allowsSystemTypographyStyles()
        val allowAesthetic = allowsSystemAestheticStyles()
        val filtered = style.copyFilteredForSystemComponent(
            allowTypography = allowTypography,
            allowAestheticSkin = allowAesthetic
        )
        lastComputedStyle = filtered
        if (!allowsBackdropBlur()) {
            nativeWidget.putClientProperty("backdropBlurPx", null)
            nativeWidget.putClientProperty("backdrop-filter", null)
            nativeWidget.putClientProperty("-webkit-backdrop-filter", null)
        }
        if (allowAesthetic) {
            filtered.backgroundColor?.let { c ->
                val alpha = c.a.coerceIn(0, 255)
                nativeWidget.background = Color(c.r.coerceIn(0, 255), c.g.coerceIn(0, 255), c.b.coerceIn(0, 255), alpha)
                nativeWidget.isOpaque = alpha == 255 && nativeWidget !is JPanel
            }
        }
        if (allowTypography) {
            filtered.textColor?.let { c ->
                val alpha = c.a.coerceIn(0, 255)
                nativeWidget.foreground = Color(c.r.coerceIn(0, 255), c.g.coerceIn(0, 255), c.b.coerceIn(0, 255), alpha)
            }
        }
        filtered.opacity.let {
            val coerced = it.coerceIn(0f, 1f)
            lastAppliedComputedOpacity = coerced
            opacityVal = coerced
            nativeWidget.putClientProperty("opacity", opacityVal)
        }
        style.padding?.let { setPadding(it.toEdgeInsets()) }
        style.margin?.let { setMargin(it.toEdgeInsets()) }
        val wFixed = CssLength.parse(style.width)?.takeIf { it.unit != CssUnit.PERCENT && !it.isAuto }?.resolvePx()?.roundToInt()
        val hFixed = CssLength.parse(style.height)?.takeIf { it.unit != CssUnit.PERCENT && !it.isAuto }?.resolvePx()?.roundToInt()
        if (wFixed != null) explicitPreferredWidthPx = wFixed.takeIf { it > 0 }
        if (hFixed != null) explicitPreferredHeightPx = hFixed.takeIf { it > 0 }

        applyAwtCursor(resolveEffectiveAwtCursor())
        syncSwingAppearance()
        if (SwingSurfacePaintUtil.shouldPaintSystemSurfaceChrome(
                nativeImpl = this,
                tagName = componentTagName,
                classPrefix = componentClassPrefix,
                classes = styleSnapshot.customClasses
            )
        ) {
            refreshDescendantThemeAppearance()
        }
        invalidateLayoutHierarchy()
        nativeWidget.repaint()
    }

    override fun supportsNativeTransitions(): Boolean = false

    override fun configureNativeTransitions(specs: List<CssTransitionSpec>) {
        nativeWidget.putClientProperty("nativeTransitionSpecs", specs)
    }

    override fun runNativeTransition(
        property: String,
        fromValue: String?,
        toValue: String,
        spec: CssTransitionSpec,
        onComplete: () -> Unit
    ): Boolean = false

    override fun setOnClick(listener: (() -> Unit)?) { onClickListener = listener; syncPointerListeners() }
    override fun setOnDoubleClick(listener: (() -> Unit)?) { onDoubleClickListener = listener; syncPointerListeners() }
    override fun setOnContextMenu(listener: ((PointerEvent) -> Unit)?) { onContextMenuListener = listener; syncPointerListeners() }
    override fun setOnPointerDown(listener: ((PointerEvent) -> Unit)?) { onPointerDownListener = listener; syncPointerListeners() }
    override fun setOnPointerMove(listener: ((PointerEvent) -> Unit)?) { onPointerMoveListener = listener; syncPointerListeners() }
    override fun setOnPointerUp(listener: ((PointerEvent) -> Unit)?) { onPointerUpListener = listener; syncPointerListeners() }
    override fun setOnPointerEnter(listener: ((PointerEvent) -> Unit)?) { onPointerEnterListener = listener; syncPointerListeners() }
    override fun setOnPointerLeave(listener: ((PointerEvent) -> Unit)?) { onPointerLeaveListener = listener; syncPointerListeners() }
    override fun setOnWheel(listener: ((WheelEvent) -> Unit)?) { onWheelListener = listener; syncPointerListeners() }
    override fun setOnKeyDown(listener: ((KeyEvent) -> Unit)?) { onKeyDownListener = listener }
    override fun setOnKeyUp(listener: ((KeyEvent) -> Unit)?) { onKeyUpListener = listener }

    fun performContextMenu(x: Float = 0f, y: Float = 0f): Boolean {
        val listener = onContextMenuListener ?: return false
        listener(PointerEvent(x = x, y = y, button = 2))
        return true
    }

    override fun dispose() {
        (nativeWidget.getClientProperty("swingOpacityChildrenBuffer") as? BufferedImage)?.flush()
        nativeWidget.putClientProperty("swingOpacityChildrenBuffer", null)
        nativeWidget.parent?.remove(nativeWidget)
    }

    private fun toPointerEvent(e: MouseEvent): PointerEvent {
        val btn = when (e.button) {
            MouseEvent.BUTTON1 -> 0
            MouseEvent.BUTTON2 -> 1
            MouseEvent.BUTTON3 -> 2
            else -> 0
        }
        return PointerEvent(
            x = e.x.toFloat(),
            y = e.y.toFloat(),
            screenX = e.xOnScreen.toFloat(),
            screenY = e.yOnScreen.toFloat(),
            button = btn,
            clickCount = e.clickCount,
            ctrlKey = e.isControlDown,
            shiftKey = e.isShiftDown,
            altKey = e.isAltDown,
            metaKey = e.isMetaDown
        )
    }

    private fun toKeyEvent(e: java.awt.event.KeyEvent): KeyEvent {
        val isTextComp = e.component is javax.swing.text.JTextComponent
        com.nexustools.genericui.event.InputFocusManager.isNativeTextInputFocused = isTextComp
        return KeyEvent(
            key = java.awt.event.KeyEvent.getKeyText(e.keyCode),
            code = e.keyCode.toString(),
            keyCode = e.keyCode,
            ctrlKey = e.isControlDown,
            shiftKey = e.isShiftDown,
            altKey = e.isAltDown,
            metaKey = e.isMetaDown,
            isTargetTextInput = isTextComp
        )
    }
}
