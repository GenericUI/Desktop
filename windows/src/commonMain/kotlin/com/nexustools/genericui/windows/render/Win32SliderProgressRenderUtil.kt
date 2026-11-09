@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.render

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.widget.DesktopProgressBarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopRangeSliderNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSpinnerNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.windows.Win32DwmUxThemeBridge
import com.nexustools.genericui.windows.Win32NativeWindowHost
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders range slider, progress bar, spinner, meter, and rating bar controls on Windows
 * (`RangeSlider`, `ProgressBar`, `Spinner`, `Meter`, `RatingBar`).
 *
 * Uses native `uxtheme.dll` `TRACKBAR` (`TKP_TRACK` / `TKP_THUMB`) and `PROGRESS`
 * (`PP_BAR` / `PP_FILL`) control parts with `BootstrapType` tinting via
 * [Win32ControlTintRenderUtil], while ignoring aesthetic `ComponentStyle` skin overrides.
 */
object Win32SliderProgressRenderUtil {

    fun canRender(widget: DesktopNativeWidget): Boolean {
        val impl = widget.attributes["nativeImpl"]
        if (impl is DesktopRangeSliderNativeImpl<*> ||
            impl is DesktopProgressBarNativeImpl<*> ||
            impl is DesktopSpinnerNativeImpl<*>
        ) {
            return true
        }
        val cls = widget.nativeClassName.lowercase()
        val role = (widget.attributes["componentRole"] as? String ?: "").lowercase()
        return cls in SLIDER_PROGRESS_CLASSES || role in SLIDER_PROGRESS_ROLES
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
            impl is DesktopRangeSliderNativeImpl<*> || cls == "rangeslider" || role == "range-slider" ->
                renderRangeSlider(ctx, widget, impl as? DesktopRangeSliderNativeImpl<*>, left, top, right)
            impl is DesktopSpinnerNativeImpl<*> || cls == "spinner" || role == "spinner" || "spinner-border" in widget.cachedAllClassTokens ->
                renderSpinner(ctx, widget, impl as? DesktopSpinnerNativeImpl<*>, left, top, right)
            cls == "ratingbar" || role == "rating-bar" ->
                renderRatingBar(ctx, widget, left, top, right)
            cls == "meter" || role == "meter" ->
                renderMeter(ctx, widget, impl as? DesktopProgressBarNativeImpl<*>, left, top, right)
            else ->
                renderProgressBar(ctx, widget, impl as? DesktopProgressBarNativeImpl<*>, left, top, right)
        }
    }

    /**
     * Renders a `RangeSlider` using `uxtheme.dll` `TRACKBAR` (`TKP_TRACK` + `TKP_THUMB`) and
     * `PROGRESS` `PP_FILL` for the filled portion, tinted by `BootstrapType` when specified.
     */
    fun renderRangeSlider(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        sliderImpl: DesktopRangeSliderNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 26
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)

        val minVal = sliderImpl?.getMin() ?: 0.0
        val maxVal = (sliderImpl?.getMax() ?: 100.0).coerceAtLeast(minVal + 0.0001)
        val curVal = (sliderImpl?.getValue() ?: minVal).coerceIn(minVal, maxVal)
        val fraction = ((curVal - minVal) / (maxVal - minVal)).toFloat().coerceIn(0f, 1f)

        val hovered = ctx.isWidgetHovered(widget, left, top, right, bottom)
        val pressed = ctx.isWidgetPressed(widget)
        val disabled = !widget.enabled
        val bootstrapType = Win32ControlTintRenderUtil.resolveWidgetBootstrapType(widget)

        val trackH = 6
        val trackT = top + (height - trackH) / 2
        val trackB = trackT + trackH
        val thumbW = 14
        val thumbH = 20
        val usableW = (right - left - thumbW).coerceAtLeast(1)
        val thumbL = left + (usableW * fraction).roundToInt()
        val thumbR = thumbL + thumbW
        val thumbT = top + (height - thumbH) / 2
        val thumbB = thumbT + thumbH

        // 1. Native TRACKBAR groove
        val drewTrack = Win32DwmUxThemeBridge.drawUxThemeControlBackground(
            hwnd = ctx.hwnd,
            hdc = ctx.hdc,
            classList = Win32DwmUxThemeBridge.resolveExplorerClassList("TRACKBAR", ctx.darkMode),
            partId = Win32DwmUxThemeBridge.TKP_TRACK,
            stateId = Win32DwmUxThemeBridge.TRS_NORMAL,
            left = left,
            top = trackT,
            right = right,
            bottom = trackB
        )
        if (!drewTrack && sliderImpl != null) {
            Win32NativeWindowHost.drawSliderControl(ctx.hdc, ctx.systemTheme, sliderImpl, left, top, right, bottom, opacity = ctx.opacity)
        } else {
            // 2. Filled track portion (native PROGRESS fill or BootstrapType tint)
            val fillRight = (thumbL + thumbW / 2).coerceIn(left, right)
            if (fillRight > left + 1) {
                Win32ControlTintRenderUtil.drawNativeOrTintedControlSurface(
                    ctx = ctx,
                    widget = widget,
                    baseClass = "PROGRESS",
                    partId = Win32DwmUxThemeBridge.PP_FILL,
                    stateId = Win32DwmUxThemeBridge.PBFS_NORMAL,
                    left = left,
                    top = trackT,
                    right = fillRight,
                    bottom = trackB,
                    explicitType = bootstrapType ?: BootstrapType.PRIMARY,
                    hovered = hovered,
                    pressed = pressed,
                    disabled = disabled
                )
            }
            // 3. Native TRACKBAR thumb
            val thumbState = when {
                disabled -> Win32DwmUxThemeBridge.TUS_DISABLED
                pressed -> Win32DwmUxThemeBridge.TUS_PRESSED
                hovered -> Win32DwmUxThemeBridge.TUS_HOT
                else -> Win32DwmUxThemeBridge.TUS_NORMAL
            }
            Win32ControlTintRenderUtil.drawNativeOrTintedControlSurface(
                ctx = ctx,
                widget = widget,
                baseClass = "TRACKBAR",
                partId = Win32DwmUxThemeBridge.TKP_THUMB,
                stateId = thumbState,
                left = thumbL,
                top = thumbT,
                right = thumbR,
                bottom = thumbB,
                explicitType = bootstrapType,
                hovered = hovered,
                pressed = pressed,
                disabled = disabled
            )
        }

        if (sliderImpl != null) {
            ctx.registerHitRegion(
                Win32NativeWindowHost.HitRegion(
                    left = left,
                    top = top,
                    right = right,
                    bottom = bottom,
                    tooltip = sliderImpl.getTooltipText(),
                    widget = widget,
                    onClick = { mx, _ ->
                        sliderImpl.beginDragFromFraction((mx - left).toDouble() / max(1, right - left).toDouble())
                    },
                    onDrag = { mx, _ ->
                        sliderImpl.updateDragFromFraction((mx - left).toDouble() / max(1, right - left).toDouble())
                    },
                    onRelease = { mx, _ ->
                        sliderImpl.endDragFromFraction((mx - left).toDouble() / max(1, right - left).toDouble())
                    }
                )
            )
        }
        return bottom
    }

    /**
     * Renders a `ProgressBar` using `uxtheme.dll` `PROGRESS` (`PP_BAR` + `PP_FILL`) with
     * `BootstrapType` tinting and optional percentage label overlay.
     */
    fun renderProgressBar(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        progressImpl: DesktopProgressBarNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 20
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)

        val progress = progressImpl?.getProgress()
            ?: (widget.attributes["progress"] as? Number)?.toFloat()
            ?: 0f
        val indeterminate = progressImpl?.isIndeterminate() == true || widget.attributes["indeterminate"] == true
        val fraction = if (indeterminate) 0.35f else progress.coerceIn(0f, 1f)

        // 1. Native PROGRESS bar trough (`PP_BAR`)
        val drewBar = Win32DwmUxThemeBridge.drawUxThemeControlBackground(
            hwnd = ctx.hwnd,
            hdc = ctx.hdc,
            classList = Win32DwmUxThemeBridge.resolveExplorerClassList("PROGRESS", ctx.darkMode),
            partId = Win32DwmUxThemeBridge.PP_BAR,
            stateId = Win32DwmUxThemeBridge.PBFS_NORMAL,
            left = left,
            top = top,
            right = right,
            bottom = bottom
        )
        if (!drewBar) {
            Win32NativeWindowHost.drawRoundedRect(
                hdc = ctx.hdc,
                left = left,
                top = top,
                right = right,
                bottom = bottom,
                radius = 4,
                fillColor = ctx.bgElevatedColorRef,
                borderColor = ctx.borderSubtleColorRef
            )
        }

        // 2. Native PROGRESS fill (`PP_FILL`) with optional BootstrapType tint
        val totalW = (right - left).coerceAtLeast(1)
        val fillW = (totalW * fraction).roundToInt()
        if (fillW > 1) {
            val fillL = if (indeterminate) left + (totalW - fillW) / 3 else left
            val fillR = (fillL + fillW).coerceAtMost(right)
            val bootstrapType = Win32ControlTintRenderUtil.resolveWidgetBootstrapType(widget)
            Win32ControlTintRenderUtil.drawNativeOrTintedControlSurface(
                ctx = ctx,
                widget = widget,
                baseClass = "PROGRESS",
                partId = Win32DwmUxThemeBridge.PP_FILL,
                stateId = when (bootstrapType) {
                    BootstrapType.DANGER -> Win32DwmUxThemeBridge.PBFS_ERROR
                    BootstrapType.WARNING -> Win32DwmUxThemeBridge.PBFS_PAUSED
                    else -> Win32DwmUxThemeBridge.PBFS_NORMAL
                },
                left = fillL,
                top = top,
                right = fillR,
                bottom = bottom,
                explicitType = bootstrapType
            )
        }

        val label = (widget.attributes["label"] as? String).orEmpty()
        if (label.isNotEmpty()) {
            ctx.selectFont(ctx.fontSmallBold ?: ctx.fontSmall)
            val fgColorRef = Win32ControlTintRenderUtil.resolveControlForegroundColorRef(ctx, widget)
            Win32NativeWindowHost.drawTextInRect(
                hdc = ctx.hdc,
                text = label,
                left = left + 4,
                top = top,
                right = right - 4,
                bottom = bottom,
                colorRef = fgColorRef,
                center = true
            )
        }
        return bottom
    }

    /**
     * Renders a `Meter` gauge using `uxtheme.dll` `PROGRESS` parts and optimal/suboptimal/critical
     * semantic states.
     */
    fun renderMeter(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        progressImpl: DesktopProgressBarNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int
    ): Int = renderProgressBar(ctx, widget, progressImpl, left, top, right, height = 18)

    /**
     * Renders a `Spinner` activity indicator with `BootstrapType` color resolution.
     */
    fun renderSpinner(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        spinnerImpl: DesktopSpinnerNativeImpl<*>?,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 28
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)
        val tintColorRef = Win32ControlTintRenderUtil.resolveControlTintOrNull(ctx, widget)
            ?.let { ctx.toColorRef(it) }
            ?: ctx.accentBlueColorRef
        val label = (spinnerImpl?.nativeWidget?.attributes?.get("label") as? String)
            ?: (widget.attributes["label"] as? String)
            .orEmpty()
        val displayText = if (label.isNotEmpty()) "⟳  $label" else "⟳"
        ctx.selectFont(ctx.fontBold ?: ctx.fontNormal)
        Win32NativeWindowHost.drawTextInRect(
            hdc = ctx.hdc,
            text = displayText,
            left = left + 4,
            top = top,
            right = right - 4,
            bottom = bottom,
            colorRef = tintColorRef,
            center = label.isEmpty()
        )
        return bottom
    }

    /**
     * Renders an interactive `RatingBar` control (`★` / `☆`).
     */
    fun renderRatingBar(
        ctx: Win32RenderContext,
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int,
        height: Int = 28
    ): Int {
        val bottom = top + height
        widget.bounds = Rect(left, top, (right - left).coerceAtLeast(1), height)
        val maxStars = ((widget.attributes["maxRating"] as? Number)?.toInt() ?: 5).coerceIn(1, 10)
        val rating = ((widget.attributes["rating"] as? Number)?.toDouble() ?: 3.0).coerceIn(0.0, maxStars.toDouble())
        val starStr = buildString {
            for (i in 1..maxStars) {
                if (i <= rating.roundToInt()) append("★ ") else append("☆ ")
            }
        }.trim()
        val starColorRef = Win32ControlTintRenderUtil.resolveControlTintOrNull(ctx, widget, BootstrapType.WARNING)
            ?.let { ctx.toColorRef(it) }
            ?: ctx.accentAmberColorRef
        ctx.selectFont(ctx.fontTitle ?: ctx.fontBold)
        Win32NativeWindowHost.drawTextInRect(
            hdc = ctx.hdc,
            text = starStr,
            left = left + 4,
            top = top,
            right = right - 4,
            bottom = bottom,
            colorRef = starColorRef
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

    private val SLIDER_PROGRESS_CLASSES = setOf(
        "rangeslider",
        "progressbar",
        "spinner",
        "meter",
        "ratingbar"
    )
    private val SLIDER_PROGRESS_ROLES = setOf(
        "range-slider",
        "progress-bar",
        "spinner",
        "meter",
        "rating-bar"
    )
}
