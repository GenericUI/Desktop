package com.nexustools.genericui.qt.render

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyGridNativeImpl
import com.nexustools.genericui.desktop.render.DesktopControlTintRenderUtil
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopDropdownNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopMarkdownViewNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopProgressBarNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopRangeSliderNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSelectNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopSpinnerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopStyledContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTabNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTableNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextInputNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapSize
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.bootstrap.TextAlignment
import com.nexustools.genericui.enums.bootstrap.TextTransform
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.qt.QtWidgetHandle
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.FastBackdropBlurKernel
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.threading.GenericUiThreading
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object QtControlRenderUtil {

    var onRequestTextInputFocus: ((DesktopTextInputNativeImpl<*>) -> Unit)? = null
    var onRequestSelectCycle: ((DesktopSelectNativeImpl<*>, Rect) -> Unit)? = null
    var onRequestDropdownPopup: ((DesktopDropdownNativeImpl<*>, Rect) -> Unit)? = null

    private val IGNORED_AESTHETIC_AND_TYPOGRAPHY_ATTRIBUTES = listOf(
        "backgroundColor",
        "backgroundGradient",
        "backgroundImageUrl",
        "textColor",
        "accentColor",
        "borderColor",
        "borderWidthPx",
        "borderStyle",
        "borderRadiusPx",
        "boxShadow",
        "textShadow",
        "fontFamily",
        "fontSizePx",
        "fontWeight",
        "fontWeightNumeric",
        "fontStyle",
        "lineHeightPx",
        "letterSpacingPx",
        "textAlign",
        "textDecoration",
        "textTransform",
        "textOverflow",
        "whiteSpace",
        "wordBreak",
        "backdropBlurPx",
        "backdropFilter"
    )

    fun isDarkThemeActive(): Boolean {
        val effectiveTheme = Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()
        return effectiveTheme == ThemeMode.DARK
    }

    fun syncThemeAttributes(widget: QtWidgetHandle): Boolean {
        val effectiveTheme = Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()
        val isDark = effectiveTheme == ThemeMode.DARK
        widget.attributes["effectiveThemeMode"] = effectiveTheme
        widget.attributes["qtColorScheme"] = if (isDark) "Qt::ColorScheme::Dark" else "Qt::ColorScheme::Light"
        return isDark
    }

    fun syncStandardControlAppearance(
        impl: DesktopBaseNativeImpl<QtWidgetHandle>,
        qStyleControlElement: String
    ) {
        val widget = impl.nativeWidget
        val isDark = syncThemeAttributes(widget)
        widget.attributes["qStyleControlElement"] = qStyleControlElement

        if (impl.acceptsAestheticSkinStyles()) {
            widget.attributes["systemStyleRuleBypassedForCustomHost"] = true
            widget.attributes["useSystemControlAppearance"] = "false"
            return
        }

        for (ignoredKey in IGNORED_AESTHETIC_AND_TYPOGRAPHY_ATTRIBUTES) {
            widget.attributes.remove(ignoredKey)
        }
        widget.attributes.remove("backgroundGradient")
        widget.attributes["systemStyleRuleBypassedForCustomHost"] = false

        val snapshot = impl.getStyleSnapshot()
        val activeType = snapshot.type ?: snapshot.backgroundType
        if (activeType != null && activeType != BootstrapType.NONE) {
            val tint = BootstrapColorPalette.resolveColor(activeType, isDark)
            val fg = DesktopControlTintRenderUtil.resolveBootstrapForegroundOrNull(
                type = activeType,
                outlineMode = snapshot.outline,
                hovered = impl.isHoveredState(),
                pressed = impl.isActivePressedState(),
                darkMode = isDark
            ) ?: BootstrapColorPalette.resolveForegroundOn(activeType, isDark)
            widget.attributes["useSystemControlAppearance"] = "false"
            widget.attributes["nativeControlRenderMode"] = "offscreen_empty_text_sprite_tint"
            widget.attributes["bootstrapTintColor"] = tint.toHex()
            widget.attributes["bootstrapForegroundColor"] = fg.toHex()
            widget.qssStyleSheet = "QWidget { background-color: ${tint.toHex()}; color: ${fg.toHex()}; }"
        } else {
            widget.attributes["useSystemControlAppearance"] = "true"
            widget.attributes["nativeControlRenderMode"] = "unmodified_native_control"
            widget.attributes.remove("bootstrapTintColor")
            widget.attributes["bootstrapForegroundColor"] =
                BootstrapColorPalette.resolveForegroundOn(BootstrapType.NONE, isDark).toHex()
            widget.qssStyleSheet = ""
        }
        val semanticBgType = snapshot.backgroundType?.takeIf { it != BootstrapType.NONE && it.isSemantic }
        if (semanticBgType != null && snapshot.type == null) {
            val tint = BootstrapColorPalette.resolveColor(semanticBgType, isDark)
            widget.attributes["qPaletteWindowTint"] = tint.toHex()
        } else {
            widget.attributes.remove("qPaletteWindowTint")
        }
    }

    fun syncTypographyElementAppearance(
        impl: DesktopBaseNativeImpl<QtWidgetHandle>,
        qtTypographyElement: String
    ) {
        val widget = impl.nativeWidget
        val isDark = syncThemeAttributes(widget)
        widget.attributes["qStyleControlElement"] = qtTypographyElement
        widget.attributes["useSystemControlAppearance"] = "false"
        widget.attributes["systemStyleRuleBypassedForCustomHost"] = true

        val componentStyle = impl.getComponentStyle()
        val computedStyle = impl.getComputedStyle()
        val snapshot = impl.getStyleSnapshot()

        val resolvedTextColor = componentStyle?.color
            ?: computedStyle?.textColor
            ?: snapshot.textColorType?.takeIf { it.isSemantic }?.let {
                BootstrapColorPalette.resolveColor(it, isDark)
            }
            ?: snapshot.type?.takeIf { it.isSemantic }?.let {
                BootstrapColorPalette.resolveColor(it, isDark)
            }
        if (resolvedTextColor != null) {
            widget.attributes["textColor"] = resolvedTextColor
        } else {
            widget.attributes.remove("textColor")
        }

        val resolvedBgType = snapshot.backgroundType?.takeIf { it.isSemantic }
            ?: snapshot.type?.takeIf { it.isSemantic && widget.attributes["componentRole"] == "badge" }
        if (resolvedBgType != null) {
            widget.attributes["bootstrapTintColor"] =
                BootstrapColorPalette.resolveColor(resolvedBgType, isDark).toHex()
        } else {
            widget.attributes.remove("bootstrapTintColor")
        }

        val fontFamily = componentStyle?.fontFamily?.toCssString()?.trim()?.takeIf { it.isNotEmpty() }
            ?: computedStyle?.fontFamily?.trim()?.takeIf { it.isNotEmpty() }
        if (fontFamily != null) widget.attributes["fontFamily"] = fontFamily else widget.attributes.remove("fontFamily")

        val fontSizePx = componentStyle?.fontSize?.value ?: computedStyle?.fontSizePx
        if (fontSizePx != null) widget.attributes["fontSizePx"] = fontSizePx else widget.attributes.remove("fontSizePx")

        val fontWeight = componentStyle?.fontWeight ?: snapshot.fontWeight
        val fontWeightNum = componentStyle?.fontWeightNumeric ?: computedStyle?.fontWeightNumeric ?: fontWeight?.numericWeight
        if (fontWeight != null) {
            widget.attributes["fontWeight"] = fontWeight.name
            widget.attributes["fontWeightNumeric"] = fontWeightNum ?: fontWeight.numericWeight
        } else if (fontWeightNum != null) {
            widget.attributes["fontWeightNumeric"] = fontWeightNum
            widget.attributes.remove("fontWeight")
        } else {
            widget.attributes.remove("fontWeight")
            widget.attributes.remove("fontWeightNumeric")
        }

        val fontStyle = componentStyle?.fontStyle ?: computedStyle?.fontStyle
        if (fontStyle != null) widget.attributes["fontStyle"] = fontStyle.name else widget.attributes.remove("fontStyle")

        val lineHeightPx = componentStyle?.lineHeight?.resolvePx(fontSizePx ?: 14f)
            ?: computedStyle?.lineHeight?.resolvePx(fontSizePx ?: 14f)
        if (lineHeightPx != null) widget.attributes["lineHeightPx"] = lineHeightPx else widget.attributes.remove("lineHeightPx")

        val letterSpacingPx = componentStyle?.letterSpacing?.resolvePx(fontSizePx ?: 14f)
            ?: computedStyle?.letterSpacing?.resolvePx(fontSizePx ?: 14f)
        if (letterSpacingPx != null) widget.attributes["letterSpacingPx"] = letterSpacingPx else widget.attributes.remove("letterSpacingPx")

        val textAlign = componentStyle?.textAlign?.name
            ?: snapshot.textAlignment?.name
            ?: computedStyle?.textAlign?.uppercase()
        if (textAlign != null) widget.attributes["textAlign"] = textAlign else widget.attributes.remove("textAlign")

        val textDecoration = componentStyle?.textDecoration ?: computedStyle?.textDecoration
        if (textDecoration != null) widget.attributes["textDecoration"] = textDecoration.name else widget.attributes.remove("textDecoration")

        val textTransform = componentStyle?.textTransform ?: snapshot.textTransform ?: computedStyle?.textTransform
        if (textTransform != null) widget.attributes["textTransform"] = textTransform.name else widget.attributes.remove("textTransform")

        val textOverflow = componentStyle?.textOverflow ?: snapshot.textOverflow ?: computedStyle?.textOverflow
        if (textOverflow != null) widget.attributes["textOverflow"] = textOverflow.name else widget.attributes.remove("textOverflow")

        val whiteSpace = componentStyle?.whiteSpace?.cssValue ?: computedStyle?.whiteSpace
        if (whiteSpace != null) widget.attributes["whiteSpace"] = whiteSpace else widget.attributes.remove("whiteSpace")

        val wordBreak = componentStyle?.wordBreak ?: computedStyle?.wordBreak
        if (wordBreak != null) widget.attributes["wordBreak"] = wordBreak.name else widget.attributes.remove("wordBreak")

        val opacityVal = (componentStyle?.opacity ?: computedStyle?.opacity)?.coerceIn(0f, 1f)
        if (opacityVal != null) {
            widget.opacity = opacityVal
            widget.attributes["opacity"] = opacityVal
        }
        widget.qssStyleSheet = ""
    }

    fun renderOffscreenEmptyTextNativeSprite(
        width: Int,
        height: Int,
        isDark: Boolean,
        cornerRadiusPx: Float,
        hovered: Boolean,
        pressed: Boolean,
        disabled: Boolean,
        outlineOnly: Boolean = false
    ): IntArray {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val pixels = IntArray(w * h)

        val baseFillLum = when {
            outlineOnly -> 0
            disabled -> if (isDark) 62 else 212
            pressed -> if (isDark) 78 else 188
            hovered -> if (isDark) 98 else 228
            else -> if (isDark) 84 else 220
        }
        val topBevelShift = if (pressed) -8 else 12
        val bottomBevelShift = if (pressed) 8 else -14
        val borderLum = when {
            disabled -> if (isDark) 96 else 164
            pressed -> if (isDark) 136 else 128
            hovered -> if (isDark) 156 else 132
            else -> if (isDark) 128 else 148
        }
        val baseAlpha = when {
            outlineOnly -> 0
            disabled -> 185
            else -> 245
        }

        for (y in 0 until h) {
            val verticalRatio = if (h <= 1) 0f else y.toFloat() / (h - 1).toFloat()
            val rowBevel = ((1f - verticalRatio) * topBevelShift + verticalRatio * bottomBevelShift).roundToInt()
            val rowOffset = y * w
            for (x in 0 until w) {
                val cov = computeRoundedRectCoverage(x, y, w, h, cornerRadiusPx)
                if (cov <= 0.01f) {
                    pixels[rowOffset + x] = 0
                    continue
                }
                val isBorder = x <= 1 || y <= 1 || x >= w - 2 || y >= h - 2
                val lum = if (isBorder) borderLum else (baseFillLum + rowBevel).coerceIn(18, 250)
                val alpha = if (isBorder) {
                    ((if (disabled) 180 else 255) * cov).roundToInt().coerceIn(0, 255)
                } else {
                    (baseAlpha * cov).roundToInt().coerceIn(0, 255)
                }
                pixels[rowOffset + x] = (alpha shl 24) or (lum shl 16) or (lum shl 8) or lum
            }
        }
        return pixels
    }

    fun renderNativeControlWithOptionalBootstrapTint(
        impl: DesktopBaseNativeImpl<QtWidgetHandle>,
        qStyleControlElement: String,
        labelText: String,
        bounds: Rect,
        renderContext: Any?,
        cornerRadiusPx: Float = 5f,
        paintLiveTextToPainter: Boolean = true
    ): IntArray {
        val widget = impl.nativeWidget
        val isDark = syncThemeAttributes(widget)
        val snapshot = impl.getStyleSnapshot()
        val w = bounds.width.coerceAtLeast(24).coerceAtMost(1024)
        val h = bounds.height.coerceAtLeast(20).coerceAtMost(256)
        val hovered = impl.isHoveredState()
        val pressed = impl.isActivePressedState()
        val disabled = !widget.enabled
        val activeType = snapshot.type ?: snapshot.backgroundType

        val spritePixels = renderOffscreenEmptyTextNativeSprite(
            width = w,
            height = h,
            isDark = isDark,
            cornerRadiusPx = cornerRadiusPx,
            hovered = hovered,
            pressed = pressed,
            disabled = disabled,
            outlineOnly = snapshot.outline
        )

        val qtCtx = renderContext as? QtRenderContext
        val themeCfg = qtCtx?.themeConfig ?: BootstrapColorPalette.activeThemeConfig
        val foregroundColor: RgbaColor
        if (activeType != null && activeType != BootstrapType.NONE) {
            val tintColor = BootstrapColorPalette.resolveColor(activeType, isDark, themeCfg)
            DesktopControlTintRenderUtil.tintNativeControlPixels(
                pixels = spritePixels,
                width = w,
                height = h,
                tint = tintColor,
                outlineMode = snapshot.outline,
                hovered = hovered,
                pressed = pressed,
                disabled = disabled
            )
            foregroundColor = DesktopControlTintRenderUtil.resolveBootstrapForegroundOrNull(
                type = activeType,
                outlineMode = snapshot.outline,
                hovered = hovered,
                pressed = pressed,
                darkMode = isDark
            ) ?: BootstrapColorPalette.resolveForegroundOn(activeType, isDark, themeCfg)

            widget.attributes["useSystemControlAppearance"] = "false"
            widget.attributes["renderedOffscreenEmptyTextSprite"] = true
            widget.attributes["bootstrapTintColor"] = tintColor.toHex()
            widget.attributes["bootstrapForegroundColor"] = foregroundColor.toHex()
            widget.attributes["contrastingTextRenderedAfterTint"] = true
        } else {
            foregroundColor = if (isDark) RgbaColor(239, 240, 241) else RgbaColor(35, 38, 41)
            widget.attributes["useSystemControlAppearance"] = "true"
            widget.attributes["renderedOffscreenEmptyTextSprite"] = false
            widget.attributes.remove("bootstrapTintColor")
            widget.attributes["bootstrapForegroundColor"] = foregroundColor.toHex()
            widget.attributes["contrastingTextRenderedAfterTint"] = false
        }

        if (labelText.isNotEmpty()) {
            blitTextSliceToArgbBuffer(spritePixels, w, 0, 0, w, h, labelText, foregroundColor, disabled)
        }

        if (renderContext is IntArray && renderContext.size >= spritePixels.size) {
            spritePixels.copyInto(renderContext, destinationOffset = 0, startIndex = 0, endIndex = spritePixels.size)
        } else if (qtCtx != null) {
            blitSpriteToQtContext(qtCtx, spritePixels, w, h, bounds)
            if (paintLiveTextToPainter && labelText.isNotEmpty()) {
                val fgAlpha = if (disabled) foregroundColor.withAlpha(0.6f) else foregroundColor
                QtGraphicsDrawUtil.drawText(
                    ctx = qtCtx,
                    text = labelText,
                    x = bounds.x + 8,
                    y = bounds.y,
                    w = (bounds.width - 16).coerceAtLeast(1),
                    h = bounds.height,
                    color = fgAlpha,
                    fontSizePx = 13f,
                    weightNumeric = 500,
                    alignment = TextAlignment.CENTER,
                    singleLine = true
                )
            }
        }

        widget.attributes["lastRenderedQStyleElement"] = qStyleControlElement
        widget.attributes["lastRenderedWidth"] = w
        widget.attributes["lastRenderedHeight"] = h
        widget.attributes["lastRenderedText"] = labelText
        return spritePixels
    }

    @Suppress("UNCHECKED_CAST")
    fun renderLeafControl(
        impl: DesktopBaseNativeImpl<*>,
        renderContext: Any?,
        bounds: Rect
    ): Boolean = when (impl) {
        is DesktopButtonNativeImpl<*> -> renderButton(impl as DesktopButtonNativeImpl<QtWidgetHandle>, renderContext, bounds)
        is DesktopStyledButtonNativeImpl<*> -> renderStyledButton(impl as DesktopStyledButtonNativeImpl<QtWidgetHandle>, renderContext, bounds)
        is DesktopTextNativeImpl<*> -> renderText(impl as DesktopTextNativeImpl<QtWidgetHandle>, renderContext, bounds)
        is DesktopMarkdownViewNativeImpl<*> -> renderMarkdownView(impl as DesktopMarkdownViewNativeImpl<QtWidgetHandle>, renderContext, bounds)
        is DesktopTextInputNativeImpl<*> -> renderTextInput(impl as DesktopTextInputNativeImpl<QtWidgetHandle>, renderContext, bounds)
        is DesktopCheckboxNativeImpl<*> -> renderCheckbox(impl as DesktopCheckboxNativeImpl<QtWidgetHandle>, renderContext, bounds)
        is DesktopSelectNativeImpl<*> -> renderSelect(impl as DesktopSelectNativeImpl<QtWidgetHandle>, renderContext, bounds)
        is DesktopDropdownNativeImpl<*> -> renderDropdown(impl as DesktopDropdownNativeImpl<QtWidgetHandle>, renderContext, bounds)
        is DesktopRangeSliderNativeImpl<*> -> renderRangeSlider(impl as DesktopRangeSliderNativeImpl<QtWidgetHandle>, renderContext, bounds)
        is DesktopProgressBarNativeImpl<*> -> renderProgressBar(impl as DesktopProgressBarNativeImpl<QtWidgetHandle>, renderContext, bounds)
        is DesktopSpinnerNativeImpl<*> -> renderSpinner(impl as DesktopSpinnerNativeImpl<QtWidgetHandle>, renderContext, bounds)
        is DesktopTableNativeImpl<*> -> {
            val weights = (impl.nativeWidget.attributes["columnWeights"] as? List<Float>) ?: emptyList()
            renderTable(impl as DesktopTableNativeImpl<QtWidgetHandle>, weights, renderContext, bounds)
        }
        is DesktopImageNativeImpl<*> -> {
            (renderContext as? QtRenderContext)?.let { QtGraphicsDrawUtil.drawImageNative(it, impl, bounds) }
            true
        }
        else -> false
    }

    fun measureButton(impl: DesktopButtonNativeImpl<QtWidgetHandle>, dpiScale: Float = 1f): Size {
        val textLen = impl.getText().length
        val hasIcon = !impl.getIcon().isNullOrBlank()
        val sizeScale = when (impl.getSize()) {
            BootstrapSize.SM -> 0.85f
            BootstrapSize.LG -> 1.2f
            else -> 1.0f
        }
        val baseW = (max(70, textLen * 8 + (if (hasIcon) 24 else 0) + 28) * sizeScale * dpiScale).roundToInt()
        val baseH = (30 * sizeScale * dpiScale).roundToInt()
        return Size(baseW.toFloat(), baseH.toFloat())
    }

    fun renderButton(
        impl: DesktopButtonNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "QStyle::CE_PushButton[QStyleOptionButton]")
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            qStyleControlElement = "QStyle::CE_PushButton[QStyleOptionButton]",
            labelText = impl.getText(),
            bounds = bounds,
            renderContext = renderContext
        )
        (renderContext as? QtRenderContext)?.registerHitRegion(
            QtHitRegion(
                left = bounds.x,
                top = bounds.y,
                right = bounds.right,
                bottom = bounds.bottom,
                widget = impl.nativeWidget,
                cursorCss = "pointer",
                onClick = {
                    if (impl.isEnabled()) {
                        GenericUiThreading.runOnLogic { impl.dispatchClick() }
                    }
                }
            )
        )
        return true
    }

    fun renderStyledButton(
        impl: DesktopStyledButtonNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        val widget = impl.nativeWidget
        val isDark = syncThemeAttributes(widget)
        widget.attributes["useSystemControlAppearance"] = "false"
        widget.attributes["qStyleControlElement"] = "QPushButton_QPainter_Styled"
        widget.attributes["lastRenderedWidth"] = bounds.width.coerceAtLeast(1)
        widget.attributes["lastRenderedHeight"] = bounds.height.coerceAtLeast(1)

        val qtCtx = renderContext as? QtRenderContext ?: return true
        val spec = impl.resolveCurrentVisuals()
        val radius = spec.borderRadiusPx ?: 6f
        if (spec.hasBackdropFilter) {
            applyBackdropFilterRegion(
                ctx = qtCtx,
                bounds = bounds,
                radiusPx = radius,
                backdropBlurPx = spec.backdropBlurPx,
                backdropFilter = spec.backdropFilter
            )
        }
        if (spec.backgroundGradient != null) {
            QtGraphicsDrawUtil.fillGradientRoundedRect(qtCtx, bounds.x, bounds.y, bounds.width, bounds.height, radius, spec.backgroundGradient!!)
        } else {
            val fill = spec.backgroundColor ?: if (spec.hasBackdropFilter) RgbaColor.TRANSPARENT else qtCtx.palette.bgControl
            if (fill.a > 0) {
                QtGraphicsDrawUtil.fillRoundedRect(qtCtx, bounds.x, bounds.y, bounds.width, bounds.height, radius, fill)
            }
        }
        val borderCol = spec.borderColor
        val borderW = spec.borderWidthPx ?: if (borderCol != null) 1f else 0f
        if (borderCol != null && borderW > 0f) {
            QtGraphicsDrawUtil.strokeRoundedRect(qtCtx, bounds.x, bounds.y, bounds.width, bounds.height, radius, borderW, borderCol)
        }
        val text = impl.getText()
        if (text.isNotEmpty()) {
            val fg = spec.textColor ?: BootstrapColorPalette.resolveForegroundOn(BootstrapType.NONE, isDark, qtCtx.themeConfig)
            QtGraphicsDrawUtil.drawText(
                ctx = qtCtx,
                text = text,
                x = bounds.x + 8,
                y = bounds.y,
                w = (bounds.width - 16).coerceAtLeast(1),
                h = bounds.height,
                color = fg,
                fontSizePx = 13.5f,
                weightNumeric = 600,
                alignment = TextAlignment.CENTER,
                singleLine = true
            )
        }
        qtCtx.registerHitRegion(
            QtHitRegion(
                left = bounds.x,
                top = bounds.y,
                right = bounds.right,
                bottom = bounds.bottom,
                widget = widget,
                cursorCss = "pointer",
                onClick = {
                    if (impl.isEnabled()) {
                        GenericUiThreading.runOnLogic { impl.dispatchClick() }
                    }
                }
            )
        )
        return true
    }

    fun measureText(impl: DesktopTextNativeImpl<QtWidgetHandle>, dpiScale: Float = 1f): Size {
        val fontSize = (impl.nativeWidget.attributes["fontSizePx"] as? Float) ?: 14f
        val weightNum = (impl.nativeWidget.attributes["fontWeightNumeric"] as? Int) ?: 400
        val text = applyTextTransform(impl.getText(), impl.getStyleSnapshot().textTransform ?: impl.getComponentStyle()?.textTransform)
        val measured = QtGraphicsDrawUtil.measureText(text, fontSize, weightNum)
        return Size(measured.width * dpiScale, measured.height * dpiScale)
    }

    fun renderText(
        impl: DesktopTextNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncTypographyElementAppearance(impl, "QStyle::drawItemText[QLabel]")
        val transform = impl.getComponentStyle()?.textTransform ?: impl.getStyleSnapshot().textTransform
        val renderedText = applyTextTransform(impl.getText(), transform)
        impl.nativeWidget.attributes["renderedText"] = renderedText

        val qtCtx = renderContext as? QtRenderContext
        val fg = (impl.nativeWidget.attributes["textColor"] as? RgbaColor)
            ?: qtCtx?.palette?.textPrimary
            ?: BootstrapColorPalette.resolveForegroundOn(BootstrapType.NONE, isDarkThemeActive())

        if (qtCtx != null) {
            val badgeType = impl.getStyleSnapshot().backgroundType?.takeIf { it.isSemantic }
                ?: impl.getStyleSnapshot().type?.takeIf { it.isSemantic && impl.nativeWidget.attributes["componentRole"] == "badge" }
            if (badgeType != null) {
                val bgCol = BootstrapColorPalette.resolveColor(badgeType, qtCtx.darkMode, qtCtx.themeConfig)
                QtGraphicsDrawUtil.fillRoundedRect(qtCtx, bounds.x, bounds.y, bounds.width, bounds.height, 4f, bgCol)
            }
            val fontSizePx = (impl.nativeWidget.attributes["fontSizePx"] as? Float) ?: 14f
            val weightNum = (impl.nativeWidget.attributes["fontWeightNumeric"] as? Int) ?: 400
            val italic = (impl.nativeWidget.attributes["fontStyle"] as? String)?.equals("ITALIC", ignoreCase = true) == true
            val align = when (impl.nativeWidget.attributes["textAlign"] as? String) {
                "CENTER" -> TextAlignment.CENTER
                "RIGHT", "END" -> TextAlignment.END
                else -> TextAlignment.START
            }
            val singleLine = (impl.nativeWidget.attributes["whiteSpace"] as? String)?.equals("NOWRAP", ignoreCase = true) == true
            QtGraphicsDrawUtil.drawText(
                ctx = qtCtx,
                text = renderedText,
                x = bounds.x,
                y = bounds.y,
                w = bounds.width,
                h = bounds.height,
                color = fg,
                fontSizePx = fontSizePx,
                weightNumeric = weightNum,
                italic = italic,
                fontFamily = impl.nativeWidget.attributes["fontFamily"] as? String,
                alignment = align,
                singleLine = singleLine
            )
            return true
        }

        if (renderContext is IntArray && renderContext.isNotEmpty()) {
            val w = bounds.width.coerceAtLeast(16).coerceAtMost(256)
            val h = bounds.height.coerceAtLeast(16).coerceAtMost(128)
            if (renderContext.size >= w * h) {
                blitTextSliceToArgbBuffer(renderContext, w, 0, 0, w, h, renderedText, fg, !impl.nativeWidget.enabled)
            }
        }
        return true
    }

    fun renderMarkdownView(
        impl: DesktopMarkdownViewNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncTypographyElementAppearance(impl, "QTextDocument_QTextBrowser_Markdown")
        val md = impl.getMarkdown()
        impl.nativeWidget.attributes["renderedMarkdownLength"] = md.length
        impl.nativeWidget.attributes["lastRenderedWidth"] = bounds.width.coerceAtLeast(1)
        impl.nativeWidget.attributes["lastRenderedHeight"] = bounds.height.coerceAtLeast(1)

        val qtCtx = renderContext as? QtRenderContext ?: return true
        val fg = (impl.nativeWidget.attributes["textColor"] as? RgbaColor) ?: qtCtx.palette.textPrimary
        val fontSizePx = (impl.nativeWidget.attributes["fontSizePx"] as? Float) ?: 14f
        val plain = md.lines().joinToString("\n") { it.trimStart('#', ' ', '*', '-', '>') }
        QtGraphicsDrawUtil.drawText(
            ctx = qtCtx,
            text = plain,
            x = bounds.x + 4,
            y = bounds.y + 4,
            w = (bounds.width - 8).coerceAtLeast(1),
            h = (bounds.height - 8).coerceAtLeast(1),
            color = fg,
            fontSizePx = fontSizePx,
            singleLine = false
        )
        return true
    }

    fun renderTextInput(
        impl: DesktopTextInputNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        val elem = if (impl.isMultiline()) {
            "QStyle::CE_ShapedFrame[QPlainTextEdit]"
        } else {
            "QStyle::PE_PanelLineEdit[QStyleOptionFrame]"
        }
        syncStandardControlAppearance(impl, elem)
        val display = if (impl.isPasswordMode()) {
            "•".repeat(impl.getValue().length)
        } else {
            impl.getValue().ifEmpty { impl.getPlaceholder() }
        }
        val qtCtx = renderContext as? QtRenderContext
        val spritePixels = renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            qStyleControlElement = elem,
            labelText = display,
            bounds = bounds,
            renderContext = renderContext,
            paintLiveTextToPainter = false
        )

        val fieldLabel = impl.getLabel()?.trim()?.takeIf { it.isNotEmpty() }
        if (qtCtx != null) {
            val widget = impl.nativeWidget
            val focused = qtCtx.focusedWidget === widget
            val inputBg = qtCtx.palette.bgInput
            val borderColor = if (focused) qtCtx.palette.accentPrimary else qtCtx.palette.borderStrong
            QtGraphicsDrawUtil.fillRoundedRect(qtCtx, bounds.x, bounds.y, bounds.width, bounds.height, 6f, inputBg)
            QtGraphicsDrawUtil.strokeRoundedRect(
                qtCtx, bounds.x, bounds.y, bounds.width, bounds.height, 6f,
                if (focused) 2f else 1f, borderColor
            )
            if (display.isNotEmpty()) {
                val isPlaceholder = impl.getValue().isEmpty()
                val textColor = if (isPlaceholder) qtCtx.palette.textMuted else qtCtx.palette.textPrimary
                QtGraphicsDrawUtil.drawText(
                    ctx = qtCtx,
                    text = display,
                    x = bounds.x + 10,
                    y = bounds.y + (if (fieldLabel != null) 4 else 0),
                    w = (bounds.width - 20).coerceAtLeast(1),
                    h = (bounds.height - (if (fieldLabel != null) 4 else 0)).coerceAtLeast(1),
                    color = textColor,
                    fontSizePx = 13.5f,
                    singleLine = !impl.isMultiline()
                )
            }
            if (fieldLabel != null) {
                val badgeW = min(bounds.width - 20, max(24, QtGraphicsDrawUtil.measureText(fieldLabel, 11f, 500).width + 10))
                if (badgeW > 12) {
                    QtGraphicsDrawUtil.fillRect(qtCtx, bounds.x + 10, bounds.y - 6, badgeW, 13, inputBg)
                    QtGraphicsDrawUtil.drawText(
                        ctx = qtCtx,
                        text = fieldLabel,
                        x = bounds.x + 14,
                        y = bounds.y - 7,
                        w = badgeW - 8,
                        h = 14,
                        color = if (focused) qtCtx.palette.accentPrimary else qtCtx.palette.textSecondary,
                        fontSizePx = 11f,
                        weightNumeric = 500,
                        singleLine = true
                    )
                }
            }
            qtCtx.registerHitRegion(
                QtHitRegion(
                    left = bounds.x,
                    top = bounds.y,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    widget = widget,
                    cursorCss = "text",
                    isTextInput = true,
                    onClick = { onRequestTextInputFocus?.invoke(impl) }
                )
            )
        }

        if (fieldLabel != null) {
            impl.nativeWidget.attributes["lastRenderedLabel"] = fieldLabel
            val w = bounds.width.coerceAtLeast(24).coerceAtMost(512)
            val h = bounds.height.coerceAtLeast(20).coerceAtMost(256)
            val isDark = qtCtx?.darkMode ?: syncThemeAttributes(impl.nativeWidget)
            val captionColor = if (isDark) RgbaColor(180, 190, 208) else RgbaColor(82, 92, 108)
            overlayTopBorderCaptionGlyphs(
                pixels = spritePixels,
                width = w,
                height = h,
                caption = fieldLabel,
                foreground = captionColor,
                disabled = !impl.nativeWidget.enabled
            )
            if (renderContext is IntArray && renderContext.size >= spritePixels.size) {
                spritePixels.copyInto(renderContext, destinationOffset = 0, startIndex = 0, endIndex = spritePixels.size)
            }
        } else {
            impl.nativeWidget.attributes.remove("lastRenderedLabel")
        }
        return true
    }

    fun renderCheckbox(
        impl: DesktopCheckboxNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        val elem = when {
            impl.switchStyle -> "QStyle::CE_CheckBox[SwitchIndicator]"
            impl.radioStyle -> "QStyle::CE_RadioButton[QStyleOptionButton]"
            else -> "QStyle::CE_CheckBox[QStyleOptionButton]"
        }
        syncStandardControlAppearance(impl, elem)
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            qStyleControlElement = elem,
            labelText = impl.getLabel(),
            bounds = bounds,
            renderContext = renderContext,
            paintLiveTextToPainter = false
        )
        val qtCtx = renderContext as? QtRenderContext ?: return true
        val checked = impl.isChecked()
        val accent = impl.getStyleSnapshot().type?.takeIf { it.isSemantic }?.let {
            BootstrapColorPalette.resolveColor(it, qtCtx.darkMode, qtCtx.themeConfig)
        } ?: qtCtx.palette.accentPrimary

        if (impl.switchStyle) {
            val trackW = 36
            val trackH = 20
            val ty = bounds.y + (bounds.height - trackH) / 2
            val trackColor = if (checked) accent else qtCtx.palette.bgMuted
            QtGraphicsDrawUtil.fillRoundedRect(qtCtx, bounds.x, ty, trackW, trackH, trackH / 2f, trackColor)
            val knobX = if (checked) bounds.x + trackW - 10f else bounds.x + 10f
            QtGraphicsDrawUtil.fillCircle(qtCtx, knobX, ty + trackH / 2f, 7.5f, RgbaColor.WHITE)
            if (impl.getLabel().isNotEmpty()) {
                QtGraphicsDrawUtil.drawText(
                    qtCtx, impl.getLabel(), bounds.x + trackW + 8, bounds.y,
                    (bounds.width - trackW - 8).coerceAtLeast(1), bounds.height,
                    qtCtx.palette.textPrimary, 13.5f, singleLine = true
                )
            }
        } else {
            val boxSz = 18
            val by = bounds.y + (bounds.height - boxSz) / 2
            val radius = if (impl.radioStyle) 9f else 4f
            val fillCol = if (checked) accent else qtCtx.palette.bgInput
            QtGraphicsDrawUtil.fillRoundedRect(qtCtx, bounds.x, by, boxSz, boxSz, radius, fillCol)
            QtGraphicsDrawUtil.strokeRoundedRect(
                qtCtx, bounds.x, by, boxSz, boxSz, radius, 1f,
                if (checked) accent else qtCtx.palette.borderStrong
            )
            if (checked) {
                if (impl.radioStyle) {
                    QtGraphicsDrawUtil.fillCircle(qtCtx, bounds.x + boxSz / 2f, by + boxSz / 2f, 4f, RgbaColor.WHITE)
                } else {
                    QtGraphicsDrawUtil.drawText(qtCtx, "✓", bounds.x, by, boxSz, boxSz, RgbaColor.WHITE, 12f, 700, alignment = TextAlignment.CENTER)
                }
            }
            if (impl.getLabel().isNotEmpty()) {
                QtGraphicsDrawUtil.drawText(
                    qtCtx, impl.getLabel(), bounds.x + boxSz + 8, bounds.y,
                    (bounds.width - boxSz - 8).coerceAtLeast(1), bounds.height,
                    qtCtx.palette.textPrimary, 13.5f, singleLine = true
                )
            }
        }

        qtCtx.registerHitRegion(
            QtHitRegion(
                left = bounds.x,
                top = bounds.y,
                right = bounds.right,
                bottom = bounds.bottom,
                widget = impl.nativeWidget,
                cursorCss = "pointer",
                onClick = {
                    if (impl.isEnabled()) {
                        GenericUiThreading.runOnLogic { impl.setChecked(!impl.isChecked()) }
                    }
                }
            )
        )
        return true
    }

    fun renderSelect(
        impl: DesktopSelectNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "QStyle::CC_ComboBox[QStyleOptionComboBox]")
        val selectedValue = impl.getSelectedValue()
        val selectedLabel = impl.getOptions().firstOrNull { it.first == selectedValue }?.second
            ?: selectedValue.orEmpty()
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            qStyleControlElement = "QStyle::CC_ComboBox[QStyleOptionComboBox]",
            labelText = selectedLabel,
            bounds = bounds,
            renderContext = renderContext,
            paintLiveTextToPainter = false
        )
        val qtCtx = renderContext as? QtRenderContext ?: return true
        QtGraphicsDrawUtil.fillRoundedRect(qtCtx, bounds.x, bounds.y, bounds.width, bounds.height, 6f, qtCtx.palette.bgInput)
        QtGraphicsDrawUtil.strokeRoundedRect(qtCtx, bounds.x, bounds.y, bounds.width, bounds.height, 6f, 1f, qtCtx.palette.borderStrong)
        QtGraphicsDrawUtil.drawText(
            qtCtx, selectedLabel, bounds.x + 10, bounds.y,
            (bounds.width - 28).coerceAtLeast(1), bounds.height,
            qtCtx.palette.textPrimary, 13.5f, singleLine = true
        )
        QtGraphicsDrawUtil.drawText(
            qtCtx, "▾", bounds.right - 22, bounds.y, 18, bounds.height,
            qtCtx.palette.textSecondary, 12f, alignment = TextAlignment.CENTER
        )
        qtCtx.registerHitRegion(
            QtHitRegion(
                left = bounds.x,
                top = bounds.y,
                right = bounds.right,
                bottom = bounds.bottom,
                widget = impl.nativeWidget,
                cursorCss = "pointer",
                onClick = { onRequestSelectCycle?.invoke(impl, bounds) }
            )
        )
        return true
    }

    fun renderDropdown(
        impl: DesktopDropdownNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "QStyle::CC_ToolButton[QToolButton_MenuButtonPopup]")
        val label = (impl.nativeWidget.attributes["label"] as? String).orEmpty()
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            qStyleControlElement = "QStyle::CC_ToolButton[QToolButton_MenuButtonPopup]",
            labelText = "$label ▾".trim(),
            bounds = bounds,
            renderContext = renderContext
        )
        val qtCtx = renderContext as? QtRenderContext ?: return true
        qtCtx.registerHitRegion(
            QtHitRegion(
                left = bounds.x,
                top = bounds.y,
                right = bounds.right,
                bottom = bounds.bottom,
                widget = impl.nativeWidget,
                cursorCss = "pointer",
                onClick = { onRequestDropdownPopup?.invoke(impl, bounds) }
            )
        )
        return true
    }

    fun renderRangeSlider(
        impl: DesktopRangeSliderNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "QStyle::CC_Slider[QStyleOptionSlider]")
        val qtCtx = renderContext as? QtRenderContext ?: return true
        val minVal = impl.getMin()
        val maxVal = impl.getMax().let { if (it <= minVal) minVal + 1.0 else it }
        val ratio = ((impl.getValue() - minVal) / (maxVal - minVal)).toFloat().coerceIn(0f, 1f)
        val accent = impl.getStyleSnapshot().type?.takeIf { it.isSemantic }?.let {
            BootstrapColorPalette.resolveColor(it, qtCtx.darkMode, qtCtx.themeConfig)
        } ?: qtCtx.palette.accentPrimary

        val trackPad = 10
        val trackX = bounds.x + trackPad
        val trackW = (bounds.width - trackPad * 2).coerceAtLeast(16)
        val trackY = bounds.y + bounds.height / 2 - 3
        QtGraphicsDrawUtil.fillRoundedRect(qtCtx, trackX, trackY, trackW, 6, 3f, qtCtx.palette.bgMuted)
        val fillW = (trackW * ratio).roundToInt().coerceAtLeast(0)
        if (fillW > 0) {
            QtGraphicsDrawUtil.fillRoundedRect(qtCtx, trackX, trackY, fillW, 6, 3f, accent)
        }
        val thumbX = trackX + (trackW * ratio)
        QtGraphicsDrawUtil.fillCircle(qtCtx, thumbX, bounds.y + bounds.height / 2f, 8f, RgbaColor.WHITE)

        val updateFromAbsX: (Float) -> Unit = { absX ->
            val t = ((absX - trackX) / trackW.toFloat()).coerceIn(0f, 1f)
            val newVal = minVal + t * (maxVal - minVal)
            GenericUiThreading.runOnLogic { impl.setValue(newVal) }
        }
        qtCtx.registerHitRegion(
            QtHitRegion(
                left = bounds.x,
                top = bounds.y,
                right = bounds.right,
                bottom = bounds.bottom,
                widget = impl.nativeWidget,
                cursorCss = "pointer",
                onClick = { updateFromAbsX(thumbX) },
                onDrag = { _, _, screenX, _ -> updateFromAbsX(screenX) },
                onRelease = { _, _, screenX, _, _ -> updateFromAbsX(screenX) }
            )
        )
        return true
    }

    fun renderProgressBar(
        impl: DesktopProgressBarNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "QStyle::CE_ProgressBar[QStyleOptionProgressBar]")
        val qtCtx = renderContext as? QtRenderContext ?: return true
        val ratio = (if (impl.isIndeterminate() || impl.isBusy()) 0.4f else impl.getProgress()).coerceIn(0f, 1f)
        val accent = impl.getStyleSnapshot().type?.takeIf { it.isSemantic }?.let {
            BootstrapColorPalette.resolveColor(it, qtCtx.darkMode, qtCtx.themeConfig)
        } ?: qtCtx.palette.accentPrimary
        val barH = bounds.height.coerceAtMost(14).coerceAtLeast(6)
        val barY = bounds.y + (bounds.height - barH) / 2
        QtGraphicsDrawUtil.fillRoundedRect(qtCtx, bounds.x, barY, bounds.width, barH, barH / 2f, qtCtx.palette.bgMuted)
        val fillW = (bounds.width * ratio).roundToInt().coerceAtLeast(if (ratio > 0f) 4 else 0)
        if (fillW > 0) {
            QtGraphicsDrawUtil.fillRoundedRect(qtCtx, bounds.x, barY, fillW, barH, barH / 2f, accent)
        }
        return true
    }

    fun renderSpinner(
        impl: DesktopSpinnerNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "QStyle::PE_IndicatorProgress[QWidget_QMovieSpinner]")
        val qtCtx = renderContext as? QtRenderContext ?: return true
        val accent = impl.getStyleSnapshot().type?.takeIf { it.isSemantic }?.let {
            BootstrapColorPalette.resolveColor(it, qtCtx.darkMode, qtCtx.themeConfig)
        } ?: qtCtx.palette.accentPrimary
        val radius = min(bounds.width, bounds.height).coerceIn(12, 32) / 2f - 2f
        val cx = bounds.x + bounds.width / 2f
        val cy = bounds.y + bounds.height / 2f
        QtGraphicsDrawUtil.strokeRoundedRect(
            qtCtx,
            (cx - radius).roundToInt(),
            (cy - radius).roundToInt(),
            (radius * 2f).roundToInt(),
            (radius * 2f).roundToInt(),
            radius,
            2.5f,
            accent
        )
        return true
    }

    fun renderContainer(
        impl: DesktopContainerNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "QStyle::PE_Widget[QWidget_QBoxLayout]")
        val qtCtx = renderContext as? QtRenderContext ?: return true
        val bgHex = impl.nativeWidget.attributes["qPaletteWindowTint"] as? String
        if (bgHex != null) {
            QtGraphicsDrawUtil.fillRoundedRect(qtCtx, bounds.x, bounds.y, bounds.width, bounds.height, 8f, RgbaColor.fromHex(bgHex))
        }
        return true
    }

    fun applyBackdropFilterRegion(
        ctx: QtRenderContext,
        bounds: Rect,
        radiusPx: Float,
        backdropBlurPx: Float?,
        backdropFilter: CssFilter?
    ) {
        val effFilter = CssFilter.backdropBlurOrFilter(backdropBlurPx, backdropFilter) ?: return
        if (ctx.bufferWidth <= 0 || ctx.bufferHeight <= 0 || bounds.width <= 0 || bounds.height <= 0) return
        val clipL = max(bounds.x, ctx.clipLeft)
        val clipT = max(bounds.y, ctx.clipTop)
        val clipR = min(bounds.x + bounds.width, ctx.clipRight)
        val clipB = min(bounds.y + bounds.height, ctx.clipBottom)
        val w = clipR - clipL
        val h = clipB - clipT
        if (w <= 0 || h <= 0) return
        val effectiveRadius = if (clipL == bounds.x && clipT == bounds.y && clipR == bounds.x + bounds.width && clipB == bounds.y + bounds.height) {
            radiusPx.coerceAtLeast(0f)
        } else {
            0f
        }
        FastBackdropBlurKernel.applyBackdropToArgbRegion(
            filter = effFilter,
            argbPixels = ctx.argbPixels,
            bufferWidth = ctx.bufferWidth,
            bufferHeight = ctx.bufferHeight,
            regionX = clipL,
            regionY = clipT,
            regionWidth = w,
            regionHeight = h,
            cornerRadiusPx = effectiveRadius
        )
    }

    fun renderStyledContainer(
        impl: DesktopStyledContainerNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        val widget = impl.nativeWidget
        syncThemeAttributes(widget)
        widget.attributes["useSystemControlAppearance"] = "false"
        widget.attributes["qStyleControlElement"] = "QWidget_QPainter_StyledContainer"
        widget.attributes["lastRenderedWidth"] = bounds.width.coerceAtLeast(1)
        widget.attributes["lastRenderedHeight"] = bounds.height.coerceAtLeast(1)
        val qtCtx = renderContext as? QtRenderContext
        val visuals = impl.getStyledVisuals()
        if (qtCtx != null) {
            val radius = visuals.borderRadiusPx ?: 0f
            if (visuals.hasBackdropFilter) {
                applyBackdropFilterRegion(
                    ctx = qtCtx,
                    bounds = bounds,
                    radiusPx = radius,
                    backdropBlurPx = visuals.backdropBlurPx,
                    backdropFilter = visuals.backdropFilter
                )
            }
            if (visuals.backgroundGradient != null) {
                QtGraphicsDrawUtil.fillGradientRoundedRect(qtCtx, bounds.x, bounds.y, bounds.width, bounds.height, radius, visuals.backgroundGradient!!)
            } else {
                visuals.backgroundColor?.takeIf { it.a > 0 }?.let { bg ->
                    QtGraphicsDrawUtil.fillRoundedRect(qtCtx, bounds.x, bounds.y, bounds.width, bounds.height, radius, bg)
                }
            }
            val bw = visuals.borderWidthPx ?: if (visuals.borderColor != null) 1f else 0f
            visuals.borderColor?.takeIf { it.a > 0 && bw > 0f }?.let { bc ->
                QtGraphicsDrawUtil.strokeRoundedRect(qtCtx, bounds.x, bounds.y, bounds.width, bounds.height, radius, bw, bc)
            }
        }
        return true
    }

    fun renderTabContainer(
        impl: DesktopTabContainerNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "QStyle::CE_TabBarTabShape[QTabWidget]")
        return true
    }

    fun renderTab(
        impl: DesktopTabNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "QStyle::CE_TabBarTab[QStyleOptionTab]")
        return true
    }

    fun renderTable(
        impl: DesktopTableNativeImpl<QtWidgetHandle>,
        columnWeights: List<Float>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "QStyle::CE_ItemViewItem[QTableWidget]")
        val headers = (impl.nativeWidget.attributes["headers"] as? List<*>)?.map { it.toString() }.orEmpty()
        val rows = (impl.nativeWidget.attributes["rows"] as? List<*>)
            ?.mapNotNull { row -> (row as? List<*>)?.map { it.toString() } }
            .orEmpty()
        val colCount = max(headers.size, columnWeights.size).coerceAtLeast(1)
        val totalWidth = bounds.width.coerceAtLeast(colCount * 40)
        val resolvedColumnWidths = computeWeightedColumnWidths(totalWidth, colCount, columnWeights)
        impl.nativeWidget.attributes["resolvedColumnWidthsPx"] = resolvedColumnWidths

        val qtCtx = renderContext as? QtRenderContext ?: return true
        QtGraphicsDrawUtil.fillRoundedRect(qtCtx, bounds.x, bounds.y, bounds.width, bounds.height, 6f, qtCtx.palette.bgCard)
        QtGraphicsDrawUtil.strokeRoundedRect(qtCtx, bounds.x, bounds.y, bounds.width, bounds.height, 6f, 1f, qtCtx.palette.borderSubtle)
        val rowH = 30
        if (headers.isNotEmpty()) {
            QtGraphicsDrawUtil.fillRect(qtCtx, bounds.x + 1, bounds.y + 1, (bounds.width - 2).coerceAtLeast(1), rowH, qtCtx.palette.bgHeader)
            var cx = bounds.x + 8
            for (c in headers.indices) {
                val cw = resolvedColumnWidths.getOrElse(c) { 80 }
                QtGraphicsDrawUtil.drawText(qtCtx, headers[c], cx, bounds.y + 2, (cw - 12).coerceAtLeast(8), rowH - 4, qtCtx.palette.textPrimary, 12.5f, 600, singleLine = true)
                cx += cw
            }
        }
        var ry = bounds.y + (if (headers.isNotEmpty()) rowH else 2)
        for (row in rows) {
            if (ry + rowH > bounds.bottom) break
            var cx = bounds.x + 8
            for (c in 0 until min(row.size, colCount)) {
                val cw = resolvedColumnWidths.getOrElse(c) { 80 }
                QtGraphicsDrawUtil.drawText(qtCtx, row[c], cx, ry + 2, (cw - 12).coerceAtLeast(8), rowH - 4, qtCtx.palette.textSecondary, 12.5f, singleLine = true)
                cx += cw
            }
            ry += rowH
        }
        return true
    }

    fun renderLazyGrid(
        impl: DesktopLazyGridNativeImpl<QtWidgetHandle>,
        minColumnWidthPx: Int,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "QStyle::CE_ItemViewItem[QListView_IconMode]")
        val availW = bounds.width.coerceAtLeast(minColumnWidthPx.coerceAtLeast(1))
        val effectiveCols = resolveAdaptiveGridColumns(
            configuredColumns = impl.getColumnCount(),
            minColumnWidthPx = minColumnWidthPx,
            availableWidthPx = availW
        )
        impl.nativeWidget.attributes["effectiveColumns"] = effectiveCols
        return true
    }

    fun computeWeightedColumnWidths(
        totalWidthPx: Int,
        columnCount: Int,
        weights: List<Float>
    ): IntArray {
        val count = columnCount.coerceAtLeast(1)
        val safeTotal = totalWidthPx.coerceAtLeast(count)
        if (weights.isEmpty()) {
            val base = safeTotal / count
            return IntArray(count) { idx -> if (idx == count - 1) safeTotal - base * (count - 1) else base }
        }
        val normalizedWeights = FloatArray(count) { idx ->
            weights.getOrNull(idx)?.takeIf { it > 0f } ?: 1f
        }
        val weightSum = normalizedWeights.sum().coerceAtLeast(1e-4f)
        var remaining = safeTotal
        return IntArray(count) { idx ->
            if (idx == count - 1) {
                remaining.coerceAtLeast(1)
            } else {
                val w = ((safeTotal * normalizedWeights[idx]) / weightSum).roundToInt().coerceAtLeast(1)
                remaining -= w
                w
            }
        }
    }

    fun resolveAdaptiveGridColumns(
        configuredColumns: Int,
        minColumnWidthPx: Int,
        availableWidthPx: Int
    ): Int {
        val maxCols = configuredColumns.coerceAtLeast(1)
        if (minColumnWidthPx <= 0 || availableWidthPx <= 0) return maxCols
        val fitCols = (availableWidthPx / minColumnWidthPx).coerceAtLeast(1)
        return min(maxCols, fitCols)
    }

    private fun applyTextTransform(text: String, transform: TextTransform?): String = when (transform) {
        TextTransform.UPPERCASE -> text.uppercase()
        TextTransform.LOWERCASE -> text.lowercase()
        TextTransform.CAPITALIZE -> text.split(' ').joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
        else -> text
    }

    private fun computeRoundedRectCoverage(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        radius: Float
    ): Float {
        if (radius <= 0.5f) return 1f
        val px = x + 0.5f
        val py = y + 0.5f
        val r = min(radius, min(width, height) / 2f)
        val cx = when {
            px < r -> r
            px > width - r -> width - r
            else -> px
        }
        val cy = when {
            py < r -> r
            py > height - r -> height - r
            else -> py
        }
        val dx = px - cx
        val dy = py - cy
        val dist = sqrt(dx * dx + dy * dy)
        return (r + 0.5f - dist).coerceIn(0f, 1f)
    }

    private fun blitSpriteToQtContext(
        ctx: QtRenderContext,
        sprite: IntArray,
        spriteW: Int,
        spriteH: Int,
        bounds: Rect
    ) {
        if (spriteW <= 0 || spriteH <= 0 || bounds.width <= 0 || bounds.height <= 0) return
        val l = max(bounds.x, ctx.clipLeft).coerceAtLeast(0)
        val t = max(bounds.y, ctx.clipTop).coerceAtLeast(0)
        val r = min(bounds.right, ctx.clipRight).coerceAtMost(ctx.bufferWidth)
        val b = min(bounds.bottom, ctx.clipBottom).coerceAtMost(ctx.bufferHeight)
        if (r <= l || b <= t) return
        val dstPixels = ctx.argbPixels
        for (py in t until b) {
            val row = py * ctx.bufferWidth
            val sy = (((py - bounds.y).toLong() * spriteH) / bounds.height.coerceAtLeast(1)).toInt().coerceIn(0, spriteH - 1)
            val sRow = sy * spriteW
            for (px in l until r) {
                val sx = (((px - bounds.x).toLong() * spriteW) / bounds.width.coerceAtLeast(1)).toInt().coerceIn(0, spriteW - 1)
                val sp = sprite[sRow + sx]
                val a = (((sp ushr 24) and 0xFF) / 255f) * ctx.currentOpacity
                if (a <= 0.002f) continue
                val sr = (sp ushr 16) and 0xFF
                val sg = (sp ushr 8) and 0xFF
                val sb = sp and 0xFF
                if (a >= 0.996f) {
                    dstPixels[row + px] = (255 shl 24) or (sr shl 16) or (sg shl 8) or sb
                } else {
                    val dst = dstPixels[row + px]
                    val dstA = ((dst ushr 24) and 0xFF) / 255f
                    val dstR = (dst ushr 16) and 0xFF
                    val dstG = (dst ushr 8) and 0xFF
                    val dstB = dst and 0xFF
                    val outA = (a + dstA * (1f - a)).coerceIn(0f, 1f)
                    val inv = 1f - a
                    val outR = ((sr * a + dstR * dstA * inv) / outA).roundToInt().coerceIn(0, 255)
                    val outG = ((sg * a + dstG * dstA * inv) / outA).roundToInt().coerceIn(0, 255)
                    val outB = ((sb * a + dstB * dstA * inv) / outA).roundToInt().coerceIn(0, 255)
                    val outAByte = (outA * 255f).roundToInt().coerceIn(0, 255)
                    dstPixels[row + px] = (outAByte shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }
        }
    }

    internal fun blitTextSliceToArgbBuffer(
        dst: IntArray,
        dstStride: Int,
        dstX: Int,
        dstY: Int,
        width: Int,
        height: Int,
        text: String,
        foreground: RgbaColor,
        disabled: Boolean
    ) {
        if (width < 8 || height < 8 || text.isBlank()) return
        val glyphCount = min(text.length, (width - 12).coerceAtLeast(1) / 6)
        if (glyphCount <= 0) return
        val totalTextWidth = glyphCount * 5 + (glyphCount - 1)
        val startX = dstX + ((width - totalTextWidth) / 2).coerceAtLeast(4)
        val centerY = dstY + height / 2
        val topY = (centerY - 3).coerceAtLeast(dstY + 2)
        val bottomY = (centerY + 3).coerceAtMost(dstY + height - 3)
        val fgAlpha = ((if (disabled) foreground.a * 0.6f else foreground.a.toFloat()) / 255f).coerceIn(0f, 1f)

        for (i in 0 until glyphCount) {
            val ch = text[i]
            if (ch.isWhitespace()) continue
            val gx = startX + i * 6
            for (gy in topY..bottomY) {
                val rowOffset = gy * dstStride
                for (dx in 0..3) {
                    val px = gx + dx
                    if (px >= dstX + width - 2 || px < 0 || rowOffset + px >= dst.size) continue
                    val bit = ((ch.code + dx * 3 + (gy - topY) * 5) and 1) == 0 ||
                        gy == topY || (dx == 0 && gy < bottomY)
                    if (!bit) continue
                    val idx = rowOffset + px
                    val cur = dst[idx]
                    val curA = (cur ushr 24) and 0xFF
                    val curR = (cur ushr 16) and 0xFF
                    val curG = (cur ushr 8) and 0xFF
                    val curB = cur and 0xFF
                    val outR = (curR * (1f - fgAlpha) + foreground.r * fgAlpha).roundToInt().coerceIn(0, 255)
                    val outG = (curG * (1f - fgAlpha) + foreground.g * fgAlpha).roundToInt().coerceIn(0, 255)
                    val outB = (curB * (1f - fgAlpha) + foreground.b * fgAlpha).roundToInt().coerceIn(0, 255)
                    val outA = max(curA, (fgAlpha * 255f).roundToInt()).coerceIn(0, 255)
                    dst[idx] = (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }
        }
    }

    private fun overlayTopBorderCaptionGlyphs(
        pixels: IntArray,
        width: Int,
        height: Int,
        caption: String,
        foreground: RgbaColor,
        disabled: Boolean
    ) {
        if (width < 24 || height < 12 || caption.isBlank()) return
        val maxGlyphs = ((width - 20).coerceAtLeast(0)) / 5
        val glyphCount = min(caption.length, maxGlyphs)
        if (glyphCount <= 0) return
        val startX = 10
        val spanW = glyphCount * 5 + 4
        val interiorSample = pixels[min(4, height - 1) * width + min(startX, width - 1)]
        val maskEndX = min(width - 2, startX + spanW)
        for (my in 0..min(2, height - 1)) {
            val rowOffset = my * width
            for (mx in (startX - 2).coerceAtLeast(1) until maskEndX) {
                pixels[rowOffset + mx] = interiorSample
            }
        }
        val fgAlpha = ((if (disabled) foreground.a * 0.6f else foreground.a.toFloat()) / 255f).coerceIn(0f, 1f)
        for (i in 0 until glyphCount) {
            val ch = caption[i]
            if (ch.isWhitespace()) continue
            val gx = startX + i * 5
            for (gy in 0..min(4, height - 2)) {
                val rowOffset = gy * width
                for (dx in 0..3) {
                    val px = gx + dx
                    if (px >= width - 2) continue
                    val bit = ((ch.code + dx * 3 + gy * 5) and 1) == 0 ||
                        gy == 0 || (dx == 0 && gy < 4)
                    if (!bit) continue
                    val idx = rowOffset + px
                    val cur = pixels[idx]
                    val curA = (cur ushr 24) and 0xFF
                    val curR = (cur ushr 16) and 0xFF
                    val curG = (cur ushr 8) and 0xFF
                    val curB = cur and 0xFF
                    val outR = (curR * (1f - fgAlpha) + foreground.r * fgAlpha).roundToInt().coerceIn(0, 255)
                    val outG = (curG * (1f - fgAlpha) + foreground.g * fgAlpha).roundToInt().coerceIn(0, 255)
                    val outB = (curB * (1f - fgAlpha) + foreground.b * fgAlpha).roundToInt().coerceIn(0, 255)
                    val outA = max(curA, (fgAlpha * 255f).roundToInt()).coerceIn(0, 255)
                    pixels[idx] = (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }
        }
    }
}
