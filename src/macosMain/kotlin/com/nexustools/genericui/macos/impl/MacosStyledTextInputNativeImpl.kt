@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.macos.impl

import com.nexustools.genericui.apple.style.AppleStyledSurfaceHelper
import com.nexustools.genericui.desktop.core.DesktopBaseContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledTextInputNativeImpl
import com.nexustools.genericui.event.InputFocusManager
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.macos.MacosAppKitAppearance
import com.nexustools.genericui.macos.MacosAppKitDispatch
import com.nexustools.genericui.macos.MacosControlActionTarget
import com.nexustools.genericui.macos.MacosFocusSupport
import com.nexustools.genericui.macos.MacosSurfaceLayerSupport
import com.nexustools.genericui.macos.MacosViewHandle
import com.nexustools.genericui.macos.MacosViewInteractionController
import com.nexustools.genericui.macos.isWithinOverlayTitleBarNonControlArea
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.transition.CssTransitionSpec
import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import platform.AppKit.NSControl
import platform.AppKit.NSEvent
import platform.AppKit.NSFocusRingTypeNone
import platform.AppKit.NSFont
import platform.AppKit.NSFontAttributeName
import platform.AppKit.NSFontWeightBold
import platform.AppKit.NSFontWeightLight
import platform.AppKit.NSFontWeightMedium
import platform.AppKit.NSFontWeightRegular
import platform.AppKit.NSFontWeightSemibold
import platform.AppKit.NSFontWeightThin
import platform.AppKit.NSForegroundColorAttributeName
import platform.AppKit.NSItalicFontMask
import platform.AppKit.NSFontManager
import platform.AppKit.NSLineBreakByClipping
import platform.AppKit.NSNoBorder
import platform.AppKit.NSScrollView
import platform.AppKit.NSSecureTextField
import platform.AppKit.NSTextField
import platform.AppKit.NSTextFieldCell
import platform.AppKit.NSTextView
import platform.AppKit.NSView
import platform.AppKit.cell
import platform.AppKit.labelWithString
import platform.AppKit.setInsertionPointColor
import platform.CoreGraphics.CGPoint
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSAttributedString
import platform.Foundation.NSMakeRect
import platform.Foundation.NSRect
import platform.Foundation.create
import kotlin.math.ceil
import kotlin.math.roundToInt

private class MacosPassthroughLabelField(
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 0.0, 0.0)
) : NSTextField(frameRect) {
    init {
        setEditable(false)
        setSelectable(false)
        setBezeled(false)
        setBordered(false)
        setDrawsBackground(false)
        setFocusRingType(NSFocusRingTypeNone)
    }

    override fun hitTest(point: CValue<CGPoint>): NSView? = null
}

private class MacosStyledSingleLineField(
    private val owner: MacosStyledTextInputNativeImpl,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 200.0, 28.0)
) : NSTextField(frameRect) {
    override fun becomeFirstResponder(): Boolean {
        if (!owner.nativeWidget.isEffectivelyFocusable()) return false
        val ok = super.becomeFirstResponder()
        if (ok) {
            InputFocusManager.isNativeTextInputFocused = true
            owner.setFocusedState(true)
        }
        return ok
    }
}

private class MacosStyledSecureInputField(
    private val owner: MacosStyledTextInputNativeImpl,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 200.0, 28.0)
) : NSSecureTextField(frameRect) {
    override fun becomeFirstResponder(): Boolean {
        if (!owner.nativeWidget.isEffectivelyFocusable()) return false
        val ok = super.becomeFirstResponder()
        if (ok) {
            InputFocusManager.isNativeTextInputFocused = true
            owner.setFocusedState(true)
        }
        return ok
    }
}

