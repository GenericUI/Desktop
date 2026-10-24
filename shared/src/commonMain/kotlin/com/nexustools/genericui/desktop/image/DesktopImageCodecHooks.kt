package com.nexustools.genericui.desktop.image

import com.nexustools.genericui.image.ImageLoader

/**
 * Shared desktop codec registration wiring [DesktopLibJpegImageCodecImpl] into
 * [ImageLoader.activeNativeCodec], [ImageLoader.platformDecoderHook], and [ImageLoader.platformEncoderHook]
 * for `:windows` (WIC + libjpeg-turbo), `:macos` (ImageIO CGImageSource + libjpeg-turbo),
 * `:gtk` (GdkPixbuf + libjpeg-turbo), and `:qt` (QImage + libjpeg-turbo).
 */
object DesktopImageCodecHooks {

    var activeDesktopBackend: String = "Desktop"
        private set

    var activeCodec: DesktopLibJpegImageCodecImpl? = null
        private set

    fun install(backendName: String): DesktopLibJpegImageCodecImpl {
        activeDesktopBackend = backendName
        val codec = DesktopLibJpegImageCodecImpl("$backendName Native Image Loader + libjpeg-turbo/libpng/libwebp")
        activeCodec = codec
        ImageLoader.activeNativeCodec = codec
        return codec
    }
}
