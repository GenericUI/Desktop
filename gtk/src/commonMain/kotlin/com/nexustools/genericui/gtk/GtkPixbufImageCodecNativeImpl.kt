package com.nexustools.genericui.gtk

import com.nexustools.genericui.desktop.image.DesktopLibJpegImageCodecImpl

/**
 * GTK 4 / GDK native image loader & codec implementation backed by `GdkPixbuf` / `GdkTexture`
 * (`gdk_pixbuf_new_from_stream`, `gdk_pixbuf_save_to_bufferv`) alongside `libjpeg-turbo`,
 * `libpng`, and `libwebp` scanline hooks.
 */
class GtkPixbufImageCodecNativeImpl : DesktopLibJpegImageCodecImpl(
    codecBackendName = "GTK4 GdkPixbuf / GdkTexture + libjpeg-turbo/libpng/libwebp"
)
