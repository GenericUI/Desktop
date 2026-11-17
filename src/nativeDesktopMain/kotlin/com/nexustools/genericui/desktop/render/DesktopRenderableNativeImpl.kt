package com.nexustools.genericui.desktop.render

import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.style.color.RgbaColor

/**
 * Contract implemented by desktop [com.nexustools.genericui.core.NativeImpl] classes so each
 * component's native implementation owns its measurement, native control rendering, hit-testing,
 * and [com.nexustools.genericui.enums.bootstrap.BootstrapType] tint resolution hooks.
 */
interface DesktopRenderableNativeImpl {
    val nativeWidget: DesktopNativeWidget

    /**
     * Measures the intrinsic desktop size of this component node when given [parentWidthPx] and
     * [parentHeightPx] constraints at [dpiScale]. Returns `null` to fall back to default layout
     * measurement.
     */
    fun measureDesktopNode(
        parentWidthPx: Int,
        parentHeightPx: Int,
        dpiScale: Float = 1f
    ): Size? = null

    /**
     * Renders this component node into the platform-specific [renderContext] at [bounds].
     * Returns `true` when the component handled its own rendering, or `false` to use the
     * platform renderer's default dispatch.
     */
    fun renderDesktopNode(
        renderContext: Any,
        bounds: Rect = nativeWidget.bounds
    ): Boolean = false

    /**
     * Performs component-specific hit testing for pointer coordinate ([x], [y]) against [bounds].
     */
    fun hitTestDesktopNode(
        x: Int,
        y: Int,
        bounds: Rect = nativeWidget.bounds
    ): Boolean =
        nativeWidget.visible &&
            x >= bounds.x &&
            y >= bounds.y &&
            x < bounds.x + bounds.width &&
            y < bounds.y + bounds.height

    /**
     * Returns `true` only when this component is a custom-styled skinnable component
     * (`StyledButton`, `StyledContainer`) or is hosted inside a custom style engine (`WebView`).
     * Standard native OS controls return `false` so aesthetic skin properties on `ComponentStyle`
     * do not replace the native OS control look.
     */
    fun acceptsAestheticSkinStyles(): Boolean = false

    /**
     * Returns `true` when this component honors `ComponentStyle` typography and text color
     * properties (`Label`, `Text`, `Heading`, `Badge`, `Blockquote`, `CodeBlock`, `MarkdownView`,
     * `StyledButton`, `StyledContainer`, or components hosted inside a custom style engine).
     */
    fun acceptsTypographyStyles(): Boolean = false

    /**
     * Returns `true` when this component or one of its ancestors is hosted inside a custom style
     * engine such as `WebView`.
     */
    fun isHostedInCustomStyleEngine(): Boolean = false

    /**
     * Resolves the active [RgbaColor] tint for this native control from its
     * [com.nexustools.genericui.enums.bootstrap.BootstrapType] and active
     * [com.nexustools.genericui.style.theme.ThemeConfig], or `null` when
     * `BootstrapType.NONE` (or unset) so the 100% unmodified native OS control is drawn.
     */
    fun resolveNativeControlTint(darkTheme: Boolean = false): RgbaColor? = null

    /**
     * Resolves the contrasting foreground text/icon [RgbaColor] when a
     * [com.nexustools.genericui.enums.bootstrap.BootstrapType] tint is active, or `null` when the
     * default native OS control text color should be used.
     */
    fun resolveNativeControlForeground(darkTheme: Boolean = false): RgbaColor? = null
}
