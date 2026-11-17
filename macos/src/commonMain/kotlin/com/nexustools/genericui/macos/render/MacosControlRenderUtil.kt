package com.nexustools.genericui.macos.render

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
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
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.res.Resources
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssLength
import com.nexustools.genericui.style.theme.SystemThemeDetector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Type-grouped macOS AppKit control render utility (`NSButtonCell`, `NSSwitch`, `NSPopUpButtonCell`,
 * `NSTextFieldCell`, `NSSliderCell`, `NSProgressIndicator`, `NSLevelIndicatorCell`, `NSTabView`,
 * `NSTableView`, `NSCollectionView`).
 *
 * Implements:
 * 1. **Native-Only Rendering when `BootstrapType == BootstrapType.NONE`**: Renders the 100% unmodified
 *    macOS AppKit control (`NSAppearanceNameAqua` / `NSAppearanceNameDarkAqua`).
 * 2. **Offscreen Empty-Text Native Control Sprite + `ThemeConfig` Color-Tint + Contrasting Text Overlay
 *    when `BootstrapType != BootstrapType.NONE`**: Renders the native AppKit control with NO text (`""`)
 *    into an offscreen 32-bpp ARGB pixel buffer, tints the control pixels using
 *    [BootstrapColorPalette.resolveColor] (from active `ThemeConfig`) via
 *    [DesktopControlTintRenderUtil.tintNativeControlPixels] while preserving native AppKit bevels,
 *    borders, specular highlights, and hover/pressed/disabled states, blits the tinted native control,
 *    and renders the contrasting text and icon ([BootstrapColorPalette.resolveForegroundOn]) on top afterward.
 * 3. **System Backend Style Rules**:
 *    - Text / typography elements (`MacosTextNativeImpl`, `MacosMarkdownViewNativeImpl` — `Label`, `Text`,
 *      `Heading`, `Badge`, `Blockquote`, `CodeBlock`, `MarkdownView`) honor typography and text-color
 *      properties from `ComponentStyle` and `ComponentBootstrap`.
 *    - Standard buttons, inputs, controls, and containers silently ignore aesthetic and typography
 *      `ComponentStyle` overrides and can ONLY be tinted via `BootstrapType`, unless `StyledContainer` /
 *      `StyledButton` or `component.isHostedInCustomStyleEngine()` is used.
 */
object MacosControlRenderUtil {

    var onRequestTextInputFocus: ((DesktopTextInputNativeImpl<DesktopNativeWidget>) -> Unit)? = null
    var onRequestSelectPopup: ((DesktopSelectNativeImpl<DesktopNativeWidget>, Rect) -> Unit)? = null
    var onRequestDropdownPopup: ((DesktopDropdownNativeImpl<DesktopNativeWidget>, Rect) -> Unit)? = null

    private val controlSpriteCache = LinkedHashMap<String, IntArray>(128)

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