internal class MacosStyledTextInputHostView(
    private val owner: MacosStyledTextInputNativeImpl,
    frameRect: CValue<NSRect> = NSMakeRect(0.0, 0.0, 200.0, 34.0)
) : NSView(frameRect) {

    val surfaceSupport = MacosSurfaceLayerSupport(this)
    private val interactionController = MacosViewInteractionController(owner, this) { owner.hasWheelListener() }

    init {
        setWantsLayer(true)
        interactionController.onVisualStateChanged = {
            owner.setHoveredState(interactionController.isHovered)
        }
    }

    override fun isFlipped(): Boolean = true

    override fun acceptsFirstMouse(event: NSEvent?): Boolean = true

    override fun mouseDownCanMoveWindow(): Boolean = isWithinOverlayTitleBarNonControlArea(this)

    override fun hitTest(point: CValue<CGPoint>): NSView? {
        if (isHidden() || alphaValue <= 0.004 || owner.nativeWidget.isSelfPointerEventsNone()) {
            return null
        }
        return super.hitTest(point)
    }

    override fun setFrame(frame: CValue<NSRect>) {
        super.setFrame(frame)
        owner.layoutInnerViews()
    }

    override fun layout() {
        super.layout()
        surfaceSupport.refreshBackdropUnderlayIfNeeded(force = false)
        owner.layoutInnerViews()
    }

    override fun viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        surfaceSupport.refreshBackdropUnderlayIfNeeded(force = true)
        if (owner.nativeWidget.attributes["cursor"] != null || owner.nativeWidget.attributes["customCursor"] != null) {
            window?.invalidateCursorRectsForView(this)
        }
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
        owner.focusEditorFromShellClick()
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
}

/**
 * macOS AppKit retained implementation of [DesktopStyledTextInputNativeImpl] combining
 * [MacosSurfaceLayerSupport] (pill radii, gradients, borders, box shadows, focus rings, and
 * backdrop blur) with borderless, transparent [NSTextField], [NSSecureTextField], or
 * [NSScrollView] + [NSTextView] text editing.
 */
