@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.render

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.theme.ThemeConfig
import com.nexustools.genericui.windows.Win32NativeWindowHost
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import platform.windows.CreateFontW
import platform.windows.DEFAULT_CHARSET
import platform.windows.DEFAULT_PITCH
import platform.windows.DeleteObject
import platform.windows.FF_DONTCARE
import platform.windows.FW_BOLD
import platform.windows.FW_NORMAL
import platform.windows.HDC
import platform.windows.HFONT
import platform.windows.HWND
import platform.windows.OUT_DEFAULT_PRECIS
import platform.windows.PROOF_QUALITY
import platform.windows.RECT
import platform.windows.SelectObject
import kotlin.math.roundToInt

/**
 * Encapsulates the Win32 rendering state for a single frame/pass: the target [hwnd] and [hdc],
 * active 32-bit backbuffer pixel pointer, clip/viewport [bounds], [dpiScale], [darkMode],
 * active [themeConfig], GDI font cache, hit-test registration callback, and child widget
 * recursion callback.
 */
class Win32RenderContext internal constructor(
    val hwnd: HWND,
    val hdc: HDC?,
    val bounds: Rect,
    val dpiScale: Float = 1f,
    val darkMode: Boolean = false,
    val themeConfig: ThemeConfig = BootstrapColorPalette.activeThemeConfig,
    val fontSmall: HFONT? = null,
    val fontSmallBold: HFONT? = null,
    val fontNormal: HFONT? = null,
    val fontBold: HFONT? = null,
    val fontTitle: HFONT? = null,
    val fontMetricBig: HFONT? = null,
    val titleBarWidgets: Set<DesktopNativeWidget> = emptySet(),
    val opacity: Float = 1f,
    val backBufferPixels: CPointer<UByteVar>? = Win32NativeWindowHost.activeBackBufferPixels?.reinterpret(),
    val backBufferWidth: Int = Win32NativeWindowHost.activeBackBufferWidth,
    val backBufferHeight: Int = Win32NativeWindowHost.activeBackBufferHeight,
    internal val registerHitRegionCallback: ((Win32NativeWindowHost.HitRegion) -> Unit)? = null,
    val renderChildCallback: ((Win32RenderContext, DesktopNativeWidget, Int, Int, Int) -> Int)? = null,
    val measureInlineChildWidthCallback: ((Win32RenderContext, DesktopNativeWidget, Int) -> Int)? = null,
    val drawImageBoxCallback: ((Win32RenderContext, DesktopNativeWidget, String, String, Boolean, String, Int, Int, Int, Int, UInt) -> Unit)? = null
) {
    private val customFontsByKey = HashMap<String, HFONT>()

    val bgBaseColorRef: UInt = if (darkMode) 0x202020u else 0xF0F0F0u
    val bgPanelColorRef: UInt = if (darkMode) 0x2B2B2Bu else 0xFFFFFFu
    val bgCardColorRef: UInt = if (darkMode) 0x363434u else 0xFCFAFAu
    val bgElevatedColorRef: UInt = if (darkMode) 0x423E3Eu else 0xEAE5E5u
    val borderSubtleColorRef: UInt = if (darkMode) 0x4E4A4Au else 0xCCC6C6u
    val textPrimaryColorRef: UInt = if (darkMode) 0xF5F5F5u else 0x000000u
    val textSecondaryColorRef: UInt = if (darkMode) 0xC4C4C4u else 0x444444u
    val textMutedColorRef: UInt = if (darkMode) 0x9A9A9Au else 0x6D6D6Du
    val accentBlueColorRef: UInt = 0xD47800u
    val accentAmberColorRef: UInt = if (darkMode) 0x00E1FCu else 0x005D9Du

    internal val systemTheme: Win32NativeWindowHost.SystemThemePalette =
        Win32NativeWindowHost.SystemThemePalette(
            isDark = darkMode,
            bgBase = bgBaseColorRef,
            bgPanel = bgPanelColorRef,
            bgCard = bgCardColorRef,
            bgElevated = bgElevatedColorRef,
            borderSubtle = borderSubtleColorRef,
            borderDash = if (darkMode) 0x5A5656u else 0xB4B4B4u,
            textPrimary = textPrimaryColorRef,
            textSecondary = textSecondaryColorRef,
            textMuted = textMutedColorRef,
            accentBlue = accentBlueColorRef,
            accentBlueHover = if (darkMode) 0xE88A18u else 0xB86200u,
            accentBlueSoftBg = if (darkMode) 0x323232u else 0xF0F0F0u,
            accentBlueBorder = accentBlueColorRef,
            accentBlueText = if (darkMode) 0xFFCD60u else 0xCC6600u,
            accentGreen = if (darkMode) 0x5FCB6Cu else 0x107C10u,
            accentGreenSoftBg = if (darkMode) 0x323232u else 0xF0F0F0u,
            accentGreenBorder = if (darkMode) 0x508C50u else 0x107C10u,
            accentAmber = accentAmberColorRef,
            accentAmberSoftBg = if (darkMode) 0x323232u else 0xF0F0F0u,
            accentRed = if (darkMode) 0xA499FFu else 0x1C2BC4u,
            accentRedSoftBg = if (darkMode) 0x323232u else 0xF0F0F0u,
            accentRedBorder = if (darkMode) 0x4646A0u else 0x1C2BC4u
        )

    /**
     * Creates a child [Win32RenderContext] with updated bounds and optional [newOpacity].
     */
    fun withBounds(
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        newOpacity: Float = opacity
    ): Win32RenderContext = Win32RenderContext(
        hwnd = hwnd,
        hdc = hdc,
        bounds = Rect(left, top, (right - left).coerceAtLeast(0), (bottom - top).coerceAtLeast(0)),
        dpiScale = dpiScale,
        darkMode = darkMode,
        themeConfig = themeConfig,
        fontSmall = fontSmall,
        fontSmallBold = fontSmallBold,
        fontNormal = fontNormal,
        fontBold = fontBold,
        fontTitle = fontTitle,
        fontMetricBig = fontMetricBig,
        titleBarWidgets = titleBarWidgets,
        opacity = newOpacity,
        backBufferPixels = backBufferPixels,
        backBufferWidth = backBufferWidth,
        backBufferHeight = backBufferHeight,
        registerHitRegionCallback = registerHitRegionCallback,
        renderChildCallback = renderChildCallback,
        measureInlineChildWidthCallback = measureInlineChildWidthCallback,
        drawImageBoxCallback = drawImageBoxCallback
    )

    fun measureInlineWidth(widget: DesktopNativeWidget, availW: Int): Int =
        measureInlineChildWidthCallback?.invoke(this, widget, availW) ?: availW.coerceIn(32, 160)

    /**
     * Registers an interactive [Win32NativeWindowHost.HitRegion] for mouse hit-testing.
     */
    internal fun registerHitRegion(region: Win32NativeWindowHost.HitRegion) {
        if (registerHitRegionCallback != null) {
            registerHitRegionCallback.invoke(region)
        } else {
            Win32NativeWindowHost.addHitRegion(region)
        }
    }

    /**
     * Renders a child [widget] within `[left..right]` starting at `top`, returning the bottom Y coordinate.
     */
    fun renderChild(
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        if (!widget.visible || widget.disposed || widget in titleBarWidgets) return top
        val impl = widget.attributes["nativeImpl"] as? DesktopBaseNativeImpl<*>
        if (renderChildCallback != null && (
                impl is com.nexustools.genericui.windows.impl.WindowsContainerNativeImpl ||
                impl is com.nexustools.genericui.windows.impl.WindowsStyledContainerNativeImpl ||
                impl is com.nexustools.genericui.windows.impl.WindowsTabContainerNativeImpl ||
                impl is com.nexustools.genericui.windows.impl.WindowsLazyListNativeImpl
            )
        ) {
            return renderChildCallback.invoke(this, widget, left, top, right)
        }
        val childBounds = Rect(left, top, (right - left).coerceAtLeast(0), widget.bounds.height.coerceAtLeast(24))
        val childCtx = withBounds(left, top, right, top + childBounds.height)
        if (impl != null && impl.renderDesktopNode(childCtx, childBounds)) {
            return widget.bounds.bottom.coerceAtLeast(top)
        }
        renderChildCallback?.let { return it.invoke(this, widget, left, top, right) }
        return dispatchWidgetRender(widget, left, top, right)
    }

    /**
     * Dispatches [widget] to the appropriate `Win32*RenderUtil` based on its native implementation or role.
     */
    fun dispatchWidgetRender(
        widget: DesktopNativeWidget,
        left: Int,
        top: Int,
        right: Int
    ): Int {
        if (!widget.visible || widget.disposed || widget in titleBarWidgets) return top
        return when {
            Win32MediaCanvasRenderUtil.canRender(widget) ->
                Win32MediaCanvasRenderUtil.render(this, widget, left, top, right)
            Win32WorkspaceWindowRenderUtil.canRender(widget) ->
                Win32WorkspaceWindowRenderUtil.render(this, widget, left, top, right)
            Win32NavigationRenderUtil.canRender(widget) ->
                Win32NavigationRenderUtil.render(this, widget, left, top, right)
            Win32DataDisplayRenderUtil.canRender(widget) ->
                Win32DataDisplayRenderUtil.render(this, widget, left, top, right)
            Win32ButtonRenderUtil.canRender(widget) ->
                Win32ButtonRenderUtil.render(this, widget, left, top, right)
            Win32ToggleSelectionRenderUtil.canRender(widget) ->
                Win32ToggleSelectionRenderUtil.render(this, widget, left, top, right)
            Win32InputFieldRenderUtil.canRender(widget) ->
                Win32InputFieldRenderUtil.render(this, widget, left, top, right)
            Win32SliderProgressRenderUtil.canRender(widget) ->
                Win32SliderProgressRenderUtil.render(this, widget, left, top, right)
            Win32TextRenderUtil.canRender(widget) ->
                Win32TextRenderUtil.render(this, widget, left, top, right)
            else ->
                Win32ContainerChromeRenderUtil.render(this, widget, left, top, right)
        }
    }

    /**
     * Resolves or creates a cached GDI [HFONT] matching [familyName], [sizePx], [weight],
     * [italic], [underline], and [strikeOut].
     */
    fun obtainFont(
        familyName: String? = null,
        sizePx: Int = 15,
        weight: Int = FW_NORMAL,
        italic: Boolean = false,
        underline: Boolean = false,
        strikeOut: Boolean = false
    ): HFONT? {
        val scaledPx = (sizePx * dpiScale).roundToInt().coerceIn(9, 96)
        val cleanFamily = familyName
            ?.split(',')
            ?.firstOrNull()
            ?.trim()
            ?.trim('"', '\'')
            ?.takeIf { it.isNotEmpty() && !it.equals("inherit", ignoreCase = true) }
            ?: "Segoe UI"

        if (cleanFamily.equals("Segoe UI", ignoreCase = true) && !italic && !underline && !strikeOut) {
            when {
                scaledPx <= 13 && weight >= FW_BOLD && fontSmallBold != null -> return fontSmallBold
                scaledPx <= 13 && fontSmall != null -> return fontSmall
                scaledPx in 14..16 && weight >= FW_BOLD && fontBold != null -> return fontBold
                scaledPx in 14..16 && fontNormal != null -> return fontNormal
                scaledPx in 17..21 && fontTitle != null -> return fontTitle
                scaledPx >= 22 && fontMetricBig != null -> return fontMetricBig
            }
        }

        val key = "$cleanFamily|$scaledPx|$weight|$italic|$underline|$strikeOut"
        customFontsByKey[key]?.let { return it }
        val created = CreateFontW(
            -scaledPx,
            0,
            0,
            0,
            weight.coerceIn(100, 900),
            if (italic) 1u else 0u,
            if (underline) 1u else 0u,
            if (strikeOut) 1u else 0u,
            DEFAULT_CHARSET.toUInt(),
            OUT_DEFAULT_PRECIS.toUInt(),
            0u,
            PROOF_QUALITY.toUInt(),
            (DEFAULT_PITCH or FF_DONTCARE).toUInt(),
            cleanFamily
        )
        if (created != null) {
            customFontsByKey[key] = created
        }
        return created ?: fontNormal
    }

    /**
     * Selects [font] into [hdc] if non-null.
     */
    fun selectFont(font: HFONT?) {
        if (hdc != null && font != null) {
            SelectObject(hdc, font)
        }
    }

    /**
     * Resolves the [RgbaColor] for [bootstrapType] from [themeConfig] (or `null` when
     * [bootstrapType] is `null` or [BootstrapType.NONE]).
     */
    fun resolveBootstrapTintOrNull(bootstrapType: BootstrapType?): RgbaColor? =
        BootstrapColorPalette.resolveTintOrNull(bootstrapType, darkMode, themeConfig)

    /**
     * Converts an [RgbaColor] to a Win32 `COLORREF` (`0x00BBGGRR`).
     */
    fun toColorRef(color: RgbaColor): UInt =
        (color.b.toUInt() shl 16) or (color.g.toUInt() shl 8) or color.r.toUInt()

    /**
     * Converts a Win32 `COLORREF` (`0x00BBGGRR`) to an [RgbaColor].
     */
    fun fromColorRef(colorRef: UInt, alpha: Int = 255): RgbaColor = RgbaColor(
        r = (colorRef and 0xFFu).toInt(),
        g = ((colorRef shr 8) and 0xFFu).toInt(),
        b = ((colorRef shr 16) and 0xFFu).toInt(),
        a = alpha.coerceIn(0, 255)
    )

    /**
     * Returns whether [widget] is currently hovered by the pointer.
     */
    fun isWidgetHovered(widget: DesktopNativeWidget, left: Int, top: Int, right: Int, bottom: Int): Boolean =
        widget.enabled && (
            Win32NativeWindowHost.hoveredWidget === widget ||
                Win32NativeWindowHost.isRectHovered(hwnd, left, top, right, bottom)
            )

    /**
     * Returns whether [widget] is currently in the active pressed state.
     */
    fun isWidgetPressed(widget: DesktopNativeWidget): Boolean =
        widget.enabled && (
            Win32NativeWindowHost.pressedWidget === widget ||
                widget.attributes["activePressed"] == true
            )

    /**
     * Returns visible, non-disposed children of [container] excluding [titleBarWidgets].
     */
    fun visibleChildrenOf(container: DesktopNativeWidget): List<DesktopNativeWidget> =
        container.children.filter { it.visible && !it.disposed && it !in titleBarWidgets }

    /**
     * Releases any temporary custom GDI fonts allocated during this context's lifetime.
     */
    fun disposeCustomFonts() {
        for (font in customFontsByKey.values) {
            DeleteObject(font)
        }
        customFontsByKey.clear()
    }

    /**
     * Helper to populate a Win32 [RECT] inside a `memScoped` block.
     */
    fun withWin32Rect(left: Int, top: Int, right: Int, bottom: Int, block: (RECT) -> Unit) {
        memScoped {
            val rc = alloc<RECT>()
            rc.left = left
            rc.top = top
            rc.right = right
            rc.bottom = bottom
            block(rc)
        }
    }
}
