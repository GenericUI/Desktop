package com.nexustools.genericui.desktop.widget

import com.nexustools.genericui.desktop.core.DesktopBaseNativeImpl
import com.nexustools.genericui.desktop.core.DesktopNativeWidget
import com.nexustools.genericui.enums.ui.ObjectFit
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.nativeimpl.content.ImageNativeImpl
import com.nexustools.genericui.style.color.ImageTintMode
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssFilter
import kotlinx.coroutines.Job

/**
 * Shared desktop base implementation of [ImageNativeImpl].
 *
 * Supports placeholder loading images ([placeholderUrlState]), antialiased scaling by default
 * ([antialiasingState] == `true`), and holds its decoded [loadedImageBuffer] only while mounted
 * so [com.nexustools.genericui.image.ImageLoader]'s [com.nexustools.genericui.util.WeakReference]
 * cache automatically reclaims memory when the component is unloaded or disposed.
 */
open class DesktopImageNativeImpl<out W : DesktopNativeWidget>(
    nativeWidget: W
) : DesktopBaseNativeImpl<W>(nativeWidget, "img"), ImageNativeImpl<W> {

    protected var sourceUrlState: String? = null
    protected var placeholderUrlState: String? = "res://drawable/loading"
    protected var fallbackTextState: String? = null
    protected var objectFitState: ObjectFit = ObjectFit.CONTAIN
    protected var antialiasingState: Boolean = true
    protected var loadingState: Boolean = false
    protected var tintColorState: RgbaColor? = null
    protected var tintModeState: ImageTintMode = ImageTintMode.NONE
    protected var cssFilterState: CssFilter? = null
    protected var onLoadListener: (() -> Unit)? = null
    protected var onErrorListener: (() -> Unit)? = null
    protected var loadGeneration: Int = 0

    var loadedImageBuffer: ImageBuffer? = null
    var activeLoadJob: Job? = null

    init {
        nativeWidget.attributes["antialiasing"] = true
    }

    override fun setSourceUrl(url: String?) {
        val trimmed = url?.trim()
        if (!trimmed.isNullOrEmpty() && trimmed.startsWith("memory://", ignoreCase = true)) {
            ImageLoader.resolveMemoryBuffer(trimmed)?.let {
                setImageBuffer(it)
                return
            }
        }
        if (sourceUrlState != url) {
            loadGeneration++
            activeLoadJob?.cancel()
            activeLoadJob = null
            loadedImageBuffer = null
            nativeWidget.attributes.remove("imageBuffer")
        }
        sourceUrlState = url
        nativeWidget.attributes["sourceUrl"] = url
        if (!url.isNullOrBlank()) {
            onLoadListener?.invoke()
        } else {
            loadingState = false
            nativeWidget.attributes["isLoading"] = false
        }
    }

    override fun getSourceUrl(): String? = sourceUrlState

    override fun setImageBuffer(buffer: ImageBuffer?) {
        loadGeneration++
        activeLoadJob?.cancel()
        activeLoadJob = null
        if (buffer == null) {
            sourceUrlState = null
            loadedImageBuffer = null
            loadingState = false
            nativeWidget.attributes["sourceUrl"] = null
            nativeWidget.attributes.remove("imageBuffer")
            nativeWidget.attributes["isLoading"] = false
            notifyTreeInvalidated()
            return
        }
        val memoryUri = "memory://imagebuffer/${buffer.width}x${buffer.height}@${buffer.hashCode()}"
        ImageLoader.registerMemoryBuffer(memoryUri, buffer)
        sourceUrlState = memoryUri
        loadedImageBuffer = buffer
        loadingState = false
        nativeWidget.attributes["sourceUrl"] = memoryUri
        nativeWidget.attributes["imageBuffer"] = buffer
        nativeWidget.attributes["isLoading"] = false
        notifyTreeInvalidated()
        onLoadListener?.invoke()
    }

    override fun setPlaceholderUrl(url: String?) {
        placeholderUrlState = url
        nativeWidget.attributes["placeholderUrl"] = url
    }

    override fun getPlaceholderUrl(): String? = placeholderUrlState

    override fun setAntialiasing(enabled: Boolean) {
        antialiasingState = enabled
        nativeWidget.attributes["antialiasing"] = enabled
    }

    override fun isAntialiasing(): Boolean = antialiasingState

    override fun isLoading(): Boolean = loadingState

    fun markLoading(loading: Boolean) {
        loadingState = loading
        nativeWidget.attributes["isLoading"] = loading
    }

    fun notifyAsyncLoadSucceeded(buffer: ImageBuffer?) {
        loadingState = false
        loadedImageBuffer = buffer
        nativeWidget.attributes["isLoading"] = false
        onLoadListener?.invoke()
    }

    fun notifyAsyncLoadFailed() {
        loadingState = false
        nativeWidget.attributes["isLoading"] = false
        onErrorListener?.invoke()
    }

    override fun setFallbackText(fallback: String?) {
        fallbackTextState = fallback
        nativeWidget.attributes["fallbackText"] = fallback
    }

    override fun setObjectFit(fit: ObjectFit) {
        objectFitState = fit
        nativeWidget.attributes["objectFit"] = fit
    }

    override fun getObjectFit(): ObjectFit = objectFitState

    override fun setTintColor(color: RgbaColor?) {
        tintColorState = color
        nativeWidget.attributes["tintColor"] = color
    }

    override fun getTintColor(): RgbaColor? = tintColorState

    override fun setTintMode(mode: ImageTintMode) {
        tintModeState = mode
        nativeWidget.attributes["tintMode"] = mode.name
    }

    override fun getTintMode(): ImageTintMode = tintModeState

    override fun setCssFilter(filter: CssFilter?) {
        cssFilterState = filter
        if (filter != null && !filter.isNone) {
            nativeWidget.attributes["cssFilter"] = filter
        } else {
            nativeWidget.attributes.remove("cssFilter")
        }
    }

    override fun getCssFilter(): CssFilter? = cssFilterState

    override fun setOnLoad(listener: (() -> Unit)?) {
        onLoadListener = listener
        if (listener != null && !sourceUrlState.isNullOrBlank()) {
            listener.invoke()
        }
    }

    override fun setOnError(listener: (() -> Unit)?) {
        onErrorListener = listener
    }

    override fun setAttribute(name: String, value: String?) {
        super<DesktopBaseNativeImpl>.setAttribute(name, value)
        if (name == "src") {
            setSourceUrl(value)
        } else if (name == "placeholder") {
            setPlaceholderUrl(value)
        }
    }

    override fun dispose() {
        activeLoadJob?.cancel()
        activeLoadJob = null
        loadedImageBuffer = null
        super.dispose()
    }
}
