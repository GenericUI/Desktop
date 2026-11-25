package com.nexustools.genericui.desktop.core

import com.nexustools.genericui.core.NativeImpl
import com.nexustools.genericui.desktop.dialog.DesktopDialogWindowNativeImpl
import com.nexustools.genericui.desktop.dock.DesktopDockWindowNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyGridNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyListNativeImpl
import com.nexustools.genericui.desktop.shell.DesktopWindowNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopScrollViewNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledComponentNativeImpl
import com.nexustools.genericui.enums.bootstrap.*
import com.nexustools.genericui.enums.css.CssCursor
import com.nexustools.genericui.enums.css.CssPointerEvents
import com.nexustools.genericui.enums.css.CssPosition
import com.nexustools.genericui.enums.css.CssUnit
import com.nexustools.genericui.enums.css.CssVisibility
import com.nexustools.genericui.enums.ui.Orientation
import com.nexustools.genericui.event.KeyEvent
import com.nexustools.genericui.event.PointerEvent
import com.nexustools.genericui.event.WheelEvent
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.nativeimpl.action.ButtonNativeImpl
import com.nexustools.genericui.style.bootstrap.ComponentBootstrap
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComponentStyle
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssCustomCursor
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.css.CssProperty
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.style.mapper.BootstrapClassMapper
import com.nexustools.genericui.style.mapper.BootstrapStyleSnapshot
import com.nexustools.genericui.style.policy.AestheticStylePolicy
import com.nexustools.genericui.style.transition.CssTransitionSpec
import kotlin.math.roundToInt

