package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.bootstrap.*
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.enums.css.CssVisibility
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.event.KeyEvent
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
import com.nexustools.genericui.style.gradient.Gradient
import com.nexustools.genericui.style.mapper.BootstrapClassMapper
import com.nexustools.genericui.style.mapper.BootstrapStyleSnapshot
import com.nexustools.genericui.style.theme.SystemThemeDetector
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Toolkit
import java.awt.event.KeyAdapter
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Base [NativeImpl] for the Java `:swing` backend.
 */
open class SwingBaseNativeImpl(
    override val nativeWidget: JComponent = JPanel(),
    override val componentClassPrefix: String = ""
) : NativeImpl<JComponent> {

    protected var styleSnapshot: BootstrapStyleSnapshot =
        BootstrapStyleSnapshot(componentPrefix = componentClassPrefix)

    private var opacityVal: Float = 1.0f
    private var paddingInsets: EdgeInsets = EdgeInsets.ZERO
    private var marginInsets: EdgeInsets = EdgeInsets.ZERO
    private var logicalBounds: Rect = Rect(0, 0, 200, 40)

    protected var lastComputedStyle: ComputedStyle? = null
    protected var lastComponentStyle: ComponentStyle? = null
    protected var lastBootstrap: ComponentBootstrap? = null
    protected var customCursorState: CssCursorValue? = null
    private var customBackgroundColor: RgbaColor? = null
    private var customTextColor: RgbaColor? = null
    private var backgroundGradientState: Gradient? = null

    private val defaultComponentBackground: Color? = nativeWidget.background
    private val defaultComponentForeground: Color? = nativeWidget.foreground
    private val defaultComponentBorder = nativeWidget.border

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

    init {
        val mouseAdapter = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!nativeWidget.isEnabled || styleSnapshot.pointerEvents == BootstrapPointerEvents.NONE) return
                if (e.clickCount >= 2) {
                    onDoubleClickListener?.invoke()
                } else if (e.button == MouseEvent.BUTTON1) {
                    onClickListener?.invoke()
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (!nativeWidget.isEnabled || styleSnapshot.pointerEvents == BootstrapPointerEvents.NONE) return
                val pe = toPointerEvent(e)
                onPointerDownListener?.invoke(pe)
                if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) {
                    onContextMenuListener?.invoke(pe)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (!nativeWidget.isEnabled || styleSnapshot.pointerEvents == BootstrapPointerEvents.NONE) return
                val pe = toPointerEvent(e)
                onPointerUpListener?.invoke(pe)
                if (e.isPopupTrigger) {
                    onContextMenuListener?.invoke(pe)
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                if (!nativeWidget.isEnabled || styleSnapshot.pointerEvents == BootstrapPointerEvents.NONE) return
                onPointerMoveListener?.invoke(toPointerEvent(e))
            }

            override fun mouseDragged(e: MouseEvent) {
                if (!nativeWidget.isEnabled || styleSnapshot.pointerEvents == BootstrapPointerEvents.NONE) return
                onPointerMoveListener?.invoke(toPointerEvent(e))
            }

            override fun mouseEntered(e: MouseEvent) {
                onPointerEnterListener?.invoke(toPointerEvent(e))
            }

            override fun mouseExited(e: MouseEvent) {
                onPointerLeaveListener?.invoke(toPointerEvent(e))
            }
        }
        nativeWidget.addMouseListener(mouseAdapter)
        nativeWidget.addMouseMotionListener(mouseAdapter)
        nativeWidget.addMouseWheelListener { e ->
            if (nativeWidget.isEnabled && styleSnapshot.pointerEvents != BootstrapPointerEvents.NONE) {
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
        nativeWidget.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (nativeWidget.isEnabled) onKeyDownListener?.invoke(toKeyEvent(e))
            }

            override fun keyReleased(e: java.awt.event.KeyEvent) {
                if (nativeWidget.isEnabled) onKeyUpListener?.invoke(toKeyEvent(e))
            }
        })
    }

    protected open fun syncSwingAppearance() {
        val isDark = SystemThemeDetector.detectSystemTheme() == ThemeMode.DARK
        val semanticType = styleSnapshot.type?.takeIf { it.isSemantic }
            ?: styleSnapshot.backgroundType?.takeIf { it.isSemantic }
        val bgRgba = customBackgroundColor
            ?: lastComponentStyle?.backgroundColor
            ?: semanticType?.let { BootstrapColorPalette.resolveColor(it, isDark) }
        val fgRgba = customTextColor
            ?: lastComponentStyle?.color
            ?: styleSnapshot.textColorType?.takeIf { it.isSemantic }?.let { BootstrapColorPalette.resolveColor(it, isDark) }
            ?: semanticType?.let {
                if (styleSnapshot.outline) BootstrapColorPalette.resolveColor(it, isDark)
                else BootstrapColorPalette.resolveForegroundOn(it)
            }

        if (bgRgba != null && !styleSnapshot.outline) {
            nativeWidget.background = Color(bgRgba.r.coerceIn(0, 255), bgRgba.g.coerceIn(0, 255), bgRgba.b.coerceIn(0, 255), bgRgba.a.coerceIn(0, 255))
            nativeWidget.isOpaque = bgRgba.a > 0
            (nativeWidget as? JButton)?.isContentAreaFilled = true
        } else {
            nativeWidget.background = defaultComponentBackground
        }

        if (fgRgba != null) {
            nativeWidget.foreground = Color(fgRgba.r.coerceIn(0, 255), fgRgba.g.coerceIn(0, 255), fgRgba.b.coerceIn(0, 255), fgRgba.a.coerceIn(0, 255))
        } else {
            nativeWidget.foreground = defaultComponentForeground
        }

        if (styleSnapshot.display == DisplayMode.NONE || styleSnapshot.visibility == BootstrapVisibility.INVISIBLE) {
            nativeWidget.isVisible = false
        }
        styleSnapshot.opacity?.let {
            opacityVal = it.alpha.coerceIn(0f, 1f)
        }
    }

    override fun setClass(className: String) {
        val tokens = className.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
        styleSnapshot = styleSnapshot.copy(customClasses = tokens)
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
    }

    override fun removeClass(className: String) {
        val tokens = className.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return
        val updated = LinkedHashSet(styleSnapshot.customClasses)
        updated.removeAll(tokens.toSet())
        styleSnapshot = styleSnapshot.copy(customClasses = updated)
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
        syncSwingAppearance()
    }

    override fun applyComponentStyle(style: ComponentStyle) {
        lastComponentStyle = style
        customBackgroundColor = style.backgroundColor
        customTextColor = style.color
        style.backgroundImage?.gradient?.let { backgroundGradientState = it }
        style.opacity?.let { opacityVal = it.coerceIn(0f, 1f) }
        style.cursor?.let { setCustomCursor(it) }
        if (style.visibility == CssVisibility.HIDDEN || style.visibility == CssVisibility.COLLAPSE) {
            nativeWidget.isVisible = false
        }
        syncSwingAppearance()
    }

    override fun setCustomCursor(cursor: CssCursorValue?) {
        customCursorState = cursor
        when (cursor) {
            null -> nativeWidget.cursor = Cursor.getDefaultCursor()
            is CssCursor -> nativeWidget.cursor = mapCssCursorToAwt(cursor)
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
                        nativeWidget.cursor = Toolkit.getDefaultToolkit().createCustomCursor(bImg, hotSpot, "GenericUICustomCursor")
                    } else {
                        nativeWidget.cursor = mapCssCursorToAwt(cursor.fallback)
                    }
                }
            }
            else -> nativeWidget.cursor = Cursor.getDefaultCursor()
        }
    }

    private fun mapCssCursorToAwt(cursor: CssCursor): Cursor = when (cursor) {
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

    override fun setFontWeight(weight: FontWeight?) { styleSnapshot = styleSnapshot.copy(fontWeight = weight) }
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
    }
    override fun getDisplay(): DisplayMode? = styleSnapshot.display

    override fun setFlexDirection(direction: FlexDirection?) { styleSnapshot = styleSnapshot.copy(flexDirection = direction) }
    override fun getFlexDirection(): FlexDirection? = styleSnapshot.flexDirection

    override fun setFlexWrap(wrap: FlexWrap?) { styleSnapshot = styleSnapshot.copy(flexWrap = wrap) }
    override fun getFlexWrap(): FlexWrap? = styleSnapshot.flexWrap

    override fun setJustifyContent(justify: JustifyContent?) { styleSnapshot = styleSnapshot.copy(justifyContent = justify) }
    override fun getJustifyContent(): JustifyContent? = styleSnapshot.justifyContent

    override fun setAlignItems(align: AlignItems?) { styleSnapshot = styleSnapshot.copy(alignItems = align) }
    override fun getAlignItems(): AlignItems? = styleSnapshot.alignItems

    override fun setAlignSelf(align: AlignSelf?) { styleSnapshot = styleSnapshot.copy(alignSelf = align) }
    override fun getAlignSelf(): AlignSelf? = styleSnapshot.alignSelf

    override fun setOverflow(overflow: OverflowBehavior?) { styleSnapshot = styleSnapshot.copy(overflow = overflow) }
    override fun getOverflow(): OverflowBehavior? = styleSnapshot.overflow

    override fun setPosition(position: PositionMode?) { styleSnapshot = styleSnapshot.copy(position = position) }
    override fun getPosition(): PositionMode? = styleSnapshot.position

    override fun setMarginAll(scale: SpacingScale?) { styleSnapshot = styleSnapshot.copy(marginAll = scale) }
    override fun getMarginAll(): SpacingScale? = styleSnapshot.marginAll

    override fun setPaddingAll(scale: SpacingScale?) { styleSnapshot = styleSnapshot.copy(paddingAll = scale) }
    override fun getPaddingAll(): SpacingScale? = styleSnapshot.paddingAll

    override fun setVisible(visible: Boolean) {
        nativeWidget.isVisible = visible
    }

    override fun isVisible(): Boolean = nativeWidget.isVisible

    override fun setEnabled(enabled: Boolean) {
        nativeWidget.isEnabled = enabled
    }

    override fun isEnabled(): Boolean = nativeWidget.isEnabled

    override fun setOpacity(opacity: Float) {
        opacityVal = opacity.coerceIn(0f, 1f)
    }

    override fun getOpacity(): Float = opacityVal

    override fun setTooltipText(tooltip: String?) {
        nativeWidget.toolTipText = tooltip
    }

    override fun getTooltipText(): String? = nativeWidget.toolTipText

    override fun setPreferredSize(size: Size?) {
        if (size != null) {
            nativeWidget.preferredSize = Dimension(size.width, size.height)
            nativeWidget.setSize(size.width, size.height)
            logicalBounds = logicalBounds.copy(width = size.width, height = size.height)
        } else {
            nativeWidget.preferredSize = null
        }
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
        if (padding != EdgeInsets.ZERO) {
            nativeWidget.border = BorderFactory.createEmptyBorder(padding.top, padding.left, padding.bottom, padding.right)
        } else {
            nativeWidget.border = defaultComponentBorder
        }
    }

    override fun setMargin(margin: EdgeInsets) {
        marginInsets = margin
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        lastComputedStyle = style
        style.backgroundColor?.let { c ->
            val alpha = c.a.coerceIn(0, 255)
            nativeWidget.background = Color(c.r.coerceIn(0, 255), c.g.coerceIn(0, 255), c.b.coerceIn(0, 255), alpha)
            nativeWidget.isOpaque = alpha > 0
        }
        style.textColor?.let { c ->
            val alpha = c.a.coerceIn(0, 255)
            nativeWidget.foreground = Color(c.r.coerceIn(0, 255), c.g.coerceIn(0, 255), c.b.coerceIn(0, 255), alpha)
        }
        style.fontSizePx?.let { sz ->
            val currentFont = nativeWidget.font ?: Font("SansSerif", Font.PLAIN, 14)
            nativeWidget.font = currentFont.deriveFont(sz)
        }
        style.opacity.let { opacityVal = it.coerceIn(0f, 1f) }
        style.customCursor?.let { setCustomCursor(it) }
    }

    override fun setOnClick(listener: (() -> Unit)?) { onClickListener = listener }
    override fun setOnDoubleClick(listener: (() -> Unit)?) { onDoubleClickListener = listener }
    override fun setOnContextMenu(listener: ((PointerEvent) -> Unit)?) { onContextMenuListener = listener }
    override fun setOnPointerDown(listener: ((PointerEvent) -> Unit)?) { onPointerDownListener = listener }
    override fun setOnPointerMove(listener: ((PointerEvent) -> Unit)?) { onPointerMoveListener = listener }
    override fun setOnPointerUp(listener: ((PointerEvent) -> Unit)?) { onPointerUpListener = listener }
    override fun setOnPointerEnter(listener: ((PointerEvent) -> Unit)?) { onPointerEnterListener = listener }
    override fun setOnPointerLeave(listener: ((PointerEvent) -> Unit)?) { onPointerLeaveListener = listener }
    override fun setOnWheel(listener: ((WheelEvent) -> Unit)?) { onWheelListener = listener }
    override fun setOnKeyDown(listener: ((KeyEvent) -> Unit)?) { onKeyDownListener = listener }
    override fun setOnKeyUp(listener: ((KeyEvent) -> Unit)?) { onKeyUpListener = listener }

    fun performContextMenu(x: Float = 0f, y: Float = 0f): Boolean {
        val listener = onContextMenuListener ?: return false
        listener(PointerEvent(x = x, y = y, button = 2))
        return true
    }

    override fun dispose() {
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

    private fun toKeyEvent(e: java.awt.event.KeyEvent): KeyEvent =
        KeyEvent(
            key = java.awt.event.KeyEvent.getKeyText(e.keyCode),
            code = e.keyCode.toString(),
            keyCode = e.keyCode,
            ctrlKey = e.isControlDown,
            shiftKey = e.isShiftDown,
            altKey = e.isAltDown,
            metaKey = e.isMetaDown
        )
}
