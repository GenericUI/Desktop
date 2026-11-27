@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.css.CssBorderStyle
import com.nexustools.genericui.enums.css.CssUnit
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.css.FastBackdropBlurKernel
import com.nexustools.genericui.style.css.GradientStopNormalizer
import com.nexustools.genericui.style.css.StyledVisualSpec
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.style.gradient.Gradient.LinearGradient
import com.nexustools.genericui.style.gradient.Gradient.RadialGradient
import com.nexustools.genericui.style.theme.ThemeConfig
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.windows.AC_SRC_ALPHA
import platform.windows.AC_SRC_OVER
import platform.windows.AlphaBlend
import platform.windows.BITMAPINFO
import platform.windows.BITMAPINFOHEADER
import platform.windows.BI_RGB
import platform.windows.BLENDFUNCTION
import platform.windows.BitBlt
import platform.windows.CreateCompatibleDC
import platform.windows.CreateDIBSection
import platform.windows.CreatePen
import platform.windows.CreateSolidBrush
import platform.windows.DIB_RGB_COLORS
import platform.windows.DT_CENTER
import platform.windows.DT_END_ELLIPSIS
import platform.windows.DT_LEFT
import platform.windows.DT_NOPREFIX
import platform.windows.DT_RIGHT
import platform.windows.DT_SINGLELINE
import platform.windows.DT_VCENTER
import platform.windows.DeleteDC
import platform.windows.DeleteObject
import platform.windows.DrawTextW
import platform.windows.FillRect
import platform.windows.GW_HWNDNEXT
import platform.windows.GdiFlush
import platform.windows.GetParent
import platform.windows.GetWindow
import platform.windows.GetWindowRect
import platform.windows.HBITMAP
import platform.windows.HBRUSH
import platform.windows.HDC
import platform.windows.HFONT
import platform.windows.HGDIOBJ
import platform.windows.HPEN
import platform.windows.HWND
import platform.windows.IsWindowVisible
import platform.windows.MapWindowPoints
import platform.windows.POINT
import platform.windows.PS_INSIDEFRAME
import platform.windows.PS_SOLID
import platform.windows.PrintWindow
import platform.windows.RECT
import platform.windows.RoundRect
import platform.windows.SRCCOPY
import platform.windows.SelectObject
import platform.windows.SetBkMode
import platform.windows.SetTextColor
import platform.windows.TRANSPARENT
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Shared 32-bpp DIB surface painter for custom retained child `HWND`s
 * (`GenericUIStyledContainerW`, `GenericUIStyledComponentW`, `GenericUIStyledButtonW`,
 * `GenericUISemanticSurfaceW`, `GenericUIBadgeW`, and custom controls) as well as genuine
 * `"Button"` (`WC_BUTTON`) semantic Bootstrap controls via `NM_CUSTOMDRAW`.
 *
 * Supports rounded SDF clipping, borders, box shadows, focus rings, CSS multi-stop linear/radial
 * gradients, all 10 CSS `backdrop-filter` and `filter` operations via
 * [Win32Direct2dBackdropBlurEngine] / [FastBackdropBlurKernel], and ClearType alpha-preserved text.
 */
object Win32StyledWidgetHelper {

    private const val PW_RENDERFULLCONTENT_FLAG: UInt = 2u

    private var reusableSurfaceDc: HDC? = null
    private var reusableSurfaceBmp: HBITMAP? = null
    private var reusableSurfaceOldBmp: HGDIOBJ? = null
    private var reusableSurfacePixels: CPointer<UIntVar>? = null
    private var reusableSurfaceW: Int = 0
    private var reusableSurfaceH: Int = 0

    private var scratchSiblingDc: HDC? = null
    private var scratchSiblingBmp: HBITMAP? = null
    private var scratchSiblingOldBmp: HGDIOBJ? = null
    private var scratchSiblingPixels: CPointer<UIntVar>? = null
    private var scratchSiblingW: Int = 0
    private var scratchSiblingH: Int = 0

    private var backdropCaptureDepth: Int = 0

    private class CachedEffectiveSpec(
        val selfAttrVer: Long,
        val stateBits: Int,
        val spec: StyledVisualSpec
    )

    private val cachedSolidBrushes = HashMap<UInt, HBRUSH>()
    private val cachedSolidPens = HashMap<Long, HPEN>()
    private val cachedAncestorBgByHwnd = HashMap<Long, RgbaColor>()
    private val cachedInTransparentDwmByHwnd = HashMap<Long, Boolean>()
    private var cachedAncestorBgMutationVer: Long = -1L
    private var cachedAncestorBgIsDark: Boolean = false
    private val cachedEffectiveSpecsByHwnd = HashMap<Long, CachedEffectiveSpec>()
    private var cachedSpecMutationVer: Long = -1L
    private var cachedSpecIsDark: Boolean = false

    internal var ancestorBgCacheEpoch: Long = 0L

    internal fun invalidateAncestorBackgroundCache(hwnd: HWND? = null) {
        ancestorBgCacheEpoch++
        if (hwnd == null) {
            cachedAncestorBgByHwnd.clear()
            cachedInTransparentDwmByHwnd.clear()
            cachedAncestorBgMutationVer = -1L
            cachedEffectiveSpecsByHwnd.clear()
            cachedSpecMutationVer = -1L
        } else {
            val key = hwnd.toLong()
            cachedAncestorBgByHwnd.remove(key)
            cachedInTransparentDwmByHwnd.remove(key)
            cachedEffectiveSpecsByHwnd.remove(key)
        }
    }

    private var scratchSurfacePixels = IntArray(0)
    private var scratchCapturePixels = IntArray(0)
    private var scratchStagePixels = IntArray(0)
    private var scratchTextBeforeWords = IntArray(0)
    private var scratchFilterPixels = IntArray(0)

    private fun obtainScratchSurfacePixels(needed: Int): IntArray {
        if (scratchSurfacePixels.size < needed) {
            scratchSurfacePixels = IntArray(needed)
        }
        return scratchSurfacePixels
    }

    private fun obtainScratchCapturePixels(needed: Int): IntArray {
        if (scratchCapturePixels.size < needed) {
            scratchCapturePixels = IntArray(needed)
        }
        return scratchCapturePixels
    }

    private fun obtainScratchStagePixels(needed: Int): IntArray {
        if (scratchStagePixels.size < needed) {
            scratchStagePixels = IntArray(needed)
        }
        return scratchStagePixels
    }

    private fun obtainScratchTextBeforeWords(needed: Int): IntArray {
        if (scratchTextBeforeWords.size < needed) {
            scratchTextBeforeWords = IntArray(needed)
        }
        return scratchTextBeforeWords
    }

    private fun obtainScratchFilterPixels(needed: Int): IntArray {
        if (scratchFilterPixels.size < needed) {
            scratchFilterPixels = IntArray(needed)
        }
        return scratchFilterPixels
    }

    private fun obtainSolidBrush(color: RgbaColor): HBRUSH? {
        val colorRef = Win32DwmUxThemeBridge.colorRefRgb(color.r, color.g, color.b)
        return obtainSolidBrushRef(colorRef)
    }

    private fun obtainSolidBrushRef(colorRef: UInt): HBRUSH? =
        cachedSolidBrushes[colorRef] ?: CreateSolidBrush(colorRef)?.also {
            cachedSolidBrushes[colorRef] = it
        }

    private fun obtainSolidPen(widthPx: Int, color: RgbaColor, insideFrame: Boolean = false): HPEN? {
        val colorRef = Win32DwmUxThemeBridge.colorRefRgb(color.r, color.g, color.b)
        val style = if (insideFrame) PS_INSIDEFRAME else PS_SOLID
        val key = ((style.toLong() and 0xFFL) shl 48) or
            ((widthPx.toLong() and 0xFFFFL) shl 32) or
            colorRef.toLong()
        return cachedSolidPens[key] ?: CreatePen(style, widthPx, colorRef)?.also {
            cachedSolidPens[key] = it
        }
    }

    /**
     * Paints a custom retained child [Win32HwndHandle] into its `WM_PAINT` [hdc].
     */
    fun paintCustomEntry(
        entry: Win32HwndHandle?,
        hwnd: HWND,
        hdc: HDC,
        width: Int,
        height: Int,
        dirtyLeft: Int = 0,
        dirtyTop: Int = 0,
        dirtyRight: Int = width,
        dirtyBottom: Int = height
    ) {
        if (width <= 0 || height <= 0) return
        val isDark = Win32NativeWindowHost.isEffectivelyDarkTheme()

        if (entry == null || entry.ownerImpl == null) {
            fillSolidParentBackdrop(
                hwnd = hwnd,
                hdc = hdc,
                width = width,
                height = height,
                isDark = isDark,
                dirtyLeft = dirtyLeft,
                dirtyTop = dirtyTop,
                dirtyRight = dirtyRight,
                dirtyBottom = dirtyBottom
            )
            return
        }
        val owner = entry.ownerImpl!!

        val resolvedClass = entry.actualWin32ClassName
        val spec = resolveEffectiveVisualSpec(entry, owner, resolvedClass, isDark)
        val hasContentSpec = spec.hasCustomBackground || spec.hasBorder || spec.hasBackdropFilter ||
            spec.hasFocusRing || (spec.boxShadowColor != null) || (spec.filter?.hasActiveEffects == true)

        val textLabel: String? = when (owner) {
            is DesktopButtonNativeImpl<*> -> buildButtonLabel(owner)
            is DesktopTextNativeImpl<*> -> if (resolvedClass == "GenericUIBadgeW") owner.getText() else null
            else -> null
        }

        if (!hasContentSpec && textLabel.isNullOrBlank()) {
            fillSolidParentBackdrop(
                hwnd = hwnd,
                hdc = hdc,
                width = width,
                height = height,
                isDark = isDark,
                dirtyLeft = dirtyLeft,
                dirtyTop = dirtyTop,
                dirtyRight = dirtyRight,
                dirtyBottom = dirtyBottom
            )
            return
        }

        val iconBuffer: ImageBuffer? = if (owner is DesktopButtonNativeImpl<*>) {
            resolveChildIconBuffer(entry)
        } else {
            null
        }

        renderStyledSpecToHdc(
            hwnd = hwnd,
            hdc = hdc,
            width = width,
            height = height,
            spec = spec,
            isDark = isDark,
            focused = owner.isFocusedState(),
            fontHandle = entry.fontHandle,
            text = textLabel,
            textColorOverride = resolveTextColorForSpec(entry, owner, spec, resolvedClass, isDark),
            iconBuffer = iconBuffer,
            cornerRadii = resolveCornerRadii(entry, owner, spec, width, height),
            dirtyLeft = dirtyLeft,
            dirtyTop = dirtyTop,
            dirtyRight = dirtyRight,
            dirtyBottom = dirtyBottom
        )
    }

    private fun resolveChildIconBuffer(entry: Win32HwndHandle): ImageBuffer? {
        for (child in entry.children) {
            val raw = child.attributes["activeRawBuffer"] as? ImageBuffer
            if (raw != null) return raw
            val imgImpl = child.attributes["nativeImpl"] as? DesktopImageNativeImpl<*>
            val loaded = imgImpl?.loadedImageBuffer
            if (loaded != null) return loaded
        }
        return null
    }

    /**
     * Paints a semantic Bootstrap `Button` into its `NM_CUSTOMDRAW` [hdc] when UxTheme cannot
     * natively tint the standard push-button bezel.
     */
    fun paintCustomBootstrapButtonToHdc(
        entry: Win32HwndHandle,
        hwnd: HWND,
        hdc: HDC,
        width: Int,
        height: Int,
        owner: DesktopButtonNativeImpl<*>
    ) {
        if (width <= 0 || height <= 0) return
        val isDark = Win32NativeWindowHost.isEffectivelyDarkTheme()
        val spec = resolveEffectiveVisualSpec(entry, owner, "Button", isDark)
        val iconBuffer = resolveChildIconBuffer(entry)
        renderStyledSpecToHdc(
            hwnd = hwnd,
            hdc = hdc,
            width = width,
            height = height,
            spec = spec,
            isDark = isDark,
            focused = owner.isFocusedState(),
            fontHandle = entry.fontHandle,
            text = buildButtonLabel(owner),
            textColorOverride = spec.textColor ?: BootstrapColorPalette.resolveForegroundOn(owner.getType() ?: BootstrapType.NONE, isDark),
            iconBuffer = iconBuffer
        )
    }

