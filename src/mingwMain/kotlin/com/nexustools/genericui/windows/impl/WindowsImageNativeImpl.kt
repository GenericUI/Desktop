@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.nexustools.genericui.windows.impl

import com.nexustools.genericui.desktop.system.DesktopConfigDirectoryHelper
import com.nexustools.genericui.desktop.widget.DesktopImageNativeImpl
import com.nexustools.genericui.enums.ui.ObjectFit
import com.nexustools.genericui.geometry.Rect
import com.nexustools.genericui.geometry.Size
import com.nexustools.genericui.image.ImageBuffer
import com.nexustools.genericui.image.ImageDecodeOptions
import com.nexustools.genericui.image.ImageFormat
import com.nexustools.genericui.image.ImageLoader
import com.nexustools.genericui.image.ImageSource
import com.nexustools.genericui.image.PixelFormat
import com.nexustools.genericui.image.cache.ImageCacheManager
import com.nexustools.genericui.res.DataUrlCodec
import com.nexustools.genericui.style.color.ImageTintMode
import com.nexustools.genericui.style.color.RgbaColor
import com.nexustools.genericui.style.css.CssCursorValue
import com.nexustools.genericui.threading.GenericUiThreading
import com.nexustools.genericui.windows.Win32ControlMessageRouter
import com.nexustools.genericui.windows.Win32Direct2dBackdropBlurEngine
import com.nexustools.genericui.windows.Win32HwndHandle
import com.nexustools.genericui.windows.Win32NativeWindowHost
import com.nexustools.genericui.windows.Win32RetainedControlSupport
import com.nexustools.genericui.windows.Win32StyledWidgetHelper
import com.nexustools.genericui.windows.WindowsWicImageCodecNativeImpl
import platform.windows.HDC
import platform.windows.HWND
import platform.windows.InvalidateRect
import platform.windows.IsWindow
import platform.windows.WS_CHILD
import platform.windows.WS_CLIPSIBLINGS
import platform.windows.WS_VISIBLE
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Retained Win32 `"GenericUIImageW"` control rendering decoded [ImageBuffer]s with `ObjectFit`,
 * tinting (`SRC_IN`, `MULTIPLY`, `MONOCOLOR_AUTO_THEME`), WIC/DataURL/Resource decoding, and all
 * 10 CSS `filter` ops cleanly onto double-buffered 32-bpp DIBs.
 */
