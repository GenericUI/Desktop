@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.kde

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.desktop.widget.DesktopStyledTextInputNativeImpl
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.event.InputFocusManager
import com.nexustools.genericui.geometry.EdgeInsets
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.CssTransform
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.threading.GenericUiThreading
import kotlinx.cinterop.COpaquePointer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Retained KDE custom-styled text input (`StyledTextField` / `StyledTextArea`) painting
 * [StyledVisualSpec] pill radii, gradients, borders, box shadows, focus rings, and 10-op
 * `backdrop-filter` via [KdePainterSupport.paintStyledSurface] around a transparent, borderless
 * child Qt6 `QLineEdit` or `QPlainTextEdit`.
 */
open class KdeStyledTextInputNativeImpl(
    nativeWidget: KdeWidgetHandle = KdeWidgetHandle("KdeStyledTextInput"),
    multiline: Boolean = false,
    classPrefix: String = if (multiline) "styled-textarea" else "styled-textfield"
) : DesktopStyledTextInputNativeImpl<KdeWidgetHandle>(nativeWidget, multiline, classPrefix) {

    private var hovered = false
    private var pressed = false
    private var suppressSignalSync = false
    private var editorPtr: COpaquePointer? = null
    private var builtMultiline: Boolean = multiline
    private var lastVisuals: StyledVisualSpec = StyledVisualSpec()

    init {
        KdeDynamicBindings.ensureQApplication()
        nativeWidget.attributes["isTextInputComponent"] = true
        nativeWidget.attributes["useSystemControlAppearance"] = "false"
        materializeRootAndEditor(isInitial = true)
    }

    private fun materializeRootAndEditor(isInitial: Boolean) {
        val wantMulti = isMultiline()
        if (!isInitial && nativeWidget.qWidgetPtr != null && editorPtr != null && builtMultiline == wantMulti) {
            syncEditorGeometryAndState()
            return
        }
        builtMultiline = wantMulti

        val existingRoot = nativeWidget.qWidgetPtr
        if (existingRoot != null && editorPtr != null) {
            editorPtr?.let {
                KdeDynamicBindings.setWidgetParent(it, null)
                if (KdeVTableBridge.isPeerAlive(it)) {
                    KdeVTableBridge.destroyObject(it)
                }
            }
            editorPtr = null
            createInnerEditor(existingRoot, wantMulti)
            syncEditorGeometryAndState()
            applyStyledVisuals(resolveCurrentVisuals())
            return
        }

        val rootPtr = KdeDynamicBindings.createQWidget(nativeWidget.desiredParentPtr, 0)
        nativeWidget.attachToQWidget(rootPtr, if (wantMulti) "KdeStyledTextArea" else "KdeStyledTextField", customPainted = true)
        KdeDynamicBindings.setWidgetAttribute(rootPtr, KdeDynamicBindings.WA_HOVER, true)
        KdeDynamicBindings.setWidgetFocusPolicy(rootPtr, KdeDynamicBindings.FOCUS_STRONG_FOCUS)

        KdePainterSupport.attachStandardInputHooks(
            widgetPtr = rootPtr,
            impl = this,
            hookMask = KdeVTableBridge.HOOK_PAINT or KdeVTableBridge.HOOK_RESIZE or KdeVTableBridge.HOOK_SIZE_HINT,
            dispatchLeftClickOnRelease = false,
            onPaint = { wPtr, painter, w, h -> paintStyledInputSurface(wPtr, painter, w, h) },
            onSizeHint = { _ ->
                val sz = measureIntrinsicSize(null, null)
                sz.width.toInt().coerceAtLeast(120) to sz.height.toInt().coerceAtLeast(30)
            },
            onHoverChanged = { h ->
                hovered = h
                setHoveredState(h)
                nativeWidget.requestRepaint()
            },
            onPressedChanged = { p ->
                pressed = p
                setActivePressedState(p)
                if (p && nativeWidget.enabled && nativeWidget.isEffectivelyFocusable()) {
                    editorPtr?.let { ed -> KdeDynamicBindings.setWidgetFocus(ed, true) }
                }
                nativeWidget.requestRepaint()
            }
        )
        KdeVTableBridge.attachWidgetHooks(rootPtr, KdeVTableBridge.HOOK_RESIZE) {
            onResize = { _, _, _ -> layoutInnerEditor() }
        }

        createInnerEditor(rootPtr, wantMulti)
        applyStyledVisuals(resolveCurrentVisuals())
    }

    private fun createInnerEditor(parentRootPtr: COpaquePointer, wantMulti: Boolean) {
        val ed = if (wantMulti) {
            val te = KdeDynamicBindings.createQPlainTextEdit(parentRootPtr)
            KdeDynamicBindings.setFrameShape(te, 0 /* QFrame::NoFrame */)
            KdeVTableBridge.connectSignal(te, "textChanged()") {
                if (!suppressSignalSync) {
                    emitUserValueChange(KdeDynamicBindings.readPlainTextEditText(te))
                }
            }
            te
        } else {
            val le = KdeDynamicBindings.createQLineEdit(parentRootPtr)
            KdeDynamicBindings.setLineEditFrame(le, false)
            KdeVTableBridge.connectSignal(le, "textEdited(QString)") { args ->
                if (!suppressSignalSync) {
                    val s = KdeDynamicBindings.readBorrowedQString(KdeVTableBridge.readSignalArgPtr(args, 1))
                    emitUserValueChange(s)
                }
            }
            KdeVTableBridge.connectSignal(le, "returnPressed()") {
                GenericUiThreading.runOnLogic { onSubmitListener?.invoke(textValue) }
            }
            le
        }
        KdeDynamicBindings.setWidgetAutoFillBackground(ed, false)
        KdeDynamicBindings.setWidgetAttribute(ed, KdeDynamicBindings.WA_HOVER, true)
        KdeDynamicBindings.setWidgetFocusPolicy(ed, KdeDynamicBindings.FOCUS_STRONG_FOCUS)
        KdeVTableBridge.attachWidgetHooks(ed, KdeVTableBridge.HOOK_INPUT) {
            onEnter = { _ ->
                hovered = true
                setHoveredState(true)
                nativeWidget.requestRepaint()
            }
            onLeave = { _ ->
                hovered = false
                setHoveredState(false)
                nativeWidget.requestRepaint()
            }
            onMousePress = { _, info ->
                if (info.button == 1) {
                    pressed = true
                    setActivePressedState(true)
                    nativeWidget.requestRepaint()
                }
                false
            }
            onMouseRelease = { _, info ->
                if (info.button == 1) {
                    pressed = false
                    setActivePressedState(false)
                    nativeWidget.requestRepaint()
                }
                false
            }
            onFocusIn = { _ ->
                InputFocusManager.setEditableInputFocused(true)
                if (nativeWidget.isEffectivelyFocusable()) {
                    setFocusedState(true)
                }
                nativeWidget.requestRepaint()
            }
            onFocusOut = { _ ->
                InputFocusManager.setEditableInputFocused(false)
                setFocusedState(false)
                nativeWidget.requestRepaint()
            }
        }
        KdeDynamicBindings.setWidgetVisible(ed, true)
        editorPtr = ed
        syncEditorGeometryAndState()
    }

    private fun layoutInnerEditor() {
        val rootPtr = nativeWidget.qWidgetPtr ?: return
        val ed = editorPtr ?: return
        val w = KdeDynamicBindings.getWidgetWidth(rootPtr).takeIf { it > 0 } ?: nativeWidget.bounds.width
        val h = KdeDynamicBindings.getWidgetHeight(rootPtr).takeIf { it > 0 } ?: nativeWidget.bounds.height
        if (w <= 0 || h <= 0) return

        val pad = lastVisuals.padding ?: lastComputedStyle?.padding?.toEdgeInsets() ?: DEFAULT_INPUT_PADDING
        val captionTop = if (!getLabel().isNullOrBlank()) 20 else 0
        val left = pad.left.coerceAtLeast(0)
        val top = captionTop + pad.top.coerceAtLeast(0)
        val innerW = (w - left - pad.right.coerceAtLeast(0)).coerceAtLeast(8)
        val innerH = (h - top - pad.bottom.coerceAtLeast(0)).coerceAtLeast(16)
        KdeDynamicBindings.setWidgetGeometry(ed, left, top, innerW, innerH)
    }

    private fun syncEditorGeometryAndState() {
        val ed = editorPtr ?: return
        suppressSignalSync = true
        try {
            if (builtMultiline) {
                KdeDynamicBindings.setPlainTextEditState(ed, textValue, placeholderText, readOnlyState)
            } else {
                KdeDynamicBindings.setLineEditState(ed, textValue, placeholderText, readOnlyState, password = passwordModeState)
            }
            KdeDynamicBindings.setWidgetEnabled(ed, nativeWidget.enabled)
        } finally {
            suppressSignalSync = false
        }
        layoutInnerEditor()
    }

    override fun applyStyledVisuals(visuals: StyledVisualSpec) {
        lastVisuals = visuals
        val ed = editorPtr
        if (ed != null) {
            val isDark = (Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()) == ThemeMode.DARK
            var textFg = visuals.textColor ?: getEffectiveTextColor() ?: defaultTextColor(isDark)
            if (!nativeWidget.enabled) {
                textFg = textFg.copy(a = (textFg.a * 0.55f).roundToInt().coerceIn(0, 255))
            }
            val placeholderFg = visuals.placeholderColor ?: textFg.copy(a = (textFg.a * 0.52f).roundToInt().coerceIn(0, 255))
            val fontSize = visuals.fontSizePx?.takeIf { it > 0f }
                ?: lastComputedStyle?.fontSizePx
                ?: (nativeWidget.attributes["fontSizePx"] as? Number)?.toFloat()
                ?: 14f
            val weight = visuals.fontWeightNumeric ?: lastComputedStyle?.fontWeightNumeric ?: 400
            val fontFamily = (visuals.fontFamily ?: lastComputedStyle?.fontFamily)?.trim()?.takeIf { it.isNotEmpty() }
            val bold = weight >= 600
            val italic = lastComputedStyle?.fontStyle?.cssValue?.equals("italic", ignoreCase = true) == true

            KdeDynamicBindings.setWidgetFontStyle(ed, fontFamily, fontSize, bold, italic)
            KdeDynamicBindings.applyStyledInputPalette(ed, textFg, placeholderFg)
            val colorCss = "rgba(${textFg.r.coerceIn(0, 255)}, ${textFg.g.coerceIn(0, 255)}, ${textFg.b.coerceIn(0, 255)}, ${(textFg.a / 255.0).coerceIn(0.0, 1.0)})"
            KdeDynamicBindings.setWidgetStyleSheet(
                ed,
                "QLineEdit, QPlainTextEdit { background: transparent; border: none; padding: 0px; color: $colorCss; }"
            )
            layoutInnerEditor()
        }
        nativeWidget.requestRepaint()
    }

    private fun paintStyledInputSurface(widgetPtr: COpaquePointer, painter: COpaquePointer, w: Int, h: Int) {
        val visuals = resolveCurrentVisuals()
        val computed = getComputedStyle()
        val backdrop = (nativeWidget.attributes["backdropFilter"] as? CssFilter) ?: visuals.backdropFilter
        val grad = visuals.backgroundGradient ?: (nativeWidget.attributes["backgroundGradient"] as? Gradient)

        KdePainterSupport.paintStyledSurface(
            widgetPtr = widgetPtr,
            painterPtr = painter,
            widthPx = w,
            heightPx = h,
            spec = visuals,
            radii = computed?.borderRadius,
            gradientOverride = grad,
            backdropFilterOverride = backdrop,
            focused = isFocusedState(),
            opacity = getOpacity(),
            isPressed = pressed,
            isHovered = hovered
        )

        val label = getLabel()
        if (!label.isNullOrBlank()) {
            val isDark = (Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()) == ThemeMode.DARK
            val fg = visuals.textColor ?: getEffectiveTextColor() ?: defaultTextColor(isDark)
            KdeDynamicBindings.painterDrawText(
                p = painter,
                text = label,
                x = 2.0,
                y = 0.0,
                w = (w - 4).coerceAtLeast(1).toDouble(),
                h = 18.0,
                color = fg,
                fontSizePx = 11.5f,
                fontWeight = 600,
                fontFamily = computed?.fontFamily,
                alignmentFlags = KdeDynamicBindings.ALIGN_LEFT or KdeDynamicBindings.ALIGN_VCENTER
            )
        }
    }

    private fun emitUserValueChange(newText: String) {
        textValue = newText
        nativeWidget.attributes["value"] = newText
        GenericUiThreading.runOnLogic {
            onValueChangedListener?.invoke(newText)
        }
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val visuals = resolveCurrentVisuals()
        val pad = visuals.padding ?: lastComputedStyle?.padding?.toEdgeInsets() ?: DEFAULT_INPUT_PADDING
        val captionExtra = if (!getLabel().isNullOrBlank()) 20 else 0
        val defaultW = if (isMultiline()) 240 else 200
        val defaultH = if (isMultiline()) 96 + captionExtra else 36 + captionExtra
        val prefW = visuals.widthPx ?: max(visuals.minWidthPx ?: defaultW, 120 + pad.left + pad.right)
        val prefH = visuals.heightPx ?: max(visuals.minHeightPx ?: defaultH, 24 + pad.top + pad.bottom + captionExtra)
        val clampedW = if (maxWidth != null) minOf(prefW.toFloat(), maxWidth) else prefW.toFloat()
        return Size(clampedW, prefH.toFloat())
    }

    override fun setValue(value: String) {
        super.setValue(value)
        syncEditorGeometryAndState()
    }

    override fun setPlaceholder(placeholder: String) {
        super.setPlaceholder(placeholder)
        syncEditorGeometryAndState()
    }

    override fun setLabel(label: String?) {
        super.setLabel(label)
        syncEditorGeometryAndState()
        nativeWidget.requestRepaint()
    }

    override fun setReadOnly(readOnly: Boolean) {
        super.setReadOnly(readOnly)
        syncEditorGeometryAndState()
    }

    override fun setPasswordMode(password: Boolean) {
        super.setPasswordMode(password)
        syncEditorGeometryAndState()
    }

    override fun setMultiline(multiline: Boolean) {
        super.setMultiline(multiline)
        materializeRootAndEditor(isInitial = false)
    }

    override fun setFocusedState(focused: Boolean) {
        super.setFocusedState(focused)
        KdePainterSupport.syncWidgetFocus(this, isFocusedState(), editorPtr ?: nativeWidget.qWidgetPtr)
        nativeWidget.requestRepaint()
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        nativeWidget.applyBoundsToNative(rect)
        layoutInnerEditor()
        nativeWidget.requestRepaint()
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        nativeWidget.syncVisibilityToNative(visible)
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        nativeWidget.qWidgetPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) }
        editorPtr?.let { KdeDynamicBindings.setWidgetEnabled(it, enabled) }
        applyStyledVisuals(resolveCurrentVisuals())
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        nativeWidget.requestRepaint()
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, nativeWidget.isSelfPointerEventsNone(), tooltip, getOpacity())
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, cursor, nativeWidget.isSelfPointerEventsNone(), nativeWidget.tooltipText, getOpacity())
    }

    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        KdePainterSupport.applyCommonWidgetProperties(nativeWidget, customCursorState, pointerEventsNone, nativeWidget.tooltipText, getOpacity())
    }

    override fun onNativeTransformChanged(transform: CssTransform?) {
        nativeWidget.requestRepaint()
    }

    override fun onNativeZIndexChanged(zIndex: Int) {
        KdePainterSupport.restackParentByZOrder(nativeWidget)
    }

    override fun requestRepaintNative() {
        nativeWidget.requestRepaint()
    }

    override fun dispose() {
        editorPtr = null
        nativeWidget.destroyQWidget()
        super.dispose()
    }

    private companion object {
        val DEFAULT_INPUT_PADDING = EdgeInsets(6, 12, 6, 12)

        fun defaultTextColor(isDark: Boolean): RgbaColor =
            if (isDark) RgbaColor(0xFC, 0xFC, 0xFC) else RgbaColor(0x23, 0x26, 0x29)
    }
}
