@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.render

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopDropdownNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.windows.Win32DwmUxThemeBridge
import com.nexustools.genericui.windows.Win32NativeWindowHost
import kotlin.math.max
import kotlin.math.min

/**
 * Renders toggle and selection controls on Windows (`CheckBox`, `RadioButton`, `Switch`,
 * `Select`, `ComboBox`, `DropdownButton`, `SegmentedButton`, `ChipGroup`).
 *
 * Uses native `uxtheme.dll` glyphs and control frames (`BUTTON` `BP_CHECKBOX` / `BP_RADIOBUTTON`,
 * `COMBOBOX` `CP_BORDER` / `CP_READONLY` / `CP_DROPDOWNBUTTONRIGHT`, `BUTTON` `BP_PUSHBUTTON`)
 * with `BootstrapType` tinting via [Win32ControlTintRenderUtil] while ignoring aesthetic
 * `ComponentStyle` skin overrides on standard controls.
 */
object Win32ToggleSelectionRenderUtil {

    fun canRender(widget: DesktopNativeWidget): Boolean {
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopCheckboxNativeImpl<*> ||
            impl is DesktopSelectNativeImpl<*> ||
            impl is DesktopDropdownNativeImpl<*>
        ) {
            return true
        }
        val cls = widget.nativeClassName.lowercase()
        val role = (widget.attributes["componentRole"] as? String ?: "").lowercase()
        return cls in TOGGLE_SELECTION_CLASSES || role in TOGGLE_SELECTION_ROLES
    }

    fun render(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val impl = widget.attributes["nativeImpl"]
        val cls = widget.nativeClassName.lowercase()
        val role = (widget.attributes["componentRole"] as? String ?: "").lowercase()

        return when {
            impl is DesktopCheckboxNativeImpl<*> || cls in CHECK_RADIO_SWITCH_CLASSES || role in CHECK_RADIO_SWITCH_ROLES ->
                renderCheckRadioOrSwitch(ctx, widget, impl as? DesktopCheckboxNativeImpl<*>, left, top, right)
            impl is DesktopSelectNativeImpl<*> || cls == "select" || cls == "combobox" || role == "select" || role == "combobox" ->
                renderSelectOrComboBox(ctx, widget, impl as? DesktopSelectNativeImpl<*>, left, top, right)
            impl is DesktopDropdownNativeImpl<*> || cls == "dropdownbutton" || role == "dropdown-button" ->
                renderDropdownButton(ctx, widget, impl as? DesktopDropdownNativeImpl<*>, left, top, right)
            cls == "segmentedbutton" || role == "segmented-button" ->
                renderSegmentedButton(ctx, widget, left, top, right)
            else ->
                renderChipGroup(ctx, widget, left, top, right)
        }
    }

    /**
     * Renders `CheckBox`, `RadioButton`, or `Switch` using `uxtheme.dll` `BP_CHECKBOX` /
     * `BP_RADIOBUTTON` / `BP_PUSHBUTTON` glyphs and `BootstrapType` tinting.
     */
    fun renderCheckRadioOrSwitch(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        checkboxImpl: DesktopCheckboxNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 26
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)

        val checked = checkboxImpl?.isChecked() == true || widget.attributes["checked"] == true
        val indeterminate = widget.attributes["indeterminate"] == true
        val label = checkboxImpl?.getLabel()
            ?: (widget.attributes["label"] as? String)
            ?: (widget.attributes["text"] as? String)
            ?: Win32NativeWindowHost.extractWidgetText(widget)

        val isSwitch = checkboxImpl?.switchStyle == true ||
            widget.nativeClassName.equals("Switch", ignoreCase = true) ||
            widget.attributes["componentRole"] == "switch" ||
            "form-switch" in widget.cachedAllClassTokens
        val isRadio = checkboxImpl?.radioStyle == true ||
            widget.nativeClassName.equals("RadioButton", ignoreCase = true) ||
            widget.attributes["componentRole"] == "radio"

        val hovered = ctx.isWidgetHovered(widget, left, top, right, bottom)
        val pressed = ctx.isWidgetPressed(widget)
        val disabled = !widget.enabled
        val activeBootstrapType = Win32ControlTintRenderUtil.resolveWidgetBootstrapType(widget)
            ?: if (checked) BootstrapType.PRIMARY else null

        val labelStartX: Int
        if (isSwitch) {
            val trackW = 36
            val trackH = 18
            val trackL = left + 2
            val trackT = top + (height - trackH) / 2
            val trackR = trackL + trackW
            val trackB = trackT + trackH
            val trackState = when {
                disabled -> Win32DwmUxThemeBridge.PBS_DISABLED
                pressed -> Win32DwmUxThemeBridge.PBS_PRESSED
                hovered -> Win32DwmUxThemeBridge.PBS_HOT
                else -> Win32DwmUxThemeBridge.PBS_NORMAL
            }
            Win32ControlTintRenderUtil.drawNativeOrTintedControlSurface(
                ctx = ctx,
                widget = widget,
                baseClass = "BUTTON",
                partId = Win32DwmUxThemeBridge.BP_PUSHBUTTON,
                stateId = trackState,
                left = trackL,
                top = trackT,
                right = trackR,
                bottom = trackB,
                explicitType = if (checked) activeBootstrapType else null,
                hovered = hovered,
                pressed = pressed,
                disabled = disabled
            )
            val thumbSize = trackH - 4
            val thumbL = if (checked) trackR - thumbSize - 2 else trackL + 2
            val thumbT = trackT + 2
            Win32DwmUxThemeBridge.drawUxThemeControlBackground(
                hwnd = ctx.hwnd,
                hdc = ctx.hdc,
                classList = Win32DwmUxThemeBridge.resolveExplorerClassList("TRACKBAR", ctx.darkMode),
                partId = Win32DwmUxThemeBridge.TKP_THUMB,
                stateId = if (disabled) Win32DwmUxThemeBridge.TUS_DISABLED else Win32DwmUxThemeBridge.TUS_NORMAL,
                left = thumbL,
                top = thumbT,
                right = thumbL + thumbSize,
                bottom = thumbT + thumbSize
            )
            labelStartX = trackR + 8
        } else {
            val boxSize = 16
            val boxL = left + 2
            val boxT = top + (height - boxSize) / 2
            val boxR = boxL + boxSize
            val boxB = boxT + boxSize
            val partId = if (isRadio) Win32DwmUxThemeBridge.BP_RADIOBUTTON else Win32DwmUxThemeBridge.BP_CHECKBOX
            val stateId = if (isRadio) {
                when {
                    checked && disabled -> Win32DwmUxThemeBridge.RBS_CHECKEDDISABLED
                    checked && pressed -> Win32DwmUxThemeBridge.RBS_CHECKEDPRESSED
                    checked && hovered -> Win32DwmUxThemeBridge.RBS_CHECKEDHOT
                    checked -> Win32DwmUxThemeBridge.RBS_CHECKEDNORMAL
                    disabled -> Win32DwmUxThemeBridge.RBS_UNCHECKEDDISABLED
                    pressed -> Win32DwmUxThemeBridge.RBS_UNCHECKEDPRESSED
                    hovered -> Win32DwmUxThemeBridge.RBS_UNCHECKEDHOT
                    else -> Win32DwmUxThemeBridge.RBS_UNCHECKEDNORMAL
                }
            } else {
                when {
                    indeterminate && disabled -> Win32DwmUxThemeBridge.CBS_MIXEDDISABLED
                    indeterminate && pressed -> Win32DwmUxThemeBridge.CBS_MIXEDPRESSED
                    indeterminate && hovered -> Win32DwmUxThemeBridge.CBS_MIXEDHOT
                    indeterminate -> Win32DwmUxThemeBridge.CBS_MIXEDNORMAL
                    checked && disabled -> Win32DwmUxThemeBridge.CBS_CHECKEDDISABLED
                    checked && pressed -> Win32DwmUxThemeBridge.CBS_CHECKEDPRESSED
                    checked && hovered -> Win32DwmUxThemeBridge.CBS_CHECKEDHOT
                    checked -> Win32DwmUxThemeBridge.CBS_CHECKEDNORMAL
                    disabled -> Win32DwmUxThemeBridge.CBS_UNCHECKEDDISABLED
                    pressed -> Win32DwmUxThemeBridge.CBS_UNCHECKEDPRESSED
                    hovered -> Win32DwmUxThemeBridge.CBS_UNCHECKEDHOT
                    else -> Win32DwmUxThemeBridge.CBS_UNCHECKEDNORMAL
                }
            }
            val explicitTint = Win32ControlTintRenderUtil.resolveWidgetBootstrapType(widget)
            Win32ControlTintRenderUtil.drawNativeOrTintedControlSurface(
                ctx = ctx,
                widget = widget,
                baseClass = "BUTTON",
                partId = partId,
                stateId = stateId,
                left = boxL,
                top = boxT,
                right = boxR,
                bottom = boxB,
                explicitType = explicitTint,
                hovered = hovered,
                pressed = pressed,
                disabled = disabled
            )
            labelStartX = boxR + 8
        }

        if (label.isNotEmpty()) {
            ctx.selectFont(ctx.fontNormal)
            val fgColorRef = if (disabled) ctx.textMutedColorRef else ctx.textPrimaryColorRef
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = label,
                left = labelStartX,
                top = top,
                right = right - 2,
                bottom = bottom,
                colorRef = fgColorRef,
                center = false
            )
        }

        ctx.registerHitRegion(
            Win32NativeWindowHost.HitRegion(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                tooltip = checkboxImpl?.getTooltipText()?.takeIf { it.isNotBlank() && it != label },
                widget = widget,
                onClick = { _, _ ->
                    if (checkboxImpl != null) {
                        val nextChecked = !checkboxImpl.isChecked()
                        checkboxImpl.setChecked(nextChecked)
                    } else {
                        (widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.dispatchClick()
                    }
                }
            )
        )
        return bottom
    }

    /**
     * Renders `Select` or `ComboBox` using `uxtheme.dll` `COMBOBOX` (`CP_READONLY` / `CP_BORDER`
     * + `CP_DROPDOWNBUTTONRIGHT`) and `BootstrapType` tinting.
     */
    fun renderSelectOrComboBox(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        selectImpl: DesktopSelectNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 30
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)

        val hovered = ctx.isWidgetHovered(widget, left, top, right, bottom)
        val pressed = ctx.isWidgetPressed(widget)
        val disabled = !widget.enabled
        val stateId = when {
            disabled -> Win32DwmUxThemeBridge.CBXS_DISABLED
            pressed -> Win32DwmUxThemeBridge.CBXS_PRESSED
            hovered -> Win32DwmUxThemeBridge.CBXS_HOT
            else -> Win32DwmUxThemeBridge.CBXS_NORMAL
        }

        val drewCombo = Win32ControlTintRenderUtil.drawNativeOrTintedControlSurface(
            ctx = ctx,
            widget = widget,
            baseClass = "COMBOBOX",
            partId = Win32DwmUxThemeBridge.CP_READONLY,
            stateId = stateId,
            left = left,
            top = top,
            right = right,
            bottom = bottom,
            hovered = hovered,
            pressed = pressed,
            disabled = disabled
        )
        if (!drewCombo) {
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
                pressed = pressed,
                disabled = disabled
            )
        }

        val arrowW = 22.coerceAtMost((right - left) / 3)
        Win32DwmUxThemeBridge.drawUxThemeControlBackground(
            hwnd = ctx.hwnd,
            hdc = ctx.hdc,
            classList = Win32DwmUxThemeBridge.resolveExplorerClassList("COMBOBOX", ctx.darkMode),
            partId = Win32DwmUxThemeBridge.CP_DROPDOWNBUTTONRIGHT,
            stateId = stateId,
            left = right - arrowW,
            top = top + 2,
            right = right - 2,
            bottom = bottom - 2
        )

        val selectedVal = selectImpl?.getSelectedValue() ?: (widget.attributes["selectedValue"] as? String).orEmpty()
        val displayLabel = selectImpl?.getOptions()?.firstOrNull { it.first == selectedVal }?.second
            ?: selectedVal.ifBlank { (widget.attributes["placeholder"] as? String) ?: "Select..." }

        ctx.selectFont(ctx.fontNormal)
        val fgColorRef = Win32ControlTintRenderUtil.resolveControlForegroundColorRef(
            ctx = ctx,
            widget = widget,
            hovered = hovered,
            pressed = pressed,
            disabled = disabled
        )
        Win32NativeWindowHost.drawTextInRect(
            hdc = ctx.hdc,
            text = displayLabel,
            left = left + 10,
            top = top,
            right = (right - arrowW - 4).coerceAtLeast(left + 12),
            bottom = bottom,
            colorRef = fgColorRef,
            center = false
        )

        val hwnd = ctx.hwnd
        ctx.registerHitRegion(
            Win32NativeWindowHost.HitRegion(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                tooltip = selectImpl?.getTooltipText()?.takeIf { it.isNotBlank() && it != displayLabel },
                widget = widget,
                onClick = { mx, my ->
                    if (selectImpl != null && hwnd != null) {
                        Win32NativeWindowHost.openSelectPopupMenu(hwnd, selectImpl, mx, my)
                    } else {
                        (widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.dispatchClick()
                    }
                }
            )
        )
        return bottom
    }

    /**
     * Renders a `DropdownButton` using native `uxtheme.dll` `BP_PUSHBUTTON` + `BootstrapType`
     * tinting and a dropdown chevron indicator.
     */
    fun renderDropdownButton(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        dropdownImpl: DesktopDropdownNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 30
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)

        val hovered = ctx.isWidgetHovered(widget, left, top, right, bottom)
        val pressed = ctx.isWidgetPressed(widget) || widget.attributes["open"] == true
        val disabled = !widget.enabled
        val stateId = when {
            disabled -> Win32DwmUxThemeBridge.PBS_DISABLED
            pressed -> Win32DwmUxThemeBridge.PBS_PRESSED
            hovered -> Win32DwmUxThemeBridge.PBS_HOT
            else -> Win32DwmUxThemeBridge.PBS_NORMAL
        }
        val rawLabel = (widget.attributes["label"] as? String)
            ?: (widget.attributes["text"] as? String)
            ?: Win32NativeWindowHost.extractWidgetText(widget)
        val displayText = if (rawLabel.endsWith("▾")) rawLabel else "$rawLabel  ▾"

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
            text = displayText,
            font = ctx.fontNormal,
            centerText = true,
            hovered = hovered,
            pressed = pressed,
            disabled = disabled
        )

        ctx.registerHitRegion(
            Win32NativeWindowHost.HitRegion(
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                tooltip = dropdownImpl?.getTooltipText(),
                widget = widget,
                onClick = { _, _ ->
                    if (dropdownImpl != null) {
                        val wasOpen = widget.attributes["open"] == true
                        widget.attributes["open"] = !wasOpen
                        dropdownImpl.dispatchClick()
                    } else {
                        (widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>)?.dispatchClick()
                    }
                }
            )
        )
        return bottom
    }

    /**
     * Renders a `SegmentedButton` group where each segment is a native `uxtheme.dll` `BP_PUSHBUTTON`
     * with `BootstrapType` tinting on the active segment.
     */
    fun renderSegmentedButton(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 30
    ): Int {
        val children = ctx.visibleChildrenOf(widget)
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)
        if (children.isEmpty()) {
            Win32ButtonRenderUtil.render(ctx, widget, left, top, right, fixedHeight = height)
            return bottom
        }
        val segCount = children.size
        val totalW = (right - left).coerceAtLeast(segCount * 24)
        val segW = totalW / segCount
        for ((idx, seg) in children.withIndex()) {
            val sL = left + idx * segW
            val sR = if (idx == segCount - 1) right else (sL + segW)
            Win32ButtonRenderUtil.render(ctx, seg, sL, top, sR, fixedHeight = height)
        }
        return bottom
    }

    /**
     * Renders a `ChipGroup` as a horizontal wrap of native chip buttons.
     */
    fun renderChipGroup(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        val children = ctx.visibleChildrenOf(widget)
        if (children.isEmpty()) return top
        var curX = left
        var curY = top
        val rowH = 28
        val gap = 6
        for (chip in children) {
            val txt = Win32NativeWindowHost.extractWidgetText(chip).trim()
            val chipW = (txt.length * 7 + 26).coerceIn(48, max(64, right - left))
            if (curX + chipW > right && curX > left) {
                curX = left
                curY += rowH + gap
            }
            val cR = min(right, curX + chipW)
            Win32ButtonRenderUtil.render(ctx, chip, curX, curY, cR, fixedHeight = rowH)
            curX = cR + gap
        }
        val bottom = curY + rowH
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), bottom - top)
        return bottom
    }

    private val CHECK_RADIO_SWITCH_CLASSES = setOf("checkbox", "radiobutton", "switch")
    private val CHECK_RADIO_SWITCH_ROLES = setOf("checkbox", "radio", "switch")
    private val TOGGLE_SELECTION_CLASSES = setOf(
        "checkbox",
        "radiobutton",
        "switch",
        "select",
        "combobox",
        "dropdownbutton",
        "segmentedbutton",
        "chipgroup"
    )
    private val TOGGLE_SELECTION_ROLES = setOf(
        "checkbox",
        "radio",
        "switch",
        "select",
        "combobox",
        "dropdown-button",
        "segmented-button",
        "chip-group"
    )
}