/**
 * Shared desktop base implementation of [NativeImpl] supporting dual-mode Bootstrap 5 enum styling,
 * strict user CSS class isolation (`customClasses`), [ComponentStyle], [ComponentBootstrap],
 * [CssCustomCursor], geometry, visibility, and input event callbacks across `:windows`, `:macos`,
 * `:gtk`, and `:kde`.
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
    private var cachedEffectiveBase: ComputedStyle? = null
    private var cachedEffectiveHovered: Boolean = false
    private var cachedEffectiveFocused: Boolean = false
    private var cachedEffectiveActive: Boolean = false
    private var cachedEffectiveEnabled: Boolean = true
    private var cachedEffectiveResult: ComputedStyle? = null
    private var backgroundGradientState: Gradient? = null
    private var backgroundColorState: RgbaColor? = null

    private var hoveredState: Boolean = false
    private var focusedState: Boolean = false
    private var activePressedState: Boolean = false
    private var focusableState: Boolean = true
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
    private var baseInitialized: Boolean = false
    private var lastDispatchedCursor: CssCursorValue? = null
    private var lastPointerEventsNone: Boolean = false
    private var lastEffectiveZIndex: Int = 0
    private var lastResolvedTransform: CssTransform? = null

    init {
        nativeWidget.attributes["nativeImpl"] = this
        nativeWidget.attributes["antialiasing"] = antialiasing
        syncStyleToNativeWidget()
        baseInitialized = true
    }

    // -------------------------------------------------------------------------
    // Retained Native Widget Lifecycle Hooks
    // -------------------------------------------------------------------------

    protected open fun onNativeBoundsChanged(rect: Rect) {}

    protected open fun onNativeVisibilityChanged(visible: Boolean) {}

    protected open fun onNativeEnabledChanged(enabled: Boolean) {}

    protected open fun onNativeFocusableChanged(focusable: Boolean) {}

    protected open fun onNativeOpacityChanged(opacity: Float) {}

    protected open fun onNativeTooltipChanged(tooltip: String?) {}

    protected open fun onNativeCursorChanged(cursor: CssCursorValue?) {}

    protected open fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {}

    protected open fun onNativeZIndexChanged(zIndex: Int) {}

    protected open fun onNativeTransformChanged(transform: CssTransform?) {}

    protected open fun onNativeStyleSynced() {}

    fun notifyHostThemeChanged() {
        onNativeStyleSynced()
    }

    open fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        nativeWidget.preferredSize?.let { pref ->
            if (pref.width > 0 || pref.height > 0) {
                return pref
            }
        }
        val refW = maxWidth ?: nativeWidget.bounds.width.toFloat()
        val refH = maxHeight ?: nativeWidget.bounds.height.toFloat()
        val styleW = (nativeWidget.attributes["styleWidth"] as? CssLength)?.resolvePx(refW)?.roundToInt()
            ?: lastComponentStyle?.width?.resolvePx(refW)?.roundToInt()
        val styleH = (nativeWidget.attributes["styleHeight"] as? CssLength)?.resolvePx(refH)?.roundToInt()
            ?: lastComponentStyle?.height?.resolvePx(refH)?.roundToInt()
        val pad = getEffectivePaddingInsets(refW, refH)
        val bounds = nativeWidget.bounds
        val w = styleW ?: if (bounds.width > 0) bounds.width else (pad.left + pad.right)
        val h = styleH ?: if (bounds.height > 0) bounds.height else (pad.top + pad.bottom)
        return Size(w.coerceAtLeast(0), h.coerceAtLeast(0))
    }

    protected fun notifyStyleSyncedIfReady() {
        if (baseInitialized) {
            onNativeStyleSynced()
        }
    }

    protected fun syncPointerEventsAndZIndexHooks() {
        if (!baseInitialized) {
            lastPointerEventsNone = nativeWidget.isSelfPointerEventsNone()
            lastEffectiveZIndex = nativeWidget.effectiveZIndex()
            return
        }
        val peNone = nativeWidget.isSelfPointerEventsNone()
        if (lastPointerEventsNone != peNone) {
            lastPointerEventsNone = peNone
            onNativePointerEventsChanged(peNone)
        }
        val zIdx = nativeWidget.effectiveZIndex()
        if (lastEffectiveZIndex != zIdx) {
            lastEffectiveZIndex = zIdx
            (nativeWidget.parentWidget?.attributes?.get("nativeImpl") as? DesktopBaseContainerNativeImpl<*>)
                ?.onChildZIndexInvalidated()
            onNativeZIndexChanged(zIdx)
        }
    }

    protected fun applyResolvedOpacity(opacity: Float, notifyTree: Boolean = false) {
        val clamped = opacity.coerceIn(0f, 1f)
        val changed = nativeWidget.opacity != clamped
        nativeWidget.opacity = clamped
        nativeWidget.attributes["opacity"] = clamped
        if (changed) {
            if (baseInitialized) {
                onNativeOpacityChanged(clamped)
            }
            if (notifyTree) {
                notifyTreeInvalidated()
            }
        }
    }

    protected fun applyCursor(cursor: CssCursorValue?, fallbackCssString: String? = null) {
        when (cursor) {
            is CssCustomCursor -> {
                nativeWidget.attributes["customCursor"] = cursor
                nativeWidget.attributes["cursor"] = cursor.fallback.cssValue
            }
            is CssCursor -> {
                nativeWidget.attributes.remove("customCursor")
                nativeWidget.attributes["cursor"] = cursor.cssValue
            }
            null -> {
                nativeWidget.attributes.remove("customCursor")
                if (fallbackCssString != null) {
                    nativeWidget.attributes["cursor"] = fallbackCssString
                } else {
                    nativeWidget.attributes.remove("cursor")
                }
            }
            else -> {
                nativeWidget.attributes.remove("customCursor")
                nativeWidget.attributes["cursor"] = cursor.toCssString()
            }
        }
        if (lastDispatchedCursor != cursor) {
            lastDispatchedCursor = cursor
            if (baseInitialized) {
                onNativeCursorChanged(cursor)
            }
        }
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

    fun hasPointerListeners(): Boolean =
        onPointerDownListener != null ||
            onPointerMoveListener != null ||
            onPointerUpListener != null ||
            onPointerEnterListener != null ||
            onPointerLeaveListener != null

    fun isPointerInteractionDisabled(): Boolean {
        if (nativeWidget.isSelfPointerEventsNone()) return true
        var cur: DesktopNativeWidget? = nativeWidget.parentWidget
        while (cur != null) {
            if (cur.isSubtreeHitTestBlocked()) return true
            cur = cur.parentWidget
        }
        return false
    }

    open fun dispatchClick() {
        if (nativeWidget.enabled && !isPointerInteractionDisabled()) {
            if (DesktopNativeWidget.interceptPopupClick(Float.NaN, Float.NaN, nativeWidget)) return
            onClickListener?.invoke()
        }
    }

    override fun setAttribute(name: String, value: String?) {
        if ((name.equals("backdrop-filter", ignoreCase = true) || name.equals("-webkit-backdrop-filter", ignoreCase = true)) &&
            !acceptsAestheticSkinStyles()
        ) {
            return
        }
        val prevValue = nativeWidget.attributes[name]
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
                nativeWidget.notifyTreeInvalidated()
            }
        } else {
            if (name == "pointerEvents" || name == "zIndex") {
                syncPointerEventsAndZIndexHooks()
            }
            if (prevValue != value) {
                notifyTreeInvalidated()
            }
        }
    }

    fun dispatchPointerDown(event: PointerEvent) {
        if (nativeWidget.enabled && !isPointerInteractionDisabled()) {
            if (DesktopNativeWidget.interceptPopupPointerDown(event.x, event.y, nativeWidget)) return
            onPointerDownListener?.invoke(event)
        }
    }

    fun dispatchPointerMove(event: PointerEvent) {
        if (nativeWidget.enabled && !isPointerInteractionDisabled()) {
            onPointerMoveListener?.invoke(event)
        }
    }

    fun dispatchPointerUp(event: PointerEvent) {
        if (nativeWidget.enabled && !isPointerInteractionDisabled()) {
            if (DesktopNativeWidget.interceptPopupPointerUp(event.x, event.y, nativeWidget)) return
            onPointerUpListener?.invoke(event)
        }
    }

    fun dispatchPointerEnter(event: PointerEvent = PointerEvent()) {
        if (!nativeWidget.enabled || isPointerInteractionDisabled()) return
        setHoveredState(true)
        onPointerEnterListener?.invoke(event)
    }

    fun dispatchPointerLeave(event: PointerEvent = PointerEvent()) {
        setHoveredState(false)
        onPointerLeaveListener?.invoke(event)
    }

    fun dispatchDoubleClick() {
        if (nativeWidget.enabled && !isPointerInteractionDisabled()) {
            onDoubleClickListener?.invoke()
        }
    }

    fun dispatchContextMenu(event: PointerEvent) {
        if (nativeWidget.enabled && !isPointerInteractionDisabled()) {
            onContextMenuListener?.invoke(event)
        }
    }

    fun performContextMenu(x: Float = 0f, y: Float = 0f): Boolean {
        val listener = onContextMenuListener ?: return false
        listener(PointerEvent(x = x, y = y, button = 2))
        return true
    }

    fun dispatchWheel(event: WheelEvent) {
        if (nativeWidget.enabled && !isPointerInteractionDisabled()) {
            onWheelListener?.invoke(event)
        }
    }

    open fun dispatchKeyDown(event: KeyEvent) {
        if (nativeWidget.enabled) {
            onKeyDownListener?.invoke(event)
        }
    }

    open fun dispatchKeyUp(event: KeyEvent) {
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
            if (nativeWidget.visible) {
                nativeWidget.visible = false
                if (baseInitialized) {
                    onNativeVisibilityChanged(false)
                }
            }
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

        // Sync padding and margin from explicit, StyledVisualSpec, ComponentStyle, ComputedStyle, or Bootstrap snapshot/tokens
        syncResolvedBoxSpacing(tokens)

        // Sync gap / row-gap / column-gap
        val gapPx = lastComponentStyle?.gap?.resolvePx()?.roundToInt()
            ?: styleSnapshot.gap?.toPixels()?.coerceAtLeast(0)
            ?: spacingTokenPx(tokens, TOKENS_GAP)
            ?: spacingTokenPx(tokens, TOKENS_G)
        if (gapPx != null) {
            nativeWidget.attributes["gapPx"] = gapPx
        }
        val rowGapPx = lastComponentStyle?.rowGap?.resolvePx()?.roundToInt()
            ?: styleSnapshot.rowGap?.toPixels()?.coerceAtLeast(0)
            ?: spacingTokenPx(tokens, TOKENS_ROW_GAP)
            ?: spacingTokenPx(tokens, TOKENS_GY)
        if (rowGapPx != null) {
            nativeWidget.attributes["rowGapPx"] = rowGapPx
        }
        val colGapPx = lastComponentStyle?.columnGap?.resolvePx()?.roundToInt()
            ?: styleSnapshot.columnGap?.toPixels()?.coerceAtLeast(0)
            ?: spacingTokenPx(tokens, TOKENS_COLUMN_GAP)
            ?: spacingTokenPx(tokens, TOKENS_GX)
        if (colGapPx != null) {
            nativeWidget.attributes["columnGapPx"] = colGapPx
        }

        // Sync display / flex properties
        when {
            styleSnapshot.display == DisplayMode.NONE || "d-none" in tokens -> {
                nativeWidget.attributes["display"] = "none"
                if (nativeWidget.visible) {
                    nativeWidget.visible = false
                    if (baseInitialized) {
                        onNativeVisibilityChanged(false)
                    }
                }
            }
            styleSnapshot.display == DisplayMode.FLEX || "d-flex" in tokens -> {
                nativeWidget.attributes["display"] = "flex"
            }
            styleSnapshot.display == DisplayMode.INLINE_FLEX || "d-inline-flex" in tokens -> {
                nativeWidget.attributes["display"] = "inline-flex"
            }
        }
        when {
            styleSnapshot.flexDirection == FlexDirection.COLUMN || "flex-column" in tokens ->
                nativeWidget.attributes["flexDirection"] = "column"
            styleSnapshot.flexDirection == FlexDirection.ROW || "flex-row" in tokens ->
                nativeWidget.attributes["flexDirection"] = "row"
            styleSnapshot.flexDirection == FlexDirection.COLUMN_REVERSE || "flex-column-reverse" in tokens ->
                nativeWidget.attributes["flexDirection"] = "column-reverse"
            styleSnapshot.flexDirection == FlexDirection.ROW_REVERSE || "flex-row-reverse" in tokens ->
                nativeWidget.attributes["flexDirection"] = "row-reverse"
        }
        when {
            styleSnapshot.flexWrap == FlexWrap.WRAP || "flex-wrap" in tokens ->
                nativeWidget.attributes["flexWrap"] = "wrap"
            styleSnapshot.flexWrap == FlexWrap.NOWRAP || "flex-nowrap" in tokens ->
                nativeWidget.attributes["flexWrap"] = "nowrap"
        }
        when {
            styleSnapshot.justifyContent == JustifyContent.BETWEEN || "justify-content-between" in tokens ->
                nativeWidget.attributes["justifyContent"] = "space-between"
            styleSnapshot.justifyContent == JustifyContent.CENTER || "justify-content-center" in tokens ->
                nativeWidget.attributes["justifyContent"] = "center"
            styleSnapshot.justifyContent == JustifyContent.END || "justify-content-end" in tokens ->
                nativeWidget.attributes["justifyContent"] = "flex-end"
            styleSnapshot.justifyContent == JustifyContent.START || "justify-content-start" in tokens ->
                nativeWidget.attributes["justifyContent"] = "flex-start"
            styleSnapshot.justifyContent == JustifyContent.AROUND || "justify-content-around" in tokens ->
                nativeWidget.attributes["justifyContent"] = "space-around"
            styleSnapshot.justifyContent == JustifyContent.EVENLY || "justify-content-evenly" in tokens ->
                nativeWidget.attributes["justifyContent"] = "space-evenly"
        }
        when {
            styleSnapshot.alignItems == AlignItems.CENTER || "align-items-center" in tokens ->
                nativeWidget.attributes["alignItems"] = "center"
            styleSnapshot.alignItems == AlignItems.START || "align-items-start" in tokens ->
                nativeWidget.attributes["alignItems"] = "flex-start"
            styleSnapshot.alignItems == AlignItems.END || "align-items-end" in tokens ->
                nativeWidget.attributes["alignItems"] = "flex-end"
            styleSnapshot.alignItems == AlignItems.STRETCH || "align-items-stretch" in tokens ->
                nativeWidget.attributes["alignItems"] = "stretch"
            styleSnapshot.alignItems == AlignItems.BASELINE || "align-items-baseline" in tokens ->
                nativeWidget.attributes["alignItems"] = "baseline"
        }
        when {
            styleSnapshot.flexGrow == BootstrapFlexGrow.GROW_1 || styleSnapshot.flexFill || "flex-grow-1" in tokens || "flex-fill" in tokens ->
                nativeWidget.attributes["flexGrow"] = 1f
            styleSnapshot.flexGrow == BootstrapFlexGrow.GROW_0 || "flex-grow-0" in tokens ->
                nativeWidget.attributes["flexGrow"] = 0f
        }
        when {
            styleSnapshot.flexShrink == BootstrapFlexShrink.SHRINK_0 || "flex-shrink-0" in tokens ->
                nativeWidget.attributes["flexShrink"] = 0f
            styleSnapshot.flexShrink == BootstrapFlexShrink.SHRINK_1 || "flex-shrink-1" in tokens ->
                nativeWidget.attributes["flexShrink"] = 1f
        }

        // Sync width / height fractions
        resolveBootstrapWidthFraction()?.let { frac ->
            if ("styleWidth" !in nativeWidget.attributes) {
                nativeWidget.attributes["widthFraction"] = frac
            }
        }
        resolveBootstrapHeightFraction()?.let { frac ->
            if ("styleHeight" !in nativeWidget.attributes) {
                nativeWidget.attributes["heightFraction"] = frac
            }
        }

        // Sync position and overflow
        syncResolvedLayoutAndInteractionAttributes()
        when {
            styleSnapshot.overflow == OverflowBehavior.HIDDEN || "overflow-hidden" in tokens ->
                nativeWidget.attributes["overflow"] = "hidden"
            styleSnapshot.overflow == OverflowBehavior.AUTO || "overflow-auto" in tokens ->
                nativeWidget.attributes["overflow"] = "auto"
            styleSnapshot.overflow == OverflowBehavior.SCROLL || "overflow-scroll" in tokens ->
                nativeWidget.attributes["overflow"] = "scroll"
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
        }

        if (!acceptsAestheticSkinStyles()) {
            nativeWidget.attributes.remove("backdropFilter")
            nativeWidget.attributes.remove("backdropBlurPx")
        }
        notifyStyleSyncedIfReady()
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
        onStyleChanged()
    }

    override fun removeClass(className: String) {
        val tokens = className.trim().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return
        val updated = LinkedHashSet(styleSnapshot.customClasses)
        if (!updated.removeAll(tokens.toSet())) return
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

        if (acceptsAestheticSkinStyles()) {
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
        } else {
            backgroundColorState = null
            nativeWidget.attributes.remove("backgroundColor")
            backgroundGradientState = null
            nativeWidget.attributes.remove("backgroundGradient")
        }

        if (acceptsTypographyStyles()) {
            if (style.color != null) {
                nativeWidget.attributes["textColor"] = style.color
            } else {
                nativeWidget.attributes.remove("textColor")
            }
            val resolvedFontSizePx = style.fontSize?.resolvePx()
            if (resolvedFontSizePx != null) {
                nativeWidget.attributes["fontSizePx"] = resolvedFontSizePx
            } else {
                nativeWidget.attributes.remove("fontSizePx")
            }
            val resolvedFontWeight = style.fontWeight ?: styleSnapshot.fontWeight
            if (resolvedFontWeight != null) {
                nativeWidget.attributes["fontWeight"] = resolvedFontWeight.name
            } else {
                nativeWidget.attributes.remove("fontWeight")
            }
        } else {
            nativeWidget.attributes.remove("textColor")
            nativeWidget.attributes.remove("fontSizePx")
            nativeWidget.attributes.remove("fontWeight")
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
            applyResolvedOpacity(style.opacity!!, notifyTree = false)
        }

        val fgFilter = style.filter ?: lastComputedStyle?.filter
        if (fgFilter != null && !fgFilter.isNone) {
            nativeWidget.attributes["cssFilter"] = fgFilter
        } else {
            nativeWidget.attributes.remove("cssFilter")
        }

        if (style.cursor != null) {
            setCustomCursor(style.cursor)
        }

        val compVisHidden = lastComponentStyle?.visibility == CssVisibility.HIDDEN ||
            lastComponentStyle?.visibility == CssVisibility.COLLAPSE ||
            lastComputedStyle?.visibility == CssVisibility.HIDDEN ||
            lastComputedStyle?.visibility == CssVisibility.COLLAPSE ||
            styleSnapshot.visibility == BootstrapVisibility.INVISIBLE
        if (compVisHidden) {
            nativeWidget.attributes["visibilityHidden"] = true
        } else {
            nativeWidget.attributes.remove("visibilityHidden")
        }

        syncResolvedLayoutAndInteractionAttributes()
        if (batchAttachDepth > 0) {
            batchStyleDirty = true
        } else {
            syncBootstrapClassesAndStyles()
            nativeWidget.notifyTreeInvalidated()
        }
    }

    private fun syncResolvedLayoutAndInteractionAttributes() {
        val tokens = nativeWidget.cachedAllClassTokens
        val comp = lastComponentStyle
        val sheet = lastComputedStyle
        val prevPointerEventsAttr = nativeWidget.attributes["pointerEvents"]
        val prevCursorAttr = nativeWidget.attributes["cursor"]
        val prevCustomCursorAttr = nativeWidget.attributes["customCursor"]

        fun putOrRemove(key: String, value: Any?) {
            if (value != null) {
                nativeWidget.attributes[key] = value
            } else {
                nativeWidget.attributes.remove(key)
            }
        }

        putOrRemove("styleWidth", comp?.width ?: sheet?.width)
        putOrRemove("styleHeight", comp?.height ?: sheet?.height)
        putOrRemove("styleMinWidth", comp?.minWidth ?: sheet?.minWidth)
        putOrRemove("styleMinHeight", comp?.minHeight ?: sheet?.minHeight)
        putOrRemove("styleMaxWidth", comp?.maxWidth ?: sheet?.maxWidth)
        putOrRemove("styleMaxHeight", comp?.maxHeight ?: sheet?.maxHeight)
        putOrRemove("flexGrow", comp?.flexGrow ?: sheet?.flexGrow ?: nativeWidget.attributes["flexGrow"])
        putOrRemove("flexShrink", comp?.flexShrink ?: sheet?.flexShrink ?: nativeWidget.attributes["flexShrink"])
        putOrRemove("flexBasis", comp?.flexBasis ?: sheet?.flexBasis)

        val resolvedTop = comp?.top ?: sheet?.top
        val resolvedRight = comp?.right ?: sheet?.right
        val resolvedBottom = comp?.bottom ?: sheet?.bottom
        val resolvedLeft = comp?.left ?: sheet?.left
        putOrRemove("styleTop", resolvedTop)
        putOrRemove("styleRight", resolvedRight)
        putOrRemove("styleBottom", resolvedBottom)
        putOrRemove("styleLeft", resolvedLeft)
        putOrRemove("top", sheet?.top ?: comp?.top?.toCssString())
        putOrRemove("left", sheet?.left ?: comp?.left?.toCssString())

        comp?.aspectRatio?.let { nativeWidget.attributes["aspectRatio"] = it }

        val resolvedPosition = comp?.position?.cssValue
            ?: sheet?.position?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: when {
                styleSnapshot.position == PositionMode.ABSOLUTE || "position-absolute" in tokens -> "absolute"
                styleSnapshot.position == PositionMode.RELATIVE || "position-relative" in tokens -> "relative"
                styleSnapshot.position == PositionMode.FIXED || "position-fixed" in tokens -> "fixed"
                styleSnapshot.position == PositionMode.STICKY || "position-sticky" in tokens -> "sticky"
                else -> null
            }
        if (resolvedPosition != null && resolvedPosition != "static") {
            nativeWidget.attributes["position"] = resolvedPosition
        } else {
            nativeWidget.attributes.remove("position")
        }

        val sheetPeImportant = sheet != null &&
            sheet.isImportant(CssProperty.POINTER_EVENTS) &&
            (sheet.hasProperty(CssProperty.POINTER_EVENTS) || !sheet.pointerEventsEnabled)
        val compPeImportant = comp?.pointerEvents != null && comp.isImportant(CssProperty.POINTER_EVENTS)
        val resolvedPointerEvents = when {
            sheetPeImportant && !compPeImportant -> if (sheet!!.pointerEventsEnabled) "auto" else "none"
            comp?.pointerEvents == CssPointerEvents.NONE -> "none"
            comp?.pointerEvents == CssPointerEvents.AUTO -> "auto"
            sheet != null && (sheet.hasProperty(CssProperty.POINTER_EVENTS) || !sheet.pointerEventsEnabled) ->
                if (sheet.pointerEventsEnabled) "auto" else "none"
            styleSnapshot.pointerEvents == BootstrapPointerEvents.NONE || "pe-none" in tokens -> "none"
            styleSnapshot.pointerEvents == BootstrapPointerEvents.AUTO || "pe-auto" in tokens -> "auto"
            else -> null
        }
        putOrRemove("pointerEvents", resolvedPointerEvents)

        val resolvedOverflow = comp?.overflow?.name?.lowercase()
            ?: sheet?.overflow?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: when {
                styleSnapshot.overflow == OverflowBehavior.HIDDEN || "overflow-hidden" in tokens -> "hidden"
                styleSnapshot.overflow == OverflowBehavior.AUTO || "overflow-auto" in tokens -> "auto"
                styleSnapshot.overflow == OverflowBehavior.SCROLL || "overflow-scroll" in tokens -> "scroll"
                styleSnapshot.overflow == OverflowBehavior.VISIBLE -> "visible"
                else -> null
            }
        val resolvedOverflowX = comp?.overflowX?.name?.lowercase()
            ?: sheet?.overflowX?.name?.lowercase()
        val resolvedOverflowY = comp?.overflowY?.name?.lowercase()
            ?: sheet?.overflowY?.name?.lowercase()
        putOrRemove("overflow", resolvedOverflow)
        putOrRemove("overflowX", resolvedOverflowX)
        putOrRemove("overflowY", resolvedOverflowY)

        val resolvedZIndex = comp?.zIndex ?: sheet?.zIndex
        putOrRemove("zIndex", resolvedZIndex)
        syncPointerEventsAndZIndexHooks()

        val t2d = comp?.transform ?: sheet?.transform2D
        val tStr = comp?.transform?.toCssString() ?: sheet?.transform ?: t2d?.toCssString()
        if (t2d != null) {
            nativeWidget.attributes["transform2D"] = t2d
        } else {
            nativeWidget.attributes.remove("transform2D")
        }
        if (tStr != null && !tStr.equals("none", ignoreCase = true)) {
            nativeWidget.attributes["transform"] = tStr
        } else {
            nativeWidget.attributes.remove("transform")
        }
        val parsedTransform = t2d?.let { CssTransform.fromTransform2D(it) }
            ?: tStr?.let { CssTransform.parse(it) }
        setResolvedTransform(parsedTransform)

        val resolvedCursor: CssCursorValue? = customCursorState
            ?: comp?.cursor
            ?: sheet?.customCursor
            ?: sheet?.cursor?.let { CssCursor.fromCss(it) }
        applyCursor(
            cursor = resolvedCursor,
            fallbackCssString = if (resolvedCursor == null) sheet?.cursor else null
        )
        if (nativeWidget.attributes["cursor"] != prevCursorAttr ||
            nativeWidget.attributes["customCursor"] != prevCustomCursorAttr ||
            nativeWidget.attributes["pointerEvents"] != prevPointerEventsAttr
        ) {
            DesktopNativeWidget.onCursorUpdated?.invoke()
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
        syncResolvedLayoutAndInteractionAttributes()
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
            onNativeVisibilityChanged(visible)
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
            onNativeEnabledChanged(enabled)
            nativeWidget.notifyTreeInvalidated()
        }
    }

    override fun isEnabled(): Boolean = nativeWidget.enabled

    override fun setFocusable(focusable: Boolean) {
        val changed = focusableState != focusable || nativeWidget.focusable != focusable
        focusableState = focusable
        nativeWidget.focusable = focusable
        if (!focusable) {
            setFocusedState(false)
        }
        if (changed) {
            onNativeFocusableChanged(focusable)
            nativeWidget.onEffectiveFocusableChanged(focusable)
            nativeWidget.notifyTreeInvalidated(visualOnly = true)
        }
    }

    override fun isFocusable(): Boolean = focusableState

    override fun setOpacity(opacity: Float) {
        applyResolvedOpacity(opacity, notifyTree = true)
    }

    override fun getOpacity(): Float = nativeWidget.opacity

    override fun setTooltipText(tooltip: String?) {
        if (nativeWidget.tooltipText != tooltip) {
            nativeWidget.tooltipText = tooltip
            onNativeTooltipChanged(tooltip)
        }
    }

    override fun getTooltipText(): String? = nativeWidget.tooltipText

    open fun setBounds(rect: Rect) {
        if (nativeWidget.bounds != rect) {
            nativeWidget.bounds = rect
            onNativeBoundsChanged(rect)
        }
    }

    open fun setResolvedTransform(transform: CssTransform?) {
        val normalized = transform?.takeIf { !it.isIdentity }
        if (normalized != null) {
            nativeWidget.attributes["resolvedTransform"] = normalized
        } else {
            nativeWidget.attributes.remove("resolvedTransform")
        }
        if (lastResolvedTransform != normalized) {
            lastResolvedTransform = normalized
            if (baseInitialized) {
                onNativeTransformChanged(normalized)
            }
        }
    }

    override fun setPreferredSize(size: Size?) {
        val prevSize = nativeWidget.preferredSize
        nativeWidget.preferredSize = size
        val nextBounds = if (size != null) {
            Rect(nativeWidget.bounds.x, nativeWidget.bounds.y, size.width, size.height)
        } else {
            nativeWidget.bounds
        }
        val boundsChanged = nextBounds != nativeWidget.bounds
        if (boundsChanged) {
            nativeWidget.bounds = nextBounds
            onNativeBoundsChanged(nextBounds)
        }
        if (prevSize != size || boundsChanged) {
            notifyTreeInvalidated()
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
        val local = nativeWidget.bounds
        val selfImpl = nativeWidget.attributes["nativeImpl"]
        if (selfImpl is DesktopWindowNativeImpl<*> ||
            selfImpl is DesktopDialogWindowNativeImpl<*> ||
            selfImpl is DesktopDockWindowNativeImpl<*> ||
            nativeWidget.attributes["genericuiWindowRootHost"] == true
        ) {
            return local
        }
        var wx = local.x
        var wy = local.y
        var anc: DesktopNativeWidget? = nativeWidget.parentWidget
        while (anc != null) {
            val ancImpl = anc.attributes["nativeImpl"]
            if (ancImpl is DesktopWindowNativeImpl<*> ||
                ancImpl is DesktopDialogWindowNativeImpl<*> ||
                ancImpl is DesktopDockWindowNativeImpl<*> ||
                anc.attributes["genericuiWindowRootHost"] == true
            ) {
                wx += (anc.attributes["contentOffsetX"] as? Int) ?: 0
                wy += (anc.attributes["contentOffsetY"] as? Int) ?: 0
                break
            }
            wx += anc.bounds.x + ((anc.attributes["tabContentOffsetX"] as? Int) ?: 0)
            wy += anc.bounds.y + ((anc.attributes["tabContentOffsetY"] as? Int) ?: 0)
            when (ancImpl) {
                is DesktopScrollViewNativeImpl<*> -> {
                    wx -= ancImpl.currentScrollX()
                    wy -= ancImpl.currentScrollY()
                }
                is DesktopLazyListNativeImpl<*> -> {
                    val scrollPx = ancImpl.viewportEngine.scrollOffsetPx.roundToInt()
                    if ((anc.attributes["orientation"] as? Orientation) == Orientation.HORIZONTAL) {
                        wx -= scrollPx
                    } else {
                        wy -= scrollPx
                    }
                }
                is DesktopLazyGridNativeImpl<*> -> {
                    wy -= ancImpl.viewportEngine.scrollOffsetPx.roundToInt()
                }
            }
            anc = anc.parentWidget
        }
        return Rect(wx, wy, local.width, local.height)
    }

    override fun setPadding(padding: EdgeInsets) {
        val nextExplicit = if (padding == EdgeInsets.ZERO) null else padding
        val changed = explicitPadding != nextExplicit || nativeWidget.padding != padding
        explicitPadding = nextExplicit
        if (nextExplicit != null) {
            nativeWidget.padding = nextExplicit
        } else {
            syncBootstrapClassesAndStyles()
        }
        if (changed) {
            notifyTreeInvalidated()
        }
    }

    override fun setMargin(margin: EdgeInsets) {
        val nextExplicit = if (margin == EdgeInsets.ZERO) null else margin
        val changed = explicitMargin != nextExplicit || nativeWidget.margin != margin
        explicitMargin = nextExplicit
        if (nextExplicit != null) {
            nativeWidget.margin = nextExplicit
        } else {
            syncBootstrapClassesAndStyles()
        }
        if (changed) {
            notifyTreeInvalidated()
        }
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
        val prevStyle = lastComputedStyle
        lastComputedStyle = style
        if (acceptsAestheticSkinStyles()) {
            style.backgroundColor?.let { nativeWidget.attributes["backgroundColor"] = it }
                ?: run { if (lastComponentStyle?.backgroundColor == null) nativeWidget.attributes.remove("backgroundColor") }
            style.backgroundGradient?.let {
                backgroundGradientState = it
                nativeWidget.attributes["backgroundGradient"] = it
            }
        } else {
            backgroundColorState = null
            nativeWidget.attributes.remove("backgroundColor")
            backgroundGradientState = null
            nativeWidget.attributes.remove("backgroundGradient")
        }
        if (acceptsTypographyStyles()) {
            style.textColor?.let { nativeWidget.attributes["textColor"] = it }
            val resolvedFontSizePx = lastComponentStyle?.fontSize?.resolvePx() ?: style.fontSizePx
            if (resolvedFontSizePx != null) {
                nativeWidget.attributes["fontSizePx"] = resolvedFontSizePx
            } else {
                nativeWidget.attributes.remove("fontSizePx")
            }
            val resolvedWeight = lastComponentStyle?.fontWeight?.name
                ?: styleSnapshot.fontWeight?.name
                ?: style.fontWeightNumeric
            if (resolvedWeight != null) {
                nativeWidget.attributes["fontWeight"] = resolvedWeight
            } else {
                nativeWidget.attributes.remove("fontWeight")
            }
        } else {
            nativeWidget.attributes.remove("textColor")
            nativeWidget.attributes.remove("fontSizePx")
            nativeWidget.attributes.remove("fontWeight")
        }
        style.accentColor?.let { nativeWidget.attributes["accentColor"] = it }
        val resolvedOp = (style.opacity ?: lastComponentStyle?.opacity ?: 1f).coerceIn(0f, 1f)
        applyResolvedOpacity(resolvedOp, notifyTree = false)
        if (style.transition != null) nativeWidget.attributes["transition"] = style.transition else nativeWidget.attributes.remove("transition")
        if (style.backgroundImageUrl != null) nativeWidget.attributes["backgroundImageUrl"] = style.backgroundImageUrl else nativeWidget.attributes.remove("backgroundImageUrl")
        if (style.textShadow != null) nativeWidget.attributes["textShadow"] = style.textShadow else nativeWidget.attributes.remove("textShadow")
        if (style.boxShadow != null) nativeWidget.attributes["boxShadow"] = style.boxShadow else nativeWidget.attributes.remove("boxShadow")
        if (!acceptsAestheticSkinStyles()) {
            nativeWidget.attributes.remove("backdropFilter")
            nativeWidget.attributes.remove("backdropBlurPx")
        } else {
            val bf = lastComponentStyle?.backdropFilter ?: style.backdropFilter
            if (bf != null && !bf.isNone) {
                nativeWidget.attributes["backdropFilter"] = bf
                bf.blur?.resolvePx()?.takeIf { it > 0f }?.let {
                    nativeWidget.attributes["backdropBlurPx"] = it
                } ?: nativeWidget.attributes.remove("backdropBlurPx")
            } else {
                nativeWidget.attributes.remove("backdropFilter")
                nativeWidget.attributes.remove("backdropBlurPx")
            }
        }
        val computedFgFilter = lastComponentStyle?.filter ?: style.filter
        if (computedFgFilter != null && !computedFgFilter.isNone) {
            nativeWidget.attributes["cssFilter"] = computedFgFilter
        } else {
            nativeWidget.attributes.remove("cssFilter")
        }
        if (style.visibility == CssVisibility.HIDDEN || style.visibility == CssVisibility.COLLAPSE) {
            nativeWidget.attributes["visibilityHidden"] = true
        } else if (lastComponentStyle?.visibility != CssVisibility.HIDDEN &&
            lastComponentStyle?.visibility != CssVisibility.COLLAPSE &&
            styleSnapshot.visibility != BootstrapVisibility.INVISIBLE
        ) {
            nativeWidget.attributes.remove("visibilityHidden")
        }
        nativeWidget.attributes["computedStyle"] = style
        if (prevStyle == null ||
            prevStyle.padding != style.padding ||
            prevStyle.paddingTop != style.paddingTop ||
            prevStyle.paddingRight != style.paddingRight ||
            prevStyle.paddingBottom != style.paddingBottom ||
            prevStyle.paddingLeft != style.paddingLeft ||
            prevStyle.margin != style.margin ||
            prevStyle.marginTop != style.marginTop ||
            prevStyle.marginRight != style.marginRight ||
            prevStyle.marginBottom != style.marginBottom ||
            prevStyle.marginLeft != style.marginLeft
        ) {
            syncResolvedBoxSpacing(nativeWidget.cachedAllClassTokens)
        }
        syncResolvedLayoutAndInteractionAttributes()
        notifyStyleSyncedIfReady()
        notifyTreeInvalidated()
    }

    private fun syncResolvedBoxSpacing(tokens: Set<String>) {
        val styledPad = (nativeWidget.attributes["styledButtonVisuals"] as? com.nexustools.genericui.style.css.StyledVisualSpec)?.padding
            ?: (nativeWidget.attributes["styledComponentVisuals"] as? com.nexustools.genericui.style.css.StyledVisualSpec)?.padding
            ?: (nativeWidget.attributes["styledContainerVisuals"] as? com.nexustools.genericui.style.css.StyledVisualSpec)?.padding
        val resolvedPad = explicitPadding ?: styledPad ?: run {
            val pAll = styleSnapshot.paddingAll?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx(tokens, TOKENS_P)
            val pX = styleSnapshot.paddingX?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx(tokens, TOKENS_PX) ?: pAll
            val pY = styleSnapshot.paddingY?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx(tokens, TOKENS_PY) ?: pAll
            val pTop = styleSnapshot.paddingTop?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx(tokens, TOKENS_PT) ?: pY ?: 0
            val pBottom = styleSnapshot.paddingBottom?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx(tokens, TOKENS_PB) ?: pY ?: 0
            val pLeft = styleSnapshot.paddingStart?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx(tokens, TOKENS_PS) ?: pX ?: 0
            val pRight = styleSnapshot.paddingEnd?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx(tokens, TOKENS_PE) ?: pX ?: 0
            val comp = lastComponentStyle
            val sheet = lastComputedStyle
            if (comp != null || sheet != null) {
                val refW = nativeWidget.bounds.width.toFloat()
                val refH = nativeWidget.bounds.height.toFloat()
                val compBase = comp?.padding?.toEdgeInsets(refW, refH)
                val sheetBase = sheet?.padding?.toEdgeInsets(refW, refH)
                EdgeInsets(
                    top = comp?.paddingTop?.resolvePx(refH)?.roundToInt()
                        ?: compBase?.top
                        ?: sheet?.paddingTop?.resolvePx(refH)?.roundToInt()
                        ?: sheetBase?.top
                        ?: pTop,
                    right = comp?.paddingRight?.resolvePx(refW)?.roundToInt()
                        ?: compBase?.right
                        ?: sheet?.paddingRight?.resolvePx(refW)?.roundToInt()
                        ?: sheetBase?.right
                        ?: pRight,
                    bottom = comp?.paddingBottom?.resolvePx(refH)?.roundToInt()
                        ?: compBase?.bottom
                        ?: sheet?.paddingBottom?.resolvePx(refH)?.roundToInt()
                        ?: sheetBase?.bottom
                        ?: pBottom,
                    left = comp?.paddingLeft?.resolvePx(refW)?.roundToInt()
                        ?: compBase?.left
                        ?: sheet?.paddingLeft?.resolvePx(refW)?.roundToInt()
                        ?: sheetBase?.left
                        ?: pLeft
                )
            } else {
                EdgeInsets(top = pTop, right = pRight, bottom = pBottom, left = pLeft)
            }
        }
        nativeWidget.padding = resolvedPad

        val resolvedMargin = explicitMargin ?: run {
            val mAll = styleSnapshot.marginAll?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx(tokens, TOKENS_M)
            val mX = styleSnapshot.marginX?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx(tokens, TOKENS_MX) ?: mAll
            val mY = styleSnapshot.marginY?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx(tokens, TOKENS_MY) ?: mAll
            val mTop = styleSnapshot.marginTop?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx(tokens, TOKENS_MT) ?: mY ?: 0
            val mBottom = styleSnapshot.marginBottom?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx(tokens, TOKENS_MB) ?: mY ?: 0
            val mLeft = styleSnapshot.marginStart?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx(tokens, TOKENS_MS) ?: mX ?: 0
            val mRight = styleSnapshot.marginEnd?.toPixels()?.coerceAtLeast(0) ?: spacingTokenPx(tokens, TOKENS_ME) ?: mX ?: 0
            val comp = lastComponentStyle
            val sheet = lastComputedStyle
            if (comp != null || sheet != null) {
                val refW = nativeWidget.bounds.width.toFloat()
                val refH = nativeWidget.bounds.height.toFloat()
                val compBase = comp?.margin?.toEdgeInsets(refW, refH)
                val sheetBase = sheet?.margin?.toEdgeInsets(refW, refH)
                EdgeInsets(
                    top = comp?.marginTop?.resolvePx(refH)?.roundToInt()
                        ?: compBase?.top
                        ?: sheet?.marginTop?.resolvePx(refH)?.roundToInt()
                        ?: sheetBase?.top
                        ?: mTop,
                    right = comp?.marginRight?.resolvePx(refW)?.roundToInt()
                        ?: compBase?.right
                        ?: sheet?.marginRight?.resolvePx(refW)?.roundToInt()
                        ?: sheetBase?.right
                        ?: mRight,
                    bottom = comp?.marginBottom?.resolvePx(refH)?.roundToInt()
                        ?: compBase?.bottom
                        ?: sheet?.marginBottom?.resolvePx(refH)?.roundToInt()
                        ?: sheetBase?.bottom
                        ?: mBottom,
                    left = comp?.marginLeft?.resolvePx(refW)?.roundToInt()
                        ?: compBase?.left
                        ?: sheet?.marginLeft?.resolvePx(refW)?.roundToInt()
                        ?: sheetBase?.left
                        ?: mLeft
                )
            } else {
                EdgeInsets(top = mTop, right = mRight, bottom = mBottom, left = mLeft)
            }
        }
        nativeWidget.margin = resolvedMargin
    }

    open fun acceptsAestheticSkinStyles(): Boolean {
        if (isHostedInCustomStyleEngine()) return true
        if (this is DesktopStyledComponentNativeImpl<*>) return true
        val role = nativeWidget.attributes["componentRole"] as? String ?: ""
        return role == "styled-button" ||
            role == "styled-component" ||
            role == "styled-container" ||
            nativeWidget.attributes.containsKey("styledButtonVisuals") ||
            nativeWidget.attributes.containsKey("styledComponentVisuals") ||
            nativeWidget.attributes.containsKey("styledContainerVisuals")
    }

    open fun acceptsTypographyStyles(): Boolean {
        if (acceptsAestheticSkinStyles()) return true
        val cls = nativeWidget.nativeClassName.lowercase()
        val role = (nativeWidget.attributes["componentRole"] as? String ?: "").lowercase()
        return cls in TYPOGRAPHY_WIDGET_CLASSES || role in TYPOGRAPHY_WIDGET_ROLES
    }

    open fun isHostedInCustomStyleEngine(): Boolean {
        var cur: DesktopNativeWidget? = nativeWidget
        while (cur != null) {
            if (cur.attributes["customStyleEngine"] == true ||
                cur.nativeClassName.equals("WebView", ignoreCase = true) ||
                cur.attributes["componentRole"] == "webview"
            ) {
                return true
            }
            cur = cur.parentWidget
        }
        return false
    }

    open fun acceptsBootstrapSemanticColors(): Boolean {
        if (isHostedInCustomStyleEngine()) return true
        if (this is ButtonNativeImpl<*>) return true
        val tagName = (nativeWidget.attributes["tagName"] as? String)
            ?: (nativeWidget.attributes["data-genericui-tag"] as? String)
            ?: nativeWidget.nativeClassName
        return AestheticStylePolicy.isBootstrapSpecificNonControlTagOrPrefix(tagName, componentClassPrefix) ||
            AestheticStylePolicy.isStandardButtonTagOrPrefix(tagName, componentClassPrefix)
    }

    override fun setBackgroundGradient(gradient: Gradient?) {
        if (backgroundGradientState != gradient || nativeWidget.attributes["backgroundGradient"] != gradient) {
            backgroundGradientState = gradient
            nativeWidget.attributes["backgroundGradient"] = gradient
            notifyTreeInvalidated()
        }
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
        val effective = focused && nativeWidget.enabled && focusableState && nativeWidget.isEffectivelyFocusable()
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

    fun resolveEffectiveStyle(): ComputedStyle? {
        val base = lastComputedStyle ?: return null
        val hov = hoveredState
        val foc = focusedState
        val act = activePressedState
        val ena = nativeWidget.enabled
        if (base === cachedEffectiveBase &&
            hov == cachedEffectiveHovered &&
            foc == cachedEffectiveFocused &&
            act == cachedEffectiveActive &&
            ena == cachedEffectiveEnabled
        ) {
            return cachedEffectiveResult
        }
        val resolved = base.resolveForState(
            hovered = hov,
            focused = foc,
            active = act,
            enabled = ena
        )
        cachedEffectiveBase = base
        cachedEffectiveHovered = hov
        cachedEffectiveFocused = foc
        cachedEffectiveActive = act
        cachedEffectiveEnabled = ena
        cachedEffectiveResult = resolved
        return resolved
    }

    fun resolveSemanticBootstrapColor(darkTheme: Boolean = false): RgbaColor? {
        if (!acceptsBootstrapSemanticColors()) return null
        val semanticType = styleSnapshot.type?.takeIf { it.isSemantic }
            ?: styleSnapshot.backgroundType?.takeIf { it.isSemantic }
            ?: return null
        return BootstrapColorPalette.resolveColor(semanticType, darkTheme)
    }

    fun resolveSemanticBootstrapForeground(darkTheme: Boolean = false): RgbaColor? {
        if (!acceptsBootstrapSemanticColors()) return null
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

    override fun supportsNativeTransitions(): Boolean = false

    override fun configureNativeTransitions(specs: List<CssTransitionSpec>) {
        nativeWidget.attributes["nativeTransitionSpecs"] = specs
    }

    override fun runNativeTransition(
        property: String,
        fromValue: String?,
        toValue: String,
        spec: CssTransitionSpec,
        onComplete: () -> Unit
    ): Boolean = false

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
        private val SPACING_SCALE_PX = intArrayOf(0, 4, 8, 16, 24, 48)
        private fun spacingTokens(prefix: String): Array<String> = Array(6) { "$prefix-$it" }
        private val TOKENS_P = spacingTokens("p")
        private val TOKENS_PX = spacingTokens("px")
        private val TOKENS_PY = spacingTokens("py")
        private val TOKENS_PT = spacingTokens("pt")
        private val TOKENS_PB = spacingTokens("pb")
        private val TOKENS_PS = spacingTokens("ps")
        private val TOKENS_PE = spacingTokens("pe")
        private val TOKENS_M = spacingTokens("m")
        private val TOKENS_MX = spacingTokens("mx")
        private val TOKENS_MY = spacingTokens("my")
        private val TOKENS_MT = spacingTokens("mt")
        private val TOKENS_MB = spacingTokens("mb")
        private val TOKENS_MS = spacingTokens("ms")
        private val TOKENS_ME = spacingTokens("me")
        private val TOKENS_GAP = spacingTokens("gap")
        private val TOKENS_G = spacingTokens("g")
        private val TOKENS_ROW_GAP = spacingTokens("row-gap")
        private val TOKENS_GY = spacingTokens("gy")
        private val TOKENS_COLUMN_GAP = spacingTokens("column-gap")
        private val TOKENS_GX = spacingTokens("gx")

        private fun spacingTokenPx(tokens: Set<String>, candidates: Array<String>): Int? {
            if (tokens.isEmpty()) return null
            for (scale in 0..5) {
                if (candidates[scale] in tokens) return SPACING_SCALE_PX[scale]
            }
            return null
        }

        private val TYPOGRAPHY_WIDGET_CLASSES = setOf(
            "label",
            "text",
            "heading",
            "badge",
            "blockquote",
            "codeblock",
            "code",
            "markdown",
            "markdownview"
        )
        private val TYPOGRAPHY_WIDGET_ROLES = setOf(
            "label",
            "text",
            "heading",
            "badge",
            "blockquote",
            "code-block",
            "markdown-view"
        )
    }
}
