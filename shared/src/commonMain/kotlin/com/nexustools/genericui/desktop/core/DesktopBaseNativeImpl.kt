package com.nexustools.genericui.desktop.core

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.enums.bootstrap.*
import com.nexustools.genericui.event.KeyEvent
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.event.WheelEvent
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.gradient.Gradient
import com.nexustools.genericui.style.mapper.BootstrapClassMapper
import com.nexustools.genericui.style.mapper.BootstrapStyleSnapshot
import com.nexustools.genericui.style.transition.CssTransitionSpec

/**
 * Shared desktop base implementation of [NativeImpl] supporting dual-mode Bootstrap 5 enum styling,
 * bidirectional [BootstrapClassMapper] synchronization, geometry, visibility, and input event callbacks
 * across `:windows`, `:macos`, `:gtk`, and `:qt`.
 */
open class DesktopBaseNativeImpl<out W : DesktopNativeWidget>(
    override val nativeWidget: W,
    override val componentClassPrefix: String = ""
) : NativeImpl<W> {

    private var typeState: BootstrapType? = null
    private var outlineState: Boolean = false
    private var sizeState: BootstrapSize? = null
    private var backgroundTypeState: BootstrapType? = null
    private var textColorTypeState: BootstrapType? = null
    private var borderTypeState: BootstrapType? = null
    private var textAlignmentState: TextAlignment? = null
    private var fontWeightState: FontWeight? = null
    private var textTransformState: TextTransform? = null
    private var textOverflowState: TextOverflow? = null
    private var borderRadiusState: BorderRadiusStyle? = null
    private var shadowState: ShadowElevation? = null
    private var displayState: DisplayMode? = null
    private var flexDirectionState: FlexDirection? = null
    private var flexWrapState: FlexWrap? = null
    private var justifyContentState: JustifyContent? = null
    private var alignItemsState: AlignItems? = null
    private var alignSelfState: AlignSelf? = null
    private var overflowState: OverflowBehavior? = null
    private var positionState: PositionMode? = null
    private var marginAllState: SpacingScale? = null
    private var paddingAllState: SpacingScale? = null
    private val customClasses: MutableSet<String> = linkedSetOf()

    protected var lastComputedStyle: ComputedStyle? = null
    private var tintState: RgbaColor? = null
    private var backgroundGradientState: Gradient? = null
    private var hoveredState: Boolean = false
    private var focusedState: Boolean = false
    private var activePressedState: Boolean = false
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
        nativeWidget.attributes["nativeImpl"] = this
    }

    fun hasClickListener(): Boolean = onClickListener != null

    fun dispatchClick() {
        onClickListener?.invoke()
    }

    override fun setAttribute(name: String, value: String?) {
        if (value == null) {
            nativeWidget.attributes.remove(name)
        } else {
            nativeWidget.attributes[name] = value
        }
    }

    fun dispatchPointerDown(event: PointerEvent) {
        onPointerDownListener?.invoke(event)
    }

    fun dispatchPointerMove(event: PointerEvent) {
        onPointerMoveListener?.invoke(event)
    }

    fun dispatchPointerUp(event: PointerEvent) {
        onPointerUpListener?.invoke(event)
    }

    override val isDisposed: Boolean
        get() = nativeWidget.disposed

    override fun dispose() {
        nativeWidget.disposed = true
        nativeWidget.clearChildWidgets()
    }

    protected open fun onStyleChanged() {
        nativeWidget.attributes["bootstrapClass"] = getClass()
    }

    open fun requestRepaintNative() {
        onStyleChanged()
    }

    private fun currentSnapshot(): BootstrapStyleSnapshot = BootstrapStyleSnapshot(
        componentPrefix = componentClassPrefix,
        type = typeState,
        outline = outlineState,
        size = sizeState,
        backgroundType = backgroundTypeState,
        textColorType = textColorTypeState,
        borderType = borderTypeState,
        textAlignment = textAlignmentState,
        fontWeight = fontWeightState,
        textTransform = textTransformState,
        textOverflow = textOverflowState,
        borderRadius = borderRadiusState,
        shadow = shadowState,
        display = displayState,
        flexDirection = flexDirectionState,
        flexWrap = flexWrapState,
        justifyContent = justifyContentState,
        alignItems = alignItemsState,
        alignSelf = alignSelfState,
        overflow = overflowState,
        position = positionState,
        marginAll = marginAllState,
        paddingAll = paddingAllState,
        customClasses = customClasses.toSet()
    )

    private fun applySnapshot(snapshot: BootstrapStyleSnapshot) {
        typeState = snapshot.type
        outlineState = snapshot.outline
        sizeState = snapshot.size
        backgroundTypeState = snapshot.backgroundType
        textColorTypeState = snapshot.textColorType
        borderTypeState = snapshot.borderType
        textAlignmentState = snapshot.textAlignment
        fontWeightState = snapshot.fontWeight
        textTransformState = snapshot.textTransform
        textOverflowState = snapshot.textOverflow
        borderRadiusState = snapshot.borderRadius
        shadowState = snapshot.shadow
        displayState = snapshot.display
        flexDirectionState = snapshot.flexDirection
        flexWrapState = snapshot.flexWrap
        justifyContentState = snapshot.justifyContent
        alignItemsState = snapshot.alignItems
        alignSelfState = snapshot.alignSelf
        overflowState = snapshot.overflow
        positionState = snapshot.position
        marginAllState = snapshot.marginAll
        paddingAllState = snapshot.paddingAll
        customClasses.clear()
        customClasses.addAll(snapshot.customClasses)
        onStyleChanged()
    }

    override fun setClass(className: String) {
        val parsed = BootstrapClassMapper.fromClassString(className, componentClassPrefix)
        applySnapshot(parsed)
    }

    override fun getClass(): String =
        BootstrapClassMapper.toClassString(currentSnapshot())

    override fun addClass(className: String) {
        val merged = (getClass() + " " + className).trim()
        setClass(merged)
    }

    override fun removeClass(className: String) {
        val toRemove = className.trim().split(Regex("\\s+")).toSet()
        val remaining = BootstrapClassMapper.toClassTokens(currentSnapshot())
            .filterNot { it in toRemove }
            .joinToString(" ")
        setClass(remaining)
    }

    override fun hasClass(className: String): Boolean {
        val tokens = BootstrapClassMapper.toClassTokens(currentSnapshot())
        return className.trim().split(Regex("\\s+")).all { it in tokens }
    }

    override fun setType(type: BootstrapType?) {
        typeState = type
        onStyleChanged()
    }

    override fun getType(): BootstrapType? = typeState

    override fun setOutline(outline: Boolean) {
        outlineState = outline
        onStyleChanged()
    }

    override fun isOutline(): Boolean = outlineState

    override fun setSize(size: BootstrapSize?) {
        sizeState = size
        onStyleChanged()
    }

    override fun getSize(): BootstrapSize? = sizeState

    override fun setBackgroundType(type: BootstrapType?) {
        backgroundTypeState = type
        onStyleChanged()
    }

    override fun getBackgroundType(): BootstrapType? = backgroundTypeState

    override fun setTextColorType(type: BootstrapType?) {
        textColorTypeState = type
        onStyleChanged()
    }

    override fun getTextColorType(): BootstrapType? = textColorTypeState

    override fun setBorderType(type: BootstrapType?) {
        borderTypeState = type
        onStyleChanged()
    }

    override fun getBorderType(): BootstrapType? = borderTypeState

    override fun setTextAlignment(alignment: TextAlignment?) {
        textAlignmentState = alignment
        onStyleChanged()
    }

    override fun getTextAlignment(): TextAlignment? = textAlignmentState

    override fun setFontWeight(weight: FontWeight?) {
        fontWeightState = weight
        onStyleChanged()
    }

    override fun getFontWeight(): FontWeight? = fontWeightState

    override fun setTextTransform(transform: TextTransform?) {
        textTransformState = transform
        onStyleChanged()
    }

    override fun getTextTransform(): TextTransform? = textTransformState

    override fun setTextOverflow(overflow: TextOverflow?) {
        textOverflowState = overflow
        onStyleChanged()
    }

    override fun getTextOverflow(): TextOverflow? = textOverflowState

    override fun setBorderRadius(radius: BorderRadiusStyle?) {
        borderRadiusState = radius
        onStyleChanged()
    }

    override fun getBorderRadius(): BorderRadiusStyle? = borderRadiusState

    override fun setShadow(shadow: ShadowElevation?) {
        shadowState = shadow
        onStyleChanged()
    }

    override fun getShadow(): ShadowElevation? = shadowState

    override fun setDisplay(display: DisplayMode?) {
        displayState = display
        onStyleChanged()
    }

    override fun getDisplay(): DisplayMode? = displayState

    override fun setFlexDirection(direction: FlexDirection?) {
        flexDirectionState = direction
        onStyleChanged()
    }

    override fun getFlexDirection(): FlexDirection? = flexDirectionState

    override fun setFlexWrap(wrap: FlexWrap?) {
        flexWrapState = wrap
        onStyleChanged()
    }

    override fun getFlexWrap(): FlexWrap? = flexWrapState

    override fun setJustifyContent(justify: JustifyContent?) {
        justifyContentState = justify
        onStyleChanged()
    }

    override fun getJustifyContent(): JustifyContent? = justifyContentState

    override fun setAlignItems(align: AlignItems?) {
        alignItemsState = align
        onStyleChanged()
    }

    override fun getAlignItems(): AlignItems? = alignItemsState

    override fun setAlignSelf(align: AlignSelf?) {
        alignSelfState = align
        onStyleChanged()
    }

    override fun getAlignSelf(): AlignSelf? = alignSelfState

    override fun setOverflow(overflow: OverflowBehavior?) {
        overflowState = overflow
        onStyleChanged()
    }

    override fun getOverflow(): OverflowBehavior? = overflowState

    override fun setPosition(position: PositionMode?) {
        positionState = position
        onStyleChanged()
    }

    override fun getPosition(): PositionMode? = positionState

    override fun setMarginAll(scale: SpacingScale?) {
        marginAllState = scale
        onStyleChanged()
    }

    override fun getMarginAll(): SpacingScale? = marginAllState

    override fun setPaddingAll(scale: SpacingScale?) {
        paddingAllState = scale
        onStyleChanged()
    }

    override fun getPaddingAll(): SpacingScale? = paddingAllState

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

    override fun applyComputedStyle(style: ComputedStyle) {
        lastComputedStyle = style
        style.tint?.let {
            tintState = it
            nativeWidget.attributes["tint"] = it
        }
        style.backgroundGradient?.let {
            backgroundGradientState = it
            nativeWidget.attributes["backgroundGradient"] = it
        }
        style.opacity.let { nativeWidget.opacity = it }
        style.transform?.let { nativeWidget.attributes["transform"] = it }
        style.transition?.let { nativeWidget.attributes["transition"] = it }
        style.backgroundImageUrl?.let { nativeWidget.attributes["backgroundImageUrl"] = it }
        style.textShadow?.let { nativeWidget.attributes["textShadow"] = it }
        style.boxShadow?.let { nativeWidget.attributes["boxShadow"] = it }
        style.zIndex?.let { nativeWidget.attributes["zIndex"] = it }
        style.left?.let { nativeWidget.attributes["left"] = it }
        style.top?.let { nativeWidget.attributes["top"] = it }
        style.cursor?.let { nativeWidget.attributes["cursor"] = it }
        nativeWidget.attributes["computedStyle"] = style
    }

    override fun setTint(tint: RgbaColor?) {
        tintState = tint
        nativeWidget.attributes["tint"] = tint
    }

    override fun getTint(): RgbaColor? = tintState ?: lastComputedStyle?.tint

    override fun setBackgroundGradient(gradient: Gradient?) {
        backgroundGradientState = gradient
        nativeWidget.attributes["backgroundGradient"] = gradient
    }

    override fun getBackgroundGradient(): Gradient? =
        backgroundGradientState ?: lastComputedStyle?.backgroundGradient

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

    fun resolveEffectiveStyle(): ComputedStyle? =
        lastComputedStyle?.resolveForState(
            hovered = hoveredState,
            focused = focusedState,
            active = activePressedState,
            enabled = nativeWidget.enabled
        )

    fun getEffectiveTintColor(): RgbaColor? =
        resolveEffectiveStyle()?.tint ?: tintState

    fun getEffectiveBackgroundColor(): RgbaColor? =
        resolveEffectiveStyle()?.backgroundColor

    fun getEffectiveBackgroundGradient(): Gradient? =
        resolveEffectiveStyle()?.backgroundGradient ?: backgroundGradientState

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

    override fun setOnWheel(listener: ((WheelEvent) -> Unit)?) {
        onWheelListener = listener
    }

    override fun setOnKeyDown(listener: ((KeyEvent) -> Unit)?) {
        onKeyDownListener = listener
    }

    override fun setOnKeyUp(listener: ((KeyEvent) -> Unit)?) {
        onKeyUpListener = listener
    }
}