class WindowsImageNativeImpl(
    widget: Win32HwndHandle = Win32HwndHandle("GenericUIImageW")
) : DesktopImageNativeImpl<Win32HwndHandle>(widget) {

    companion object {
        private val fallbackCodec: WindowsWicImageCodecNativeImpl by lazy {
            WindowsWicImageCodecNativeImpl()
        }
    }

    init {
        nativeWidget.attributes["nativeImpl"] = this
        Win32ControlMessageRouter.createChildHwnd(
            handle = nativeWidget,
            owner = this,
            win32ClassName = "GenericUIImageW",
            dwStyle = WS_CHILD or WS_VISIBLE or WS_CLIPSIBLINGS,
            initialWidth = 64,
            initialHeight = 64,
            installSubclass = false
        )
        nativeWidget.customPaintCallback = { hwnd, hdc, w, h -> paintImageControl(hwnd, hdc, w, h) }
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
    }

    override fun setSourceUrl(url: String?) {
        super.setSourceUrl(url)
        val trimmed = url?.trim()
        val gen = loadGeneration
        if (!trimmed.isNullOrEmpty() && loadedImageBuffer == null) {
            val decoded = tryDecodeSynchronously(trimmed)
            if (decoded != null) {
                if (gen == loadGeneration) {
                    notifyAsyncLoadSucceeded(decoded)
                }
                return
            }
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true)
            ) {
                markLoading(true)
                val decodeOpts = ImageDecodeOptions(
                    preferredPixelFormat = PixelFormat.RGBA_8888,
                    antialiasing = antialiasingState
                )
                activeLoadJob = ImageLoader.launchLoad(
                    source = ImageSource.fromUrl(trimmed),
                    options = decodeOpts,
                    onLoaded = { buf ->
                        GenericUiThreading.runOnUi {
                            if (gen == loadGeneration) {
                                notifyAsyncLoadSucceeded(buf)
                            }
                        }
                    },
                    onError = {
                        GenericUiThreading.runOnUi {
                            if (gen == loadGeneration) {
                                notifyAsyncLoadFailed()
                            }
                        }
                    }
                )
            }
        }
        nativeWidget.hwnd?.let { hwnd ->
            if (IsWindow(hwnd) != 0) InvalidateRect(hwnd, null, 0)
        }
    }

    override fun notifyAsyncLoadSucceeded(buffer: ImageBuffer?) {
        super.notifyAsyncLoadSucceeded(buffer)
        nativeWidget.hwnd?.let { hwnd ->
            if (IsWindow(hwnd) != 0) InvalidateRect(hwnd, null, 0)
        }
    }

    override fun notifyAsyncLoadFailed() {
        super.notifyAsyncLoadFailed()
        nativeWidget.hwnd?.let { hwnd ->
            if (IsWindow(hwnd) != 0) InvalidateRect(hwnd, null, 0)
        }
    }

    override fun setImageBuffer(buffer: ImageBuffer?) {
        super.setImageBuffer(buffer)
        nativeWidget.hwnd?.let { hwnd ->
            if (IsWindow(hwnd) != 0) InvalidateRect(hwnd, null, 0)
        }
    }

    override fun setTintColor(color: RgbaColor?) {
        super.setTintColor(color)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun setTintMode(mode: ImageTintMode) {
        super.setTintMode(mode)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun setObjectFit(fit: ObjectFit) {
        super.setObjectFit(fit)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    override fun setCssFilter(filter: com.nexustools.genericui.style.css.CssFilter?) {
        super.setCssFilter(filter)
        nativeWidget.hwnd?.let { InvalidateRect(it, null, 0) }
    }

    private fun decodeWithActiveOrFallback(source: ImageSource, options: ImageDecodeOptions): ImageBuffer? {
        val active = ImageLoader.activeNativeCodec
        return active?.decode(source, options)
            ?: if (active !is WindowsWicImageCodecNativeImpl) {
                fallbackCodec.decode(source, options)
            } else {
                null
            }
    }

    private fun tryDecodeSynchronously(url: String): ImageBuffer? {
        val options = ImageDecodeOptions(
            preferredPixelFormat = PixelFormat.RGBA_8888,
            antialiasing = antialiasingState
        )
        if (url.startsWith("memory://", ignoreCase = true)) {
            ImageLoader.resolveMemoryBuffer(url)?.let { return it }
        }
        ImageLoader.getWeakImageReference(url)?.let { return it }
        ImageCacheManager.getMemoryCached(url)?.let { return it }

        return when {
            url.startsWith("data:", ignoreCase = true) -> {
                val bytes = DataUrlCodec.decodeBytes(url) ?: return null
                decodeWithActiveOrFallback(
                    ImageSource.fromBytes(bytes, ImageFormat.detectFromHeader(bytes)),
                    options
                )
            }
            url.startsWith("res://", ignoreCase = true) || url.startsWith("drawable/", ignoreCase = true) -> {
                val bytes = DesktopConfigDirectoryHelper.readResourceOrLocalFile(url)
                    ?: Win32NativeWindowHost.resolveGenericUiIconBytes(url)
                    ?: return null
                decodeWithActiveOrFallback(
                    ImageSource.fromBytes(bytes, ImageFormat.detectFromHeader(bytes)),
                    options
                )
            }
            !url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true) -> {
                val resolvedPath = DesktopConfigDirectoryHelper.resolveLocalAssetFilePath(url)
                if (resolvedPath != null) {
                    val fileBytes = DesktopConfigDirectoryHelper.readBytesFromFile(resolvedPath)
                    if (fileBytes != null && fileBytes.isNotEmpty()) {
                        return decodeWithActiveOrFallback(
                            ImageSource.fromBytes(fileBytes, ImageFormat.detectFromHeader(fileBytes)),
                            options
                        )
                    }
                    decodeWithActiveOrFallback(ImageSource.fromFile(resolvedPath), options)
                } else {
                    decodeWithActiveOrFallback(
                        ImageSource.fromFile(url.removePrefix("file:///").removePrefix("file://")),
                        options
                    )
                }
            }
            else -> null
        }
    }

    private fun paintImageControl(hwnd: HWND, hdc: HDC, width: Int, height: Int) {
        val isDark = Win32NativeWindowHost.isEffectivelyDarkTheme()
        val dstPixels = IntArray(width * height)
        Win32StyledWidgetHelper.captureBackdropIntoBuffer(hwnd, 0, 0, width, height, dstPixels, isDark)

        val buf = loadedImageBuffer
        if (buf == null || buf.width <= 0 || buf.height <= 0) {
            Win32StyledWidgetHelper.blitArgbArrayToHdc(hdc, width, height, dstPixels, premultiplied = false)
            return
        }

        val rgba = buf.asRgba8888()
        val srcW = rgba.width
        val srcH = rgba.height
        val srcBytes = rgba.bytes

        val drawRect = computeFitRect(width, height, srcW, srcH, objectFitState)
        val dx0 = drawRect[0].coerceIn(0, width)
        val dy0 = drawRect[1].coerceIn(0, height)
        val dx1 = (drawRect[0] + drawRect[2]).coerceIn(dx0, width)
        val dy1 = (drawRect[1] + drawRect[3]).coerceIn(dy0, height)
        val dw = drawRect[2].coerceAtLeast(1)
        val dh = drawRect[3].coerceAtLeast(1)

        val effTintMode = tintModeState
        val tintCol = tintColorState
            ?: if (effTintMode == ImageTintMode.MONOCOLOR_AUTO_THEME && isMonoColorRgba(srcBytes, srcW, srcH)) {
                if (isDark) RgbaColor(222, 226, 230) else RgbaColor(33, 37, 41)
            } else {
                null
            }

        for (py in dy0 until dy1) {
            val v = (py - drawRect[1]).toFloat() / dh.toFloat()
            val sy = (v * srcH).toInt().coerceIn(0, srcH - 1)
            val dstRow = py * width
            val srcRow = sy * srcW * 4
            for (px in dx0 until dx1) {
                val u = (px - drawRect[0]).toFloat() / dw.toFloat()
                val sx = (u * srcW).toInt().coerceIn(0, srcW - 1)
                val sOff = srcRow + sx * 4
                var sr = srcBytes[sOff].toInt() and 0xFF
                var sg = srcBytes[sOff + 1].toInt() and 0xFF
                var sb = srcBytes[sOff + 2].toInt() and 0xFF
                var sa = srcBytes[sOff + 3].toInt() and 0xFF
                if (sa <= 0) continue

                if (tintCol != null) {
                    when (effTintMode) {
                        ImageTintMode.SRC_IN, ImageTintMode.MONOCOLOR_AUTO_THEME -> {
                            sr = tintCol.r; sg = tintCol.g; sb = tintCol.b
                            sa = (sa * tintCol.a + 127) / 255
                        }
                        ImageTintMode.MULTIPLY -> {
                            sr = (sr * tintCol.r + 127) / 255
                            sg = (sg * tintCol.g + 127) / 255
                            sb = (sb * tintCol.b + 127) / 255
                            sa = (sa * tintCol.a + 127) / 255
                        }
                        ImageTintMode.NONE -> Unit
                    }
                }
                val idx = dstRow + px
                if (sa >= 255) {
                    dstPixels[idx] = (0xFF shl 24) or (sr shl 16) or (sg shl 8) or sb
                } else {
                    val dst = dstPixels[idx]
                    val dr = (dst ushr 16) and 0xFF
                    val dg = (dst ushr 8) and 0xFF
                    val db = dst and 0xFF
                    val invA = 255 - sa
                    val outR = (sr * sa + dr * invA + 127) / 255
                    val outG = (sg * sa + dg * invA + 127) / 255
                    val outB = (sb * sa + db * invA + 127) / 255
                    dstPixels[idx] = (0xFF shl 24) or (outR shl 16) or (outG shl 8) or outB
                }
            }
        }

        val filter = cssFilterState
        if (filter != null && filter.hasActiveEffects) {
            Win32Direct2dBackdropBlurEngine.applyBackdropToArgbArray(
                filter = filter,
                argbPixels = dstPixels,
                bufferWidth = width,
                bufferHeight = height,
                regionX = 0,
                regionY = 0,
                regionWidth = width,
                regionHeight = height
            )
        }
        Win32StyledWidgetHelper.blitArgbArrayToHdc(hdc, width, height, dstPixels, premultiplied = false)
    }

    private fun isMonoColorRgba(bytes: ByteArray, w: Int, h: Int): Boolean {
        var refR = -1
        var refG = -1
        var refB = -1
        val limit = min(bytes.size, w * h * 4)
        var i = 0
        while (i + 3 < limit) {
            val a = bytes[i + 3].toInt() and 0xFF
            if (a > 24) {
                val r = bytes[i].toInt() and 0xFF
                val g = bytes[i + 1].toInt() and 0xFF
                val b = bytes[i + 2].toInt() and 0xFF
                if (refR < 0) {
                    refR = r; refG = g; refB = b
                } else if (kotlin.math.abs(r - refR) > 24 || kotlin.math.abs(g - refG) > 24 || kotlin.math.abs(b - refB) > 24) {
                    return false
                }
            }
            i += 4
        }
        return refR >= 0
    }

    private fun computeFitRect(cw: Int, ch: Int, sw: Int, sh: Int, fit: ObjectFit): IntArray {
        if (fit == ObjectFit.FILL || sw <= 0 || sh <= 0) {
            return intArrayOf(0, 0, cw, ch)
        }
        if (fit == ObjectFit.NONE) {
            return intArrayOf((cw - sw) / 2, (ch - sh) / 2, sw, sh)
        }
        val scaleX = cw.toFloat() / sw.toFloat()
        val scaleY = ch.toFloat() / sh.toFloat()
        val scale = when (fit) {
            ObjectFit.COVER -> max(scaleX, scaleY)
            ObjectFit.SCALE_DOWN -> min(1f, min(scaleX, scaleY))
            else -> min(scaleX, scaleY)
        }
        val dw = (sw * scale).roundToInt().coerceAtLeast(1)
        val dh = (sh * scale).roundToInt().coerceAtLeast(1)
        return intArrayOf((cw - dw) / 2, (ch - dh) / 2, dw, dh)
    }

    override fun measureIntrinsicSize(maxWidth: Float?, maxHeight: Float?): Size {
        val buf = loadedImageBuffer
        if (buf != null && buf.width > 0 && buf.height > 0) {
            return Size(buf.width, buf.height)
        }
        return Size(32, 32)
    }

    override fun onNativeBoundsChanged(rect: Rect) {
        Win32RetainedControlSupport.onLeafBoundsChanged(nativeWidget, rect)
    }

    override fun onNativeVisibilityChanged(visible: Boolean) {
        Win32RetainedControlSupport.onVisibilityChanged(nativeWidget, visible)
    }

    override fun onNativeEnabledChanged(enabled: Boolean) {
        Win32RetainedControlSupport.onEnabledChanged(nativeWidget, enabled)
    }

    override fun onNativeOpacityChanged(opacity: Float) {
        Win32RetainedControlSupport.onOpacityChanged(nativeWidget, opacity)
    }

    override fun onNativeTooltipChanged(tooltip: String?) {
        Win32RetainedControlSupport.onTooltipChanged(nativeWidget, tooltip)
    }

    override fun onNativeCursorChanged(cursor: CssCursorValue?) {
        Win32RetainedControlSupport.onCursorChanged(nativeWidget, cursor)
    }

    override fun onNativePointerEventsChanged(pointerEventsNone: Boolean) {
        Win32RetainedControlSupport.onPointerEventsChanged(nativeWidget, pointerEventsNone)
    }

    override fun onNativeZIndexChanged(zIndex: Int) {
        Win32RetainedControlSupport.onZIndexChanged(nativeWidget, zIndex)
    }

    override fun onNativeStyleSynced() {
        Win32RetainedControlSupport.onStyleSynced(this, nativeWidget, defaultTransparentBg = true)
    }

    override fun dispose() {
        Win32RetainedControlSupport.onDispose(nativeWidget)
        super.dispose()
    }
}
