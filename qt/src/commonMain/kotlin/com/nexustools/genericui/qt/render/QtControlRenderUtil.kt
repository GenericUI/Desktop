package com.nexustools.genericui.qt.render

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.lazy.DesktopLazyGridNativeImpl
import com.nexustools.genericui.desktop.render.DesktopControlTintRenderUtil
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopCheckboxNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopDropdownNativeImpl
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
import com.nexustools.genericui.enums.bootstrap.TextTransform
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.qt.QtWidgetHandle
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.theme.SystemThemeDetector
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Type-grouped Qt 6 control render utility (`QStyle::drawControl`, `QStyle::drawComplexControl`,
 * `QStyle::drawPrimitive`, `QStyleOptionButton`, `QStyleOptionComboBox`, `QStyleOptionFrame`,
 * `QStyleOptionSlider`, `QStyleOptionProgressBar`, `QStyleOptionTab`, `QStyleOptionViewItem`).
 *
 * Implements:
 * 1. **Native-Only Rendering when `BootstrapType == BootstrapType.NONE`**: Renders the 100% unmodified
 *    Qt6 control (`Qt::ColorScheme::Dark` / `Qt::ColorScheme::Light`).
 * 2. **Offscreen Empty-Text Native Control Sprite + `ThemeConfig` Color-Tint + Contrasting Text Overlay
 *    when `BootstrapType != BootstrapType.NONE`**: Renders the native Qt6 control with NO text
 *    (`opt.text = ""`) into an offscreen 32-bpp ARGB `QImage(w, h, QImage::Format_ARGB32)`, tints
 *    the control pixels using [BootstrapColorPalette.resolveColor] (from active `ThemeConfig`) via
 *    [DesktopControlTintRenderUtil.tintNativeControlPixels] while preserving native Qt6 `QStyle`
 *    bevels, borders, specular highlights, and hover/pressed/disabled states, blits the tinted native
 *    control, and renders the contrasting text and icon ([BootstrapColorPalette.resolveForegroundOn])
 *    on top afterward via `QPainter::drawText`.
 * 3. **System Backend Style Rules**:
 *    - Text / typography elements (`QtTextNativeImpl`, `QtMarkdownViewNativeImpl` — `Label`, `Text`,
 *      `Heading`, `Badge`, `Blockquote`, `CodeBlock`, `MarkdownView`) honor typography and text-color
 *      properties from `ComponentStyle` and `ComponentBootstrap`.
 *    - Standard buttons, inputs, controls, and containers silently ignore aesthetic and typography
 *      `ComponentStyle` overrides and can ONLY be tinted via `BootstrapType`, unless `StyledContainer` /
 *      `StyledButton` or `component.isHostedInCustomStyleEngine()` is used.
 */
