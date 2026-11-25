@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledButtonNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapSize
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.ComputedStyle
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.mapper.BootstrapStyleSnapshot
import com.nexustools.genericui.style.transition.CssTransitionSpec
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSCompositingOperation
import platform.AppKit.NSControl
import platform.AppKit.NSEvent
import platform.AppKit.NSFontAttributeName
import platform.AppKit.NSTextAlignmentCenter
import platform.AppKit.NSView
import platform.AppKit.drawInRect
import platform.AppKit.size
import platform.CoreGraphics.CGPoint
import platform.Foundation.NSAttributedString
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect
import platform.Foundation.create
import kotlin.math.ceil
import kotlin.math.roundToInt

internal class MacosStyledButtonControl(
    private val owner: MacosStyledButtonNativeImpl,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 96.0, 34.0)
) : NSControl(frameRect) {

    val surfaceSupport = MacosSurfaceLayerSupport(this)
    private val interactionController = MacosViewInteractionController(owner, this) { owner.hasWheelListener() }
    val foregroundView = MacosForegroundDrawView({ drawForeground() })

    init {
        setWantsLayer(true)
        addSubview(foregroundView)
        interactionController.onVisualStateChanged = {
            owner.setHoveredState(interactionController.isHovered)
        }
    }

    override fun setFrame(frame: CValue<NSRect>) {
        super.setFrame(frame)
        foregroundView.syncToHost(this)
    }

    override fun viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        surfaceSupport.refreshBackdropUnderlayIfNeeded(force = true)
        if (owner.nativeWidget.attributes["cursor"] != null || owner.nativeWidget.attributes["customCursor"] != null) {
            window?.invalidateCursorRectsForView(this)
        }
    }

    override fun layout() {
        super.layout()
        surfaceSupport.refreshBackdropUnderlayIfNeeded(force = false)
        foregroundView.syncToHost(this)
    }

    override fun isFlipped(): Boolean = true

    override fun acceptsFirstMouse(event: NSEvent?): Boolean = true

    override fun hitTest(point: CValue<CGPoint>): NSView? {
        if (isHidden() || alphaValue <= 0.004 || owner.nativeWidget.isSelfPointerEventsNone()) {
            return null
        }
        return super.hitTest(point)
    }

    override fun updateTrackingAreas() {
        super.updateTrackingAreas()
        interactionController.refreshTrackingAreas()
    }

    override fun resetCursorRects() {
        interactionController.applyCursorRects()
    }

    override fun cursorUpdate(event: NSEvent) {
        interactionController.handleCursorUpdate(event)
    }

    override fun mouseDown(event: NSEvent) {
        if (!owner.nativeWidget.enabled || owner.isPointerInteractionDisabled()) return
        owner.setActivePressedState(true)
        interactionController.handleMouseDown(event, buttonIndex = 0)
    }

    override fun mouseDragged(event: NSEvent) {
        interactionController.handleMouseDragged(event, buttonIndex = 0)
    }

    override fun mouseUp(event: NSEvent) {
        owner.setActivePressedState(false)
        interactionController.handleMouseUp(event, buttonIndex = 0)
    }

    override fun rightMouseDown(event: NSEvent) {
        interactionController.handleRightMouseDown(event)
    }

    override fun mouseEntered(event: NSEvent) {
        interactionController.handleMouseEntered(event)
    }

    override fun mouseExited(event: NSEvent) {
        interactionController.handleMouseExited(event)
    }

    override fun mouseMoved(event: NSEvent) {
        interactionController.handleMouseMoved(event)
    }

    override fun scrollWheel(event: NSEvent) {
        if (!interactionController.handleScrollWheel(event)) {
            super.scrollWheel(event)
        }
    }

    private fun drawForeground() {
        val text = owner.getText()
        val iconSpec = owner.getIcon()
        val resolved = owner.resolveCurrentVisuals()
        val darkTheme = MacosAppKitAppearance.isDarkTheme(owner.nativeWidget)
        var textColor = resolved.textColor ?: if (darkTheme) RgbaColor.WHITE else RgbaColor(24, 24, 27, 255)
        if (!owner.nativeWidget.enabled) {
            textColor = textColor.copy(a = (textColor.a * 0.55f).roundToInt().coerceIn(0, 255))
        }

        val font = MacosAppKitAppearance.resolveFont(
            style = owner.lastComputedStyleSnapshot(),
            snapshot = owner.currentStyleSnapshot,
            defaultSizePt = 13.0,
            defaultBold = false
        )
        val attrStr = MacosAppKitAppearance.buildAttributedString(
            text = text,
            style = owner.lastComputedStyleSnapshot(),
            textColor = textColor,
            defaultFont = font,
            alignment = NSTextAlignmentCenter
        )

        val (bw, bh) = bounds.useContents { size.width to size.height }
        val iconPx = when (owner.currentStyleSnapshot.size) {
            BootstrapSize.SM -> 14
            BootstrapSize.LG -> 18
            else -> 16
        }
        val iconImg = if (!iconSpec.isNullOrBlank()) {
            MacosAppKitAppearance.resolveNsImage(iconSpec, desiredPx = iconPx, tintColor = textColor)
        } else {
            null
        }

        val textSize = if (text.isNotEmpty()) attrStr.size().useContents { width to height } else 0.0 to 0.0
        val gap = if (iconImg != null && text.isNotEmpty()) 6.0 else 0.0
        val iconW = if (iconImg != null) iconPx.toDouble() else 0.0
        val totalW = iconW + gap + textSize.first
        val compactWidth = bw <= 20.0
        val compactHeight = bh <= 20.0
        val horizMargin = if (compactWidth) (bw * 0.05).coerceAtLeast(0.0) else 4.0
        val startX = ((bw - totalW) * 0.5).coerceAtLeast(horizMargin)

        if (iconImg != null) {
            val minIconY = if (compactHeight) (bh * 0.05).coerceAtLeast(0.0) else 2.0
            val iconY = ((bh - iconPx.toDouble()) * 0.5).coerceAtLeast(minIconY)
            iconImg.drawInRect(
                dstSpacePortionRect = NSMakeRect(startX, iconY, iconPx.toDouble(), iconPx.toDouble()),
                fromRect = NSMakeRect(0.0, 0.0, 0.0, 0.0),
                operation = NSCompositingOperation.NSCompositingOperationSourceOver,
                fraction = 1.0,
                respectFlipped = true,
                hints = null
            )
        }
        if (text.isNotEmpty()) {
            val textX = startX + iconW + gap
            val minTextY = if (compactHeight) 0.0 else 1.0
            val textY = ((bh - textSize.second) * 0.5).coerceAtLeast(minTextY)
            val availW = (bw - textX - horizMargin).coerceAtLeast(0.0)
            val drawH = if (compactHeight) (bh - textY).coerceAtLeast(textSize.second) else (textSize.second + 2.0)
            attrStr.drawInRect(NSMakeRect(textX, textY, availW, drawH))
        }
    }
}