    fun syncThemeAttributes(widget: DesktopNativeWidget): Boolean {
        val effectiveTheme = Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()
        val isDark = effectiveTheme == ThemeMode.DARK
        widget.attributes["effectiveThemeMode"] = effectiveTheme
        widget.attributes["nsAppearance"] = if (isDark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua"
        widget.attributes.remove("backdropBlurPx")
        widget.attributes.remove("backdropFilter")
        return isDark
    }

    fun syncStandardControlAppearance(
        impl: DesktopBaseNativeImpl<DesktopNativeWidget>,
        nativeControlCell: String
    ) {
        val widget = impl.nativeWidget
        val isDark = syncThemeAttributes(widget)
        widget.attributes["nativeControlCell"] = nativeControlCell

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

    fun syncTypographyElementAppearance(
        impl: DesktopBaseNativeImpl<DesktopNativeWidget>,
        nativeControlCell: String
    ) {
        val widget = impl.nativeWidget
        val isDark = syncThemeAttributes(widget)
        widget.attributes["nativeControlCell"] = nativeControlCell
        syncTypographyAndColorAttributes(
            impl = impl,
            isDark = isDark,
            allowAestheticSkin = impl.isHostedInCustomStyleEngine()
        )
    }

    private fun syncTypographyAndColorAttributes(
        impl: DesktopBaseNativeImpl<DesktopNativeWidget>,
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

    fun renderOffscreenEmptyTextNativeSprite(
        cellClass: String,
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

        val isInputField = cellClass.contains("NSTextFieldCell_BezelSquare") || cellClass.contains("NSTextView_Scrollable")
        val baseR = when {
            checked && (cellClass.contains("Switch") || cellClass.contains("Tab")) -> if (darkMode) 10 else 0
            isInputField -> if (darkMode) 30 else 255
            darkMode -> when {
                pressed -> 72
                hovered -> 98
                else -> 84
            }
            else -> when {
                pressed -> 218
                hovered -> 248
                else -> 240
            }
        }
        val baseG = when {
            checked && (cellClass.contains("Switch") || cellClass.contains("Tab")) -> if (darkMode) 132 else 122
            isInputField -> if (darkMode) 33 else 255
            darkMode -> when {
                pressed -> 74
                hovered -> 101
                else -> 87
            }
            else -> when {
                pressed -> 220
                hovered -> 249
                else -> 241
            }
        }
        val baseB = when {
            checked && (cellClass.contains("Switch") || cellClass.contains("Tab")) -> 255
            isInputField -> if (darkMode) 40 else 255
            darkMode -> when {
                pressed -> 80
                hovered -> 108
                else -> 94
            }
            else -> when {
                pressed -> 225
                hovered -> 252
                else -> 244
            }
        }

        val cornerRadius = when {
            cellClass.contains("Radio") || cellClass.contains("Spinning") -> min(w, h) / 2f
            cellClass.contains("NSSwitch") -> h / 2f
            cellClass.contains("Progress") || cellClass.contains("Slider") -> min(6f, h / 2f)
            else -> min(6f, min(w, h) / 3f)
        }
        val alphaMultiplier = if (disabled) 0.58f else 1.0f
        val isProgressCell = cellClass.contains("Progress")
        val progressFillW = if (isProgressCell && progressFraction > 0f) {
            (w * progressFraction.coerceIn(0f, 1f)).roundToInt()
        } else {
            0
        }

        for (y in 0 until h) {
            val rowOffset = y * w
            val vRatio = if (h > 1) y.toFloat() / (h - 1).toFloat() else 0.5f
            val bevelDelta = when {
                isInputField -> 0
                pressed -> ((vRatio - 0.5f) * 14f).roundToInt()
                y == 1 -> if (darkMode) 20 else 14
                else -> ((0.5f - vRatio) * 18f).roundToInt()
            }

            for (x in 0 until w) {
                val cov = computeRoundedRectCoverage(x, y, w, h, cornerRadius)
                if (cov <= 0f) continue

                val edgeDist = min(min(x, w - 1 - x), min(y, h - 1 - y))
                val isBorder = edgeDist == 0
                val isInnerHighlight = !isInputField && edgeDist == 1 && y <= 2 && !pressed

                var r = baseR + bevelDelta
                var g = baseG + bevelDelta
                var b = baseB + bevelDelta

                if (progressFillW > 0 && x < progressFillW) {
                    r = if (darkMode) 10 else 0
                    g = if (darkMode) 132 else 122
                    b = 255
                }

                if (isBorder) {
                    if (isInputField) {
                        r = if (darkMode) 76 else 188
                        g = if (darkMode) 82 else 194
                        b = if (darkMode) 96 else 204
                    } else {
                        val borderScale = if (darkMode) 0.62f else 0.74f
                        r = (r * borderScale).roundToInt()
                        g = (g * borderScale).roundToInt()
                        b = (b * borderScale).roundToInt()
                    }
                } else if (isInnerHighlight) {
                    r += 16
                    g += 16
                    b += 16
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

    fun renderNativeControlWithOptionalBootstrapTint(
        impl: DesktopBaseNativeImpl<DesktopNativeWidget>,
        cellClass: String,
        labelText: String,
        bounds: Rect = impl.nativeWidget.bounds,
        renderContext: Any? = null,
        checked: Boolean = false,
        progressFraction: Float = 0f
    ): IntArray {
        val widget = impl.nativeWidget
        val isDark = syncThemeAttributes(widget)
        val w = bounds.width.coerceAtLeast(24).coerceAtMost(768)
        val h = bounds.height.coerceAtLeast(20).coerceAtMost(256)
        val hovered = impl.isHoveredState()
        val pressed = impl.isActivePressedState()
        val disabled = !widget.enabled
        val snapshot = impl.getStyleSnapshot()
        val activeType = snapshot.type?.takeIf { it != BootstrapType.NONE }
            ?: snapshot.backgroundType?.takeIf { it != BootstrapType.NONE }

        val tintColor = activeType?.let { BootstrapColorPalette.resolveColor(it, isDark) }
        val foregroundColor: RgbaColor
        if (activeType != null && tintColor != null) {
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
            foregroundColor = if (isDark) RgbaColor(235, 235, 245) else RgbaColor(28, 28, 30)
            widget.attributes["useSystemControlAppearance"] = "true"
            widget.attributes["renderedOffscreenEmptyTextSprite"] = false
            widget.attributes.remove("bootstrapTintColor")
            widget.attributes["bootstrapForegroundColor"] = foregroundColor.toHex()
            widget.attributes["contrastingTextRenderedAfterTint"] = false
        }

        val progressBucket = (progressFraction.coerceIn(0f, 1f) * 200f).roundToInt()
        val cacheKey = if (renderContext is MacosRenderContext) {
            "$cellClass|${w}x$h|$isDark|$hovered|$pressed|$disabled|$checked|$progressBucket|${activeType?.name}|${snapshot.outline}|${tintColor?.toHex()}"
        } else {
            null
        }

        val cachedSprite = if (cacheKey != null) controlSpriteCache[cacheKey] else null
        val spritePixels = if (cachedSprite != null) {
            cachedSprite
        } else {
            val fresh = renderOffscreenEmptyTextNativeSprite(
                cellClass = cellClass,
                width = w,
                height = h,
                darkMode = isDark,
                hovered = hovered,
                pressed = pressed,
                disabled = disabled,
                checked = checked,
                progressFraction = progressFraction
            )
            if (activeType != null && tintColor != null) {
                DesktopControlTintRenderUtil.tintNativeControlPixels(
                    pixels = fresh,
                    width = w,
                    height = h,
                    tint = tintColor,
                    outlineMode = snapshot.outline,
                    hovered = hovered,
                    pressed = pressed,
                    disabled = disabled
                )
            }
            if (cacheKey != null) {
                if (controlSpriteCache.size >= 256) {
                    controlSpriteCache.keys.firstOrNull()?.let { controlSpriteCache.remove(it) }
                }
                controlSpriteCache[cacheKey] = fresh
            }
            fresh
        }

        if (renderContext is MacosRenderContext) {
            // Blit empty-text (optionally tinted) native AppKit control sprite onto CoreGraphics view
            MacosGraphicsDrawUtil.blitArgbSprite(
                ctx = renderContext,
                pixels = spritePixels,
                srcW = w,
                srcH = h,
                dstX = bounds.x,
                dstY = bounds.y,
                dstW = bounds.width.coerceAtLeast(1),
                dstH = bounds.height.coerceAtLeast(1)
            )
        } else {
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
        }

        widget.attributes["lastRenderedCellClass"] = cellClass
        widget.attributes["lastRenderedWidth"] = w
        widget.attributes["lastRenderedHeight"] = h
        widget.attributes["lastRenderedText"] = labelText
        widget.attributes["_resolvedControlForeground"] = foregroundColor
        return spritePixels
    }

    fun renderLeafControl(
        impl: DesktopBaseNativeImpl<DesktopNativeWidget>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        if (renderContext != null && impl.renderDesktopNode(renderContext, bounds)) {
            return true
        }
        return when (impl) {
            is DesktopStyledButtonNativeImpl<*> -> renderStyledButton(impl, renderContext, bounds)
            is DesktopButtonNativeImpl<*> -> renderButton(impl, renderContext, bounds)
            is DesktopTextInputNativeImpl<*> -> renderTextInput(impl, renderContext, bounds)
            is DesktopCheckboxNativeImpl<*> -> renderCheckbox(impl, renderContext, bounds)
            is DesktopSelectNativeImpl<*> -> renderSelect(impl, renderContext, bounds)
            is DesktopDropdownNativeImpl<*> -> renderDropdown(impl, renderContext, bounds)
            is DesktopRangeSliderNativeImpl<*> -> renderRangeSlider(impl, renderContext, bounds)
            is DesktopProgressBarNativeImpl<*> -> renderProgressBar(impl, renderContext, bounds)
            is DesktopSpinnerNativeImpl<*> -> renderSpinner(impl, renderContext, bounds)
            is DesktopTextNativeImpl<*> -> renderText(impl, renderContext, bounds)
            is DesktopMarkdownViewNativeImpl<*> -> renderMarkdownView(impl, renderContext, bounds)
            is DesktopImageNativeImpl<*> -> renderImage(impl, renderContext, bounds)
            else -> false
        }
    }

    internal fun resolveButtonIconSpec(impl: DesktopButtonNativeImpl<*>): String? {
        impl.getIcon()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        (impl.nativeWidget.attributes["icon"] as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        for (child in impl.nativeWidget.children) {
            if (!child.visible || child.disposed) continue
            val childImg = child.attributes["nativeImpl"] as? DesktopImageNativeImpl<*>
            childImg?.getSourceUrl()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    private fun resolveExplicitLengthPx(raw: Any?): Int? = when (raw) {
        is Number -> raw.toInt().takeIf { it > 0 }
        is CssLength -> raw.resolvePx(0f)?.roundToInt()?.takeIf { it > 0 }
        else -> null
    }

    fun measureButton(impl: DesktopButtonNativeImpl<DesktopNativeWidget>, dpiScale: Float = 1f): Size {
        val widget = impl.nativeWidget
        val explicitW = resolveExplicitLengthPx(widget.attributes["styleWidth"])
            ?: widget.preferredSize?.width?.takeIf { it > 0 }
        val explicitH = resolveExplicitLengthPx(widget.attributes["styleHeight"])
            ?: widget.preferredSize?.height?.takeIf { it > 0 }

        val text = impl.getText().trim()
        val iconSpec = resolveButtonIconSpec(impl)
        val hasIcon = !iconSpec.isNullOrBlank()
        val sizeScale = when (impl.getSize()) {
            BootstrapSize.SM -> 0.88f
            BootstrapSize.LG -> 1.18f
            else -> 1.0f
        }
        if (text.isEmpty() && !hasIcon) {
            return Size(explicitW ?: 0, explicitH ?: 0)
        }
        if (text.isEmpty() && hasIcon) {
            val side = (30 * sizeScale * dpiScale).roundToInt().coerceAtLeast(26)
            return Size(explicitW ?: side, explicitH ?: side)
        }
        val fontSize = 13f * sizeScale
        val textW = MacosGraphicsDrawUtil.measureText(text, fontSize, 500).width
        val iconExtra = if (hasIcon) 20 else 0
        val minW = when {
            text.length <= 2 && !hasIcon -> 32
            text.length <= 4 && !hasIcon -> 42
            sizeScale < 1f -> 50
            else -> 62
        }
        val baseW = (max(minW, textW + iconExtra + 20) * dpiScale).roundToInt()
        val baseH = (30 * sizeScale * dpiScale).roundToInt().coerceAtLeast(24)
        return Size(explicitW ?: baseW, explicitH ?: baseH)
    }

    fun renderButton(
        impl: DesktopButtonNativeImpl<DesktopNativeWidget>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "NSButtonCell_NSBezelStyleRounded")
        val snapshot = impl.getStyleSnapshot()
        val isLinkStyle = snapshot.type == BootstrapType.LINK
        val iconSpec = resolveButtonIconSpec(impl)

        if (renderContext is MacosRenderContext) {
            val isDark = renderContext.darkMode
            val disabled = !impl.nativeWidget.enabled
            val fgColor: RgbaColor
            if (isLinkStyle) {
                if (impl.isHoveredState()) {
                    MacosGraphicsDrawUtil.fillRoundedRect(
                        renderContext,
                        bounds.x,
                        bounds.y,
                        bounds.width,
                        bounds.height,
                        6f,
                        renderContext.palette.bgHover
                    )
                }
                fgColor = BootstrapColorPalette.resolveColor(BootstrapType.LINK, isDark)
            } else {
                renderNativeControlWithOptionalBootstrapTint(
                    impl = impl,
                    cellClass = "NSButtonCell_NSBezelStyleRounded",
                    labelText = impl.getText(),
                    bounds = bounds,
                    renderContext = renderContext
                )
                fgColor = (impl.nativeWidget.attributes["_resolvedControlForeground"] as? RgbaColor)
                    ?: renderContext.palette.textPrimary
            }
            val effectiveFg = if (disabled) fgColor.withAlpha(0.55f) else fgColor
            drawButtonContent(renderContext, impl.getText(), iconSpec, bounds, effectiveFg, impl.getSize())
            registerControlClickRegion(renderContext, impl, bounds)
            return true
        }

        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            cellClass = "NSButtonCell_NSBezelStyleRounded",
            labelText = impl.getText(),
            bounds = bounds,
            renderContext = renderContext
        )
        return true
    }

    fun renderStyledButton(
        impl: DesktopStyledButtonNativeImpl<DesktopNativeWidget>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        val widget = impl.nativeWidget
        syncThemeAttributes(widget)
        widget.attributes["useSystemControlAppearance"] = "false"
        widget.attributes["nativeControlCell"] = "NSButton_CALayer_Styled"
        widget.attributes["lastRenderedWidth"] = bounds.width.coerceAtLeast(1)
        widget.attributes["lastRenderedHeight"] = bounds.height.coerceAtLeast(1)

        if (renderContext is MacosRenderContext) {
            val visuals = impl.resolveCurrentVisuals()
            val style = impl.getComponentStyle()
            val snap = impl.getStyleSnapshot()
            val tokens = widget.cachedAllClassTokens
            val isDark = renderContext.darkMode
            val bsType = snap.type?.takeIf { it != BootstrapType.NONE && it != BootstrapType.LINK }

            val radius = visuals.borderRadiusPx
                ?: (widget.attributes["borderRadiusPx"] as? Float)
                ?: style?.borderRadius?.topLeft?.resolvePx()
                ?: when {
                    "rounded-circle" in tokens -> min(bounds.width, bounds.height) / 2f
                    "rounded-pill" in tokens -> bounds.height / 2f
                    else -> 8f
                }

            val explicitBg = visuals.backgroundColor
                ?: style?.backgroundColor
                ?: (widget.attributes["backgroundColor"] as? RgbaColor)

            val bg: RgbaColor = when {
                explicitBg != null && explicitBg.a > 0 -> {
                    when {
                        !widget.enabled && impl.getDisabledVisuals()?.backgroundColor == null ->
                            explicitBg.withAlpha(explicitBg.alphaFloat * 0.5f)
                        impl.isActivePressedState() && impl.getActiveVisuals()?.backgroundColor == null ->
                            explicitBg.adjustBrightness(-18)
                        impl.isHoveredState() && impl.getHoverVisuals()?.backgroundColor == null ->
                            explicitBg.adjustBrightness(12)
                        else -> explicitBg
                    }
                }
                explicitBg != null -> {
                    // Explicitly transparent button: only highlight on hover/press
                    if (widget.enabled && (impl.isActivePressedState() || impl.isHoveredState())) {
                        renderContext.palette.bgHover
                    } else {
                        RgbaColor.TRANSPARENT
                    }
                }
                bsType != null -> {
                    val bsCol = BootstrapColorPalette.resolveColor(bsType, isDark)
                    if (snap.outline) {
                        when {
                            !widget.enabled -> RgbaColor.TRANSPARENT
                            impl.isActivePressedState() ->
                                if (bsType == BootstrapType.LIGHT && !isDark) renderContext.palette.bgHover else bsCol.withAlpha(0.26f)
                            impl.isHoveredState() ->
                                if (bsType == BootstrapType.LIGHT && !isDark) renderContext.palette.bgHover else bsCol.withAlpha(0.16f)
                            else -> RgbaColor.TRANSPARENT
                        }
                    } else {
                        when {
                            !widget.enabled -> bsCol.withAlpha(0.5f)
                            impl.isActivePressedState() -> bsCol.adjustBrightness(-18)
                            impl.isHoveredState() -> bsCol.adjustBrightness(12)
                            else -> bsCol
                        }
                    }
                }
                else -> {
                    val defBg = renderContext.palette.bgElevated
                    when {
                        !widget.enabled -> defBg.withAlpha(0.5f)
                        impl.isActivePressedState() -> defBg.adjustBrightness(-18)
                        impl.isHoveredState() -> defBg.adjustBrightness(14)
                        else -> defBg
                    }
                }
            }

            if (bg.a > 0) {
                MacosGraphicsDrawUtil.fillRoundedRect(renderContext, bounds.x, bounds.y, bounds.width, bounds.height, radius, bg)
            }

            val borderCol = visuals.borderColor
                ?: style?.borderColor
                ?: (widget.attributes["borderColor"] as? RgbaColor)
                ?: if (bsType != null && snap.outline) {
                    if (bsType == BootstrapType.LIGHT && !isDark) {
                        renderContext.palette.borderStrong
                    } else {
                        BootstrapColorPalette.resolveColor(bsType, isDark).withAlpha(0.65f)
                    }
                } else {
                    null
                }
            val borderW = (visuals.borderWidthPx ?: 1f).coerceAtLeast(1f)
            if (borderCol != null && borderCol.a > 0 && visuals.borderStyle != CssBorderStyle.NONE) {
                MacosGraphicsDrawUtil.strokeRoundedRect(renderContext, bounds.x, bounds.y, bounds.width, bounds.height, radius, borderCol, borderW)
            }

            val fg = visuals.textColor
                ?: style?.color
                ?: (widget.attributes["textColor"] as? RgbaColor)
                ?: when {
                    bsType != null && !snap.outline -> BootstrapColorPalette.resolveForegroundOn(bsType, isDark)
                    bsType != null && snap.outline ->
                        if (bsType == BootstrapType.LIGHT && !isDark) renderContext.palette.textPrimary
                        else BootstrapColorPalette.resolveColor(bsType, isDark)
                    else -> renderContext.palette.textPrimary
                }
            val effectiveFg = if (!widget.enabled) fg.withAlpha(0.55f) else fg
            drawButtonContent(renderContext, impl.getText(), resolveButtonIconSpec(impl), bounds, effectiveFg, impl.getSize())
            registerControlClickRegion(renderContext, impl, bounds)
        }
        return true
    }

    private fun drawButtonContent(
        ctx: MacosRenderContext,
        rawText: String,
        rawIcon: String?,
        bounds: Rect,
        fg: RgbaColor,
        size: BootstrapSize?
    ) {
        val text = rawText.trim()
        val iconSpec = rawIcon?.trim()?.takeIf { it.isNotEmpty() }
        val fontSize = when (size) {
            BootstrapSize.SM -> 12f
            BootstrapSize.LG -> 15f
            else -> 13f
        }
        val iconSide = when (size) {
            BootstrapSize.SM -> 15
            BootstrapSize.LG -> 20
            else -> 17
        }.coerceAtMost((bounds.height - 6).coerceAtLeast(12))

        if (iconSpec != null && text.isEmpty()) {
            val ix = bounds.x + (bounds.width - iconSide) / 2
            val iy = bounds.y + (bounds.height - iconSide) / 2
            if (!isDrawableUri(iconSpec)) {
                MacosGraphicsDrawUtil.drawText(ctx, iconSpec, bounds.x, bounds.y, bounds.width, bounds.height, fg, fontSize + 1f, 600, TextAlignment.CENTER)
            } else {
                MacosGraphicsDrawUtil.drawIconOrUri(ctx, iconSpec, ix, iy, iconSide, iconSide, tintColor = fg)
            }
            return
        }

        if (iconSpec != null && text.isNotEmpty()) {
            val measured = MacosGraphicsDrawUtil.measureText(text, fontSize, 500)
            val gap = 6
            val totalW = min(bounds.width - 12, iconSide + gap + measured.width)
            val startX = bounds.x + ((bounds.width - totalW) / 2).coerceAtLeast(6)
            val iy = bounds.y + (bounds.height - iconSide) / 2
            if (!isDrawableUri(iconSpec)) {
                MacosGraphicsDrawUtil.drawText(ctx, iconSpec, startX, iy, iconSide + 2, iconSide + 2, fg, fontSize, 500, TextAlignment.CENTER)
            } else {
                MacosGraphicsDrawUtil.drawIconOrUri(ctx, iconSpec, startX, iy, iconSide, iconSide, tintColor = fg)
            }
            val textX = startX + iconSide + gap
            val textW = (bounds.right - 6 - textX).coerceAtLeast(1)
            MacosGraphicsDrawUtil.drawText(ctx, text, textX, bounds.y, textW, bounds.height, fg, fontSize, 500, TextAlignment.START)
            return
        }

        if (text.isNotEmpty()) {
            val padX = if (bounds.width <= 42) 4 else 8
            MacosGraphicsDrawUtil.drawText(
                ctx = ctx,
                text = text,
                x = bounds.x + padX,
                y = bounds.y,
                w = (bounds.width - padX * 2).coerceAtLeast(1),
                h = bounds.height,
                color = fg,
                fontSizePx = fontSize,
                weightNumeric = 500,
                alignment = TextAlignment.CENTER
            )
        }
    }

    internal fun isDrawableUri(spec: String): Boolean =
        spec.startsWith("res://") ||
            spec.startsWith("@") ||
            spec.startsWith("ic_") ||
            spec.startsWith("data:") ||
            spec.startsWith("http://") ||
            spec.startsWith("https://") ||
            spec.startsWith("memory://") ||
            spec.endsWith(".svg", ignoreCase = true) ||
            spec.endsWith(".png", ignoreCase = true) ||
            Resources.getDrawable(spec) != null

    fun measureText(impl: DesktopTextNativeImpl<DesktopNativeWidget>, dpiScale: Float = 1f): Size {
        val fontSize = resolveTextFontSize(impl)
        val weight = resolveTextFontWeight(impl)
        val transform = impl.getStyleSnapshot().textTransform ?: impl.getComponentStyle()?.textTransform
        val text = applyTextTransform(impl.getText(), transform)
        if (text.isEmpty()) return Size(0, (fontSize * 1.35f).roundToInt())
        val isBadge = "badge" in impl.nativeWidget.cachedAllClassTokens || impl.componentClassPrefix.contains("badge")
        val extraW = if (isBadge) 16 else 2
        val extraH = if (isBadge) 6 else 0
        val w = max(1, (text.length * fontSize * 0.56f * dpiScale).roundToInt() + extraW)
        val h = max(16, (fontSize * 1.35f * dpiScale).roundToInt() + extraH)
        return Size(w, h)
    }

    fun resolveTextFontSize(impl: DesktopTextNativeImpl<DesktopNativeWidget>): Float {
        (impl.nativeWidget.attributes["fontSizePx"] as? Float)?.let { return it }
        impl.getComponentStyle()?.fontSize?.resolvePx()?.let { return it }
        val tokens = impl.nativeWidget.cachedAllClassTokens
        val tag = (impl.nativeWidget.attributes["htmlTag"] as? String)?.lowercase()
        return when {
            tag == "h1" || "h1" in tokens || "fs-1" in tokens -> 26f
            tag == "h2" || "h2" in tokens || "fs-2" in tokens -> 22f
            tag == "h3" || "h3" in tokens || "fs-3" in tokens -> 19f
            tag == "h4" || "h4" in tokens || "fs-4" in tokens -> 17f
            tag == "h5" || "h5" in tokens || "fs-5" in tokens -> 15.5f
            tag == "h6" || "h6" in tokens || "fs-6" in tokens -> 14f
            "badge" in tokens -> 11.5f
            "small" in tokens || tag == "small" -> 12f
            else -> 13.5f
        }
    }

    fun resolveTextFontWeight(impl: DesktopTextNativeImpl<DesktopNativeWidget>): Int {
        (impl.nativeWidget.attributes["fontWeightNumeric"] as? Int)?.let { return it }
        impl.getComponentStyle()?.fontWeight?.numericWeight?.let { return it }
        impl.getStyleSnapshot().fontWeight?.numericWeight?.let { return it }
        val tokens = impl.nativeWidget.cachedAllClassTokens
        val tag = (impl.nativeWidget.attributes["htmlTag"] as? String)?.lowercase()
        return when {
            "fw-bold" in tokens || tag in setOf("h1", "h2", "h3", "h4", "strong", "b") -> 700
            "fw-semibold" in tokens || tag in setOf("h5", "h6") || "badge" in tokens -> 600
            "fw-medium" in tokens -> 500
            else -> 400
        }
    }

    fun renderText(
        impl: DesktopTextNativeImpl<DesktopNativeWidget>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncTypographyElementAppearance(impl, "NSTextFieldCell_Label")
        val transform = impl.getComponentStyle()?.textTransform ?: impl.getStyleSnapshot().textTransform
        val renderedText = applyTextTransform(impl.getText(), transform)
        impl.nativeWidget.attributes["renderedText"] = renderedText

        if (renderContext is MacosRenderContext) {
            if (renderedText.isEmpty()) return true
            val tokens = impl.nativeWidget.cachedAllClassTokens
            val isBadge = "badge" in tokens || impl.componentClassPrefix.contains("badge")
            val snapshot = impl.getStyleSnapshot()
            val isDark = renderContext.darkMode
            val fontSize = resolveTextFontSize(impl)
            val weight = resolveTextFontWeight(impl)
            val monospace = "font-monospace" in tokens ||
                ((impl.nativeWidget.attributes["fontFamily"] as? String)?.contains("mono", ignoreCase = true) == true) ||
                (impl.nativeWidget.attributes["htmlTag"] == "code") ||
                (impl.nativeWidget.attributes["htmlTag"] == "pre")

            var fg = (impl.nativeWidget.attributes["textColor"] as? RgbaColor)
                ?: when {
                    "text-muted" in tokens || "text-secondary" in tokens -> renderContext.palette.textSecondary
                    else -> renderContext.palette.textPrimary
                }

            if (isBadge) {
                val badgeType = snapshot.backgroundType?.takeIf { it != BootstrapType.NONE }
                    ?: snapshot.type?.takeIf { it != BootstrapType.NONE }
                    ?: BootstrapType.SECONDARY
                val bgCol = BootstrapColorPalette.resolveColor(badgeType, isDark)
                fg = (impl.nativeWidget.attributes["textColor"] as? RgbaColor)
                    ?: BootstrapColorPalette.resolveForegroundOn(badgeType, isDark)
                val pillRadius = if ("rounded-pill" in tokens) bounds.height / 2f else 6f
                MacosGraphicsDrawUtil.fillRoundedRect(renderContext, bounds.x, bounds.y, bounds.width, bounds.height, pillRadius, bgCol)
                MacosGraphicsDrawUtil.drawText(
                    ctx = renderContext,
                    text = renderedText,
                    x = bounds.x + 6,
                    y = bounds.y,
                    w = (bounds.width - 12).coerceAtLeast(1),
                    h = bounds.height,
                    color = fg,
                    fontSizePx = fontSize,
                    weightNumeric = weight,
                    alignment = TextAlignment.CENTER,
                    singleLine = true,
                    monospace = monospace
                )
            } else {
                val align = impl.getComponentStyle()?.textAlign
                    ?: snapshot.textAlignment
                    ?: when {
                        "text-center" in tokens -> TextAlignment.CENTER
                        "text-end" in tokens -> TextAlignment.END
                        else -> TextAlignment.START
                    }
                val whiteSpace = impl.nativeWidget.attributes["whiteSpace"] as? String
                val singleLine = whiteSpace == "nowrap" ||
                    ("text-truncate" in tokens) ||
                    ("text-nowrap" in tokens) ||
                    ("native-row-title" in tokens) ||
                    ("native-row-detail" in tokens) ||
                    bounds.height <= (fontSize * 1.9f).roundToInt()
                MacosGraphicsDrawUtil.drawText(
                    ctx = renderContext,
                    text = renderedText,
                    x = bounds.x,
                    y = bounds.y,
                    w = bounds.width,
                    h = bounds.height,
                    color = if (!impl.nativeWidget.enabled) fg.withAlpha(0.55f) else fg,
                    fontSizePx = fontSize,
                    weightNumeric = weight,
                    alignment = align,
                    singleLine = singleLine,
                    verticallyCentered = singleLine,
                    monospace = monospace
                )
            }
            if (impl.hasClickListener() || impl.nativeWidget.tooltipText != null) {
                registerControlClickRegion(renderContext, impl, bounds)
            }
            return true
        }

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
        impl: DesktopMarkdownViewNativeImpl<DesktopNativeWidget>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncTypographyElementAppearance(impl, "NSTextView_NSAttributedString_Markdown")
        val md = impl.getMarkdown()
        impl.nativeWidget.attributes["renderedMarkdownLength"] = md.length
        impl.nativeWidget.attributes["lastRenderedWidth"] = bounds.width.coerceAtLeast(1)
        impl.nativeWidget.attributes["lastRenderedHeight"] = bounds.height.coerceAtLeast(1)
        if (renderContext is MacosRenderContext && md.isNotBlank()) {
            val cleaned = md.replace(Regex("^#+\\s*", RegexOption.MULTILINE), "")
                .replace("**", "")
                .replace("`", "")
            val fg = (impl.nativeWidget.attributes["textColor"] as? RgbaColor) ?: renderContext.palette.textPrimary
            MacosGraphicsDrawUtil.drawText(
                ctx = renderContext,
                text = cleaned,
                x = bounds.x,
                y = bounds.y,
                w = bounds.width,
                h = bounds.height,
                color = fg,
                fontSizePx = 13.5f,
                singleLine = false,
                verticallyCentered = false
            )
        }
        return true
    }

    fun renderTextInput(
        impl: DesktopTextInputNativeImpl<DesktopNativeWidget>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        val cell = if (impl.isMultiline()) "NSTextView_Scrollable" else "NSTextFieldCell_BezelSquare"
        syncStandardControlAppearance(impl, cell)
        val rawVal = impl.getValue()
        val display = if (impl.isPasswordMode()) {
            "•".repeat(rawVal.length)
        } else {
            rawVal.ifEmpty { impl.getPlaceholder() }
        }
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            cellClass = cell,
            labelText = display,
            bounds = bounds,
            renderContext = renderContext
        )
        val fieldLabel = impl.getLabel()?.trim()?.takeIf { it.isNotEmpty() }
        if (fieldLabel != null) {
            impl.nativeWidget.attributes["lastRenderedLabel"] = fieldLabel
        } else {
            impl.nativeWidget.attributes.remove("lastRenderedLabel")
        }

        if (renderContext is MacosRenderContext) {
            val isFocused = renderContext.focusedWidget === impl.nativeWidget || impl.isFocusedState()
            if (isFocused) {
                MacosGraphicsDrawUtil.strokeRoundedRect(
                    renderContext,
                    bounds.x,
                    bounds.y,
                    bounds.width,
                    bounds.height,
                    6f,
                    renderContext.palette.accentPrimary,
                    2f
                )
            }
            if (fieldLabel != null && bounds.width >= 36) {
                val capSize = MacosGraphicsDrawUtil.measureText(
                    text = fieldLabel,
                    fontSizePx = 11f,
                    weightNumeric = 500,
                    singleLine = true
                )
                val capPadX = 4
                val maxCapW = (bounds.width - 20).coerceAtLeast(12)
                val capW = (capSize.width + capPadX * 2).coerceAtMost(maxCapW)
                val capH = capSize.height.coerceAtLeast(13)
                val capX = bounds.x + 10
                val capY = bounds.y - capH / 2
                MacosGraphicsDrawUtil.fillRect(
                    renderContext,
                    capX - 2,
                    bounds.y - 2,
                    capW + 4,
                    4,
                    renderContext.palette.bgInput
                )
                val capColor = if (isFocused) renderContext.palette.accentPrimary else renderContext.palette.textSecondary
                MacosGraphicsDrawUtil.drawText(
                    ctx = renderContext,
                    text = fieldLabel,
                    x = capX + capPadX,
                    y = capY,
                    w = (capW - capPadX * 2).coerceAtLeast(1),
                    h = capH,
                    color = capColor,
                    fontSizePx = 11f,
                    weightNumeric = 500,
                    singleLine = true,
                    verticallyCentered = true
                )
            }
            val isPlaceholder = rawVal.isEmpty()
            val textCol = if (isPlaceholder) {
                renderContext.palette.textMuted
            } else {
                renderContext.palette.textPrimary
            }
            val shownValue = if (impl.isPasswordMode()) "•".repeat(rawVal.length) else rawVal
            val drawStr = if (isPlaceholder) impl.getPlaceholder() else shownValue
            val padX = 10
            val textW = (bounds.width - padX * 2).coerceAtLeast(1)
            if (drawStr.isNotEmpty()) {
                MacosGraphicsDrawUtil.drawText(
                    ctx = renderContext,
                    text = drawStr,
                    x = bounds.x + padX,
                    y = if (impl.isMultiline()) bounds.y + 6 else bounds.y,
                    w = textW,
                    h = if (impl.isMultiline()) (bounds.height - 12).coerceAtLeast(1) else bounds.height,
                    color = textCol,
                    fontSizePx = 13.5f,
                    singleLine = !impl.isMultiline(),
                    verticallyCentered = !impl.isMultiline()
                )
            }
            if (isFocused && !impl.isReadOnly() && impl.nativeWidget.enabled) {
                val caretOffset = if (shownValue.isEmpty()) 0 else MacosGraphicsDrawUtil.measureText(shownValue, 13.5f).width
                val caretX = (bounds.x + padX + caretOffset + 1).coerceAtMost(bounds.right - 6)
                val caretTop = bounds.y + 6
                val caretBot = (bounds.bottom - 6).coerceAtLeast(caretTop + 8)
                MacosGraphicsDrawUtil.drawLine(
                    renderContext,
                    caretX.toFloat(),
                    caretTop.toFloat(),
                    caretX.toFloat(),
                    caretBot.toFloat(),
                    renderContext.palette.accentPrimary,
                    1.5f
                )
            }
            renderContext.registerHitRegion(
                MacosHitRegion(
                    left = bounds.x,
                    top = bounds.y,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    widget = impl.nativeWidget,
                    tooltip = impl.nativeWidget.tooltipText,
                    cursorCss = "text",
                    isTextInput = true,
                    onPress = { _, _, _, _, _, _ ->
                        if (impl.nativeWidget.enabled) {
                            onRequestTextInputFocus?.invoke(impl)
                        }
                        true
                    },
                    onClick = {
                        if (impl.nativeWidget.enabled) {
                            onRequestTextInputFocus?.invoke(impl)
                            impl.dispatchClick()
                        }
                    }
                )
            )
        }
        return true
    }

    fun renderCheckbox(
        impl: DesktopCheckboxNativeImpl<DesktopNativeWidget>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        val cell = when {
            impl.switchStyle -> "NSSwitch"
            impl.radioStyle -> "NSButtonCell_NSButtonTypeRadio"
            else -> "NSButtonCell_NSButtonTypeSwitch"
        }
        syncStandardControlAppearance(impl, cell)
        if (renderContext !is MacosRenderContext) {
            renderNativeControlWithOptionalBootstrapTint(
                impl = impl,
                cellClass = cell,
                labelText = impl.getLabel(),
                bounds = bounds,
                renderContext = renderContext,
                checked = impl.isChecked()
            )
            return true
        }

        val checked = impl.isChecked()
        val indicatorW = if (impl.switchStyle) 36 else 18
        val indicatorH = if (impl.switchStyle) 20 else 18
        val iy = bounds.y + (bounds.height - indicatorH) / 2
        val indicatorRect = Rect(bounds.x, iy, indicatorW, indicatorH)

        val activeBg = if (checked) {
            val type = impl.getStyleSnapshot().type?.takeIf { it != BootstrapType.NONE } ?: BootstrapType.PRIMARY
            BootstrapColorPalette.resolveColor(type, renderContext.darkMode)
        } else {
            renderContext.palette.bgElevated
        }
        val radius = if (impl.switchStyle || impl.radioStyle) indicatorH / 2f else 4.5f
        MacosGraphicsDrawUtil.fillRoundedRect(renderContext, indicatorRect.x, indicatorRect.y, indicatorW, indicatorH, radius, activeBg)
        if (!checked) {
            MacosGraphicsDrawUtil.strokeRoundedRect(
                renderContext,
                indicatorRect.x,
                indicatorRect.y,
                indicatorW,
                indicatorH,
                radius,
                renderContext.palette.borderStrong,
                1f
            )
        }
        if (impl.switchStyle) {
            val knobSize = indicatorH - 4
            val kx = if (checked) indicatorRect.right - knobSize - 2 else indicatorRect.x + 2
            MacosGraphicsDrawUtil.fillRoundedRect(renderContext, kx, indicatorRect.y + 2, knobSize, knobSize, knobSize / 2f, RgbaColor.WHITE)
        } else if (checked) {
            if (impl.radioStyle) {
                MacosGraphicsDrawUtil.fillRoundedRect(
                    renderContext,
                    indicatorRect.x + 5,
                    indicatorRect.y + 5,
                    indicatorW - 10,
                    indicatorH - 10,
                    (indicatorH - 10) / 2f,
                    RgbaColor.WHITE
                )
            } else {
                MacosGraphicsDrawUtil.drawLine(
                    renderContext,
                    (indicatorRect.x + 4).toFloat(),
                    (indicatorRect.y + 9).toFloat(),
                    (indicatorRect.x + 8).toFloat(),
                    (indicatorRect.y + 13).toFloat(),
                    RgbaColor.WHITE,
                    2f
                )
                MacosGraphicsDrawUtil.drawLine(
                    renderContext,
                    (indicatorRect.x + 8).toFloat(),
                    (indicatorRect.y + 13).toFloat(),
                    (indicatorRect.x + 14).toFloat(),
                    (indicatorRect.y + 5).toFloat(),
                    RgbaColor.WHITE,
                    2f
                )
            }
        }

        val label = impl.getLabel()
        if (label.isNotBlank()) {
            val lx = indicatorRect.right + 8
            val lw = (bounds.right - lx).coerceAtLeast(1)
            MacosGraphicsDrawUtil.drawText(
                ctx = renderContext,
                text = label,
                x = lx,
                y = bounds.y,
                w = lw,
                h = bounds.height,
                color = renderContext.palette.textPrimary,
                fontSizePx = 13.5f
            )
        }

        renderContext.registerHitRegion(
            MacosHitRegion(
                left = bounds.x,
                top = bounds.y,
                right = bounds.right,
                bottom = bounds.bottom,
                widget = impl.nativeWidget,
                tooltip = impl.nativeWidget.tooltipText,
                cursorCss = "pointer",
                onClick = {
                    if (impl.nativeWidget.enabled) {
                        impl.setChecked(!impl.isChecked())
                        impl.dispatchClick()
                    }
                }
            )
        )
        return true
    }

    fun renderSelect(
        impl: DesktopSelectNativeImpl<DesktopNativeWidget>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "NSPopUpButtonCell")
        val selectedValue = impl.getSelectedValue()
        val selectedLabel = impl.getOptions().firstOrNull { it.first == selectedValue }?.second
            ?: selectedValue.orEmpty()
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            cellClass = "NSPopUpButtonCell",
            labelText = selectedLabel,
            bounds = bounds,
            renderContext = renderContext
        )

        if (renderContext is MacosRenderContext) {
            val fg = (impl.nativeWidget.attributes["_resolvedControlForeground"] as? RgbaColor)
                ?: renderContext.palette.textPrimary
            MacosGraphicsDrawUtil.drawText(
                ctx = renderContext,
                text = selectedLabel,
                x = bounds.x + 10,
                y = bounds.y,
                w = (bounds.width - 28).coerceAtLeast(1),
                h = bounds.height,
                color = fg,
                fontSizePx = 13f
            )
            // Native AppKit popup up/down chevrons on trailing edge
            val cx = (bounds.right - 14).toFloat()
            val cy = (bounds.y + bounds.height / 2).toFloat()
            MacosGraphicsDrawUtil.drawLine(renderContext, cx - 3.5f, cy - 2f, cx, cy - 5.5f, fg, 1.4f)
            MacosGraphicsDrawUtil.drawLine(renderContext, cx, cy - 5.5f, cx + 3.5f, cy - 2f, fg, 1.4f)
            MacosGraphicsDrawUtil.drawLine(renderContext, cx - 3.5f, cy + 2f, cx, cy + 5.5f, fg, 1.4f)
            MacosGraphicsDrawUtil.drawLine(renderContext, cx, cy + 5.5f, cx + 3.5f, cy + 2f, fg, 1.4f)

            renderContext.registerHitRegion(
                MacosHitRegion(
                    left = bounds.x,
                    top = bounds.y,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    widget = impl.nativeWidget,
                    tooltip = impl.nativeWidget.tooltipText,
                    cursorCss = "pointer",
                    onClick = {
                        if (impl.nativeWidget.enabled) {
                            onRequestSelectPopup?.invoke(impl, bounds)
                        }
                    }
                )
            )
        }
        return true
    }

    fun renderDropdown(
        impl: DesktopDropdownNativeImpl<DesktopNativeWidget>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "NSPopUpButtonCell_PullDown")
        val label = (impl.nativeWidget.attributes["label"] as? String).orEmpty()
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            cellClass = "NSPopUpButtonCell_PullDown",
            labelText = label,
            bounds = bounds,
            renderContext = renderContext
        )

        if (renderContext is MacosRenderContext) {
            val fg = (impl.nativeWidget.attributes["_resolvedControlForeground"] as? RgbaColor)
                ?: renderContext.palette.textPrimary
            MacosGraphicsDrawUtil.drawText(
                ctx = renderContext,
                text = label,
                x = bounds.x + 10,
                y = bounds.y,
                w = (bounds.width - 26).coerceAtLeast(1),
                h = bounds.height,
                color = fg,
                fontSizePx = 13f
            )
            val cx = (bounds.right - 13).toFloat()
            val cy = (bounds.y + bounds.height / 2).toFloat()
            MacosGraphicsDrawUtil.drawLine(renderContext, cx - 4f, cy - 1.5f, cx, cy + 2.5f, fg, 1.5f)
            MacosGraphicsDrawUtil.drawLine(renderContext, cx, cy + 2.5f, cx + 4f, cy - 1.5f, fg, 1.5f)

            renderContext.registerHitRegion(
                MacosHitRegion(
                    left = bounds.x,
                    top = bounds.y,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    widget = impl.nativeWidget,
                    tooltip = impl.nativeWidget.tooltipText,
                    cursorCss = "pointer",
                    onClick = {
                        if (impl.nativeWidget.enabled) {
                            onRequestDropdownPopup?.invoke(impl, bounds)
                        }
                    }
                )
            )
        }
        return true
    }

    fun renderRangeSlider(
        impl: DesktopRangeSliderNativeImpl<DesktopNativeWidget>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "NSSliderCell")
        val span = (impl.getMax() - impl.getMin()).coerceAtLeast(1e-6)
        val fraction = ((impl.getValue() - impl.getMin()) / span).toFloat().coerceIn(0f, 1f)
        if (renderContext !is MacosRenderContext) {
            renderNativeControlWithOptionalBootstrapTint(
                impl = impl,
                cellClass = "NSSliderCell",
                labelText = "",
                bounds = bounds,
                renderContext = renderContext,
                progressFraction = fraction
            )
            return true
        }

        val trackPad = 8
        val trackX = bounds.x + trackPad
        val trackW = (bounds.width - trackPad * 2).coerceAtLeast(8)
        val trackH = if (impl.isHoveredState() || impl.isActivePressedState()) 6 else 4
        val trackY = bounds.y + (bounds.height - trackH) / 2
        val activeType = impl.getStyleSnapshot().type?.takeIf { it != BootstrapType.NONE } ?: BootstrapType.PRIMARY
        val accent = BootstrapColorPalette.resolveColor(activeType, renderContext.darkMode)

        MacosGraphicsDrawUtil.fillRoundedRect(
            renderContext,
            trackX,
            trackY,
            trackW,
            trackH,
            trackH / 2f,
            renderContext.palette.borderStrong
        )
        val bufferedFraction = (impl.nativeWidget.attributes["bufferedFraction"] as? String)?.toFloatOrNull()
            ?: (impl.nativeWidget.attributes["bufferedFraction"] as? Number)?.toFloat()
            ?: 0f
        val bufferedW = (trackW * bufferedFraction.coerceIn(0f, 1f)).roundToInt()
        if (bufferedW > 0) {
            MacosGraphicsDrawUtil.fillRoundedRect(
                renderContext,
                trackX,
                trackY,
                bufferedW,
                trackH,
                trackH / 2f,
                if (renderContext.darkMode) RgbaColor(255, 255, 255, 96) else RgbaColor(0, 0, 0, 64)
            )
        }
        val fillW = (trackW * fraction).roundToInt()
        if (fillW > 0) {
            MacosGraphicsDrawUtil.fillRoundedRect(
                renderContext,
                trackX,
                trackY,
                fillW,
                trackH,
                trackH / 2f,
                accent
            )
        }
        val knobR = if (impl.isHoveredState() || impl.isActivePressedState()) 8 else 7
        val knobCx = trackX + fillW
        val knobCy = bounds.y + bounds.height / 2
        MacosGraphicsDrawUtil.fillRoundedRect(
            renderContext,
            knobCx - knobR,
            knobCy - knobR,
            knobR * 2,
            knobR * 2,
            knobR.toFloat(),
            RgbaColor.WHITE
        )
        MacosGraphicsDrawUtil.strokeRoundedRect(
            renderContext,
            knobCx - knobR,
            knobCy - knobR,
            knobR * 2,
            knobR * 2,
            knobR.toFloat(),
            RgbaColor(0, 0, 0, 70),
            1f
        )

        fun fractionAt(wx: Float): Double =
            ((wx - trackX.toFloat()) / trackW.toFloat()).toDouble().coerceIn(0.0, 1.0)

        renderContext.registerHitRegion(
            MacosHitRegion(
                left = bounds.x,
                top = bounds.y,
                right = bounds.right,
                bottom = bounds.bottom,
                widget = impl.nativeWidget,
                tooltip = impl.nativeWidget.tooltipText,
                cursorCss = "pointer",
                onPress = { _, _, wx, _, _, _ ->
                    if (impl.nativeWidget.enabled) {
                        impl.beginDragFromFraction(fractionAt(wx))
                    }
                    true
                },
                onDrag = { _, _, wx, _ ->
                    if (impl.nativeWidget.enabled) {
                        impl.updateDragFromFraction(fractionAt(wx))
                    }
                },
                onRelease = { _, _, wx, _, _ ->
                    if (impl.nativeWidget.enabled) {
                        impl.endDragFromFraction(fractionAt(wx))
                    }
                }
            )
        )
        return true
    }

    fun renderProgressBar(
        impl: DesktopProgressBarNativeImpl<DesktopNativeWidget>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "NSProgressIndicator_Bar_NSLevelIndicatorCell")
        val label = (impl.nativeWidget.attributes["label"] as? String).orEmpty()
        val frac = if (impl.isIndeterminate()) 0.45f else impl.getProgress().coerceIn(0f, 1f)
        if (renderContext !is MacosRenderContext) {
            renderNativeControlWithOptionalBootstrapTint(
                impl = impl,
                cellClass = "NSProgressIndicator_Bar_NSLevelIndicatorCell",
                labelText = label,
                bounds = bounds,
                renderContext = renderContext,
                progressFraction = frac
            )
            return true
        }

        val barH = bounds.height.coerceIn(6, 20)
        val barY = bounds.y + (bounds.height - barH) / 2
        val radius = barH / 2f
        MacosGraphicsDrawUtil.fillRoundedRect(renderContext, bounds.x, barY, bounds.width, barH, radius, renderContext.palette.bgElevated)
        val activeType = impl.getStyleSnapshot().type?.takeIf { it != BootstrapType.NONE } ?: BootstrapType.PRIMARY
        val fillCol = BootstrapColorPalette.resolveColor(activeType, renderContext.darkMode)
        val fillW = (bounds.width * frac).roundToInt()
        if (fillW > 0) {
            MacosGraphicsDrawUtil.fillRoundedRect(renderContext, bounds.x, barY, fillW, barH, radius, fillCol)
        }
        if (label.isNotBlank() && barH >= 14) {
            MacosGraphicsDrawUtil.drawText(
                renderContext,
                label,
                bounds.x + 6,
                barY,
                (bounds.width - 12).coerceAtLeast(1),
                barH,
                RgbaColor.WHITE,
                11f,
                600,
                TextAlignment.CENTER
            )
        }
        return true
    }

    fun renderSpinner(
        impl: DesktopSpinnerNativeImpl<DesktopNativeWidget>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "NSProgressIndicator_Spinning")
        val label = (impl.nativeWidget.attributes["label"] as? String).orEmpty()
        if (renderContext !is MacosRenderContext) {
            renderNativeControlWithOptionalBootstrapTint(
                impl = impl,
                cellClass = "NSProgressIndicator_Spinning",
                labelText = label,
                bounds = bounds,
                renderContext = renderContext
            )
            return true
        }

        // Authentic macOS 12-fin NSProgressIndicatorSpinningStyle
        val diameter = min(bounds.width, bounds.height).coerceIn(14, 36)
        val cx = bounds.x + diameter / 2f + (if (label.isBlank()) (bounds.width - diameter) / 2f else 0f)
        val cy = bounds.y + bounds.height / 2f
        val outerR = diameter * 0.45f
        val innerR = diameter * 0.24f
        val baseCol = impl.getStyleSnapshot().type?.takeIf { it != BootstrapType.NONE }?.let {
            BootstrapColorPalette.resolveColor(it, renderContext.darkMode)
        } ?: renderContext.palette.textSecondary

        val phase = ((DesktopNativeWidget.globalTreeMutationVersion % 12L).toInt())
        for (tick in 0 until 12) {
            val angle = (tick * 30.0) * PI / 180.0
            val rel = (tick - phase + 12) % 12
            val alpha = (0.22f + (rel / 11f) * 0.78f).coerceIn(0.2f, 1f)
            val x1 = cx + (cos(angle) * innerR).toFloat()
            val y1 = cy + (sin(angle) * innerR).toFloat()
            val x2 = cx + (cos(angle) * outerR).toFloat()
            val y2 = cy + (sin(angle) * outerR).toFloat()
            MacosGraphicsDrawUtil.drawLine(renderContext, x1, y1, x2, y2, baseCol.withAlpha(alpha), max(1.5f, diameter * 0.085f))
        }
        if (label.isNotBlank()) {
            val lx = (cx + diameter * 0.5f + 8f).roundToInt()
            MacosGraphicsDrawUtil.drawText(
                renderContext,
                label,
                lx,
                bounds.y,
                (bounds.right - lx).coerceAtLeast(1),
                bounds.height,
                renderContext.palette.textSecondary,
                13f
            )
        }
        return true
    }