    /**
     * Resolves the effective [StyledVisualSpec] for custom surface, button, semantic container,
     * and badge child `HWND`s, cached per `HWND` against `(selfAttrMutationVersion, stateBits)`
     * and non-overlay mutation/theme versions.
     */
    fun resolveEffectiveVisualSpec(
        entry: Win32HwndHandle,
        owner: DesktopBaseNativeImpl<*>,
        win32Class: String,
        isDark: Boolean
    ): StyledVisualSpec {
        val ver = DesktopNativeWidget.globalNonOverlayMutationVersion
        if (cachedSpecMutationVer != ver || cachedSpecIsDark != isDark) {
            cachedEffectiveSpecsByHwnd.clear()
            cachedSpecMutationVer = ver
            cachedSpecIsDark = isDark
        }
        val stateBits = (if (owner.isHoveredState()) 1 else 0) or
            (if (owner.isActivePressedState()) 2 else 0) or
            (if (owner.isFocusedState()) 4 else 0) or
            (if (owner.isEnabled()) 8 else 0)
        val hwndKey = entry.hwnd?.toLong()
        if (hwndKey != null) {
            val cached = cachedEffectiveSpecsByHwnd[hwndKey]
            if (cached != null &&
                cached.selfAttrVer == entry.selfAttrMutationVersion &&
                cached.stateBits == stateBits
            ) {
                return cached.spec
            }
        }
        val spec = computeEffectiveVisualSpec(entry, owner, win32Class, isDark)
        if (hwndKey != null) {
            cachedEffectiveSpecsByHwnd[hwndKey] = CachedEffectiveSpec(
                selfAttrVer = entry.selfAttrMutationVersion,
                stateBits = stateBits,
                spec = spec
            )
        }
        return spec
    }

    private fun computeEffectiveVisualSpec(
        entry: Win32HwndHandle,
        owner: DesktopBaseNativeImpl<*>,
        win32Class: String,
        isDark: Boolean
    ): StyledVisualSpec {
        when (win32Class) {
            "Button" -> {
                if (owner is DesktopButtonNativeImpl<*>) {
                    return resolveBootstrapButtonVisualSpec(owner, isDark)
                }
            }
            "GenericUIBadgeW" -> {
                if (owner is DesktopTextNativeImpl<*>) {
                    return resolveBadgeVisualSpec(owner, isDark)
                }
            }
            "GenericUISemanticSurfaceW" -> {
                if (owner is DesktopContainerNativeImpl<*>) {
                    val semantic = owner.resolveSemanticSurfaceSpec(isDark)?.toStyledVisualSpec()
                    val styled = entry.attributes["styledVisuals"] as? StyledVisualSpec
                        ?: resolveStyledSpecFromOwner(owner)
                    if (semantic != null) {
                        return semantic.mergeWith(styled)
                    }
                }
            }
        }
        val precomputed = entry.attributes["styledVisuals"] as? StyledVisualSpec
        return precomputed ?: resolveStyledSpecFromOwner(owner)
    }

    /**
     * Builds a Bootstrap pill/button [StyledVisualSpec] for genuine `"Button"` (`WC_BUTTON`) `NM_CUSTOMDRAW` painting.
     */
    fun resolveBootstrapButtonVisualSpec(
        buttonImpl: DesktopButtonNativeImpl<*>,
        isDark: Boolean
    ): StyledVisualSpec {
        val type = buttonImpl.getType() ?: BootstrapType.NONE
        val outline = buttonImpl.isOutline()
        val enabled = buttonImpl.isEnabled()
        val hovered = buttonImpl.isHoveredState()
        val pressed = buttonImpl.isActivePressedState()
        val focused = buttonImpl.isFocusedState()
        val themeConfig = BootstrapColorPalette.activeThemeConfig

        val baseAccent = BootstrapColorPalette.resolveTintOrNull(type, isDark, themeConfig)
            ?: if (isDark) RgbaColor(58, 62, 70) else RgbaColor(233, 236, 239)

        val bgColor: RgbaColor
        val borderColor: RgbaColor?
        val fgColor: RgbaColor
        if (type == BootstrapType.LINK) {
            bgColor = if (pressed) {
                baseAccent.copy(a = (255 * 0.14f).roundToInt())
            } else if (hovered) {
                baseAccent.copy(a = (255 * 0.08f).roundToInt())
            } else {
                RgbaColor.TRANSPARENT
            }
            borderColor = null
            fgColor = baseAccent
        } else if (outline) {
            bgColor = when {
                !enabled -> RgbaColor.TRANSPARENT
                pressed -> baseAccent
                hovered -> baseAccent.copy(a = 230)
                else -> RgbaColor.TRANSPARENT
            }
            borderColor = baseAccent
            fgColor = if (hovered || pressed) {
                BootstrapColorPalette.resolveForegroundOn(type, isDark, themeConfig)
            } else {
                baseAccent
            }
        } else {
            bgColor = when {
                !enabled -> baseAccent.copy(a = 155)
                pressed -> darkenOrLighten(baseAccent, delta = -26)
                hovered -> darkenOrLighten(baseAccent, delta = -14)
                else -> baseAccent
            }
            borderColor = darkenOrLighten(bgColor, delta = -18).copy(a = bgColor.a)
            fgColor = if (type == BootstrapType.NONE) {
                if (isDark) RgbaColor(240, 240, 245) else RgbaColor(33, 37, 41)
            } else {
                BootstrapColorPalette.resolveForegroundOn(type, isDark, themeConfig)
            }
        }

        val styledOverride = StyledVisualSpec.fromComponentAndComputed(
            componentStyle = buttonImpl.getComponentStyle(),
            computedStyle = buttonImpl.getComputedStyle(),
            snapshot = null,
            darkTheme = isDark
        )
        return StyledVisualSpec(
            backgroundColor = bgColor,
            textColor = fgColor,
            borderColor = borderColor,
            borderWidthPx = if (borderColor != null) 1f else 0f,
            borderStyle = if (borderColor != null) CssBorderStyle.SOLID else CssBorderStyle.NONE,
            borderRadiusPx = 6f,
            focusRingColor = if (focused) baseAccent.copy(a = 128) else null,
            focusRingWidthPx = if (focused) 2f else null
        ).mergeWith(styledOverride)
    }

    /**
     * Builds a semantic Bootstrap pill badge [StyledVisualSpec] for `"GenericUIBadgeW"`.
     */
    fun resolveBadgeVisualSpec(
        textImpl: DesktopTextNativeImpl<*>,
        isDark: Boolean
    ): StyledVisualSpec {
        val type = textImpl.getBackgroundType() ?: textImpl.getType() ?: BootstrapType.SECONDARY
        val bg = BootstrapColorPalette.resolveTintOrNull(type, isDark)
            ?: if (isDark) RgbaColor(73, 80, 87) else RgbaColor(108, 117, 125)
        val fg = BootstrapColorPalette.resolveForegroundOn(type, isDark)
        val styledOverride = resolveStyledSpecFromOwner(textImpl)
        return StyledVisualSpec(
            backgroundColor = bg,
            textColor = fg,
            borderRadiusPx = 12f
        ).mergeWith(styledOverride)
    }

    /**
     * Renders a full [StyledVisualSpec] (backdrop blur + all 10 `backdrop-filter` ops, box shadow,
     * background gradient/color, rounded SDF clipping, borders, focus ring, optional ClearType text,
     * and all 10 foreground `filter` ops) into a double-buffered 32-bpp DIB and blits cleanly to [hdc].
     */
    internal data class ResolvedCornerRadii(
        val topLeft: Float,
        val topRight: Float,
        val bottomRight: Float,
        val bottomLeft: Float,
        val chromiumFilletEarPx: Float = 0f,
        val skipBottomBorder: Boolean = false
    ) {
        val isUniform: Boolean
            get() = abs(topLeft - topRight) <= 0.1f &&
                abs(topRight - bottomRight) <= 0.1f &&
                abs(bottomRight - bottomLeft) <= 0.1f &&
                chromiumFilletEarPx <= 0.1f

        val maxRadius: Float
            get() = max(max(topLeft, topRight), max(bottomRight, bottomLeft))
    }

    internal fun resolveCornerRadii(
        entry: Win32HwndHandle?,
        owner: DesktopBaseNativeImpl<*>?,
        spec: StyledVisualSpec,
        width: Int,
        height: Int
    ): ResolvedCornerRadii {
        val maxCap = (min(width, height) * 0.5f).coerceAtLeast(0f)
        val baseFallback = (spec.borderRadiusPx ?: 0f).coerceIn(0f, maxCap)
        if (owner == null) {
            return ResolvedCornerRadii(baseFallback, baseFallback, baseFallback, baseFallback)
        }
        val eff = owner.resolveEffectiveStyle()
        val activeState = when {
            !owner.isEnabled() -> eff?.disabledStyle
            owner.isActivePressedState() -> eff?.activeStyle
            owner.isFocusedState() -> eff?.focusStyle
            owner.isHoveredState() -> eff?.hoverStyle
            else -> null
        }
        val compR = owner.getComponentStyle()?.getBorderRadius()
        val refMin = min(width, height).toFloat().coerceAtLeast(1f)

        fun resolveCorner(explicitStatePx: Float?, stateRadiiLen: com.nexustools.genericui.style.css.CssLength?, effPx: Float?, effLen: com.nexustools.genericui.style.css.CssLength?, compLen: com.nexustools.genericui.style.css.CssLength?): Float? {
            if (explicitStatePx != null) return explicitStatePx.coerceIn(0f, maxCap)
            if (stateRadiiLen != null) {
                val px = if (stateRadiiLen.unit == CssUnit.PERCENT) stateRadiiLen.value * 0.01f * refMin else stateRadiiLen.resolvePx()
                if (px != null) return px.coerceIn(0f, maxCap)
            }
            if (effPx != null) return effPx.coerceIn(0f, maxCap)
            if (effLen != null) {
                val px = if (effLen.unit == CssUnit.PERCENT) effLen.value * 0.01f * refMin else effLen.resolvePx()
                if (px != null) return px.coerceIn(0f, maxCap)
            }
            if (compLen != null) {
                val px = if (compLen.unit == CssUnit.PERCENT) compLen.value * 0.01f * refMin else compLen.resolvePx()
                if (px != null) return px.coerceIn(0f, maxCap)
            }
            return null
        }

        val stateR = activeState?.borderRadius
        val effR = eff?.borderRadius
        var tl = resolveCorner(activeState?.borderRadiusTopLeftPx, stateR?.topLeft, eff?.borderRadiusTopLeftPx, effR?.topLeft, compR?.topLeft) ?: baseFallback
        var tr = resolveCorner(activeState?.borderRadiusTopRightPx, stateR?.topRight, eff?.borderRadiusTopRightPx, effR?.topRight, compR?.topRight) ?: baseFallback
        var br = resolveCorner(activeState?.borderRadiusBottomRightPx, stateR?.bottomRight, eff?.borderRadiusBottomRightPx, effR?.bottomRight, compR?.bottomRight) ?: baseFallback
        var bl = resolveCorner(activeState?.borderRadiusBottomLeftPx, stateR?.bottomLeft, eff?.borderRadiusBottomLeftPx, effR?.bottomLeft, compR?.bottomLeft) ?: baseFallback

        val isRawAllSame = abs(tl - tr) <= 0.1f && abs(tr - br) <= 0.1f && abs(br - bl) <= 0.1f
        if (isRawAllSame && spec.borderRadiusPx != null && abs(tl - baseFallback) > 0.1f) {
            tl = baseFallback
            tr = baseFallback
            br = baseFallback
            bl = baseFallback
        }

        val tokens = entry?.cachedAllClassTokens.orEmpty()
        val isChromiumTabFlag = ("gui-tab-item--active" in tokens && ("gui-tab-item--chromium" in tokens || "gui-tab-item" in tokens)) ||
            ("genericui-tabbed-chip" in tokens && "active" in tokens) ||
            entry?.attributes?.get("chromiumTabFillet") == true
        if (isChromiumTabFlag && "gui-tab-item--chromium" in tokens && tl <= 0.5f && tr <= 0.5f && br <= 0.5f && bl <= 0.5f) {
            tl = 8f.coerceAtMost(maxCap)
            tr = 8f.coerceAtMost(maxCap)
        }
        val topRoundedBottomSquare = (tl > 0.5f || tr > 0.5f) && br <= 0.5f && bl <= 0.5f
        val earPx = if (isChromiumTabFlag && topRoundedBottomSquare && width >= 28 && height >= 16) {
            val maxAllowedEar = ((width - tl - tr - 6f) * 0.5f).coerceAtLeast(0f)
            min(max(tl, tr), min(8f, min(height * 0.35f, maxAllowedEar))).coerceAtLeast(0f)
        } else {
            0f
        }
        return ResolvedCornerRadii(
            topLeft = tl,
            topRight = tr,
            bottomRight = br,
            bottomLeft = bl,
            chromiumFilletEarPx = earPx,
            skipBottomBorder = earPx > 0.5f
        )
    }

