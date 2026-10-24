package com.nexustools.genericui.swing

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.bootstrap.*
import com.nexustools.genericui.event.KeyEvent
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.event.WheelEvent
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.mapper.BootstrapClassMapper
import com.nexustools.genericui.style.mapper.BootstrapStyleSnapshot
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
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

    protected var onClickListener: (() -> Unit)? = null
    protected var onDoubleClickListener: (() -> Unit)? = null
    protected var onContextMenuListener: ((PointerEvent) -> Unit)? = null
    protected var onPointerDownListener: ((PointerEvent) -> Unit)? = null
    protected var onPointerMoveListener: ((PointerEvent) -> Unit)? = null
    protected var onPointerUpListener: ((PointerEvent) -> Unit)? = null
    protected var onWheelListener: ((WheelEvent) -> Unit)? = null
    protected var onKeyDownListener: ((KeyEvent) -> Unit)? = null
    protected var onKeyUpListener: ((KeyEvent) -> Unit)? = null

    init {
        val mouseAdapter = object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2) {
                    onDoubleClickListener?.invoke()
                } else if (e.button == MouseEvent.BUTTON1) {
                    onClickListener?.invoke()
                }
            }

            override fun mousePressed(e: MouseEvent) {
                val pe = toPointerEvent(e)
                onPointerDownListener?.invoke(pe)
                if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) {
                    onContextMenuListener?.invoke(pe)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                val pe = toPointerEvent(e)
                onPointerUpListener?.invoke(pe)
                if (e.isPopupTrigger) {
                    onContextMenuListener?.invoke(pe)
                }
            }

            override fun mouseMoved(e: MouseEvent) {
                onPointerMoveListener?.invoke(toPointerEvent(e))
            }

            override fun mouseDragged(e: MouseEvent) {
                onPointerMoveListener?.invoke(toPointerEvent(e))
            }
        }
        nativeWidget.addMouseListener(mouseAdapter)
        nativeWidget.addMouseMotionListener(mouseAdapter)
        nativeWidget.addMouseWheelListener { e ->
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
        nativeWidget.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                onKeyDownListener?.invoke(toKeyEvent(e))
            }

            override fun keyReleased(e: java.awt.event.KeyEvent) {
                onKeyUpListener?.invoke(toKeyEvent(e))
            }
        })
    }

    override fun setClass(className: String) {
        styleSnapshot = BootstrapClassMapper.fromClassString(className, componentClassPrefix)
    }

    override fun getClass(): String =
        BootstrapClassMapper.toClassString(styleSnapshot)

    override fun addClass(className: String) {
        val combined = (getClass() + " " + className).trim()
        styleSnapshot = BootstrapClassMapper.fromClassString(combined, componentClassPrefix)
    }

    override fun removeClass(className: String) {
        val remaining = getClass().split(Regex("\\s+")).filter { it.isNotEmpty() && it != className }
        styleSnapshot = BootstrapClassMapper.fromClassString(remaining.joinToString(" "), componentClassPrefix)
    }

    override fun hasClass(className: String): Boolean =
        BootstrapClassMapper.toClassTokens(styleSnapshot).contains(className.trim())

    override fun setType(type: BootstrapType?) { styleSnapshot = styleSnapshot.copy(type = type) }
    override fun getType(): BootstrapType? = styleSnapshot.type

    override fun setOutline(outline: Boolean) { styleSnapshot = styleSnapshot.copy(outline = outline) }
    override fun isOutline(): Boolean = styleSnapshot.outline

    override fun setSize(size: BootstrapSize?) { styleSnapshot = styleSnapshot.copy(size = size) }
    override fun getSize(): BootstrapSize? = styleSnapshot.size

    override fun setBackgroundType(type: BootstrapType?) { styleSnapshot = styleSnapshot.copy(backgroundType = type) }
    override fun getBackgroundType(): BootstrapType? = styleSnapshot.backgroundType

    override fun setTextColorType(type: BootstrapType?) { styleSnapshot = styleSnapshot.copy(textColorType = type) }
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

    override fun setDisplay(display: DisplayMode?) { styleSnapshot = styleSnapshot.copy(display = display) }
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
        nativeWidget.border = BorderFactory.createEmptyBorder(padding.top, padding.left, padding.bottom, padding.right)
    }

    override fun setMargin(margin: EdgeInsets) {
        marginInsets = margin
    }

    override fun applyComputedStyle(style: ComputedStyle) {
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
        style.opacity?.let { opacityVal = it.coerceIn(0f, 1f) }
    }

    override fun setOnClick(listener: (() -> Unit)?) { onClickListener = listener }
    override fun setOnDoubleClick(listener: (() -> Unit)?) { onDoubleClickListener = listener }
    override fun setOnContextMenu(listener: ((PointerEvent) -> Unit)?) { onContextMenuListener = listener }
    override fun setOnPointerDown(listener: ((PointerEvent) -> Unit)?) { onPointerDownListener = listener }
    override fun setOnPointerMove(listener: ((PointerEvent) -> Unit)?) { onPointerMoveListener = listener }
    override fun setOnPointerUp(listener: ((PointerEvent) -> Unit)?) { onPointerUpListener = listener }
    override fun setOnWheel(listener: ((WheelEvent) -> Unit)?) { onWheelListener = listener }
    override fun setOnKeyDown(listener: ((KeyEvent) -> Unit)?) { onKeyDownListener = listener }
    override fun setOnKeyUp(listener: ((KeyEvent) -> Unit)?) { onKeyUpListener = listener }

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