object QtControlRenderUtil {

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
        widget.attributes.remove("backdropBlurPx")
        widget.attributes.remove("backdropFilter")
        return isDark
    }

    /**
     * Enforces System Backend Style Rules on standard native Qt6 controls and containers:
     * unless [impl] is a `StyledButton` / `StyledContainer` or is hosted inside a custom style
     * engine (`WebView`), aesthetic and typography `ComponentStyle` overrides are stripped and
     * tinting is resolved strictly from [BootstrapType].
     */
    fun syncStandardControlAppearance(
        impl: DesktopBaseNativeImpl<QtWidgetHandle>,
        qStyleControlElement: String
    ) {
        val widget = impl.nativeWidget
        val isDark = syncThemeAttributes(widget)
        widget.attributes["qStyleControlElement"] = qStyleControlElement

        if (!impl.acceptsAestheticSkinStyles() && !impl.isHostedInCustomStyleEngine()) {
            for (attr in IGNORED_AESTHETIC_AND_TYPOGRAPHY_ATTRIBUTES) {
                widget.attributes.remove(attr)
            }
        } else if (impl.isHostedInCustomStyleEngine()) {
            syncTypographyAndColorAttributes(impl, isDark, allowAestheticSkin = true)
        }

        val snapshot = impl.getStyleSnapshot()
        val activeType = (snapshot.type?.takeIf { it != BootstrapType.NONE }
            ?: snapshot.backgroundType?.takeIf { it != BootstrapType.NONE })

        if (activeType != null) {
            val tint = BootstrapColorPalette.resolveColor(activeType, isDark)
            val fg = DesktopControlTintRenderUtil.resolveBootstrapForegroundOrNull(
                type = activeType,
                outlineMode = snapshot.outline,
                hovered = impl.isHoveredState(),
                pressed = impl.isActivePressedState(),
                darkMode = isDark
            ) ?: BootstrapColorPalette.resolveForegroundOn(activeType, isDark)
            widget.attributes["useSystemControlAppearance"] = "false"
            widget.attributes["bootstrapType"] = activeType.name
            widget.attributes["bootstrapTintColor"] = tint.toHex()
            widget.attributes["bootstrapForegroundColor"] = fg.toHex()
            widget.attributes["nativeControlRenderMode"] = "offscreen_empty_text_sprite_tint"
        } else {
            widget.attributes["useSystemControlAppearance"] = "true"
            widget.attributes["bootstrapType"] = BootstrapType.NONE.name
            widget.attributes.remove("bootstrapTintColor")
            widget.attributes.remove("bootstrapForegroundColor")
            widget.attributes["nativeControlRenderMode"] = "unmodified_native_control"
        }
    }

    /**
     * Enforces System Backend Style Rules on text / typography elements (`QtTextNativeImpl`,
     * `QtMarkdownViewNativeImpl` — `Label`, `Text`, `Heading`, `Badge`, `Blockquote`, `CodeBlock`,
     * `MarkdownView`): honors typography and text-color properties from `ComponentStyle` and
     * `ComponentBootstrap`, while ignoring background/border skin overrides unless hosted in a
     * custom style engine or styled via `BootstrapType` (e.g., `Badge`).
     */
    fun syncTypographyElementAppearance(
        impl: DesktopBaseNativeImpl<QtWidgetHandle>,
        qStyleControlElement: String
    ) {
        val widget = impl.nativeWidget
        val isDark = syncThemeAttributes(widget)
        widget.attributes["qStyleControlElement"] = qStyleControlElement
        syncTypographyAndColorAttributes(
            impl = impl,
            isDark = isDark,
            allowAestheticSkin = impl.isHostedInCustomStyleEngine()
        )
    }

    private fun syncTypographyAndColorAttributes(
        impl: DesktopBaseNativeImpl<QtWidgetHandle>,
        isDark: Boolean,
        allowAestheticSkin: Boolean
    ) {
        val widget = impl.nativeWidget
        val style = impl.getComponentStyle()
        val computed = impl.getComputedStyle()
        val snapshot = impl.getStyleSnapshot()

        if (!allowAestheticSkin) {
            widget.attributes.remove("backgroundColor")
            widget.attributes.remove("backgroundGradient")
            widget.attributes.remove("backgroundImageUrl")
            widget.attributes.remove("borderColor")
            widget.attributes.remove("borderWidthPx")
            widget.attributes.remove("borderStyle")
            widget.attributes.remove("borderRadiusPx")
            widget.attributes.remove("boxShadow")
        }

        val resolvedTextColor = style?.color
            ?: computed?.textColor
            ?: snapshot.textColorType?.takeIf { it != BootstrapType.NONE }?.let {
                BootstrapColorPalette.resolveColor(it, isDark)
            }
            ?: snapshot.type?.takeIf { it != BootstrapType.NONE }?.let {
                BootstrapColorPalette.resolveForegroundOn(it, isDark)
            }
        if (resolvedTextColor != null) {
            widget.attributes["textColor"] = resolvedTextColor
            widget.attributes["resolvedTextColorHex"] = resolvedTextColor.toHex()
        } else {
            widget.attributes.remove("textColor")
            widget.attributes["resolvedTextColorHex"] =
                BootstrapColorPalette.resolveForegroundOn(BootstrapType.NONE, isDark).toHex()
        }

        val semanticBgType = snapshot.backgroundType?.takeIf { it != BootstrapType.NONE }
            ?: snapshot.type?.takeIf { it != BootstrapType.NONE }
        if (semanticBgType != null) {
            val tint = BootstrapColorPalette.resolveColor(semanticBgType, isDark)
            widget.attributes["bootstrapTintColor"] = tint.toHex()
        } else {
            widget.attributes.remove("bootstrapTintColor")
        }

        style?.fontFamily?.let { widget.attributes["fontFamily"] = it.toCssString() }
            ?: computed?.fontFamily?.let { widget.attributes["fontFamily"] = it }
            ?: widget.attributes.remove("fontFamily")

        val fontSizePx = style?.fontSize?.resolvePx() ?: computed?.fontSizePx
        if (fontSizePx != null) {
            widget.attributes["fontSizePx"] = fontSizePx
        } else {
            widget.attributes.remove("fontSizePx")
        }

        val fontWeight = style?.fontWeight ?: snapshot.fontWeight
        if (fontWeight != null) {
            widget.attributes["fontWeight"] = fontWeight.name
            widget.attributes["fontWeightNumeric"] = style?.fontWeightNumeric ?: fontWeight.numericWeight
        } else if (style?.fontWeightNumeric != null) {
            widget.attributes["fontWeightNumeric"] = style.fontWeightNumeric
        } else {
            widget.attributes.remove("fontWeight")
            widget.attributes.remove("fontWeightNumeric")
        }

        style?.fontStyle?.let { widget.attributes["fontStyle"] = it.cssValue }
            ?: widget.attributes.remove("fontStyle")

        style?.lineHeight?.resolvePx(fontSizePx ?: 14f)?.let { widget.attributes["lineHeightPx"] = it }
            ?: widget.attributes.remove("lineHeightPx")

        style?.letterSpacing?.resolvePx(fontSizePx ?: 14f)?.let { widget.attributes["letterSpacingPx"] = it }
            ?: widget.attributes.remove("letterSpacingPx")

        val textAlign = style?.textAlign ?: snapshot.textAlignment
        if (textAlign != null) {
            widget.attributes["textAlign"] = textAlign.name.lowercase()
        } else {
            widget.attributes.remove("textAlign")
        }

        style?.textDecoration?.let { widget.attributes["textDecoration"] = it.cssValue }
            ?: widget.attributes.remove("textDecoration")

        val textTransform = style?.textTransform ?: snapshot.textTransform
        if (textTransform != null) {
            widget.attributes["textTransform"] = textTransform.name.lowercase()
        } else {
            widget.attributes.remove("textTransform")
        }

        val textOverflow = style?.textOverflow ?: snapshot.textOverflow
        if (textOverflow != null) {
            widget.attributes["textOverflow"] = textOverflow.name.lowercase()
        } else {
            widget.attributes.remove("textOverflow")
        }

        style?.whiteSpace?.let { widget.attributes["whiteSpace"] = it.cssValue }
            ?: widget.attributes.remove("whiteSpace")

        style?.wordBreak?.let { widget.attributes["wordBreak"] = it.cssValue }
            ?: widget.attributes.remove("wordBreak")
    }

    /**
     * Renders an empty-text (`opt.text = ""`) Qt6 `QStyle` control (`QStyle::CE_PushButton`,
     * `QStyle::CE_CheckBox`, `QStyle::CE_RadioButton`, `QStyle::CC_ComboBox`,
     * `QStyle::PE_PanelLineEdit`, `QStyle::CC_Slider`, `QStyle::CE_ProgressBar`,
     * `QStyle::CE_TabBarTab`) into an offscreen 32-bpp ARGB pixel buffer with Fusion/Breeze
     * Light/Dark bevels, top highlight, inner/outer border frame, rounded corner anti-aliasing
     * alpha, and hover/pressed/disabled states.
     */
    fun renderOffscreenEmptyTextNativeSprite(
        qStyleControlElement: String,
        width: Int,
        height: Int,
        darkMode: Boolean,
        hovered: Boolean = false,
        pressed: Boolean = false,
        disabled: Boolean = false,
        checked: Boolean = false,
        progressFraction: Float = 0f
    ): IntArray {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val pixels = IntArray(w * h)

        val baseR = when {
            checked && (qStyleControlElement.contains("CheckBox") || qStyleControlElement.contains("RadioButton") || qStyleControlElement.contains("Switch")) -> 61
            darkMode -> when {
                pressed -> 48
                hovered -> 74
                else -> 62
            }
            else -> when {
                pressed -> 216
                hovered -> 246
                else -> 239
            }
        }
        val baseG = when {
            checked && (qStyleControlElement.contains("CheckBox") || qStyleControlElement.contains("RadioButton") || qStyleControlElement.contains("Switch")) -> 174
            darkMode -> when {
                pressed -> 50
                hovered -> 76
                else -> 64
            }
            else -> when {
                pressed -> 218
                hovered -> 247
                else -> 240
            }
        }
        val baseB = when {
            checked && (qStyleControlElement.contains("CheckBox") || qStyleControlElement.contains("RadioButton") || qStyleControlElement.contains("Switch")) -> 233
            darkMode -> when {
                pressed -> 54
                hovered -> 82
                else -> 68
            }
            else -> when {
                pressed -> 222
                hovered -> 250
                else -> 243
            }
        }

        val cornerRadius = when {
            qStyleControlElement.contains("RadioButton") || qStyleControlElement.contains("Spinner") -> min(w, h) / 2f
            qStyleControlElement.contains("Switch") -> h / 2f
            qStyleControlElement.contains("ProgressBar") || qStyleControlElement.contains("Slider") -> min(5f, h / 2f)
            else -> min(5f, min(w, h) / 3f)
        }
        val alphaMultiplier = if (disabled) 0.56f else 1.0f

        for (y in 0 until h) {
            val rowOffset = y * w
            val vRatio = if (h > 1) y.toFloat() / (h - 1).toFloat() else 0.5f
            val bevelDelta = when {
                pressed -> ((vRatio - 0.5f) * 14f).roundToInt()
                y == 1 -> if (darkMode) 20 else 14
                else -> ((0.5f - vRatio) * 18f).roundToInt()
            }

            for (x in 0 until w) {
                val cov = computeRoundedRectCoverage(x, y, w, h, cornerRadius)
                if (cov <= 0f) continue

                val edgeDist = min(min(x, w - 1 - x), min(y, h - 1 - y))
                val isBorder = edgeDist == 0
                val isInnerHighlight = edgeDist == 1 && y <= 2 && !pressed

                var r = baseR + bevelDelta
                var g = baseG + bevelDelta
                var b = baseB + bevelDelta

                if ((qStyleControlElement.contains("ProgressBar") || qStyleControlElement.contains("Slider")) && progressFraction > 0f) {
                    val fillW = (w * progressFraction.coerceIn(0f, 1f)).roundToInt()
                    if (x < fillW) {
                        r = 61
                        g = 174
                        b = 233
                    }
                }

                if (isBorder) {
                    val borderScale = if (darkMode) 0.60f else 0.73f
                    r = (r * borderScale).roundToInt()
                    g = (g * borderScale).roundToInt()
                    b = (b * borderScale).roundToInt()
                } else if (isInnerHighlight) {
                    r += 15
                    g += 15
                    b += 15
                }

                val outA = (cov * 255f * alphaMultiplier).roundToInt().coerceIn(0, 255)
                val outR = r.coerceIn(0, 255)
                val outG = g.coerceIn(0, 255)
                val outB = b.coerceIn(0, 255)
                pixels[rowOffset + x] = (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
            }
        }
        return pixels
    }

    /**
     * Executes the two-mode native control rendering pipeline for [impl] on Qt6:
     * - When `BootstrapType == NONE`: renders the 100% unmodified native Qt6 `QStyle` control.
     * - When `BootstrapType != NONE`: renders the native Qt6 control with NO text (`opt.text = ""`)
     *   into an offscreen 32-bpp ARGB `QImage`, tints the control pixels via
     *   [DesktopControlTintRenderUtil.tintNativeControlPixels] using the active `ThemeConfig`'s
     *   [BootstrapColorPalette.resolveColor], blits the tinted sprite, and renders contrasting text
     *   ([BootstrapColorPalette.resolveForegroundOn]) on top afterward via `QPainter::drawText`.
     */
    fun renderNativeControlWithOptionalBootstrapTint(
        impl: DesktopBaseNativeImpl<QtWidgetHandle>,
        qStyleControlElement: String,
        labelText: String,
        bounds: Rect = impl.nativeWidget.bounds,
        renderContext: Any? = null,
        checked: Boolean = false,
        progressFraction: Float = 0f
    ): IntArray {
        val widget = impl.nativeWidget
        val isDark = syncThemeAttributes(widget)
        val w = bounds.width.coerceAtLeast(24).coerceAtMost(512)
        val h = bounds.height.coerceAtLeast(20).coerceAtMost(256)
        val hovered = impl.isHoveredState()
        val pressed = impl.isActivePressedState()
        val disabled = !widget.enabled
        val snapshot = impl.getStyleSnapshot()
        val activeType = snapshot.type?.takeIf { it != BootstrapType.NONE }
            ?: snapshot.backgroundType?.takeIf { it != BootstrapType.NONE }

        val spritePixels = renderOffscreenEmptyTextNativeSprite(
            qStyleControlElement = qStyleControlElement,
            width = w,
            height = h,
            darkMode = isDark,
            hovered = hovered,
            pressed = pressed,
            disabled = disabled,
            checked = checked,
            progressFraction = progressFraction
        )

        val foregroundColor: RgbaColor
        if (activeType != null) {
            val tintColor = BootstrapColorPalette.resolveColor(activeType, isDark)
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
            ) ?: BootstrapColorPalette.resolveForegroundOn(activeType, isDark)

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
            overlayContrastingTextGlyphs(
                pixels = spritePixels,
                width = w,
                height = h,
                text = labelText,
                foreground = foregroundColor,
                disabled = disabled
            )
        }

        if (renderContext is IntArray && renderContext.size >= spritePixels.size) {
            spritePixels.copyInto(renderContext, destinationOffset = 0, startIndex = 0, endIndex = spritePixels.size)
        }

        widget.attributes["lastRenderedQStyleElement"] = qStyleControlElement
        widget.attributes["lastRenderedWidth"] = w
        widget.attributes["lastRenderedHeight"] = h
        widget.attributes["lastRenderedText"] = labelText
        return spritePixels
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
        return true
    }

    fun renderStyledButton(
        impl: DesktopStyledButtonNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        val widget = impl.nativeWidget
        syncThemeAttributes(widget)
        widget.attributes["useSystemControlAppearance"] = "false"
        widget.attributes["qStyleControlElement"] = "QPushButton_QPainter_Styled"
        widget.attributes["lastRenderedWidth"] = bounds.width.coerceAtLeast(1)
        widget.attributes["lastRenderedHeight"] = bounds.height.coerceAtLeast(1)
        return true
    }

    fun measureText(impl: DesktopTextNativeImpl<QtWidgetHandle>, dpiScale: Float = 1f): Size {
        val fontSize = (impl.nativeWidget.attributes["fontSizePx"] as? Float) ?: 14f
        val text = applyTextTransform(impl.getText(), impl.getStyleSnapshot().textTransform ?: impl.getComponentStyle()?.textTransform)
        val w = max(1, (text.length * fontSize * 0.56f * dpiScale).roundToInt())
        val h = max(16, (fontSize * 1.35f * dpiScale).roundToInt())
        return Size(w.toFloat(), h.toFloat())
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
        if (renderContext is IntArray && renderContext.isNotEmpty()) {
            val w = bounds.width.coerceAtLeast(16).coerceAtMost(256)
            val h = bounds.height.coerceAtLeast(16).coerceAtMost(128)
            if (renderContext.size >= w * h) {
                val fg = (impl.nativeWidget.attributes["textColor"] as? RgbaColor)
                    ?: BootstrapColorPalette.resolveForegroundOn(BootstrapType.NONE, isDarkThemeActive())
                overlayContrastingTextGlyphs(renderContext, w, h, renderedText, fg, !impl.nativeWidget.enabled)
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
        impl.nativeWidget.attributes["renderedMarkdownLength"] = impl.getMarkdown().length
        impl.nativeWidget.attributes["lastRenderedWidth"] = bounds.width.coerceAtLeast(1)
        impl.nativeWidget.attributes["lastRenderedHeight"] = bounds.height.coerceAtLeast(1)
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
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            qStyleControlElement = elem,
            labelText = display,
            bounds = bounds,
            renderContext = renderContext
        )
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
            checked = impl.isChecked()
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
            renderContext = renderContext
        )
        return true
    }

    fun renderDropdown(
        impl: DesktopDropdownNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "QStyle::CC_ToolButton[MenuButtonPopup]")
        val label = (impl.nativeWidget.attributes["label"] as? String).orEmpty()
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            qStyleControlElement = "QStyle::CC_ToolButton[MenuButtonPopup]",
            labelText = label,
            bounds = bounds,
            renderContext = renderContext
        )
        return true
    }

    fun renderRangeSlider(
        impl: DesktopRangeSliderNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "QStyle::CC_Slider[QStyleOptionSlider]")
        val span = (impl.getMax() - impl.getMin()).coerceAtLeast(1e-6)
        val fraction = ((impl.getValue() - impl.getMin()) / span).toFloat().coerceIn(0f, 1f)
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            qStyleControlElement = "QStyle::CC_Slider[QStyleOptionSlider]",
            labelText = "",
            bounds = bounds,
            renderContext = renderContext,
            progressFraction = fraction
        )
        return true
    }

    fun renderProgressBar(
        impl: DesktopProgressBarNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "QStyle::CE_ProgressBar[QStyleOptionProgressBar]")
        val label = (impl.nativeWidget.attributes["label"] as? String).orEmpty()
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            qStyleControlElement = "QStyle::CE_ProgressBar[QStyleOptionProgressBar]",
            labelText = label,
            bounds = bounds,
            renderContext = renderContext,
            progressFraction = if (impl.isIndeterminate()) 0.5f else impl.getProgress()
        )
        return true
    }

    fun renderSpinner(
        impl: DesktopSpinnerNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "QStyle::PE_IndicatorProgressChunk[Spinner]")
        val label = (impl.nativeWidget.attributes["label"] as? String).orEmpty()
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            qStyleControlElement = "QStyle::PE_IndicatorProgressChunk[Spinner]",
            labelText = label,
            bounds = bounds,
            renderContext = renderContext
        )
        return true
    }

    fun renderContainer(
        impl: DesktopContainerNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "QStyle::PE_Widget[QWidget_QBoxLayout]")
        if (impl.getStyleSnapshot().type != null && impl.getStyleSnapshot().type != BootstrapType.NONE ||
            impl.getStyleSnapshot().backgroundType != null && impl.getStyleSnapshot().backgroundType != BootstrapType.NONE
        ) {
            renderNativeControlWithOptionalBootstrapTint(
                impl = impl,
                qStyleControlElement = "QStyle::PE_Frame[QFrame_CardSurface]",
                labelText = "",
                bounds = bounds,
                renderContext = renderContext
            )
        }
        return true
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
        return true
    }

    fun renderTabContainer(
        impl: DesktopTabContainerNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "QStyle::CE_TabBarTabShape[QTabWidget]")
        impl.nativeWidget.attributes["lastRenderedWidth"] = bounds.width.coerceAtLeast(1)
        impl.nativeWidget.attributes["lastRenderedHeight"] = bounds.height.coerceAtLeast(1)
        return true
    }

    fun renderTab(
        impl: DesktopTabNativeImpl<QtWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "QStyle::CE_TabBarTab[QStyleOptionTab]")
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            qStyleControlElement = "QStyle::CE_TabBarTab[QStyleOptionTab]",
            labelText = impl.titleVal,
            bounds = bounds,
            renderContext = renderContext,
            checked = impl.selectedVal
        )
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
        val colCount = max(headers.size, columnWeights.size).coerceAtLeast(1)
        val totalWidth = bounds.width.coerceAtLeast(colCount * 40)
        val resolvedColumnWidths = computeWeightedColumnWidths(totalWidth, colCount, columnWeights)
        impl.nativeWidget.attributes["resolvedColumnWidthsPx"] = resolvedColumnWidths
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

    private fun overlayContrastingTextGlyphs(
        pixels: IntArray,
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
        val startX = ((width - totalTextWidth) / 2).coerceAtLeast(4)
        val centerY = height / 2
        val topY = (centerY - 3).coerceAtLeast(2)
        val bottomY = (centerY + 3).coerceAtMost(height - 3)
        val fgAlpha = ((if (disabled) foreground.a * 0.6f else foreground.a.toFloat()) / 255f).coerceIn(0f, 1f)

        for (i in 0 until glyphCount) {
            val ch = text[i]
            if (ch.isWhitespace()) continue
            val gx = startX + i * 6
            for (gy in topY..bottomY) {
                val rowOffset = gy * width
                for (dx in 0..3) {
                    val px = gx + dx
                    if (px >= width - 2) continue
                    val bit = ((ch.code + dx * 3 + (gy - topY) * 5) and 1) == 0 ||
                        gy == topY || (dx == 0 && gy < bottomY)
                    if (!bit) continue
                    val idx = rowOffset + px
                    val dst = pixels[idx]
                    val dstA = (dst ushr 24) and 0xFF
                    val dstR = (dst ushr 16) and 0xFF
                    val dstG = (dst ushr 8) and 0xFF
                    val dstB = dst and 0xFF
                    val outR = (dstR * (1f - fgAlpha) + foreground.r * fgAlpha).roundToInt().coerceIn(0, 255)
                    val outG = (dstG * (1f - fgAlpha) + foreground.g * fgAlpha).roundToInt().coerceIn(0, 255)
                    val outB = (dstB * (1f - fgAlpha) + foreground.b * fgAlpha).roundToInt().coerceIn(0, 255)
                    val outA = max(dstA, (fgAlpha * 255f).roundToInt()).coerceIn(0, 255)
                    pixels[idx] = (outA shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }
        }
    }
}