    internal fun renderStyledSpecToHdc(
        hwnd: HWND,
        hdc: HDC,
        width: Int,
        height: Int,
        spec: StyledVisualSpec,
        isDark: Boolean,
        focused: Boolean = false,
        fontHandle: HFONT? = null,
        text: String? = null,
        textColorOverride: RgbaColor? = null,
        alignLeft: Boolean = false,
        iconBuffer: ImageBuffer? = null,
        cornerRadii: ResolvedCornerRadii? = null,
        dirtyLeft: Int = 0,
        dirtyTop: Int = 0,
        dirtyRight: Int = width,
        dirtyBottom: Int = height
    ) {
        if (width <= 0 || height <= 0) return

        val resolvedRadii = cornerRadii ?: run {
            val u = (spec.borderRadiusPx ?: 0f).coerceIn(0f, min(width, height) * 0.5f)
            ResolvedCornerRadii(u, u, u, u)
        }

        val backdropFilter = spec.backdropFilter ?: if ((spec.backdropBlurPx ?: 0f) > 0f) {
            CssFilter(blur = com.nexustools.genericui.style.css.CssLength.px(spec.backdropBlurPx!!))
        } else {
            null
        }
        val hasBackdrop = backdropFilter != null && backdropFilter.hasActiveEffects

        if (resolvedRadii.isUniform &&
            tryRenderFastGdiStyledSpec(
                hwnd = hwnd,
                hdc = hdc,
                width = width,
                height = height,
                spec = spec,
                isDark = isDark,
                hasBackdrop = hasBackdrop,
                focused = focused,
                fontHandle = fontHandle,
                text = text,
                textColorOverride = textColorOverride,
                alignLeft = alignLeft,
                iconBuffer = iconBuffer,
                dirtyLeft = dirtyLeft,
                dirtyTop = dirtyTop,
                dirtyRight = dirtyRight,
                dirtyBottom = dirtyBottom
            )
        ) {
            return
        }

        val surfacePixels = obtainScratchSurfacePixels(width * height)
        val radiusPx = resolvedRadii.maxRadius.coerceIn(0f, min(width, height) * 0.5f)
        val bgColor = spec.backgroundColor
        val isTranslucentBg = (bgColor?.a ?: 0) in 1..254
        val isOpaqueFlatCard = !hasBackdrop &&
            resolvedRadii.isUniform &&
            bgColor != null && bgColor.a >= 255 &&
            radiusPx <= 0.5f &&
            spec.backgroundGradient == null &&
            (spec.boxShadowColor == null || (spec.boxShadowBlurPx ?: 0f) <= 0f)

        // 1. Capture live backdrop via PrintWindow ONLY for backdrop-filter or truly translucent overlays.
        //    Opaque rounded cards / gradients pre-fill from stage host (if present) or resolveAncestorBackgroundColor.
        if (hasBackdrop) {
            val pad = computeFilterPadPx(backdropFilter)
            val capW = width + pad * 2
            val capH = height + pad * 2
            val capPixels = obtainScratchCapturePixels(capW * capH)
            captureBackdropIntoBuffer(hwnd, -pad, -pad, capW, capH, capPixels, isDark)
            Win32Direct2dBackdropBlurEngine.applyBackdropToArgbArray(
                filter = backdropFilter!!,
                argbPixels = capPixels,
                bufferWidth = capW,
                bufferHeight = capH,
                regionX = pad,
                regionY = pad,
                regionWidth = width,
                regionHeight = height,
                cornerRadiusPx = radiusPx,
                opacity = 1f
            )
            for (py in 0 until height) {
                val srcOff = (py + pad) * capW + pad
                val dstOff = py * width
                for (px in 0 until width) {
                    surfacePixels[dstOff + px] = capPixels[srcOff + px] or 0xFF000000.toInt()
                }
            }
        } else if (isTranslucentBg) {
            captureBackdropIntoBuffer(hwnd, 0, 0, width, height, surfacePixels, isDark)
        } else if (!isOpaqueFlatCard) {
            val copiedStage = backdropCaptureDepth == 0 &&
                Win32LayeredStageHostHwnd.hasAnyStageHosts() &&
                Win32LayeredStageHostHwnd.tryCopyBackdropForChild(hwnd, 0, 0, width, height, surfacePixels)
            if (!copiedStage) {
                val fallbackBg = resolveAncestorBackgroundColor(hwnd, isDark)
                val fallbackWord = (0xFF shl 24) or (fallbackBg.r shl 16) or (fallbackBg.g shl 8) or fallbackBg.b
                surfacePixels.fill(fallbackWord, 0, width * height)
            }
        }

        // 2. Subtle outer box shadow inset / border allowance
        val shadowColor = spec.boxShadowColor
        val shadowBlur = (spec.boxShadowBlurPx ?: 0f).coerceAtLeast(0f)
        if (resolvedRadii.chromiumFilletEarPx <= 0.5f && shadowColor != null && shadowColor.a > 0 && shadowBlur > 0f) {
            drawSoftInnerOuterElevationRim(surfacePixels, width, height, radiusPx, shadowColor)
        }

        // 3. Background gradient or solid fill inside rounded SDF mask
        val gradient = spec.backgroundGradient
        if (gradient != null) {
            if (resolvedRadii.isUniform) {
                fillRoundedGradientArgb(surfacePixels, width, height, gradient, radiusPx)
            } else {
                fillPerCornerRoundedGradientArgb(surfacePixels, width, height, gradient, resolvedRadii)
            }
        } else if (bgColor != null && bgColor.a > 0) {
            if (resolvedRadii.isUniform) {
                fillRoundedSolidArgb(surfacePixels, width, height, bgColor, radiusPx)
            } else {
                fillPerCornerRoundedSolidArgb(surfacePixels, width, height, bgColor, resolvedRadii)
            }
        }

        // 4. Rounded SDF border
        val borderW = (spec.borderWidthPx ?: 0f).coerceAtLeast(0f)
        val borderColor = spec.borderColor
        if (borderW > 0f && borderColor != null && borderColor.a > 0 &&
            spec.borderStyle != CssBorderStyle.NONE && spec.borderStyle != CssBorderStyle.HIDDEN
        ) {
            if (resolvedRadii.isUniform) {
                strokeRoundedBorderArgb(
                    pixels = surfacePixels,
                    width = width,
                    height = height,
                    borderColor = borderColor,
                    borderWidthPx = borderW,
                    radiusPx = radiusPx,
                    style = spec.borderStyle ?: CssBorderStyle.SOLID
                )
            } else {
                strokePerCornerRoundedBorderArgb(
                    pixels = surfacePixels,
                    width = width,
                    height = height,
                    borderColor = borderColor,
                    borderWidthPx = borderW,
                    radii = resolvedRadii,
                    style = spec.borderStyle ?: CssBorderStyle.SOLID
                )
            }
        }

        // 5. Focus ring
        val ringW = (spec.focusRingWidthPx ?: 0f).coerceAtLeast(0f)
        val ringColor = spec.focusRingColor
        if (focused && ringW > 0f && ringColor != null && ringColor.a > 0) {
            if (resolvedRadii.isUniform) {
                strokeRoundedBorderArgb(
                    pixels = surfacePixels,
                    width = width,
                    height = height,
                    borderColor = ringColor,
                    borderWidthPx = ringW,
                    radiusPx = radiusPx,
                    style = CssBorderStyle.SOLID
                )
            } else {
                strokePerCornerRoundedBorderArgb(
                    pixels = surfacePixels,
                    width = width,
                    height = height,
                    borderColor = ringColor,
                    borderWidthPx = ringW,
                    radii = resolvedRadii,
                    style = CssBorderStyle.SOLID
                )
            }
        }

        // 5.5. Composite optional child ImageBuffer icon (pure SrcOver alpha blend, never opaque box)
        if (iconBuffer != null && iconBuffer.width > 0 && iconBuffer.height > 0 && iconBuffer.bytes.size >= iconBuffer.width * iconBuffer.height * 4) {
            compositeCenteredRgbaIcon(surfacePixels, width, height, iconBuffer)
        }

        // 6. Optional text overlay + foreground CSS filter (all 10 ops) + final BitBlt
        val dib = ensureSurfaceDib(hdc, width, height) ?: return
        val dibStride = reusableSurfaceW
        val dibDc = reusableSurfaceDc ?: return
        val total = width * height
        if (dibStride == width) {
            for (i in 0 until total) {
                val argb = surfacePixels[i]
                val a = (argb ushr 24) and 0xFF
                dib[i] = if (a >= 255) {
                    argb.toUInt()
                } else if (a <= 0) {
                    0x00000000u
                } else {
                    val r = (((argb ushr 16) and 0xFF) * a + 127) / 255
                    val g = (((argb ushr 8) and 0xFF) * a + 127) / 255
                    val b = ((argb and 0xFF) * a + 127) / 255
                    ((a shl 24) or (r shl 16) or (g shl 8) or b).toUInt()
                }
            }
        } else {
            for (py in 0 until height) {
                val srcRow = py * width
                val dstRow = py * dibStride
                for (px in 0 until width) {
                    val argb = surfacePixels[srcRow + px]
                    val a = (argb ushr 24) and 0xFF
                    dib[dstRow + px] = if (a >= 255) {
                        argb.toUInt()
                    } else if (a <= 0) {
                        0x00000000u
                    } else {
                        val r = (((argb ushr 16) and 0xFF) * a + 127) / 255
                        val g = (((argb ushr 8) and 0xFF) * a + 127) / 255
                        val b = ((argb and 0xFF) * a + 127) / 255
                        ((a shl 24) or (r shl 16) or (g shl 8) or b).toUInt()
                    }
                }
            }
        }

        if (!text.isNullOrBlank()) {
            val textCol = textColorOverride ?: spec.textColor
                ?: if (isDark) RgbaColor(242, 242, 245) else RgbaColor(20, 20, 24)
            drawTextIntoPremulSurfaceDib(
                dibDc = dibDc,
                pixelsPtr = dib,
                stride = dibStride,
                width = width,
                height = height,
                text = text,
                textColor = textCol,
                fontHandle = fontHandle,
                alignLeft = alignLeft
            )
        }

        val fgFilter = spec.filter
        if (fgFilter != null && fgFilter.hasActiveEffects) {
            GdiFlush()
            val argbForFilter = obtainScratchFilterPixels(total)
            for (py in 0 until height) {
                val srcRow = py * dibStride
                val dstRow = py * width
                for (px in 0 until width) {
                    val p = dib[srcRow + px].toInt()
                    val a = (p ushr 24) and 0xFF
                    argbForFilter[dstRow + px] = if (a == 255 || a == 0) {
                        p
                    } else {
                        val r = (((p ushr 16) and 0xFF) * 255 + a / 2) / a
                        val g = (((p ushr 8) and 0xFF) * 255 + a / 2) / a
                        val b = ((p and 0xFF) * 255 + a / 2) / a
                        (a shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
            }
            Win32Direct2dBackdropBlurEngine.applyBackdropToArgbArray(
                filter = fgFilter,
                argbPixels = argbForFilter,
                bufferWidth = width,
                bufferHeight = height,
                regionX = 0,
                regionY = 0,
                regionWidth = width,
                regionHeight = height,
                cornerRadiusPx = radiusPx,
                opacity = 1f
            )
            for (py in 0 until height) {
                val srcRow = py * width
                val dstRow = py * dibStride
                for (px in 0 until width) {
                    val argb = argbForFilter[srcRow + px]
                    val a = (argb ushr 24) and 0xFF
                    dib[dstRow + px] = if (a >= 255) {
                        argb.toUInt()
                    } else if (a <= 0) {
                        0u
                    } else {
                        val r = (((argb ushr 16) and 0xFF) * a + 127) / 255
                        val g = (((argb ushr 8) and 0xFF) * a + 127) / 255
                        val b = ((argb and 0xFF) * a + 127) / 255
                        ((a shl 24) or (r shl 16) or (g shl 8) or b).toUInt()
                    }
                }
            }
        }

        GdiFlush()
        BitBlt(hdc, 0, 0, width, height, dibDc, 0, 0, SRCCOPY)
    }

    /**
     * Native Win32 GDI fast path for opaque/transparent solid-background and rounded cards/containers
     * outside video stage hosts, avoiding software pixel-buffer allocation and DIB copies.
     */
    private fun tryRenderFastGdiStyledSpec(
        hwnd: HWND,
        hdc: HDC,
        width: Int,
        height: Int,
        spec: StyledVisualSpec,
        isDark: Boolean,
        hasBackdrop: Boolean,
        focused: Boolean,
        fontHandle: HFONT? = null,
        text: String? = null,
        textColorOverride: RgbaColor? = null,
        alignLeft: Boolean = false,
        iconBuffer: ImageBuffer? = null,
        dirtyLeft: Int = 0,
        dirtyTop: Int = 0,
        dirtyRight: Int = width,
        dirtyBottom: Int = height
    ): Boolean {
        if (hasBackdrop) return false
        if (spec.backgroundGradient != null) return false
        if (spec.filter?.hasActiveEffects == true) return false
        if (iconBuffer != null) return false
        if (focused) return false

        val dl = dirtyLeft.coerceIn(0, width)
        val dt = dirtyTop.coerceIn(0, height)
        val dr = dirtyRight.coerceIn(dl, width)
        val db = dirtyBottom.coerceIn(dt, height)
        if (dr <= dl || db <= dt) return true

        val hasText = !text.isNullOrBlank()
        val resolvedTextCol = if (hasText) {
            (textColorOverride ?: spec.textColor ?: if (isDark) RgbaColor(242, 242, 245) else RgbaColor(20, 20, 24)).also {
                if (it.a < 255) return false
            }
        } else {
            null
        }

        val bgColor = spec.backgroundColor
        val bgOpaque = bgColor != null && bgColor.a >= 255
        val bgTransparent = bgColor == null || bgColor.a <= 0
        if (!bgOpaque && !bgTransparent) return false

        val shadowColor = spec.boxShadowColor
        val shadowBlur = (spec.boxShadowBlurPx ?: 0f).coerceAtLeast(0f)
        val hasBlockingShadow = shadowColor != null &&
            shadowColor.a > 0 &&
            shadowBlur > 0f &&
            !(bgOpaque && shadowBlur <= 4.5f && shadowColor.a <= 80)
        if (hasBlockingShadow) return false

        val borderW = (spec.borderWidthPx ?: 0f).coerceAtLeast(0f)
        val borderColor = spec.borderColor
        val hasVisibleBorder = borderW > 0f &&
            borderColor != null &&
            borderColor.a > 0 &&
            spec.borderStyle != CssBorderStyle.NONE &&
            spec.borderStyle != CssBorderStyle.HIDDEN
        if (hasVisibleBorder && spec.borderStyle != null && spec.borderStyle != CssBorderStyle.SOLID) {
            return false
        }

        if (isInTransparentDwmTitleBar(hwnd, isDark)) {
            return false
        }

        if (Win32LayeredStageHostHwnd.hasAnyStageHosts() && Win32LayeredStageHostHwnd.isDescendantOfStageHost(hwnd)) {
            return false
        }

        val radiusPx = (spec.borderRadiusPx ?: 0f).coerceIn(0f, min(width, height) * 0.5f)
        val cornerSpan = min(ceil(radiusPx).toInt() + 2, (min(width, height) + 1) / 2).coerceAtLeast(1)
        val inStraightVerticalMiddle = radiusPx > 0.5f &&
            !hasText &&
            dt >= cornerSpan &&
            db <= height - cornerSpan

        val canUseRectPath = radiusPx <= 0.5f || (bgTransparent && !hasVisibleBorder) || (bgOpaque && inStraightVerticalMiddle)
        if (hasVisibleBorder && borderColor!!.a < 255) {
            if (!(radiusPx <= 0.5f || (bgOpaque && inStraightVerticalMiddle))) {
                return false
            }
        }

        val fillColor = if (bgOpaque) bgColor!! else resolveAncestorBackgroundColor(hwnd, isDark)
        val effectiveBorderRef: UInt? = if (hasVisibleBorder) {
            val bc = borderColor!!
            if (bc.a >= 255) {
                Win32DwmUxThemeBridge.colorRefRgb(bc.r, bc.g, bc.b)
            } else if (fillColor.a >= 255) {
                val a = bc.a
                val invA = 255 - a
                val br = ((bc.r * a + fillColor.r * invA + 127) / 255).coerceIn(0, 255)
                val bg = ((bc.g * a + fillColor.g * invA + 127) / 255).coerceIn(0, 255)
                val bb = ((bc.b * a + fillColor.b * invA + 127) / 255).coerceIn(0, 255)
                Win32DwmUxThemeBridge.colorRefRgb(br, bg, bb)
            } else {
                return false
            }
        } else {
            null
        }
        val fillBrush = obtainSolidBrush(fillColor) ?: return false

        if (canUseRectPath) {
            val borderBrush = if (effectiveBorderRef != null) obtainSolidBrushRef(effectiveBorderRef) ?: return false else null
            memScoped {
                val rc = alloc<RECT>()
                if (borderBrush == null) {
                    rc.left = dl
                    rc.top = dt
                    rc.right = dr
                    rc.bottom = db
                    FillRect(hdc, rc.ptr, fillBrush)
                } else {
                    val bw = borderW.roundToInt().coerceIn(1, max(1, min(width, height) / 2))
                    val intL = max(dl, bw)
                    val intT = if (inStraightVerticalMiddle) dt else max(dt, bw)
                    val intR = min(dr, width - bw)
                    val intB = if (inStraightVerticalMiddle) db else min(db, height - bw)
                    if (intR > intL && intB > intT) {
                        rc.left = intL
                        rc.top = intT
                        rc.right = intR
                        rc.bottom = intB
                        FillRect(hdc, rc.ptr, fillBrush)
                    }
                    if (!inStraightVerticalMiddle) {
                        // Top border strip
                        if (dt < bw) {
                            rc.left = dl
                            rc.top = dt
                            rc.right = dr
                            rc.bottom = min(db, bw)
                            FillRect(hdc, rc.ptr, borderBrush)
                        }
                        // Bottom border strip
                        if (height > bw && db > height - bw) {
                            rc.left = dl
                            rc.top = max(dt, height - bw)
                            rc.right = dr
                            rc.bottom = db
                            FillRect(hdc, rc.ptr, borderBrush)
                        }
                    }
                    // Left & right border strips
                    val midTop = if (inStraightVerticalMiddle) dt else max(dt, bw)
                    val midBottom = if (inStraightVerticalMiddle) db else min(db, height - bw).coerceAtLeast(midTop)
                    if (midBottom > midTop) {
                        if (dl < bw) {
                            rc.left = dl
                            rc.top = midTop
                            rc.right = min(dr, bw)
                            rc.bottom = midBottom
                            FillRect(hdc, rc.ptr, borderBrush)
                        }
                        if (width > bw && dr > width - bw) {
                            rc.left = max(dl, width - bw)
                            rc.top = midTop
                            rc.right = dr
                            rc.bottom = midBottom
                            FillRect(hdc, rc.ptr, borderBrush)
                        }
                    }
                }
            }
        } else {
            val cornerBrush = if (bgOpaque) {
                obtainSolidBrush(resolveAncestorBackgroundColor(hwnd, isDark)) ?: return false
            } else {
                fillBrush
            }
            val bw = if (hasVisibleBorder) {
                borderW.roundToInt().coerceIn(1, max(1, min(width, height) / 2))
            } else {
                1
            }
            val penColor = if (hasVisibleBorder) borderColor!! else fillColor
            val pen = obtainSolidPen(bw, penColor, insideFrame = hasVisibleBorder && bw > 1) ?: return false
            val diam = (radiusPx * 2f).roundToInt().coerceIn(2, min(width, height).coerceAtLeast(2))

            memScoped {
                val rc = alloc<RECT>()
                // Top-left corner cutout
                rc.left = 0
                rc.top = 0
                rc.right = cornerSpan
                rc.bottom = cornerSpan
                FillRect(hdc, rc.ptr, cornerBrush)
                // Top-right corner cutout
                rc.left = (width - cornerSpan).coerceAtLeast(0)
                rc.top = 0
                rc.right = width
                rc.bottom = cornerSpan
                FillRect(hdc, rc.ptr, cornerBrush)
                // Bottom-left corner cutout
                rc.left = 0
                rc.top = (height - cornerSpan).coerceAtLeast(0)
                rc.right = cornerSpan
                rc.bottom = height
                FillRect(hdc, rc.ptr, cornerBrush)
                // Bottom-right corner cutout
                rc.left = (width - cornerSpan).coerceAtLeast(0)
                rc.top = (height - cornerSpan).coerceAtLeast(0)
                rc.right = width
                rc.bottom = height
                FillRect(hdc, rc.ptr, cornerBrush)
            }

            val oldBrush = SelectObject(hdc, fillBrush)
            val oldPen = SelectObject(hdc, pen)
            try {
                RoundRect(hdc, 0, 0, width, height, diam, diam)
            } finally {
                if (oldPen != null) SelectObject(hdc, oldPen)
                if (oldBrush != null) SelectObject(hdc, oldBrush)
            }
        }

        if (hasText) {
            val textCol = resolvedTextCol!!
            val oldFont = if (fontHandle != null) SelectObject(hdc, fontHandle) else null
            val prevBk = SetBkMode(hdc, TRANSPARENT)
            val prevTextCol = SetTextColor(hdc, Win32DwmUxThemeBridge.colorRefRgb(textCol.r, textCol.g, textCol.b))
            try {
                memScoped {
                    val textRc = alloc<RECT>()
                    val padX = if (alignLeft) 8 else 4
                    textRc.left = padX
                    textRc.top = 0
                    textRc.right = (width - padX).coerceAtLeast(padX)
                    textRc.bottom = height
                    val alignFlag = if (alignLeft) DT_LEFT else DT_CENTER
                    val flags = (DT_SINGLELINE or DT_VCENTER or alignFlag or DT_END_ELLIPSIS or DT_NOPREFIX).toUInt()
                    DrawTextW(hdc, text!!, -1, textRc.ptr, flags)
                }
            } finally {
                if (prevTextCol != 0xFFFFFFFFu) SetTextColor(hdc, prevTextCol)
                if (prevBk != 0) SetBkMode(hdc, prevBk)
                if (oldFont != null) SelectObject(hdc, oldFont)
            }
        }
        return true
    }

    /**
     * Blits a premultiplied/straight ARGB array cleanly to [hdc] using our shared double-buffered DIB.
     */
    fun blitArgbArrayToHdc(
        hdc: HDC,
        width: Int,
        height: Int,
        argbPixels: IntArray,
        premultiplied: Boolean = false
    ) {
        if (width <= 0 || height <= 0 || argbPixels.size < width * height) return
        val dib = ensureSurfaceDib(hdc, width, height) ?: return
        val dibStride = reusableSurfaceW
        val dibDc = reusableSurfaceDc ?: return
        if (dibStride == width) {
            val count = width * height
            if (premultiplied) {
                for (i in 0 until count) {
                    dib[i] = argbPixels[i].toUInt()
                }
            } else {
                for (i in 0 until count) {
                    val argb = argbPixels[i]
                    val a = (argb ushr 24) and 0xFF
                    dib[i] = when {
                        a >= 255 -> argb.toUInt()
                        a <= 0 -> 0u
                        else -> {
                            val r = (((argb ushr 16) and 0xFF) * a + 127) / 255
                            val g = (((argb ushr 8) and 0xFF) * a + 127) / 255
                            val b = ((argb and 0xFF) * a + 127) / 255
                            ((a shl 24) or (r shl 16) or (g shl 8) or b).toUInt()
                        }
                    }
                }
            }
        } else {
            for (py in 0 until height) {
                val srcRow = py * width
                val dstRow = py * dibStride
                if (premultiplied) {
                    for (px in 0 until width) {
                        dib[dstRow + px] = argbPixels[srcRow + px].toUInt()
                    }
                } else {
                    for (px in 0 until width) {
                        val argb = argbPixels[srcRow + px]
                        val a = (argb ushr 24) and 0xFF
                        dib[dstRow + px] = when {
                            a >= 255 -> argb.toUInt()
                            a <= 0 -> 0u
                            else -> {
                                val r = (((argb ushr 16) and 0xFF) * a + 127) / 255
                                val g = (((argb ushr 8) and 0xFF) * a + 127) / 255
                                val b = ((argb and 0xFF) * a + 127) / 255
                                ((a shl 24) or (r shl 16) or (g shl 8) or b).toUInt()
                            }
                        }
                    }
                }
            }
        }
        GdiFlush()
        BitBlt(hdc, 0, 0, width, height, dibDc, 0, 0, SRCCOPY)
    }

    /**
     * Captures the real backdrop behind [hwnd] at local region `[relX .. relX + capW, relY .. relY + capH]`:
     * 1. Fast path if hosted under [Win32LayeredStageHostHwnd] (samples live `zIndex = 0` stage pixels).
     * 2. Otherwise initializes from ancestor background color and composites any visible lower-Z
     *    sibling `HWND`s in back-to-front order via `PrintWindow(..., PW_RENDERFULLCONTENT)`.
     */
    fun captureBackdropIntoBuffer(
        hwnd: HWND,
        relX: Int,
        relY: Int,
        capW: Int,
        capH: Int,
        dstArgb: IntArray,
        isDark: Boolean
    ) {
        if (capW <= 0 || capH <= 0 || dstArgb.size < capW * capH) return

        // 1. Check LayeredContainer stage host fast-path first (zero recursion, real stage pixels)
        if (Win32LayeredStageHostHwnd.tryCopyBackdropForChild(hwnd, relX, relY, capW, capH, dstArgb)) {
            return
        }

        val fallbackBg = resolveAncestorBackgroundColor(hwnd, isDark)
        val fallbackWord = (0xFF shl 24) or (fallbackBg.r shl 16) or (fallbackBg.g shl 8) or fallbackBg.b
        dstArgb.fill(fallbackWord, 0, capW * capH)

        if (backdropCaptureDepth > 0) return
        val parent = GetParent(hwnd) ?: return

        backdropCaptureDepth++
        try {
            val capOriginParent = memScoped {
                val pt = alloc<POINT>()
                pt.x = relX
                pt.y = relY
                MapWindowPoints(hwnd, parent, pt.ptr, 1u)
                pt.x to pt.y
            }
            val capLeft = capOriginParent.first
            val capTop = capOriginParent.second
            val capRight = capLeft + capW
            val capBottom = capTop + capH

            // Collect siblings below hwnd in Z-order (GW_HWNDNEXT walks top->bottom in Win32 Z-order)
            val behindSiblings = ArrayList<HWND>()
            var cur = GetWindow(hwnd, GW_HWNDNEXT.toUInt())
            while (cur != null) {
                if (IsWindowVisible(cur) != 0) {
                    behindSiblings.add(cur)
                }
                cur = GetWindow(cur, GW_HWNDNEXT.toUInt())
            }
            if (behindSiblings.isEmpty()) return

            for (idx in behindSiblings.indices.reversed()) {
                val sib = behindSiblings[idx]
                val sibBox = memScoped {
                    val wr = alloc<RECT>()
                    GetWindowRect(sib, wr.ptr)
                    val pts = alloc<POINT>()
                    pts.x = wr.left
                    pts.y = wr.top
                    MapWindowPoints(null, parent, pts.ptr, 1u)
                    val sw = (wr.right - wr.left).coerceAtLeast(0)
                    val sh = (wr.bottom - wr.top).coerceAtLeast(0)
                    intArrayOf(pts.x, pts.y, pts.x + sw, pts.y + sh, sw, sh)
                }
                val sL = sibBox[0]
                val sT = sibBox[1]
                val sR = sibBox[2]
                val sB = sibBox[3]
                val sW = sibBox[4]
                val sH = sibBox[5]
                if (sW <= 0 || sH <= 0) continue
                val iL = max(capLeft, sL)
                val iT = max(capTop, sT)
                val iR = min(capRight, sR)
                val iB = min(capBottom, sB)
                if (iR <= iL || iB <= iT) continue

                val sibEntry = Win32ControlMessageRouter.resolveEntry(sib)
                if (sibEntry?.actualWin32ClassName == "GenericUIContainerW") {
                    val sibOwner = sibEntry.ownerImpl
                    val sibSpec = if (sibOwner != null) resolveStyledSpecFromOwner(sibOwner) else null
                    if (sibSpec != null && !sibSpec.hasCustomBackground && !sibSpec.hasBorder) {
                        continue
                    }
                }

                val sibPixels = captureWindowPixelsIntoScratch(sib, sW, sH) ?: continue
                val sibStride = scratchSiblingW
                for (py in iT until iB) {
                    val srcRow = (py - sT) * sibStride
                    val dstRow = (py - capTop) * capW
                    for (px in iL until iR) {
                        val src = sibPixels[srcRow + (px - sL)].toInt()
                        val dstIdx = dstRow + (px - capLeft)
                        val sa = (src ushr 24) and 0xFF
                        if (sa >= 255) {
                            dstArgb[dstIdx] = src
                        } else if (sa > 0) {
                            // Scratch DIB from PrintWindow uses RGB directly (treat as opaque when alpha=0 or blend)
                            dstArgb[dstIdx] = blendPremulWordOver(src, dstArgb[dstIdx])
                        }
                    }
                }
            }
        } finally {
            backdropCaptureDepth--
        }
    }

    private fun compositeCenteredRgbaIcon(
        dstArgb: IntArray,
        dstW: Int,
        dstH: Int,
        icon: ImageBuffer
    ) {
        val iw = icon.width
        val ih = icon.height
        val bytes = icon.bytes
        val ox = (dstW - iw) / 2
        val oy = (dstH - ih) / 2
        for (sy in 0 until ih) {
            val dy = oy + sy
            if (dy !in 0 until dstH) continue
            val dstRow = dy * dstW
            val srcRow = sy * iw
            for (sx in 0 until iw) {
                val dx = ox + sx
                if (dx !in 0 until dstW) continue
                val off = (srcRow + sx) * 4
                val sr = bytes[off].toInt() and 0xFF
                val sg = bytes[off + 1].toInt() and 0xFF
                val sb = bytes[off + 2].toInt() and 0xFF
                val sa = bytes[off + 3].toInt() and 0xFF
                if (sa > 0) {
                    val idx = dstRow + dx
                    dstArgb[idx] = if (sa >= 255) {
                        (0xFF shl 24) or (sr shl 16) or (sg shl 8) or sb
                    } else {
                        blendStraightOverArgb(sr, sg, sb, sa, dstArgb[idx])
                    }
                }
            }
        }
    }

    internal fun paintStageOverlayTextBackdrop(
        hwnd: HWND,
        hdc: HDC,
        width: Int,
        height: Int,
        isDark: Boolean
    ): Boolean {
        if (width <= 0 || height <= 0) return false
        val total = width * height
        val pixels = obtainScratchStagePixels(total)
        if (!Win32LayeredStageHostHwnd.tryCopyBackdropForChild(hwnd, 0, 0, width, height, pixels)) {
            return false
        }
        val ancestorBgs = ArrayList<RgbaColor>(4)
        var cur: HWND? = GetParent(hwnd)
        var hops = 0
        while (cur != null && hops < 16) {
            val entry = Win32ControlMessageRouter.resolveEntry(cur)
            if (entry?.actualWin32ClassName == "GenericUILayeredStageHostW" ||
                Win32LayeredStageHostHwnd.isStageHostHwnd(cur)
            ) {
                break
            }
            val bg = entry?.ownerImpl?.let { resolveStyledSpecFromOwner(it).backgroundColor }
            if (bg != null && bg.a > 0) {
                ancestorBgs.add(bg)
                if (bg.a >= 255) break
            }
            cur = GetParent(cur)
            hops++
        }
        for (idx in ancestorBgs.indices.reversed()) {
            val bg = ancestorBgs[idx]
            if (bg.a >= 255) {
                val solidWord = (0xFF shl 24) or (bg.r shl 16) or (bg.g shl 8) or bg.b
                pixels.fill(solidWord, 0, total)
            } else {
                for (i in 0 until total) {
                    pixels[i] = blendStraightOverArgb(bg.r, bg.g, bg.b, bg.a, pixels[i])
                }
            }
        }
        blitArgbArrayToHdc(hdc, width, height, pixels, premultiplied = true)
        return true
    }

    fun fillSolidParentBackdrop(
        hwnd: HWND,
        hdc: HDC,
        width: Int,
        height: Int,
        isDark: Boolean,
        dirtyLeft: Int = 0,
        dirtyTop: Int = 0,
        dirtyRight: Int = width,
        dirtyBottom: Int = height
    ) {
        if (width <= 0 || height <= 0) return
        val dl = dirtyLeft.coerceIn(0, width)
        val dt = dirtyTop.coerceIn(0, height)
        val dr = dirtyRight.coerceIn(dl, width)
        val db = dirtyBottom.coerceIn(dt, height)
        if (dr <= dl || db <= dt) return
        if (backdropCaptureDepth == 0 && Win32LayeredStageHostHwnd.hasAnyStageHosts()) {
            val stagePixels = obtainScratchStagePixels(width * height)
            if (Win32LayeredStageHostHwnd.tryCopyBackdropForChild(hwnd, 0, 0, width, height, stagePixels)) {
                blitArgbArrayToHdc(hdc, width, height, stagePixels, premultiplied = true)
                return
            }
        }
        val inTransparentDwm = isInTransparentDwmTitleBar(hwnd, isDark)
        val bg = resolveAncestorBackgroundColor(hwnd, isDark)
        val brush = if (!inTransparentDwm) obtainSolidBrush(bg) else null
        if (brush != null) {
            memScoped {
                val rc = alloc<RECT>()
                rc.left = dl
                rc.top = dt
                rc.right = dr
                rc.bottom = db
                FillRect(hdc, rc.ptr, brush)
            }
            return
        }
        // In transparent DWM titlebars (Vista/7 Aero glass / Win8), avoid GDI FillRect (alpha=0)
        // and write explicit alpha via 32-bpp DIB so DWM doesn't double-expose child backgrounds.
        val word = ((0xFF shl 24) or (bg.r shl 16) or (bg.g shl 8) or bg.b).toUInt()
        val dib = ensureSurfaceDib(hdc, width, height) ?: return
        val dibStride = reusableSurfaceW
        val dibDc = reusableSurfaceDc ?: return
        for (py in dt until db) {
            val rowOff = py * dibStride
            for (px in dl until dr) {
                dib[rowOff + px] = word
            }
        }
        GdiFlush()
        BitBlt(hdc, dl, dt, dr - dl, db - dt, dibDc, dl, dt, SRCCOPY)
    }

    internal fun resolveEffectiveEditBackdropColor(
        hwnd: HWND,
        spec: StyledVisualSpec,
        isDark: Boolean
    ): RgbaColor {
        val parentBg = resolveAncestorBackgroundColor(hwnd, isDark)
        val gradient = spec.backgroundGradient
        val sampleColor: RgbaColor? = when (gradient) {
            is LinearGradient -> {
                val norm = GradientStopNormalizer.normalizeLinear(gradient, 100f, 32f)
                sampleStops(norm.positions, norm.colors, 0.5f)
            }
            is RadialGradient -> {
                val norm = GradientStopNormalizer.normalizeRadial(gradient, 100f, 32f)
                sampleStops(norm.positions, norm.colors, 0.5f)
            }
            else -> spec.backgroundColor
        }
        val fg = sampleColor ?: return if (isDark) RgbaColor(34, 34, 40) else RgbaColor(255, 255, 255)
        if (fg.a >= 255) return fg
        if (fg.a <= 0) return parentBg
        val invA = 255 - fg.a
        return RgbaColor(
            r = ((fg.r * fg.a + parentBg.r * invA + 127) / 255).coerceIn(0, 255),
            g = ((fg.g * fg.a + parentBg.g * invA + 127) / 255).coerceIn(0, 255),
            b = ((fg.b * fg.a + parentBg.b * invA + 127) / 255).coerceIn(0, 255),
            a = 255
        )
    }

    internal fun isInTransparentDwmTitleBar(hwnd: HWND, isDark: Boolean): Boolean {
        val gen = Win32DwmUxThemeBridge.detectVisualStyleGeneration()
        if (gen != Win32VisualStyleGeneration.VISTA_7_AERO_GLASS &&
            gen != Win32VisualStyleGeneration.WIN8_AERO
        ) {
            return false
        }
        val ver = DesktopNativeWidget.globalNonOverlayMutationVersion
        if (cachedAncestorBgMutationVer != ver || cachedAncestorBgIsDark != isDark) {
            cachedAncestorBgByHwnd.clear()
            cachedInTransparentDwmByHwnd.clear()
            cachedAncestorBgMutationVer = ver
            cachedAncestorBgIsDark = isDark
        }
        val key = hwnd.toLong()
        cachedInTransparentDwmByHwnd[key]?.let { return it }

        var cur: HWND? = GetParent(hwnd)
        var inTransparent = false
        while (cur != null) {
            val nextParent = GetParent(cur)
            if (nextParent == null) {
                val hostWin = Win32NativeWindowHost.resolveWindowForHwnd(cur)
                if (hostWin != null && Win32NativeWindowHost.hasCustomTitleBarForWindow(hostWin, cur)) {
                    val tbH = Win32NativeWindowHost.getMainWindowTitleBarHeight(hostWin)
                    val relTop = memScoped {
                        val pt = alloc<POINT>()
                        pt.x = 0
                        pt.y = 0
                        MapWindowPoints(hwnd, cur, pt.ptr, 1u)
                        pt.y
                    }
                    if (relTop in 0 until tbH) {
                        val pal = Win32DwmUxThemeBridge.resolveTitleBarPalette(
                            hwnd = cur,
                            isToolWindow = hostWin.isToolModalWindow,
                            kind = hostWin.titleBarKind,
                            isDark = isDark
                        )
                        inTransparent = pal.useTransparentDwmBackdrop
                    }
                }
            }
            cur = nextParent
        }
        cachedInTransparentDwmByHwnd[key] = inTransparent
        return inTransparent
    }

    internal fun resolveAncestorBackgroundColor(hwnd: HWND, isDark: Boolean): RgbaColor {
        val ver = DesktopNativeWidget.globalNonOverlayMutationVersion
        if (cachedAncestorBgMutationVer != ver || cachedAncestorBgIsDark != isDark) {
            cachedAncestorBgByHwnd.clear()
            cachedInTransparentDwmByHwnd.clear()
            cachedAncestorBgMutationVer = ver
            cachedAncestorBgIsDark = isDark
        }
        val key = hwnd.toLong()
        cachedAncestorBgByHwnd[key]?.let { return it }

        var cur: HWND? = GetParent(hwnd)
        while (cur != null) {
            val entry = Win32ControlMessageRouter.resolveEntry(cur)
            val owner = entry?.ownerImpl
            if (owner != null) {
                val bg = owner.getEffectiveBackgroundColor()
                if (bg != null && bg.a >= 250) {
                    cachedAncestorBgByHwnd[key] = bg
                    return bg
                }
            }
            val nextParent = GetParent(cur)
            if (nextParent == null) {
                val hostWin = Win32NativeWindowHost.resolveWindowForHwnd(cur)
                if (hostWin != null && Win32NativeWindowHost.hasCustomTitleBarForWindow(hostWin, cur)) {
                    val tbH = Win32NativeWindowHost.getMainWindowTitleBarHeight(hostWin)
                    val relTop = memScoped {
                        val pt = alloc<POINT>()
                        pt.x = 0
                        pt.y = 0
                        MapWindowPoints(hwnd, cur, pt.ptr, 1u)
                        pt.y
                    }
                    if (relTop in 0 until tbH) {
                        val pal = Win32DwmUxThemeBridge.resolveTitleBarPalette(
                            hwnd = cur,
                            isToolWindow = hostWin.isToolModalWindow,
                            kind = hostWin.titleBarKind,
                            isDark = isDark
                        )
                        if (!pal.useTransparentDwmBackdrop) {
                            val cr = pal.captionBgColor.toInt()
                            val tbBg = RgbaColor(cr and 0xFF, (cr ushr 8) and 0xFF, (cr ushr 16) and 0xFF, 255)
                            cachedAncestorBgByHwnd[key] = tbBg
                            return tbBg
                        }
                    }
                }
            }
            cur = nextParent
        }
        val fallback = if (isDark) RgbaColor(24, 24, 28) else RgbaColor(246, 246, 248)
        cachedAncestorBgByHwnd[key] = fallback
        return fallback
    }

    internal fun resolveStyledSpecFromOwner(owner: DesktopBaseNativeImpl<*>): StyledVisualSpec {
        val eff = owner.resolveEffectiveStyle()
        return StyledVisualSpec(
            backgroundColor = owner.getEffectiveBackgroundColor(),
            backgroundGradient = owner.getEffectiveBackgroundGradient(),
            textColor = owner.getEffectiveTextColor(),
            borderColor = eff?.borderColor,
            borderWidthPx = eff?.borderWidthPx,
            borderRadiusPx = eff?.borderRadiusPx,
            opacity = eff?.opacity,
            focusRingColor = eff?.focusRingColor,
            focusRingWidthPx = eff?.focusRingWidthPx,
            backdropFilter = eff?.backdropFilter,
            filter = eff?.filter
        )
    }

    private fun resolveTextColorForSpec(
        entry: Win32HwndHandle,
        owner: DesktopBaseNativeImpl<*>,
        spec: StyledVisualSpec,
        resolvedClass: String,
        isDark: Boolean
    ): RgbaColor? {
        if (spec.textColor != null) return spec.textColor
        if (resolvedClass == "Button" && owner is DesktopButtonNativeImpl<*>) {
            val t = owner.getType() ?: BootstrapType.NONE
            return BootstrapColorPalette.resolveForegroundOn(t, isDark)
        }
        return owner.getEffectiveTextColor()
    }

    private fun buildButtonLabel(button: DesktopButtonNativeImpl<*>): String {
        val text = button.getText()
        val icon = button.getIcon()
        return when {
            !icon.isNullOrBlank() && text.isBlank() -> icon
            !icon.isNullOrBlank() && icon.length <= 2 -> "$icon $text"
            else -> text
        }
    }

    private fun drawTextIntoPremulSurfaceDib(
        dibDc: HDC,
        pixelsPtr: CPointer<UIntVar>,
        stride: Int,
        width: Int,
        height: Int,
        text: String,
        textColor: RgbaColor,
        fontHandle: HFONT?,
        alignLeft: Boolean
    ) {
        val count = width * height
        // Snapshot current pixels before GDI DrawTextW writes opaque RGB values with alpha=0
        val beforeWords = obtainScratchTextBeforeWords(count)
        for (py in 0 until height) {
            val dibRow = py * stride
            val bufRow = py * width
            for (px in 0 until width) {
                val p = pixelsPtr[dibRow + px].toInt()
                beforeWords[bufRow + px] = p
                // Ensure alpha byte sentinel = 0xFF temporarily so we detect GDI rasterized texels cleanly
                pixelsPtr[dibRow + px] = (p.toUInt() and 0x00FFFFFFu) or 0xFF000000u
            }
        }

        val oldFont = if (fontHandle != null) SelectObject(dibDc, fontHandle) else null
        val prevBk = SetBkMode(dibDc, TRANSPARENT)
        val colorRef = (textColor.r or (textColor.g shl 8) or (textColor.b shl 16)).toUInt()
        SetTextColor(dibDc, colorRef)
        memScoped {
            val rc = alloc<RECT>()
            rc.left = if (alignLeft) 8 else 4
            rc.top = 0
            rc.right = (width - if (alignLeft) 8 else 4).coerceAtLeast(rc.left + 1)
            rc.bottom = height
            val alignFlag = if (alignLeft) DT_LEFT else DT_CENTER
            val flags = (DT_SINGLELINE or DT_VCENTER or alignFlag or DT_END_ELLIPSIS or DT_NOPREFIX).toUInt()
            DrawTextW(dibDc, text, -1, rc.ptr, flags)
        }
        if (prevBk != 0) SetBkMode(dibDc, prevBk)
        if (oldFont != null) SelectObject(dibDc, oldFont)
        GdiFlush()

        for (py in 0 until height) {
            val dibRow = py * stride
            val bufRow = py * width
            for (px in 0 until width) {
                val drawn = pixelsPtr[dibRow + px].toInt()
                val gdiAlphaByte = (drawn ushr 24) and 0xFF
                val orig = beforeWords[bufRow + px]
                val origA = (orig ushr 24) and 0xFF
                if (gdiAlphaByte == 0xFF) {
                    // Pixel untouched by GDI DrawTextW -> restore exact premul RGBA
                    pixelsPtr[dibRow + px] = orig.toUInt()
                } else {
                    // GDI cleared alpha byte on touched texels -> extract final premul RGB safely
                    val dr = (drawn ushr 16) and 0xFF
                    val dg = (drawn ushr 8) and 0xFF
                    val db = drawn and 0xFF
                    val outA = max(origA, textColor.a).coerceIn(0, 255)
                    pixelsPtr[dibRow + px] = ((outA shl 24) or (dr shl 16) or (dg shl 8) or db).toUInt()
                }
            }
        }
    }

    private fun fillRoundedSolidArgb(
        pixels: IntArray,
        w: Int,
        h: Int,
        color: RgbaColor,
        radiusPx: Float
    ) {
        val sr = color.r
        val sg = color.g
        val sb = color.b
        val sa = color.a
        if (sa <= 0) return

        val solidWord = (0xFF shl 24) or (sr shl 16) or (sg shl 8) or sb
        val checkR = radiusPx > 0.5f
        if (!checkR) {
            if (sa >= 255) {
                pixels.fill(solidWord, 0, w * h)
            } else {
                for (i in 0 until w * h) {
                    pixels[i] = blendStraightOverArgb(sr, sg, sb, sa, pixels[i])
                }
            }
            return
        }

        val hw = w * 0.5f
        val hh = h * 0.5f
        val rx = radiusPx.coerceIn(0f, min(hw, hh))
        val cornerSpan = ceil(rx + 1.5f).toInt()
        val topEndY = min(cornerSpan, h)
        val bottomStartY = max(topEndY, h - cornerSpan)
        val centerLeftX = min(cornerSpan, w)
        val centerRightX = max(centerLeftX, w - cornerSpan)

        for (py in 0 until h) {
            val rowOff = py * w
            if (py in topEndY until bottomStartY) {
                if (sa >= 255) {
                    pixels.fill(solidWord, rowOff, rowOff + w)
                } else {
                    for (px in 0 until w) {
                        pixels[rowOff + px] = blendStraightOverArgb(sr, sg, sb, sa, pixels[rowOff + px])
                    }
                }
                continue
            }

            val dy = abs(py + 0.5f - hh) - (hh - rx)
            for (px in 0 until centerLeftX) {
                val dx = abs(px + 0.5f - hw) - (hw - rx)
                val cov = (0.5f - roundedRectSignedDistance(dx, dy, rx)).coerceIn(0f, 1f)
                if (cov <= 0f) continue
                val effA = (sa * cov).roundToInt().coerceIn(0, 255)
                if (effA <= 0) continue
                val idx = rowOff + px
                pixels[idx] = if (effA >= 255) solidWord else blendStraightOverArgb(sr, sg, sb, effA, pixels[idx])
            }
            if (centerRightX > centerLeftX) {
                if (sa >= 255) {
                    pixels.fill(solidWord, rowOff + centerLeftX, rowOff + centerRightX)
                } else {
                    for (px in centerLeftX until centerRightX) {
                        val idx = rowOff + px
                        pixels[idx] = blendStraightOverArgb(sr, sg, sb, sa, pixels[idx])
                    }
                }
            }
            for (px in centerRightX until w) {
                val dx = abs(px + 0.5f - hw) - (hw - rx)
                val cov = (0.5f - roundedRectSignedDistance(dx, dy, rx)).coerceIn(0f, 1f)
                if (cov <= 0f) continue
                val effA = (sa * cov).roundToInt().coerceIn(0, 255)
                if (effA <= 0) continue
                val idx = rowOff + px
                pixels[idx] = if (effA >= 255) solidWord else blendStraightOverArgb(sr, sg, sb, effA, pixels[idx])
            }
        }
    }

    private fun fillRoundedGradientArgb(
        pixels: IntArray,
        w: Int,
        h: Int,
        gradient: Gradient,
        radiusPx: Float
    ) {
        val checkR = radiusPx > 0.5f
        val hw = w * 0.5f
        val hh = h * 0.5f
        val rx = radiusPx.coerceIn(0f, min(hw, hh))

        when (gradient) {
            is LinearGradient -> {
                val norm = GradientStopNormalizer.normalizeLinear(gradient, w.toFloat(), h.toFloat())
                val dxLine = norm.x2 - norm.x1
                val dyLine = norm.y2 - norm.y1
                val lenSq = (dxLine * dxLine + dyLine * dyLine).coerceAtLeast(1e-4f)
                val invLenSq = 1f / lenSq
                for (py in 0 until h) {
                    val rowOff = py * w
                    val dyBox = if (checkR) abs(py + 0.5f - hh) - (hh - rx) else 0f
                    val gy = py + 0.5f - norm.y1
                    for (px in 0 until w) {
                        val cov = if (!checkR) 1f else {
                            val dxBox = abs(px + 0.5f - hw) - (hw - rx)
                            (0.5f - roundedRectSignedDistance(dxBox, dyBox, rx)).coerceIn(0f, 1f)
                        }
                        if (cov <= 0f) continue
                        val t = (((px + 0.5f - norm.x1) * dxLine + gy * dyLine) * invLenSq).coerceIn(0f, 1f)
                        val c = sampleStops(norm.positions, norm.colors, t)
                        val effA = (c.a * cov).roundToInt().coerceIn(0, 255)
                        if (effA > 0) {
                            val idx = rowOff + px
                            pixels[idx] = blendStraightOverArgb(c.r, c.g, c.b, effA, pixels[idx])
                        }
                    }
                }
            }

            is RadialGradient -> {
                val norm = GradientStopNormalizer.normalizeRadial(gradient, w.toFloat(), h.toFloat())
                val invRx = 1f / norm.radiusX.coerceAtLeast(1f)
                val invRy = 1f / norm.radiusY.coerceAtLeast(1f)
                for (py in 0 until h) {
                    val rowOff = py * w
                    val dyBox = if (checkR) abs(py + 0.5f - hh) - (hh - rx) else 0f
                    val ny = (py + 0.5f - norm.cy) * invRy
                    for (px in 0 until w) {
                        val cov = if (!checkR) 1f else {
                            val dxBox = abs(px + 0.5f - hw) - (hw - rx)
                            (0.5f - roundedRectSignedDistance(dxBox, dyBox, rx)).coerceIn(0f, 1f)
                        }
                        if (cov <= 0f) continue
                        val nx = (px + 0.5f - norm.cx) * invRx
                        val t = sqrt(nx * nx + ny * ny).coerceIn(0f, 1f)
                        val c = sampleStops(norm.positions, norm.colors, t)
                        val effA = (c.a * cov).roundToInt().coerceIn(0, 255)
                        if (effA > 0) {
                            val idx = rowOff + px
                            pixels[idx] = blendStraightOverArgb(c.r, c.g, c.b, effA, pixels[idx])
                        }
                    }
                }
            }

            else -> Unit
        }
    }

    private fun strokeRoundedBorderArgb(
        pixels: IntArray,
        width: Int,
        height: Int,
        borderColor: RgbaColor,
        borderWidthPx: Float,
        radiusPx: Float,
        style: CssBorderStyle
    ) {
        val hw = width * 0.5f
        val hh = height * 0.5f
        val rx = radiusPx.coerceIn(0f, min(hw, hh))
        val bw = borderWidthPx.coerceIn(0.5f, min(hw, hh))
        val halfDash = if (style == CssBorderStyle.DASHED) 6 else if (style == CssBorderStyle.DOTTED) 2 else 0
        val borderSpanY = ceil(rx + bw + 2f).toInt()
        val topEndY = min(borderSpanY, height)
        val bottomStartY = max(topEndY, height - borderSpanY)
        val edgeBandX = ceil(bw + 2f).toInt()
        val leftEndX = min(edgeBandX, width)
        val rightStartX = max(leftEndX, width - edgeBandX)

        for (py in 0 until height) {
            val rowOff = py * width
            val dy = abs(py + 0.5f - hh) - (hh - rx)
            val isCornerRow = py < topEndY || py >= bottomStartY
            if (isCornerRow) {
                for (px in 0 until width) {
                    if (halfDash > 0 && (((px + py) / halfDash) and 1) != 0) continue
                    val dx = abs(px + 0.5f - hw) - (hw - rx)
                    val sd = roundedRectSignedDistance(dx, dy, rx)
                    val outerCov = (0.5f - sd).coerceIn(0f, 1f)
                    val innerCov = (sd + bw + 0.5f).coerceIn(0f, 1f)
                    val strokeCov = min(outerCov, innerCov)
                    if (strokeCov <= 0f) continue
                    val effA = (borderColor.a * strokeCov).roundToInt().coerceIn(0, 255)
                    if (effA <= 0) continue
                    val idx = rowOff + px
                    pixels[idx] = blendStraightOverArgb(borderColor.r, borderColor.g, borderColor.b, effA, pixels[idx])
                }
            } else {
                for (px in 0 until leftEndX) {
                    if (halfDash > 0 && (((px + py) / halfDash) and 1) != 0) continue
                    val dx = abs(px + 0.5f - hw) - (hw - rx)
                    val sd = roundedRectSignedDistance(dx, dy, rx)
                    val outerCov = (0.5f - sd).coerceIn(0f, 1f)
                    val innerCov = (sd + bw + 0.5f).coerceIn(0f, 1f)
                    val strokeCov = min(outerCov, innerCov)
                    if (strokeCov <= 0f) continue
                    val effA = (borderColor.a * strokeCov).roundToInt().coerceIn(0, 255)
                    if (effA <= 0) continue
                    val idx = rowOff + px
                    pixels[idx] = blendStraightOverArgb(borderColor.r, borderColor.g, borderColor.b, effA, pixels[idx])
                }
                for (px in rightStartX until width) {
                    if (halfDash > 0 && (((px + py) / halfDash) and 1) != 0) continue
                    val dx = abs(px + 0.5f - hw) - (hw - rx)
                    val sd = roundedRectSignedDistance(dx, dy, rx)
                    val outerCov = (0.5f - sd).coerceIn(0f, 1f)
                    val innerCov = (sd + bw + 0.5f).coerceIn(0f, 1f)
                    val strokeCov = min(outerCov, innerCov)
                    if (strokeCov <= 0f) continue
                    val effA = (borderColor.a * strokeCov).roundToInt().coerceIn(0, 255)
                    if (effA <= 0) continue
                    val idx = rowOff + px
                    pixels[idx] = blendStraightOverArgb(borderColor.r, borderColor.g, borderColor.b, effA, pixels[idx])
                }
            }
        }
    }

    private fun drawSoftInnerOuterElevationRim(
        pixels: IntArray,
        w: Int,
        h: Int,
        radiusPx: Float,
        shadowColor: RgbaColor
    ) {
        val hw = w * 0.5f
        val hh = h * 0.5f
        val rx = radiusPx.coerceIn(0f, min(hw, hh))
        val rimSpanY = ceil(rx + 4f).toInt()
        val topEndY = min(rimSpanY, h)
        val bottomStartY = max(topEndY, h - rimSpanY)
        val leftEndX = min(4, w)
        val rightStartX = max(leftEndX, w - 4)
        for (py in 0 until h) {
            val rowOff = py * w
            val dy = abs(py + 0.5f - hh) - (hh - rx)
            if (py < topEndY || py >= bottomStartY) {
                for (px in 0 until w) {
                    val dx = abs(px + 0.5f - hw) - (hw - rx)
                    val sd = roundedRectSignedDistance(dx, dy, rx)
                    if (sd > -1.5f && sd < 2.5f) {
                        val alpha = (shadowColor.a * 0.35f * (1f - abs(sd) / 2.5f)).roundToInt().coerceIn(0, 120)
                        if (alpha > 0) {
                            val idx = rowOff + px
                            pixels[idx] = blendStraightOverArgb(shadowColor.r, shadowColor.g, shadowColor.b, alpha, pixels[idx])
                        }
                    }
                }
            } else {
                for (px in 0 until leftEndX) {
                    val dx = abs(px + 0.5f - hw) - (hw - rx)
                    val sd = roundedRectSignedDistance(dx, dy, rx)
                    if (sd > -1.5f && sd < 2.5f) {
                        val alpha = (shadowColor.a * 0.35f * (1f - abs(sd) / 2.5f)).roundToInt().coerceIn(0, 120)
                        if (alpha > 0) {
                            val idx = rowOff + px
                            pixels[idx] = blendStraightOverArgb(shadowColor.r, shadowColor.g, shadowColor.b, alpha, pixels[idx])
                        }
                    }
                }
                for (px in rightStartX until w) {
                    val dx = abs(px + 0.5f - hw) - (hw - rx)
                    val sd = roundedRectSignedDistance(dx, dy, rx)
                    if (sd > -1.5f && sd < 2.5f) {
                        val alpha = (shadowColor.a * 0.35f * (1f - abs(sd) / 2.5f)).roundToInt().coerceIn(0, 120)
                        if (alpha > 0) {
                            val idx = rowOff + px
                            pixels[idx] = blendStraightOverArgb(shadowColor.r, shadowColor.g, shadowColor.b, alpha, pixels[idx])
                        }
                    }
                }
            }
        }
    }

    internal fun chromiumActiveTabSignedDistance(
        px: Float,
        py: Float,
        width: Float,
        height: Float,
        radii: ResolvedCornerRadii
    ): Float {
        val earR = radii.chromiumFilletEarPx
        if (earR > 0.5f && width > earR * 2f + 2f) {
            val earTopY = height - earR
            if (py >= earTopY) {
                if (px < earR) {
                    val dxC = px
                    val dyC = py - earTopY
                    val dCenter = sqrt(dxC * dxC + dyC * dyC)
                    return max(earR - dCenter, max(-px, py - height))
                }
                if (px > width - earR) {
                    val dxC = width - px
                    val dyC = py - earTopY
                    val dCenter = sqrt(dxC * dxC + dyC * dyC)
                    return max(earR - dCenter, max(px - width, py - height))
                }
            }
            val innerW = (width - earR * 2f).coerceAtLeast(1f)
            val hw = innerW * 0.5f
            val hh = height * 0.5f
            val relX = px - earR - hw
            val relY = py - hh
            val cornerR = if (relX > 0f) {
                if (relY > 0f) radii.bottomRight else radii.topRight
            } else {
                if (relY > 0f) radii.bottomLeft else radii.topLeft
            }.coerceIn(0f, min(hw, hh))
            return roundedRectSignedDistance(abs(relX) - (hw - cornerR), abs(relY) - (hh - cornerR), cornerR)
        }
        val hw = width * 0.5f
        val hh = height * 0.5f
        val relX = px - hw
        val relY = py - hh
        val cornerR = if (relX > 0f) {
            if (relY > 0f) radii.bottomRight else radii.topRight
        } else {
            if (relY > 0f) radii.bottomLeft else radii.topLeft
        }.coerceIn(0f, min(hw, hh))
        return roundedRectSignedDistance(abs(relX) - (hw - cornerR), abs(relY) - (hh - cornerR), cornerR)
    }

    private fun fillPerCornerRoundedSolidArgb(
        pixels: IntArray,
        w: Int,
        h: Int,
        color: RgbaColor,
        radii: ResolvedCornerRadii
    ) {
        val sa = color.a
        if (sa <= 0) return
        val solidWord = (0xFF shl 24) or (color.r shl 16) or (color.g shl 8) or color.b
        val wf = w.toFloat()
        val hf = h.toFloat()
        for (py in 0 until h) {
            val rowOff = py * w
            val y = py + 0.5f
            for (px in 0 until w) {
                val sd = chromiumActiveTabSignedDistance(px + 0.5f, y, wf, hf, radii)
                val cov = (0.5f - sd).coerceIn(0f, 1f)
                if (cov <= 0f) continue
                val effA = (sa * cov).roundToInt().coerceIn(0, 255)
                if (effA <= 0) continue
                val idx = rowOff + px
                pixels[idx] = if (effA >= 255) solidWord else blendStraightOverArgb(color.r, color.g, color.b, effA, pixels[idx])
            }
        }
    }

    private fun fillPerCornerRoundedGradientArgb(
        pixels: IntArray,
        w: Int,
        h: Int,
        gradient: Gradient,
        radii: ResolvedCornerRadii
    ) {
        val wf = w.toFloat()
        val hf = h.toFloat()
        when (gradient) {
            is LinearGradient -> {
                val norm = GradientStopNormalizer.normalizeLinear(gradient, wf, hf)
                val dxLine = norm.x2 - norm.x1
                val dyLine = norm.y2 - norm.y1
                val invLenSq = 1f / (dxLine * dxLine + dyLine * dyLine).coerceAtLeast(1e-4f)
                for (py in 0 until h) {
                    val rowOff = py * w
                    val y = py + 0.5f
                    val gy = y - norm.y1
                    for (px in 0 until w) {
                        val x = px + 0.5f
                        val cov = (0.5f - chromiumActiveTabSignedDistance(x, y, wf, hf, radii)).coerceIn(0f, 1f)
                        if (cov <= 0f) continue
                        val t = (((x - norm.x1) * dxLine + gy * dyLine) * invLenSq).coerceIn(0f, 1f)
                        val c = sampleStops(norm.positions, norm.colors, t)
                        val effA = (c.a * cov).roundToInt().coerceIn(0, 255)
                        if (effA > 0) {
                            val idx = rowOff + px
                            pixels[idx] = blendStraightOverArgb(c.r, c.g, c.b, effA, pixels[idx])
                        }
                    }
                }
            }
            is RadialGradient -> {
                val norm = GradientStopNormalizer.normalizeRadial(gradient, wf, hf)
                val invRx = 1f / norm.radiusX.coerceAtLeast(1f)
                val invRy = 1f / norm.radiusY.coerceAtLeast(1f)
                for (py in 0 until h) {
                    val rowOff = py * w
                    val y = py + 0.5f
                    val ny = (y - norm.cy) * invRy
                    for (px in 0 until w) {
                        val x = px + 0.5f
                        val cov = (0.5f - chromiumActiveTabSignedDistance(x, y, wf, hf, radii)).coerceIn(0f, 1f)
                        if (cov <= 0f) continue
                        val nx = (x - norm.cx) * invRx
                        val t = sqrt(nx * nx + ny * ny).coerceIn(0f, 1f)
                        val c = sampleStops(norm.positions, norm.colors, t)
                        val effA = (c.a * cov).roundToInt().coerceIn(0, 255)
                        if (effA > 0) {
                            val idx = rowOff + px
                            pixels[idx] = blendStraightOverArgb(c.r, c.g, c.b, effA, pixels[idx])
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    private fun strokePerCornerRoundedBorderArgb(
        pixels: IntArray,
        width: Int,
        height: Int,
        borderColor: RgbaColor,
        borderWidthPx: Float,
        radii: ResolvedCornerRadii,
        style: CssBorderStyle
    ) {
        val bw = borderWidthPx.coerceIn(0.5f, min(width, height) * 0.5f)
        val halfDash = if (style == CssBorderStyle.DASHED) 6 else if (style == CssBorderStyle.DOTTED) 2 else 0
        val wf = width.toFloat()
        val hf = height.toFloat()
        val bottomCutoffY = if (radii.skipBottomBorder) height - ceil(bw).toInt() else height
        for (py in 0 until height) {
            if (radii.skipBottomBorder && py >= bottomCutoffY) {
                val rowOff = py * width
                val earSpan = ceil(radii.chromiumFilletEarPx + bw + 2f).toInt()
                val y = py + 0.5f
                for (px in 0 until min(earSpan, width)) {
                    if (halfDash > 0 && (((px + py) / halfDash) and 1) != 0) continue
                    val sd = chromiumActiveTabSignedDistance(px + 0.5f, y, wf, hf, radii)
                    val strokeCov = min((0.5f - sd).coerceIn(0f, 1f), (sd + bw + 0.5f).coerceIn(0f, 1f))
                    val effA = (borderColor.a * strokeCov).roundToInt().coerceIn(0, 255)
                    if (effA > 0) {
                        pixels[rowOff + px] = blendStraightOverArgb(borderColor.r, borderColor.g, borderColor.b, effA, pixels[rowOff + px])
                    }
                }
                for (px in max(earSpan, width - earSpan) until width) {
                    if (halfDash > 0 && (((px + py) / halfDash) and 1) != 0) continue
                    val sd = chromiumActiveTabSignedDistance(px + 0.5f, y, wf, hf, radii)
                    val strokeCov = min((0.5f - sd).coerceIn(0f, 1f), (sd + bw + 0.5f).coerceIn(0f, 1f))
                    val effA = (borderColor.a * strokeCov).roundToInt().coerceIn(0, 255)
                    if (effA > 0) {
                        pixels[rowOff + px] = blendStraightOverArgb(borderColor.r, borderColor.g, borderColor.b, effA, pixels[rowOff + px])
                    }
                }
                continue
            }
            val rowOff = py * width
            val y = py + 0.5f
            for (px in 0 until width) {
                if (halfDash > 0 && (((px + py) / halfDash) and 1) != 0) continue
                val sd = chromiumActiveTabSignedDistance(px + 0.5f, y, wf, hf, radii)
                val outerCov = (0.5f - sd).coerceIn(0f, 1f)
                val innerCov = (sd + bw + 0.5f).coerceIn(0f, 1f)
                val strokeCov = min(outerCov, innerCov)
                if (strokeCov <= 0f) continue
                val effA = (borderColor.a * strokeCov).roundToInt().coerceIn(0, 255)
                if (effA <= 0) continue
                val idx = rowOff + px
                pixels[idx] = blendStraightOverArgb(borderColor.r, borderColor.g, borderColor.b, effA, pixels[idx])
            }
        }
    }

    private fun roundedRectSignedDistance(dx: Float, dy: Float, radius: Float): Float {
        val qx = max(dx, 0f)
        val qy = max(dy, 0f)
        return sqrt(qx * qx + qy * qy) + min(max(dx, dy), 0f) - radius
    }

    private fun sampleStops(positions: FloatArray, colors: Array<RgbaColor>, t: Float): RgbaColor {
        if (colors.isEmpty()) return RgbaColor.TRANSPARENT
        if (colors.size == 1 || t <= positions.first()) return colors.first()
        if (t >= positions.last()) return colors.last()
        for (i in 0 until colors.size - 1) {
            val p0 = positions[i]
            val p1 = positions[i + 1]
            if (t <= p1) {
                val span = (p1 - p0).coerceAtLeast(1e-4f)
                val f = ((t - p0) / span).coerceIn(0f, 1f)
                val c0 = colors[i]
                val c1 = colors[i + 1]
                return RgbaColor(
                    r = (c0.r + (c1.r - c0.r) * f).roundToInt().coerceIn(0, 255),
                    g = (c0.g + (c1.g - c0.g) * f).roundToInt().coerceIn(0, 255),
                    b = (c0.b + (c1.b - c0.b) * f).roundToInt().coerceIn(0, 255),
                    a = (c0.a + (c1.a - c0.a) * f).roundToInt().coerceIn(0, 255)
                )
            }
        }
        return colors.last()
    }

    private fun blendStraightOverArgb(sr: Int, sg: Int, sb: Int, sa: Int, dstWord: Int): Int {
        if (sa >= 255) return (0xFF shl 24) or (sr shl 16) or (sg shl 8) or sb
        if (sa <= 0) return dstWord
        val da = (dstWord ushr 24) and 0xFF
        val dr = (dstWord ushr 16) and 0xFF
        val dg = (dstWord ushr 8) and 0xFF
        val db = dstWord and 0xFF
        val invA = 255 - sa
        val outA = (sa + (da * invA + 127) / 255).coerceIn(0, 255)
        val outR = (sr * sa + dr * da * invA / 255 + 127) / 255
        val outG = (sg * sa + dg * da * invA / 255 + 127) / 255
        val outB = (sb * sa + db * da * invA / 255 + 127) / 255
        return (outA.coerceIn(0, 255) shl 24) or
            (outR.coerceIn(0, 255) shl 16) or
            (outG.coerceIn(0, 255) shl 8) or
            outB.coerceIn(0, 255)
    }

    private fun blendPremulWordOver(srcPremul: Int, dstArgb: Int): Int {
        val sa = (srcPremul ushr 24) and 0xFF
        if (sa >= 255) return srcPremul
        if (sa <= 0) return dstArgb
        val sr = (srcPremul ushr 16) and 0xFF
        val sg = (srcPremul ushr 8) and 0xFF
        val sb = srcPremul and 0xFF
        val dr = (dstArgb ushr 16) and 0xFF
        val dg = (dstArgb ushr 8) and 0xFF
        val db = dstArgb and 0xFF
        val invA = 255 - sa
        val outR = (sr + (dr * invA + 127) / 255).coerceIn(0, 255)
        val outG = (sg + (dg * invA + 127) / 255).coerceIn(0, 255)
        val outB = (sb + (db * invA + 127) / 255).coerceIn(0, 255)
        return (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
    }

    private fun darkenOrLighten(c: RgbaColor, delta: Int): RgbaColor = RgbaColor(
        r = (c.r + delta).coerceIn(0, 255),
        g = (c.g + delta).coerceIn(0, 255),
        b = (c.b + delta).coerceIn(0, 255),
        a = c.a
    )

    private fun computeFilterPadPx(filter: CssFilter?): Int {
        if (filter == null || !filter.hasActiveEffects) return 0
        val blur = (filter.blur?.resolvePx() ?: 0f).coerceIn(0f, 48f)
        val blurPad = if (blur > 0f) ceil(blur * 1.5f).toInt().coerceIn(1, 32) else 0
        val shadow = filter.dropShadow?.takeIf { filter.hasDropShadow }
        val shadowPad = if (shadow != null) {
            val sBlur = (shadow.blurRadius.resolvePx() ?: 0f).coerceIn(0f, 48f)
            val sOff = max(abs(shadow.offsetX.resolvePx() ?: 0f), abs(shadow.offsetY.resolvePx() ?: 0f))
            (ceil(sBlur * 1.5f).toInt() + ceil(sOff).toInt()).coerceIn(1, 48)
        } else 0
        return max(blurPad, shadowPad)
    }

    private fun ensureSurfaceDib(refDc: HDC, w: Int, h: Int): CPointer<UIntVar>? {
        if (reusableSurfaceDc != null && reusableSurfacePixels != null &&
            reusableSurfaceW >= w && reusableSurfaceH >= h
        ) {
            return reusableSurfacePixels
        }
        val allocW = max(w, reusableSurfaceW)
        val allocH = max(h, reusableSurfaceH)
        reusableSurfaceOldBmp?.let { old -> reusableSurfaceDc?.let { dc -> SelectObject(dc, old) } }
        reusableSurfaceOldBmp = null
        reusableSurfaceBmp?.let { DeleteObject(it) }
        reusableSurfaceBmp = null
        reusableSurfacePixels = null

        val dc = reusableSurfaceDc ?: CreateCompatibleDC(refDc)?.also { reusableSurfaceDc = it } ?: return null
        return memScoped {
            val bmi = alloc<BITMAPINFO>()
            bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
            bmi.bmiHeader.biWidth = allocW
            bmi.bmiHeader.biHeight = -allocH
            bmi.bmiHeader.biPlanes = 1u
            bmi.bmiHeader.biBitCount = 32u
            bmi.bmiHeader.biCompression = BI_RGB.toUInt()
            val bitsPtrVar = alloc<COpaquePointerVar>()
            val bmp = CreateDIBSection(dc, bmi.ptr, DIB_RGB_COLORS.toUInt(), bitsPtrVar.ptr, null, 0u)
            val pixels = bitsPtrVar.value?.reinterpret<UIntVar>()
            if (bmp == null || pixels == null) {
                if (bmp != null) DeleteObject(bmp)
                reusableSurfaceW = 0
                reusableSurfaceH = 0
                return@memScoped null
            }
            reusableSurfaceOldBmp = SelectObject(dc, bmp)
            reusableSurfaceBmp = bmp
            reusableSurfacePixels = pixels
            reusableSurfaceW = allocW
            reusableSurfaceH = allocH
            pixels
        }
    }

    private fun captureWindowPixelsIntoScratch(hwnd: HWND, w: Int, h: Int): CPointer<UIntVar>? {
        if (w <= 0 || h <= 0) return null
        if (scratchSiblingDc == null || scratchSiblingPixels == null || scratchSiblingW < w || scratchSiblingH < h) {
            scratchSiblingOldBmp?.let { old -> scratchSiblingDc?.let { dc -> SelectObject(dc, old) } }
            scratchSiblingOldBmp = null
            scratchSiblingBmp?.let { DeleteObject(it) }
            scratchSiblingBmp = null
            scratchSiblingPixels = null
            val dc = scratchSiblingDc ?: CreateCompatibleDC(null)?.also { scratchSiblingDc = it } ?: return null
            val ok = memScoped {
                val bmi = alloc<BITMAPINFO>()
                bmi.bmiHeader.biSize = sizeOf<BITMAPINFOHEADER>().toUInt()
                bmi.bmiHeader.biWidth = w
                bmi.bmiHeader.biHeight = -h
                bmi.bmiHeader.biPlanes = 1u
                bmi.bmiHeader.biBitCount = 32u
                bmi.bmiHeader.biCompression = BI_RGB.toUInt()
                val bitsPtrVar = alloc<COpaquePointerVar>()
                val bmp = CreateDIBSection(dc, bmi.ptr, DIB_RGB_COLORS.toUInt(), bitsPtrVar.ptr, null, 0u)
                val pixels = bitsPtrVar.value?.reinterpret<UIntVar>()
                if (bmp == null || pixels == null) {
                    if (bmp != null) DeleteObject(bmp)
                    false
                } else {
                    scratchSiblingOldBmp = SelectObject(dc, bmp)
                    scratchSiblingBmp = bmp
                    scratchSiblingPixels = pixels
                    scratchSiblingW = w
                    scratchSiblingH = h
                    true
                }
            }
            if (!ok) return null
        }
        val dc = scratchSiblingDc ?: return null
        val pixels = scratchSiblingPixels ?: return null
        val count = scratchSiblingW * h
        for (i in 0 until count) {
            pixels[i] = 0x00000000u
        }
        PrintWindow(hwnd, dc, PW_RENDERFULLCONTENT_FLAG)
        GdiFlush()
        return pixels
    }
}