    fun renderImage(
        impl: DesktopImageNativeImpl<DesktopNativeWidget>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        if (renderContext !is MacosRenderContext) return true
        val imgTokens = impl.nativeWidget.cachedAllClassTokens
        val radius = (impl.nativeWidget.attributes["borderRadiusPx"] as? Float)
            ?: impl.getComponentStyle()?.borderRadius?.topLeft?.resolvePx()
            ?: when {
                "rounded-circle" in imgTokens || "native-row-image-circle" in imgTokens -> min(bounds.width, bounds.height) / 2f
                "rounded-4" in imgTokens -> 12f
                "rounded-3" in imgTokens -> 8f
                "rounded" in imgTokens || "rounded-2" in imgTokens || "native-row-image-rounded" in imgTokens -> 6f
                else -> 0f
            }

        val src = impl.getSourceUrl()?.trim()
        val drawn = if (!src.isNullOrEmpty()) {
            MacosGraphicsDrawUtil.drawIconOrUri(
                ctx = renderContext,
                uriOrIcon = src,
                dstX = bounds.x,
                dstY = bounds.y,
                dstW = bounds.width,
                dstH = bounds.height,
                tintColor = impl.getTintColor(),
                objectFit = impl.getObjectFit(),
                cornerRadius = radius,
                imageImpl = impl
            )
        } else {
            false
        }
        if (!drawn) {
            val ph = impl.getPlaceholderUrl()?.trim()
            if (!ph.isNullOrEmpty()) {
                MacosGraphicsDrawUtil.drawIconOrUri(
                    ctx = renderContext,
                    uriOrIcon = ph,
                    dstX = bounds.x,
                    dstY = bounds.y,
                    dstW = bounds.width,
                    dstH = bounds.height,
                    cornerRadius = radius
                )
            }
        }
        if (impl.hasClickListener() || impl.nativeWidget.tooltipText != null) {
            registerControlClickRegion(renderContext, impl, bounds)
        }
        return true
    }

