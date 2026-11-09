@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.render

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.windows.Win32DwmUxThemeBridge
import com.nexustools.genericui.windows.Win32NativeWindowHost

/**
 * Renders text and specialized input field controls on Windows (`TextField`, `PasswordField`,
 * `SearchBox`, `NumberField`, `TextArea`, `RichTextEditor`, `CodeEditor`, `ColorPicker`,
 * `DateTimePicker`, `FilePickerDropZone`).
 *
 * Uses native `uxtheme.dll` `EDIT` (`EP_EDITBORDER_NOSCROLL` / `EP_BACKGROUNDWITHBORDER`) and
 * `SPIN` (`SPNP_UP` / `SPNP_DOWN`) control surfaces with `BootstrapType` tinting via
 * [Win32ControlTintRenderUtil], while ignoring aesthetic `ComponentStyle` skin overrides.
 */
object Win32InputFieldRenderUtil {

    fun canRender(widget: DesktopNativeWidget): Boolean {
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopTextInputNativeImpl<*>) return true
        val cls = widget.nativeClassName.lowercase()
        val role = (widget.attributes["componentRole"] as? String ?: "").lowercase()
        return cls in INPUT_FIELD_CLASSES || role in INPUT_FIELD_ROLES
    }

    fun render(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val inputImpl = widget.attributes["nativeImpl"] as? DesktopTextInputNativeImpl<*>
        val cls = widget.nativeClassName.lowercase()
        val role = (widget.attributes["componentRole"] as? String ?: "").lowercase()

        return when {
            cls == "colorpicker" || role == "color-picker" ->
                renderColorPicker(ctx, widget, inputImpl, left, top, right)
            cls == "datetimepicker" || role == "date-time-picker" || role == "datepicker" ->
                renderDateTimePicker(ctx, widget, inputImpl, left, top, right)
            cls == "filepickerdropzone" || role == "file-picker-drop-zone" ->
                renderFilePickerDropZone(ctx, widget, left, top, right)
            inputImpl?.isMultiline() == true || cls in MULTILINE_INPUT_CLASSES || role in MULTILINE_INPUT_ROLES ->
                renderMultilineEditor(ctx, widget, inputImpl, left, top, right)
            cls == "numberfield" || role == "number-field" ->
                renderNumberField(ctx, widget, inputImpl, left, top, right)
            else ->
                renderSingleLineInput(ctx, widget, inputImpl, left, top, right)
        }
    }

    /**
     * Renders a single-line native `TextField`, `PasswordField`, or `SearchBox` using `uxtheme.dll`
     * `EDIT` (`EP_EDITBORDER_NOSCROLL`) + `BootstrapType` tinting.
     */
    fun renderSingleLineInput(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        inputImpl: DesktopTextInputNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 30
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)
        if (inputImpl != null && (Win32NativeWindowHost.focusedWidget == widget || Win32NativeWindowHost.focusedTextInput === inputImpl)) {
            Win32NativeWindowHost.renderAndMountTextInputControl(
                hwnd = ctx.hwnd,
                hdc = ctx.hdc,
                theme = ctx.systemTheme,
                font = ctx.fontNormal,
                inputImpl = inputImpl,
                widget = widget,
                left = left,
                top = top,
                right = right,
                bottom = bottom
            )
            return bottom
        }

        drawNativeEditSurface(ctx, widget, inputImpl, left, top, right, bottom)

        val isPassword = inputImpl?.isPasswordMode() == true ||
            widget.nativeClassName.equals("PasswordField", ignoreCase = true) ||
            widget.attributes["componentRole"] == "password-field"
        val isSearch = widget.nativeClassName.equals("SearchBox", ignoreCase = true) ||
            widget.attributes["componentRole"] == "search-box"

        val rawValue = inputImpl?.getValue() ?: (widget.attributes["value"] as? String).orEmpty()
        val placeholder = inputImpl?.getPlaceholder() ?: (widget.attributes["placeholder"] as? String).orEmpty()
        val displayValue = when {
            rawValue.isNotEmpty() && isPassword -> "•".repeat(rawValue.length.coerceAtMost(32))
            rawValue.isNotEmpty() -> rawValue
            else -> placeholder
        }

        var textLeft = left + 10
        ctx.selectFont(ctx.fontNormal)
        if (isSearch) {
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = "🔍",
                left = left + 8,
                top = top,
                right = left + 26,
                bottom = bottom,
                colorRef = ctx.textMutedColorRef
            )
            textLeft = left + 28
        }

        if (displayValue.isNotEmpty()) {
            val fgColorRef = if (rawValue.isEmpty()) {
                ctx.textMutedColorRef
            } else {
                ctx.textPrimaryColorRef
            }
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = displayValue,
                left = textLeft,
                top = top,
                right = (right - 8).coerceAtLeast(textLeft + 1),
                bottom = bottom,
                colorRef = fgColorRef,
                center = false
            )
        }

        registerInputHitRegion(ctx, widget, inputImpl, left, top, right, bottom)
        return bottom
    }

    /**
     * Renders a `NumberField` with a native `EDIT` frame and `uxtheme.dll` `SPIN` (`SPNP_UP` / `SPNP_DOWN`)
     * stepper buttons on the right.
     */
    fun renderNumberField(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        inputImpl: DesktopTextInputNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 30
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)
        if (inputImpl != null && (Win32NativeWindowHost.focusedWidget == widget || Win32NativeWindowHost.focusedTextInput === inputImpl)) {
            Win32NativeWindowHost.renderAndMountTextInputControl(
                hwnd = ctx.hwnd,
                hdc = ctx.hdc,
                theme = ctx.systemTheme,
                font = ctx.fontNormal,
                inputImpl = inputImpl,
                widget = widget,
                left = left,
                top = top,
                right = right,
                bottom = bottom
            )
            return bottom
        }

        drawNativeEditSurface(ctx, widget, inputImpl, left, top, right, bottom)

        val spinW = 20.coerceAtMost((right - left) / 3)
        val spinL = right - spinW - 2
        val midY = (top + bottom) / 2
        val spinClassList = Win32DwmUxThemeBridge.resolveExplorerClassList("SPIN", ctx.darkMode)
        val spinState = if (!widget.enabled) Win32DwmUxThemeBridge.UPS_DISABLED else Win32DwmUxThemeBridge.UPS_NORMAL
        Win32DwmUxThemeBridge.drawUxThemeControlBackground(
            hwnd = ctx.hwnd,
            hdc = ctx.hdc,
            classList = spinClassList,
            partId = Win32DwmUxThemeBridge.SPNP_UP,
            stateId = spinState,
            left = spinL,
            top = top + 2,
            right = right - 2,
            bottom = midY
        )
        Win32DwmUxThemeBridge.drawUxThemeControlBackground(
            hwnd = ctx.hwnd,
            hdc = ctx.hdc,
            classList = spinClassList,
            partId = Win32DwmUxThemeBridge.SPNP_DOWN,
            stateId = spinState,
            left = spinL,
            top = midY,
            right = right - 2,
            bottom = bottom - 2
        )

        val rawValue = inputImpl?.getValue() ?: (widget.attributes["value"] as? String).orEmpty()
        val placeholder = inputImpl?.getPlaceholder() ?: "0"
        val displayText = rawValue.ifEmpty { placeholder }
        ctx.selectFont(ctx.fontNormal)
        Win32NativeWindowHost.drawTextInRect(
            hdc = ctx.hdc,
            text = displayText,
            left = left + 10,
            top = top,
            right = (spinL - 4).coerceAtLeast(left + 12),
            bottom = bottom,
            colorRef = if (rawValue.isEmpty()) ctx.textMutedColorRef else ctx.textPrimaryColorRef
        )

        registerInputHitRegion(ctx, widget, inputImpl, left, top, right, bottom)
        return bottom
    }

    /**
     * Renders `TextArea`, `RichTextEditor`, or `CodeEditor` using a native `uxtheme.dll` `EDIT`
     * frame and multiline text rendering.
     */
    fun renderMultilineEditor(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        inputImpl: DesktopTextInputNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val rows = ((widget.attributes["rows"] as? Number)?.toInt() ?: 4).coerceIn(2, 16)
        val isCodeEditor = widget.nativeClassName.equals("CodeEditor", ignoreCase = true) ||
            widget.attributes["componentRole"] == "code-editor"
        val isRichEditor = widget.nativeClassName.equals("RichTextEditor", ignoreCase = true) ||
            widget.attributes["componentRole"] == "rich-text-editor"
        val toolbarH = if (isRichEditor) 26 else 0
        val height = widget.preferredSize?.height?.takeIf { it > 0 } ?: (rows * 20 + 16 + toolbarH)
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)
        if (inputImpl != null && (Win32NativeWindowHost.focusedWidget == widget || Win32NativeWindowHost.focusedTextInput === inputImpl)) {
            Win32NativeWindowHost.renderAndMountTextInputControl(
                hwnd = ctx.hwnd,
                hdc = ctx.hdc,
                theme = ctx.systemTheme,
                font = ctx.fontNormal,
                inputImpl = inputImpl,
                widget = widget,
                left = left,
                top = top,
                right = right,
                bottom = bottom
            )
            return bottom
        }

        drawNativeEditSurface(ctx, widget, inputImpl, left, top, right, bottom)

        var contentTop = top + 6
        if (isRichEditor) {
            Win32DwmUxThemeBridge.drawUxThemeControlBackground(
                hwnd = ctx.hwnd,
                hdc = ctx.hdc,
                classList = Win32DwmUxThemeBridge.resolveExplorerClassList("TOOLBAR", ctx.darkMode),
                partId = Win32DwmUxThemeBridge.TP_BUTTON,
                stateId = Win32DwmUxThemeBridge.TS_NORMAL,
                left = left + 2,
                top = top + 2,
                right = right - 2,
                bottom = top + toolbarH
            )
            ctx.selectFont(ctx.fontSmallBold ?: ctx.fontSmall)
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = "B   I   U   •   1.   🔗",
                left = left + 10,
                top = top + 2,
                right = right - 10,
                bottom = top + toolbarH,
                colorRef = ctx.textSecondaryColorRef
            )
            Win32NativeWindowHost.fillRectColor(ctx.hdc, left + 2, top + toolbarH, right - 2, top + toolbarH + 1, ctx.borderSubtleColorRef)
            contentTop = top + toolbarH + 6
        }

        val rawValue = inputImpl?.getValue() ?: (widget.attributes["value"] as? String).orEmpty()
        val placeholder = inputImpl?.getPlaceholder() ?: (widget.attributes["placeholder"] as? String).orEmpty()
        val lines = (if (rawValue.isNotEmpty()) rawValue else placeholder).lines()
        val font = if (isCodeEditor) ctx.obtainFont("Consolas", 13) else ctx.fontNormal
        ctx.selectFont(font)

        val fgColorRef = if (rawValue.isEmpty()) ctx.textMutedColorRef else ctx.textPrimaryColorRef
        val gutterW = if (isCodeEditor) 32 else 0
        if (isCodeEditor) {
            Win32NativeWindowHost.fillRectColor(ctx.hdc, left + 2, contentTop - 4, left + gutterW, bottom - 2, ctx.bgElevatedColorRef)
        }
        var lineY = contentTop
        for ((idx, line) in lines.withIndex()) {
            if (lineY + 18 > bottom - 4) break
            if (isCodeEditor) {
                Win32NativeWindowHost.drawTextInRect(
                    hdc = ctx.hdc,
                    text = (idx + 1).toString(),
                    left = left + 4,
                    top = lineY,
                    right = left + gutterW - 4,
                    bottom = lineY + 18,
                    colorRef = ctx.textMutedColorRef,
                    center = true
                )
            }
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = line,
                left = left + gutterW + 8,
                top = lineY,
                right = right - 8,
                bottom = lineY + 18,
                colorRef = fgColorRef
            )
            lineY += 18
        }

        registerInputHitRegion(ctx, widget, inputImpl, left, top, right, bottom)
        return bottom
    }

    /**
     * Renders a `ColorPicker` with a native `uxtheme.dll` `BUTTON` frame and live color swatch.
     */
    fun renderColorPicker(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        inputImpl: DesktopTextInputNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 30
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)

        val hovered = ctx.isWidgetHovered(widget, left, top, right, bottom)
        val pressed = ctx.isWidgetPressed(widget)
        val stateId = when {
            !widget.enabled -> Win32DwmUxThemeBridge.PBS_DISABLED
            pressed -> Win32DwmUxThemeBridge.PBS_PRESSED
            hovered -> Win32DwmUxThemeBridge.PBS_HOT
            else -> Win32DwmUxThemeBridge.PBS_NORMAL
        }
        Win32ControlTintRenderUtil.drawNativeOrTintedControlSurface(
            ctx = ctx,
            widget = widget,
            baseClass = "BUTTON",
            partId = Win32DwmUxThemeBridge.BP_PUSHBUTTON,
            stateId = stateId,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            hovered = hovered,
            pressed = pressed
        )

        val colorHex = (inputImpl?.getValue() ?: (widget.attributes["color"] as? String) ?: "#0D6EFD").trim()
        val parsedColor = RgbaColor.fromCss(colorHex) ?: RgbaColor(13, 110, 253)
        Win32NativeWindowHost.drawRoundedRect(
            hdc = ctx.hdc,
            left = left + 6,
            top = top + 5,
            right = (left + 30).coerceAtMost(right - 6),
            bottom = bottom - 5,
            radius = 4,
            fillColor = ctx.toColorRef(parsedColor),
            borderColor = ctx.borderSubtleColorRef
        )
        ctx.selectFont(ctx.fontNormal)
        Win32NativeWindowHost.drawTextInRect(
            hdc = ctx.hdc,
            text = colorHex,
            left = left + 36,
            top = top,
            right = right - 8,
            bottom = bottom,
            colorRef = ctx.textPrimaryColorRef
        )
        registerInputHitRegion(ctx, widget, inputImpl, left, top, right, bottom)
        return bottom
    }

    /**
     * Renders a `DateTimePicker` with a native `EDIT` surface and calendar indicator.
     */
    fun renderDateTimePicker(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        inputImpl: DesktopTextInputNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 30
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)
        if (inputImpl != null && (Win32NativeWindowHost.focusedWidget == widget || Win32NativeWindowHost.focusedTextInput === inputImpl)) {
            Win32NativeWindowHost.renderAndMountTextInputControl(
                hwnd = ctx.hwnd,
                hdc = ctx.hdc,
                theme = ctx.systemTheme,
                font = ctx.fontNormal,
                inputImpl = inputImpl,
                widget = widget,
                left = left,
                top = top,
                right = right,
                bottom = bottom
            )
            return bottom
        }

        drawNativeEditSurface(ctx, widget, inputImpl, left, top, right, bottom)

        val valueText = inputImpl?.getValue()
            ?: (widget.attributes["value"] as? String)
            ?: (widget.attributes["placeholder"] as? String)
            ?: "YYYY-MM-DD"
        ctx.selectFont(ctx.fontNormal)
        Win32NativeWindowHost.drawTextInRect(
            hdc = ctx.hdc,
            text = valueText,
            left = left + 10,
            top = top,
            right = right - 28,
            bottom = bottom,
            colorRef = ctx.textPrimaryColorRef
        )
        Win32NativeWindowHost.drawTextInRect(
            hdc = ctx.hdc,
            text = "📅",
            left = right - 26,
            top = top,
            right = right - 6,
            bottom = bottom,
            colorRef = ctx.textSecondaryColorRef,
            center = true
        )
        registerInputHitRegion(ctx, widget, inputImpl, left, top, right, bottom)
        return bottom
    }

    /**
     * Renders a `FilePickerDropZone` using a native `uxtheme.dll` `BUTTON` (`BP_GROUPBOX` / `BP_PUSHBUTTON`)
     * frame and prompt label.
     */
    fun renderFilePickerDropZone(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 72
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)
        val hovered = ctx.isWidgetHovered(widget, left, top, right, bottom)
        val stateId = if (hovered) Win32DwmUxThemeBridge.PBS_HOT else Win32DwmUxThemeBridge.PBS_NORMAL
        val label = Win32NativeWindowHost.extractWidgetText(widget).ifBlank { "Drop files here or click to browse..." }

        Win32ControlTintRenderUtil.drawNativeOrTintedControlWithTextOverlay(
            ctx = ctx,
            widget = widget,
            baseClass = "BUTTON",
            partId = Win32DwmUxThemeBridge.BP_PUSHBUTTON,
            stateId = stateId,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            text = label,
            font = ctx.fontNormal,
            centerText = true,
            hovered = hovered
        )
        val baseImpl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        ctx.registerHitRegion(
            Win32NativeWindowHost.HitRegion(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                tooltip = baseImpl?.getTooltipText(),
                widget = widget,
                onClick = { _, _ -> baseImpl?.dispatchClick() }
            )
        )
        return bottom
    }

    private fun drawNativeEditSurface(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        inputImpl: DesktopTextInputNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val hovered = ctx.isWidgetHovered(widget, left, top, right, bottom)
        val focused = inputImpl?.isFocusedState() == true || widget.attributes["focused"] == true
        val disabled = !widget.enabled
        val readOnly = inputImpl?.isReadOnly() == true
        val stateId = when {
            disabled -> Win32DwmUxThemeBridge.ETS_DISABLED
            readOnly -> Win32DwmUxThemeBridge.ETS_READONLY
            focused -> Win32DwmUxThemeBridge.ETS_FOCUSED
            hovered -> Win32DwmUxThemeBridge.ETS_HOT
            else -> Win32DwmUxThemeBridge.ETS_NORMAL
        }
        val drewEdit = Win32ControlTintRenderUtil.drawNativeOrTintedControlSurface(
            ctx = ctx,
            widget = widget,
            baseClass = "EDIT",
            partId = Win32DwmUxThemeBridge.EP_EDITBORDER_NOSCROLL,
            stateId = stateId,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            hovered = hovered,
            pressed = focused,
            disabled = disabled
        )
        if (!drewEdit) {
            Win32NativeWindowHost.drawRoundedRect(
                hdc = ctx.hdc,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                radius = 4,
                fillColor = ctx.bgBaseColorRef,
                borderColor = if (focused) ctx.accentBlueColorRef else ctx.borderSubtleColorRef
            )
        }
    }

    private fun registerInputHitRegion(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        inputImpl: DesktopTextInputNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) {
        val baseImpl = (inputImpl as? DesktopBaseNativeImpl<*>)
            ?: (widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)
        ctx.registerHitRegion(
            Win32NativeWindowHost.HitRegion(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                tooltip = baseImpl?.getTooltipText(),
                widget = widget,
                isTextInput = inputImpl != null,
                onClick = { _, _ ->
                    if (inputImpl != null) {
                        Win32NativeWindowHost.setWidgetFocused(widget)
                    } else {
                        baseImpl?.dispatchClick()
                    }
                }
            )
        )
    }

    private val MULTILINE_INPUT_CLASSES = setOf("textarea", "richtexteditor", "codeeditor")
    private val MULTILINE_INPUT_ROLES = setOf("text-area", "textarea", "rich-text-editor", "code-editor")
    private val INPUT_FIELD_CLASSES = setOf(
        "textfield",
        "passwordfield",
        "searchbox",
        "numberfield",
        "textarea",
        "richtexteditor",
        "codeeditor",
        "colorpicker",
        "datetimepicker",
        "filepickerdropzone"
    )
    private val INPUT_FIELD_ROLES = setOf(
        "text-field",
        "password-field",
        "search-box",
        "number-field",
        "text-area",
        "textarea",
        "rich-text-editor",
        "code-editor",
        "color-picker",
        "date-time-picker",
        "datepicker",
        "file-picker-drop-zone"
    )
}
