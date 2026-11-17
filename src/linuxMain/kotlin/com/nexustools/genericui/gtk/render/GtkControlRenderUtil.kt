@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.gtk.render

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
import com.nexustools.genericui.gtk.Gtk4DynamicBindings
import com.nexustools.genericui.gtk.GtkWidgetHandle
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.threading.GenericUiThreading
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object GtkControlRenderUtil {

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

    fun syncThemeAttributes(widget: GtkWidgetHandle): Boolean {
        val effectiveTheme = Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()
        val isDark = effectiveTheme == ThemeMode.DARK
        widget.attributes["effectiveThemeMode"] = effectiveTheme
        widget.attributes["gtkPreferDarkTheme"] = isDark
        widget.attributes["adwColorScheme"] = if (isDark) "ADW_COLOR_SCHEME_FORCE_DARK" else "ADW_COLOR_SCHEME_FORCE_LIGHT"
        widget.attributes.remove("backdropBlurPx")
        widget.attributes.remove("backdropFilter")
        return isDark
    }

    fun syncStandardControlAppearance(
        impl: DesktopBaseNativeImpl<GtkWidgetHandle>,
        gtkRenderNode: String
    ) {
        val widget = impl.nativeWidget
        val isDark = syncThemeAttributes(widget)
        widget.attributes["gtkRenderNode"] = gtkRenderNode

        if (!impl.acceptsAestheticSkinStyles() && !impl.isHostedInCustomStyleEngine()) {
            for (attr in IGNORED_AESTHETIC_AND_TYPOGRAPHY_ATTRIBUTES) {
                widget.attributes.remove(attr)
            }
        } else if (impl.isHostedInCustomStyleEngine()) {
            syncTypographyAndColorAttributes(impl, isDark, allowAestheticSkin = true)
        }

        val snapshot = impl.getStyleSnapshot()
        val activeType = snapshot.type?.takeIf { it != BootstrapType.NONE }
            ?: snapshot.backgroundType?.takeIf { it != BootstrapType.NONE }

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
        impl: DesktopBaseNativeImpl<GtkWidgetHandle>,
        gtkRenderNode: String
    ) {
        val widget = impl.nativeWidget
        val isDark = syncThemeAttributes(widget)
        widget.attributes["gtkRenderNode"] = gtkRenderNode
        syncTypographyAndColorAttributes(
            impl = impl,
            isDark = isDark,
            allowAestheticSkin = impl.isHostedInCustomStyleEngine()
        )
    }

    private fun syncTypographyAndColorAttributes(
        impl: DesktopBaseNativeImpl<GtkWidgetHandle>,
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
        gtkRenderNode: String,
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

        val accentSurface = checked && (gtkRenderNode.contains("switch") || gtkRenderNode.contains("check") || gtkRenderNode.contains("radio"))
        val baseR = when {
            accentSurface -> 53
            darkMode -> when {
                pressed -> 42
                hovered -> 66
                else -> 54
            }
            else -> when {
                pressed -> 214
                hovered -> 244
                else -> 235
            }
        }
        val baseG = when {
            accentSurface -> 132
            darkMode -> when {
                pressed -> 42
                hovered -> 66
                else -> 54
            }
            else -> when {
                pressed -> 214
                hovered -> 244
                else -> 235
            }
        }
        val baseB = when {
            accentSurface -> 228
            darkMode -> when {
                pressed -> 46
                hovered -> 70
                else -> 58
            }
            else -> when {
                pressed -> 218
                hovered -> 246
                else -> 238
            }
        }

        val cornerRadius = when {
            gtkRenderNode.contains("radio") || gtkRenderNode.contains("spinner") -> min(w, h) / 2f
            gtkRenderNode.contains("switch") -> h / 2f
            gtkRenderNode.contains("progressbar") || gtkRenderNode.contains("scale") -> min(6f, h / 2f)
            else -> min(6f, min(w, h) / 3f)
        }
        val alphaMultiplier = if (disabled) 0.55f else 1.0f

        for (y in 0 until h) {
            val rowOffset = y * w
            val vRatio = if (h > 1) y.toFloat() / (h - 1).toFloat() else 0.5f
            val bevelDelta = when {
                pressed -> ((vRatio - 0.5f) * 12f).roundToInt()
                y == 1 -> if (darkMode) 18 else 12
                else -> ((0.5f - vRatio) * 16f).roundToInt()
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

                if ((gtkRenderNode.contains("progressbar") || gtkRenderNode.contains("scale")) && progressFraction > 0f) {
                    val fillW = (w * progressFraction.coerceIn(0f, 1f)).roundToInt()
                    if (x < fillW) {
                        r = 53
                        g = 132
                        b = 228
                    }
                }

                if (isBorder) {
                    val borderScale = if (darkMode) 0.58f else 0.72f
                    r = (r * borderScale).roundToInt()
                    g = (g * borderScale).roundToInt()
                    b = (b * borderScale).roundToInt()
                } else if (isInnerHighlight) {
                    r += 14
                    g += 14
                    b += 14
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
        impl: DesktopBaseNativeImpl<GtkWidgetHandle>,
        gtkRenderNode: String,
        labelText: String,
        bounds: Rect = impl.nativeWidget.bounds,
        renderContext: Any? = null,
        checked: Boolean = false,
        progressFraction: Float = 0f,
        paintLiveTextToCairo: Boolean = true
    ): IntArray {
        val widget = impl.nativeWidget
        val cairoCtx = renderContext as? GtkCairoRenderContext
        val isDark = cairoCtx?.darkMode ?: syncThemeAttributes(widget)
        val w = bounds.width.coerceAtLeast(24).coerceAtMost(512)
        val h = bounds.height.coerceAtLeast(20).coerceAtMost(256)
        val hovered = impl.isHoveredState() || cairoCtx?.hoveredWidget === widget
        val pressed = impl.isActivePressedState() || cairoCtx?.pressedWidget === widget
        val disabled = !widget.enabled
        val snapshot = impl.getStyleSnapshot()
        val activeType = snapshot.type?.takeIf { it != BootstrapType.NONE }
            ?: snapshot.backgroundType?.takeIf { it != BootstrapType.NONE }

        val spritePixels = renderOffscreenEmptyTextNativeSprite(
            gtkRenderNode = gtkRenderNode,
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
            val themeCfg = cairoCtx?.themeConfig ?: BootstrapColorPalette.activeThemeConfig
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
                darkMode = isDark,
                themeConfig = themeCfg
            ) ?: BootstrapColorPalette.resolveForegroundOn(activeType, isDark, themeCfg)

            widget.attributes["useSystemControlAppearance"] = "false"
            widget.attributes["renderedOffscreenEmptyTextSprite"] = true
            widget.attributes["bootstrapTintColor"] = tintColor.toHex()
            widget.attributes["bootstrapForegroundColor"] = foregroundColor.toHex()
            widget.attributes["contrastingTextRenderedAfterTint"] = true
        } else {
            foregroundColor = if (isDark) RgbaColor(245, 246, 248) else RgbaColor(46, 52, 54)
            widget.attributes["useSystemControlAppearance"] = "true"
            widget.attributes["renderedOffscreenEmptyTextSprite"] = false
            widget.attributes.remove("bootstrapTintColor")
            widget.attributes["bootstrapForegroundColor"] = foregroundColor.toHex()
            widget.attributes["contrastingTextRenderedAfterTint"] = false
        }

        if (cairoCtx != null) {
            GtkGraphicsDrawUtil.blitArgbSprite(
                ctx = cairoCtx,
                cacheKey = 0L,
                straightArgbPixels = spritePixels,
                width = w,
                height = h,
                dstX = bounds.x,
                dstY = bounds.y,
                dstW = bounds.width.coerceAtLeast(1),
                dstH = bounds.height.coerceAtLeast(1)
            )
            if (paintLiveTextToCairo && labelText.isNotEmpty()) {
                val fg = if (disabled) RgbaColor(foregroundColor.r, foregroundColor.g, foregroundColor.b, 150) else foregroundColor
                GtkGraphicsDrawUtil.drawText(
                    ctx = cairoCtx,
                    text = labelText,
                    x = bounds.x + 8,
                    y = bounds.y,
                    w = (bounds.width - 16).coerceAtLeast(1),
                    h = bounds.height.coerceAtLeast(1),
                    color = fg,
                    fontSizePx = 13.5f,
                    weightNumeric = 600,
                    alignment = TextAlignment.CENTER,
                    singleLine = true
                )
            }
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

        widget.attributes["lastRenderedGtkNode"] = gtkRenderNode
        widget.attributes["lastRenderedWidth"] = w
        widget.attributes["lastRenderedHeight"] = h
        widget.attributes["lastRenderedText"] = labelText
        return spritePixels
    }

    @Suppress("UNCHECKED_CAST")
    fun renderLeafControl(
        impl: Any,
        ctx: GtkCairoRenderContext,
        bounds: Rect
    ) {
        when (impl) {
            is DesktopButtonNativeImpl<*> -> renderButton(impl as DesktopButtonNativeImpl<GtkWidgetHandle>, ctx, bounds)
            is DesktopStyledButtonNativeImpl<*> -> renderStyledButton(impl as DesktopStyledButtonNativeImpl<GtkWidgetHandle>, ctx, bounds)
            is DesktopTextInputNativeImpl<*> -> renderTextInput(impl as DesktopTextInputNativeImpl<GtkWidgetHandle>, ctx, bounds)
            is DesktopCheckboxNativeImpl<*> -> renderCheckbox(impl as DesktopCheckboxNativeImpl<GtkWidgetHandle>, ctx, bounds)
            is DesktopSelectNativeImpl<*> -> renderSelect(impl as DesktopSelectNativeImpl<GtkWidgetHandle>, ctx, bounds)
            is DesktopDropdownNativeImpl<*> -> renderDropdown(impl as DesktopDropdownNativeImpl<GtkWidgetHandle>, ctx, bounds)
            is DesktopRangeSliderNativeImpl<*> -> renderRangeSlider(impl as DesktopRangeSliderNativeImpl<GtkWidgetHandle>, ctx, bounds)
            is DesktopProgressBarNativeImpl<*> -> renderProgressBar(impl as DesktopProgressBarNativeImpl<GtkWidgetHandle>, ctx, bounds)
            is DesktopSpinnerNativeImpl<*> -> renderSpinner(impl as DesktopSpinnerNativeImpl<GtkWidgetHandle>, ctx, bounds)
            is DesktopTextNativeImpl<*> -> renderText(impl as DesktopTextNativeImpl<GtkWidgetHandle>, ctx, bounds)
            is DesktopMarkdownViewNativeImpl<*> -> renderMarkdownView(impl as DesktopMarkdownViewNativeImpl<GtkWidgetHandle>, ctx, bounds)
            is DesktopTableNativeImpl<*> -> renderTable(impl as DesktopTableNativeImpl<GtkWidgetHandle>, emptyList(), ctx, bounds)
            is DesktopImageNativeImpl<*> -> GtkGraphicsDrawUtil.drawImageNative(ctx, impl, bounds)
        }
    }

    fun measureButton(impl: DesktopButtonNativeImpl<GtkWidgetHandle>, dpiScale: Float = 1f): Size {
        val textLen = impl.getText().length
        val hasIcon = !impl.getIcon().isNullOrBlank()
        val sizeScale = when (impl.getSize()) {
            BootstrapSize.SM -> 0.85f
            BootstrapSize.LG -> 1.2f
            else -> 1.0f
        }
        val baseW = (max(72, textLen * 8 + (if (hasIcon) 24 else 0) + 28) * sizeScale * dpiScale).roundToInt()
        val baseH = (32 * sizeScale * dpiScale).roundToInt()
        return Size(baseW.toFloat(), baseH.toFloat())
    }

    fun renderButton(
        impl: DesktopButtonNativeImpl<GtkWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "gtk_render_background+gtk_render_frame[button]")
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            gtkRenderNode = "gtk_render_background+gtk_render_frame[button]",
            labelText = impl.getText(),
            bounds = bounds,
            renderContext = renderContext
        )
        registerButtonHitRegion(impl, renderContext as? GtkCairoRenderContext, bounds)
        return true
    }

    fun renderStyledButton(
        impl: DesktopStyledButtonNativeImpl<GtkWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        val widget = impl.nativeWidget
        val cairoCtx = renderContext as? GtkCairoRenderContext
        val isDark = cairoCtx?.darkMode ?: syncThemeAttributes(widget)
        widget.attributes["useSystemControlAppearance"] = "false"
        widget.attributes["gtkRenderNode"] = "GtkDrawingArea_Cairo_StyledButton"
        widget.attributes["lastRenderedWidth"] = bounds.width.coerceAtLeast(1)
        widget.attributes["lastRenderedHeight"] = bounds.height.coerceAtLeast(1)

        if (cairoCtx != null) {
            val style = impl.getComponentStyle()
            val snapshot = impl.getStyleSnapshot()
            val radius = style?.borderRadius?.topLeft?.resolvePx() ?: 6f
            val activeType = snapshot.type?.takeIf { it != BootstrapType.NONE }
                ?: snapshot.backgroundType?.takeIf { it != BootstrapType.NONE }
            val bg = style?.backgroundColor
                ?: activeType?.let { BootstrapColorPalette.resolveColor(it, isDark, cairoCtx.themeConfig) }
                ?: cairoCtx.palette.accentPrimary
            val fg = style?.color
                ?: activeType?.let { BootstrapColorPalette.resolveForegroundOn(it, isDark, cairoCtx.themeConfig) }
                ?: RgbaColor.WHITE
            val effectiveBg = if (cairoCtx.pressedWidget === widget) darken(bg, 0.85f) else if (cairoCtx.hoveredWidget === widget) brighten(bg, 1.08f) else bg
            GtkGraphicsDrawUtil.fillRoundedRect(cairoCtx, bounds.x, bounds.y, bounds.width, bounds.height, radius, effectiveBg)
            style?.borderColor?.let { bc ->
                GtkGraphicsDrawUtil.strokeRoundedRect(cairoCtx, bounds.x, bounds.y, bounds.width, bounds.height, radius, style.borderWidth?.top?.resolvePx() ?: 1f, bc)
            }
            val label = impl.getText()
            if (label.isNotEmpty()) {
                GtkGraphicsDrawUtil.drawText(
                    ctx = cairoCtx,
                    text = label,
                    x = bounds.x + 8,
                    y = bounds.y,
                    w = (bounds.width - 16).coerceAtLeast(1),
                    h = bounds.height.coerceAtLeast(1),
                    color = fg,
                    fontSizePx = style?.fontSize?.resolvePx() ?: 13.5f,
                    weightNumeric = style?.fontWeightNumeric ?: style?.fontWeight?.numericWeight ?: 600,
                    alignment = TextAlignment.CENTER,
                    singleLine = true
                )
            }
            registerButtonHitRegion(impl, cairoCtx, bounds)
        }
        return true
    }

    fun measureText(impl: DesktopTextNativeImpl<GtkWidgetHandle>, dpiScale: Float = 1f): Size {
        val fontSize = (impl.nativeWidget.attributes["fontSizePx"] as? Float) ?: 14f
        val text = applyTextTransform(impl.getText(), impl.getStyleSnapshot().textTransform ?: impl.getComponentStyle()?.textTransform)
        val measured = GtkGraphicsDrawUtil.measureText(
            text = text,
            fontSizePx = fontSize * dpiScale,
            weightNumeric = (impl.nativeWidget.attributes["fontWeightNumeric"] as? Int) ?: 400,
            singleLine = true
        )
        return Size(max(1f, measured.width.toFloat()), max(16f, measured.height.toFloat()))
    }

    fun renderText(
        impl: DesktopTextNativeImpl<GtkWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncTypographyElementAppearance(impl, "gtk_render_layout[label]")
        val widget = impl.nativeWidget
        val transform = impl.getComponentStyle()?.textTransform ?: impl.getStyleSnapshot().textTransform
        val renderedText = applyTextTransform(impl.getText(), transform)
        widget.attributes["renderedText"] = renderedText

        val cairoCtx = renderContext as? GtkCairoRenderContext
        val fg = (widget.attributes["textColor"] as? RgbaColor)
            ?: BootstrapColorPalette.resolveForegroundOn(BootstrapType.NONE, cairoCtx?.darkMode ?: isDarkThemeActive())

        if (cairoCtx != null && renderedText.isNotEmpty()) {
            val fontSize = (widget.attributes["fontSizePx"] as? Float) ?: 14f
            val weight = (widget.attributes["fontWeightNumeric"] as? Int) ?: 400
            val italic = (widget.attributes["fontStyle"] as? String) == "italic"
            val deco = (widget.attributes["textDecoration"] as? String).orEmpty()
            val alignStr = (widget.attributes["textAlign"] as? String) ?: "left"
            val align = when (alignStr) {
                "center" -> TextAlignment.CENTER
                "right", "end" -> TextAlignment.END
                else -> TextAlignment.START
            }
            val bgTint = (widget.attributes["bootstrapTintColor"] as? String)
            if (bgTint != null && impl.getStyleSnapshot().backgroundType != null) {
                val bgCol = BootstrapColorPalette.resolveColor(impl.getStyleSnapshot().backgroundType!!, cairoCtx.darkMode, cairoCtx.themeConfig)
                GtkGraphicsDrawUtil.fillRoundedRect(cairoCtx, bounds.x, bounds.y, bounds.width, bounds.height, 4f, bgCol)
            }
            GtkGraphicsDrawUtil.drawText(
                ctx = cairoCtx,
                text = renderedText,
                x = bounds.x,
                y = bounds.y,
                w = bounds.width.coerceAtLeast(1),
                h = bounds.height.coerceAtLeast(1),
                color = if (widget.enabled) fg else RgbaColor(fg.r, fg.g, fg.b, 140),
                fontSizePx = fontSize,
                weightNumeric = weight,
                italic = italic,
                alignment = align,
                singleLine = (widget.attributes["whiteSpace"] as? String) == "nowrap",
                underline = deco.contains("underline"),
                strikethrough = deco.contains("line-through")
            )
        } else if (renderContext is IntArray && renderContext.isNotEmpty()) {
            val w = bounds.width.coerceAtLeast(16).coerceAtMost(256)
            val h = bounds.height.coerceAtLeast(16).coerceAtMost(128)
            if (renderContext.size >= w * h) {
                overlayContrastingTextGlyphs(renderContext, w, h, renderedText, fg, !widget.enabled)
            }
        }
        return true
    }

    fun renderMarkdownView(
        impl: DesktopMarkdownViewNativeImpl<GtkWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncTypographyElementAppearance(impl, "GtkTextView_PangoAttrList_Markdown")
        val md = impl.getMarkdown()
        impl.nativeWidget.attributes["renderedMarkdownLength"] = md.length
        impl.nativeWidget.attributes["lastRenderedWidth"] = bounds.width.coerceAtLeast(1)
        impl.nativeWidget.attributes["lastRenderedHeight"] = bounds.height.coerceAtLeast(1)
        val cairoCtx = renderContext as? GtkCairoRenderContext
        if (cairoCtx != null && md.isNotEmpty()) {
            val fg = (impl.nativeWidget.attributes["textColor"] as? RgbaColor) ?: cairoCtx.palette.textPrimary
            GtkGraphicsDrawUtil.drawText(
                ctx = cairoCtx,
                text = md,
                x = bounds.x + 4,
                y = bounds.y + 4,
                w = (bounds.width - 8).coerceAtLeast(1),
                h = (bounds.height - 8).coerceAtLeast(1),
                color = fg,
                fontSizePx = (impl.nativeWidget.attributes["fontSizePx"] as? Float) ?: 13.5f,
                singleLine = false
            )
        }
        return true
    }

    fun renderTextInput(
        impl: DesktopTextInputNativeImpl<GtkWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        val node = if (impl.isMultiline()) "gtk_render_background+gtk_render_frame[textview]" else "gtk_render_background+gtk_render_frame[entry]"
        syncStandardControlAppearance(impl, node)
        val display = if (impl.isPasswordMode()) {
            "•".repeat(impl.getValue().length)
        } else {
            impl.getValue().ifEmpty { impl.getPlaceholder() }
        }
        val cairoCtx = renderContext as? GtkCairoRenderContext
        val spritePixels = renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            gtkRenderNode = node,
            labelText = display,
            bounds = bounds,
            renderContext = renderContext,
            paintLiveTextToCairo = false
        )

        val fieldLabel = impl.getLabel()?.trim()?.takeIf { it.isNotEmpty() }
        if (cairoCtx != null) {
            val widget = impl.nativeWidget
            val focused = cairoCtx.focusedWidget === widget
            val inputBg = cairoCtx.palette.bgInput
            val borderColor = if (focused) cairoCtx.palette.accentPrimary else cairoCtx.palette.borderStrong
            GtkGraphicsDrawUtil.fillRoundedRect(cairoCtx, bounds.x, bounds.y, bounds.width, bounds.height, 6f, inputBg)
            GtkGraphicsDrawUtil.strokeRoundedRect(
                cairoCtx,
                bounds.x,
                bounds.y,
                bounds.width,
                bounds.height,
                6f,
                if (focused) 2f else 1f,
                borderColor
            )
            if (display.isNotEmpty()) {
                val isPlaceholder = impl.getValue().isEmpty()
                val textColor = if (isPlaceholder) cairoCtx.palette.textMuted else cairoCtx.palette.textPrimary
                GtkGraphicsDrawUtil.drawText(
                    ctx = cairoCtx,
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
                val badgeW = min(bounds.width - 20, max(24, GtkGraphicsDrawUtil.measureText(fieldLabel, 11f, 500).width + 10))
                if (badgeW > 12) {
                    GtkGraphicsDrawUtil.fillRect(cairoCtx, bounds.x + 10, bounds.y - 6, badgeW, 13, inputBg)
                    GtkGraphicsDrawUtil.drawText(
                        ctx = cairoCtx,
                        text = fieldLabel,
                        x = bounds.x + 14,
                        y = bounds.y - 7,
                        w = badgeW - 8,
                        h = 14,
                        color = if (focused) cairoCtx.palette.accentPrimary else cairoCtx.palette.textSecondary,
                        fontSizePx = 11f,
                        weightNumeric = 500,
                        singleLine = true
                    )
                }
            }
            cairoCtx.registerHitRegion(
                GtkHitRegion(
                    left = bounds.x,
                    top = bounds.y,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    widget = widget,
                    cursorCss = "text",
                    isTextInput = true,
                    onClick = {
                        onRequestTextInputFocus?.invoke(impl)
                    }
                )
            )
        }

        if (fieldLabel != null) {
            impl.nativeWidget.attributes["lastRenderedLabel"] = fieldLabel
            val w = bounds.width.coerceAtLeast(24).coerceAtMost(512)
            val h = bounds.height.coerceAtLeast(20).coerceAtMost(256)
            val isDark = cairoCtx?.darkMode ?: syncThemeAttributes(impl.nativeWidget)
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
        impl: DesktopCheckboxNativeImpl<GtkWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        val node = when {
            impl.switchStyle -> "gtk_render_background+gtk_render_slider[switch]"
            impl.radioStyle -> "gtk_render_option[radio]"
            else -> "gtk_render_check[checkbutton]"
        }
        syncStandardControlAppearance(impl, node)
        val cairoCtx = renderContext as? GtkCairoRenderContext
        val checked = impl.isChecked()
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            gtkRenderNode = node,
            labelText = impl.getLabel(),
            bounds = if (cairoCtx != null) Rect(bounds.x, bounds.y + max(0, (bounds.height - 20) / 2), if (impl.switchStyle) 36 else 20, 20) else bounds,
            renderContext = renderContext,
            checked = checked,
            paintLiveTextToCairo = false
        )
        if (cairoCtx != null) {
            val boxSize = 20
            val indicatorW = if (impl.switchStyle) 36 else boxSize
            val iy = bounds.y + max(0, (bounds.height - boxSize) / 2)
            val label = impl.getLabel()
            if (checked) {
                if (impl.switchStyle) {
                    GtkGraphicsDrawUtil.fillRoundedRect(cairoCtx, bounds.x + indicatorW - 18, iy + 2, 16, 16, 8f, RgbaColor.WHITE)
                } else if (impl.radioStyle) {
                    GtkGraphicsDrawUtil.fillRoundedRect(cairoCtx, bounds.x + 6, iy + 6, 8, 8, 4f, RgbaColor.WHITE)
                } else {
                    Gtk4DynamicBindings.let { b ->
                        cairoCtx.cairoContext?.let { cr ->
                            b.cairoSave(cr)
                            b.cairoSetSourceRgba(cr, 1.0, 1.0, 1.0, 1.0)
                            b.cairoSetLineWidth(cr, 2.0)
                            b.cairoMoveTo(cr, bounds.x + 4.5, iy + 10.5)
                            b.cairoLineTo(cr, bounds.x + 8.5, iy + 14.5)
                            b.cairoLineTo(cr, bounds.x + 15.5, iy + 6.0)
                            b.cairoStroke(cr)
                            b.cairoRestore(cr)
                        }
                    }
                }
            } else if (impl.switchStyle) {
                GtkGraphicsDrawUtil.fillRoundedRect(cairoCtx, bounds.x + 2, iy + 2, 16, 16, 8f, RgbaColor(220, 224, 230))
            }
            if (label.isNotEmpty()) {
                GtkGraphicsDrawUtil.drawText(
                    ctx = cairoCtx,
                    text = label,
                    x = bounds.x + indicatorW + 8,
                    y = bounds.y,
                    w = (bounds.width - indicatorW - 8).coerceAtLeast(1),
                    h = bounds.height.coerceAtLeast(1),
                    color = if (impl.nativeWidget.enabled) cairoCtx.palette.textPrimary else cairoCtx.palette.textMuted,
                    fontSizePx = 13.5f,
                    singleLine = true
                )
            }
            cairoCtx.registerHitRegion(
                GtkHitRegion(
                    left = bounds.x,
                    top = bounds.y,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    widget = impl.nativeWidget,
                    cursorCss = if (impl.nativeWidget.enabled) "pointer" else null,
                    onClick = {
                        if (impl.nativeWidget.enabled) {
                            val next = !impl.isChecked()
                            GenericUiThreading.runOnLogic {
                                impl.setChecked(next)
                            }
                        }
                    }
                )
            )
        }
        return true
    }

    fun renderSelect(
        impl: DesktopSelectNativeImpl<GtkWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "gtk_render_background+gtk_render_frame[dropdown]")
        val selectedValue = impl.getSelectedValue()
        val selectedLabel = impl.getOptions().firstOrNull { it.first == selectedValue }?.second
            ?: selectedValue.orEmpty()
        val cairoCtx = renderContext as? GtkCairoRenderContext
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            gtkRenderNode = "gtk_render_background+gtk_render_frame[dropdown]",
            labelText = selectedLabel,
            bounds = bounds,
            renderContext = renderContext,
            paintLiveTextToCairo = false
        )
        if (cairoCtx != null) {
            val fg = (impl.nativeWidget.attributes["bootstrapForegroundColor"] as? String)
                ?.let { null } ?: cairoCtx.palette.textPrimary
            if (selectedLabel.isNotEmpty()) {
                GtkGraphicsDrawUtil.drawText(
                    ctx = cairoCtx,
                    text = selectedLabel,
                    x = bounds.x + 10,
                    y = bounds.y,
                    w = (bounds.width - 28).coerceAtLeast(1),
                    h = bounds.height.coerceAtLeast(1),
                    color = fg,
                    fontSizePx = 13.5f,
                    singleLine = true
                )
            }
            GtkGraphicsDrawUtil.drawText(
                ctx = cairoCtx,
                text = "▾",
                x = bounds.right - 22,
                y = bounds.y,
                w = 16,
                h = bounds.height.coerceAtLeast(1),
                color = cairoCtx.palette.textSecondary,
                fontSizePx = 12f,
                alignment = TextAlignment.CENTER,
                singleLine = true
            )
            cairoCtx.registerHitRegion(
                GtkHitRegion(
                    left = bounds.x,
                    top = bounds.y,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    widget = impl.nativeWidget,
                    cursorCss = if (impl.nativeWidget.enabled) "pointer" else null,
                    onClick = {
                        if (impl.nativeWidget.enabled) {
                            onRequestSelectCycle?.invoke(impl, bounds) ?: run {
                                val opts = impl.getOptions()
                                if (opts.isNotEmpty()) {
                                    val curIdx = opts.indexOfFirst { it.first == impl.getSelectedValue() }
                                    val nextOpt = opts[(curIdx + 1) % opts.size]
                                    GenericUiThreading.runOnLogic { impl.setSelectedValue(nextOpt.first) }
                                }
                            }
                        }
                    }
                )
            )
        }
        return true
    }

    fun renderDropdown(
        impl: DesktopDropdownNativeImpl<GtkWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "gtk_render_background+gtk_render_frame[menubutton]")
        val label = (impl.nativeWidget.attributes["label"] as? String).orEmpty()
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            gtkRenderNode = "gtk_render_background+gtk_render_frame[menubutton]",
            labelText = label,
            bounds = bounds,
            renderContext = renderContext
        )
        val cairoCtx = renderContext as? GtkCairoRenderContext
        if (cairoCtx != null) {
            cairoCtx.registerHitRegion(
                GtkHitRegion(
                    left = bounds.x,
                    top = bounds.y,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    widget = impl.nativeWidget,
                    cursorCss = if (impl.nativeWidget.enabled) "pointer" else null,
                    onClick = {
                        if (impl.nativeWidget.enabled) {
                            onRequestDropdownPopup?.invoke(impl, bounds) ?: GenericUiThreading.runOnLogic {
                                impl.nativeWidget.attributes["lastClickedTimestampMs"] = 1L
                            }
                        }
                    }
                )
            )
        }
        return true
    }

    fun renderRangeSlider(
        impl: DesktopRangeSliderNativeImpl<GtkWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "gtk_render_slider[scale]")
        val span = (impl.getMax() - impl.getMin()).coerceAtLeast(1e-6)
        val fraction = ((impl.getValue() - impl.getMin()) / span).toFloat().coerceIn(0f, 1f)
        val cairoCtx = renderContext as? GtkCairoRenderContext
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            gtkRenderNode = "gtk_render_slider[scale]",
            labelText = "",
            bounds = bounds,
            renderContext = renderContext,
            progressFraction = fraction
        )
        if (cairoCtx != null) {
            val trackH = 6
            val trackY = bounds.y + (bounds.height - trackH) / 2
            val padX = 10
            val trackW = (bounds.width - padX * 2).coerceAtLeast(16)
            val trackX = bounds.x + padX
            GtkGraphicsDrawUtil.fillRoundedRect(cairoCtx, trackX, trackY, trackW, trackH, 3f, cairoCtx.palette.bgElevated)
            val fillW = (trackW * fraction).roundToInt().coerceIn(0, trackW)
            if (fillW > 0) {
                GtkGraphicsDrawUtil.fillRoundedRect(cairoCtx, trackX, trackY, fillW, trackH, 3f, cairoCtx.palette.accentPrimary)
            }
            val thumbD = 16
            val thumbX = (trackX + fillW - thumbD / 2).coerceIn(trackX - thumbD / 2, trackX + trackW - thumbD / 2)
            val thumbY = bounds.y + (bounds.height - thumbD) / 2
            GtkGraphicsDrawUtil.fillRoundedRect(cairoCtx, thumbX, thumbY, thumbD, thumbD, 8f, RgbaColor.WHITE)
            GtkGraphicsDrawUtil.strokeRoundedRect(cairoCtx, thumbX, thumbY, thumbD, thumbD, 8f, 1.5f, cairoCtx.palette.borderStrong)

            fun updateFromWindowX(wx: Float) {
                val frac = ((wx - trackX) / trackW.toFloat()).coerceIn(0f, 1f)
                val targetVal = impl.getMin() + span * frac
                GenericUiThreading.runOnLogic { impl.setValue(targetVal) }
            }
            cairoCtx.registerHitRegion(
                GtkHitRegion(
                    left = bounds.x,
                    top = bounds.y,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    widget = impl.nativeWidget,
                    cursorCss = if (impl.nativeWidget.enabled) "pointer" else null,
                    onPress = { _, _, wx, _, _, _ ->
                        if (impl.nativeWidget.enabled) {
                            updateFromWindowX(wx)
                            true
                        } else false
                    },
                    onDrag = { _, _, wx, _ ->
                        if (impl.nativeWidget.enabled) updateFromWindowX(wx)
                    }
                )
            )
        }
        return true
    }

    fun renderProgressBar(
        impl: DesktopProgressBarNativeImpl<GtkWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "gtk_render_background+gtk_render_frame[progressbar]")
        val label = (impl.nativeWidget.attributes["label"] as? String).orEmpty()
        val frac = if (impl.isIndeterminate()) 0.5f else impl.getProgress().coerceIn(0f, 1f)
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            gtkRenderNode = "gtk_render_background+gtk_render_frame[progressbar]",
            labelText = label,
            bounds = bounds,
            renderContext = renderContext,
            progressFraction = frac
        )
        return true
    }

    fun renderSpinner(
        impl: DesktopSpinnerNativeImpl<GtkWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "gtk_render_activity[spinner]")
        val label = (impl.nativeWidget.attributes["label"] as? String).orEmpty()
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            gtkRenderNode = "gtk_render_activity[spinner]",
            labelText = label,
            bounds = bounds,
            renderContext = renderContext
        )
        val cairoCtx = renderContext as? GtkCairoRenderContext
        val cr = cairoCtx?.cairoContext
        if (cr != null) {
            val r = (min(bounds.width, bounds.height) * 0.36).coerceAtLeast(4.0)
            val cx = bounds.x + bounds.width * 0.5
            val cy = bounds.y + bounds.height * 0.5
            Gtk4DynamicBindings.cairoSave(cr)
            Gtk4DynamicBindings.cairoNewPath(cr)
            Gtk4DynamicBindings.cairoSetLineWidth(cr, 2.5)
            Gtk4DynamicBindings.cairoSetLineCap(cr, Gtk4DynamicBindings.CAIRO_LINE_CAP_ROUND)
            val c = cairoCtx.palette.accentPrimary
            Gtk4DynamicBindings.cairoSetSourceRgba(cr, c.r / 255.0, c.g / 255.0, c.b / 255.0, 1.0)
            Gtk4DynamicBindings.cairoArc(cr, cx, cy, r, -PI * 0.4, PI * 1.1)
            Gtk4DynamicBindings.cairoStroke(cr)
            Gtk4DynamicBindings.cairoRestore(cr)
        }
        return true
    }

    fun renderContainer(
        impl: DesktopContainerNativeImpl<GtkWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "gtk_render_background+gtk_render_frame[box]")
        val hasTint = (impl.getStyleSnapshot().type != null && impl.getStyleSnapshot().type != BootstrapType.NONE) ||
            (impl.getStyleSnapshot().backgroundType != null && impl.getStyleSnapshot().backgroundType != BootstrapType.NONE)
        if (hasTint) {
            renderNativeControlWithOptionalBootstrapTint(
                impl = impl,
                gtkRenderNode = "gtk_render_background+gtk_render_frame[card]",
                labelText = "",
                bounds = bounds,
                renderContext = renderContext
            )
        }
        return true
    }

    fun renderStyledContainer(
        impl: DesktopStyledContainerNativeImpl<GtkWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        val widget = impl.nativeWidget
        syncThemeAttributes(widget)
        widget.attributes["useSystemControlAppearance"] = "false"
        widget.attributes["gtkRenderNode"] = "GtkDrawingArea_Cairo_StyledContainer"
        widget.attributes["lastRenderedWidth"] = bounds.width.coerceAtLeast(1)
        widget.attributes["lastRenderedHeight"] = bounds.height.coerceAtLeast(1)
        return true
    }

    fun renderTabContainer(
        impl: DesktopTabContainerNativeImpl<GtkWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "gtk_render_background[AdwViewStack]")
        impl.nativeWidget.attributes["lastRenderedWidth"] = bounds.width.coerceAtLeast(1)
        impl.nativeWidget.attributes["lastRenderedHeight"] = bounds.height.coerceAtLeast(1)
        return true
    }

    fun renderTab(
        impl: DesktopTabNativeImpl<GtkWidgetHandle>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "gtk_render_background+gtk_render_frame[tab]")
        renderNativeControlWithOptionalBootstrapTint(
            impl = impl,
            gtkRenderNode = "gtk_render_background+gtk_render_frame[tab]",
            labelText = impl.titleVal,
            bounds = bounds,
            renderContext = renderContext,
            checked = impl.selectedVal
        )
        return true
    }

    fun renderTable(
        impl: DesktopTableNativeImpl<GtkWidgetHandle>,
        columnWeights: List<Float>,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "GtkColumnView")
        val headers = (impl.nativeWidget.attributes["headers"] as? List<*>)?.map { it.toString() }.orEmpty()
        val colCount = max(headers.size, columnWeights.size).coerceAtLeast(1)
        val totalWidth = bounds.width.coerceAtLeast(colCount * 40)
        val resolvedColumnWidths = computeWeightedColumnWidths(totalWidth, colCount, columnWeights)
        impl.nativeWidget.attributes["resolvedColumnWidthsPx"] = resolvedColumnWidths
        val cairoCtx = renderContext as? GtkCairoRenderContext
        if (cairoCtx != null) {
            GtkGraphicsDrawUtil.fillRoundedRect(cairoCtx, bounds.x, bounds.y, bounds.width, bounds.height, 6f, cairoCtx.palette.bgCard)
            GtkGraphicsDrawUtil.strokeRoundedRect(cairoCtx, bounds.x, bounds.y, bounds.width, bounds.height, 6f, 1f, cairoCtx.palette.borderSubtle)
            val rowH = 30
            if (headers.isNotEmpty() && bounds.height >= rowH) {
                GtkGraphicsDrawUtil.fillRect(cairoCtx, bounds.x, bounds.y, bounds.width, rowH, cairoCtx.palette.bgHeader)
                var cx = bounds.x
                for (i in headers.indices) {
                    val cw = resolvedColumnWidths.getOrElse(i) { 80 }
                    GtkGraphicsDrawUtil.drawText(cairoCtx, headers[i], cx + 8, bounds.y, (cw - 16).coerceAtLeast(1), rowH, cairoCtx.palette.textPrimary, 12.5f, 600, singleLine = true)
                    cx += cw
                }
            }
        }
        return true
    }

    fun renderLazyGrid(
        impl: DesktopLazyGridNativeImpl<GtkWidgetHandle>,
        minColumnWidthPx: Int,
        renderContext: Any?,
        bounds: Rect = impl.nativeWidget.bounds
    ): Boolean {
        syncStandardControlAppearance(impl, "GtkGridView")
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

    private fun registerButtonHitRegion(
        impl: DesktopBaseNativeImpl<GtkWidgetHandle>,
        ctx: GtkCairoRenderContext?,
        bounds: Rect
    ) {
        ctx ?: return
        val widget = impl.nativeWidget
        ctx.registerHitRegion(
            GtkHitRegion(
                left = bounds.x,
                top = bounds.y,
                right = bounds.right,
                bottom = bounds.bottom,
                widget = widget,
                cursorCss = if (widget.enabled) "pointer" else null,
                onClick = {
                    if (widget.enabled) {
                        GenericUiThreading.runOnLogic {
                            impl.dispatchClick()
                        }
                    }
                }
            )
        )
    }

    private fun darken(c: RgbaColor, factor: Float): RgbaColor = RgbaColor(
        r = (c.r * factor).roundToInt().coerceIn(0, 255),
        g = (c.g * factor).roundToInt().coerceIn(0, 255),
        b = (c.b * factor).roundToInt().coerceIn(0, 255),
        a = c.a
    )

    private fun brighten(c: RgbaColor, factor: Float): RgbaColor = RgbaColor(
        r = (c.r * factor).roundToInt().coerceIn(0, 255),
        g = (c.g * factor).roundToInt().coerceIn(0, 255),
        b = (c.b * factor).roundToInt().coerceIn(0, 255),
        a = c.a
    )

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