class MacosStyledTextInputNativeImpl(
    nativeWidget: MacosViewHandle,
    multiline: Boolean = false,
    classPrefix: String = if (multiline) "styled-textarea" else "styled-textfield"
) : DesktopStyledTextInputNativeImpl<MacosViewHandle>(nativeWidget, multiline, classPrefix) {

    private var hostView: MacosStyledTextInputHostView? = null
    private var captionLabel: NSTextField? = null
    private var singleLineField: NSTextField? = null
    private var secureField: NSSecureTextField? = null
    private var scrollView: NSScrollView? = null
    private var multilineTextView: NSTextView? = null
    private var multilinePlaceholderLabel: MacosPassthroughLabelField? = null

    private var suppressCallbacks: Boolean = false
    private var activeVariantKey: String = ""
    private var activeHasCaptionLabel: Boolean = false
    private var lastAppliedVisuals: StyledVisualSpec = StyledVisualSpec()
    private var lastVisualBoundsW: Int = -1
    private var lastVisualBoundsH: Int = -1

    internal fun hasWheelListener(): Boolean = onWheelListener != null

    private val fieldDelegate = MacosTextFieldBridgeDelegate(
        onTextChanged = { text ->
            if (!suppressCallbacks) {
                super.setValue(text)
            }
        },
        onBeginEditing = {
            if (nativeWidget.isEffectivelyFocusable()) {
                InputFocusManager.isNativeTextInputFocused = true
                setFocusedState(true)
            } else {
                MacosFocusSupport.resignIfInside(hostView)
            }
        },
        onEndEditing = { text ->
            if (!suppressCallbacks) {
                super.setValue(text)
                submitValue()
            }
            InputFocusManager.isNativeTextInputFocused = false
            setFocusedState(false)
        }
    )

    private val textViewDelegate = MacosTextViewBridgeDelegate(
        onTextChanged = { text ->
            if (!suppressCallbacks) {
                super.setValue(text)
                syncMultilinePlaceholderVisibility()
            }
        },
        onBeginEditing = {
            if (nativeWidget.isEffectivelyFocusable()) {
                InputFocusManager.isNativeTextInputFocused = true
                setFocusedState(true)
            } else {
                MacosFocusSupport.resignIfInside(hostView)
            }
        },
        onEndEditing = { text ->
            if (!suppressCallbacks) {
                super.setValue(text)
                syncMultilinePlaceholderVisibility()
            }
            InputFocusManager.isNativeTextInputFocused = false
            setFocusedState(false)
        }
    )

    private val fieldSubmitAction = MacosControlActionTarget {
        if (!suppressCallbacks) {
            submitValue()
        }
    }

    init {
        nativeWidget.viewMaterializer = {
            ensureHostView().also { applyStyledVisuals(resolveCurrentVisuals()) }
        }
        if (MacosAppKitDispatch.isMainThread()) {
            ensureHostView()
            applyStyledVisuals(resolveCurrentVisuals())
        }
    }

    private fun resolveVariantKey(): String = when {
        isMultiline() -> "multiline"
        isPasswordMode() -> "password"
        else -> "text"
    }

    private fun ensureHostView(): MacosStyledTextInputHostView {
        val host = hostView ?: MacosStyledTextInputHostView(this).also { created ->
            hostView = created
            nativeWidget.nsView = created
        }
        if (nativeWidget.nsView == null) {
            nativeWidget.nsView = host
        }
        ensureInnerViews(host, forceRebuild = false)
        return host
    }

    private fun clearSubReferences() {
        singleLineField?.setDelegate(null)
        secureField?.setDelegate(null)
        multilineTextView?.setDelegate(null)
        captionLabel?.removeFromSuperview()
        singleLineField?.removeFromSuperview()
        secureField?.removeFromSuperview()
        scrollView?.removeFromSuperview()
        multilinePlaceholderLabel?.removeFromSuperview()
        captionLabel = null
        singleLineField = null
        secureField = null
        scrollView = null
        multilineTextView = null
        multilinePlaceholderLabel = null
    }

    private fun ensureInnerViews(host: MacosStyledTextInputHostView, forceRebuild: Boolean) {
        val nextKey = resolveVariantKey()
        val label = getLabel()
        val wantLabel = !label.isNullOrBlank()
        if (!forceRebuild &&
            nextKey == activeVariantKey &&
            wantLabel == activeHasCaptionLabel &&
            (singleLineField != null || secureField != null || scrollView != null)
        ) {
            syncCaptionText(label)
            return
        }

        clearSubReferences()
        activeVariantKey = nextKey
        activeHasCaptionLabel = wantLabel

        if (wantLabel && label != null) {
            captionLabel = NSTextField.labelWithString(label).apply {
                setFont(NSFont.systemFontOfSize(11.0, weight = NSFontWeightMedium))
                setDrawsBackground(false)
                setBezeled(false)
            }
        }

        when (nextKey) {
            "multiline" -> {
                val sv = NSScrollView(frame = NSMakeRect(0.0, 0.0, 220.0, 88.0)).apply {
                    setHasVerticalScroller(true)
                    setHasHorizontalScroller(false)
                    setAutohidesScrollers(true)
                    setDrawsBackground(false)
                    setBorderType(NSNoBorder)
                }
                val tv = MacosFocusableTextView.create(nativeWidget, NSMakeRect(0.0, 0.0, 220.0, 88.0)).apply {
                    setDrawsBackground(false)
                    setVerticallyResizable(true)
                    setHorizontallyResizable(false)
                    setDelegate(textViewDelegate)
                }
                sv.setDocumentView(tv)
                scrollView = sv
                multilineTextView = tv
                multilinePlaceholderLabel = MacosPassthroughLabelField(NSMakeRect(0.0, 0.0, 200.0, 20.0))
            }
            "password" -> {
                secureField = MacosStyledSecureInputField(this, NSMakeRect(0.0, 0.0, 200.0, 28.0)).apply {
                    setBezeled(false)
                    setBordered(false)
                    setDrawsBackground(false)
                    setFocusRingType(NSFocusRingTypeNone)
                    setUsesSingleLineMode(true)
                    setLineBreakMode(NSLineBreakByClipping)
                    (cell() as? NSTextFieldCell)?.setScrollable(true)
                    setDelegate(fieldDelegate)
                    setTarget(fieldSubmitAction)
                    setAction(fieldSubmitAction.actionSelector)
                }
            }
            else -> {
                singleLineField = MacosStyledSingleLineField(this, NSMakeRect(0.0, 0.0, 200.0, 28.0)).apply {
                    setBezeled(false)
                    setBordered(false)
                    setDrawsBackground(false)
                    setFocusRingType(NSFocusRingTypeNone)
                    setUsesSingleLineMode(true)
                    setLineBreakMode(NSLineBreakByClipping)
                    (cell() as? NSTextFieldCell)?.setScrollable(true)
                    setDelegate(fieldDelegate)
                    setTarget(fieldSubmitAction)
                    setAction(fieldSubmitAction.actionSelector)
                }
            }
        }

        syncHostChildren(host)
        layoutInnerViews()
        syncControlValues()
    }

    private fun syncHostChildren(host: MacosStyledTextInputHostView) {
        val children = ArrayList<NSView>(3)
        captionLabel?.let { children.add(it) }
        singleLineField?.let { children.add(it) }
        secureField?.let { children.add(it) }
        scrollView?.let { children.add(it) }
        multilinePlaceholderLabel?.let { children.add(it) }
        MacosAppKitDispatch.syncChildViewHierarchy(
            parentView = host,
            orderedChildViews = children,
            pinnedBackViews = host.surfaceSupport.pinnedBackdropSubviews()
        )
    }

    internal fun layoutInnerViews() {
        val host = hostView ?: return
        val (w, h) = host.bounds.useContents { size.width to size.height }
        if (w <= 0.0 || h <= 0.0) return

        val visuals = lastAppliedVisuals
        val pad = visuals.padding
            ?: lastComputedStyle?.padding?.toEdgeInsets()
            ?: DEFAULT_INPUT_PADDING

        var contentTop = 0.0
        captionLabel?.let { cap ->
            val capH = 18.0.coerceAtMost(h * 0.45)
            cap.setFrame(CGRectMake(0.0, 0.0, w, capH))
            contentTop = capH + 2.0
        }

        val left = pad.left.coerceAtLeast(0).toDouble()
        val top = contentTop + pad.top.coerceAtLeast(0).toDouble()
        val right = pad.right.coerceAtLeast(0).toDouble()
        val bottom = pad.bottom.coerceAtLeast(0).toDouble()
        val innerW = (w - left - right).coerceAtLeast(8.0)
        val innerH = (h - top - bottom).coerceAtLeast(16.0)

        (singleLineField ?: secureField)?.let { field ->
            val fontLineH = (field.font?.let { ceil(it.ascender - it.descender + 4.0) } ?: 20.0)
                .coerceIn(16.0, innerH)
            val y = top + ((innerH - fontLineH) * 0.5).coerceAtLeast(0.0)
            field.setFrame(CGRectMake(left, y, innerW, fontLineH))
        }

        scrollView?.let { sv ->
            sv.setFrame(CGRectMake(left, top, innerW, innerH))
            multilineTextView?.setFrame(CGRectMake(0.0, 0.0, innerW, innerH))
            multilinePlaceholderLabel?.setFrame(CGRectMake(left + 4.0, top, (innerW - 8.0).coerceAtLeast(4.0), 22.0.coerceAtMost(innerH)))
        }
    }

    private fun syncCaptionText(label: String?) {
        captionLabel?.setStringValue(label ?: "")
    }

    private fun resolveEditorFont(visuals: StyledVisualSpec): NSFont {
        val base = MacosAppKitAppearance.resolveFont(lastComputedStyle, styleSnapshot, 13.0, false)
        val px = visuals.fontSizePx?.takeIf { it > 0f }?.toDouble() ?: lastComputedStyle?.fontSizePx?.toDouble() ?: base.pointSize
        val numericWeight = visuals.fontWeightNumeric ?: lastComputedStyle?.fontWeightNumeric
        val weightFloat = when {
            numericWeight != null && numericWeight >= 700 -> NSFontWeightBold
            numericWeight != null && numericWeight >= 600 -> NSFontWeightSemibold
            numericWeight != null && numericWeight >= 500 -> NSFontWeightMedium
            numericWeight != null && numericWeight <= 200 -> NSFontWeightThin
            numericWeight != null && numericWeight <= 300 -> NSFontWeightLight
            else -> NSFontWeightRegular
        }
        val familyName = (visuals.fontFamily ?: lastComputedStyle?.fontFamily)
            ?.trim()
            ?.split(',')
            ?.firstOrNull()
            ?.trim()
            ?.trim('\'', '"')
        var font = if (!familyName.isNullOrEmpty() &&
            !familyName.equals("system-ui", ignoreCase = true) &&
            !familyName.equals("sans-serif", ignoreCase = true) &&
            !familyName.equals("-apple-system", ignoreCase = true)
        ) {
            if (familyName.equals("monospace", ignoreCase = true) || familyName.equals("ui-monospace", ignoreCase = true)) {
                NSFont.monospacedSystemFontOfSize(px, weight = weightFloat)
            } else {
                NSFont.fontWithName(familyName, size = px) ?: NSFont.systemFontOfSize(px, weight = weightFloat)
            }
        } else if (visuals.fontSizePx != null || visuals.fontWeightNumeric != null) {
            NSFont.systemFontOfSize(px, weight = weightFloat)
        } else {
            base
        }
        if (lastComputedStyle?.fontStyle?.cssValue?.equals("italic", ignoreCase = true) == true) {
            font = NSFontManager.sharedFontManager().convertFont(font, toHaveTrait = NSItalicFontMask)
        }
        return font
    }

    private fun syncControlValues() {
        val host = hostView ?: return
        suppressCallbacks = true
        try {
            val enabled = nativeWidget.enabled
            val editable = !isReadOnly() && enabled
            val visuals = lastAppliedVisuals
            val font = resolveEditorFont(visuals)
            val isDark = MacosAppKitAppearance.isDarkTheme(nativeWidget)
            var textRgba = visuals.textColor ?: lastComputedStyle?.textColor ?: defaultTextColor(isDark)
            if (!enabled) {
                textRgba = textRgba.copy(a = (textRgba.a * 0.55f).roundToInt().coerceIn(0, 255))
            }
            val placeholderRgba = visuals.placeholderColor ?: textRgba.copy(a = (textRgba.a * 0.52f).roundToInt().coerceIn(0, 255))
            val textNsColor = MacosAppKitAppearance.toNsColor(textRgba)
            val placeholderNsColor = MacosAppKitAppearance.toNsColor(placeholderRgba)

            (singleLineField ?: secureField)?.let { field ->
                if (field.stringValue != textValue) {
                    field.setStringValue(textValue)
                }
                field.setFont(font)
                field.setTextColor(textNsColor)
                if (placeholderText.isNotEmpty()) {
                    val attrs = mapOf<Any?, Any?>(
                        NSFontAttributeName to font,
                        NSForegroundColorAttributeName to placeholderNsColor
                    )
                    field.setPlaceholderAttributedString(NSAttributedString.create(placeholderText, attrs))
                } else {
                    field.setPlaceholderAttributedString(null)
                    field.setPlaceholderString("")
                }
                field.setEditable(editable)
                field.setEnabled(enabled)
            }

            multilineTextView?.let { tv ->
                if (tv.string != textValue) {
                    tv.setString(textValue)
                }
                tv.setFont(font)
                tv.setTextColor(textNsColor)
                tv.setInsertionPointColor(textNsColor)
                tv.setEditable(editable)
            }
            multilinePlaceholderLabel?.let { ph ->
                ph.setFont(font)
                ph.setTextColor(placeholderNsColor)
                ph.setStringValue(placeholderText)
            }
            syncMultilinePlaceholderVisibility()

            host.setHidden(!nativeWidget.visible)
            host.setAlphaValue(getOpacity().coerceIn(0f, 1f).toDouble())
            host.setToolTip(nativeWidget.tooltipText)
            MacosFocusSupport.applyFocusable(host, nativeWidget.isEffectivelyFocusable())
        } finally {
            suppressCallbacks = false
        }
    }

    private fun syncMultilinePlaceholderVisibility() {
        multilinePlaceholderLabel?.setHidden(placeholderText.isEmpty() || textValue.isNotEmpty())
    }

    internal fun activeFocusTargetView(): NSView? =
        multilineTextView ?: secureField ?: singleLineField

    internal fun focusEditorFromShellClick() {
        if (!nativeWidget.enabled || !nativeWidget.isEffectivelyFocusable()) return
        MacosFocusSupport.syncViewFirstResponder(
            targetView = activeFocusTargetView(),
            focused = true,
            effectivelyFocusable = nativeWidget.isEffectivelyFocusable(),
            enabled = nativeWidget.enabled
        )
    }

    override fun applyStyledVisuals(visuals: StyledVisualSpec) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer { applyStyledVisuals(resolveCurrentVisuals()) }
            return
        }
        lastAppliedVisuals = visuals
        val host = ensureHostView()
        lastVisualBoundsW = nativeWidget.bounds.width
        lastVisualBoundsH = nativeWidget.bounds.height
        val isDark = MacosAppKitAppearance.isDarkTheme(nativeWidget)
        val computed = lastComputedStyle

        host.setAlphaValue(getOpacity().coerceIn(0f, 1f).toDouble())
        host.surfaceSupport.applyStyledVisuals(
            spec = visuals,
            cornerRadii = computed?.borderRadius,
            boxShadow = computed?.boxShadowList?.shadows?.firstOrNull(),
            isFocused = isFocusedState(),
            clipOverflow = false,
            darkTheme = isDark,
            effectiveBackdropFilter = (nativeWidget.attributes["backdropFilter"] as? CssFilter) ?: visuals.backdropFilter,
            effectiveForegroundFilter = (nativeWidget.attributes["cssFilter"] as? CssFilter) ?: visuals.filter
        )
        syncHostChildren(host)
        layoutInnerViews()
        syncControlValues()
        host.setNeedsDisplay(true)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val visuals = resolveCurrentVisuals()
        val explicitW = visuals.widthPx?.coerceAtLeast(visuals.minWidthPx ?: 0)
        val explicitH = visuals.heightPx?.coerceAtLeast(visuals.minHeightPx ?: 0)
        if (explicitW != null && explicitH != null) {
            return Size(explicitW, explicitH)
        }
        val pad = visuals.padding
            ?: lastComputedStyle?.padding?.toEdgeInsets()
            ?: DEFAULT_INPUT_PADDING
        val font = resolveEditorFont(visuals)
        val lineH = ceil(font.ascender - font.descender + 4.0).toInt().coerceAtLeast(18)
        val captionExtra = if (!getLabel().isNullOrBlank()) 20 else 0
        val defaultW = if (isMultiline()) 240 else 200
        val defaultH = if (isMultiline()) {
            lineH * 3 + pad.top + pad.bottom + captionExtra
        } else {
            lineH + pad.top + pad.bottom + captionExtra
        }
        return Size(
            explicitW ?: defaultW.coerceAtLeast(visuals.minWidthPx ?: 0),
            explicitH ?: defaultH.coerceAtLeast(visuals.minHeightPx ?: 28)
        )
    }

    override fun setValue(value: String) {
        super.setValue(value)
        if (MacosAppKitDispatch.isMainThread()) syncControlValues()
    }

    override fun setPlaceholder(placeholder: String) {
        super.setPlaceholder(placeholder)
        if (MacosAppKitDispatch.isMainThread()) syncControlValues()
    }

    override fun setLabel(label: String?) {
        super.setLabel(label)
        if (MacosAppKitDispatch.isMainThread()) {
            hostView?.let { ensureInnerViews(it, forceRebuild = false) }
        }
    }

    override fun setReadOnly(readOnly: Boolean) {
        super.setReadOnly(readOnly)
        if (MacosAppKitDispatch.isMainThread()) syncControlValues()
    }

    override fun setPasswordMode(password: Boolean) {
        super.setPasswordMode(password)
        if (MacosAppKitDispatch.isMainThread()) {
            hostView?.let { ensureInnerViews(it, forceRebuild = true) }
        }
    }

    override fun setMultiline(multiline: Boolean) {
        super.setMultiline(multiline)
        if (MacosAppKitDispatch.isMainThread()) {
            hostView?.let { ensureInnerViews(it, forceRebuild = true) }
        }
    }

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        MacosFocusSupport.syncViewFirstResponder(
            targetView = activeFocusTargetView(),
            focused = isFocusedState(),
            effectivelyFocusable = nativeWidget.isEffectivelyFocusable(),
            enabled = nativeWidget.enabled
        )
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        if (!MacosAppKitDispatch.isMainThread()) {
            MacosAppKitDispatch.onMainOrDefer {
                if (!isDisposed) onNativeBoundsChanged(nativeWidget.bounds)
            }
            return
        }
        val host = ensureHostView()
        MacosAppKitDispatch.applyFrameAndTransform(host, rect, nativeWidget.cssTransform, useAlignmentRectExpansion = false)
        layoutInnerViews()
        if (rect.width != lastVisualBoundsW || rect.height != lastVisualBoundsH) {
            applyStyledVisuals(resolveCurrentVisuals())
        }
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        MacosAppKitDispatch.onMainOrDefer {
            hostView?.setHidden(!visible)
            if (visible) {
                hostView?.surfaceSupport?.refreshBackdropUnderlayIfNeeded(force = true)
            }
        }
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        applyStyledVisuals(resolveCurrentVisuals())
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        MacosAppKitDispatch.onMainOrDefer {
            hostView?.setAlphaValue(opacity.coerceIn(0f, 1f).toDouble())
        }
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        MacosAppKitDispatch.onMainOrDefer {
            hostView?.setToolTip(tooltip)
        }
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        MacosAppKitDispatch.onMainOrDefer {
            hostView?.let { v -> v.window?.invalidateCursorRectsForView(v) }
        }
    }

    override fun onNativeZIndexChanged(zIndex: Int) {
        (nativeWidget.parentWidget?.attributes?.get("nativeImpl") as? DesktopBaseContainerNativeImpl<*>)
            ?.onChildZIndexInvalidated()
        nativeWidget.requestRetainedRelayout()
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.cssTransform = transform
        val host = hostView ?: return
        MacosAppKitDispatch.applyFrameAndTransform(host, nativeWidget.bounds, transform, useAlignmentRectExpansion = false)
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
        clearSubReferences()
        val host = hostView
        hostView = null
        MacosAppKitDispatch.detachViewOnMain(host)
        super.dispose()
    }

    private companion object {
        val DEFAULT_INPUT_PADDING = EdgeInsets(6, 12, 6, 12)

        fun defaultTextColor(isDark: Boolean): RgbaColor =
            if (isDark) RgbaColor(240, 242, 245, 255) else RgbaColor(24, 24, 27, 255)
    }
}
