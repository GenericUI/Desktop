package com.nexustools.genericui.windows

import com.nexustools.genericui.desktop.image.DesktopLibJpegImageCodecImpl

/**
 * Windows native image loader & codec implementation backed by Windows Imaging Component
 * (`IWICImagingFactory`, `IWICBitmapDecoder`, `IWICFormatConverter`) alongside direct
 * `libjpeg-turbo`, `libpng`, and `libwebp` scanline hooks.
 */
class WindowsWicImageCodecNativeImpl : DesktopLibJpegImageCodecImpl(
    codecBackendName = "Windows WIC (IWICImagingFactory) + libjpeg-turbo/libpng/libwebp"
)
