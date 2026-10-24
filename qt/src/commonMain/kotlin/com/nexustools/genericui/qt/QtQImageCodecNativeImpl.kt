package com.nexustools.genericui.qt

import com.nexustools.genericui.desktop.image.DesktopLibJpegImageCodecImpl

/**
 * Qt 6 native image loader & codec implementation backed by `QImage`, `QImageReader`, and `QImageWriter`
 * alongside direct `libjpeg-turbo`, `libpng`, and `libwebp` scanline hooks.
 */
class QtQImageCodecNativeImpl : DesktopLibJpegImageCodecImpl(
    codecBackendName = "Qt6 QImage / QImageReader / QImageWriter + libjpeg-turbo/libpng/libwebp"
)
