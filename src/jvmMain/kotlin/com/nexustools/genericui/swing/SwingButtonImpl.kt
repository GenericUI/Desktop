package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.nativeimpl.action.ButtonNativeImpl
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.gradient.Gradient.Gradient
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
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
    private var focusedStateVal: Boolean = false

    init {
        nativeWidget.addActionListener {
            onClickListener?.invoke()
        }
        if (this !is SwingStyledButtonImpl) {
            nativeWidget.addFocusListener(object : FocusAdapter() {
                override fun focusGained(e: FocusEvent?) {
                    val effective = isEnabled() && isEffectivelyFocusableInSwing()
                    focusedStateVal = effective
                    if (!effective) {
                        clearAwtFocusOnEdtIfOwner(nativeWidget)
                    }
                }

                override fun focusLost(e: FocusEvent?) {
                    focusedStateVal = false
                }
            })
        }
        syncSwingAppearance()
    }

    override fun setFocusedState(focused: Boolean) {
        val effective = focused && isEnabled() && isEffectivelyFocusableInSwing()
        focusedStateVal = effective
        if (effective) {
            requestAwtFocusOnEdt(nativeWidget)
        } else {
            clearAwtFocusOnEdtIfOwner(nativeWidget)
        }
    }

    override fun isFocusedState(): Boolean = focusedStateVal || nativeWidget.isFocusOwner

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
    ): BufferedImage = SwingControlTintRenderUtil.renderButtonOffscreenSprite(
        button = nativeWidget,
        width = width,
        height = height,
        type = getType() ?: BootstrapType.NONE,
        outline = isOutline(),
        allowTinted = this !is SwingStyledButtonImpl,
        includeTextOverlay = includeTextOverlay
    )

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
        applyResolvedTypographyFont()
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