    fun renderContainer(
        impl: DesktopContainerNativeImpl<DesktopNativeWidget>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "NSStackView_NSBox")
        if (impl.getStyleSnapshot().type != null && impl.getStyleSnapshot().type != BootstrapType.NONE ||
            impl.getStyleSnapshot().backgroundType != null && impl.getStyleSnapshot().backgroundType != BootstrapType.NONE
        ) {
            renderNativeControlWithOptionalBootstrapTint(
                impl = impl,
                cellClass = "NSBox_CardSurface",
                labelText = "",
                bounds = bounds,
                renderContext = renderContext
            )
        }
        return true
    }

    fun renderStyledContainer(
        impl: DesktopStyledContainerNativeImpl<DesktopNativeWidget>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        val widget = impl.nativeWidget
        syncThemeAttributes(widget)
        widget.attributes["useSystemControlAppearance"] = "false"
        widget.attributes["nativeControlCell"] = "NSView_CALayer_StyledContainer"
        widget.attributes["lastRenderedWidth"] = bounds.width.coerceAtLeast(1)
        widget.attributes["lastRenderedHeight"] = bounds.height.coerceAtLeast(1)
        return true
    }

    fun renderTabContainer(
        impl: DesktopTabContainerNativeImpl<DesktopNativeWidget>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "NSTabView")
        impl.nativeWidget.attributes["lastRenderedWidth"] = bounds.width.coerceAtLeast(1)
        impl.nativeWidget.attributes["lastRenderedHeight"] = bounds.height.coerceAtLeast(1)
        return true
    }

    fun renderTab(
        impl: DesktopTabNativeImpl<DesktopNativeWidget>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "NSTabViewItem")
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            cellClass = "NSTabViewItem",
            labelText = impl.titleVal,
            bounds = bounds,
            renderContext = renderContext,
            checked = impl.selectedVal
        )
        if (renderContext is MacosRenderContext) {
            val fg = if (impl.selectedVal) RgbaColor.WHITE else renderContext.palette.textPrimary
            MacosGraphicsDrawUtil.drawText(
                renderContext,
                impl.titleVal,
                bounds.x + 8,
                bounds.y,
                (bounds.width - 16).coerceAtLeast(1),
                bounds.height,
                fg,
                13f,
                if (impl.selectedVal) 600 else 500,
                TextAlignment.CENTER
            )
            registerControlClickRegion(renderContext, impl, bounds)
        }
        return true
    }

    fun renderTable(
        impl: DesktopTableNativeImpl<DesktopNativeWidget>,
        columnWeights: List<Float>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "NSTableView")
        val headers = (impl.nativeWidget.attributes["headers"] as? List<*>)?.map { it.toString() }.orEmpty()
        val colCount = max(headers.size, columnWeights.size).coerceAtLeast(1)
        val totalWidth = bounds.width.coerceAtLeast(colCount * 40)
        val resolvedColumnWidths = computeWeightedColumnWidths(totalWidth, colCount, columnWeights)
        impl.nativeWidget.attributes["resolvedColumnWidthsPx"] = resolvedColumnWidths
        return true
    }

    fun renderLazyGrid(
        impl: DesktopLazyGridNativeImpl<DesktopNativeWidget>,
        minColumnWidthPx: Int,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "NSCollectionView")
        val availW = bounds.width.coerceAtLeast(minColumnWidthPx.coerceAtLeast(1))
        val effectiveCols = resolveAdaptiveGridColumns(
            configuredColumns = impl.getColumnCount(),
            minColumnWidthPx = minColumnWidthPx,
            availableWidthPx = availW
        )
        impl.nativeWidget.attributes["effectiveColumns"] = effectiveCols
        return true
    }

    private fun registerControlClickRegion(
        ctx: MacosRenderContext,
        impl: DesktopBaseNativeImpl<DesktopNativeWidget>,
        bounds: Rect
    ) {
        val widget = impl.nativeWidget
        ctx.registerHitRegion(
            MacosHitRegion(
                left = bounds.x,
                top = bounds.y,
                right = bounds.right,
                bottom = bounds.bottom,
                widget = widget,
                tooltip = widget.tooltipText,
                cursorCss = if (widget.enabled && impl.hasClickListener()) "pointer" else null,
                onClick = if (widget.enabled) {
                    { impl.dispatchClick() }
                } else null,
                onDoubleClick = if (widget.enabled) {
                    { impl.dispatchDoubleClick() }
                } else null,
                onContextMenu = if (widget.enabled) {
                    { wx, wy -> impl.performContextMenu(wx, wy) }
                } else null
            )
        )
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
