package com.nexustools.genericui.macos

import com.nexustools.genericui.desktop.image.DesktopLibJpegImageCodecImpl

/**
 * macOS native image loader & codec implementation backed by Apple `ImageIO`
 * (`CGImageSourceCreateWithData`, `CGImageSourceCreateImageAtIndex`, `CGImageDestinationCreateWithData`)
 * alongside `libjpeg-turbo`, `libpng`, and `libwebp` scanline hooks.
 */
class MacosImageIoCodecNativeImpl : DesktopLibJpegImageCodecImpl(
    codecBackendName = "macOS ImageIO (CGImageSource / CGImageDestination) + libjpeg-turbo/libpng/libwebp"
)
