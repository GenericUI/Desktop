@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopButtonNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopContainerNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.desktop.widget.DesktopTextNativeImpl
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.css.CssBorderStyle
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
import platform.windows.GW_HWNDNEXT
import platform.windows.GdiFlush
import platform.windows.GetParent
import platform.windows.GetWindow
import platform.windows.GetWindowRect
import platform.windows.HBITMAP
import platform.windows.HDC
import platform.windows.HFONT
import platform.windows.HGDIOBJ
import platform.windows.HWND
import platform.windows.IsWindowVisible
import platform.windows.MapWindowPoints
import platform.windows.POINT
import platform.windows.PrintWindow
import platform.windows.RECT
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

    /**
     * Paints a custom retained child [Win32HwndHandle] into its `WM_PAINT` [hdc].
     */
    fun paintCustomEntry(
        entry: Win32HwndHandle?,
        hwnd: HWND,
        hdc: HDC,
        width: Int,
        height: Int
    ) {
        if (width <= 0 || height <= 0) return
        val owner = entry?.ownerImpl
        val isDark = Win32NativeWindowHost.isEffectivelyDarkTheme()

        if (entry == null || owner == null) {
            fillSolidParentBackdrop(hwnd, hdc, width, height, isDark)
            return
        }

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
            fillSolidParentBackdrop(hwnd, hdc, width, height, isDark)
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
            iconBuffer = iconBuffer
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
        val spec = resolveBootstrapButtonVisualSpec(owner, isDark)
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
     * and badge child `HWND`s.
     */
    fun resolveEffectiveVisualSpec(
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
    fun renderStyledSpecToHdc(
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
        iconBuffer: ImageBuffer? = null
    ) {
        if (width <= 0 || height <= 0) return

        val backdropFilter = spec.backdropFilter ?: if ((spec.backdropBlurPx ?: 0f) > 0f) {
            CssFilter(blur = com.nexustools.genericui.style.css.CssLength.px(spec.backdropBlurPx!!))
        } else {
            null
        }
        val hasBackdrop = backdropFilter != null && backdropFilter.hasActiveEffects

        val surfacePixels = IntArray(width * height)
        val radiusPx = (spec.borderRadiusPx ?: 0f).coerceIn(0f, min(width, height) * 0.5f)

        // 1. Sample parent / stage / lower-Z sibling backdrop when backdrop-filter OR transparent overlay needs it
        val needsParentPixels = hasBackdrop ||
            ((spec.backgroundColor?.a ?: 0) in 1..254) ||
            spec.backgroundGradient != null ||
            radiusPx > 0.5f
        if (needsParentPixels) {
            val pad = computeFilterPadPx(backdropFilter)
            if (hasBackdrop) {
                val capW = width + pad * 2
                val capH = height + pad * 2
                val capPixels = IntArray(capW * capH)
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
            } else {
                captureBackdropIntoBuffer(hwnd, 0, 0, width, height, surfacePixels, isDark)
            }
        }

        // 2. Subtle outer box shadow inset / border allowance
        val shadowColor = spec.boxShadowColor
        val shadowBlur = (spec.boxShadowBlurPx ?: 0f).coerceAtLeast(0f)
        if (shadowColor != null && shadowColor.a > 0 && shadowBlur > 0f) {
            drawSoftInnerOuterElevationRim(surfacePixels, width, height, radiusPx, shadowColor)
        }

        // 3. Background gradient or solid fill inside rounded SDF mask
        val gradient = spec.backgroundGradient
        val bgColor = spec.backgroundColor
        if (gradient != null) {
            fillRoundedGradientArgb(surfacePixels, width, height, gradient, radiusPx)
        } else if (bgColor != null && bgColor.a > 0) {
            fillRoundedSolidArgb(surfacePixels, width, height, bgColor, radiusPx)
        }

        // 4. Rounded SDF border
        val borderW = (spec.borderWidthPx ?: 0f).coerceAtLeast(0f)
        val borderColor = spec.borderColor
        if (borderW > 0f && borderColor != null && borderColor.a > 0 &&
            spec.borderStyle != CssBorderStyle.NONE && spec.borderStyle != CssBorderStyle.HIDDEN
        ) {
            strokeRoundedBorderArgb(
                pixels = surfacePixels,
                width = width,
                height = height,
                borderColor = borderColor,
                borderWidthPx = borderW,
                radiusPx = radiusPx,
                style = spec.borderStyle ?: CssBorderStyle.SOLID
            )
        }

        // 5. Focus ring
        val ringW = (spec.focusRingWidthPx ?: 0f).coerceAtLeast(0f)
        val ringColor = spec.focusRingColor
        if (focused && ringW > 0f && ringColor != null && ringColor.a > 0) {
            strokeRoundedBorderArgb(
                pixels = surfacePixels,
                width = width,
                height = height,
                borderColor = ringColor,
                borderWidthPx = ringW,
                radiusPx = radiusPx,
                style = CssBorderStyle.SOLID
            )
        }

        // 5.5. Composite optional child ImageBuffer icon (pure SrcOver alpha blend, never opaque box)
        if (iconBuffer != null && iconBuffer.width > 0 && iconBuffer.height > 0 && iconBuffer.bytes.size >= iconBuffer.width * iconBuffer.height * 4) {
            compositeCenteredRgbaIcon(surfacePixels, width, height, iconBuffer)
        }

        // 6. Optional text overlay + foreground CSS filter (all 10 ops) + final BitBlt
        val dib = ensureSurfaceDib(hdc, width, height) ?: return
        for (i in 0 until width * height) {
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

        if (!text.isNullOrBlank()) {
            val textCol = textColorOverride ?: spec.textColor
                ?: if (isDark) RgbaColor(242, 242, 245) else RgbaColor(20, 20, 24)
            drawTextIntoPremulSurfaceDib(
                dibDc = reusableSurfaceDc!!,
                pixelsPtr = dib,
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
            val argbForFilter = IntArray(width * height)
            for (i in 0 until width * height) {
                val p = dib[i].toInt()
                val a = (p ushr 24) and 0xFF
                if (a == 255 || a == 0) {
                    argbForFilter[i] = p
                } else {
                    val r = (((p ushr 16) and 0xFF) * 255 + a / 2) / a
                    val g = (((p ushr 8) and 0xFF) * 255 + a / 2) / a
                    val b = ((p and 0xFF) * 255 + a / 2) / a
                    argbForFilter[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
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
            for (i in 0 until width * height) {
                val argb = argbForFilter[i]
                val a = (argb ushr 24) and 0xFF
                dib[i] = if (a >= 255) {
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

        GdiFlush()
        BitBlt(hdc, 0, 0, width, height, reusableSurfaceDc, 0, 0, SRCCOPY)
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
        GdiFlush()
        BitBlt(hdc, 0, 0, width, height, reusableSurfaceDc, 0, 0, SRCCOPY)
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

    fun fillSolidParentBackdrop(
        hwnd: HWND,
        hdc: HDC,
        width: Int,
        height: Int,
        isDark: Boolean
    ) {
        if (width <= 0 || height <= 0) return
        if (backdropCaptureDepth == 0) {
            val stagePixels = IntArray(width * height)
            if (Win32LayeredStageHostHwnd.tryCopyBackdropForChild(hwnd, 0, 0, width, height, stagePixels)) {
                blitArgbArrayToHdc(hdc, width, height, stagePixels, premultiplied = true)
                return
            }
            if (GetWindow(hwnd, GW_HWNDNEXT.toUInt()) != null) {
                captureBackdropIntoBuffer(hwnd, 0, 0, width, height, stagePixels, isDark)
                blitArgbArrayToHdc(hdc, width, height, stagePixels, premultiplied = true)
                return
            }
        }
        val bg = resolveAncestorBackgroundColor(hwnd, isDark)
        val word = ((0xFF shl 24) or (bg.r shl 16) or (bg.g shl 8) or bg.b).toUInt()
        val dib = ensureSurfaceDib(hdc, width, height) ?: return
        val count = width * height
        for (i in 0 until count) {
            dib[i] = word
        }
        GdiFlush()
        BitBlt(hdc, 0, 0, width, height, reusableSurfaceDc, 0, 0, SRCCOPY)
    }

    private fun resolveAncestorBackgroundColor(hwnd: HWND, isDark: Boolean): RgbaColor {
        var cur: HWND? = GetParent(hwnd)
        while (cur != null) {
            val entry = Win32ControlMessageRouter.resolveEntry(cur)
            val owner = entry?.ownerImpl
            if (owner != null) {
                val bg = owner.getEffectiveBackgroundColor()
                if (bg != null && bg.a >= 250) return bg
            }
            cur = GetParent(cur)
        }
        return if (isDark) RgbaColor(24, 24, 28) else RgbaColor(246, 246, 248)
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
        width: Int,
        height: Int,
        text: String,
        textColor: RgbaColor,
        fontHandle: HFONT?,
        alignLeft: Boolean
    ) {
        val count = width * height
        // Snapshot current pixels before GDI DrawTextW writes opaque RGB values with alpha=0
        val beforeWords = IntArray(count)
        for (i in 0 until count) {
            val p = pixelsPtr[i].toInt()
            beforeWords[i] = p
            // Ensure alpha byte sentinel = 0xFF temporarily so we detect GDI rasterized texels cleanly
            pixelsPtr[i] = (p.toUInt() and 0x00FFFFFFu) or 0xFF000000u
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

        for (i in 0 until count) {
            val drawn = pixelsPtr[i].toInt()
            val gdiAlphaByte = (drawn ushr 24) and 0xFF
            val orig = beforeWords[i]
            val origA = (orig ushr 24) and 0xFF
            if (gdiAlphaByte == 0xFF) {
                // Pixel untouched by GDI DrawTextW -> restore exact premul RGBA
                pixelsPtr[i] = orig.toUInt()
            } else {
                // GDI cleared alpha byte on touched texels -> extract final premul RGB safely
                val dr = (drawn ushr 16) and 0xFF
                val dg = (drawn ushr 8) and 0xFF
                val db = drawn and 0xFF
                val outA = max(origA, textColor.a).coerceIn(0, 255)
                pixelsPtr[i] = ((outA shl 24) or (dr shl 16) or (dg shl 8) or db).toUInt()
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

        val checkR = radiusPx > 0.5f
        val hw = w * 0.5f
        val hh = h * 0.5f
        val rx = radiusPx.coerceIn(0f, min(hw, hh))

        for (py in 0 until h) {
            val rowOff = py * w
            val dy = if (checkR) abs(py + 0.5f - hh) - (hh - rx) else 0f
            for (px in 0 until w) {
                val cov: Float = if (!checkR) {
                    1f
                } else {
                    val dx = abs(px + 0.5f - hw) - (hw - rx)
                    val sd = roundedRectSignedDistance(dx, dy, rx)
                    (0.5f - sd).coerceIn(0f, 1f)
                }
                if (cov <= 0f) continue
                val effA = (sa * cov).roundToInt().coerceIn(0, 255)
                if (effA <= 0) continue
                val idx = rowOff + px
                if (effA >= 255) {
                    pixels[idx] = (0xFF shl 24) or (sr shl 16) or (sg shl 8) or sb
                } else {
                    pixels[idx] = blendStraightOverArgb(sr, sg, sb, effA, pixels[idx])
                }
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

        for (py in 0 until height) {
            val rowOff = py * width
            val dy = abs(py + 0.5f - hh) - (hh - rx)
            for (px in 0 until width) {
                if (halfDash > 0 && (((px + py) / halfDash) and 1) != 0) continue
                val dx = abs(px + 0.5f - hw) - (hw - rx)
                val sd = roundedRectSignedDistance(dx, dy, rx)
                // Outside edge sd = 0, inner border edge sd = -bw
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
        for (py in 0 until h) {
            val rowOff = py * w
            val dy = abs(py + 0.5f - hh) - (hh - rx)
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
            reusableSurfaceW == w && reusableSurfaceH == h
        ) {
            return reusableSurfacePixels
        }
        reusableSurfaceOldBmp?.let { old -> reusableSurfaceDc?.let { dc -> SelectObject(dc, old) } }
        reusableSurfaceOldBmp = null
        reusableSurfaceBmp?.let { DeleteObject(it) }
        reusableSurfaceBmp = null
        reusableSurfacePixels = null

        val dc = reusableSurfaceDc ?: CreateCompatibleDC(refDc)?.also { reusableSurfaceDc = it } ?: return null
        return memScoped {
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
                reusableSurfaceW = 0
                reusableSurfaceH = 0
                return@memScoped null
            }
            reusableSurfaceOldBmp = SelectObject(dc, bmp)
            reusableSurfaceBmp = bmp
            reusableSurfacePixels = pixels
            reusableSurfaceW = w
            reusableSurfaceH = h
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