/**
 * macOS AppKit retained [NSControl] implementation of [DesktopStyledButtonNativeImpl].
 */
class MacosStyledButtonNativeImpl(
    nativeWidget: MacosViewHandle,
    classPrefix: String = "styled-btn"
) : DesktopStyledButtonNativeImpl<MacosViewHandle>(nativeWidget, classPrefix) {

    private var buttonControl: MacosStyledButtonControl? = null

    internal fun hasWheelListener(): Boolean = onWheelListener != null
    private var lastVisualBoundsW: Int = -1
    private var lastVisualBoundsH: Int = -1
    private var cachedMeasureText: String? = null
    private var cachedMeasureIcon: String? = null
    private var cachedMeasureComputed: ComputedStyle? = null
    private var cachedMeasureSnapshot: com.nexustools.genericui.style.mapper.BootstrapStyleSnapshot? = null
    private var cachedMeasureWidthPx: Int? = null
    private var cachedMeasureHeightPx: Int? = null
    private var cachedMeasureMinWidthPx: Int? = null
    private var cachedMeasureMinHeightPx: Int? = null
    private var cachedMeasurePadding: com.nexustools.genericui.geometry.EdgeInsets? = null
    private var cachedMeasureSize: Size? = null

    init {
        nativeWidget.viewMaterializer = {
            ensureButtonControl().also { syncMacosStyledButtonVisuals() }
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureButtonControl()
            syncMacosStyledButtonVisuals()
        }
    }

    internal val currentStyleSnapshot: BootstrapStyleSnapshot
        get() = styleSnapshot

    internal fun lastComputedStyleSnapshot(): ComputedStyle? = lastComputedStyle

    private fun ensureButtonControl(): MacosStyledButtonControl {
        buttonControl?.let { existing ->
            if (nativeWidget.nsView == null) nativeWidget.nsView = existing
            return existing
        }
        val ctrl = MacosStyledButtonControl(this)
        buttonControl = ctrl
        nativeWidget.nsView = ctrl
        ctrl.setEnabled(nativeWidget.enabled)
        ctrl.setHidden(!nativeWidget.visible)
        ctrl.setToolTip(nativeWidget.tooltipText)
        return ctrl
    }

    private fun syncMacosStyledButtonVisuals() {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { syncMacosStyledButtonVisuals() }
            return
        }
        val ctrl = ensureButtonControl()
        lastVisualBoundsW = nativeWidget.bounds.width
        lastVisualBoundsH = nativeWidget.bounds.height
        val isDark = MacosAppKitAppearance.isDarkTheme(nativeWidget)
        val resolved = resolveCurrentVisuals()
        val computed = lastComputedStyle

        ctrl.setEnabled(nativeWidget.enabled)
        ctrl.setAlphaValue(getOpacity().coerceIn(0f, 1f).toDouble())

        ctrl.surfaceSupport.applyStyledVisuals(
            spec = resolved,
            cornerRadii = computed?.borderRadius,
            boxShadow = computed?.boxShadowList?.shadows?.firstOrNull(),
            isFocused = isFocusedState(),
            clipOverflow = false,
            darkTheme = isDark,
            effectiveBackdropFilter = nativeWidget.attributes["backdropFilter"] as? CssFilter,
            effectiveForegroundFilter = nativeWidget.attributes["cssFilter"] as? CssFilter
        )
        MacosAppKitDispatch.syncChildViewHierarchy(
            parentView = ctrl,
            orderedChildViews = listOf(ctrl.foregroundView),
            pinnedBackViews = ctrl.surfaceSupport.pinnedBackdropSubviews()
        )
        ctrl.foregroundView.syncToHost(ctrl, forceRedraw = true)
        ctrl.setNeedsDisplay(true)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val text = getText()
        val icon = getIcon()
        val resolved = resolveCurrentVisuals()
        val cached = cachedMeasureSize
        if (cached != null &&
            cachedMeasureText == text &&
            cachedMeasureIcon == icon &&
            cachedMeasureComputed === lastComputedStyle &&
            cachedMeasureSnapshot == styleSnapshot &&
            cachedMeasureWidthPx == resolved.widthPx &&
            cachedMeasureHeightPx == resolved.heightPx &&
            cachedMeasureMinWidthPx == resolved.minWidthPx &&
            cachedMeasureMinHeightPx == resolved.minHeightPx &&
            cachedMeasurePadding == resolved.padding
        ) {
            return cached
        }
        val explicitW = resolved.widthPx?.coerceAtLeast(resolved.minWidthPx ?: 0)
        val explicitH = resolved.heightPx?.coerceAtLeast(resolved.minHeightPx ?: 0)
        val hasIcon = !icon.isNullOrBlank()
        val pad = resolved.padding ?: getEffectivePaddingInsets(
            referenceWidthPx = maxWidth ?: nativeWidget.bounds.width.toFloat(),
            referenceHeightPx = maxHeight ?: nativeWidget.bounds.height.toFloat()
        )
        val measured = if (explicitW != null && explicitH != null) {
            Size(explicitW, explicitH)
        } else if (text.isEmpty() && !hasIcon) {
            Size(
                explicitW ?: (pad.left + pad.right).coerceAtLeast(resolved.minWidthPx ?: 0),
                explicitH ?: (pad.top + pad.bottom).coerceAtLeast(resolved.minHeightPx ?: 0)
            )
        } else {
            val font = MacosAppKitAppearance.resolveFont(lastComputedStyle, styleSnapshot, 13.0, false)
            val textW = if (text.isNotEmpty()) {
                val attrs = mapOf<Any?, Any?>(NSFontAttributeName to font)
                NSAttributedString.create(text, attrs).size().useContents { ceil(width).toInt() }
            } else {
                0
            }
            val iconW = if (hasIcon) 20 else 0
            val padX = (pad.left + pad.right).coerceAtLeast(16)
            val padY = (pad.top + pad.bottom).coerceAtLeast(10)
            Size(
                explicitW ?: (textW + iconW + padX).coerceAtLeast(resolved.minWidthPx ?: 36),
                explicitH ?: (18 + padY).coerceAtLeast(resolved.minHeightPx ?: 28)
            )
        }
        cachedMeasureText = text
        cachedMeasureIcon = icon
        cachedMeasureComputed = lastComputedStyle
        cachedMeasureSnapshot = styleSnapshot
        cachedMeasureWidthPx = resolved.widthPx
        cachedMeasureHeightPx = resolved.heightPx
        cachedMeasureMinWidthPx = resolved.minWidthPx
        cachedMeasureMinHeightPx = resolved.minHeightPx
        cachedMeasurePadding = resolved.padding
        cachedMeasureSize = measured
        return measured
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { onNativeBoundsChanged(nativeWidget.bounds) }
            return
        }
        val ctrl = ensureButtonControl()
        MacosAppKitDispatch.applyFrameAndTransform(ctrl, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
        if (rect.width != lastVisualBoundsW || rect.height != lastVisualBoundsH) {
            syncMacosStyledButtonVisuals()
        }
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        MacosAppKitDispatch.onMainOrDefer {
            buttonControl?.setHidden(!visible)
            if (visible) {
                buttonControl?.surfaceSupport?.refreshBackdropUnderlayIfNeeded(force = true)
            }
        }
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        syncMacosStyledButtonVisuals()
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        MacosAppKitDispatch.onMainOrDefer {
            buttonControl?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
        }
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        buttonControl?.setToolTip(tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        buttonControl?.let { v -> v.window?.invalidateCursorRectsForView(v) }
    }

    override fun onNativeZIndexChanged(zIndex: Int) {
        (nativeWidget.parentWidget?.attributes?.get("nativeImpl") as? DesktopBaseContainerNativeImpl<*>)
            ?.onChildZIndexInvalidated()
        nativeWidget.requestRetainedRelayout()
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val ctrl = buttonControl ?: return
        MacosAppKitDispatch.applyFrameAndTransform(ctrl, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
    }

    override fun onNativeStyleSynced() {
        cachedMeasureSize = null
        syncMacosStyledButtonVisuals()
    }

    override fun setText(text: String) {
        super.setText(text)
        cachedMeasureSize = null
        syncMacosStyledButtonVisuals()
    }

    override fun setIcon(icon: String?) {
        super.setIcon(icon)
        cachedMeasureSize = null
        syncMacosStyledButtonVisuals()
    }

    override fun setAttribute(name: String, value: String?) {
        super.setAttribute(name, value)
        if (name == "backdrop-filter" || name == "backdropFilter") {
            val parsed = CssFilter.parse(value)
            setBaseVisuals(
                getBaseVisuals().copy(
                    backdropBlurPx = parsed?.blur?.resolvePx(),
                    backdropFilter = parsed
                )
            )
        } else if (name == "backdropBlurPx" || name == "data-backdrop-blur-px") {
            val px = value?.trim()?.removeSuffix("px")?.toFloatOrNull()
            val cur = getBaseVisuals()
            val nextBf = cur.backdropFilter?.copy(
                blur = px?.takeIf { it > 0f }?.let { com.nexustools.genericui.style.css.CssLength.px(it) }
            )
            setBaseVisuals(cur.copy(backdropBlurPx = px, backdropFilter = nextBf))
        } else if (name == "filter") {
            val parsed = CssFilter.parse(value)
            setBaseVisuals(getBaseVisuals().copy(filter = parsed))
        }
    }

    override fun runNativeTransition(
        property: String,
        fromValue: String?,
        toValue: String,
        spec: CssTransitionSpec,
        onComplete: () -> Unit
    ): Boolean {
        val handled = super.runNativeTransition(property, fromValue, toValue, spec, onComplete)
        if (property.equals("opacity", ignoreCase = true)) {
            val fromAlpha = (fromValue?.toFloatOrNull() ?: getOpacity()).coerceIn(0f, 1f)
            val targetAlpha = (toValue.toFloatOrNull() ?: getOpacity()).coerceIn(0f, 1f)
            nativeWidget.attributes["transitionFromOpacity"] = fromAlpha
            nativeWidget.attributes["transitionToOpacity"] = targetAlpha
            setOpacity(targetAlpha)
            if (spec.durationMs <= 0f) {
                onComplete()
            }
            return true
        }
        return handled
    }

    override fun dispose() {
        val ctrl = buttonControl
        buttonControl = null
        MacosAppKitDispatch.detachViewOnMain(ctrl)
        super.dispose()
    }
}
