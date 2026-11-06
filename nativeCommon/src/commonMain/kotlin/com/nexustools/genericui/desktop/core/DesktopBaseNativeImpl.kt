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
import com.nexustools.genericui.style.gradient.Gradient.Gradient
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
    private var explicitPadding: EdgeInsets? = null
    private var explicitMargin: EdgeInsets? = null

    var antialiasing: Boolean = true
        set(value) {
            field = value
            nativeWidget.attributes["antialiasing"] = value
        }

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

    private var batchAttachDepth: Int = 0
    private var batchStyleDirty: Boolean = false

    init {
        nativeWidget.attributes["nativeImpl"] = this
        nativeWidget.attributes["antialiasing"] = antialiasing
        syncStyleToNativeWidget()
    }

    override fun beginBatchAttach() {
        batchAttachDepth++
    }

    override fun endBatchAttach() {
        if (batchAttachDepth > 0) {
            batchAttachDepth--
            if (batchAttachDepth == 0 && batchStyleDirty) {
                batchStyleDirty = false
                syncStyleToNativeWidget()
                nativeWidget.notifyTreeInvalidated()
            }
        }
    }

    protected fun notifyTreeInvalidated() {
        if (batchAttachDepth > 0) {
            batchStyleDirty = true
            return
        }
        nativeWidget.notifyTreeInvalidated()
    }

    fun hasClickListener(): Boolean = onClickListener != null

    fun dispatchClick() {
        if (nativeWidget.enabled && styleSnapshot.pointerEvents != BootstrapPointerEvents.NONE) {
            if (DesktopNativeWidget.interceptPopupClick(Float.NaN, Float.NaN, nativeWidget)) return
            onClickListener?.invoke()
        }
    }

    override fun setAttribute(name: String, value: String?) {
        if (name.equals("backdrop-filter", ignoreCase = true) || name.equals("-webkit-backdrop-filter", ignoreCase = true)) {
            return
        }
        if (value == null) {
            nativeWidget.attributes.remove(name)
        } else {
            nativeWidget.attributes[name] = value
        }
        if (name == "class" || name == "bootstrapClass") {
            if (batchAttachDepth > 0) {
                batchStyleDirty = true
            } else {
                nativeWidget.rebuildClassTokensCache(componentClassPrefix)
                syncBootstrapClassesAndStyles()
            }
        }
    }

    fun dispatchPointerDown(event: PointerEvent) {
        if (nativeWidget.enabled && styleSnapshot.pointerEvents != BootstrapPointerEvents.NONE) {
            if (DesktopNativeWidget.interceptPopupPointerDown(event.x, event.y, nativeWidget)) return
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
            if (DesktopNativeWidget.interceptPopupPointerUp(event.x, event.y, nativeWidget)) return
            onPointerUpListener?.invoke(event)
        }
    }

    fun dispatchPointerEnter(event: PointerEvent = PointerEvent()) {
        if (!nativeWidget.enabled || styleSnapshot.pointerEvents == BootstrapPointerEvents.NONE) return
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
        nativeWidget.rebuildClassTokensCache(componentClassPrefix)
        syncBootstrapClassesAndStyles()
    }

    protected open fun syncBootstrapClassesAndStyles() {
        val tokens = nativeWidget.cachedAllClassTokens

        fun spacingTokenPx(prefix: String): Int? {
            for (scale in 0..5) {
                if ("$prefix-$scale" in tokens) {
                    return when (scale) {
                        0 -> 0
                        1 -> 4
                        2 -> 8
                        3 -> 16
                        4 -> 24
                        5 -> 48
                        else -> 0
                    }
                }
            }
            return null
        }

        // Sync padding from explicit, StyledVisualSpec, ComponentStyle, or Bootstrap snapshot/tokens
        val styledPad = (nativeWidget.attributes["styledButtonVisuals"] as? com.nexustools.genericui.style.css.StyledVisualSpec)?.padding
            ?: (nativeWidget.attributes["styledContainerVisuals"] as? com.nexustools.genericui.style.css.StyledVisualSpec)?.padding
        val resolvedPad = explicitPadding ?: styledPad ?: run {
            val pAll = styleSnapshot.paddingAll?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx("p")
            val pX = styleSnapshot.paddingX?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx("px") ?: pAll
            val pY = styleSnapshot.paddingY?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx("py") ?: pAll
            val pTop = styleSnapshot.paddingTop?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx("pt") ?: pY ?: 0
            val pBottom = styleSnapshot.paddingBottom?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx("pb") ?: pY ?: 0
            val pLeft = styleSnapshot.paddingStart?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx("ps") ?: pX ?: 0
            val pRight = styleSnapshot.paddingEnd?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx("pe") ?: pX ?: 0
            val comp = lastComponentStyle
            if (comp != null) {
                val refW = nativeWidget.bounds.width.toFloat()
                val refH = nativeWidget.bounds.height.toFloat()
                val base = comp.padding?.toEdgeInsets(refW, refH)
                EdgeInsets(
                    top = comp.paddingTop?.resolvePx(refH)?.roundToInt() ?: base?.top ?: pTop,
                    right = comp.paddingRight?.resolvePx(refW)?.roundToInt() ?: base?.right ?: pRight,
                    bottom = comp.paddingBottom?.resolvePx(refH)?.roundToInt() ?: base?.bottom ?: pBottom,
                    left = comp.paddingLeft?.resolvePx(refW)?.roundToInt() ?: base?.left ?: pLeft
                )
            } else {
                EdgeInsets(top = pTop, right = pRight, bottom = pBottom, left = pLeft)
            }
        }
        if (explicitPadding != null || styledPad != null || resolvedPad != EdgeInsets.ZERO) {
            nativeWidget.padding = resolvedPad
        }

        // Sync margin from explicit, ComponentStyle, or Bootstrap snapshot/tokens
        val resolvedMargin = explicitMargin ?: run {
            val mAll = styleSnapshot.marginAll?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx("m")
            val mX = styleSnapshot.marginX?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx("mx") ?: mAll
            val mY = styleSnapshot.marginY?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx("my") ?: mAll
            val mTop = styleSnapshot.marginTop?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx("mt") ?: mY ?: 0
            val mBottom = styleSnapshot.marginBottom?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx("mb") ?: mY ?: 0
            val mLeft = styleSnapshot.marginStart?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx("ms") ?: mX ?: 0
            val mRight = styleSnapshot.marginEnd?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx("me") ?: mX ?: 0
            val comp = lastComponentStyle
            if (comp != null) {
                val refW = nativeWidget.bounds.width.toFloat()
                val refH = nativeWidget.bounds.height.toFloat()
                val base = comp.margin?.toEdgeInsets(refW, refH)
                EdgeInsets(
                    top = comp.marginTop?.resolvePx(refH)?.roundToInt() ?: base?.top ?: mTop,
                    right = comp.marginRight?.resolvePx(refW)?.roundToInt() ?: base?.right ?: mRight,
                    bottom = comp.marginBottom?.resolvePx(refH)?.roundToInt() ?: base?.bottom ?: mBottom,
                    left = comp.marginLeft?.resolvePx(refW)?.roundToInt() ?: base?.left ?: mLeft
                )
            } else {
                EdgeInsets(top = mTop, right = mRight, bottom = mBottom, left = mLeft)
            }
        }
        if (explicitMargin != null || resolvedMargin != EdgeInsets.ZERO) {
            nativeWidget.margin = resolvedMargin
        }

        // Sync gap / row-gap / column-gap
        val gapPx = lastComponentStyle?.gap?.resolvePx()?.roundToInt()
            ?: styleSnapshot.gap?.toPixels()?.coerceAtLeast(0)
            ?: spacingTokenPx("gap")
            ?: spacingTokenPx("g")
        if (gapPx != null) {
            nativeWidget.styleProperties["gap"] = "${gapPx}px"
            nativeWidget.attributes["gapPx"] = gapPx
        }
        val rowGapPx = lastComponentStyle?.rowGap?.resolvePx()?.roundToInt()
            ?: styleSnapshot.rowGap?.toPixels()?.coerceAtLeast(0)
            ?: spacingTokenPx("row-gap")
            ?: spacingTokenPx("gy")
        if (rowGapPx != null) {
            nativeWidget.styleProperties["row-gap"] = "${rowGapPx}px"
            nativeWidget.attributes["rowGapPx"] = rowGapPx
        }
        val colGapPx = lastComponentStyle?.columnGap?.resolvePx()?.roundToInt()
            ?: styleSnapshot.columnGap?.toPixels()?.coerceAtLeast(0)
            ?: spacingTokenPx("column-gap")
            ?: spacingTokenPx("gx")
        if (colGapPx != null) {
            nativeWidget.styleProperties["column-gap"] = "${colGapPx}px"
            nativeWidget.attributes["columnGapPx"] = colGapPx
        }

        // Sync display / flex properties
        when {
            styleSnapshot.display == DisplayMode.NONE || "d-none" in tokens -> {
                nativeWidget.styleProperties["display"] = "none"
                nativeWidget.visible = false
            }
            styleSnapshot.display == DisplayMode.FLEX || "d-flex" in tokens -> {
                nativeWidget.styleProperties["display"] = "flex"
            }
            styleSnapshot.display == DisplayMode.INLINE_FLEX || "d-inline-flex" in tokens -> {
                nativeWidget.styleProperties["display"] = "inline-flex"
            }
        }
        when {
            styleSnapshot.flexDirection == FlexDirection.COLUMN || "flex-column" in tokens ->
                nativeWidget.styleProperties["flex-direction"] = "column"
            styleSnapshot.flexDirection == FlexDirection.ROW || "flex-row" in tokens ->
                nativeWidget.styleProperties["flex-direction"] = "row"
            styleSnapshot.flexDirection == FlexDirection.COLUMN_REVERSE || "flex-column-reverse" in tokens ->
                nativeWidget.styleProperties["flex-direction"] = "column-reverse"
            styleSnapshot.flexDirection == FlexDirection.ROW_REVERSE || "flex-row-reverse" in tokens ->
                nativeWidget.styleProperties["flex-direction"] = "row-reverse"
        }
        when {
            styleSnapshot.flexWrap == FlexWrap.WRAP || "flex-wrap" in tokens ->
                nativeWidget.styleProperties["flex-wrap"] = "wrap"
            styleSnapshot.flexWrap == FlexWrap.NOWRAP || "flex-nowrap" in tokens ->
                nativeWidget.styleProperties["flex-wrap"] = "nowrap"
        }
        when {
            styleSnapshot.justifyContent == JustifyContent.BETWEEN || "justify-content-between" in tokens ->
                nativeWidget.styleProperties["justify-content"] = "space-between"
            styleSnapshot.justifyContent == JustifyContent.CENTER || "justify-content-center" in tokens ->
                nativeWidget.styleProperties["justify-content"] = "center"
            styleSnapshot.justifyContent == JustifyContent.END || "justify-content-end" in tokens ->
                nativeWidget.styleProperties["justify-content"] = "flex-end"
            styleSnapshot.justifyContent == JustifyContent.START || "justify-content-start" in tokens ->
                nativeWidget.styleProperties["justify-content"] = "flex-start"
            styleSnapshot.justifyContent == JustifyContent.AROUND || "justify-content-around" in tokens ->
                nativeWidget.styleProperties["justify-content"] = "space-around"
            styleSnapshot.justifyContent == JustifyContent.EVENLY || "justify-content-evenly" in tokens ->
                nativeWidget.styleProperties["justify-content"] = "space-evenly"
        }
        when {
            styleSnapshot.alignItems == AlignItems.CENTER || "align-items-center" in tokens ->
                nativeWidget.styleProperties["align-items"] = "center"
            styleSnapshot.alignItems == AlignItems.START || "align-items-start" in tokens ->
                nativeWidget.styleProperties["align-items"] = "flex-start"
            styleSnapshot.alignItems == AlignItems.END || "align-items-end" in tokens ->
                nativeWidget.styleProperties["align-items"] = "flex-end"
            styleSnapshot.alignItems == AlignItems.STRETCH || "align-items-stretch" in tokens ->
                nativeWidget.styleProperties["align-items"] = "stretch"
            styleSnapshot.alignItems == AlignItems.BASELINE || "align-items-baseline" in tokens ->
                nativeWidget.styleProperties["align-items"] = "baseline"
        }
        when {
            styleSnapshot.flexGrow == BootstrapFlexGrow.GROW_1 || styleSnapshot.flexFill || "flex-grow-1" in tokens || "flex-fill" in tokens ->
                nativeWidget.styleProperties["flex-grow"] = "1"
            styleSnapshot.flexGrow == BootstrapFlexGrow.GROW_0 || "flex-grow-0" in tokens ->
                nativeWidget.styleProperties["flex-grow"] = "0"
        }
        when {
            styleSnapshot.flexShrink == BootstrapFlexShrink.SHRINK_0 || "flex-shrink-0" in tokens ->
                nativeWidget.styleProperties["flex-shrink"] = "0"
            styleSnapshot.flexShrink == BootstrapFlexShrink.SHRINK_1 || "flex-shrink-1" in tokens ->
                nativeWidget.styleProperties["flex-shrink"] = "1"
        }

        // Sync width / height fractions
        resolveBootstrapWidthFraction()?.let { frac ->
            if ("width" !in nativeWidget.styleProperties) {
                nativeWidget.styleProperties["width"] = "${(frac * 100f).roundToInt()}%"
            }
        }
        resolveBootstrapHeightFraction()?.let { frac ->
            if ("height" !in nativeWidget.styleProperties) {
                nativeWidget.styleProperties["height"] = "${(frac * 100f).roundToInt()}%"
            }
        }

        // Sync position and overflow
        when {
            styleSnapshot.position == PositionMode.ABSOLUTE || "position-absolute" in tokens ->
                nativeWidget.styleProperties["position"] = "absolute"
            styleSnapshot.position == PositionMode.RELATIVE || "position-relative" in tokens ->
                nativeWidget.styleProperties["position"] = "relative"
            styleSnapshot.position == PositionMode.FIXED || "position-fixed" in tokens ->
                nativeWidget.styleProperties["position"] = "fixed"
            styleSnapshot.position == PositionMode.STICKY || "position-sticky" in tokens ->
                nativeWidget.styleProperties["position"] = "sticky"
        }
        when {
            styleSnapshot.overflow == OverflowBehavior.HIDDEN || "overflow-hidden" in tokens ->
                nativeWidget.styleProperties["overflow"] = "hidden"
            styleSnapshot.overflow == OverflowBehavior.AUTO || "overflow-auto" in tokens ->
                nativeWidget.styleProperties["overflow"] = "auto"
            styleSnapshot.overflow == OverflowBehavior.SCROLL || "overflow-scroll" in tokens ->
                nativeWidget.styleProperties["overflow"] = "scroll"
        }

        // Sync aspect ratio
        val ratio = lastComponentStyle?.aspectRatio?.numericRatio ?: styleSnapshot.aspectRatio?.numericRatio ?: when {
            "ratio-16x9" in tokens -> 16f / 9f
            "ratio-4x3" in tokens -> 4f / 3f
            "ratio-1x1" in tokens -> 1f
            "ratio-21x9" in tokens -> 21f / 9f
            else -> null
        }
        if (ratio != null && ratio > 0f) {
            nativeWidget.attributes["aspectRatio"] = ratio.toString()
            nativeWidget.styleProperties["aspect-ratio"] = ratio.toString()
        }

        // Ensure backdrop-filter is never active on Desktop
        nativeWidget.styleProperties.remove("backdrop-filter")
        nativeWidget.styleProperties.remove("-webkit-backdrop-filter")
        nativeWidget.attributes.remove("backdropFilter")
    }

    protected open fun onStyleChanged() {
        if (batchAttachDepth > 0) {
            batchStyleDirty = true
            return
        }
        syncStyleToNativeWidget()
        nativeWidget.notifyTreeInvalidated()
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
        if (tokens == styleSnapshot.customClasses) return
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
        if (!updated.addAll(tokens)) return
        styleSnapshot = styleSnapshot.copy(customClasses = updated)
        if (tokens.all { it == "row-hovered" || it == "hovered" }) {
            cachedClassTokens = null
            nativeWidget.attributes["bootstrapClass"] = styleSnapshot.customClasses.joinToString(" ")
            nativeWidget.attributes["styleSnapshot"] = styleSnapshot
            nativeWidget.rebuildClassTokensCache(componentClassPrefix)
            return
        }
        onStyleChanged()
    }

    override fun removeClass(className: String) {
        val tokens = className.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return
        val updated = LinkedHashSet(styleSnapshot.customClasses)
        if (!updated.removeAll(tokens.toSet())) return
        styleSnapshot = styleSnapshot.copy(customClasses = updated)
        if (tokens.all { it == "row-hovered" || it == "hovered" }) {
            cachedClassTokens = null
            nativeWidget.attributes["bootstrapClass"] = styleSnapshot.customClasses.joinToString(" ")
            nativeWidget.attributes["styleSnapshot"] = styleSnapshot
            nativeWidget.rebuildClassTokensCache(componentClassPrefix)
            return
        }
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

        style.width?.let { nativeWidget.styleProperties["width"] = it.toCssString() }
        style.height?.let { nativeWidget.styleProperties["height"] = it.toCssString() }
        style.minWidth?.let { nativeWidget.styleProperties["min-width"] = it.toCssString() }
        style.minHeight?.let { nativeWidget.styleProperties["min-height"] = it.toCssString() }
        style.maxWidth?.let { nativeWidget.styleProperties["max-width"] = it.toCssString() }
        style.maxHeight?.let { nativeWidget.styleProperties["max-height"] = it.toCssString() }
        style.flexGrow?.let { nativeWidget.styleProperties["flex-grow"] = it.toString() }
        style.flexShrink?.let { nativeWidget.styleProperties["flex-shrink"] = it.toString() }
        style.flexBasis?.let { nativeWidget.styleProperties["flex-basis"] = it.toCssString() }
        style.top?.let { nativeWidget.styleProperties["top"] = it.toCssString() }
        style.right?.let { nativeWidget.styleProperties["right"] = it.toCssString() }
        style.bottom?.let { nativeWidget.styleProperties["bottom"] = it.toCssString() }
        style.left?.let { nativeWidget.styleProperties["left"] = it.toCssString() }
        style.aspectRatio?.let {
            nativeWidget.attributes["aspectRatio"] = it
            nativeWidget.styleProperties["aspect-ratio"] = it.toString()
        }
        if (batchAttachDepth > 0) {
            batchStyleDirty = true
        } else {
            syncBootstrapClassesAndStyles()
        }
    }

    fun getComponentStyle(): ComponentStyle? = lastComponentStyle

    override fun applyComponentBootstrap(bootstrap: ComponentBootstrap) {
        lastBootstrap = bootstrap
        val next = bootstrap.applyToSnapshot(styleSnapshot)
        if (next == styleSnapshot) return
        styleSnapshot = next
        onStyleChanged()
    }

    fun getComponentBootstrap(): ComponentBootstrap? = lastBootstrap

    override fun setCustomCursor(cursor: CssCursorValue?) {
        if (customCursorState == cursor) return
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
        if (styleSnapshot.type == type) return
        styleSnapshot = styleSnapshot.copy(type = type)
        onStyleChanged()
    }

    override fun getType(): BootstrapType? = styleSnapshot.type

    override fun setOutline(outline: Boolean) {
        if (styleSnapshot.outline == outline) return
        styleSnapshot = styleSnapshot.copy(outline = outline)
        onStyleChanged()
    }

    override fun isOutline(): Boolean = styleSnapshot.outline

    override fun setSize(size: BootstrapSize?) {
        if (styleSnapshot.size == size) return
        styleSnapshot = styleSnapshot.copy(size = size)
        onStyleChanged()
    }

    override fun getSize(): BootstrapSize? = styleSnapshot.size

    override fun setBackgroundType(type: BootstrapType?) {
        if (styleSnapshot.backgroundType == type) return
        styleSnapshot = styleSnapshot.copy(backgroundType = type)
        onStyleChanged()
    }

    override fun getBackgroundType(): BootstrapType? = styleSnapshot.backgroundType

    override fun setTextColorType(type: BootstrapType?) {
        if (styleSnapshot.textColorType == type) return
        styleSnapshot = styleSnapshot.copy(textColorType = type)
        onStyleChanged()
    }

    override fun getTextColorType(): BootstrapType? = styleSnapshot.textColorType

    override fun setBorderType(type: BootstrapType?) {
        if (styleSnapshot.borderType == type) return
        styleSnapshot = styleSnapshot.copy(borderType = type)
        onStyleChanged()
    }

    override fun getBorderType(): BootstrapType? = styleSnapshot.borderType

    override fun setTextAlignment(alignment: TextAlignment?) {
        if (styleSnapshot.textAlignment == alignment) return
        styleSnapshot = styleSnapshot.copy(textAlignment = alignment)
        onStyleChanged()
    }

    override fun getTextAlignment(): TextAlignment? = styleSnapshot.textAlignment

    override fun setFontWeight(weight: FontWeight?) {
        if (styleSnapshot.fontWeight == weight) return
        styleSnapshot = styleSnapshot.copy(fontWeight = weight)
        onStyleChanged()
    }

    override fun getFontWeight(): FontWeight? = styleSnapshot.fontWeight

    override fun setTextTransform(transform: TextTransform?) {
        if (styleSnapshot.textTransform == transform) return
        styleSnapshot = styleSnapshot.copy(textTransform = transform)
        onStyleChanged()
    }

    override fun getTextTransform(): TextTransform? = styleSnapshot.textTransform

    override fun setTextOverflow(overflow: TextOverflow?) {
        if (styleSnapshot.textOverflow == overflow) return
        styleSnapshot = styleSnapshot.copy(textOverflow = overflow)
        onStyleChanged()
    }

    override fun getTextOverflow(): TextOverflow? = styleSnapshot.textOverflow

    override fun setBorderRadius(radius: BorderRadiusStyle?) {
        if (styleSnapshot.borderRadius == radius) return
        styleSnapshot = styleSnapshot.copy(borderRadius = radius)
        onStyleChanged()
    }

    override fun getBorderRadius(): BorderRadiusStyle? = styleSnapshot.borderRadius

    override fun setShadow(shadow: ShadowElevation?) {
        if (styleSnapshot.shadow == shadow) return
        styleSnapshot = styleSnapshot.copy(shadow = shadow)
        onStyleChanged()
    }

    override fun getShadow(): ShadowElevation? = styleSnapshot.shadow

    override fun setDisplay(display: DisplayMode?) {
        if (styleSnapshot.display == display) return
        styleSnapshot = styleSnapshot.copy(display = display)
        onStyleChanged()
    }

    override fun getDisplay(): DisplayMode? = styleSnapshot.display

    override fun setFlexDirection(direction: FlexDirection?) {
        if (styleSnapshot.flexDirection == direction) return
        styleSnapshot = styleSnapshot.copy(flexDirection = direction)
        onStyleChanged()
    }

    override fun getFlexDirection(): FlexDirection? = styleSnapshot.flexDirection

    override fun setFlexWrap(wrap: FlexWrap?) {
        if (styleSnapshot.flexWrap == wrap) return
        styleSnapshot = styleSnapshot.copy(flexWrap = wrap)
        onStyleChanged()
    }

    override fun getFlexWrap(): FlexWrap? = styleSnapshot.flexWrap

    override fun setJustifyContent(justify: JustifyContent?) {
        if (styleSnapshot.justifyContent == justify) return
        styleSnapshot = styleSnapshot.copy(justifyContent = justify)
        onStyleChanged()
    }

    override fun getJustifyContent(): JustifyContent? = styleSnapshot.justifyContent

    override fun setAlignItems(align: AlignItems?) {
        if (styleSnapshot.alignItems == align) return
        styleSnapshot = styleSnapshot.copy(alignItems = align)
        onStyleChanged()
    }

    override fun getAlignItems(): AlignItems? = styleSnapshot.alignItems

    override fun setAlignSelf(align: AlignSelf?) {
        if (styleSnapshot.alignSelf == align) return
        styleSnapshot = styleSnapshot.copy(alignSelf = align)
        onStyleChanged()
    }

    override fun getAlignSelf(): AlignSelf? = styleSnapshot.alignSelf

    override fun setOverflow(overflow: OverflowBehavior?) {
        if (styleSnapshot.overflow == overflow) return
        styleSnapshot = styleSnapshot.copy(overflow = overflow)
        onStyleChanged()
    }

    override fun getOverflow(): OverflowBehavior? = styleSnapshot.overflow

    override fun setPosition(position: PositionMode?) {
        if (styleSnapshot.position == position) return
        styleSnapshot = styleSnapshot.copy(position = position)
        onStyleChanged()
    }

    override fun getPosition(): PositionMode? = styleSnapshot.position

    override fun setMarginAll(scale: SpacingScale?) {
        if (styleSnapshot.marginAll == scale) return
        styleSnapshot = styleSnapshot.copy(marginAll = scale)
        onStyleChanged()
    }

    override fun getMarginAll(): SpacingScale? = styleSnapshot.marginAll

    override fun setPaddingAll(scale: SpacingScale?) {
        if (styleSnapshot.paddingAll == scale) return
        styleSnapshot = styleSnapshot.copy(paddingAll = scale)
        onStyleChanged()
    }

    override fun getPaddingAll(): SpacingScale? = styleSnapshot.paddingAll

    // -------------------------------------------------------------------------
    // Geometry, Visibility & Layout Resolution Helpers
    // -------------------------------------------------------------------------

    override fun setVisible(visible: Boolean) {
        if (nativeWidget.visible != visible) {
            nativeWidget.visible = visible
            nativeWidget.notifyTreeInvalidated()
        }
    }

    override fun isVisible(): Boolean = nativeWidget.visible

    override fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            hoveredState = false
            focusedState = false
            activePressedState = false
            nativeWidget.attributes["hovered"] = false
            nativeWidget.attributes["focused"] = false
            nativeWidget.attributes["activePressed"] = false
        }
        if (nativeWidget.enabled != enabled) {
            nativeWidget.enabled = enabled
            nativeWidget.notifyTreeInvalidated()
        }
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

    override fun getBounds(): Rect {
        val hitTestWidget = DesktopNativeWidget.activePopupHitTestWidget
        if (hitTestWidget != null) {
            if (nativeWidget.containsNativeWidget(hitTestWidget)) {
                return Rect(DesktopNativeWidget.activePopupHitTestX, DesktopNativeWidget.activePopupHitTestY, 1, 1)
            }
            if (nativeWidget.bounds.width <= 0 || nativeWidget.bounds.height <= 0) {
                return Rect(DesktopNativeWidget.activePopupHitTestX + 100000, DesktopNativeWidget.activePopupHitTestY + 100000, 0, 0)
            }
        }
        return nativeWidget.bounds
    }

    override fun setPadding(padding: EdgeInsets) {
        explicitPadding = padding
        nativeWidget.padding = padding
    }

    override fun setMargin(margin: EdgeInsets) {
        explicitMargin = margin
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
            if (k.equals("backdrop-filter", ignoreCase = true) || k.equals("-webkit-backdrop-filter", ignoreCase = true)) {
                continue
            }
            if (nativeWidget.styleProperties[k] != v) {
                nativeWidget.styleProperties[k] = v
            }
        }
        nativeWidget.attributes.remove("backdropFilter")
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
        val effective = hovered && nativeWidget.enabled
        if (hoveredState == effective) return
        hoveredState = effective
        nativeWidget.attributes["hovered"] = effective
    }

    override fun isHoveredState(): Boolean = hoveredState

    override fun setFocusedState(focused: Boolean) {
        val effective = focused && nativeWidget.enabled
        if (focusedState == effective) return
        focusedState = effective
        nativeWidget.attributes["focused"] = effective
    }

    override fun isFocusedState(): Boolean = focusedState

    override fun setActivePressedState(active: Boolean) {
        val effective = active && nativeWidget.enabled
        if (activePressedState == effective) return
        activePressedState = effective
        nativeWidget.attributes["activePressed"] = effective
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

    override fun setOnBeforeTargetFramePresent(listener: ((frameSwapCount: Long, timestampMs: Double?) -> Unit)?) {
        if (listener != null) {
            nativeWidget.attributes["onBeforeTargetFramePresent"] = listener
            nativeWidget.stageOverlaySpec?.onBeforeTargetFramePresent = listener
        } else {
            nativeWidget.attributes.remove("onBeforeTargetFramePresent")
            nativeWidget.stageOverlaySpec?.onBeforeTargetFramePresent = null
        }
    }

    companion object {
        private val WHITESPACE_REGEX = Regex("\\s+")
    }
}
