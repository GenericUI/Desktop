package com.nexustools.genericui.swing

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.nativeimpl.action.ButtonNativeImpl
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import com.nexustools.genericui.style.theme.SystemThemeDetector
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JComponent

/**
 * Java Swing `JButton` implementation of [ButtonNativeImpl].
 *
 * - When [BootstrapType] is [BootstrapType.NONE] (or `null`) and not a [SwingStyledButtonImpl],
 *   renders the 100% unmodified Swing system Look-And-Feel `JButton` (`isContentAreaFilled = true`,
 *   `isBorderPainted = true`, default UI delegate).
 * - When [BootstrapType] is non-`NONE` and not a [SwingStyledButtonImpl], renders the native Swing
 *   `JButton` with empty text (`""`) and no icon into an offscreen [BufferedImage], color-tints the
 *   offscreen ARGB pixels using [BootstrapColorPalette.resolveColor] from the active `ThemeConfig`
 *   while preserving native button bevels, borders, and hover/pressed/disabled shading, draws the
 *   tinted [BufferedImage] onto `Graphics2D`, and draws contrasting text and icon on top afterward.
 */
open class SwingButtonImpl(
    classPrefix: String = "btn",
    button: JButton = SwingControlTintRenderUtil.createNativeOrTintedButton()
) : SwingBaseNativeImpl(button, classPrefix), ButtonNativeImpl<JComponent> {
    override val nativeWidget: JButton get() = super.nativeWidget as JButton
    private var iconVal: String? = null

    init {
        nativeWidget.addActionListener {
            onClickListener?.invoke()
        }
        syncSwingAppearance()
    }

    override fun syncSwingAppearance() {
        super.syncSwingAppearance()
        if (this !is SwingStyledButtonImpl) {
            nativeWidget.putClientProperty("backdropBlurPx", null)
            nativeWidget.putClientProperty("backdrop-filter", null)
            nativeWidget.putClientProperty("-webkit-backdrop-filter", null)
            nativeWidget.putClientProperty("backgroundGradient", null)
            val bType = getType() ?: BootstrapType.NONE
            nativeWidget.isContentAreaFilled = true
            nativeWidget.isBorderPainted = true
            nativeWidget.putClientProperty("bootstrapType", bType)
            nativeWidget.putClientProperty("bootstrapOutline", isOutline())
            nativeWidget.putClientProperty("activeThemeConfig", BootstrapColorPalette.activeThemeConfig)
            nativeWidget.repaint()
        }
    }

    /**
     * Renders the empty-text tinted offscreen [BufferedImage] sprite for this button when
     * [getType] is non-`NONE`, or the unmodified native `JButton` surface when `NONE`.
     */
    fun renderOffscreenButtonSprite(
        width: Int = nativeWidget.width.takeIf { it > 0 } ?: 120,
        height: Int = nativeWidget.height.takeIf { it > 0 } ?: 36,
        includeTextOverlay: Boolean = true
    ): BufferedImage {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val bType = getType() ?: BootstrapType.NONE
        val isDark = (Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()) == ThemeMode.DARK
        val themeConfig = BootstrapColorPalette.activeThemeConfig
        if (this !is SwingStyledButtonImpl && bType != BootstrapType.NONE) {
            val sprite = SwingControlTintRenderUtil.renderEmptyTextTintedButtonSprite(
                button = nativeWidget,
                width = w,
                height = h,
                type = bType,
                outline = isOutline(),
                isDark = isDark,
                themeConfig = themeConfig
            )
            if (includeTextOverlay) {
                val g2 = sprite.createGraphics()
                try {
                    val fgRgba = SwingControlTintRenderUtil.resolveButtonForeground(
                        type = bType,
                        outline = isOutline(),
                        isDark = isDark,
                        themeConfig = themeConfig
                    )
                    SwingControlTintRenderUtil.drawContrastingTextAndIconOverlay(
                        g2 = g2,
                        button = nativeWidget,
                        width = w,
                        height = h,
                        fgColor = SwingControlTintRenderUtil.toAwtColor(fgRgba, !nativeWidget.isEnabled),
                        pressed = false
                    )
                } finally {
                    g2.dispose()
                }
            }
            return sprite
        }
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g2 = image.createGraphics()
        try {
            val prevW = nativeWidget.width
            val prevH = nativeWidget.height
            if (prevW <= 0 || prevH <= 0) {
                nativeWidget.setSize(w, h)
            }
            nativeWidget.paint(g2)
        } finally {
            g2.dispose()
        }
        return image
    }

    override fun setBackgroundGradient(gradient: Gradient?) {
        if (this is SwingStyledButtonImpl) {
            super<SwingBaseNativeImpl>.setBackgroundGradient(gradient)
        } else {
            super<SwingBaseNativeImpl>.setBackgroundGradient(null)
            nativeWidget.putClientProperty("backgroundGradient", null)
        }
    }

    override fun getBackgroundGradient(): Gradient? =
        if (this is SwingStyledButtonImpl) super<SwingBaseNativeImpl>.getBackgroundGradient() else null

    override fun setText(text: String) {
        nativeWidget.text = text
        nativeWidget.repaint()
    }

    override fun getText(): String = nativeWidget.text ?: ""

    override fun setIcon(icon: String?) {
        iconVal = icon
        nativeWidget.putClientProperty("icon", icon)
        nativeWidget.repaint()
    }

    override fun getIcon(): String? = iconVal
}
