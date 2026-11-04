package com.nexustools.genericui.swing

import com.nexustools.genericui.enums.ui.ObjectFit
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.nativeimpl.content.ImageNativeImpl
import com.nexustools.genericui.style.color.ImageTintMode
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssFilter
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.SwingUtilities
import kotlinx.coroutines.Job

/**
 * Java Swing `JLabel` + `ImageIcon` / `BufferedImage` implementation of [ImageNativeImpl].
 *
 * Uses Kotlin coroutines via [ImageLoader.launchLoad] for non-blocking image loading, supports
 * placeholder loading images, and enables antialiased bicubic interpolation by default.
 */
class SwingImageImpl : SwingBaseNativeImpl(
    object : JLabel() {
        override fun paintComponent(g: Graphics?) {
            val g2 = g as? Graphics2D
            if (g2 != null) {
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
            }
            super.paintComponent(g)
        }
    },
    "img-fluid"
), ImageNativeImpl<JComponent> {
    override val nativeWidget: JLabel = super.nativeWidget as JLabel

    private var sourceUrlVal: String? = null
    private var placeholderUrlVal: String? = "res://drawable/loading"
    private var objectFitVal: ObjectFit = ObjectFit.CONTAIN
    private var antialiasingVal: Boolean = true
    private var isLoadingVal: Boolean = false
    private var tintColorVal: RgbaColor? = null
    private var tintModeVal: ImageTintMode = ImageTintMode.NONE
    private var cssFilterVal: CssFilter? = null
    private var onLoadCallback: (() -> Unit)? = null
    private var onErrorCallback: (() -> Unit)? = null
    private var activeLoadJob: Job? = null
    private var retainedBuffer: ImageBuffer? = null

    init {
        nativeWidget.putClientProperty("antialiasing", true)
    }

    override fun setSourceUrl(url: String?) {
        activeLoadJob?.cancel()
        activeLoadJob = null
        sourceUrlVal = url
        nativeWidget.putClientProperty("src", url)
        if (url.isNullOrBlank()) {
            isLoadingVal = false
            retainedBuffer = null
            nativeWidget.putClientProperty("isLoading", false)
            return
        }
        isLoadingVal = true
        nativeWidget.putClientProperty("isLoading", true)
        activeLoadJob = ImageLoader.launchLoad(
            source = ImageSource.fromUrl(url),
            options = ImageDecodeOptions(antialiasing = antialiasingVal),
            onLoaded = { buffer ->
                retainedBuffer = buffer
                isLoadingVal = false
                nativeWidget.putClientProperty("isLoading", false)
                onLoadCallback?.invoke()
                SwingUtilities.invokeLater { nativeWidget.repaint() }
            },
            onError = {
                isLoadingVal = false
                nativeWidget.putClientProperty("isLoading", false)
                onErrorCallback?.invoke()
            }
        )
    }

    override fun getSourceUrl(): String? = sourceUrlVal

    override fun setPlaceholderUrl(url: String?) {
        placeholderUrlVal = url
        nativeWidget.putClientProperty("placeholderUrl", url)
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
        nativeWidget.putClientProperty("fallbackText", fallback)
    }

    override fun setObjectFit(fit: ObjectFit) {
        objectFitVal = fit
        nativeWidget.putClientProperty("objectFit", fit.name)
    }

    override fun getObjectFit(): ObjectFit = objectFitVal

    override fun setTintColor(color: RgbaColor?) {
        tintColorVal = color
        nativeWidget.putClientProperty("tintColor", color)
    }

    override fun getTintColor(): RgbaColor? = tintColorVal

    override fun setTintMode(mode: ImageTintMode) {
        tintModeVal = mode
        nativeWidget.putClientProperty("tintMode", mode.name)
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
        retainedBuffer = null
        super.dispose()
    }
}
