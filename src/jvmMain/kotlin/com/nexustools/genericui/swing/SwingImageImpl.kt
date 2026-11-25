package com.nexustools.genericui.swing

import com.nexustools.genericui.component.shell.Application
import com.nexustools.genericui.enums.ui.ObjectFit
import com.nexustools.genericui.enums.ui.ThemeMode
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.MonocolorImageRecolorProcessor
import com.nexustools.genericui.jvm.JvmControlTintPixelUtil
import com.nexustools.genericui.nativeimpl.content.ImageNativeImpl
import com.nexustools.genericui.style.color.ImageTintMode
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssFilter
import com.nexustools.genericui.style.theme.SystemThemeDetector
import java.awt.AlphaComposite
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.ImageIcon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlinx.coroutines.Job

/**
 * Java Swing `JLabel` + `ImageIcon` / `BufferedImage` implementation of [ImageNativeImpl].
 *
 * Supports coroutine-based image loading via [ImageLoader.launchLoad], live placeholder rendering
 * while loading, [ImageTintMode] recoloring via [MonocolorImageRecolorProcessor] and
 * [JvmControlTintPixelUtil], and antialiased bicubic rendering.
 */
class SwingImageImpl : SwingBaseNativeImpl(
    object : JLabel() {
        override fun contains(x: Int, y: Int): Boolean {
            val impl = getClientProperty(CLIENT_PROPERTY_KEY) as? SwingBaseNativeImpl ?: return super.contains(x, y)
            return impl.hitTestContains(this, x, y, super.contains(x, y))
        }

        override fun paintComponent(g: Graphics?) {
            val componentOpacity = ((getClientProperty("opacity") as? Float) ?: 1f).coerceIn(0f, 1f)
            if (componentOpacity <= SwingSurfacePaintUtil.OPACITY_SKIP_THRESHOLD) return
            val g2 = g?.create() as? Graphics2D
            if (g2 != null) {
                try {
                    if (componentOpacity < 0.999f) {
                        g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, componentOpacity)
                    }
                    val aa = getClientProperty("antialiasing") as? Boolean ?: true
                    g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING,
                        if (aa) RenderingHints.VALUE_ANTIALIAS_ON else RenderingHints.VALUE_ANTIALIAS_OFF
                    )
                    g2.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        if (aa) RenderingHints.VALUE_INTERPOLATION_BICUBIC else RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                    )
                    g2.setRenderingHint(
                        RenderingHints.KEY_RENDERING,
                        if (aa) RenderingHints.VALUE_RENDER_QUALITY else RenderingHints.VALUE_RENDER_SPEED
                    )
                    val ownerImpl = getClientProperty(CLIENT_PROPERTY_KEY) as? SwingImageImpl
                    val drawn = if (ownerImpl != null) ownerImpl.paintScaledImageIfReady(g2, this) else false
                    if (!drawn) {
                        super.paintComponent(g2)
                    }
                } finally {
                    g2.dispose()
                }
            } else {
                super.paintComponent(g)
            }
        }
    },
    "img-fluid"
), ImageNativeImpl<JComponent> {
    override val nativeWidget: JLabel get() = super.nativeWidget as JLabel

    private var sourceUrlVal: String? = null
    private var placeholderUrlVal: String? = "res://drawable/loading"
    private var fallbackTextVal: String? = null
    private var objectFitVal: ObjectFit = ObjectFit.CONTAIN
    private var antialiasingVal: Boolean = true
    private var isLoadingVal: Boolean = false
    private var tintColorVal: RgbaColor? = null
    private var tintModeVal: ImageTintMode = ImageTintMode.NONE
    private var cssFilterVal: CssFilter? = null
    private var onLoadCallback: (() -> Unit)? = null
    private var onErrorCallback: (() -> Unit)? = null
    private var activeLoadGeneration: Int = 0
    private var activeLoadJob: Job? = null
    private var activePlaceholderJob: Job? = null
    private var retainedBuffer: ImageBuffer? = null
    var renderedBuffer: ImageBuffer? = null
        private set

    init {
        nativeWidget.putClientProperty("antialiasing", true)
    }

    override fun setSourceUrl(url: String?) {
        val trimmed = url?.trim()
        if (!trimmed.isNullOrEmpty() && trimmed.startsWith("memory://", ignoreCase = true)) {
            ImageLoader.resolveMemoryBuffer(trimmed)?.let {
                setImageBuffer(it)
                return
            }
        }
        val gen = ++activeLoadGeneration
        activeLoadJob?.cancel()
        activeLoadJob = null
        activePlaceholderJob?.cancel()
        activePlaceholderJob = null
        sourceUrlVal = url
        nativeWidget.putClientProperty("src", url)
        if (url.isNullOrBlank()) {
            isLoadingVal = false
            retainedBuffer = null
            renderedBuffer = null
            nativeWidget.putClientProperty("imageBuffer", null)
            nativeWidget.putClientProperty("isLoading", false)
            nativeWidget.icon = null
            nativeWidget.text = fallbackTextVal ?: ""
            nativeWidget.repaint()
            return
        }
        isLoadingVal = true
        retainedBuffer = null
        renderedBuffer = null
        nativeWidget.putClientProperty("imageBuffer", null)
        nativeWidget.putClientProperty("isLoading", true)
        renderPlaceholderWhileLoading(gen)
        activeLoadJob = ImageLoader.launchLoad(
            source = ImageSource.fromUrl(url),
            options = ImageDecodeOptions(antialiasing = antialiasingVal),
            onLoaded = { buffer ->
                if (gen != activeLoadGeneration) return@launchLoad
                activePlaceholderJob?.cancel()
                activePlaceholderJob = null
                retainedBuffer = buffer
                isLoadingVal = false
                nativeWidget.putClientProperty("isLoading", false)
                applyBufferIcon(buffer)
                onLoadCallback?.invoke()
                if (SwingUtilities.isEventDispatchThread()) {
                    nativeWidget.repaint()
                } else {
                    SwingUtilities.invokeLater { nativeWidget.repaint() }
                }
            },
            onError = {
                if (gen != activeLoadGeneration) return@launchLoad
                activePlaceholderJob?.cancel()
                activePlaceholderJob = null
                isLoadingVal = false
                nativeWidget.putClientProperty("isLoading", false)
                if (retainedBuffer == null && !fallbackTextVal.isNullOrEmpty()) {
                    nativeWidget.text = fallbackTextVal
                }
                onErrorCallback?.invoke()
            }
        )
    }

    override fun getSourceUrl(): String? = sourceUrlVal

    override fun setImageBuffer(buffer: ImageBuffer?) {
        activeLoadGeneration++
        activeLoadJob?.cancel()
        activeLoadJob = null
        activePlaceholderJob?.cancel()
        activePlaceholderJob = null
        if (buffer == null) {
            isLoadingVal = false
            sourceUrlVal = null
            retainedBuffer = null
            renderedBuffer = null
            nativeWidget.putClientProperty("src", null)
            nativeWidget.putClientProperty("imageBuffer", null)
            nativeWidget.putClientProperty("isLoading", false)
            nativeWidget.icon = null
            nativeWidget.text = fallbackTextVal ?: ""
            nativeWidget.repaint()
            return
        }
        val memoryUri = "memory://imagebuffer/${buffer.width}x${buffer.height}@${buffer.hashCode()}"
        ImageLoader.registerMemoryBuffer(memoryUri, buffer)
        sourceUrlVal = memoryUri
        isLoadingVal = false
        retainedBuffer = buffer
        nativeWidget.putClientProperty("src", memoryUri)
        nativeWidget.putClientProperty("isLoading", false)
        applyBufferIcon(buffer)
        nativeWidget.repaint()
        onLoadCallback?.invoke()
    }

    override fun setPlaceholderUrl(url: String?) {
        placeholderUrlVal = url
        nativeWidget.putClientProperty("placeholderUrl", url)
        if (isLoadingVal && retainedBuffer == null) {
            renderPlaceholderWhileLoading(activeLoadGeneration)
        }
    }

    override fun getPlaceholderUrl(): String? = placeholderUrlVal

    override fun setAntialiasing(enabled: Boolean) {
        antialiasingVal = enabled
        nativeWidget.putClientProperty("antialiasing", enabled)
        nativeWidget.repaint()
    }

    override fun isAntialiasing(): Boolean = antialiasingVal

    override fun isLoading(): Boolean = isLoadingVal

    override fun setFallbackText(fallback: String?) {
        fallbackTextVal = fallback
        nativeWidget.putClientProperty("fallbackText", fallback)
        if (retainedBuffer == null && sourceUrlVal.isNullOrBlank()) {
            nativeWidget.text = fallback ?: ""
        }
    }

    override fun setObjectFit(fit: ObjectFit) {
        objectFitVal = fit
        nativeWidget.putClientProperty("objectFit", fit.name)
    }

    override fun getObjectFit(): ObjectFit = objectFitVal

    override fun setTintColor(color: RgbaColor?) {
        tintColorVal = color
        nativeWidget.putClientProperty("tintColor", color)
        retainedBuffer?.let {
            applyBufferIcon(it)
            nativeWidget.repaint()
        }
    }

    override fun getTintColor(): RgbaColor? = tintColorVal

    override fun setTintMode(mode: ImageTintMode) {
        tintModeVal = mode
        nativeWidget.putClientProperty("tintMode", mode.name)
        retainedBuffer?.let {
            applyBufferIcon(it)
            nativeWidget.repaint()
        }
    }

    override fun getTintMode(): ImageTintMode = tintModeVal

    override fun setCssFilter(filter: CssFilter?) {
        cssFilterVal = filter
        nativeWidget.putClientProperty("cssFilter", filter)
    }

    override fun getCssFilter(): CssFilter? = cssFilterVal

    override fun setOnLoad(listener: (() -> Unit)?) {
        onLoadCallback = listener
    }

    override fun setOnError(listener: (() -> Unit)?) {
        onErrorCallback = listener
    }

    override fun dispose() {
        activeLoadJob?.cancel()
        activeLoadJob = null
        activePlaceholderJob?.cancel()
        activePlaceholderJob = null
        retainedBuffer = null
        renderedBuffer = null
        super.dispose()
    }

    private fun renderPlaceholderWhileLoading(gen: Int) {
        val placeholder = placeholderUrlVal?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (placeholder.startsWith("memory://", ignoreCase = true)) {
            ImageLoader.resolveMemoryBuffer(placeholder)?.let { buf ->
                if (gen == activeLoadGeneration && isLoadingVal && retainedBuffer == null) {
                    applyDisplayOnlyBuffer(buf)
                }
                return
            }
        }
        if (placeholder == "res://drawable/loading") {
            applyDisplayOnlyBuffer(ImageLoader.createDefaultPlaceholderBuffer(48, 48))
            return
        }
        activePlaceholderJob?.cancel()
        activePlaceholderJob = ImageLoader.launchLoad(
            source = ImageSource.fromUrl(placeholder),
            options = ImageDecodeOptions(antialiasing = antialiasingVal),
            onLoaded = { buf ->
                if (gen == activeLoadGeneration && isLoadingVal && retainedBuffer == null) {
                    applyDisplayOnlyBuffer(buf)
                    if (SwingUtilities.isEventDispatchThread()) {
                        nativeWidget.repaint()
                    } else {
                        SwingUtilities.invokeLater { nativeWidget.repaint() }
                    }
                }
            },
            onError = {}
        )
    }

    private fun resolveEffectiveBuffer(raw: ImageBuffer): ImageBuffer {
        val effectiveMode = if (tintModeVal == ImageTintMode.NONE && tintColorVal != null) {
            ImageTintMode.SRC_IN
        } else {
            tintModeVal
        }
        if (effectiveMode == ImageTintMode.NONE) return raw
        val isDark = (Application.current?.getResolvedTheme() ?: SystemThemeDetector.detectSystemTheme()) == ThemeMode.DARK
        return MonocolorImageRecolorProcessor.processImageBuffer(
            buffer = raw,
            mode = effectiveMode,
            tintColor = tintColorVal,
            isDarkTheme = isDark
        )
    }

    internal var cachedBufferedImage: java.awt.image.BufferedImage? = null

    internal fun paintScaledImageIfReady(g2: Graphics2D, label: JLabel): Boolean {
        val img = cachedBufferedImage ?: (label.icon as? ImageIcon)?.image ?: return false
        val iw = img.getWidth(null)
        val ih = img.getHeight(null)
        val w = label.width
        val h = label.height
        if (iw <= 0 || ih <= 0 || w <= 0 || h <= 0) return false
        when (objectFitVal) {
            ObjectFit.FILL -> {
                g2.drawImage(img, 0, 0, w, h, null)
            }
            ObjectFit.COVER -> {
                val scale = maxOf(w.toDouble() / iw.toDouble(), h.toDouble() / ih.toDouble())
                val dw = (iw * scale).toInt().coerceAtLeast(1)
                val dh = (ih * scale).toInt().coerceAtLeast(1)
                val dx = (w - dw) / 2
                val dy = (h - dh) / 2
                val prevClip = g2.clip
                g2.clipRect(0, 0, w, h)
                g2.drawImage(img, dx, dy, dw, dh, null)
                g2.clip = prevClip
            }
            ObjectFit.NONE -> {
                val dx = (w - iw) / 2
                val dy = (h - ih) / 2
                val prevClip = g2.clip
                g2.clipRect(0, 0, w, h)
                g2.drawImage(img, dx, dy, iw, ih, null)
                g2.clip = prevClip
            }
            ObjectFit.SCALE_DOWN -> {
                val scale = minOf(1.0, w.toDouble() / iw.toDouble(), h.toDouble() / ih.toDouble())
                val dw = (iw * scale).toInt().coerceAtLeast(1)
                val dh = (ih * scale).toInt().coerceAtLeast(1)
                g2.drawImage(img, (w - dw) / 2, (h - dh) / 2, dw, dh, null)
            }
            ObjectFit.CONTAIN -> {
                val scale = minOf(w.toDouble() / iw.toDouble(), h.toDouble() / ih.toDouble())
                val dw = (iw * scale).toInt().coerceAtLeast(1)
                val dh = (ih * scale).toInt().coerceAtLeast(1)
                g2.drawImage(img, (w - dw) / 2, (h - dh) / 2, dw, dh, null)
            }
        }
        return true
    }

    internal fun measureNaturalOrScaledSize(availableWidthPx: Int, explicitHeightPx: Int?): com.nexustools.genericui.geometry.Size {
        val natW = renderedBuffer?.width ?: cachedBufferedImage?.width ?: nativeWidget.icon?.iconWidth ?: 48
        val natH = renderedBuffer?.height ?: cachedBufferedImage?.height ?: nativeWidget.icon?.iconHeight ?: 48
        val safeNatW = natW.coerceAtLeast(1)
        val safeNatH = natH.coerceAtLeast(1)
        if (explicitHeightPx != null && explicitHeightPx > 0) {
            val scaledW = ((safeNatW.toDouble() / safeNatH.toDouble()) * explicitHeightPx).toInt().coerceAtLeast(1)
            return com.nexustools.genericui.geometry.Size(scaledW.coerceAtMost(availableWidthPx.coerceAtLeast(1)), explicitHeightPx)
        }
        if (availableWidthPx in 1 until safeNatW) {
            val scaledH = ((safeNatH.toDouble() / safeNatW.toDouble()) * availableWidthPx).toInt().coerceAtLeast(1)
            return com.nexustools.genericui.geometry.Size(availableWidthPx, scaledH)
        }
        return com.nexustools.genericui.geometry.Size(safeNatW, safeNatH)
    }

    private fun applyBufferIcon(rawBuffer: ImageBuffer) {
        val effective = resolveEffectiveBuffer(rawBuffer)
        renderedBuffer = effective
        val bimg = SwingControlTintRenderUtil.imageBufferToBufferedImage(effective)
        cachedBufferedImage = bimg
        nativeWidget.putClientProperty("imageBuffer", effective)
        nativeWidget.icon = ImageIcon(bimg)
        nativeWidget.text = ""
        invalidateLayoutHierarchy()
    }

    private fun applyDisplayOnlyBuffer(placeholderBuffer: ImageBuffer) {
        val bimg = SwingControlTintRenderUtil.imageBufferToBufferedImage(placeholderBuffer)
        cachedBufferedImage = bimg
        nativeWidget.putClientProperty("placeholderShowing", true)
        nativeWidget.icon = ImageIcon(bimg)
        nativeWidget.text = ""
    }
}
