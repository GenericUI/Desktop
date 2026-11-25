package com.nexustools.genericui.swing

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.enums.bootstrap.BootstrapType
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.jvm.JvmControlTintPixelUtil
import com.nexustools.genericui.style.color.BootstrapColorPalette
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.theme.SystemThemeDetector
import com.nexustools.genericui.style.theme.ThemeConfig
import kotlinx.coroutines.Job
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.SwingUtilities

/**
 * Utility for rendering native Swing Look-And-Feel controls (`JButton`) and applying
 * empty-text offscreen native sprite capture + [JvmControlTintPixelUtil] pixel tinting
 * + contrasting text/icon overlay when `BootstrapType != BootstrapType.NONE`.
 */
object SwingControlTintRenderUtil {

    fun createNativeOrTintedButton(): JButton = object : JButton() {
        private var paintingOffscreenNativeSprite: Boolean = false

        override fun contains(x: Int, y: Int): Boolean {
            val impl = getClientProperty(SwingBaseNativeImpl.CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl ?: return super.contains(x, y)
            return impl.hitTestContains(this, x, y, super.contains(x, y))
        }

        override fun paintComponent(g: Graphics) {
            if (paintingOffscreenNativeSprite) {
                super.paintComponent(g)
                return
            }

            val componentOpacity = ((getClientProperty("opacity") as? Float) ?: 1f).coerceIn(0f, 1f)
            if (componentOpacity <= SwingSurfacePaintUtil.OPACITY_SKIP_THRESHOLD) return

            val type = (getClientProperty("bootstrapType") as? BootstrapType)
                ?.takeIf { it != BootstrapType.NONE }

            if (type == null) {
                if (!isContentAreaFilled) isContentAreaFilled = true
                if (!isBorderPainted) isBorderPainted = true
                if (componentOpacity < 0.999f) {
                    val alphaG2 = g.create() as? Graphics2D
                    if (alphaG2 != null) {
                        try {
                            alphaG2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, componentOpacity)
                            super.paintComponent(alphaG2)
                        } finally {
                            alphaG2.dispose()
                        }
                        return
                    }
                }
                super.paintComponent(g)
                return
            }

            val w = width.coerceAtLeast(1)
            val h = height.coerceAtLeast(1)
            val isDark = resolveIsDark(this)
            val outline = (getClientProperty("bootstrapOutline") as? Boolean) ?: false
            val hovered = model?.isRollover == true
            val pressed = (model?.isArmed == true && model?.isPressed == true) || model?.isSelected == true
            val disabled = !isEnabled

            val offscreen = renderEmptyTextTintedButtonSprite(
                button = this,
                width = w,
                height = h,
                type = type,
                outline = outline,
                isDark = isDark,
                hovered = hovered,
                pressed = pressed,
                disabled = disabled,
                paintEmptyNativeDelegate = { emptyG2 ->
                    paintingOffscreenNativeSprite = true
                    try {
                        super.paintComponent(emptyG2)
                    } finally {
                        paintingOffscreenNativeSprite = false
                    }
                }
            )
            putClientProperty("lastTintedSprite", offscreen)

            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                if (componentOpacity < 0.999f) {
                    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, componentOpacity)
                }

                g2.drawImage(offscreen, 0, 0, null)

                val fgRgba = resolveButtonForeground(
                    type = type,
                    outline = outline,
                    isDark = isDark,
                    hovered = hovered,
                    pressed = pressed
                )
                drawContrastingTextAndIconOverlay(
                    g2 = g2,
                    button = this,
                    width = w,
                    height = h,
                    fgColor = toAwtColor(fgRgba, disabled),
                    pressed = pressed
                )
            } finally {
                g2.dispose()
            }
        }
    }

    fun renderEmptyTextTintedButtonSprite(
        button: JButton,
        width: Int,
        height: Int,
        type: BootstrapType,
        outline: Boolean,
        isDark: Boolean,
        hovered: Boolean = button.model?.isRollover == true,
        pressed: Boolean = (button.model?.isArmed == true && button.model?.isPressed == true),
        disabled: Boolean = !button.isEnabled,
        themeConfig: ThemeConfig = BootstrapColorPalette.activeThemeConfig,
        paintEmptyNativeDelegate: ((Graphics2D) -> Unit)? = null
    ): BufferedImage {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val offscreen = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)

        val savedText = button.text
        val savedIcon = button.icon
        val savedFilled = button.isContentAreaFilled
        val savedBorderPainted = button.isBorderPainted
        val savedWidth = button.width
        val savedHeight = button.height

        try {
            button.text = ""
            button.icon = null
            button.isContentAreaFilled = true
            button.isBorderPainted = true
            if (savedWidth <= 0 || savedHeight <= 0) {
                button.setSize(w, h)
            }
            val offG2 = offscreen.createGraphics()
            try {
                offG2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (paintEmptyNativeDelegate != null) {
                    paintEmptyNativeDelegate(offG2)
                } else {
                    button.ui?.update(offG2, button)
                }
            } finally {
                offG2.dispose()
            }
        } finally {
            button.text = savedText
            button.icon = savedIcon
            button.isContentAreaFilled = savedFilled
            button.isBorderPainted = savedBorderPainted
            if (savedWidth <= 0 || savedHeight <= 0) {
                button.setSize(savedWidth, savedHeight)
            }
        }

        val pixels = IntArray(w * h)
        offscreen.getRGB(0, 0, w, h, pixels, 0, w)

        var hasVisiblePixels = false
        for (px in pixels) {
            if (((px ushr 24) and 0xFF) > 16) {
                hasVisiblePixels = true
                break
            }
        }
        if (!hasVisiblePixels) {
            JvmControlTintPixelUtil.synthesizeFallbackNativeButtonSurface(pixels, w, h, isDark)
        }

        val tintColor = BootstrapColorPalette.resolveColor(type, isDark, themeConfig)
        tintNativeControlPixels(
            pixels = pixels,
            width = w,
            height = h,
            tint = tintColor,
            outlineMode = outline,
            hovered = hovered,
            pressed = pressed,
            disabled = disabled
        )
        offscreen.setRGB(0, 0, w, h, pixels, 0, w)
        return offscreen
    }

    fun resolveButtonForeground(
        type: BootstrapType,
        outline: Boolean,
        isDark: Boolean,
        hovered: Boolean = false,
        pressed: Boolean = false,
        themeConfig: ThemeConfig = BootstrapColorPalette.activeThemeConfig
    ): RgbaColor = JvmControlTintPixelUtil.resolveContrastingForeground(
        type = type,
        darkMode = isDark,
        outline = outline,
        hovered = hovered,
        pressed = pressed,
        themeConfig = themeConfig
    )

    fun tintNativeControlPixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        tint: RgbaColor,
        outlineMode: Boolean = false,
        hovered: Boolean = false,
        pressed: Boolean = false,
        disabled: Boolean = false
    ) {
        JvmControlTintPixelUtil.tintNativeControlPixels(
            pixels = pixels,
            width = width,
            height = height,
            tint = tint,
            outline = outlineMode,
            hovered = hovered,
            pressed = pressed,
            disabled = disabled
        )
    }

    internal fun drawContrastingTextAndIconOverlay(
        g2: Graphics2D,
        button: JButton,
        width: Int,
        height: Int,
        fgColor: Color,
        pressed: Boolean
    ) {
        val rawText = button.text.orEmpty()
        val iconStr = (button.getClientProperty("icon") as? String)?.takeIf { it.isNotBlank() }
        val displayLabel = when {
            iconStr != null && !iconStr.startsWith("res://") && !iconStr.startsWith("http") -> {
                if (rawText.isNotEmpty()) "$iconStr $rawText" else iconStr
            }
            else -> rawText
        }

        if (displayLabel.isEmpty() && button.icon == null) return

        g2.font = button.font
        g2.color = fgColor
        val fm = g2.fontMetrics
        val textW = fm.stringWidth(displayLabel)
        val iconObj = button.icon
        val iconW = iconObj?.iconWidth ?: 0
        val gap = if (iconObj != null && displayLabel.isNotEmpty()) button.iconTextGap else 0
        val totalW = iconW + gap + textW
        var startX = ((width - totalW) / 2).coerceAtLeast(2)
        val shiftY = if (pressed) 1 else 0

        if (iconObj != null) {
            val iconY = ((height - iconObj.iconHeight) / 2) + shiftY
            iconObj.paintIcon(button, g2, startX, iconY)
            startX += iconW + gap
        }

        if (displayLabel.isNotEmpty()) {
            val baselineY = ((height - fm.height) / 2) + fm.ascent + shiftY
            g2.drawString(displayLabel, startX, baselineY)
        }
    }

    internal fun toAwtColor(c: RgbaColor, disabled: Boolean): Color {
        val alpha = if (disabled) (c.a * 165 / 255).coerceIn(0, 255) else c.a.coerceIn(0, 255)
        return Color(c.r.coerceIn(0, 255), c.g.coerceIn(0, 255), c.b.coerceIn(0, 255), alpha)
    }

    internal fun resolveIsDark(component: JButton): Boolean {
        val prop = component.getClientProperty("effectiveThemeMode") as? ThemeMode
        if (prop != null) return prop == ThemeMode.DARK
        val effective = Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()
        return effective == ThemeMode.DARK
    }

    internal fun imageBufferToBufferedImage(
        buffer: ImageBuffer,
        imageType: Int = BufferedImage.TYPE_INT_ARGB
    ): BufferedImage {
        val w = buffer.width.coerceAtLeast(1)
        val h = buffer.height.coerceAtLeast(1)
        val out = BufferedImage(w, h, imageType)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = buffer.getPixel(x, y)
                val argb = ((c.a and 0xFF) shl 24) or
                    ((c.r and 0xFF) shl 16) or
                    ((c.g and 0xFF) shl 8) or
                    (c.b and 0xFF)
                out.setRGB(x, y, argb)
            }
        }
        return out
    }

    internal fun loadScaledIconAsync(
        iconSpec: String,
        targetSizePx: Int,
        onLoaded: (ImageIcon) -> Unit
    ): Job? {
        val trimmed = iconSpec.trim()
        if (trimmed.isEmpty()) return null
        val size = targetSizePx.coerceAtLeast(1)
        if (trimmed.startsWith("memory://", ignoreCase = true)) {
            ImageLoader.resolveMemoryBuffer(trimmed)?.let { buf ->
                val scaled = if (buf.width != size || buf.height != size) {
                    ImageLoader.resize(buf, size, size, antialiasing = true)
                } else {
                    buf
                }
                val icon = ImageIcon(imageBufferToBufferedImage(scaled))
                onLoaded(icon)
                return null
            }
        }
        return ImageLoader.launchLoad(
            source = ImageSource.fromUrl(trimmed),
            options = ImageDecodeOptions(targetWidth = size, targetHeight = size, antialiasing = true),
            onLoaded = { buf ->
                val scaled = if (buf.width != size || buf.height != size) {
                    ImageLoader.resize(buf, size, size, antialiasing = true)
                } else {
                    buf
                }
                val icon = ImageIcon(imageBufferToBufferedImage(scaled))
                if (SwingUtilities.isEventDispatchThread()) {
                    onLoaded(icon)
                } else {
                    SwingUtilities.invokeLater { onLoaded(icon) }
                }
            },
            onError = {}
        )
    }

    fun renderButtonOffscreenSprite(
        button: JButton,
        width: Int = button.width.takeIf { it > 0 } ?: 120,
        height: Int = button.height.takeIf { it > 0 } ?: 36,
        type: BootstrapType = BootstrapType.NONE,
        outline: Boolean = false,
        allowTinted: Boolean = true,
        includeTextOverlay: Boolean = true
    ): BufferedImage {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        val isDark = resolveIsDark(button)
        val themeConfig = BootstrapColorPalette.activeThemeConfig
        if (allowTinted && type != BootstrapType.NONE) {
            val sprite = renderEmptyTextTintedButtonSprite(
                button = button,
                width = w,
                height = h,
                type = type,
                outline = outline,
                isDark = isDark,
                themeConfig = themeConfig
            )
            if (includeTextOverlay) {
                val g2 = sprite.createGraphics()
                try {
                    val fgRgba = resolveButtonForeground(
                        type = type,
                        outline = outline,
                        isDark = isDark,
                        themeConfig = themeConfig
                    )
                    drawContrastingTextAndIconOverlay(
                        g2 = g2,
                        button = button,
                        width = w,
                        height = h,
                        fgColor = toAwtColor(fgRgba, !button.isEnabled),
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
            if (button.width <= 0 || button.height <= 0) {
                button.setSize(w, h)
            }
            button.paint(g2)
        } finally {
            g2.dispose()
        }
        return image
    }
}
