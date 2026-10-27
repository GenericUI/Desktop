package com.nexustools.genericui.desktop.core

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.bootstrap.*
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.enums.css.CssUnit
import com.nexustools.genericui.enums.css.CssVisibility
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
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.gradient.Gradient
import com.nexustools.genericui.style.mapper.BootstrapClassMapper
import com.nexustools.genericui.style.mapper.BootstrapStyleSnapshot
import com.nexustools.genericui.style.transition.CssTransitionSpec
import kotlin.math.roundToInt

/**
 * Shared desktop base implementation of [NativeImpl] supporting dual-mode Bootstrap 5 enum styling,
 * strict user CSS class isolation (`customClasses`), [ComponentStyle], [ComponentBootstrap],
 * [CssCustomCursor], geometry, visibility, and input event callbacks across `:windows`, `:macos`,
 * `:gtk`, and `:qt`.
 */
open class DesktopBaseNativeImpl<out W : DesktopNativeWidget>(
    override val nativeWidget: W,
    override val componentClassPrefix: String = ""
) : NativeImpl<W> {

    protected var styleSnapshot: BootstrapStyleSnapshot =
        BootstrapStyleSnapshot(componentPrefix = componentClassPrefix)

    private var cachedClassTokens: Set<String>? = null
    private var cachedClassString: String? = null

    protected var lastComputedStyle: ComputedStyle? = null
    protected var lastComponentStyle: ComponentStyle? = null
    protected var lastBootstrap: ComponentBootstrap? = null
    protected var customCursorState: CssCursorValue? = null
    private var backgroundGradientState: Gradient? = null
    private var backgroundColorState: RgbaColor? = null

    private var hoveredState: Boolean = false
    private var focusedState: Boolean = false
    private var activePressedState: Boolean = false

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
        nativeWidget.attributes["nativeImpl"] = this
        syncStyleToNativeWidget()
    }

    fun hasClickListener(): Boolean = onClickListener != null

    fun dispatchClick() {
        if (nativeWidget.enabled && styleSnapshot.pointerEvents != BootstrapPointerEvents.NONE) {
            onClickListener?.invoke()
        }
    }

    override fun setAttribute(name: String, value: String?) {
        if (value == null) {
            nativeWidget.attributes.remove(name)
        } else {
            nativeWidget.attributes[name] = value
        }
    }

    fun dispatchPointerDown(event: PointerEvent) {
        if (nativeWidget.enabled && styleSnapshot.pointerEvents != BootstrapPointerEvents.NONE) {
            onPointerDownListener?.invoke(event)
        }
    }

    fun dispatchPointerMove(event: PointerEvent) {
        if (nativeWidget.enabled && styleSnapshot.pointerEvents != BootstrapPointerEvents.NONE) {
            onPointerMoveListener?.invoke(event)
        }
    }

    fun dispatchPointerUp(event: PointerEvent) {
        if (nativeWidget.enabled && styleSnapshot.pointerEvents != BootstrapPointerEvents.NONE) {
            onPointerUpListener?.invoke(event)
        }
    }

    fun dispatchPointerEnter(event: PointerEvent = PointerEvent()) {
        setHoveredState(true)
        onPointerEnterListener?.invoke(event)
    }

    fun dispatchPointerLeave(event: PointerEvent = PointerEvent()) {
        setHoveredState(false)
        onPointerLeaveListener?.invoke(event)
    }

    fun dispatchDoubleClick() {
        if (nativeWidget.enabled && styleSnapshot.pointerEvents != BootstrapPointerEvents.NONE) {
            onDoubleClickListener?.invoke()
        }
    }

    fun dispatchContextMenu(event: PointerEvent) {
        if (nativeWidget.enabled && styleSnapshot.pointerEvents != BootstrapPointerEvents.NONE) {
            onContextMenuListener?.invoke(event)
        }
    }

    fun performContextMenu(x: Float = 0f, y: Float = 0f): Boolean {
        val listener = onContextMenuListener ?: return false
        listener(PointerEvent(x = x, y = y, button = 2))
        return true
    }

    fun dispatchWheel(event: WheelEvent) {
        if (nativeWidget.enabled && styleSnapshot.pointerEvents != BootstrapPointerEvents.NONE) {
            onWheelListener?.invoke(event)
        }
    }

    fun dispatchKeyDown(event: KeyEvent) {
        if (nativeWidget.enabled) {
            onKeyDownListener?.invoke(event)
        }
    }

    fun dispatchKeyUp(event: KeyEvent) {
        if (nativeWidget.enabled) {
            onKeyUpListener?.invoke(event)
        }
    }

    override val isDisposed: Boolean
        get() = nativeWidget.disposed

    override fun dispose() {
        nativeWidget.disposed = true
        nativeWidget.clearChildWidgets()
    }

    protected open fun syncStyleToNativeWidget() {
        cachedClassTokens = null
        cachedClassString = null
        val userClassString = styleSnapshot.customClasses.joinToString(" ")
        val enumClassSet = BootstrapClassMapper.toEnumClassSet(styleSnapshot)
        nativeWidget.attributes["bootstrapClass"] = userClassString
        nativeWidget.attributes["enumClasses"] = enumClassSet
        nativeWidget.attributes["styleSnapshot"] = styleSnapshot
        if (styleSnapshot.display == DisplayMode.NONE) {
            nativeWidget.visible = false
        } else if (styleSnapshot.visibility == BootstrapVisibility.INVISIBLE) {
            nativeWidget.attributes["visibilityHidden"] = true
        } else {
            nativeWidget.attributes.remove("visibilityHidden")
        }
    }

    protected open fun onStyleChanged() {
        syncStyleToNativeWidget()
    }

    open fun requestRepaintNative() {
        onStyleChanged()
    }

    /**
     * Returns all class tokens (both enum-mapped classes and custom user classes) for internal layout/painter inspection.
     */
    fun getClassTokensSet(): Set<String> {
        cachedClassTokens?.let { return it }
        val computed = BootstrapClassMapper.toClassTokens(styleSnapshot)
        cachedClassTokens = computed
        return computed
    }

    fun getStyleSnapshot(): BootstrapStyleSnapshot = styleSnapshot

    fun getEnumClasses(): Set<String> = BootstrapClassMapper.toEnumClassSet(styleSnapshot)

    // -------------------------------------------------------------------------
    // User CSS Class Isolation (strictly customClasses only)
    // -------------------------------------------------------------------------

    override fun setClass(className: String) {
        val tokens = className.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }.toSet()
        styleSnapshot = styleSnapshot.copy(customClasses = tokens)
        onStyleChanged()
    }

    override fun getClass(): String =
        styleSnapshot.customClasses.joinToString(" ")

    override fun getClasses(): Set<String> =
        styleSnapshot.customClasses.toSet()

    override fun addClass(className: String) {
        val tokens = className.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return
        val updated = LinkedHashSet(styleSnapshot.customClasses)
        updated.addAll(tokens)
        styleSnapshot = styleSnapshot.copy(customClasses = updated)
        onStyleChanged()
    }

    override fun removeClass(className: String) {
        val tokens = className.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return
        val updated = LinkedHashSet(styleSnapshot.customClasses)
        updated.removeAll(tokens.toSet())
        styleSnapshot = styleSnapshot.copy(customClasses = updated)
        onStyleChanged()
    }

    override fun hasClass(className: String): Boolean {
        val tokens = className.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return false
        return styleSnapshot.customClasses.containsAll(tokens)
    }

    // -------------------------------------------------------------------------
    // ComponentStyle, ComponentBootstrap & Custom Cursor
    // -------------------------------------------------------------------------

    override fun applyComponentStyle(style: ComponentStyle) {
        lastComponentStyle = style
        nativeWidget.attributes["componentStyle"] = style

        backgroundColorState = style.backgroundColor
        if (style.backgroundColor != null) {
            nativeWidget.attributes["backgroundColor"] = style.backgroundColor
        } else {
            nativeWidget.attributes.remove("backgroundColor")
        }

        style.backgroundImage?.gradient?.let {
            backgroundGradientState = it
            nativeWidget.attributes["backgroundGradient"] = it
        }

        if (style.color != null) {
            nativeWidget.attributes["textColor"] = style.color
        } else {
            nativeWidget.attributes.remove("textColor")
        }

        if (style.accentColor != null) {
            nativeWidget.attributes["accentColor"] = style.accentColor
        } else {
            nativeWidget.attributes.remove("accentColor")
        }

        if (style.borderColor != null) {
            nativeWidget.attributes["borderColor"] = style.borderColor
        } else {
            nativeWidget.attributes.remove("borderColor")
        }

        val radiusPx = style.borderRadius?.topLeft?.resolvePx()
        if (radiusPx != null) {
            nativeWidget.attributes["borderRadiusPx"] = radiusPx
        } else {
            nativeWidget.attributes.remove("borderRadiusPx")
        }

        if (style.opacity != null) {
            val op = style.opacity!!.coerceIn(0f, 1f)
            nativeWidget.opacity = op
            nativeWidget.attributes["opacity"] = op
        }

        if (style.zIndex != null) {
            nativeWidget.attributes["zIndex"] = style.zIndex
        } else {
            nativeWidget.attributes.remove("zIndex")
        }

        if (style.cursor != null) {
            setCustomCursor(style.cursor)
        }

        if (style.visibility == CssVisibility.HIDDEN || style.visibility == CssVisibility.COLLAPSE) {
            nativeWidget.attributes["visibilityHidden"] = true
        }
    }

    fun getComponentStyle(): ComponentStyle? = lastComponentStyle

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        lastBootstrap = bootstrap
        styleSnapshot = bootstrap.applyToSnapshot(styleSnapshot)
        onStyleChanged()
    }

    fun getComponentBootstrap(): ComponentBootstrap? = lastBootstrap

    override fun setCustomCursor(cursor: CssCursorValue?) {
        customCursorState = cursor
        when (cursor) {
            null -> {
                nativeWidget.attributes.remove("customCursor")
                nativeWidget.attributes.remove("cursor")
            }
            is CssCustomCursor -> {
                nativeWidget.attributes["customCursor"] = cursor
                nativeWidget.attributes["cursor"] = cursor.fallback.cssValue
            }
            is CssCursor -> {
                nativeWidget.attributes.remove("customCursor")
                nativeWidget.attributes["cursor"] = cursor.cssValue
            }
            else -> {
                nativeWidget.attributes["cursor"] = cursor.toCssString()
            }
        }
    }

    fun getCustomCursor(): CssCursorValue? =
        customCursorState ?: lastComponentStyle?.cursor ?: lastComputedStyle?.customCursor

    // -------------------------------------------------------------------------
    // NativeImpl Bootstrap Enum Methods
    // -------------------------------------------------------------------------

    override fun setType(type: BootstrapType?) {
        styleSnapshot = styleSnapshot.copy(type = type)
        onStyleChanged()
    }

    override fun getType(): BootstrapType? = styleSnapshot.type

    override fun setOutline(outline: Boolean) {
        styleSnapshot = styleSnapshot.copy(outline = outline)
        onStyleChanged()
    }

    override fun isOutline(): Boolean = styleSnapshot.outline

    override fun setSize(size: BootstrapSize?) {
        styleSnapshot = styleSnapshot.copy(size = size)
        onStyleChanged()
    }

    override fun getSize(): BootstrapSize? = styleSnapshot.size

    override fun setBackgroundType(type: BootstrapType?) {
        styleSnapshot = styleSnapshot.copy(backgroundType = type)
        onStyleChanged()
    }

    override fun getBackgroundType(): BootstrapType? = styleSnapshot.backgroundType

    override fun setTextColorType(type: BootstrapType?) {
        styleSnapshot = styleSnapshot.copy(textColorType = type)
        onStyleChanged()
    }

    override fun getTextColorType(): BootstrapType? = styleSnapshot.textColorType

    override fun setBorderType(type: BootstrapType?) {
        styleSnapshot = styleSnapshot.copy(borderType = type)
        onStyleChanged()
    }

    override fun getBorderType(): BootstrapType? = styleSnapshot.borderType

    override fun setTextAlignment(alignment: TextAlignment?) {
        styleSnapshot = styleSnapshot.copy(textAlignment = alignment)
        onStyleChanged()
    }

    override fun getTextAlignment(): TextAlignment? = styleSnapshot.textAlignment

    override fun setFontWeight(weight: FontWeight?) {
        styleSnapshot = styleSnapshot.copy(fontWeight = weight)
        onStyleChanged()
    }

    override fun getFontWeight(): FontWeight? = styleSnapshot.fontWeight

    override fun setTextTransform(transform: TextTransform?) {
        styleSnapshot = styleSnapshot.copy(textTransform = transform)
        onStyleChanged()
    }

    override fun getTextTransform(): TextTransform? = styleSnapshot.textTransform

    override fun setTextOverflow(overflow: TextOverflow?) {
        styleSnapshot = styleSnapshot.copy(textOverflow = overflow)
        onStyleChanged()
    }

    override fun getTextOverflow(): TextOverflow? = styleSnapshot.textOverflow

    override fun setBorderRadius(radius: BorderRadiusStyle?) {
        styleSnapshot = styleSnapshot.copy(borderRadius = radius)
        onStyleChanged()
    }

    override fun getBorderRadius(): BorderRadiusStyle? = styleSnapshot.borderRadius

    override fun setShadow(shadow: ShadowElevation?) {
        styleSnapshot = styleSnapshot.copy(shadow = shadow)
        onStyleChanged()
    }

    override fun getShadow(): ShadowElevation? = styleSnapshot.shadow

    override fun setDisplay(display: DisplayMode?) {
        styleSnapshot = styleSnapshot.copy(display = display)
        onStyleChanged()
    }

    override fun getDisplay(): DisplayMode? = styleSnapshot.display

    override fun setFlexDirection(direction: FlexDirection?) {
        styleSnapshot = styleSnapshot.copy(flexDirection = direction)
        onStyleChanged()
    }

    override fun getFlexDirection(): FlexDirection? = styleSnapshot.flexDirection

    override fun setFlexWrap(wrap: FlexWrap?) {
        styleSnapshot = styleSnapshot.copy(flexWrap = wrap)
        onStyleChanged()
    }

    override fun getFlexWrap(): FlexWrap? = styleSnapshot.flexWrap

    override fun setJustifyContent(justify: JustifyContent?) {
        styleSnapshot = styleSnapshot.copy(justifyContent = justify)
        onStyleChanged()
    }

    override fun getJustifyContent(): JustifyContent? = styleSnapshot.justifyContent

    override fun setAlignItems(align: AlignItems?) {
        styleSnapshot = styleSnapshot.copy(alignItems = align)
        onStyleChanged()
    }

    override fun getAlignItems(): AlignItems? = styleSnapshot.alignItems

    override fun setAlignSelf(align: AlignSelf?) {
        styleSnapshot = styleSnapshot.copy(alignSelf = align)
        onStyleChanged()
    }

    override fun getAlignSelf(): AlignSelf? = styleSnapshot.alignSelf

    override fun setOverflow(overflow: OverflowBehavior?) {
        styleSnapshot = styleSnapshot.copy(overflow = overflow)
        onStyleChanged()
    }

    override fun getOverflow(): OverflowBehavior? = styleSnapshot.overflow

    override fun setPosition(position: PositionMode?) {
        styleSnapshot = styleSnapshot.copy(position = position)
        onStyleChanged()
    }

    override fun getPosition(): PositionMode? = styleSnapshot.position

    override fun setMarginAll(scale: SpacingScale?) {
        styleSnapshot = styleSnapshot.copy(marginAll = scale)
        onStyleChanged()
    }

    override fun getMarginAll(): SpacingScale? = styleSnapshot.marginAll

    override fun setPaddingAll(scale: SpacingScale?) {
        styleSnapshot = styleSnapshot.copy(paddingAll = scale)
        onStyleChanged()
    }

    override fun getPaddingAll(): SpacingScale? = styleSnapshot.paddingAll

    // -------------------------------------------------------------------------
    // Geometry, Visibility & Layout Resolution Helpers
    // -------------------------------------------------------------------------

    override fun setVisible(visible: Boolean) {
        nativeWidget.visible = visible
    }

    override fun isVisible(): Boolean = nativeWidget.visible

    override fun setEnabled(enabled: Boolean) {
        nativeWidget.enabled = enabled
    }

    override fun isEnabled(): Boolean = nativeWidget.enabled

    override fun setOpacity(opacity: Float) {
        nativeWidget.opacity = opacity.coerceIn(0f, 1f)
    }

    override fun getOpacity(): Float = nativeWidget.opacity

    override fun setTooltipText(tooltip: String?) {
        nativeWidget.tooltipText = tooltip
    }

    override fun getTooltipText(): String? = nativeWidget.tooltipText

    override fun setPreferredSize(size: Size?) {
        nativeWidget.preferredSize = size
        if (size != null) {
            nativeWidget.bounds = Rect(nativeWidget.bounds.x, nativeWidget.bounds.y, size.width, size.height)
        }
    }

    override fun getBounds(): Rect = nativeWidget.bounds

    override fun setPadding(padding: EdgeInsets) {
        nativeWidget.padding = padding
    }

    override fun setMargin(margin: EdgeInsets) {
        nativeWidget.margin = margin
    }

    fun getEffectivePaddingInsets(
        referenceWidthPx: Float = nativeWidget.bounds.width.toFloat(),
        referenceHeightPx: Float = nativeWidget.bounds.height.toFloat()
    ): EdgeInsets {
        if (nativeWidget.padding != EdgeInsets.ZERO) return nativeWidget.padding

        val compStyle = lastComponentStyle
        if (compStyle != null) {
            val base = compStyle.padding?.toEdgeInsets(referenceWidthPx, referenceHeightPx) ?: EdgeInsets.ZERO
            val top = compStyle.paddingTop?.resolvePx(referenceHeightPx)?.roundToInt() ?: base.top
            val right = compStyle.paddingRight?.resolvePx(referenceWidthPx)?.roundToInt() ?: base.right
            val bottom = compStyle.paddingBottom?.resolvePx(referenceHeightPx)?.roundToInt() ?: base.bottom
            val left = compStyle.paddingLeft?.resolvePx(referenceWidthPx)?.roundToInt() ?: base.left
            if (top != 0 || right != 0 || bottom != 0 || left != 0) {
                return EdgeInsets(top = top, right = right, bottom = bottom, left = left)
            }
        }

        val allPx = styleSnapshot.paddingAll?.toPixels()?.coerceAtLeast(0) ?: 0
        val xPx = styleSnapshot.paddingX?.toPixels()?.coerceAtLeast(0) ?: allPx
        val yPx = styleSnapshot.paddingY?.toPixels()?.coerceAtLeast(0) ?: allPx
        val topPx = styleSnapshot.paddingTop?.toPixels()?.coerceAtLeast(0) ?: yPx
        val bottomPx = styleSnapshot.paddingBottom?.toPixels()?.coerceAtLeast(0) ?: yPx
        val leftPx = styleSnapshot.paddingStart?.toPixels()?.coerceAtLeast(0) ?: xPx
        val rightPx = styleSnapshot.paddingEnd?.toPixels()?.coerceAtLeast(0) ?: xPx
        return EdgeInsets(top = topPx, right = rightPx, bottom = bottomPx, left = leftPx)
    }

    fun getEffectiveMarginInsets(
        referenceWidthPx: Float = nativeWidget.bounds.width.toFloat(),
        referenceHeightPx: Float = nativeWidget.bounds.height.toFloat()
    ): EdgeInsets {
        if (nativeWidget.margin != EdgeInsets.ZERO) return nativeWidget.margin

        val compStyle = lastComponentStyle
        if (compStyle != null) {
            val base = compStyle.margin?.toEdgeInsets(referenceWidthPx, referenceHeightPx) ?: EdgeInsets.ZERO
            val top = compStyle.marginTop?.resolvePx(referenceHeightPx)?.roundToInt() ?: base.top
            val right = compStyle.marginRight?.resolvePx(referenceWidthPx)?.roundToInt() ?: base.right
            val bottom = compStyle.marginBottom?.resolvePx(referenceHeightPx)?.roundToInt() ?: base.bottom
            val left = compStyle.marginLeft?.resolvePx(referenceWidthPx)?.roundToInt() ?: base.left
            if (top != 0 || right != 0 || bottom != 0 || left != 0) {
                return EdgeInsets(top = top, right = right, bottom = bottom, left = left)
            }
        }

        val allPx = styleSnapshot.marginAll?.toPixels()?.coerceAtLeast(0) ?: 0
        val xPx = styleSnapshot.marginX?.toPixels()?.coerceAtLeast(0) ?: allPx
        val yPx = styleSnapshot.marginY?.toPixels()?.coerceAtLeast(0) ?: allPx
        val topPx = styleSnapshot.marginTop?.toPixels()?.coerceAtLeast(0) ?: yPx
        val bottomPx = styleSnapshot.marginBottom?.toPixels()?.coerceAtLeast(0) ?: yPx
        val leftPx = styleSnapshot.marginStart?.toPixels()?.coerceAtLeast(0) ?: xPx
        val rightPx = styleSnapshot.marginEnd?.toPixels()?.coerceAtLeast(0) ?: xPx
        return EdgeInsets(top = topPx, right = rightPx, bottom = bottomPx, left = leftPx)
    }

    fun isHorizontalMarginAuto(): Pair<Boolean, Boolean> {
        val startScale = styleSnapshot.marginStart ?: styleSnapshot.marginX ?: styleSnapshot.marginAll
        val endScale = styleSnapshot.marginEnd ?: styleSnapshot.marginX ?: styleSnapshot.marginAll
        val leftAuto = startScale == SpacingScale.AUTO ||
            lastComponentStyle?.marginLeft?.isAuto == true ||
            lastComponentStyle?.margin?.left?.isAuto == true
        val rightAuto = endScale == SpacingScale.AUTO ||
            lastComponentStyle?.marginRight?.isAuto == true ||
            lastComponentStyle?.margin?.right?.isAuto == true
        return leftAuto to rightAuto
    }

    fun resolveBootstrapWidthFraction(): Float? {
        styleSnapshot.width?.fraction?.let { return it }
        if ("w-100" in styleSnapshot.customClasses) return 1.0f
        if ("w-75" in styleSnapshot.customClasses) return 0.75f
        if ("w-50" in styleSnapshot.customClasses) return 0.50f
        if ("w-25" in styleSnapshot.customClasses) return 0.25f
        val styleWidth = lastComponentStyle?.width
        if (styleWidth != null && styleWidth.unit == CssUnit.PERCENT) {
            return (styleWidth.value / 100f).coerceIn(0f, 1f)
        }
        return null
    }

    fun resolveBootstrapHeightFraction(): Float? {
        styleSnapshot.height?.fraction?.let { return it }
        if ("h-100" in styleSnapshot.customClasses) return 1.0f
        if ("h-75" in styleSnapshot.customClasses) return 0.75f
        if ("h-50" in styleSnapshot.customClasses) return 0.50f
        if ("h-25" in styleSnapshot.customClasses) return 0.25f
        val styleHeight = lastComponentStyle?.height
        if (styleHeight != null && styleHeight.unit == CssUnit.PERCENT) {
            return (styleHeight.value / 100f).coerceIn(0f, 1f)
        }
        return null
    }

    override fun applyComputedStyle(style: ComputedStyle) {
        lastComputedStyle = style
        style.backgroundColor?.let { nativeWidget.attributes["backgroundColor"] = it }
        style.backgroundGradient?.let {
            backgroundGradientState = it
            nativeWidget.attributes["backgroundGradient"] = it
        }
        style.textColor?.let { nativeWidget.attributes["textColor"] = it }
        style.accentColor?.let { nativeWidget.attributes["accentColor"] = it }
        style.opacity.let { nativeWidget.opacity = it }
        style.transform?.let { nativeWidget.attributes["transform"] = it }
        style.transition?.let { nativeWidget.attributes["transition"] = it }
        style.backgroundImageUrl?.let { nativeWidget.attributes["backgroundImageUrl"] = it }
        style.textShadow?.let { nativeWidget.attributes["textShadow"] = it }
        style.boxShadow?.let { nativeWidget.attributes["boxShadow"] = it }
        style.zIndex?.let { nativeWidget.attributes["zIndex"] = it }
        style.left?.let { nativeWidget.attributes["left"] = it }
        style.top?.let { nativeWidget.attributes["top"] = it }
        style.customCursor?.let { setCustomCursor(it) }
            ?: style.cursor?.let { nativeWidget.attributes["cursor"] = it }
        val rawPadLeft = style.rawDeclarations["padding-left"]?.removeSuffix("px")?.trim()?.toIntOrNull()
        val rawPadTop = style.rawDeclarations["padding-top"]?.removeSuffix("px")?.trim()?.toIntOrNull()
        val rawPadRight = style.rawDeclarations["padding-right"]?.removeSuffix("px")?.trim()?.toIntOrNull()
        val rawPadBottom = style.rawDeclarations["padding-bottom"]?.removeSuffix("px")?.trim()?.toIntOrNull()
        if (rawPadLeft != null || rawPadTop != null || rawPadRight != null || rawPadBottom != null) {
            nativeWidget.padding = EdgeInsets(
                top = rawPadTop ?: nativeWidget.padding.top,
                right = rawPadRight ?: nativeWidget.padding.right,
                bottom = rawPadBottom ?: nativeWidget.padding.bottom,
                left = rawPadLeft ?: nativeWidget.padding.left
            )
        }
        for ((k, v) in style.rawDeclarations) {
            nativeWidget.styleProperties[k] = v
        }
        nativeWidget.attributes["computedStyle"] = style
    }

    override fun setBackgroundGradient(gradient: Gradient?) {
        backgroundGradientState = gradient
        nativeWidget.attributes["backgroundGradient"] = gradient
    }

    override fun getBackgroundGradient(): Gradient? =
        backgroundGradientState
            ?: lastComponentStyle?.backgroundImage?.gradient
            ?: lastComputedStyle?.backgroundGradient

    override fun setHoveredState(hovered: Boolean) {
        hoveredState = hovered
        nativeWidget.attributes["hovered"] = hovered
    }

    override fun isHoveredState(): Boolean = hoveredState

    override fun setFocusedState(focused: Boolean) {
        focusedState = focused
        nativeWidget.attributes["focused"] = focused
    }

    override fun isFocusedState(): Boolean = focusedState

    override fun setActivePressedState(active: Boolean) {
        activePressedState = active
        nativeWidget.attributes["activePressed"] = active
    }

    override fun isActivePressedState(): Boolean = activePressedState

    fun getComputedStyle(): ComputedStyle? = lastComputedStyle

    fun resolveEffectiveStyle(): ComputedStyle? =
        lastComputedStyle?.resolveForState(
            hovered = hoveredState,
            focused = focusedState,
            active = activePressedState,
            enabled = nativeWidget.enabled
        )

    fun resolveSemanticBootstrapColor(darkTheme: Boolean = false): RgbaColor? {
        val semanticType = styleSnapshot.type?.takeIf { it.isSemantic }
            ?: styleSnapshot.backgroundType?.takeIf { it.isSemantic }
            ?: return null
        return BootstrapColorPalette.resolveColor(semanticType, darkTheme)
    }

    fun resolveSemanticBootstrapForeground(darkTheme: Boolean = false): RgbaColor? {
        styleSnapshot.textColorType?.takeIf { it.isSemantic }?.let {
            return BootstrapColorPalette.resolveColor(it, darkTheme)
        }
        val semanticType = styleSnapshot.type?.takeIf { it.isSemantic } ?: return null
        return if (styleSnapshot.outline) {
            BootstrapColorPalette.resolveColor(semanticType, darkTheme)
        } else {
            BootstrapColorPalette.resolveForegroundOn(semanticType)
        }
    }

    fun getEffectiveAccentColor(darkTheme: Boolean = false): RgbaColor? =
        lastComponentStyle?.accentColor
            ?: resolveEffectiveStyle()?.accentColor
            ?: getEffectiveBackgroundColor()
            ?: resolveSemanticBootstrapColor(darkTheme)

    fun getEffectiveBackgroundColor(): RgbaColor? =
        backgroundColorState
            ?: lastComponentStyle?.backgroundColor
            ?: resolveEffectiveStyle()?.backgroundColor

    fun getEffectiveTextColor(): RgbaColor? =
        lastComponentStyle?.color
            ?: resolveEffectiveStyle()?.textColor
            ?: lastComputedStyle?.textColor

    fun getEffectiveBackgroundGradient(): Gradient? =
        resolveEffectiveStyle()?.backgroundGradient
            ?: lastComponentStyle?.backgroundImage?.gradient
            ?: backgroundGradientState

    override fun supportsNativeTransitions(): Boolean = true

    override fun configureNativeTransitions(specs: List<CssTransitionSpec>) {
        nativeWidget.attributes["nativeTransitionSpecs"] = specs
    }

    override fun runNativeTransition(
        property: String,
        fromValue: String?,
        toValue: String,
        spec: CssTransitionSpec,
        onComplete: () -> Unit
    ): Boolean {
        nativeWidget.attributes["activeNativeTransition:$property"] = "$fromValue->$toValue@${spec.durationMs}ms"
        return true
    }

    override fun setOnClick(listener: (() -> Unit)?) {
        onClickListener = listener
    }

    override fun setOnDoubleClick(listener: (() -> Unit)?) {
        onDoubleClickListener = listener
    }

    override fun setOnContextMenu(listener: ((PointerEvent) -> Unit)?) {
        onContextMenuListener = listener
    }

    override fun setOnPointerDown(listener: ((PointerEvent) -> Unit)?) {
        onPointerDownListener = listener
    }

    override fun setOnPointerMove(listener: ((PointerEvent) -> Unit)?) {
        onPointerMoveListener = listener
    }

    override fun setOnPointerUp(listener: ((PointerEvent) -> Unit)?) {
        onPointerUpListener = listener
    }

    override fun setOnPointerEnter(listener: ((PointerEvent) -> Unit)?) {
        onPointerEnterListener = listener
    }

    override fun setOnPointerLeave(listener: ((PointerEvent) -> Unit)?) {
        onPointerLeaveListener = listener
    }

    override fun setOnWheel(listener: ((WheelEvent) -> Unit)?) {
        onWheelListener = listener
    }

    override fun setOnKeyDown(listener: ((KeyEvent) -> Unit)?) {
        onKeyDownListener = listener
    }

    override fun setOnKeyUp(listener: ((KeyEvent) -> Unit)?) {
        onKeyUpListener = listener
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
